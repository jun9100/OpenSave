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
)

data class GameSaveArchiveName(
    val gameId: String,
    val branch: String,
    val snapshotId: String,
    val timestampMillis: Long,
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

    fun parseHeadFileName(name: String): String? = headNamePattern.matchEntire(name)?.groupValues?.get(1)

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

data class GameSaveHeadState(
    val snapshotId: String,
    val fileName: String,
    val manifestHash: String,
    val chain: List<String>,
    val savedAtMillis: Long,
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

/** Publishes the current save only after the archive itself is available in game-save. */
class GameSaveHeadPublisher(
    context: Context,
    private val config: GameSaveConfig,
) {
    private val appContext = context.applicationContext
    private val states = GameSaveHeadStore(appContext)
    private val device = GameSaveDeviceStore(appContext)

    fun publishSnapshot(identity: GameCloudIdentity, record: SnapshotRecord): Result<Unit> = runCatching {
        val archive = GameSaveCloudProtocol.parseArchiveName(record.remoteName)
            ?: error("本地快照名称不是可同步的 OpenSave 快照")
        require(archive.gameId == identity.gameId && archive.branch == "main") { "快照与游戏身份不匹配" }
        val previous = states.load(identity)
        val state = GameSaveHeadState(
            snapshotId = archive.snapshotId,
            fileName = record.remoteName,
            manifestHash = record.manifestHash,
            chain = if (previous?.snapshotId == archive.snapshotId) {
                previous.chain
            } else {
                GameSaveCloudProtocol.appendChain(previous?.chain.orEmpty(), archive.snapshotId)
            },
            savedAtMillis = archive.timestampMillis,
            publishedSnapshotId = previous?.publishedSnapshotId.orEmpty(),
            announcedAtMillis = previous?.announcedAtMillis ?: 0L,
        )
        if (state.publishedSnapshotId == archive.snapshotId) return@runCatching
        // Persist the current save before the network request. A periodic task
        // can retry this announcement if the snapshot upload succeeded first.
        states.save(identity, state)
        val announcedAt = max(System.currentTimeMillis(), state.announcedAtMillis + 1L)
        val (deviceId, deviceName) = device.identity()
        val head = GameSaveCloudHead(
            deviceId = deviceId,
            deviceName = deviceName,
            gameId = identity.gameId,
            snapshot = archive.snapshotId,
            file = record.remoteName,
            chain = state.chain,
            savedAt = Instant.ofEpochMilli(archive.timestampMillis).toString(),
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
                    publishedSnapshotId = archive.snapshotId,
                    announcedAtMillis = announcedAt,
                ),
            )
        } finally {
            headFile.delete()
        }
    }

    fun markRemoteRestore(identity: GameCloudIdentity, remote: RemoteSnapshot, manifestHash: String) {
        val archive = GameSaveCloudProtocol.parseArchiveName(remote.name) ?: return
        if (remote.type != identity.saveType.id || archive.gameId != identity.gameId || archive.branch != "main") return
        states.save(
            identity,
            GameSaveHeadState(
                snapshotId = archive.snapshotId,
                fileName = remote.name,
                manifestHash = manifestHash,
                chain = listOf(archive.snapshotId),
                savedAtMillis = archive.timestampMillis,
                publishedSnapshotId = archive.snapshotId,
            ),
        )
    }
}
