package com.jun9100.opensave

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject

data class RemoteSnapshot(
    val type: String,
    val name: String,
    val sizeBytes: Long,
    val modified: String,
)

data class UploadReport(
    val type: String,
    val name: String,
    val sizeBytes: Long,
)

class GameSaveException(message: String, val retryable: Boolean = false) : Exception(message)

/** Native game-save API client. Every request is bearer-authenticated and type-scoped. */
class GameSaveClient(private val config: GameSaveConfig) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val serviceUrl = normalizeServiceUrl(config.serviceUrl)

    init {
        require(serviceUrl.isNotBlank()) { "请先填写 game-save 服务地址" }
        require(config.syncKey.isNotBlank()) { "请先填写同步密钥" }
    }

    fun testConnection(): Result<Unit> = list().map { Unit }

    fun list(): Result<List<RemoteSnapshot>> = runCatching {
        execute(request(listEndpoint())).use { response ->
            requireSuccess(response, "列举 game-save 快照")
            val body = response.body?.string().orEmpty()
            val files = JSONObject(body).optJSONArray("files") ?: return@use emptyList()
            buildList {
                for (index in 0 until files.length()) {
                    val item = files.optJSONObject(index) ?: continue
                    val type = item.optString("type").trim().lowercase()
                    val name = item.optString("name").trim()
                    if (!GameSaveTypes.isValid(type) || !PathPolicy.isSafeDocumentName(name) || !name.endsWith(".zip")) {
                        continue
                    }
                    add(
                        RemoteSnapshot(
                            type = type,
                            name = name,
                            sizeBytes = item.optLong("sizeBytes", -1L),
                            modified = item.optString("createdTime").trim(),
                        ),
                    )
                }
            }.sortedWith(compareByDescending<RemoteSnapshot> { it.modified }.thenBy { it.type }.thenBy { it.name })
        }
    }.mapFailure()

    fun upload(file: File, type: GameSaveType, remoteName: String): Result<UploadReport> = runCatching {
        require(file.isFile) { "本地快照不存在" }
        require(PathPolicy.isSafeDocumentName(remoteName) && remoteName.endsWith(".zip")) { "远端快照名称不安全" }
        execute(
            request(fileEndpoint(type.id, remoteName))
                .put(file.asRequestBody(ZIP_MEDIA_TYPE)),
        ).use { response ->
            requireSuccess(response, "上传 game-save 快照")
        }
        UploadReport(type.id, remoteName, file.length())
    }.mapFailure()

    fun download(remote: RemoteSnapshot, destination: File): Result<Unit> = runCatching {
        validateRemote(remote)
        val parent = destination.absoluteFile.parentFile ?: error("下载目标没有父目录")
        require(parent.exists() || parent.mkdirs()) { "无法创建下载目录" }
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            execute(request(fileEndpoint(remote.type, remote.name))).use { response ->
                requireSuccess(response, "下载 game-save 快照")
                BufferedInputStream(response.body?.byteStream() ?: error("下载响应为空")).use { input ->
                    BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                    }
                }
                response.header("Content-Length")?.toLongOrNull()?.let { expected ->
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

    fun delete(remote: RemoteSnapshot): Result<Unit> = runCatching {
        validateRemote(remote)
        execute(request(fileEndpoint(remote.type, remote.name)).delete()).use { response ->
            requireSuccess(response, "删除 game-save 快照")
        }
    }.mapFailure()

    private fun validateRemote(remote: RemoteSnapshot) {
        require(GameSaveTypes.isValid(remote.type)) { "云端快照类型无效" }
        require(PathPolicy.isSafeDocumentName(remote.name) && remote.name.endsWith(".zip")) { "云端快照名称无效" }
    }

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${config.syncKey}")
        .header("X-Eden-Platform", "opensave-android")

    private fun execute(builder: Request.Builder): Response = httpClient.newCall(builder.build()).execute()

    private fun requireSuccess(response: Response, operation: String) {
        if (response.isSuccessful) return
        val body = runCatching { response.body?.string().orEmpty().take(512) }.getOrDefault("")
        throw GameSaveException(
            "${operation}失败：HTTP ${response.code}${if (body.isBlank()) "" else " - $body"}",
            response.code >= 500 || response.code == 408 || response.code == 429,
        )
    }

    private fun listEndpoint(): String = "$serviceUrl/v1/opensave/files"

    private fun fileEndpoint(type: String, name: String): String =
        "${listEndpoint()}/${encodePathSegment(type)}/${encodePathSegment(name)}"

    private fun <T> Result<T>.mapFailure(): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = { error ->
            if (error is GameSaveException) Result.failure(error)
            else Result.failure(GameSaveException(error.message ?: "game-save 请求失败", error is IOException))
        },
    )

    companion object {
        private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()
        private const val BUFFER_SIZE = 1024 * 1024
        private const val TIMEOUT_MS = 30L * 1000L

        /** Accept a service root, including an optional reverse-proxy path, but never credentials or a query. */
        fun normalizeServiceUrl(value: String): String {
            val raw = value.trim()
            if (raw.isEmpty()) return ""
            val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("game-save 地址无效") }
            val scheme = uri.scheme.orEmpty().lowercase()
            require(scheme == "http" || scheme == "https") { "game-save 只支持 http 或 https" }
            require(!uri.host.isNullOrBlank()) { "game-save 地址缺少主机名" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "game-save 地址不能包含账号、查询参数或片段"
            }
            val rawPath = uri.rawPath.orEmpty()
            require(!rawPath.contains("%2f", ignoreCase = true) && !rawPath.contains("%5c", ignoreCase = true)) {
                "game-save 地址不能包含编码路径分隔符"
            }
            require(!rawPath.contains('\\')) { "game-save 地址不能包含反斜杠" }
            var path = rawPath.trimEnd('/')
            require(path.split('/').none { it == "." || it == ".." }) { "game-save 地址不能包含相对路径" }
            if (path.endsWith("/v1/opensave", ignoreCase = true)) {
                path = path.dropLast("/v1/opensave".length)
            }
            val authority = uri.rawAuthority ?: throw IllegalArgumentException("game-save 地址缺少主机名")
            return "$scheme://$authority$path"
        }

        private fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
