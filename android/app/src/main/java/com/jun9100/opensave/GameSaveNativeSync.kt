package com.jun9100.opensave

import android.content.Context
import java.io.File
import java.time.Instant

/** Device-unique archive names used by game-save's immutable OpenSave scopes. */
data class GameSaveNativeArchiveName(
    val gameId: String,
    val branch: String,
    val snapshotId: String,
)

/** A scope head that can be inspected or restored without an old ZIP head file. */
data class GameSaveNativeCandidate(
    val remote: RemoteSnapshot,
    val snapshot: GameSaveScopeSnapshot,
)

data class GameSaveNativeDecision(
    val action: GameSaveRemoteAction,
    val candidate: GameSaveNativeCandidate? = null,
    val message: String = "",
)

sealed interface GameSaveNativePublishResult {
    data class Confirmed(val head: GameSaveScopeSnapshot) : GameSaveNativePublishResult
    data class Conflict(val currentHead: GameSaveScopeSnapshot?) : GameSaveNativePublishResult
}

/**
 * Native scope naming mirrors OpenSave desktop's cloud.GameSaveSnapshotID and
 * cloud.GameSaveArchiveName. The server-facing suffix makes simultaneous
 * device snapshots immutable even when their local clock milliseconds match.
 */
internal object GameSaveNativeProtocol {
    private const val DEVICE_SUFFIX = "--dev-"
    private val localArchivePattern = Regex(
        "^([a-z0-9][a-z0-9_.-]{0,127})__([a-z0-9][a-z0-9-]{0,63})__(snap_[0-9]{1,16})\\.zip$",
        RegexOption.IGNORE_CASE,
    )
    private val nativeArchivePattern = Regex(
        "^([a-z0-9][a-z0-9_.-]{0,127})__([a-z0-9][a-z0-9-]{0,63})__(snap_[0-9]{1,16}--dev-[a-z0-9]+)\\.zip$",
        RegexOption.IGNORE_CASE,
    )

    fun localSnapshotId(archiveName: String): String? =
        localArchivePattern.matchEntire(archiveName)?.groupValues?.get(3)

    fun archiveName(archiveName: String): GameSaveNativeArchiveName? {
        val match = nativeArchivePattern.matchEntire(archiveName) ?: return null
        return GameSaveNativeArchiveName(
            gameId = match.groupValues[1],
            branch = match.groupValues[2],
            snapshotId = match.groupValues[3],
        )
    }

    fun deviceKey(deviceId: String): String = deviceId.lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' }
        .ifBlank { "android" }

    fun remoteSnapshotId(localSnapshotId: String, deviceId: String): String {
        require(localSnapshotId.matches(Regex("^snap_[0-9]{1,16}$"))) { "本地快照标识无效" }
        return "$localSnapshotId$DEVICE_SUFFIX${deviceKey(deviceId)}"
    }

    fun archiveName(identity: GameCloudIdentity, localSnapshotId: String, deviceId: String): String =
        "${identity.gameId}__main__${remoteSnapshotId(localSnapshotId, deviceId)}.zip"

    fun follows(scope: GameSaveScope, ancestor: String): Boolean {
        if (ancestor.isBlank() || scope.head == null) return false
        val snapshots = scope.snapshots
            .filterNot(GameSaveScopeSnapshot::conflict)
            .associateBy(GameSaveScopeSnapshot::id)
        var current = scope.head.id
        var foundAncestor = false
        val visited = mutableSetOf<String>()
        repeat(snapshots.size + 1) {
            if (!visited.add(current)) return false
            if (current == ancestor) foundAncestor = true
            val parent = snapshots[current]?.parent ?: return false
            if (parent.isBlank()) return foundAncestor
            current = parent
        }
        return foundAncestor
    }
}

