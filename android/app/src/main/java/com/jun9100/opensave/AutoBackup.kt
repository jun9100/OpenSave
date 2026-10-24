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
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            policy,
            request,
        )
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
    override suspend fun doWork(): Result =
        StorageOperationLock.withLock { doWorkLocked() }

    private suspend fun doWorkLocked(): Result {
        val storage = SafStorage(applicationContext)
        val rootStorage = RootStorage(applicationContext)
        val selectionStore = SourceSelectionStore(applicationContext)
        val config = WebDavConfigStore(applicationContext).load()
        if (!config.enabled || config.url.isBlank()) return Result.success()

        val source = when (selectionStore.mode()) {
            StorageMode.SAF -> {
                val uri = storage.persistedTreeUri() ?: return Result.success()
                if (!storage.hasReadWritePermission(uri)) return Result.success()
                SourceSelection.Saf(uri)
            }

            StorageMode.ROOT -> {
                val path = rootStorage.persistedRootPath() ?: return Result.success()
                SourceSelection.Root(path)
            }
        }
        val scan = when (source) {
            is SourceSelection.Saf -> storage.scanTree(source.uri)
                .getOrElse { return scanFailure(it) }

            is SourceSelection.Root -> rootStorage.scanPath(source.path)
                .getOrElse { return scanFailure(it) }
        }
        val games = AutoBackupPolicy.eligibleGames(scan)
        if (games.isEmpty()) return Result.success()

        val sourceId = SnapshotNaming.sourceId(source.idSeed)
        val repository = SnapshotRepository(File(applicationContext.filesDir, SNAPSHOT_DIRECTORY))
        for (game in games) {
            val outcome = runCatching {
                backupGame(source, sourceId, game, repository, storage, rootStorage, config)
            }.getOrElse { error ->
                return if (error is WebDavException && error.retryable && runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            if (outcome is GameBackupOutcome.UploadFailed) {
                return if (outcome.error.retryable && runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
        return Result.success()
    }

    private suspend fun backupGame(
        source: SourceSelection,
        sourceId: String,
        game: DetectedGame,
        repository: SnapshotRepository,
        storage: SafStorage,
        rootStorage: RootStorage,
        config: WebDavConfig,
    ): GameBackupOutcome {
        val manifest = GameSnapshotManifest.from(game)
        val workspace = File(applicationContext.filesDir, AUTO_GAME_WORKSPACE_DIRECTORY)
        try {
            val copyResult = when (source) {
                is SourceSelection.Saf -> storage.copyGameLocations(source.uri, manifest.locations, workspace)
                is SourceSelection.Root -> rootStorage.copyGameLocations(source.path, manifest.locations, workspace)
            }
            copyResult.getOrThrow()
            manifest.write(File(workspace, GameSnapshotManifest.FILE_NAME))
            val fingerprint = AutoBackupPolicy.fingerprint(workspace)
            if (fingerprint.dataFileCount == 0) return GameBackupOutcome.Skipped

            val matching = repository.list().filter {
                it.sourceId == sourceId && it.gameKey == game.gameKey && it.manifestHash == fingerprint.manifestHash
            }
            if (matching.any { it.uploaded }) return GameBackupOutcome.Skipped
            val pending = matching.firstOrNull { repository.fileFor(it).isFile }
            val record = pending ?: createSnapshot(
                sourceId = sourceId,
                game = game,
                manifest = manifest,
                fingerprint = fingerprint,
                workspace = workspace,
                repository = repository,
            )

            val upload = WebDavClient(config).upload(repository.fileFor(record), record.remoteName)
            upload.fold(
                onSuccess = {
                    repository.markUploaded(record.id)
                    return GameBackupOutcome.Uploaded
                },
                onFailure = { error ->
                    return GameBackupOutcome.UploadFailed(
                        error as? WebDavException ?: WebDavException(error.message ?: "WebDAV 上传失败", true),
                    )
                },
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun createSnapshot(
        sourceId: String,
        game: DetectedGame,
        manifest: GameSnapshotManifest,
        fingerprint: AutoBackupFingerprint,
        workspace: File,
        repository: SnapshotRepository,
    ): SnapshotRecord {
        val timestamp = maxOf(
            System.currentTimeMillis(),
            (repository.list().maxOfOrNull { it.createdAtMillis } ?: 0L) + 1L,
        )
        val remoteName = SnapshotNaming.gameRemoteName(sourceId, game.gameKey, timestamp)
        val archive = File(applicationContext.filesDir, "$SNAPSHOT_DIRECTORY/$remoteName")
        val build = SnapshotEngine().createSnapshot(workspace, archive).getOrThrow()
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
        )
        repository.add(record)
        return record
    }

    private fun scanFailure(error: Throwable): Result {
        return if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    private sealed interface GameBackupOutcome {
        data object Skipped : GameBackupOutcome
        data object Uploaded : GameBackupOutcome
        data class UploadFailed(val error: WebDavException) : GameBackupOutcome
    }

    private companion object {
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val AUTO_GAME_WORKSPACE_DIRECTORY = "auto-game-workspace"
    }
}
