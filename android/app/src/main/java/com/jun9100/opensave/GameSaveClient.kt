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
    /** The server keeps this version forever; any pruning rule must leave it alone. */
    val locked: Boolean = false,
)

/** The authoritative state for exactly one `{type}/{gameId}` native scope. */
data class GameSaveScope(
    val type: String,
    val gameId: String,
    val head: GameSaveScopeSnapshot?,
    val snapshots: List<GameSaveScopeSnapshot>,
    val conflicts: List<GameSaveScopeSnapshot>,
)

/** One save location of a cloud game (C1): a scope, possibly another device's. */
data class GameSaveCloudGameMember(
    val type: String,
    val gameId: String,
    val link: String,
    val name: String,
    val head: GameSaveScopeSnapshot?,
    val snapshotCount: Int,
    val conflictCount: Int,
    /** The cloud merged this member into another scope; it only appears for history now. */
    val retired: Boolean = false,
)

/** Scopes of one title, from every device, as SaveVault 云端 groups them. */
data class GameSaveCloudGame(
    val key: String,
    val type: String,
    val name: String,
    val members: List<GameSaveCloudGameMember>,
) {
    /** The other save locations of the game [gameId] (in [type]) belongs to. */
    fun othersThan(type: String, gameId: String): List<GameSaveCloudGameMember> =
        members.filterNot { it.type == type && it.gameId == gameId }
}

/** Stable client identity sent with native game-save requests for audit only. */
data class GameSaveDeviceIdentity(
    val id: String,
    val name: String,
    val platform: String = "opensave-android",
    val version: String = "",
)

/** What the cloud says about the credential this install currently uses. */
data class GameSaveWhoami(
    val auth: String,
    val tokenId: String,
    val deviceId: String,
    val name: String,
    val sharedTokenMode: String,
)

/** A token returned by `POST /v1/pair/claim`; it can never be fetched again. */
data class GameSavePairingResult(
    val token: String,
    val tokenId: String,
    val name: String,
    val serverVersion: String,
) {
    /** The bearer secret must never reach a log or crash report through an implicit print. */
    override fun toString(): String =
        "GameSavePairingResult(token=***, tokenId=$tokenId, name=$name, serverVersion=$serverVersion)"
}

open class GameSaveException(
    message: String,
    val retryable: Boolean = false,
    /** HTTP status of a refused request; 0 when the failure was not an HTTP response. */
    val httpStatus: Int = 0,
) : Exception(message)

/** A 401 from a sync route with the server's reason; never retried. */
class GameSaveAuthException(val code: String, message: String) : GameSaveException(
    message,
    retryable = false,
    httpStatus = 401,
)

/** Only a route-level GET 404 permits the old ZIP-head compatibility path. */
class GameSaveScopesUnsupportedException : GameSaveException(
    "当前 SaveVault 云端尚不支持原生存档范围同步",
)

/**
 * 410 from a scope route: the cloud deleted the scope or merged it into
 * [successorType]/[successorGameId]. Never retried: the client stops syncing
 * this game instead of recreating the scope with a new empty root.
 */
