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
import okhttp3.RequestBody.Companion.toRequestBody
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

/** One immutable archive proposal for a typed game-save scope. */
data class GameSaveScopeCandidate(
    val id: String,
    val archive: String,
    val parent: String,
    val contentDigest: String,
    val createdAt: String,
)

/** Server-audited native scope snapshot. Device fields are informative only. */
data class GameSaveScopeSnapshot(
    val id: String,
    val archive: String,
    val parent: String,
    val contentDigest: String,
    val createdAt: String,
    val receivedAt: String,
    val device: String = "",
    val deviceName: String = "",
    val platform: String = "",
    val conflict: Boolean = false,
    val conflictOf: String = "",
)

/** The authoritative state for exactly one `{type}/{gameId}` native scope. */
data class GameSaveScope(
    val type: String,
    val gameId: String,
    val head: GameSaveScopeSnapshot?,
    val snapshots: List<GameSaveScopeSnapshot>,
    val conflicts: List<GameSaveScopeSnapshot>,
)

/** Stable client identity sent with native game-save requests for audit only. */
data class GameSaveDeviceIdentity(
    val id: String,
    val name: String,
    val platform: String = "opensave-android",
    val version: String = "",
)

open class GameSaveException(message: String, val retryable: Boolean = false) : Exception(message)

/** Only a route-level GET 404 permits the old ZIP-head compatibility path. */
class GameSaveScopesUnsupportedException : GameSaveException(
    "当前 game-save 服务尚不支持原生存档范围同步",
)

/** A CAS write lost its race; the caller must retain a conflict copy. */
class GameSaveScopeConflictException(val head: String) : GameSaveException(
    if (head.isBlank()) "云端当前存档已变化" else "云端当前存档已变化：$head",
)

