package com.jun9100.opensave.ops

import android.content.Context
import com.jun9100.opensave.AutoBackupFingerprint
import com.jun9100.opensave.AutoBackupPolicy
import com.jun9100.opensave.CloudPullCandidate
import com.jun9100.opensave.ContentHash
import com.jun9100.opensave.CrossDeviceArchiveFormat
import com.jun9100.opensave.CrossDevicePlan
import com.jun9100.opensave.CrossDeviceRestorePolicy
import com.jun9100.opensave.DetectedGame
import com.jun9100.opensave.GameCloudFacts
import com.jun9100.opensave.GameCloudIdentity
import com.jun9100.opensave.GameCloudNaming
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GameSaveAuthGuard
import com.jun9100.opensave.GameSaveClient
import com.jun9100.opensave.GameSaveCloudGameMember
import com.jun9100.opensave.GameSaveCloudUpdate
import com.jun9100.opensave.GameSaveConfig
import com.jun9100.opensave.GameSaveConfigStore
import com.jun9100.opensave.GameSaveDeviceStore
import com.jun9100.opensave.GameSaveHeadPublisher
import com.jun9100.opensave.GameSaveHeadReader
import com.jun9100.opensave.GameSaveNativeProtocol
import com.jun9100.opensave.GameSaveNativePublishResult
import com.jun9100.opensave.GameSaveNativeSync
import com.jun9100.opensave.GameSaveRemoteAction
import com.jun9100.opensave.GameSaveScopeGoneException
import com.jun9100.opensave.GameSaveScopesUnsupportedException
import com.jun9100.opensave.GameSnapshotLocation
import com.jun9100.opensave.GameSnapshotManifest
import com.jun9100.opensave.Hashing
import com.jun9100.opensave.PathAccess
import com.jun9100.opensave.PathPolicy
import com.jun9100.opensave.RemoteSnapshot
import com.jun9100.opensave.RestoreRequest
import com.jun9100.opensave.SafStorage
import com.jun9100.opensave.SaveSignatureReader
import com.jun9100.opensave.ScopeTombstoneStore
import com.jun9100.opensave.SnapshotEngine
import com.jun9100.opensave.SnapshotNaming
import com.jun9100.opensave.SnapshotRecord
import com.jun9100.opensave.SnapshotRepository
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.SourceSelection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Outcome of backing up one game ([GameOperations.captureLocked]). */
internal data class GameCaptureOutcome(
    val record: SnapshotRecord,
    val fileCount: Int,
    val uploadError: String?,
    val syncNotice: String? = null,
    val cloudUpdate: GameSaveCloudUpdate? = null,
    /** True when the content matched the newest snapshot and no new one was written. */
    val reused: Boolean = false,
    /** True when a reused snapshot that had never been uploaded was uploaded now. */
    val uploadedReused: Boolean = false,
    /** Encoded [com.jun9100.opensave.SaveSignature] read before the capture; null when it could not be read. */
    val saveSignature: String? = null,
    /** The upload failure itself, so a batch can stop on a refused sync key. */
    val uploadException: Throwable? = null,
    /** True when this capture reached game-save; any earlier cloud verdict for the game is stale then. */
    val published: Boolean = false,
)

/** What publishing one local snapshot to game-save did ([GameOperations.publishLocked]). */
internal data class PublishOutcome(
    val uploaded: Boolean,
    val uploadError: String? = null,
    val uploadException: Throwable? = null,
    val syncNotice: String? = null,
    val cloudUpdate: GameSaveCloudUpdate? = null,
)

/** Result of "上传待传" for one game. */
internal sealed interface PendingUpload {
    /** The newest backup already reached game-save, or there is none. */
    data object NothingPending : PendingUpload

    /** The saves changed since the pending backup; publishing it would move the cloud head backwards. */
    data object LocalChanged : PendingUpload

    data class Published(val outcome: PublishOutcome) : PendingUpload
}

/** Result of "安全恢复云端较新" for one game. */
internal sealed interface SafeRestore {
    data object UpToDate : SafeRestore

    /** The source cannot be written (SAF tree granted read-only). */
    data object ReadOnly : SafeRestore

    /** The fresh check needs a decision; never resolved automatically. */
    data class NeedsReview(val update: GameSaveCloudUpdate) : SafeRestore

    /** The remote snapshot is announced but not downloadable yet. */
    data class Waiting(val update: GameSaveCloudUpdate) : SafeRestore

    data class Restored(val update: GameSaveCloudUpdate) : SafeRestore
}

/** Result of "保留本机并发布" for one game ([GameOperations.keepLocalLocked]). */
internal sealed interface KeepLocal {
    /** The local saves are the cloud's current version now; [record] is the new backup. */
    data class Published(val record: SnapshotRecord) : KeepLocal

    /** The cloud head changed after the user decided; nothing replaced it. */
    data object HeadMoved : KeepLocal

    /** An older game-save without native scopes; replacing its ZIP head is not done here. */
    data object Unsupported : KeepLocal
}

/**
 * Per-game operations shared by [com.jun9100.opensave.SourceViewModel] and
 * [com.jun9100.opensave.batch.BatchWorker] (plan §5.5).
 *
 * Every `*Locked` function expects its caller to hold
 * [com.jun9100.opensave.StorageOperationLock] and never takes it itself: the
 * Mutex is not reentrant, so only the outermost caller locks.
 */
