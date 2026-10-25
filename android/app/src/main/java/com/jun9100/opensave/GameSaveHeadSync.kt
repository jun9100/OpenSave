package com.jun9100.opensave

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/** The current-save announcement format shared with the desktop OpenSave daemon. */
data class GameSaveCloudHead(
    val version: Int = GameSaveCloudProtocol.HEAD_VERSION,
    val deviceId: String,
    val deviceName: String,
    val gameId: String,
    val branch: String = "main",
    val snapshot: String,
    val file: String,
    val chain: List<String>,
    val savedAt: String,
    // Present only on clients that prove this is the save data's own mtime,
    // not the later time a snapshot happened to be created or uploaded.
    val contentModifiedAt: String = "",
)

data class GameSaveArchiveName(
    val gameId: String,
    val branch: String,
    val snapshotId: String,
    val timestampMillis: Long,
)

data class GameSaveHeadAnnouncementName(
    val gameId: String,
    val deviceKey: String,
    val announcedAtMillis: Long,
)

/** A validated remote current-save announcement and its matching archive. */
data class GameSaveRemoteHead(
    val announcement: RemoteSnapshot,
    val archive: RemoteSnapshot,
    val head: GameSaveCloudHead,
    val announcedAtMillis: Long,
    val savedAtMillis: Long,
    val contentModifiedAtMillis: Long,
    val followsLocal: Boolean,
) {
    val hasTrustedContentTime: Boolean get() = contentModifiedAtMillis > 0L
}

enum class GameSaveLocalSaveState {
    UNCHANGED,
    CHANGED,
    UNKNOWN,
}

enum class GameSaveRemoteAction {
    UP_TO_DATE,
    AUTO_RESTORE,
    REVIEW_REQUIRED,
    WAIT_FOR_REMOTE,
}

/** The only AUTO_RESTORE result is a verified descendant of an unchanged local save. */
data class GameSaveHeadDecision(
    val action: GameSaveRemoteAction,
    val localState: GameSaveLocalSaveState,
    val candidate: GameSaveRemoteHead? = null,
    val message: String = "",
)

/**
 * Keep Android's announcement wire format byte-for-byte compatible with
 * internal/cloud/heads.go in the desktop client. A ZIP head lets all existing
 * providers store it without a service-specific metadata API.
 */
object GameSaveCloudProtocol {
    const val HEAD_VERSION = 1
    const val MAX_HEAD_CHAIN = 64
    private const val HEAD_ENTRY = "head.json"
    private const val MAX_HEAD_BYTES = 64 * 1024
    private val archiveNamePattern = Regex("^([a-z0-9][a-z0-9_.-]{0,127})__([a-z0-9][a-z0-9-]{0,63})__(snap_([0-9]{1,16}))\\.zip$", RegexOption.IGNORE_CASE)
    private val headNamePattern = Regex("^([a-z0-9][a-z0-9_.-]{0,127})__head-([a-z0-9]+)-([0-9]{1,16})\\.zip$", RegexOption.IGNORE_CASE)

    fun parseArchiveName(name: String): GameSaveArchiveName? {
        val match = archiveNamePattern.matchEntire(name) ?: return null
        val timestamp = match.groupValues[4].toLongOrNull() ?: return null
        return GameSaveArchiveName(
            gameId = match.groupValues[1],
            branch = match.groupValues[2],
            snapshotId = match.groupValues[3],
            timestampMillis = timestamp,
        )
    }

    fun headFileName(gameId: String, deviceId: String, announcedAtMillis: Long): String {
        require(gameId.matches(Regex("^[a-z0-9][a-z0-9_.-]{0,127}$", RegexOption.IGNORE_CASE))) {
            "游戏同步标识无效"
        }
        require(announcedAtMillis > 0) { "同步公告时间无效" }
        return "${gameId}__head-${deviceKey(deviceId)}-$announcedAtMillis.zip"
    }

    fun parseHeadAnnouncementName(name: String): GameSaveHeadAnnouncementName? {
        val match = headNamePattern.matchEntire(name) ?: return null
        val announcedAtMillis = match.groupValues[3].toLongOrNull() ?: return null
        return GameSaveHeadAnnouncementName(
            gameId = match.groupValues[1],
            deviceKey = match.groupValues[2],
            announcedAtMillis = announcedAtMillis,
        )
    }

    fun parseHeadFileName(name: String): String? = parseHeadAnnouncementName(name)?.gameId

