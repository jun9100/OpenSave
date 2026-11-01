package com.jun9100.opensave.ops

import android.content.Context
import com.jun9100.opensave.AutoBackupFingerprint
import com.jun9100.opensave.AutoBackupPolicy
import com.jun9100.opensave.ContentHash
import com.jun9100.opensave.DetectedGame
import com.jun9100.opensave.GameCloudIdentity
import com.jun9100.opensave.GameCloudNaming
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GameSaveClient
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
import com.jun9100.opensave.GameSaveScopesUnsupportedException
import com.jun9100.opensave.GameSnapshotLocation
import com.jun9100.opensave.GameSnapshotManifest
import com.jun9100.opensave.Hashing
import com.jun9100.opensave.PathPolicy
import com.jun9100.opensave.RemoteSnapshot
import com.jun9100.opensave.RestoreRequest
import com.jun9100.opensave.RootStorage
import com.jun9100.opensave.SafStorage
import com.jun9100.opensave.SaveSignatureReader
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
    private val rootStorage: RootStorage = RootStorage(context.applicationContext),
) {
    private val app = context.applicationContext
    private val snapshotEngine = SnapshotEngine()
    private val snapshotRepository = SnapshotRepository.forApp(app)
    private val gameSaveStore = GameSaveConfigStore(app)
    private val gameSaveDevice = GameSaveDeviceStore(app)
    private val signatureReader = SaveSignatureReader(storage, rootStorage)

    fun loadConfig(): GameSaveConfig = gameSaveStore.load()

    fun client(config: GameSaveConfig): GameSaveClient = GameSaveClient(config, gameSaveDevice.identity())

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
            val reusable = snapshotRepository.findReusableRecord(
                sourceId = sourceId,
                gameKey = manifest.gameKey,
                contentHash = contentFingerprint.contentHash,
                currentManifestHash = contentFingerprint.manifestHash,
            )
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
            val published = if (config.ready) {
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
        val client = client(config)
        val native = GameSaveNativeSync(app, config, client)
        var uploaded = false
        var uploadError: String? = null
        var uploadException: Throwable? = null
        var syncNotice: String? = null
        var cloudUpdate: GameSaveCloudUpdate? = null
        val nativeUpload = native.publishSnapshot(cloudIdentity, record, archive)
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
                syncNotice = "本机版本已保留为云端冲突副本，当前存档没有被覆盖"
            }
        }.onFailure { error ->
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
                    syncNotice = "检测到${device}跨设备版本，当前快照已上传但未更新云端当前存档"
                }
            }
        }
        return PublishOutcome(uploaded, uploadError, uploadException, syncNotice, cloudUpdate)
    }

    /**
     * "上传待传": publishes the newest backup of [entry] when it never reached
     * game-save. Only a backup equal to the saves on disk now may become the
     * cloud head; one the saves moved on from is left for the next backup.
     */
    suspend fun uploadPendingLocked(entry: SourceGameEntry, config: GameSaveConfig): PendingUpload {
        require(config.ready) { "未启用 game-save 云端同步" }
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
                throw nativeError ?: error("读取 game-save 原生同步范围失败")
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
                    ?: error("当前 Switch 游戏没有唯一的已识别存档位置")
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
                    comment = "恢复前安全副本",
                    gameKey = manifest.gameKey,
                    gameName = manifest.gameName,
                    saveType = safetyIdentity.saveType.id,
                    cloudGameId = safetyIdentity.gameId,
                ),
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

    companion object {
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val GAME_WORKSPACE_DIRECTORY = "game-workspace"
    }
}

/** game-save is switched on and has both an address and a sync key. */
internal val GameSaveConfig.ready: Boolean
    get() = enabled && serviceUrl.isNotBlank() && syncKey.isNotBlank()