internal class GameOperations(
    context: Context,
    private val storage: SafStorage = SafStorage(context.applicationContext),
    private val rootStorage: PathAccess = PathAccess(context.applicationContext),
) {
    private val app = context.applicationContext
    private val snapshotEngine = SnapshotEngine()
    private val snapshotRepository = SnapshotRepository.forApp(app)
    private val gameSaveStore = GameSaveConfigStore(app)
    private val gameSaveDevice = GameSaveDeviceStore(app)
    private val signatureReader = SaveSignatureReader(storage, rootStorage)
    private val tombstones = ScopeTombstoneStore(app)

    fun loadConfig(): GameSaveConfig = gameSaveStore.load()

    fun client(config: GameSaveConfig): GameSaveClient =
        GameSaveClient(config, gameSaveDevice.identity(), GameSaveAuthGuard(app))

    /** Downloads [member]'s head archive into the cache and checks its digest. Read-only on the cloud. */
    fun downloadOtherDeviceHead(member: GameSaveCloudGameMember, config: GameSaveConfig): File {
        val head = requireNotNull(member.head) { "该设备在云端还没有存档版本" }
        val remote = RemoteSnapshot(
            type = member.type,
            name = head.archive,
            sizeBytes = 0,
            modified = head.receivedAt,
        )
        val cached = remoteCacheFile(remote)
        val expectedDigest = head.contentDigest
        if (!cached.hasSha256(expectedDigest)) {
            client(config).download(remote, cached).getOrThrow()
            if (!cached.hasSha256(expectedDigest)) {
                cached.delete()
                error("其他设备的存档校验失败")
            }
        }
        return cached
    }

    /** 路径是否存在；云端下发用它判断本机是不是已经有这款游戏的存档目录。 */
    suspend fun pathExists(path: String): Boolean =
        rootStorage.pathExists(path).getOrElse { false }

    /**
     * 读一份不超过 [maxBytes] 的文本（模拟器的配置、日志），读不到时给 null。
     *
     * 云端下发要靠这些文件判断"本机装没装这个游戏"：Eden 的 `config/config.ini`
     * 告诉你游戏目录在哪，`log/eden_log.txt` 记着跑过的标题 id。
     */
    suspend fun readText(directory: String, fileName: String, maxBytes: Int): String? =
        rootStorage.readSmallFile(directory, fileName, maxBytes)?.toString(Charsets.UTF_8)

    /** 目录里的文件名与目录名，用来按文件名核对标题 id；读不到时给空列表。 */
    suspend fun listNames(path: String, maxDepth: Int, limit: Int): List<String> =
        rootStorage.listNames(path, maxDepth, limit).getOrElse { emptyList() }

    /**
     * 来源的"变了没有"指纹，见 [ScanSnapshot]。SAF 授权目录拿不到便宜的 mtime，
     * 那里返回空列表——空指纹一律当作"没有指纹"，该来源照旧每轮都扫。
     */
    suspend fun dirStamps(path: String, maxDepth: Int): List<String> =
        rootStorage.dirStamps(path, maxDepth).getOrElse { emptyList() }

    fun canWrite(source: SourceSelection): Boolean = when (source) {
        is SourceSelection.Saf -> storage.hasWritePermission(source.uri)
        is SourceSelection.Root -> true
    }

    /**
     * Backs up the [manifest] locations of [entry]. Unchanged content reuses
     * the newest snapshot of the game (uploading it only if it never was).
     */
    suspend fun captureLocked(
        entry: SourceGameEntry,
        manifest: GameSnapshotManifest,
        /** False keeps the backup local; "保留本机并发布" publishes it on top of a chosen head instead. */
        publish: Boolean = true,
        /** True always writes a new snapshot, so its id is new to the cloud scope. */
        forceNew: Boolean = false,
    ): GameCaptureOutcome {
        val source = entry.source
        val selectedGame = entry.game.copy(
            saves = entry.game.saves.filter { save -> manifest.locations.any { it.relativePath == save.relativePath } },
        )
        val cloudIdentity = GameCloudNaming.identity(source, entry.sourceId, selectedGame)
        val workspace = File(app.filesDir, GAME_WORKSPACE_DIRECTORY)
        // Read before the copy: content changed during it reads as a change next time, never the reverse.
        val saveSignature = runCatching { signatureReader.read(source, entry.game) }.getOrNull()
        try {
            workspace.deleteRecursively()
            copyGameLocations(source, manifest.locations, workspace).getOrThrow()
            val manifestFile = File(workspace, GameSnapshotManifest.FILE_NAME)
            manifest.write(manifestFile)
            val contentFingerprint = AutoBackupPolicy.fingerprint(workspace)

            val sourceId = entry.sourceId
            // Unchanged content (same data as the newest snapshot of this game)
            // reuses that snapshot; it is only uploaded if it never was.
            val reusable = if (forceNew) {
                null
            } else {
                snapshotRepository.findReusableRecord(
                    sourceId = sourceId,
                    gameKey = manifest.gameKey,
                    contentHash = contentFingerprint.contentHash,
                    currentManifestHash = contentFingerprint.manifestHash,
                )
            }
            if (reusable != null && saveSignature != null) snapshotRepository.setSaveSignature(reusable.id, saveSignature)
            if (reusable?.uploaded == true) {
                return GameCaptureOutcome(
                    record = reusable,
                    fileCount = contentFingerprint.dataFileCount,
                    uploadError = null,
                    reused = true,
                    saveSignature = saveSignature,
                )
            }
            val record: SnapshotRecord
            val archive: File
            val fileCount: Int
            if (reusable != null) {
                record = reusable
                archive = snapshotRepository.fileFor(reusable)
                fileCount = contentFingerprint.dataFileCount
            } else {
                val timestamp = nextTimestamp()
                val newRemoteName = GameCloudNaming.remoteName(cloudIdentity, timestamp)
                archive = File(app.filesDir, "$SNAPSHOT_DIRECTORY/$newRemoteName")
                val build = if (cloudIdentity.desktopCompatible) {
                    val saveRoot = File(workspace, manifest.locations.single().archivePath)
                    require(saveRoot.isDirectory) { "Switch 存档暂存目录不完整" }
                    snapshotEngine.createSnapshot(saveRoot, archive).getOrThrow()
                } else {
                    snapshotEngine.createSnapshot(workspace, archive).getOrThrow()
                }
                record = SnapshotRecord(
                    id = "game_${UUID.randomUUID()}",
                    sourceId = sourceId,
                    remoteName = newRemoteName,
                    localPath = newRemoteName,
                    createdAtMillis = timestamp,
                    sizeBytes = build.archive.length(),
                    sha256 = build.archiveSha256,
                    manifestHash = contentFingerprint.manifestHash,
                    gameKey = manifest.gameKey,
                    gameName = manifest.gameName,
                    saveType = cloudIdentity.saveType.id,
                    cloudGameId = cloudIdentity.gameId,
                    contentHash = contentFingerprint.contentHash,
                    hashVersion = if (contentFingerprint.contentHash.isBlank()) 0 else ContentHash.VERSION,
                    saveSignature = saveSignature.orEmpty(),
                )
                snapshotRepository.add(record)
                fileCount = build.fileCount
            }

            val config = gameSaveStore.load()
            val published = if (publish && config.ready) {
                publishLocked(entry, cloudIdentity, record, archive, contentFingerprint.manifestHash, config)
            } else {
                PublishOutcome(uploaded = false)
            }
            val finalRecord = snapshotRepository.list().first { it.id == record.id }
            return GameCaptureOutcome(
                record = finalRecord,
                fileCount = fileCount,
                uploadError = published.uploadError,
                syncNotice = published.syncNotice,
                cloudUpdate = published.cloudUpdate,
                reused = reusable != null,
                // Only a reused record that had never been uploaded gets here.
                uploadedReused = reusable != null && finalRecord.uploaded,
                saveSignature = saveSignature,
                uploadException = published.uploadException,
                published = published.uploaded,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    /**
     * Publishes [record] as the game's current cloud save. Native scopes first;
     * only a server without the scope route uses the legacy ZIP-head protocol.
     * Re-running it is idempotent: an already published head is confirmed, not duplicated.
     */
    fun publishLocked(
        entry: SourceGameEntry,
        cloudIdentity: GameCloudIdentity,
        record: SnapshotRecord,
        archive: File,
        manifestHash: String,
        config: GameSaveConfig,
    ): PublishOutcome {
        // A stopped scope never receives a request again; the local snapshot is still saved.
        tombstones.get(cloudIdentity)?.let { tombstone ->
            val error = GameSaveScopeGoneException.from(tombstone)
            return PublishOutcome(uploaded = false, uploadError = error.message, uploadException = error)
        }
        val client = client(config)
        val native = GameSaveNativeSync(app, config, client)
        var uploaded = false
        var uploadError: String? = null
        var uploadException: Throwable? = null
        var syncNotice: String? = null
        var cloudUpdate: GameSaveCloudUpdate? = null
        val nativeUpload = native.publishSnapshot(cloudIdentity, record, archive, GameCloudFacts.of(entry.game))
        nativeUpload.onSuccess { result ->
            // A confirmed head and an immutable conflict are both durable;
            // neither should be retried later as a head-changing write.
            val conflict = result is GameSaveNativePublishResult.Conflict
            snapshotRepository.markUploaded(record.id, asCloudHead = !conflict)
            uploaded = true
            if (conflict) {
                val decision = native.check(cloudIdentity, manifestHash).getOrNull()
                cloudUpdate = decision
                    ?.takeUnless { it.action == GameSaveRemoteAction.UP_TO_DATE }
                    ?.let { GameSaveCloudUpdate(entry, nativeDecision = it) }
                syncNotice = "本机版本已保留为冲突副本，云端当前版本没有被覆盖"
            }
        }.onFailure { error ->
            // 410: the cloud deleted or merged this scope. It must never fall back
            // to the legacy upload (that would recreate an orphaned archive), and
            // the tombstone wording is the state the user sees, not a failure reason.
            if (error is GameSaveScopeGoneException) {
                uploadError = error.message
                uploadException = error
                return@onFailure
            }
            if (error !is GameSaveScopesUnsupportedException) {
                uploadError = "云端原生同步失败：${error.message ?: "未知错误"}"
                uploadException = error
                return@onFailure
            }

            // Older service images have no native scope route. Keep this
            // compatibility path isolated so any other native error can
            // never downgrade to the timestamped ZIP-head protocol.
            val upload = client.upload(archive, cloudIdentity.saveType, record.remoteName)
            if (upload.isFailure) {
                uploadError = "云端上传失败：${upload.exceptionOrNull()?.message ?: "未知错误"}"
                uploadException = upload.exceptionOrNull()
                return@onFailure
            }
            // A ZIP without the head protocol is all such a game has; otherwise
            // it is the current save only once the head below is advanced.
            snapshotRepository.markUploaded(record.id, asCloudHead = !cloudIdentity.desktopCompatible)
            uploaded = true
            if (!cloudIdentity.desktopCompatible) return@onFailure
            val decision = runCatching {
                GameSaveHeadReader(app, config, client).check(
                    identity = cloudIdentity,
                    currentManifestHash = manifestHash,
                    remoteSnapshots = client.list().getOrThrow(),
                )
            }.getOrElse { legacyError ->
                syncNotice = "无法检查跨设备更新，未推进当前存档：${legacyError.message ?: "未知错误"}"
                null
            }
            when (decision?.action) {
                null -> Unit
                GameSaveRemoteAction.UP_TO_DATE -> {
                    GameSaveHeadPublisher(app, config).publishSnapshot(cloudIdentity, record, archive)
                        .onSuccess { snapshotRepository.markUploaded(record.id) }
                        .onFailure { legacyError ->
                            uploadError = "云端同步公告失败：${legacyError.message ?: "未知错误"}"
                            uploadException = legacyError
                        }
                }

                else -> {
                    cloudUpdate = GameSaveCloudUpdate(entry, legacyDecision = decision)
                    val device = decision.candidate?.head?.deviceName?.let { "$it 的" }.orEmpty()
                    syncNotice = "检测到${device}的跨设备更新：本次版本已上传，但没有成为云端当前版本"
                }
            }
        }
        return PublishOutcome(uploaded, uploadError, uploadException, syncNotice, cloudUpdate)
    }

    /**
     * "保留本机并发布": backs up the saves on disk now as a new version and
     * publishes it as the cloud's current version on top of [expectedHead],
     * the head the user saw and chose to replace (its id from a fresh check).
     * The local saves are not touched, and the replaced cloud version stays in
     * the scope history. Only native scopes support it: the legacy ZIP-head
     * protocol cannot replace a head without racing other devices.
     */
    suspend fun keepLocalLocked(entry: SourceGameEntry, expectedHead: String, config: GameSaveConfig): KeepLocal {
        require(config.ready) { "未启用 SaveVault 云端同步" }
        require(expectedHead.isNotBlank()) { "没有可替换的云端当前版本" }
        val saves = GameLibrary.batchBackupLocations(entry.game)
        require(saves.isNotEmpty()) { "这款游戏没有已确认的存档位置" }
        val manifest = GameSnapshotManifest.from(entry.game, saves)
        val cloudIdentity = GameCloudNaming.identity(entry.source, entry.sourceId, entry.game.copy(saves = saves))
        val captured = captureLocked(entry, manifest, publish = false, forceNew = true)
        val archive = snapshotRepository.fileFor(captured.record)
        val result = GameSaveNativeSync(app, config, client(config))
            .publishOverHead(cloudIdentity, captured.record, archive, expectedHead)
            .getOrElse { error -> if (error is GameSaveScopesUnsupportedException) return KeepLocal.Unsupported else throw error }
        return when (result) {
            is GameSaveNativePublishResult.Confirmed -> {
                snapshotRepository.markUploaded(captured.record.id, asCloudHead = true)
                KeepLocal.Published(snapshotRepository.list().first { it.id == captured.record.id })
            }
            is GameSaveNativePublishResult.Conflict -> KeepLocal.HeadMoved
        }
    }

    /**
     * "上传待传": publishes the newest backup of [entry] when it never reached
     * game-save. Only a backup equal to the saves on disk now may become the
     * cloud head; one the saves moved on from is left for the next backup.
     */
    suspend fun uploadPendingLocked(entry: SourceGameEntry, config: GameSaveConfig): PendingUpload {
        require(config.ready) { "未启用 SaveVault 云端同步" }
        val latest = snapshotRepository.list()
            .firstOrNull { it.sourceId == entry.sourceId && it.gameKey == entry.game.gameKey && !it.isSafetyCopy }
            ?: return PendingUpload.NothingPending
        if (latest.uploaded) return PendingUpload.NothingPending
        val archive = snapshotRepository.fileFor(latest)
        require(archive.isFile) { "本地快照文件不存在" }

        val saves = GameLibrary.batchBackupLocations(entry.game)
        val manifest = GameSnapshotManifest.from(entry.game, saves)
        val cloudIdentity = GameCloudNaming.identity(entry.source, entry.sourceId, entry.game.copy(saves = saves))
        if (latest.cloudGameId != cloudIdentity.gameId || latest.saveType != cloudIdentity.saveType.id) {
            return PendingUpload.LocalChanged
        }
        val workspace = File(app.filesDir, GAME_WORKSPACE_DIRECTORY)
        try {
            workspace.deleteRecursively()
            copyGameLocations(entry.source, manifest.locations, workspace).getOrThrow()
            manifest.write(File(workspace, GameSnapshotManifest.FILE_NAME))
            val fingerprint = AutoBackupPolicy.fingerprint(workspace)
            val same = if (latest.contentHash.isNotBlank() && fingerprint.contentHash.isNotBlank()) {
                latest.contentHash == fingerprint.contentHash
            } else {
                latest.manifestHash == fingerprint.manifestHash
            }
            if (!same) return PendingUpload.LocalChanged
            return PendingUpload.Published(publishLocked(entry, cloudIdentity, latest, archive, fingerprint.manifestHash, config))
        } finally {
            workspace.deleteRecursively()
        }
    }

    /** Inspects one game's cloud head without writing outside a private workspace; null when up to date. */
    suspend fun checkCloudLocked(
        entry: SourceGameEntry,
        config: GameSaveConfig,
        client: GameSaveClient,
        remoteSnapshots: () -> List<RemoteSnapshot>,
    ): GameSaveCloudUpdate? {
        val saves = GameLibrary.batchBackupLocations(entry.game)
        val manifest = GameSnapshotManifest.from(entry.game, saves)
        val selectedGame = entry.game.copy(saves = saves)
        val identity = GameCloudNaming.identity(entry.source, entry.sourceId, selectedGame)

        val workspace = File(app.filesDir, ".cloud-check-${UUID.randomUUID()}")
        try {
            copyGameLocations(entry.source, manifest.locations, workspace).getOrThrow()
            manifest.write(File(workspace, GameSnapshotManifest.FILE_NAME))
            val fingerprint = AutoBackupPolicy.fingerprint(workspace)
            val native = GameSaveNativeSync(app, config, client)
            val nativeResult = native.check(identity, fingerprint.manifestHash)
            nativeResult.getOrNull()?.let { decision ->
                return decision.takeUnless { it.action == GameSaveRemoteAction.UP_TO_DATE }
                    ?.let { GameSaveCloudUpdate(entry, nativeDecision = it) }
            }
            val nativeError = nativeResult.exceptionOrNull()
            if (nativeError !is GameSaveScopesUnsupportedException) {
                throw nativeError ?: error("读取 SaveVault 云端原生同步范围失败")
            }

            // Only an older server without the scope route may use the legacy
            // announcement files, and that protocol is limited to the proven
            // Root Switch layout shared with the desktop client.
            if (!identity.desktopCompatible) return null
            val decision = GameSaveHeadReader(app, config, client).check(identity, fingerprint.manifestHash, remoteSnapshots())
            return decision.takeUnless { it.action == GameSaveRemoteAction.UP_TO_DATE }
                ?.let { GameSaveCloudUpdate(entry, legacyDecision = it) }
        } finally {
            workspace.deleteRecursively()
        }
    }

    /**
     * "安全恢复云端较新" for one game: re-reads the cloud head now (an earlier
     * check may be stale) and restores only a safe, strictly newer head. A
     * decision that needs review is returned, never resolved here.
     */
    suspend fun safeRestoreLocked(
        entry: SourceGameEntry,
        config: GameSaveConfig,
        client: GameSaveClient,
        remoteSnapshots: () -> List<RemoteSnapshot>,
    ): SafeRestore {
        if (!canWrite(entry.source)) return SafeRestore.ReadOnly
        val fresh = checkCloudLocked(entry, config, client, remoteSnapshots) ?: return SafeRestore.UpToDate
        return when (fresh.action) {
            GameSaveRemoteAction.UP_TO_DATE -> SafeRestore.UpToDate
            GameSaveRemoteAction.REVIEW_REQUIRED -> SafeRestore.NeedsReview(fresh)
            GameSaveRemoteAction.WAIT_FOR_REMOTE -> SafeRestore.Waiting(fresh)
            GameSaveRemoteAction.AUTO_RESTORE -> {
                val remote = fresh.remote ?: return SafeRestore.Waiting(fresh)
                restoreGameLocked(
                    request = RestoreRequest(
                        remote = remote,
                        remoteHead = fresh.legacyHead,
                        nativeHead = fresh.nativeHead,
                        game = entry.game,
                        source = entry.source,
                    ),
                    source = entry.source,
                    requestedGame = entry.game,
                    currentGame = entry.game,
                    config = config,
                )
                SafeRestore.Restored(fresh)
            }
        }
    }

    /** Extracts the archive to a fresh temp dir and returns the automatic plan; the temp dir is deleted. */
    fun planOtherDeviceRestore(archive: File, currentGame: DetectedGame): CrossDevicePlan {
        val workingRoot = File(app.filesDir, ".game-restore-plan-${UUID.randomUUID()}")
        val extractedRoot = File(workingRoot, "archive")
        require(workingRoot.mkdirs()) { "无法创建游戏恢复暂存目录" }
        try {
            snapshotEngine.restoreSnapshot(archive, extractedRoot).getOrThrow()
            val otherArchive = CrossDeviceRestorePolicy.readArchive(extractedRoot)
            return CrossDeviceRestorePolicy.plan(
                archive = otherArchive,
                targets = CrossDeviceRestorePolicy.targetsOf(currentGame),
            )
        } finally {
            workingRoot.deleteRecursively()
        }
    }

    /**
     * Restores one game from a local or cloud snapshot. The current content of
     * the manifest's locations is saved as a safety snapshot first; the write
     * and the head bookkeeping after it are not cancellable, so a cancel can
     * never leave restored saves that the sync state does not know about.
     * [currentGame] is the game as the latest scan found it.
     */
    suspend fun restoreGameLocked(
        request: RestoreRequest,
        source: SourceSelection,
        requestedGame: DetectedGame,
        currentGame: DetectedGame,
        config: GameSaveConfig = gameSaveStore.load(),
    ) {
        val sourceId = SnapshotNaming.sourceId(source.idSeed)
        val archive = when {
            request.local != null -> {
                require(request.local.gameKey == requestedGame.gameKey) { "本地快照不属于所选游戏" }
                require(request.local.sourceId == sourceId) { "本地快照不属于当前存档来源" }
                snapshotRepository.fileFor(request.local)
            }
            request.remote != null -> {
                val identity = GameCloudNaming.identity(source, sourceId, requestedGame)
                require(request.remote.type == identity.saveType.id && request.remote.name.startsWith(GameCloudNaming.remotePrefix(identity))) {
                    "云端快照不属于当前游戏或存档来源"
                }
                request.nativeHead?.let { native ->
                    require(native.remote.type == request.remote.type && native.remote.name == request.remote.name) {
                        "原生云端快照与恢复请求不匹配"
                    }
                    require(native.snapshot.id == GameSaveNativeProtocol.archiveName(native.remote.name)?.snapshotId) {
                        "原生云端快照身份无效"
                    }
                }
                val downloaded = remoteCacheFile(request.remote)
                if (!downloaded.isFile) {
                    client(config).download(request.remote, downloaded).getOrThrow()
                }
                downloaded
            }
            else -> error("没有可恢复的游戏快照")
        }
        require(archive.isFile) { "游戏快照文件不存在" }
        request.local?.let { record ->
            require(Hashing.sha256(archive) == record.sha256) { "本地游戏快照校验失败" }
        }
        require(currentGame.gameKey == requestedGame.gameKey) { "请先扫描当前存档来源" }

        val workingRoot = File(app.filesDir, ".game-restore-${UUID.randomUUID()}")
        val extractedRoot = File(workingRoot, "archive")
        val safetyRoot = File(workingRoot, "safety")
        require(workingRoot.mkdirs()) { "无法创建游戏恢复暂存目录" }
        try {
            snapshotEngine.restoreSnapshot(archive, extractedRoot).getOrThrow()
            val manifestFile = File(extractedRoot, GameSnapshotManifest.FILE_NAME)
            val manifest = if (manifestFile.isFile) {
                GameSnapshotManifest.read(manifestFile)
            } else {
                // A single TitleID directory uses the desktop ZIP layout so it can
                // be shared with the Windows client. Stage it behind our normal
                // location manifest before the same guarded restore path runs.
                val identity = GameCloudNaming.identity(source, sourceId, requestedGame)
                require(identity.desktopCompatible) { "此云端快照不是可跨端恢复的 Switch 单存档快照" }
                val save = GameLibrary.batchBackupLocations(requestedGame).singleOrNull()
                    ?: error("当前 Switch 游戏没有唯一的已确认存档位置")
                val portable = File(workingRoot, "portable-switch-save")
                require(extractedRoot.renameTo(portable)) { "无法准备 Switch 存档恢复目录" }
                require(extractedRoot.mkdirs()) { "无法创建 Switch 存档恢复目录" }
                val archivePath = "desktop-switch-save"
                require(portable.renameTo(File(extractedRoot, archivePath))) { "无法准备 Switch 存档内容" }
                GameSnapshotManifest(
                    gameKey = requestedGame.gameKey,
                    gameName = requestedGame.presentation.primaryName,
                    locations = listOf(
                        GameSnapshotLocation(
                            kind = save.kind,
                            relativePath = save.relativePath,
                            archivePath = archivePath,
                            candidate = false,
                        ),
                    ),
                )
            }
            require(manifest.matches(currentGame)) { "游戏快照路径与当前扫描清单不匹配；请重新扫描并检查游戏身份" }
            require(manifest.gameKey == requestedGame.gameKey) { "游戏快照身份与所选游戏不匹配" }
            manifest.write(manifestFile)
            val restoredFingerprint = AutoBackupPolicy.fingerprint(extractedRoot)
            val restoredManifestHash = restoredFingerprint.manifestHash

            saveRestoreSafetySnapshot(
                source = source,
                sourceId = sourceId,
                requestedGame = requestedGame,
                manifest = manifest,
                safetyRoot = safetyRoot,
                comment = "恢复前安全快照",
            )

            withContext(NonCancellable) {
                when (source) {
                    is SourceSelection.Saf -> storage.restoreGameLocations(source.uri, manifest.locations, extractedRoot, safetyRoot)
                    is SourceSelection.Root -> rootStorage.restoreGameLocations(source.path, manifest.locations, extractedRoot)
                }.getOrThrow()
                val identity = GameCloudNaming.identity(source, sourceId, requestedGame)
                request.nativeHead?.let { native ->
                    GameSaveNativeSync(app, config).markRemoteRestore(
                        identity = identity,
                        candidate = native,
                        manifestHash = restoredManifestHash,
                        archiveFile = archive,
                    ).getOrThrow()
                } ?: request.remote?.takeIf { identity.desktopCompatible }?.let { remote ->
                    GameSaveHeadPublisher(app, config).markRemoteRestore(
                        identity = identity,
                        remote = remote,
                        manifestHash = restoredManifestHash,
                        archiveFile = archive,
                        remoteHead = request.remoteHead,
                    ).getOrThrow()
                }
                request.remote?.let { remote ->
                    recordRestoredCloudSnapshot(source, sourceId, currentGame, manifest, remote, archive, restoredFingerprint)
                }
            }
        } finally {
            workingRoot.deleteRecursively()
        }
    }

    /**
     * Restores another device's save into this game's own locations. The mapped
     * local locations are saved as a safety snapshot first, exactly like
     * [restoreGameLocked]. The cloud and the sync state are not touched: no
     * markRemoteRestore, no recordRestoredCloudSnapshot, nothing marked uploaded.
     */
    suspend fun restoreOtherDeviceLocked(
        archive: File,
        plan: CrossDevicePlan,
        source: SourceSelection,
        requestedGame: DetectedGame,
        currentGame: DetectedGame,
    ) {
        require(plan.complete) { "还有未决定的存档位置" }
        require(plan.restoresAnything) { "没有选择要恢复的存档位置" }
        require(currentGame.gameKey == requestedGame.gameKey) { "请先扫描当前存档来源" }

        val targets = CrossDeviceRestorePolicy.targetsOf(currentGame)
        val mappedPaths = plan.mapping.values.filterNotNull()
        require(mappedPaths.distinct().size == mappedPaths.size) { "同一个存档位置不能恢复两份内容" }
        require(mappedPaths.all { path -> targets.any { it.relativePath == path } }) {
            "存档位置已变化，请重新扫描"
        }

        val sourceId = SnapshotNaming.sourceId(source.idSeed)
        val workingRoot = File(app.filesDir, ".game-restore-${UUID.randomUUID()}")
        val extractedRoot = File(workingRoot, "archive")
        val safetyRoot = File(workingRoot, "safety")
        require(workingRoot.mkdirs()) { "无法创建游戏恢复暂存目录" }
        try {
            snapshotEngine.restoreSnapshot(archive, extractedRoot).getOrThrow()
            val extractedArchive = CrossDeviceRestorePolicy.readArchive(extractedRoot)
            require(extractedArchive == plan.archive) { "其他设备的存档已变化，请重新选择" }

            // A PC archive's part is the extraction root itself. Move the root
            // aside first so staging cannot try to move a directory into itself.
            val contentRoot = if (extractedArchive.format == CrossDeviceArchiveFormat.PC) {
                val portable = File(workingRoot, "archive-content")
                require(extractedRoot.renameTo(portable)) { "无法准备其他设备的存档内容" }
                portable
            } else {
                extractedRoot
            }
            val stagedRoot = File(workingRoot, "staged")
            require(stagedRoot.mkdirs()) { "无法创建游戏恢复暂存目录" }
            val mappedParts = plan.mapping.entries
                .mapNotNull { entry -> entry.value?.let { entry.key to it } }
                .sortedBy { it.first }
                .mapIndexed { index, (partIndex, relativePath) ->
                    val target = targets.first { it.relativePath == relativePath }
                    val part = extractedArchive.parts[partIndex]
                    val sourceFolder = if (part.archivePath.isEmpty()) {
                        contentRoot
                    } else {
                        File(contentRoot, part.archivePath)
                    }
                    require(sourceFolder.isDirectory) { "其他设备的存档不完整" }
                    val archivePath = "location-${index.toString().padStart(4, '0')}"
                    require(sourceFolder.renameTo(File(stagedRoot, archivePath))) {
                        "无法准备其他设备的存档内容"
                    }
                    GameSnapshotLocation(
                        kind = target.kind,
                        relativePath = target.relativePath,
                        archivePath = archivePath,
                        candidate = false,
                    )
                }
            val manifest = GameSnapshotManifest(
                gameKey = requestedGame.gameKey,
                gameName = requestedGame.presentation.primaryName,
                locations = mappedParts,
            )
            require(manifest.matches(currentGame)) { "存档位置已变化，请重新扫描" }

            saveRestoreSafetySnapshot(
                source = source,
                sourceId = sourceId,
                requestedGame = requestedGame,
                manifest = manifest,
                safetyRoot = safetyRoot,
                comment = "跨设备恢复前安全快照",
            )

            withContext(NonCancellable) {
                when (source) {
                    is SourceSelection.Saf -> storage.restoreGameLocations(source.uri, manifest.locations, stagedRoot, safetyRoot)
                    is SourceSelection.Root -> rootStorage.restoreGameLocations(source.path, manifest.locations, stagedRoot)
                }.getOrThrow()
                // The restored version is only a content source. The next local
                // backup publishes it normally as a child of this device's head.
            }
        } finally {
            workingRoot.deleteRecursively()
        }
    }

    /**
     * 云端优先下发：把云端的 head 归档写进本机还不存在的存档目录。
     *
     * 与 [restoreOtherDeviceLocked] 的区别是本机既没有这款游戏的扫描结果，也没有
     * 目标目录——`nand/user/save/<uid>/<TITLE_ID>`、`PSP/SAVEDATA/<GAME_ID>` 都要
     * 由存储后端的 `createMissing` 建出来，因此没有"恢复前安全快照"这一步：
     * 没有旧内容可以丢。
     *
     * 恢复下来的版本只是内容来源；下一次正常备份会把它作为本设备的新版本发布。
     */
    suspend fun restoreCloudPullLocked(
        candidate: CloudPullCandidate,
        archive: File,
    ): Result<Unit> = runCatching {
        require(archive.isFile) { "云端存档文件不存在" }
        val workingRoot = File(app.filesDir, ".cloud-pull-${UUID.randomUUID()}")
        val extractedRoot = File(workingRoot, "archive")
        val stagedRoot = File(workingRoot, "staged")
        require(workingRoot.mkdirs()) { "无法创建云端下发暂存目录" }
        try {
            snapshotEngine.restoreSnapshot(archive, extractedRoot).getOrThrow()
            val extractedArchive = CrossDeviceRestorePolicy.readArchive(extractedRoot)
            require(extractedArchive.parts.size == 1) { "云端存档包含多个位置，暂不支持自动下发" }

            // PC 端上传的归档是"解包根即内容"，先把它挪开，避免 rename 到自己里面。
            val contentRoot = if (extractedArchive.format == CrossDeviceArchiveFormat.PC) {
                val portable = File(workingRoot, "archive-content")
                require(extractedRoot.renameTo(portable)) { "无法准备云端存档内容" }
                portable
            } else {
                extractedRoot
            }
            val part = extractedArchive.parts.single()
            val sourceFolder = if (part.archivePath.isEmpty()) contentRoot else File(contentRoot, part.archivePath)
            require(sourceFolder.isDirectory) { "云端存档不完整" }
            require(stagedRoot.mkdirs()) { "无法创建云端下发暂存目录" }
            require(sourceFolder.renameTo(File(stagedRoot, "location-0000"))) {
                "无法准备云端存档内容"
            }

            val location = GameSnapshotLocation(
                kind = candidate.kind,
                relativePath = GameSnapshotManifest.safeRelativePath(candidate.relativePath),
                archivePath = "location-0000",
                candidate = false,
            )
            val rootSource = SourceSelection.Root(candidate.root.path)
            withContext(NonCancellable) {
                rootStorage.restoreGameLocations(
                    rootSource.path,
                    listOf(location),
                    stagedRoot,
                    createMissing = true,
                ).getOrThrow()
            }
        } finally {
            workingRoot.deleteRecursively()
        }
    }

    /** Captures the current saves before a restore and records them as a local safety copy. */
    private suspend fun saveRestoreSafetySnapshot(
        source: SourceSelection,
        sourceId: String,
        requestedGame: DetectedGame,
        manifest: GameSnapshotManifest,
        safetyRoot: File,
        comment: String,
    ) {
        copyGameLocations(source, manifest.locations, safetyRoot).getOrThrow()
        val safetyManifest = File(safetyRoot, GameSnapshotManifest.FILE_NAME)
        manifest.write(safetyManifest)
        val safetyFingerprint = AutoBackupPolicy.fingerprint(safetyRoot)
        val timestamp = nextTimestamp()
        val safetyIdentity = GameCloudNaming.identity(source, sourceId, requestedGame)
        val safetyName = GameCloudNaming.remoteName(safetyIdentity, timestamp)
        val safetyArchive = File(app.filesDir, "$SNAPSHOT_DIRECTORY/$safetyName")
        val safetyBuild = snapshotEngine.createSnapshot(safetyRoot, safetyArchive).getOrThrow()
        snapshotRepository.add(
            SnapshotRecord(
                id = "game_${UUID.randomUUID()}",
                sourceId = sourceId,
                remoteName = safetyName,
                localPath = safetyName,
                createdAtMillis = timestamp,
                sizeBytes = safetyArchive.length(),
                sha256 = safetyBuild.archiveSha256,
                manifestHash = safetyFingerprint.manifestHash,
                comment = comment,
                gameKey = manifest.gameKey,
                gameName = manifest.gameName,
                saveType = safetyIdentity.saveType.id,
                cloudGameId = safetyIdentity.gameId,
            ),
        )
    }

    /**
     * The restored cloud archive becomes this game's newest local snapshot,
     * already uploaded and the cloud head, with the signature read after the
     * write. It has no comment, because a comment marks a safety copy.
     * Without it the status compares the restored saves with the
     * snapshot from before the restore and shows "本地有变更", and
     * "备份有变更" would publish the same content again.
     */
    private suspend fun recordRestoredCloudSnapshot(
        source: SourceSelection,
        sourceId: String,
        game: DetectedGame,
        manifest: GameSnapshotManifest,
        remote: RemoteSnapshot,
        archive: File,
        fingerprint: AutoBackupFingerprint,
    ) {
        val signature = runCatching { signatureReader.read(source, game) }.getOrNull()
        val existing = snapshotRepository.list().firstOrNull { it.sourceId == sourceId && it.remoteName == remote.name }
        if (existing != null) {
            snapshotRepository.markUploaded(existing.id, uploaded = true, asCloudHead = true)
            signature?.let { snapshotRepository.setSaveSignature(existing.id, it) }
            return
        }
        val identity = GameCloudNaming.identity(source, sourceId, game)
        val stored = File(app.filesDir, "$SNAPSHOT_DIRECTORY/${PathPolicy.requireSafeDocumentName(remote.name)}")
        if (archive.absoluteFile != stored.absoluteFile) archive.copyTo(stored, overwrite = true)
        val record = SnapshotRecord(
            id = "game_${UUID.randomUUID()}",
            sourceId = sourceId,
            remoteName = remote.name,
            localPath = stored.name,
            createdAtMillis = nextTimestamp(),
            sizeBytes = stored.length(),
            sha256 = Hashing.sha256(stored),
            manifestHash = fingerprint.manifestHash,
            gameKey = manifest.gameKey,
            gameName = manifest.gameName,
            saveType = identity.saveType.id,
            cloudGameId = identity.gameId,
            contentHash = fingerprint.contentHash,
            hashVersion = if (fingerprint.contentHash.isBlank()) 0 else ContentHash.VERSION,
            saveSignature = signature.orEmpty(),
        )
        snapshotRepository.add(record)
        snapshotRepository.markUploaded(record.id, uploaded = true, asCloudHead = true)
    }

    private suspend fun copyGameLocations(
        source: SourceSelection,
        locations: List<GameSnapshotLocation>,
        destination: File,
    ): Result<Unit> = when (source) {
        is SourceSelection.Saf -> storage.copyGameLocations(source.uri, locations, destination)
        is SourceSelection.Root -> rootStorage.copyGameLocations(source.path, locations, destination)
    }

    private fun nextTimestamp(): Long = maxOf(
        System.currentTimeMillis(),
        (snapshotRepository.list().maxOfOrNull { it.createdAtMillis } ?: 0L) + 1L,
    )

    fun remoteCacheFile(remote: RemoteSnapshot): File = File(
        app.filesDir,
        "$SNAPSHOT_DIRECTORY/remote-${remote.type}-${Hashing.sha256(remote.name).take(16)}.zip",
    )

    private fun File.hasSha256(expected: String): Boolean =
        isFile && Hashing.sha256(this).equals(expected, ignoreCase = true)

    companion object {
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val GAME_WORKSPACE_DIRECTORY = "game-workspace"
    }
}

/** game-save is switched on, has both an address and a sync key, and the cloud has not blocked the credential. */
internal val GameSaveConfig.ready: Boolean
    get() = enabled && serviceUrl.isNotBlank() && syncKey.isNotBlank() && authBlocked.isBlank()