    fun snapshotMillis(snapshotId: String): Long = snapshotId
        .removePrefix("snap_")
        .toLongOrNull()
        ?.takeIf { it > 0L }
        ?: 0L

    fun deviceKey(deviceId: String): String = deviceId.lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' }
        .ifBlank { "android" }

    fun appendChain(chain: List<String>, snapshotId: String): List<String> {
        if (chain.lastOrNull() == snapshotId) return chain.takeLast(MAX_HEAD_CHAIN)
        return (chain + snapshotId).takeLast(MAX_HEAD_CHAIN)
    }

    fun writeHead(file: File, head: GameSaveCloudHead) {
        require(head.version == HEAD_VERSION) { "不支持的同步公告版本" }
        require(head.branch == "main") { "不支持的同步分支" }
        require(parseArchiveName(head.file)?.let { it.gameId == head.gameId && it.snapshotId == head.snapshot } == true) {
            "同步公告快照不匹配"
        }
        val body = JSONObject()
            .put("version", head.version)
            .put("deviceId", head.deviceId)
            .put("deviceName", head.deviceName)
            .put("gameId", head.gameId)
            .put("branch", head.branch)
            .put("snapshot", head.snapshot)
            .put("file", head.file)
            .put("chain", JSONArray(head.chain))
            .put("savedAt", head.savedAt)
            .put("contentModifiedAt", head.contentModifiedAt)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_HEAD_BYTES) { "同步公告过大" }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            zip.putNextEntry(ZipEntry(HEAD_ENTRY))
            zip.write(body)
            zip.closeEntry()
        }
    }

    fun readHead(file: File): GameSaveCloudHead {
        ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != HEAD_ENTRY || entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val body = readLimited(zip, MAX_HEAD_BYTES)
                zip.closeEntry()
                val json = JSONObject(String(body, Charsets.UTF_8))
                val head = GameSaveCloudHead(
                    version = json.optInt("version"),
                    deviceId = json.optString("deviceId").trim(),
                    deviceName = json.optString("deviceName").trim(),
                    gameId = json.optString("gameId").trim(),
                    branch = json.optString("branch").trim(),
                    snapshot = json.optString("snapshot").trim(),
                    file = json.optString("file").trim(),
                    chain = json.optJSONArray("chain")?.let { values ->
                        buildList {
                            for (index in 0 until values.length()) {
                                values.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                            }
                        }
                    }.orEmpty(),
                    savedAt = json.optString("savedAt").trim(),
                    contentModifiedAt = json.optString("contentModifiedAt").trim(),
                )
                require(head.version == HEAD_VERSION) { "不支持的同步公告版本" }
                require(head.deviceId.isNotBlank() && head.gameId.isNotBlank() && head.deviceName.isNotBlank()) {
                    "同步公告缺少设备或游戏信息"
                }
                require(head.branch == "main") { "不支持的同步分支" }
                require(parseArchiveName(head.file)?.let { it.gameId == head.gameId && it.snapshotId == head.snapshot } == true) {
                    "同步公告快照不匹配"
                }
                require(head.chain.size <= MAX_HEAD_CHAIN && head.chain.lastOrNull() == head.snapshot) {
                    "同步公告链无效"
                }
                return head
            }
        }
        error("同步公告中没有 $HEAD_ENTRY")
    }

    private fun readLimited(input: ZipInputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "同步公告过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

/**
 * Applies the desktop cloud-sync rule to Android: only a remote head that
 * carries this device's known snapshot can replace an unchanged local save.
 * A head ZIP is immutable, so callers can cache it by its file name.
 */
internal object GameSaveHeadResolver {
    fun decide(
        identity: GameCloudIdentity,
        localDeviceId: String,
        localState: GameSaveHeadState?,
        currentManifestHash: String,
        remoteSnapshots: List<RemoteSnapshot>,
        readHead: (RemoteSnapshot) -> GameSaveCloudHead,
    ): GameSaveHeadDecision {
        val localSaveState = localSaveState(localState, currentManifestHash)
        val ownDeviceKey = GameSaveCloudProtocol.deviceKey(localDeviceId)
        val relevant = remoteSnapshots.asSequence()
            .filter { it.type == identity.saveType.id }
            .mapNotNull { remote ->
                GameSaveCloudProtocol.parseHeadAnnouncementName(remote.name)
                    ?.takeIf { parsed -> parsed.gameId == identity.gameId && parsed.deviceKey != ownDeviceKey }
                    ?.let { parsed -> parsed to remote }
            }
            .groupBy { (parsed, _) -> parsed.deviceKey }
            .map { (_, announcements) -> announcements.maxBy { (parsed, _) -> parsed.announcedAtMillis } }

        if (relevant.isEmpty()) {
            return GameSaveHeadDecision(GameSaveRemoteAction.UP_TO_DATE, localSaveState)
        }

        val archives = remoteSnapshots
            .filter { it.type == identity.saveType.id }
            .associateBy(RemoteSnapshot::name)
        val candidates = mutableListOf<GameSaveRemoteHead>()
        val blocked = mutableListOf<String>()
        for ((announcementName, announcement) in relevant) {
            val loadedHead = runCatching { readHead(announcement) }
            if (loadedHead.isFailure) {
                blocked += "无法读取远端同步公告"
                continue
            }
            val head = loadedHead.getOrThrow()
            if (head.gameId != identity.gameId ||
                head.branch != "main" ||
                GameSaveCloudProtocol.deviceKey(head.deviceId) != announcementName.deviceKey
            ) {
                blocked += "远端同步公告身份不匹配"
                continue
            }
            val archiveName = GameSaveCloudProtocol.parseArchiveName(head.file)
            if (archiveName == null || archiveName.gameId != identity.gameId ||
                archiveName.branch != "main" || archiveName.snapshotId != head.snapshot
            ) {
                blocked += "远端同步公告快照无效"
                continue
            }
            val archive = archives[head.file]
            if (archive == null) {
                blocked += "远端快照尚未上传完成"
                continue
            }
            if (head.snapshot == localState?.snapshotId) continue
            val followsLocal = localState?.snapshotId
                ?.takeIf(String::isNotBlank)
                ?.let(head.chain::contains)
                ?: false
            candidates += GameSaveRemoteHead(
                announcement = announcement,
                archive = archive,
                head = head,
                announcedAtMillis = announcementName.announcedAtMillis,
                savedAtMillis = savedAtMillis(head, archiveName),
                contentModifiedAtMillis = contentModifiedAtMillis(head),
                followsLocal = followsLocal,
            )
        }

        // An unreadable or incomplete current-save announcement must not be
        // bypassed by uploading this device's local copy as a newer head.
        if (blocked.isNotEmpty()) {
            return GameSaveHeadDecision(
                action = GameSaveRemoteAction.WAIT_FOR_REMOTE,
                localState = localSaveState,
                message = blocked.distinct().joinToString("；"),
            )
        }

        val candidate = candidates
            .filter { candidate -> isAhead(candidate, localState) }
            .maxWithOrNull(
                compareBy<GameSaveRemoteHead> { it.hasTrustedContentTime }
                    .thenBy { it.contentModifiedAtMillis }
                    .thenBy { it.savedAtMillis }
                    .thenBy { it.announcedAtMillis },
            )
            ?: return GameSaveHeadDecision(GameSaveRemoteAction.UP_TO_DATE, localSaveState)

        val localContentAt = localState?.contentModifiedAtMillis ?: 0L
        val safeContinuation = candidate.followsLocal &&
            candidate.hasTrustedContentTime &&
            localContentAt > 0L &&
            candidate.contentModifiedAtMillis >= localContentAt
        return if (safeContinuation && localSaveState == GameSaveLocalSaveState.UNCHANGED) {
            GameSaveHeadDecision(
                action = GameSaveRemoteAction.AUTO_RESTORE,
                localState = localSaveState,
                candidate = candidate,
                message = "${candidate.head.deviceName} 有延续当前存档的更新",
            )
        } else {
            GameSaveHeadDecision(
                action = GameSaveRemoteAction.REVIEW_REQUIRED,
                localState = localSaveState,
                candidate = candidate,
                message = when (localSaveState) {
                    GameSaveLocalSaveState.UNCHANGED -> when {
                        !candidate.hasTrustedContentTime -> "远端存档来自旧版客户端，缺少可验证的内容时间，已等待确认"
                        localContentAt <= 0L -> "本机没有可验证的内容时间，已等待确认"
                        else -> "远端存档与本机历史分叉，已等待确认"
                    }
                    GameSaveLocalSaveState.CHANGED -> "本机存档已有变化，已保留本地并等待确认"
                    GameSaveLocalSaveState.UNKNOWN -> "本机没有可验证的同步基线，已等待确认"
                },
            )
        }
    }

    private fun localSaveState(
        state: GameSaveHeadState?,
        currentManifestHash: String,
    ): GameSaveLocalSaveState = when {
        state == null || state.manifestHash.isBlank() || currentManifestHash.isBlank() -> GameSaveLocalSaveState.UNKNOWN
        state.manifestHash == currentManifestHash -> GameSaveLocalSaveState.UNCHANGED
        else -> GameSaveLocalSaveState.CHANGED
    }

    private fun isAhead(candidate: GameSaveRemoteHead, localState: GameSaveHeadState?): Boolean {
        if (!candidate.hasTrustedContentTime) return true
        val localAt = localState?.contentModifiedAtMillis ?: 0L
        return localAt <= 0L || candidate.contentModifiedAtMillis >= localAt
    }

    private fun savedAtMillis(head: GameSaveCloudHead, archive: GameSaveArchiveName): Long =
        runCatching { Instant.parse(head.savedAt).toEpochMilli() }
            .getOrDefault(archive.timestampMillis)
            .takeIf { it > 0L }
            ?: archive.timestampMillis

    private fun contentModifiedAtMillis(head: GameSaveCloudHead): Long =
        runCatching { Instant.parse(head.contentModifiedAt).toEpochMilli() }
            .getOrDefault(0L)
            .takeIf { it > 0L }
            ?: 0L
}

data class GameSaveHeadState(
    val snapshotId: String,
    val fileName: String,
    val manifestHash: String,
    val chain: List<String>,
    val savedAtMillis: Long,
    val contentModifiedAtMillis: Long = 0L,
    val publishedSnapshotId: String = "",
    val announcedAtMillis: Long = 0L,
)

/** Persists only public snapshot identifiers and hashes, never the sync key. */
class GameSaveHeadStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun load(identity: GameCloudIdentity): GameSaveHeadState? = runCatching {
        val json = preferences.getString(key(identity), null)?.let(::JSONObject) ?: return null
        val chain = json.optJSONArray("chain")?.let { values ->
            buildList {
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        }.orEmpty()
        GameSaveHeadState(
            snapshotId = json.getString("snapshotId"),
            fileName = json.getString("fileName"),
            manifestHash = json.optString("manifestHash"),
            chain = chain,
            savedAtMillis = json.getLong("savedAtMillis"),
            contentModifiedAtMillis = json.optLong("contentModifiedAtMillis"),
            publishedSnapshotId = json.optString("publishedSnapshotId"),
            announcedAtMillis = json.optLong("announcedAtMillis"),
        ).also { state ->
            require(state.chain.lastOrNull() == state.snapshotId) { "本地同步链无效" }
        }
    }.getOrNull()

    @Synchronized
    fun save(identity: GameCloudIdentity, state: GameSaveHeadState) {
        require(state.snapshotId.isNotBlank() && state.fileName.isNotBlank()) { "本地同步状态无效" }
        require(state.chain.lastOrNull() == state.snapshotId) { "本地同步链无效" }
        val json = JSONObject()
            .put("snapshotId", state.snapshotId)
            .put("fileName", state.fileName)
            .put("manifestHash", state.manifestHash)
            .put("chain", JSONArray(state.chain.takeLast(GameSaveCloudProtocol.MAX_HEAD_CHAIN)))
            .put("savedAtMillis", state.savedAtMillis)
            .put("contentModifiedAtMillis", state.contentModifiedAtMillis)
            .put("publishedSnapshotId", state.publishedSnapshotId)
            .put("announcedAtMillis", state.announcedAtMillis)
        check(preferences.edit().putString(key(identity), json.toString()).commit()) { "无法保存同步状态" }
    }

    private fun key(identity: GameCloudIdentity): String = "head_" +
        Hashing.sha256("${identity.saveType.id}\u0000${identity.gameId}").take(32)

    private companion object {
        const val PREFERENCES = "opensave_game_save_heads"
    }
}

/** Reads preserved ZIP entry times instead of trusting when OpenSave copied a save. */
internal object GameSaveSnapshotClock {
    fun contentModifiedAtMillis(archive: File): Long {
        require(archive.isFile) { "同步快照文件不存在" }
        return ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map(ZipEntry::getTime)
                .filter { it > 0L }
                .maxOrNull()
        } ?: 0L
    }
}

