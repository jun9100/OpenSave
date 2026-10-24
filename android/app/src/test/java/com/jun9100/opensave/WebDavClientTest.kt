package com.jun9100.opensave

import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {
    @Test
    fun edenSyncdServiceRootsNormalizeToTheDedicatedCollection() {
        assertEquals(
            "https://nas.example:8788/opensave",
            WebDavClient.normalizeEdenSyncdUrl("https://nas.example:8788"),
        )
        assertEquals(
            "https://nas.example/opensave",
            WebDavClient.normalizeEdenSyncdUrl("https://nas.example/opensave/"),
        )
        assertEquals(
            "https://nas.example/eden/opensave",
            WebDavClient.normalizeEdenSyncdUrl("https://nas.example/eden"),
        )
        assertEquals(
            "https://nas.example:8788/opensave",
            WebDavClient.normalizeConnectionUrl(
                WebDavConfig(url = "https://nas.example:8788", provider = WebDavProvider.EDEN_SYNCD),
            ),
        )
    }

    @Test
    fun existingWebDavConfigurationsRemainUntouched() {
        assertEquals(WebDavProvider.WEBDAV, WebDavProvider.fromStored(null))
        assertEquals(WebDavProvider.WEBDAV, WebDavProvider.fromStored("unknown"))
        assertEquals(
            "https://nas.example/dav/OpenSave",
            WebDavClient.normalizeConnectionUrl(WebDavConfig(url = " https://nas.example/dav/OpenSave/ ")),
        )
        assertTrue(runCatching { WebDavClient.normalizeEdenSyncdUrl("https://nas.example/a/../b") }.isFailure)
        assertTrue(runCatching { WebDavClient.normalizeEdenSyncdUrl("https://user:pass@nas.example") }.isFailure)
    }

    @Test
    fun uploadListDownloadAndDeleteUseStagedPublish() {
        val server = MiniWebDavServer()
        server.start()
        try {
            val config = WebDavConfig(
                enabled = true,
                url = "http://127.0.0.1:${server.port}/dav",
                username = "user",
                password = "pass",
            )
            val client = WebDavClient(config)
            val root = createTempDir(prefix = "opensave-webdav-")
            try {
                val local = File(root, "local.zip").apply { writeText("zip-content") }
                val remoteName = "game__main__snap_1.zip"
                val upload = client.upload(local, remoteName).getOrThrow()
                assertFalse(upload.alreadyExisted)
                assertEquals("zip-content", String(server.files["/dav/$remoteName"]!!))
                assertTrue(server.files.keys.none { it.endsWith(".part") })

                val listed = client.list().getOrThrow()
                assertEquals(listOf(remoteName), listed.map { it.name })

                val downloaded = File(root, "downloaded.zip")
                client.download(remoteName, downloaded).getOrThrow()
                assertEquals("zip-content", downloaded.readText())

                client.delete(remoteName).getOrThrow()
                assertFalse(server.files.containsKey("/dav/$remoteName"))
            } finally {
                root.deleteRecursively()
            }
        } finally {
            server.close()
        }
    }
}

/** Tiny HTTP/1.1 test server so unit tests do not depend on JDK-only modules. */
private class MiniWebDavServer : AutoCloseable {
    val files = ConcurrentHashMap<String, ByteArray>()
    private val socket = ServerSocket(0)
    private var thread: Thread? = null
    val port: Int get() = socket.localPort

    fun start() {
        thread = Thread {
            while (!socket.isClosed) {
                runCatching { socket.accept().use(::handle) }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = URI(parts[1]).path
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        val body = readRequestBody(input, headers)
        if (headers["authorization"] != "Basic dXNlcjpwYXNz") {
            respond(client, 401, ByteArray(0))
            return
        }
        when (method) {
            "PROPFIND" -> {
                val listing = files.entries.joinToString("") { (name, value) ->
                    "<D:response><D:href>$name</D:href><D:propstat><D:prop>" +
                        "<D:getcontentlength>${value.size}</D:getcontentlength>" +
                        "</D:prop></D:propstat></D:response>"
                }
                respond(
                    client,
                    207,
                    "<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">$listing</D:multistatus>".toByteArray(),
                    "Content-Type: application/xml",
                )
            }
            "PUT" -> {
                files[path] = body
                respond(client, 201, ByteArray(0))
            }
            "HEAD" -> {
                val value = files[path]
                if (value == null) respond(client, 404, ByteArray(0))
                else respond(client, 200, ByteArray(0), "Content-Length: ${value.size}")
            }
            "MOVE" -> {
                val destination = headers["destination"] ?: ""
                val destinationPath = URI(destination).path
                if (files.containsKey(destinationPath)) {
                    respond(client, 412, ByteArray(0))
                } else {
                    files[destinationPath] = files.remove(path) ?: ByteArray(0)
                    respond(client, 201, ByteArray(0))
                }
            }
            "GET" -> respond(client, 200, files[path] ?: ByteArray(0))
            "DELETE" -> {
                files.remove(path)
                respond(client, 204, ByteArray(0))
            }
            else -> respond(client, 405, ByteArray(0))
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (value == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            line.write(value)
        }
    }

    private fun readRequestBody(input: BufferedInputStream, headers: Map<String, String>): ByteArray {
        if (headers["transfer-encoding"]?.equals("chunked", ignoreCase = true) == true) {
            val result = ByteArrayOutputStream()
            while (true) {
                val line = readAsciiLine(input) ?: return result.toByteArray()
                val size = line.substringBefore(';').trim().toIntOrNull(16) ?: return result.toByteArray()
                if (size == 0) {
                    readAsciiLine(input)
                    return result.toByteArray()
                }
                val chunk = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val read = input.read(chunk, offset, size - offset)
                    if (read < 0) return result.toByteArray()
                    offset += read
                }
                result.write(chunk)
                readAsciiLine(input)
            }
        }
        val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(bodyLength)
        var offset = 0
        while (offset < bodyLength) {
            val read = input.read(body, offset, bodyLength - offset)
            if (read < 0) return body.copyOf(offset)
            offset += read
        }
        return body
    }

    private fun respond(client: Socket, code: Int, body: ByteArray, extraHeader: String = "") {
        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.ISO_8859_1))
        writer.write("HTTP/1.1 $code ${statusText(code)}\r\n")
        writer.write("Connection: close\r\n")
        if (extraHeader.isNotEmpty()) writer.write("$extraHeader\r\n")
        if (extraHeader.isEmpty() || !extraHeader.startsWith("Content-Length", ignoreCase = true)) {
            writer.write("Content-Length: ${body.size}\r\n")
        }
        writer.write("\r\n")
        writer.flush()
        client.getOutputStream().write(body)
        client.getOutputStream().flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        207 -> "Multi-Status"
        401 -> "Unauthorized"
        405 -> "Method Not Allowed"
        412 -> "Precondition Failed"
        else -> "Error"
    }

    override fun close() {
        socket.close()
        thread?.join(1_000)
    }
}
