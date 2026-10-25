package com.jun9100.opensave

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

class AutoBackupScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(ENABLED_KEY, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (enabled) {
            enqueue(ExistingPeriodicWorkPolicy.UPDATE)
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
        }
    }

    fun ensureScheduled() {
        if (isEnabled()) enqueue(ExistingPeriodicWorkPolicy.KEEP)
    }

    private fun enqueue(policy: ExistingPeriodicWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }

    private companion object {
        const val PREFERENCES = "opensave_automation"
        const val ENABLED_KEY = "auto_backup_enabled"
        const val WORK_NAME = "opensave_auto_backup"
    }
}

internal data class AutoBackupFingerprint(
    val manifestHash: String,
    val dataFileCount: Int,
)

internal object AutoBackupPolicy {
    fun eligibleGames(scan: SaveScanReport): List<DetectedGame> = GameLibrary.group(
        scan.detected.filter { save ->
            !save.candidate && save.kind != "android-app" && save.relativePath != "."
        },
    )

    fun fingerprint(workspace: File): AutoBackupFingerprint {
        require(workspace.isDirectory) { "自动备份工作区不存在" }
        val manifest = File(workspace, GameSnapshotManifest.FILE_NAME)
        require(manifest.isFile) { "游戏快照清单不存在" }

        val records = mutableListOf<FileRecord>()
        var dataFileCount = 0
        workspace.walkTopDown().drop(1).forEach { file ->
            if (file == manifest) return@forEach
            val relative = file.relativeTo(workspace).invariantSeparatorsPath
            if (file.isDirectory) {
                records += FileRecord("$relative/", -1L, "")
            } else if (file.isFile) {
                records += FileRecord(relative, file.length(), Hashing.sha256(file))
                dataFileCount++
            }
        }
        records += FileRecord(manifest.name, manifest.length(), Hashing.sha256(manifest))
        return AutoBackupFingerprint(Hashing.manifestHash(records), dataFileCount)
    }
}

class AutoBackupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = StorageOperationLock.withLock { doWorkLocked() }

    private suspend fun doWorkLocked(): Result {
        val config = GameSaveConfigStore(applicationContext).load()
        if (!config.enabled || config.serviceUrl.isBlank() || config.syncKey.isBlank()) return Result.success()

        val storage = SafStorage(applicationContext)
        val rootStorage = RootStorage(applicationContext)
        val installedGameCatalog = InstalledGameCatalog(applicationContext)
        val repository = SnapshotRepository(File(applicationContext.filesDir, SNAPSHOT_DIRECTORY))
        val client = GameSaveClient(config)
        // Do not publish a local current-save head until the latest remote
        // heads have been inspected. A failed read is retried rather than
        // turning an offline device's progress into the new cloud current.
        val remoteSnapshots = client.list().getOrElse { error ->
            val retryable = (error as? GameSaveException)?.retryable ?: true
            return if (retryable && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
        val headReader = GameSaveHeadReader(applicationContext, config, client)
        var retryableFailure = false

        val androidGames = installedGameCatalog.allApps()
        for (source in backupSources(storage, rootStorage, installedGameCatalog)) {
            val scan = scanSource(source, storage, rootStorage, androidGames)
            if (scan.isFailure) {
                retryableFailure = true
                continue
            }
            val sourceId = SnapshotNaming.sourceId(source.idSeed)
            for (game in AutoBackupPolicy.eligibleGames(scan.getOrThrow())) {
                when (val outcome = runCatching {
                    backupGame(
                        source = source,
                        sourceId = sourceId,
                        game = game,
                        repository = repository,
                        storage = storage,
                        rootStorage = rootStorage,
                        config = config,
                        remoteSnapshots = remoteSnapshots,
                        headReader = headReader,
                    )
                }.getOrElse { error -> GameBackupOutcome.UploadFailed(asGameSaveError(error)) }) {
                    GameBackupOutcome.Skipped, GameBackupOutcome.Uploaded, GameBackupOutcome.Restored -> Unit
                    is GameBackupOutcome.UploadFailed -> retryableFailure = retryableFailure || outcome.error.retryable
                    is GameBackupOutcome.Deferred -> retryableFailure = retryableFailure || outcome.retryable
                }
            }
        }
        return if (retryableFailure && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    /** Includes every selected source OpenSave can read; a missing preset is simply skipped. */
    private suspend fun backupSources(
        storage: SafStorage,
        rootStorage: RootStorage,
        installedGameCatalog: InstalledGameCatalog,
    ): List<SourceSelection> {
        val sources = linkedMapOf<String, SourceSelection>()
        val selectionStore = SourceSelectionStore(applicationContext)
        storage.persistedTreeUris().filter(storage::hasReadPermission).forEach { uri ->
            val source = SourceSelection.Saf(uri)
            if (source.idSeed !in selectionStore.disabledSourceIds()) {
                sources[source.idSeed] = source
            }
        }

        if (rootStorage.checkRootAccess().isFailure) return sources.values.toList()
        val rootOptions = SaveSourceOptions.build(
            authorizedTrees = emptyList(),
            rootPath = rootStorage.persistedRootPath(),
            disabledSourceIds = selectionStore.disabledSourceIds(),
            explicitlyEnabledSourceIds = selectionStore.explicitlyEnabledSourceIds(),
            rootPresets = RootSourcePresets.all +
                installedGameCatalog.rootPresets() +
                installedGameCatalog.candidateRootPresets(),
        )
        for (option in rootOptions.filter(SaveSourceOption::selected)) {
            val source = option.source as? SourceSelection.Root ?: continue
            val path = source.path
            if (rootStorage.pathExists(path).getOrDefault(false)) {
                if (source.idSeed !in selectionStore.disabledSourceIds()) {
                    sources[source.idSeed] = source
                }
            }
        }
        return sources.values.toList()
    }

    private suspend fun scanSource(
        source: SourceSelection,
        storage: SafStorage,
        rootStorage: RootStorage,
        androidGames: Map<String, AndroidGameMetadata>,
    ): kotlin.Result<SaveScanReport> = when (source) {
        is SourceSelection.Saf -> storage.scanTree(source.uri, androidGames)
        is SourceSelection.Root -> rootStorage.scanPath(source.path, androidGames)
    }

    private suspend fun backupGame(
        source: SourceSelection,
        sourceId: String,
        game: DetectedGame,
        repository: SnapshotRepository,
        storage: SafStorage,
        rootStorage: RootStorage,
        config: GameSaveConfig,
        remoteSnapshots: List<RemoteSnapshot>,
        headReader: GameSaveHeadReader,
    ): GameBackupOutcome {
        val saves = GameLibrary.batchBackupLocations(game)
        val manifest = GameSnapshotManifest.from(game, saves)
        val selectedGame = game.copy(saves = saves)
        val cloudIdentity = GameCloudNaming.identity(source, sourceId, selectedGame)
        val workspace = File(applicationContext.filesDir, AUTO_GAME_WORKSPACE_DIRECTORY)
        try {
            when (source) {
                is SourceSelection.Saf -> storage.copyGameLocations(source.uri, manifest.locations, workspace)
                is SourceSelection.Root -> rootStorage.copyGameLocations(source.path, manifest.locations, workspace)
            }.getOrThrow()
            val manifestFile = File(workspace, GameSnapshotManifest.FILE_NAME)
            manifest.write(manifestFile)
            val fingerprint = AutoBackupPolicy.fingerprint(workspace)
            if (fingerprint.dataFileCount == 0) return GameBackupOutcome.Skipped

            if (cloudIdentity.desktopCompatible) {
                val decision = headReader.check(
                    identity = cloudIdentity,
                    currentManifestHash = fingerprint.manifestHash,
                    remoteSnapshots = remoteSnapshots,
                )
                when (decision.action) {
                    GameSaveRemoteAction.AUTO_RESTORE -> {
                        val candidate = checkNotNull(decision.candidate)
                        return restoreCurrentHead(
                            source = source,
                            sourceId = sourceId,
                            game = selectedGame,
                            manifest = manifest,
                            workspace = workspace,
                            localFingerprint = fingerprint,
                            repository = repository,
                            rootStorage = rootStorage,
                            config = config,
                            identity = cloudIdentity,
                            candidate = candidate,
                        )
                    }

                    GameSaveRemoteAction.REVIEW_REQUIRED -> {
                        return GameBackupOutcome.Deferred(decision.message, retryable = false)
                    }

                    GameSaveRemoteAction.WAIT_FOR_REMOTE -> {
                        return GameBackupOutcome.Deferred(decision.message, retryable = true)
                    }

                    GameSaveRemoteAction.UP_TO_DATE -> Unit
                }
            }

            val matching = repository.list().filter {
                it.sourceId == sourceId && it.gameKey == game.gameKey && it.manifestHash == fingerprint.manifestHash
            }
            // A snapshot can finish uploading while its current-save head fails
            // to publish. Reuse that archive on the next worker run so the
            // desktop client eventually receives the automatic-sync signal.
            val existing = matching.firstOrNull { repository.fileFor(it).isFile }
            if (existing?.uploaded == true) {
                return publishCurrentHead(config, cloudIdentity, existing, repository.fileFor(existing))
            }
            val pending = existing
            val record = pending ?: createSnapshot(
                sourceId = sourceId,
                game = game,
                manifest = manifest,
                fingerprint = fingerprint,
                workspace = workspace,
                repository = repository,
                cloudIdentity = cloudIdentity,
            )

            return GameSaveClient(config).upload(repository.fileFor(record), cloudIdentity.saveType, record.remoteName).fold(
                onSuccess = {
                    repository.markUploaded(record.id)
                    publishCurrentHead(config, cloudIdentity, record, repository.fileFor(record))
                },
                onFailure = { error -> GameBackupOutcome.UploadFailed(asGameSaveError(error)) },
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    /**
     * Applies only a remote descendant of the unchanged local baseline. The
     * pre-restore workspace becomes a private safety snapshot before Root's
     * atomic location restore replaces the live Switch save.
     */
    private suspend fun restoreCurrentHead(
        source: SourceSelection,
        sourceId: String,
        game: DetectedGame,
        manifest: GameSnapshotManifest,
        workspace: File,
        localFingerprint: AutoBackupFingerprint,
        repository: SnapshotRepository,
        rootStorage: RootStorage,
        config: GameSaveConfig,
        identity: GameCloudIdentity,
        candidate: GameSaveRemoteHead,
    ): GameBackupOutcome {
        val rootSource = source as? SourceSelection.Root
            ?: return GameBackupOutcome.Deferred("当前存档来源不支持安全自动恢复", retryable = false)
        val location = manifest.locations.singleOrNull()
            ?: return GameBackupOutcome.Deferred("Switch 存档位置不是唯一目录，已等待确认", retryable = false)
        val remoteArchive = remoteCacheFile(candidate.archive)
        GameSaveClient(config).download(candidate.archive, remoteArchive).getOrThrow()

        val workingRoot = File(applicationContext.filesDir, ".auto-game-restore-${java.util.UUID.randomUUID()}")
        val portableRoot = File(workingRoot, "portable")
        val restoreRoot = File(workingRoot, "restore")
        try {
            require(workingRoot.mkdirs()) { "无法创建跨设备恢复暂存目录" }
            SnapshotEngine().restoreSnapshot(remoteArchive, portableRoot).getOrThrow()
            require(restoreRoot.mkdirs()) { "无法创建跨设备恢复工作区" }
            val stagedSave = File(restoreRoot, location.archivePath)
            require(portableRoot.renameTo(stagedSave)) { "无法准备跨设备 Switch 存档" }
            manifest.write(File(restoreRoot, GameSnapshotManifest.FILE_NAME))
            val restoredFingerprint = AutoBackupPolicy.fingerprint(restoreRoot)

            saveSafetySnapshot(
                sourceId = sourceId,
                game = game,
                workspace = workspace,
                fingerprint = localFingerprint,
                repository = repository,
                identity = identity,
            )
            rootStorage.restoreGameLocations(rootSource.path, manifest.locations, restoreRoot).getOrThrow()
            GameSaveHeadPublisher(applicationContext, config).markRemoteRestore(
                identity = identity,
                remote = candidate.archive,
                manifestHash = restoredFingerprint.manifestHash,
                archiveFile = remoteArchive,
                remoteHead = candidate,
            )
            return GameBackupOutcome.Restored
        } finally {
            workingRoot.deleteRecursively()
        }
    }

    private fun saveSafetySnapshot(
        sourceId: String,
        game: DetectedGame,
        workspace: File,
        fingerprint: AutoBackupFingerprint,
        repository: SnapshotRepository,
        identity: GameCloudIdentity,
    ) {
        val timestamp = maxOf(
            System.currentTimeMillis(),
            (repository.list().maxOfOrNull { it.createdAtMillis } ?: 0L) + 1L,
        )
        val remoteName = GameCloudNaming.remoteName(identity, timestamp)
        val archive = File(applicationContext.filesDir, "$SNAPSHOT_DIRECTORY/$remoteName")
        val build = SnapshotEngine().createSnapshot(workspace, archive).getOrThrow()
        repository.add(
            SnapshotRecord(
                id = "auto-safety_$timestamp",
                sourceId = sourceId,
                remoteName = remoteName,
                localPath = remoteName,
                createdAtMillis = timestamp,
                sizeBytes = build.archive.length(),
                sha256 = build.archiveSha256,
                manifestHash = fingerprint.manifestHash,
                comment = "跨设备恢复前安全副本",
                gameKey = game.gameKey,
                gameName = game.presentation.primaryName,
                saveType = identity.saveType.id,
                cloudGameId = identity.gameId,
            ),
        )
    }

    private fun remoteCacheFile(remote: RemoteSnapshot): File = File(
        applicationContext.filesDir,
        "$SNAPSHOT_DIRECTORY/remote-${remote.type}-${Hashing.sha256(remote.name).take(16)}.zip",
    )

    private fun createSnapshot(
        sourceId: String,
        game: DetectedGame,
        manifest: GameSnapshotManifest,
        fingerprint: AutoBackupFingerprint,
        workspace: File,
        repository: SnapshotRepository,
        cloudIdentity: GameCloudIdentity,
    ): SnapshotRecord {
        val timestamp = maxOf(
            System.currentTimeMillis(),
            (repository.list().maxOfOrNull { it.createdAtMillis } ?: 0L) + 1L,
        )
        val remoteName = GameCloudNaming.remoteName(cloudIdentity, timestamp)
        val archive = File(applicationContext.filesDir, "$SNAPSHOT_DIRECTORY/$remoteName")
        val source = if (cloudIdentity.desktopCompatible) {
            File(workspace, manifest.locations.single().archivePath)
        } else {
            workspace
        }
        val build = SnapshotEngine().createSnapshot(source, archive).getOrThrow()
        val record = SnapshotRecord(
            id = "auto-game_$timestamp",
            sourceId = sourceId,
            remoteName = remoteName,
            localPath = remoteName,
            createdAtMillis = timestamp,
            sizeBytes = build.archive.length(),
            sha256 = build.archiveSha256,
            manifestHash = fingerprint.manifestHash,
            gameKey = game.gameKey,
            gameName = manifest.gameName,
            saveType = cloudIdentity.saveType.id,
            cloudGameId = cloudIdentity.gameId,
        )
        repository.add(record)
        return record
    }

    private fun publishCurrentHead(
        config: GameSaveConfig,
        cloudIdentity: GameCloudIdentity,
        record: SnapshotRecord,
        archiveFile: File,
    ): GameBackupOutcome {
        if (!cloudIdentity.desktopCompatible) return GameBackupOutcome.Skipped
        return GameSaveHeadPublisher(applicationContext, config).publishSnapshot(cloudIdentity, record, archiveFile).fold(
            onSuccess = { GameBackupOutcome.Uploaded },
            onFailure = { error -> GameBackupOutcome.UploadFailed(asGameSaveError(error)) },
        )
    }

    private fun asGameSaveError(error: Throwable): GameSaveException = when (error) {
        is GameSaveException -> error
        else -> GameSaveException(error.message ?: "game-save 上传失败", retryable = true)
    }

    private sealed interface GameBackupOutcome {
        data object Skipped : GameBackupOutcome
        data object Uploaded : GameBackupOutcome
        data object Restored : GameBackupOutcome
        data class UploadFailed(val error: GameSaveException) : GameBackupOutcome
        data class Deferred(val reason: String, val retryable: Boolean) : GameBackupOutcome
    }

    private companion object {
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val AUTO_GAME_WORKSPACE_DIRECTORY = "auto-game-workspace"
        const val MAX_RETRIES = 3
    }
}
