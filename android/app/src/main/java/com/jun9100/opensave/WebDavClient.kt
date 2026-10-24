package com.jun9100.opensave

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.w3c.dom.Element

enum class WebDavProvider {
    WEBDAV,
    EDEN_SYNCD,
    ;

    companion object {
        fun fromStored(value: String?): WebDavProvider =
            entries.firstOrNull { it.name == value } ?: WEBDAV
    }
}

data class WebDavConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val provider: WebDavProvider = WebDavProvider.WEBDAV,
)

data class RemoteSnapshot(
    val name: String,
    val sizeBytes: Long,
    val modified: String,
)

data class UploadReport(
    val name: String,
    val sizeBytes: Long,
    val alreadyExisted: Boolean,
)

class WebDavException(message: String, val retryable: Boolean = false) : Exception(message)

/** Basic-auth WebDAV client. Uploads are staged and published with MOVE. */
class WebDavClient(private val config: WebDavConfig) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private val connectionUrl = normalizeConnectionUrl(config)

    init {
        validateBaseUrl(connectionUrl)
    }

    fun testConnection(): Result<Unit> = runCatching {
        request("PROPFIND", baseUrl(), mapOf("Depth" to "0")).use { response ->
            requireSuccess(response, "连接测试")
        }
    }.mapFailure()

    fun list(): Result<List<RemoteSnapshot>> = runCatching {
        request("PROPFIND", baseUrl(), mapOf("Depth" to "1", "Content-Type" to "text/xml")).use { response ->
            requireSuccess(response, "列举 WebDAV 文件")
            parseMultistatus(response.input, baseUrl())
        }
    }.mapFailure()

    fun upload(file: File, remoteName: String): Result<UploadReport> = runCatching {
        require(file.isFile) { "本地快照不存在" }
        require(PathPolicy.isSafeDocumentName(remoteName) && remoteName.endsWith(".zip")) {
            "远端快照名称不安全"
        }
        val temporaryName = ".opensave-${UUID.randomUUID()}.part"
        val temporaryUrl = childUrl(temporaryName)
        val finalUrl = childUrl(remoteName)
        try {
            putFile(file, temporaryUrl)
            verifyRemoteSize(temporaryUrl, file.length())
            val existing = moveIntoPlace(temporaryUrl, finalUrl, file.length())
            UploadReport(remoteName, file.length(), existing)
        } catch (error: Throwable) {
            deleteQuietly(temporaryUrl)
            throw error
        }
    }.mapFailure()

    fun download(remoteName: String, destination: File): Result<Unit> = runCatching {
        require(PathPolicy.isSafeDocumentName(remoteName) && remoteName.endsWith(".zip")) {
            "远端快照名称不安全"
        }
        val parent = destination.absoluteFile.parentFile
            ?: error("下载目标没有父目录")
        require(parent.exists() || parent.mkdirs()) { "无法创建下载目录" }
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            request("GET", childUrl(remoteName)).use { response ->
                requireSuccess(response, "下载快照")
                BufferedInputStream(response.input).use { input ->
                    BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                    }
                }
                response.contentLength.takeIf { it >= 0 }?.let { expected ->
                    require(temporary.length() == expected) {
                        "下载快照大小校验失败：${temporary.length()} / $expected bytes"
                    }
                }
            }
            if (destination.exists() && !destination.delete()) error("无法替换下载文件")
            require(temporary.renameTo(destination)) { "无法发布下载文件" }
        } finally {
            temporary.delete()
        }
    }.mapFailure()

    fun delete(remoteName: String): Result<Unit> = runCatching {
        require(PathPolicy.isSafeDocumentName(remoteName)) { "远端名称不安全" }
        request("DELETE", childUrl(remoteName)).use { response ->
            requireSuccess(response, "删除云端快照")
        }
    }.mapFailure()

    private fun putFile(file: File, url: String) {
        request(
            "PUT",
            url,
            body = file.asRequestBody("application/zip".toMediaType()),
        ).use { response ->
            requireSuccess(response, "上传快照")
        }
    }

    private fun verifyRemoteSize(url: String, expected: Long) {
        request("HEAD", url).use { response ->
            requireSuccess(response, "校验上传结果")
            val actual = response.contentLength
            if (actual >= 0) {
                require(actual == expected) { "远端大小校验失败：$actual / $expected bytes" }
                return
            }
        }
        // A few WebDAV servers omit Content-Length on HEAD. Count the GET
        // stream rather than treating an unverified upload as successful.
        request("GET", url).use { response ->
            requireSuccess(response, "校验上传结果")
            var actual = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            response.input.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    actual += read
                }
            }
            require(actual == expected) { "远端大小校验失败：$actual / $expected bytes" }
        }
    }

    private fun moveIntoPlace(temporaryUrl: String, finalUrl: String, expectedSize: Long): Boolean {
        request(
            "MOVE",
            temporaryUrl,
            mapOf("Destination" to finalUrl, "Overwrite" to "F"),
        ).use { response ->
            if (response.code in 200..299) return false
            if (response.code == 412) {
                val existing = remoteSize(finalUrl)
                if (existing != null) {
                    return if (existing == expectedSize) {
                        deleteQuietly(temporaryUrl)
                        true
                    } else {
                        throw WebDavException("远端已有同名快照，大小不一致，拒绝覆盖")
                    }
                }
            }
            throw WebDavException("发布 WebDAV 快照失败：HTTP ${response.code}", response.code >= 500)
        }
    }

    private fun remoteSize(url: String): Long? {
        request("HEAD", url).use { response ->
            if (response.code !in 200..299) return null
            return response.contentLength
        }
    }

    private fun deleteQuietly(url: String) {
        runCatching {
            request("DELETE", url).use { }
        }
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): ResponseHandle {
        val builder = Request.Builder().url(url)
        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            val raw = "${config.username}:${config.password}"
            builder.header(
                "Authorization",
                "Basic ${Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))}",
            )
        }
        headers.forEach { (name, value) -> builder.header(name, value) }
        return ResponseHandle(httpClient.newCall(builder.method(method, body).build()).execute())
    }

    private fun requireSuccess(response: ResponseHandle, operation: String) {
        if (response.code in 200..299) return
        val body = runCatching { response.input.bufferedReader().use { it.readText().take(512) } }
            .getOrDefault("")
        throw WebDavException(
            "${operation}失败：HTTP ${response.code}${if (body.isBlank()) "" else " - ${body.trim()}"}",
            response.code >= 500 || response.code == 408 || response.code == 429,
        )
    }

    private fun baseUrl(): String = connectionUrl.trimEnd('/')

    private fun childUrl(name: String): String = "${baseUrl()}/${encodePathSegment(name)}"

    private class ResponseHandle(private val response: Response) : AutoCloseable {
        val code: Int get() = response.code
        val input get() = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
        // OkHttp exposes a HEAD response body as length zero even when the
        // server's Content-Length header describes the stored resource.
        val contentLength: Long
            get() = response.header("Content-Length")?.toLongOrNull()
                ?: response.body?.contentLength()
                ?: -1L

        override fun close() {
            response.close()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val TIMEOUT_MS = 30_000

        fun normalizeConnectionUrl(config: WebDavConfig): String = when (config.provider) {
            WebDavProvider.WEBDAV -> config.url.trim().trimEnd('/')
            WebDavProvider.EDEN_SYNCD -> normalizeEdenSyncdUrl(config.url)
        }

        // EDEN_SYNCD deliberately exposes OpenSave on a dedicated collection.
        // Keep the configured service root intact in preferences and derive the
        // collection only when connecting, so the UI never asks users to know
        // its internal path or accidentally appends it twice.
        fun normalizeEdenSyncdUrl(value: String): String {
            val raw = value.trim()
            if (raw.isEmpty()) return ""
            val uri = runCatching { URI(raw) }.getOrElse {
                throw IllegalArgumentException("EDEN_SYNCD 地址无效")
            }
            val scheme = uri.scheme.orEmpty()
            require(scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) {
                "EDEN_SYNCD 只支持 http 或 https"
            }
            require(!uri.host.isNullOrBlank()) { "EDEN_SYNCD 地址缺少主机名" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "EDEN_SYNCD 地址不能包含账号、查询参数或片段"
            }

            val rawPath = uri.rawPath.orEmpty()
            require(!rawPath.contains("%2f", ignoreCase = true) && !rawPath.contains("%5c", ignoreCase = true)) {
                "EDEN_SYNCD 地址不能包含编码路径分隔符"
            }
            require(!rawPath.contains('\\')) { "EDEN_SYNCD 地址不能包含反斜杠" }
            require(uri.path.orEmpty().split('/').none { it == "." || it == ".." }) {
                "EDEN_SYNCD 地址不能包含相对路径"
            }

            val basePath = rawPath.trimEnd('/')
            val parentPath = if (basePath.substringAfterLast('/').equals("opensave", ignoreCase = true)) {
                basePath.substringBeforeLast('/', "")
            } else {
                basePath
            }
            val collectionPath = if (parentPath.isEmpty()) "/opensave" else "$parentPath/opensave"
            val authority = uri.rawAuthority ?: throw IllegalArgumentException("EDEN_SYNCD 地址缺少主机名")
            return "$scheme://$authority$collectionPath"
        }

        fun parseMultistatus(input: java.io.InputStream, requestUrl: String): List<RemoteSnapshot> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                // Android's parser does not implement the XInclude toggle.
                runCatching { isExpandEntityReferences = false }
            }
            val document = factory.newDocumentBuilder().parse(input)
            val output = mutableListOf<RemoteSnapshot>()
            val responses = document.getElementsByTagNameNS("*", "response")
            val basePath = URI(requestUrl).path.trimEnd('/')
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                val href = response.firstText("href") ?: continue
                val resolved = URI(requestUrl).resolve(href)
                val path = resolved.path.trimEnd('/')
                if (path == basePath) continue
                val name = URLDecoder.decode(path.substringAfterLast('/'), Charsets.UTF_8.name())
                if (!name.endsWith(".zip") || name.endsWith(".part")) continue
                if (response.hasCollectionResource()) continue
                val size = response.firstText("getcontentlength")?.toLongOrNull() ?: -1L
                val modified = response.firstText("getlastmodified").orEmpty()
                output += RemoteSnapshot(name, size, modified)
            }
            return output.distinctBy { it.name }.sortedByDescending { it.modified }
        }

        private fun Element.firstText(localName: String): String? {
            val nodes = getElementsByTagNameNS("*", localName)
            return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun Element.hasCollectionResource(): Boolean {
            val nodes = getElementsByTagNameNS("*", "collection")
            return nodes.length > 0
        }

        private fun validateBaseUrl(value: String) {
            val uri = runCatching { URI(value) }.getOrNull()
                ?: throw IllegalArgumentException("WebDAV URL 无效")
            require(uri.scheme == "http" || uri.scheme == "https") { "WebDAV 只支持 http 或 https" }
            require(!uri.host.isNullOrBlank()) { "WebDAV URL 缺少主机名" }
        }

        private fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private fun <T> Result<T>.mapFailure(): Result<T> = fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                if (error is WebDavException) Result.failure(error)
                else Result.failure(WebDavException(error.message ?: "WebDAV 请求失败", true))
            },
        )
    }
}
