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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSaveClientTest {
    @Test
    fun serviceRootsNormalizeWithoutExposingProtocolPaths() {
        assertEquals("https://nas.example:8788", GameSaveClient.normalizeServiceUrl("https://nas.example:8788/"))
        assertEquals("https://nas.example/game-save", GameSaveClient.normalizeServiceUrl("https://nas.example/game-save/v1/opensave/"))
        assertTrue(runCatching { GameSaveClient.normalizeServiceUrl("https://user:pass@nas.example") }.isFailure)
        assertTrue(runCatching { GameSaveClient.normalizeServiceUrl("ftp://nas.example") }.isFailure)
    }

    @Test
    fun listUploadDownloadAndDeleteCarryBearerAndType() {
        val server = MiniGameSaveServer()
        server.start()
        val root = createTempDir(prefix = "opensave-game-save-")
        try {
            val client = GameSaveClient(
                GameSaveConfig(enabled = true, serviceUrl = "http://127.0.0.1:${server.port}", syncKey = "sync-key"),
            )
            val local = File(root, "mario.zip").apply { writeText("zip-content") }
            val name = "switch-0100000000010000__main__snap_1.zip"

            client.testConnection().getOrThrow()
            val uploaded = client.upload(local, GameSaveTypes.SWITCH, name).getOrThrow()
            assertEquals("switch", uploaded.type)
            assertEquals("zip-content", String(server.files["switch/$name"]!!))

            val listed = client.list().getOrThrow()
            assertEquals(1, listed.size)
            assertEquals("switch", listed.single().type)
            assertEquals(name, listed.single().name)

            val downloaded = File(root, "downloaded.zip")
            client.download(listed.single(), downloaded).getOrThrow()
            assertEquals("zip-content", downloaded.readText())

            client.delete(listed.single()).getOrThrow()
            assertFalse(server.files.containsKey("switch/$name"))
            assertTrue(server.authorizedRequests > 0)
        } finally {
            root.deleteRecursively()
            server.close()
        }
    }
}

/** Small HTTP server keeps the native contract test independent of WebDAV or a real NAS. */
private class MiniGameSaveServer : AutoCloseable {
    val files = ConcurrentHashMap<String, ByteArray>()
    private val socket = ServerSocket(0)
    private var thread: Thread? = null
    var authorizedRequests = 0
        private set
    val port: Int get() = socket.localPort

    fun start() {
        thread = Thread {
            while (!socket.isClosed) runCatching { socket.accept().use(::handle) }
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
        if (headers["authorization"] != "Bearer sync-key") {
            respond(client, 401, ByteArray(0))
            return
        }
        authorizedRequests++
        if (method == "GET" && path == "/v1/opensave/files") {
            val payload = JSONObject().put("files", JSONArray().apply {
                files.entries.sortedBy { it.key }.forEach { (key, value) ->
                    val type = key.substringBefore('/')
                    val name = key.substringAfter('/')
                    put(
                        JSONObject()
                            .put("type", type)
                            .put("name", name)
                            .put("sizeBytes", value.size)
                            .put("createdTime", "2026-10-24T00:00:00Z"),
                    )
                }
            }).toString().toByteArray()
            respond(client, 200, payload, "Content-Type: application/json")
            return
        }

        val segments = path.removePrefix("/v1/opensave/files/").split('/')
        if (segments.size != 2 || !path.startsWith("/v1/opensave/files/")) {
            respond(client, 404, ByteArray(0))
            return
        }
        val key = "${segments[0]}/${segments[1]}"
        when (method) {
            "PUT" -> {
                files[key] = body
                respond(client, 201, ByteArray(0))
            }
            "GET" -> {
                val value = files[key]
                if (value == null) respond(client, 404, ByteArray(0))
                else respond(client, 200, value, "Content-Type: application/zip")
            }
            "DELETE" -> {
                if (files.remove(key) == null) respond(client, 404, ByteArray(0)) else respond(client, 204, ByteArray(0))
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
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
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
        writer.write("Content-Length: ${body.size}\r\n\r\n")
        writer.flush()
        client.getOutputStream().write(body)
        client.getOutputStream().flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    override fun close() {
        socket.close()
        thread?.join(1_000)
    }
}