/** Pure safety decision for a fetched CAS scope. It never chooses by clock time. */
internal object GameSaveNativeResolver {
    fun decide(
        identity: GameCloudIdentity,
        device: GameSaveDeviceIdentity,
        localState: GameSaveHeadState?,
        currentManifestHash: String,
        scope: GameSaveScope,
    ): GameSaveNativeDecision {
        val head = scope.head ?: return GameSaveNativeDecision(GameSaveRemoteAction.UP_TO_DATE)
        val archive = GameSaveNativeProtocol.archiveName(head.archive)
            ?: return review("云端当前快照名称无效")
        if (scope.type != identity.saveType.id || scope.gameId != identity.gameId ||
            archive.gameId != identity.gameId || archive.branch != "main" || archive.snapshotId != head.id
        ) {
            return review("云端当前快照不属于此游戏")
        }
        val candidate = GameSaveNativeCandidate(
            remote = RemoteSnapshot(
                type = scope.type,
                name = head.archive,
                sizeBytes = -1L,
                modified = head.receivedAt,
            ),
            snapshot = head,
        )
        val localRemoteId = localState?.snapshotId
            ?.takeIf { it.startsWith("snap_") && !it.contains("--dev-") }
            ?.let { GameSaveNativeProtocol.remoteSnapshotId(it, device.id) }
        if (head.id == localState?.nativeHead || head.id == localRemoteId) {
            return GameSaveNativeDecision(GameSaveRemoteAction.UP_TO_DATE, candidate)
        }

        val localStateKind = when {
            localState == null || localState.manifestHash.isBlank() || currentManifestHash.isBlank() -> GameSaveLocalSaveState.UNKNOWN
            localState.manifestHash == currentManifestHash -> GameSaveLocalSaveState.UNCHANGED
            else -> GameSaveLocalSaveState.CHANGED
        }
        val localPending = localState?.let { state ->
            state.snapshotId.isNotBlank() && state.snapshotId != state.nativeHead &&
                (localRemoteId == null || localRemoteId != state.nativeHead)
        } ?: false
        val follows = GameSaveNativeProtocol.follows(scope, localState?.nativeHead.orEmpty())
        if (follows && !localPending && localStateKind == GameSaveLocalSaveState.UNCHANGED) {
            return GameSaveNativeDecision(
                action = GameSaveRemoteAction.AUTO_RESTORE,
                candidate = candidate,
                message = "${head.deviceName.ifBlank { "另一台设备" }} 有延续当前存档的更新",
            )
        }
        return GameSaveNativeDecision(
            action = GameSaveRemoteAction.REVIEW_REQUIRED,
            candidate = candidate,
            message = when {
                localState == null || localState.nativeHead.isBlank() -> "本机没有已确认的云端基线，已等待确认"
                localPending || localStateKind == GameSaveLocalSaveState.CHANGED -> "本机存档已有未确认变化，已保留本地并等待确认"
                !follows -> "远端存档与本机历史分叉，已等待确认"
                else -> "远端存档需要确认"
            },
        )
    }

    private fun review(message: String) = GameSaveNativeDecision(
        action = GameSaveRemoteAction.REVIEW_REQUIRED,
        message = message,
    )
}

/**
 * Persists native scope confirmation separately from the local snapshot and
 * enforces archive -> CAS -> conflict order. A network retry can therefore
 * create at most an idempotent archive or a recoverable conflict copy.
 */