class GameSaveScopeGoneException(
    val code: String,
    val deletedAt: String = "",
    val successorType: String = "",
    val successorGameId: String = "",
    message: String,
) : GameSaveException(message, retryable = false, httpStatus = 410) {
    val deleted: Boolean get() = code == DELETED
    val retired: Boolean get() = code == RETIRED

    companion object {
        const val DELETED = "scope_deleted"
        const val RETIRED = "scope_retired"

        private const val DELETED_MESSAGE = "这个游戏的云端存档已被管理员删除，本机存档不受影响，已停止云同步"
        private const val RETIRED_MESSAGE = "这个游戏的云端存档已合并到另一个游戏，已停止云同步"
        private const val OTHER_MESSAGE = "SaveVault 云端拒绝了这个存档范围（HTTP 410）"

        /** Builds the exception with the wording of [code], shared by the 410 parser and recorded tombstones. */
        fun of(
            code: String,
            deletedAt: String = "",
            successorType: String = "",
            successorGameId: String = "",
        ) = GameSaveScopeGoneException(
            code = code,
            deletedAt = deletedAt,
            successorType = successorType,
            successorGameId = successorGameId,
            message = when (code) {
                DELETED -> DELETED_MESSAGE
                RETIRED -> RETIRED_MESSAGE
                else -> OTHER_MESSAGE
            },
        )

        /** Rebuilds the stop from a recorded tombstone, so a skipped sync reads exactly like the server's 410. */
        internal fun from(tombstone: ScopeTombstone) = of(
            code = if (tombstone.retired) RETIRED else DELETED,
            deletedAt = tombstone.at,
            successorType = tombstone.successorType,
            successorGameId = tombstone.successorGameId,
        )
    }
}

/** A service too old to know `/v1/pair/claim`; the shared sync key still works there. */
class GameSavePairingUnsupportedException : GameSaveException(
    "SaveVault 云端不支持配对，请升级到 v1.7.0 或继续填写同步密钥",
)

/** Receives the cloud's credential verdicts, so background work can stop before retrying. */
interface GameSaveAuthObserver {
    /** Called only for `token_revoked` and `shared_token_disabled`. */
    fun onAuthRejected(code: String)

    /** The cloud is in the shared-token warning stage (`warn`). */
    fun onSharedTokenWarning()
}

/** A CAS write lost its race; the caller must retain a conflict copy. */
class GameSaveScopeConflictException(val head: String) : GameSaveException(
    if (head.isBlank()) "云端当前版本已变化" else "云端当前版本已变化：$head",
)