/** A restored save can become this device's baseline before it is safe to announce it. */
internal data class GameSaveRemoteRestorePlan(
    val state: GameSaveHeadState,
    val shouldPublish: Boolean,
)

/** Keeps upload or snapshot creation time from being promoted to a save-content time. */
internal object GameSaveRemoteRestorePlanner {
    fun plan(
        identity: GameCloudIdentity,
        remote: RemoteSnapshot,
        manifestHash: String,
        archiveFile: File,
        remoteHead: GameSaveRemoteHead?,
        previous: GameSaveHeadState?,
    ): GameSaveRemoteRestorePlan? {
        val archive = GameSaveCloudProtocol.parseArchiveName(remote.name) ?: return null
        if (remote.type != identity.saveType.id || archive.gameId != identity.gameId || archive.branch != "main") return null

        val matchingHead = remoteHead?.takeIf { candidate ->
            candidate.archive.type == remote.type &&
                candidate.archive.name == remote.name &&
                candidate.head.snapshot == archive.snapshotId &&
                candidate.head.file == remote.name
        }
        val contentModifiedAt = runCatching { GameSaveSnapshotClock.contentModifiedAtMillis(archiveFile) }
            .getOrDefault(0L)
            .takeIf { it > 0L }
            ?: matchingHead?.contentModifiedAtMillis?.takeIf { it > 0L }

        // The fallback is only local bookkeeping for the restored baseline. It
        // must never be serialized as a verified save-content modification time.
        val baselineSavedAt = contentModifiedAt
            ?: matchingHead?.savedAtMillis?.takeIf { it > 0L }
            ?: archive.timestampMillis
        return GameSaveRemoteRestorePlan(
            state = GameSaveHeadState(
                snapshotId = archive.snapshotId,
                fileName = remote.name,
                manifestHash = manifestHash,
                // A restore is the new local baseline, even when the archive
                // came from a remote chain. Subsequent edits extend it.
                chain = listOf(archive.snapshotId),
                savedAtMillis = baselineSavedAt,
                contentModifiedAtMillis = contentModifiedAt ?: 0L,
                publishedSnapshotId = "",
                announcedAtMillis = previous?.announcedAtMillis ?: 0L,
            ),
            shouldPublish = contentModifiedAt != null,
        )
    }
}

class GameSaveDeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun identity(): Pair<String, String> {
        val id = preferences.getString(DEVICE_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().also { generated ->
                check(preferences.edit().putString(DEVICE_ID, generated).commit()) { "无法保存设备标识" }
            }
        val name = Build.MODEL?.trim()?.takeIf(String::isNotBlank)?.take(80) ?: "Android"
        return id to name
    }

    private companion object {
        const val PREFERENCES = "opensave_game_save_device"
        const val DEVICE_ID = "device_id"
    }
}

/** Downloads and validates only the current remote head files relevant to one game. */
class GameSaveHeadReader(
    context: Context,
    config: GameSaveConfig,
    private val client: GameSaveClient = GameSaveClient(config),
) {
    private val appContext = context.applicationContext
    private val device = GameSaveDeviceStore(appContext)

    fun check(
        identity: GameCloudIdentity,
        currentManifestHash: String,
        remoteSnapshots: List<RemoteSnapshot>,
    ): GameSaveHeadDecision {
        val (deviceId, _) = device.identity()
        return GameSaveHeadResolver.decide(
            identity = identity,
            localDeviceId = deviceId,
            localState = GameSaveHeadStore(appContext).load(identity),
            currentManifestHash = currentManifestHash,
            remoteSnapshots = remoteSnapshots,
            readHead = ::read,
        )
    }

    private fun read(remote: RemoteSnapshot): GameSaveCloudHead {
        val directory = File(appContext.cacheDir, "game-save-head-cache")
        require(directory.exists() || directory.mkdirs()) { "无法创建同步公告缓存目录" }
        val file = File(directory, "${Hashing.sha256("${remote.type}\\u0000${remote.name}").take(32)}.zip")
        try {
            if (!file.isFile) client.download(remote, file).getOrThrow()
            return GameSaveCloudProtocol.readHead(file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}

/** Publishes the current save only after the archive itself is available in game-save. */
class GameSaveHeadPublisher(
    context: Context,
    private val config: GameSaveConfig,
) {
    private val appContext = context.applicationContext
    private val states = GameSaveHeadStore(appContext)
    private val device = GameSaveDeviceStore(appContext)

    fun publishSnapshot(identity: GameCloudIdentity, record: SnapshotRecord, archiveFile: File): Result<Unit> = runCatching {
        val archive = GameSaveCloudProtocol.parseArchiveName(record.remoteName)
            ?: error("本地快照名称不是可同步的 OpenSave 快照")
        require(archive.gameId == identity.gameId && archive.branch == "main") { "快照与游戏身份不匹配" }
        val previous = states.load(identity)
        val contentModifiedAt = GameSaveSnapshotClock.contentModifiedAtMillis(archiveFile)
        require(contentModifiedAt > 0L) { "快照没有可用的存档修改时间，已拒绝更新跨设备当前存档" }
        // A mirror or an old migration copy can be backed up after the live
        // directory. It must never replace the newer current-save head merely
        // because its upload happened later.
        if (previous != null && previous.contentModifiedAtMillis > 0L &&
            (contentModifiedAt < previous.contentModifiedAtMillis ||
                contentModifiedAt == previous.contentModifiedAtMillis && archive.snapshotId != previous.snapshotId)
        ) {
            // A restore may have left the current remote snapshot unpublished
            // from this device. Announce that saved state, never the later
            // archive that merely copied identical (or older) files.
            if (previous.publishedSnapshotId != previous.snapshotId) {
                publishCurrentState(identity, previous).getOrThrow()
            }
            return@runCatching
        }
        val state = GameSaveHeadState(
            snapshotId = archive.snapshotId,
            fileName = record.remoteName,
            manifestHash = record.manifestHash,
            chain = if (previous?.snapshotId == archive.snapshotId) {
                previous.chain
            } else {
                GameSaveCloudProtocol.appendChain(previous?.chain.orEmpty(), archive.snapshotId)
            },
            savedAtMillis = contentModifiedAt,
            contentModifiedAtMillis = contentModifiedAt,
            publishedSnapshotId = previous?.publishedSnapshotId.orEmpty(),
            announcedAtMillis = previous?.announcedAtMillis ?: 0L,
        )
        if (state.publishedSnapshotId == archive.snapshotId) return@runCatching
        // Persist the current save before the network request. A periodic task
        // can retry this announcement if the snapshot upload succeeded first.
        states.save(identity, state)
        publishCurrentState(identity, state).getOrThrow()
    }

    /** Publishes an already persisted current-save state without changing its lineage. */
    private fun publishCurrentState(identity: GameCloudIdentity, state: GameSaveHeadState): Result<Unit> = runCatching {
        require(state.contentModifiedAtMillis > 0L) {
            "当前存档缺少可验证的内容修改时间，已拒绝更新跨设备当前存档"
        }
        val announcedAt = max(System.currentTimeMillis(), state.announcedAtMillis + 1L)
        val (deviceId, deviceName) = device.identity()
        val head = GameSaveCloudHead(
            deviceId = deviceId,
            deviceName = deviceName,
            gameId = identity.gameId,
            snapshot = state.snapshotId,
            file = state.fileName,
            chain = state.chain,
            savedAt = Instant.ofEpochMilli(state.contentModifiedAtMillis).toString(),
            contentModifiedAt = Instant.ofEpochMilli(state.contentModifiedAtMillis).toString(),
        )
        val directory = File(appContext.cacheDir, "game-save-heads")
        require(directory.exists() || directory.mkdirs()) { "无法创建同步公告目录" }
        val headFile = File(directory, GameSaveCloudProtocol.headFileName(identity.gameId, deviceId, announcedAt))
        try {
            GameSaveCloudProtocol.writeHead(headFile, head)
            GameSaveClient(config).upload(headFile, identity.saveType, headFile.name).getOrThrow()
            states.save(
                identity,
                state.copy(
                    publishedSnapshotId = state.snapshotId,
                    announcedAtMillis = announcedAt,
                ),
            )
        } finally {
            headFile.delete()
        }
    }

    fun markRemoteRestore(
        identity: GameCloudIdentity,
        remote: RemoteSnapshot,
        manifestHash: String,
        archiveFile: File,
        remoteHead: GameSaveRemoteHead? = null,
    ): Result<Unit> = runCatching {
        val previous = states.load(identity)
        val plan = GameSaveRemoteRestorePlanner.plan(
            identity = identity,
            remote = remote,
            manifestHash = manifestHash,
            archiveFile = archiveFile,
            remoteHead = remoteHead,
            previous = previous,
        ) ?: return@runCatching
        states.save(identity, plan.state)
        if (plan.shouldPublish) publishCurrentState(identity, plan.state).getOrThrow()
    }
}