/** Native game-save API client. Every request is bearer-authenticated and type-scoped. */
class GameSaveClient(
    private val config: GameSaveConfig,
    private val device: GameSaveDeviceIdentity? = null,
) {
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

    /**
     * Reads a native typed scope. Unlike missing files, an empty scope is a
     * successful 200 response with `head: null`; only a route-level 404 means
     * the NAS is still on a pre-CAS image and may use the legacy fallback.
     */
    fun getScope(type: GameSaveType, gameId: String): Result<GameSaveScope> = runCatching {
        validateScope(type, gameId)
        execute(request(scopeEndpoint(type.id, gameId))).use { response ->
            if (response.code == 404) throw GameSaveScopesUnsupportedException()
            requireSuccess(response, "读取 game-save 当前存档")
            parseScope(response.body?.string().orEmpty(), type.id, gameId)
        }
    }.mapFailure()

    /** Advances a scope only when `expectedHead` remains the confirmed parent. */
    fun putScopeSnapshot(
        type: GameSaveType,
        gameId: String,
        candidate: GameSaveScopeCandidate,
        expectedHead: String,
    ): Result<GameSaveScopeSnapshot> = putScopeCandidate(
        type = type,
        gameId = gameId,
        candidate = candidate,
        expectedHead = expectedHead,
        conflict = false,
    )

    /** Retains a divergent archive without changing the scope head. */
    fun putScopeConflict(
        type: GameSaveType,
        gameId: String,
        candidate: GameSaveScopeCandidate,
    ): Result<GameSaveScopeSnapshot> = putScopeCandidate(
        type = type,
        gameId = gameId,
        candidate = candidate,
        expectedHead = "",
        conflict = true,
    )

    private fun validateRemote(remote: RemoteSnapshot) {
        require(GameSaveTypes.isValid(remote.type)) { "云端快照类型无效" }
        require(PathPolicy.isSafeDocumentName(remote.name) && remote.name.endsWith(".zip")) { "云端快照名称无效" }
    }

    private fun putScopeCandidate(
        type: GameSaveType,
        gameId: String,
        candidate: GameSaveScopeCandidate,
        expectedHead: String,
        conflict: Boolean,
    ): Result<GameSaveScopeSnapshot> = runCatching {
        validateScope(type, gameId)
        require(isSafeScopeSnapshotId(candidate.id)) { "云端快照标识无效" }
        require(PathPolicy.isSafeDocumentName(candidate.archive) && candidate.archive.endsWith(".zip")) {
            "云端快照名称无效"
        }
        require(candidate.parent.isBlank() || isSafeScopeSnapshotId(candidate.parent)) { "云端父快照标识无效" }
        require(candidate.contentDigest.matches(SHA256_PATTERN)) { "云端快照摘要无效" }
        require(candidate.createdAt.isNotBlank()) { "云端快照创建时间无效" }
        val path = scopeEndpoint(type.id, gameId) +
            if (conflict) "/conflicts/${encodePathSegment(candidate.id)}" else "/snapshots/${encodePathSegment(candidate.id)}"
        val body = JSONObject()
            .put("id", candidate.id)
            .put("archive", candidate.archive)
            .put("parent", candidate.parent)
            .put("contentDigest", candidate.contentDigest)
            .put("createdAt", candidate.createdAt)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = request(path)
            .put(body)
            .header("Content-Type", "application/json")
        if (!conflict) request.header("If-Match", "\"$expectedHead\"")
        execute(request).use { response ->
            if (response.code == 409) {
                val head = runCatching { JSONObject(response.body?.string().orEmpty()).optString("head").trim() }
                    .getOrDefault("")
                throw GameSaveScopeConflictException(head)
            }
            requireSuccess(response, if (conflict) "保留 game-save 冲突快照" else "更新 game-save 当前存档")
            parseScopeSnapshot(JSONObject(response.body?.string().orEmpty()))
        }
    }.mapFailure()

    private fun parseScope(body: String, expectedType: String, expectedGameId: String): GameSaveScope {
        val json = JSONObject(body)
        val type = json.optString("type").trim().lowercase()
        val gameId = json.optString("gameId").trim()
        require(type == expectedType && gameId == expectedGameId) { "game-save 返回了不匹配的存档范围" }
        val snapshots = json.optJSONArray("snapshots").toScopeSnapshots()
        val conflicts = json.optJSONArray("conflicts").toScopeSnapshots()
        val head = json.optJSONObject("head")?.let(::parseScopeSnapshot)
        if (head != null) {
            require(head.id.isNotBlank() && snapshots.any { it.id == head.id }) { "game-save 当前存档状态无效" }
        }
        return GameSaveScope(type, gameId, head, snapshots, conflicts)
    }

    private fun org.json.JSONArray?.toScopeSnapshots(): List<GameSaveScopeSnapshot> = buildList {
        if (this@toScopeSnapshots == null) return@buildList
        for (index in 0 until this@toScopeSnapshots.length()) {
            this@toScopeSnapshots.optJSONObject(index)?.let { add(parseScopeSnapshot(it)) }
        }
    }

    private fun parseScopeSnapshot(json: JSONObject): GameSaveScopeSnapshot {
        val id = json.optString("id").trim()
        val archive = json.optString("archive").trim()
        val parent = json.optString("parent").trim()
        val digest = json.optString("contentDigest").trim().lowercase()
        val createdAt = json.optString("createdAt").trim()
        val receivedAt = json.optString("receivedAt").trim()
        require(isSafeScopeSnapshotId(id)) { "game-save 返回了无效快照标识" }
        require(PathPolicy.isSafeDocumentName(archive) && archive.endsWith(".zip")) { "game-save 返回了无效快照名称" }
        require(parent.isBlank() || isSafeScopeSnapshotId(parent)) { "game-save 返回了无效父快照标识" }
        require(digest.matches(SHA256_PATTERN)) { "game-save 返回了无效快照摘要" }
        require(createdAt.isNotBlank() && receivedAt.isNotBlank()) { "game-save 返回了无效快照时间" }
        return GameSaveScopeSnapshot(
            id = id,
            archive = archive,
            parent = parent,
            contentDigest = digest,
            createdAt = createdAt,
            receivedAt = receivedAt,
            device = json.optString("device").trim(),
            deviceName = json.optString("deviceName").trim(),
            platform = json.optString("platform").trim(),
            conflict = json.optBoolean("conflict"),
            conflictOf = json.optString("conflictOf").trim(),
        )
    }

    private fun validateScope(type: GameSaveType, gameId: String) {
        require(GameSaveTypes.isValid(type.id)) { "存档类型无效" }
        require(SCOPE_GAME_ID_PATTERN.matches(gameId)) { "游戏同步标识无效" }
    }

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${config.syncKey}")
        .also(::applyDeviceHeaders)

    private fun applyDeviceHeaders(request: Request.Builder) {
        val identity = device
        val platform = identity?.platform ?: "opensave-android"
        // Keep released servers observable during a staggered upgrade while
        // preferring the OpenSave-specific family in current game-save builds.
        for (family in listOf("X-OpenSave", "X-Eden")) {
            request.header("$family-Platform", platform)
            if (identity != null) {
                request.header("$family-Device-ID", identity.id)
                request.header("$family-Device-Name", identity.name)
                if (identity.version.isNotBlank()) request.header("$family-Version", identity.version)
            }
        }
    }

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

    private fun scopeEndpoint(type: String, gameId: String): String =
        "$serviceUrl/v1/opensave/scopes/${encodePathSegment(type)}/${encodePathSegment(gameId)}"

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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        private val SCOPE_GAME_ID_PATTERN = Regex("^[a-z0-9][a-z0-9_.-]{0,127}$", RegexOption.IGNORE_CASE)
        private val SCOPE_SNAPSHOT_ID_PATTERN = Regex("^[a-z0-9][a-z0-9_.-]{0,191}$", RegexOption.IGNORE_CASE)
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

        private fun isSafeScopeSnapshotId(value: String): Boolean =
            SCOPE_SNAPSHOT_ID_PATTERN.matches(value)
    }
}