/** Native game-save API client. Every request is bearer-authenticated and type-scoped. */
class GameSaveClient(
    private val config: GameSaveConfig,
    private val device: GameSaveDeviceIdentity? = null,
    private val authObserver: GameSaveAuthObserver? = null,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val serviceUrl = normalizeServiceUrl(config.serviceUrl)

    init {
        require(serviceUrl.isNotBlank()) { "请先填写 SaveVault 云端地址" }
        require(config.syncKey.isNotBlank()) { "请先填写同步密钥" }
    }

    fun testConnection(): Result<Unit> = list().map { Unit }

    /** The credential's type and compatibility stage; null when the service predates the route. */
    fun whoami(): Result<GameSaveWhoami?> = runCatching {
        execute(request("$serviceUrl/v1/auth/whoami")).use { response ->
            if (response.code == 404) return@runCatching null
            requireSuccess(response, "查询 SaveVault 云端凭据")
            val json = JSONObject(response.body?.string().orEmpty())
            GameSaveWhoami(
                auth = json.optString("auth").trim(),
                tokenId = json.optString("token_id").trim(),
                deviceId = json.optString("device_id").trim(),
                name = json.optString("name").trim(),
                sharedTokenMode = json.optString("shared_token_mode").trim(),
            )
        }
    }.mapFailure()

    fun list(): Result<List<RemoteSnapshot>> = runCatching {
        execute(request(listEndpoint())).use { response ->
            requireSuccess(response, "列举 SaveVault 云端快照")
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
            scopeType = type.id,
        ).use { response ->
            requireSuccess(response, "上传 SaveVault 云端快照")
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
                requireSuccess(response, "下载 SaveVault 云端快照")
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
            requireSuccess(response, "删除 SaveVault 云端快照")
        }
    }.mapFailure()

    /**
     * Reads a native typed scope. Unlike missing files, an empty scope is a
     * successful 200 response with `head: null`; only a route-level 404 means
     * the NAS is still on a pre-CAS image and may use the legacy fallback.
     */
    fun getScope(type: GameSaveType, gameId: String): Result<GameSaveScope> = runCatching {
        validateScope(type, gameId)
        execute(request(scopeEndpoint(type.id, gameId)), scopeType = type.id).use { response ->
            if (response.code == 404) throw GameSaveScopesUnsupportedException()
            requireSuccess(response, "读取 SaveVault 云端当前存档")
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

    /**
     * Every cloud game with its save locations (C1), or null when the service
     * does not group games yet (route-level 404). Read-only: no heartbeat.
     */
    fun getGames(): Result<List<GameSaveCloudGame>?> = runCatching {
        execute(request("$serviceUrl/v1/opensave/games")).use { response ->
            if (response.code == 404) return@runCatching null
            requireSuccess(response, "读取 SaveVault 云端游戏列表")
            parseGames(response.body?.string().orEmpty())
        }
    }.mapFailure()

    /** The cloud game holding [gameId] in [type], or null when there is none or no grouping. */
    fun gameOf(type: GameSaveType, gameId: String): Result<GameSaveCloudGame?> =
        getGames().map { games -> games?.firstOrNull { game -> game.members.any { it.type == type.id && it.gameId == gameId } } }

    internal fun parseGames(body: String): List<GameSaveCloudGame> {
        val games = JSONObject(body).optJSONArray("games") ?: return emptyList()
        return buildList {
            for (index in 0 until games.length()) {
                val json = games.optJSONObject(index) ?: continue
                val type = json.optString("type").trim().lowercase()
                if (!GameSaveTypes.isValid(type)) continue
                val members = json.optJSONArray("scopes")
                val parsed = buildList {
                    for (m in 0 until (members?.length() ?: 0)) {
                        val member = members?.optJSONObject(m) ?: continue
                        val memberType = member.optString("type").trim().lowercase()
                        val gameId = member.optString("gameId").trim()
                        // A game never spans types; anything else is not trusted.
                        if (memberType != type || !SCOPE_GAME_ID_PATTERN.matches(gameId)) continue
                        add(
                            GameSaveCloudGameMember(
                                type = memberType,
                                gameId = gameId,
                                link = member.optString("link").trim(),
                                name = member.optString("name").trim().take(MAX_GAME_NAME_LENGTH),
                                head = member.optJSONObject("head")?.let { runCatching { parseScopeSnapshot(it) }.getOrNull() },
                                snapshotCount = member.optInt("snapshotCount"),
                                conflictCount = member.optInt("conflictCount"),
                                retired = member.optBoolean("retired"),
                            ),
                        )
                    }
                }
                if (parsed.isEmpty()) continue
                add(
                    GameSaveCloudGame(
                        key = json.optString("key").trim(),
                        type = type,
                        name = json.optString("name").trim().take(MAX_GAME_NAME_LENGTH),
                        members = parsed,
                    ),
                )
            }
        }
    }

    /**
     * Tells the service which game a scope holds (C1). It never moves a head.
     * A service without the route answers 404, which only means it groups
     * nothing yet; the result is false then.
     */
    fun putScopeIdentity(type: GameSaveType, gameId: String, facts: GameCloudFacts): Result<Boolean> = runCatching {
        validateScope(type, gameId)
        val body = JSONObject().apply {
            if (facts.name.isNotBlank()) put("name", facts.name)
            if (facts.titleId.isNotBlank()) put("titleId", facts.titleId)
            if (facts.steamAppId.isNotBlank()) put("steamAppId", facts.steamAppId)
            if (facts.pspGameId.isNotBlank()) put("pspGameId", facts.pspGameId)
            if (facts.androidPackage.isNotBlank()) put("androidPackage", facts.androidPackage)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = request(scopeEndpoint(type.id, gameId) + "/identity")
            .put(body)
            .header("Content-Type", "application/json")
        execute(request, scopeType = type.id).use { response ->
            if (response.code == 404) return@runCatching false
            requireSuccess(response, "上报 SaveVault 云端游戏信息")
            true
        }
    }.mapFailure()

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
        execute(request, scopeType = type.id).use { response ->
            if (response.code == 409) {
                val head = runCatching { JSONObject(response.body?.string().orEmpty()).optString("head").trim() }
                    .getOrDefault("")
                throw GameSaveScopeConflictException(head)
            }
            requireSuccess(response, if (conflict) "保留 SaveVault 云端冲突快照" else "更新 SaveVault 云端当前存档")
            parseScopeSnapshot(JSONObject(response.body?.string().orEmpty()))
        }
    }.mapFailure()

    private fun parseScope(body: String, expectedType: String, expectedGameId: String): GameSaveScope {
        val json = JSONObject(body)
        val type = json.optString("type").trim().lowercase()
        val gameId = json.optString("gameId").trim()
        require(type == expectedType && gameId == expectedGameId) { "SaveVault 云端返回了不匹配的存档范围" }
        val snapshots = json.optJSONArray("snapshots").toScopeSnapshots()
        val conflicts = json.optJSONArray("conflicts").toScopeSnapshots()
        val head = json.optJSONObject("head")?.let(::parseScopeSnapshot)
        if (head != null) {
            require(head.id.isNotBlank() && snapshots.any { it.id == head.id }) { "SaveVault 云端当前存档状态无效" }
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
        require(isSafeScopeSnapshotId(id)) { "SaveVault 云端返回了无效快照标识" }
        require(PathPolicy.isSafeDocumentName(archive) && archive.endsWith(".zip")) { "SaveVault 云端返回了无效快照名称" }
        require(parent.isBlank() || isSafeScopeSnapshotId(parent)) { "SaveVault 云端返回了无效父快照标识" }
        require(digest.matches(SHA256_PATTERN)) { "SaveVault 云端返回了无效快照摘要" }
        require(createdAt.isNotBlank() && receivedAt.isNotBlank()) { "SaveVault 云端返回了无效快照时间" }
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
            locked = json.optBoolean("locked"),
        )
    }

    private fun validateScope(type: GameSaveType, gameId: String) {
        require(GameSaveTypes.isValid(type.id)) { "存档类型无效" }
        require(SCOPE_GAME_ID_PATTERN.matches(gameId)) { "游戏同步标识无效" }
    }

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${config.syncKey}")
        .also { applyDeviceHeaders(it, device) }

    /**
     * [scopeType] is the type of the `{type}/{game}` scope the request addresses.
     * A 410 is answered here, before any caller's own status checks, because a
     * deleted or merged scope must never be retried or recreated.
     */
    private fun execute(builder: Request.Builder, scopeType: String? = null): Response {
        val response = httpClient.newCall(builder.build()).execute()
        try {
            if (response.header(AUTH_WARNING_HEADER) == SHARED_TOKEN_DEPRECATED) {
                runCatching { authObserver?.onSharedTokenWarning() }
            }
            if (response.code == 401) {
                val code = runCatching {
                    JSONObject(response.peekBody(AUTH_ERROR_BODY_LIMIT).string()).optString("code").trim()
                }.getOrDefault("")
                val reason = code.takeIf { it == TOKEN_REVOKED || it == SHARED_TOKEN_DISABLED } ?: UNAUTHORIZED
                // Persist/stop background work before the caller unwinds; that path must not retry.
                if (reason != UNAUTHORIZED) runCatching { authObserver?.onAuthRejected(reason) }
                response.close()
                throw GameSaveAuthException(reason, authMessage(reason))
            }
            if (response.code == 410) {
                throw scopeGone(response.peekBody(SCOPE_GONE_BODY_LIMIT).string(), scopeType)
            }
            return response
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    private fun requireSuccess(response: Response, operation: String) {
        if (response.isSuccessful) return
        val body = runCatching { response.body?.string().orEmpty().take(512) }.getOrDefault("")
        throw GameSaveException(
            "${operation}失败：HTTP ${response.code}${if (body.isBlank()) "" else " - $body"}",
            response.code >= 500 || response.code == 408 || response.code == 429,
            response.code,
        )
    }

    /**
     * Parses a 410 body into a stop for this scope. The successor is only kept
     * when it is a real scope of the same type; anything else stays empty so a
     * malformed body cannot redirect the client to another game.
     */
    private fun scopeGone(body: String, scopeType: String?): GameSaveScopeGoneException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val code = json?.optString("code")?.trim().orEmpty()
        if (code != GameSaveScopeGoneException.DELETED && code != GameSaveScopeGoneException.RETIRED) {
            return GameSaveScopeGoneException.of(code)
        }
        val successor = json?.optJSONObject("successor")
        val successorType = successor?.optString("type")?.trim()?.lowercase().orEmpty()
        val successorGameId = successor?.optString("gameId")?.trim().orEmpty()
        val trusted = scopeType != null && successorType == scopeType && SCOPE_GAME_ID_PATTERN.matches(successorGameId)
        return GameSaveScopeGoneException.of(
            code = code,
            deletedAt = json?.optString("deletedAt")?.trim().orEmpty(),
            successorType = successorType.takeIf { trusted }.orEmpty(),
            successorGameId = successorGameId.takeIf { trusted }.orEmpty(),
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
            else Result.failure(GameSaveException(error.message ?: "SaveVault 云端请求失败", error is IOException))
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
        private const val AUTH_ERROR_BODY_LIMIT = 4096L
        private const val SCOPE_GONE_BODY_LIMIT = 4096L
        private const val AUTH_WARNING_HEADER = "X-Game-Save-Auth-Warning"
        private const val SHARED_TOKEN_DEPRECATED = "shared-token-deprecated"
        private const val TOKEN_REVOKED = "token_revoked"
        private const val SHARED_TOKEN_DISABLED = "shared_token_disabled"
        private const val UNAUTHORIZED = "unauthorized"

        /** The pairing call has no saved credential, so it builds its own client. */
        private val pairingHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }

        /**
         * Exchanges a pairing code for a device token. This is intentionally
         * static: it must work before any config (or key) has been saved, and
         * the code itself is the only credential the request carries.
         */
        fun claimPairing(
            serviceUrl: String,
            code: String,
            device: GameSaveDeviceIdentity,
        ): Result<GameSavePairingResult> = runCatching {
            val normalizedServiceUrl = normalizeServiceUrl(serviceUrl)
            require(normalizedServiceUrl.isNotBlank()) { "请先填写 SaveVault 云端地址" }
            val claimUrl = "$normalizedServiceUrl/v1/pair/claim"
            val normalizedCode = PairingCode.normalize(code)
                ?: throw IllegalArgumentException("配对码无效")
            val body = JSONObject()
                .put("code", normalizedCode)
                .put("device_id", device.id)
                .put("device_name", device.name.take(MAX_DEVICE_NAME_LENGTH))
                .put("platform", device.platform)
                .put("version", device.version)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(claimUrl)
                .post(body)
                .header("Content-Type", "application/json")
                .also { applyDeviceHeaders(it, device) }
                .build()
            pairingHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> parsePairingResult(response.body?.string().orEmpty())
                    400 -> throw GameSaveException("配对请求无效", httpStatus = 400)
                    401 -> throw GameSaveException("配对码无效、已过期或已被使用", httpStatus = 401)
                    404 -> throw GameSavePairingUnsupportedException()
                    429 -> throw GameSaveException("配对失败次数太多，请稍后再试", retryable = true, httpStatus = 429)
                    else -> throw GameSaveException(
                        "配对失败：HTTP ${response.code}",
                        retryable = response.code >= 500 || response.code == 408,
                        httpStatus = response.code,
                    )
                }
            }
        }.mapPairingFailure()

        private fun parsePairingResult(body: String): GameSavePairingResult {
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw GameSaveException("SaveVault 云端返回的令牌无效") }
            val token = json.opt("token") as? String
            // Opaque to the client, but a bearer header must stay printable ASCII.
            if (token.isNullOrEmpty() || token.any { it.code !in 0x21..0x7e }) {
                throw GameSaveException("SaveVault 云端返回的令牌无效")
            }
            return GameSavePairingResult(
                token = token,
                tokenId = json.optString("token_id").trim(),
                name = json.optString("name").trim(),
                serverVersion = json.optString("server_version").trim(),
            )
        }

        private fun <T> Result<T>.mapPairingFailure(): Result<T> = fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                if (error is GameSaveException) {
                    Result.failure(error)
                } else {
                    val message = when (error) {
                        is IllegalArgumentException -> error.message ?: "配对请求无效"
                        is IOException -> "无法连接 SaveVault 云端：${error.message ?: "网络错误"}"
                        else -> "SaveVault 云端配对失败"
                    }
                    Result.failure(GameSaveException(message, retryable = error is IOException))
                }
            },
        )

        /** Device headers are shared by normal sync requests and the unauthenticated pairing request. */
        private fun applyDeviceHeaders(request: Request.Builder, identity: GameSaveDeviceIdentity?) {
            val platform = identity?.platform ?: "opensave-android"
            // Keep released servers observable during a staggered upgrade while
            // preferring the OpenSave-specific family in current game-save builds.
            for (family in listOf("X-OpenSave", "X-Eden")) {
                request.header("$family-Platform", platform)
                if (identity != null) {
                    request.header("$family-Device-ID", identity.id)
                    request.header("$family-Device-Name", DeviceHeaders.encodeName(identity.name))
                    if (identity.version.isNotBlank()) request.header("$family-Version", identity.version)
                }
            }
        }

        private fun authMessage(code: String): String = when (code) {
            TOKEN_REVOKED -> "此设备已被 SaveVault 云端移除，请重新配对"
            SHARED_TOKEN_DISABLED -> "SaveVault 云端已停用共享同步密钥，请用配对码连接本设备"
            else -> "同步密钥或设备令牌无效，请检查"
        }

        /** Accept a service root, including an optional reverse-proxy path, but never credentials or a query. */
        fun normalizeServiceUrl(value: String): String {
            val raw = value.trim()
            if (raw.isEmpty()) return ""
            val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("SaveVault 云端地址无效") }
            val scheme = uri.scheme.orEmpty().lowercase()
            require(scheme == "http" || scheme == "https") { "SaveVault 云端只支持 http 或 https" }
            require(!uri.host.isNullOrBlank()) { "SaveVault 云端地址缺少主机名" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "SaveVault 云端地址不能包含账号、查询参数或片段"
            }
            val rawPath = uri.rawPath.orEmpty()
            require(!rawPath.contains("%2f", ignoreCase = true) && !rawPath.contains("%5c", ignoreCase = true)) {
                "SaveVault 云端地址不能包含编码路径分隔符"
            }
            require(!rawPath.contains('\\')) { "SaveVault 云端地址不能包含反斜杠" }
            var path = rawPath.trimEnd('/')
            require(path.split('/').none { it == "." || it == ".." }) { "SaveVault 云端地址不能包含相对路径" }
            if (path.endsWith("/v1/opensave", ignoreCase = true)) {
                path = path.dropLast("/v1/opensave".length)
            }
            val authority = uri.rawAuthority ?: throw IllegalArgumentException("SaveVault 云端地址缺少主机名")
            return "$scheme://$authority$path"
        }

        private fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private const val MAX_GAME_NAME_LENGTH = 120
        private const val MAX_DEVICE_NAME_LENGTH = 80

        private fun isSafeScopeSnapshotId(value: String): Boolean =
            SCOPE_SNAPSHOT_ID_PATTERN.matches(value)
    }
}