class GameSaveNativeSync(
    context: Context,
    private val config: GameSaveConfig,
    private val client: GameSaveClient = GameSaveClient(config, GameSaveDeviceStore(context.applicationContext).identity()),
) {
    private val appContext = context.applicationContext
    private val states = GameSaveHeadStore(appContext)
    private val device = GameSaveDeviceStore(appContext)

    fun check(identity: GameCloudIdentity, currentManifestHash: String): Result<GameSaveNativeDecision> = runCatching {
        val scope = client.getScope(identity.saveType, identity.gameId).getOrThrow()
        val state = states.load(identity)
        val decision = GameSaveNativeResolver.decide(identity, device.identity(), state, currentManifestHash, scope)
        val candidate = decision.candidate
        if (decision.action == GameSaveRemoteAction.UP_TO_DATE && candidate != null && state != null &&
            candidate.snapshot.id == GameSaveNativeProtocol.remoteSnapshotIdOrNull(state.snapshotId, device.identity().id)
        ) {
            states.save(identity, state.copy(fileName = candidate.remote.name, nativeHead = candidate.snapshot.id))
        }
        decision
    }

    fun publishSnapshot(
        identity: GameCloudIdentity,
        record: SnapshotRecord,
        archiveFile: File,
    ): Result<GameSaveNativePublishResult> = runCatching {
        require(record.cloudGameId == identity.gameId && record.saveType == identity.saveType.id) {
            "本地快照与游戏同步范围不匹配"
        }
        require(archiveFile.isFile) { "本地快照不存在" }
        val localSnapshotId = GameSaveNativeProtocol.localSnapshotId(record.remoteName)
            ?: error("本地快照名称不是可同步的 OpenSave 快照")
        val identityDevice = device.identity()
        val remoteSnapshotId = GameSaveNativeProtocol.remoteSnapshotId(localSnapshotId, identityDevice.id)
        val remoteArchiveName = GameSaveNativeProtocol.archiveName(identity, localSnapshotId, identityDevice.id)
        val digest = Hashing.sha256(archiveFile).lowercase()
        require(record.sha256.isBlank() || digest == record.sha256.lowercase()) { "本地快照摘要校验失败" }
        val scope = client.getScope(identity.saveType, identity.gameId).getOrThrow()
        val previous = states.load(identity)
        val provisional = localState(
            identity = identity,
            record = record,
            archiveFile = archiveFile,
            localSnapshotId = localSnapshotId,
            archiveName = remoteArchiveName,
            previous = previous,
            nativeHead = previous?.nativeHead.orEmpty(),
        )

        scope.head?.takeIf { it.id == remoteSnapshotId }?.let { head ->
            require(head.archive == remoteArchiveName && head.contentDigest.equals(digest, ignoreCase = true)) {
                "云端当前存档与本机不可变快照不一致"
            }
            states.save(identity, provisional.copy(nativeHead = head.id))
            return@runCatching GameSaveNativePublishResult.Confirmed(head)
        }
        client.upload(archiveFile, identity.saveType, remoteArchiveName).getOrThrow()

        if (scope.head == null && previous?.nativeHead?.isNotBlank() == true) {
            error("云端当前存档丢失，已拒绝自动创建替代版本")
        }
        if (scope.head != null && previous?.nativeHead != scope.head.id) {
            client.putScopeConflict(identity.saveType, identity.gameId, candidate(remoteSnapshotId, remoteArchiveName, previous?.nativeHead.orEmpty(), digest, record))
                .getOrThrow()
            states.save(identity, provisional)
            return@runCatching GameSaveNativePublishResult.Conflict(scope.head)
        }

        val parent = scope.head?.id.orEmpty()
        val candidate = candidate(remoteSnapshotId, remoteArchiveName, parent, digest, record)
        try {
            val stored = client.putScopeSnapshot(identity.saveType, identity.gameId, candidate, parent).getOrThrow()
            states.save(identity, provisional.copy(nativeHead = stored.id))
            GameSaveNativePublishResult.Confirmed(stored)
        } catch (error: GameSaveScopeConflictException) {
            client.putScopeConflict(identity.saveType, identity.gameId, candidate).getOrThrow()
            states.save(identity, provisional)
            val currentHead = client.getScope(identity.saveType, identity.gameId).getOrThrow().head
            GameSaveNativePublishResult.Conflict(currentHead)
        }
    }

    /** Records a selected remote head locally without publishing a new head ZIP or CAS write. */
    fun markRemoteRestore(
        identity: GameCloudIdentity,
        candidate: GameSaveNativeCandidate,
        manifestHash: String,
        archiveFile: File,
    ): Result<Unit> = runCatching {
        val parsed = GameSaveNativeProtocol.archiveName(candidate.remote.name)
            ?: error("远端原生快照名称无效")
        require(parsed.gameId == identity.gameId && parsed.branch == "main" && parsed.snapshotId == candidate.snapshot.id) {
            "远端原生快照身份不匹配"
        }
        require(Hashing.sha256(archiveFile).equals(candidate.snapshot.contentDigest, ignoreCase = true)) {
            "远端原生快照摘要校验失败"
        }
        val contentAt = runCatching { GameSaveSnapshotClock.contentModifiedAtMillis(archiveFile) }.getOrDefault(0L)
        val previous = states.load(identity)
        states.save(
            identity,
            GameSaveHeadState(
                snapshotId = candidate.snapshot.id,
                fileName = candidate.remote.name,
                manifestHash = manifestHash,
                chain = listOf(candidate.snapshot.id),
                savedAtMillis = contentAt.takeIf { it > 0L } ?: recordTime(candidate.snapshot),
                contentModifiedAtMillis = contentAt,
                nativeHead = candidate.snapshot.id,
                announcedAtMillis = previous?.announcedAtMillis ?: 0L,
            ),
        )
    }

    private fun localState(
        identity: GameCloudIdentity,
        record: SnapshotRecord,
        archiveFile: File,
        localSnapshotId: String,
        archiveName: String,
        previous: GameSaveHeadState?,
        nativeHead: String,
    ): GameSaveHeadState {
        val contentAt = runCatching { GameSaveSnapshotClock.contentModifiedAtMillis(archiveFile) }.getOrDefault(0L)
        return GameSaveHeadState(
            snapshotId = localSnapshotId,
            fileName = archiveName,
            manifestHash = record.manifestHash,
            chain = listOf(localSnapshotId),
            savedAtMillis = contentAt.takeIf { it > 0L } ?: record.createdAtMillis,
            contentModifiedAtMillis = contentAt,
            nativeHead = nativeHead,
            publishedSnapshotId = previous?.publishedSnapshotId.orEmpty(),
            announcedAtMillis = previous?.announcedAtMillis ?: 0L,
        )
    }

    private fun candidate(
        id: String,
        archive: String,
        parent: String,
        digest: String,
        record: SnapshotRecord,
    ) = GameSaveScopeCandidate(
        id = id,
        archive = archive,
        parent = parent,
        contentDigest = digest,
        createdAt = Instant.ofEpochMilli(record.createdAtMillis).toString(),
    )

    private fun recordTime(snapshot: GameSaveScopeSnapshot): Long = runCatching {
        Instant.parse(snapshot.receivedAt).toEpochMilli()
    }.getOrElse {
        Instant.parse(snapshot.createdAt).toEpochMilli()
    }
}

private fun GameSaveNativeProtocol.remoteSnapshotIdOrNull(snapshotId: String, deviceId: String): String? =
    snapshotId.takeIf { it.matches(Regex("^snap_[0-9]{1,16}$")) }
        ?.let { remoteSnapshotId(it, deviceId) }
