package com.jun9100.opensave

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jun9100.opensave.activity.ActivityEntry
import com.jun9100.opensave.activity.ActivityKind
import com.jun9100.opensave.activity.ActivityLevel
import com.jun9100.opensave.activity.ActivityRecorder
import com.jun9100.opensave.batch.BatchCenter
import com.jun9100.opensave.ops.ready
import java.io.File
import java.util.concurrent.TimeUnit

class AutoBackupScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(ENABLED_KEY, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (enabled && !credentialsBlocked()) {
            enqueue(ExistingPeriodicWorkPolicy.UPDATE)
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
        }
    }

    fun ensureScheduled() {
        // A rejected credential must stay stopped even if a new ViewModel starts
        // before the user replaces the key; there is nothing a worker could do.
        if (isEnabled() && !credentialsBlocked()) enqueue(ExistingPeriodicWorkPolicy.KEEP)
    }

    private fun credentialsBlocked(): Boolean = GameSaveAuthStore(appContext).blocked().isNotBlank()

    private fun enqueue(policy: ExistingPeriodicWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }

    internal companion object {
        private const val PREFERENCES = "opensave_automation"
        private const val ENABLED_KEY = "auto_backup_enabled"

        /** Cancelled when the cloud rejects this install's credential. */
        internal const val WORK_NAME = "opensave_auto_backup"
    }
}

/**
 * Games the user switched automatic backup off for (plan §5.4), by libraryKey
 * (`sourceId::gameKey`). Kept on this device only.
 */
class AutoBackupExclusions(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun excluded(): Set<String> = preferences.getStringSet(EXCLUDED_KEY, emptySet()).orEmpty().toSet()

    fun setIncluded(libraryKeys: Collection<String>, included: Boolean): Set<String> {
        val updated = AutoBackupPolicy.withIncluded(excluded(), libraryKeys, included)
        val editor = preferences.edit()
        if (updated.isEmpty()) editor.remove(EXCLUDED_KEY) else editor.putStringSet(EXCLUDED_KEY, updated)
        editor.apply()
        return updated
    }

    private companion object {
        const val PREFERENCES = "opensave_automation"
        const val EXCLUDED_KEY = "auto_backup_excluded_games"
    }
}

internal data class AutoBackupFingerprint(
    val manifestHash: String,
    val dataFileCount: Int,
    /** [ContentHash] of the data files; blank when the manifest cannot be read. */
    val contentHash: String = "",
)

/** What one auto-backup run did, game by game. */
internal data class AutoBackupTally(
    val games: Int = 0,
    val uploaded: Int = 0,
    val restored: Int = 0,
    val unchanged: Int = 0,
    /** (game, reason) of games waiting for a decision in "同步与任务". */
    val deferred: List<Pair<String, String>> = emptyList(),
    /** (game, reason) of games whose backup or upload failed. */
    val failed: List<Pair<String, String>> = emptyList(),
    /** Games whose cloud scope was deleted or merged by an administrator; never a failure. */
    val cloudStopped: Int = 0,
    /** "来源：原因" of sources that could not be scanned. */
    val scanFailures: List<String> = emptyList(),
)

internal object AutoBackupPolicy {
    /**
     * Same games and, through [GameLibrary.batchBackupLocations], the same
     * location set as a manual batch backup, so both reuse each other's snapshots.
     */
    fun eligibleGames(scan: SaveScanReport): List<DetectedGame> =
        GameLibrary.batchBackupEligible(GameLibrary.group(scan.detected))

    /** [games] of source [sourceId] the user did not switch automatic backup off for. */
    fun includedGames(sourceId: String, games: List<DetectedGame>, excluded: Set<String>): List<DetectedGame> =
        games.filter { "$sourceId::${it.gameKey}" !in excluded }

    fun withIncluded(excluded: Set<String>, libraryKeys: Collection<String>, included: Boolean): Set<String> =
        if (included) excluded - libraryKeys.toSet() else excluded + libraryKeys

    /**
     * A user batch has priority: the periodic worker skips its round (success,
     * not retry, whose backoff would spend the retry budget) while one runs.
     */
    fun shouldYield(batchRunning: Boolean): Boolean = batchRunning

    /** The 动态 line of one worker run (plan §7 阶段 4: every run can be found in the log). */
    fun runEntry(tally: AutoBackupTally, atMillis: Long = System.currentTimeMillis()): ActivityEntry {
        val counts = BatchSummary.counts(
            listOf(
                "已上传" to tally.uploaded,
                "跨设备恢复" to tally.restored,
                "无变化" to tally.unchanged,
                "待确认" to tally.deferred.size,
                "失败" to tally.failed.size,
            ),
        )
        val stopped = tally.cloudStopped.takeIf { it > 0 }
            ?.let { "；$it 款游戏的云端存档已被删除或合并，已跳过" }
            .orEmpty()
        val text = when {
            tally.games == 0 && tally.scanFailures.isEmpty() -> "自动备份：没有可备份的游戏"
            counts.isEmpty() && stopped.isEmpty() -> "自动备份：没有处理任何游戏"
            else -> "自动备份：${counts.ifEmpty { "没有处理任何游戏" }}$stopped"
        }
        val level = when {
            tally.failed.isNotEmpty() || tally.scanFailures.isNotEmpty() -> ActivityLevel.WARNING
            tally.deferred.isNotEmpty() -> ActivityLevel.WARNING
            tally.uploaded > 0 || tally.restored > 0 -> ActivityLevel.SUCCESS
            else -> ActivityLevel.INFO
        }
        val detail = (tally.failed.map { (name, reason) -> "$name：$reason" } + tally.scanFailures)
            .take(MAX_DETAIL_LINES)
            .joinToString("；")
        return ActivityEntry.create(ActivityKind.AUTO_BACKUP, level, text, detail, atMillis = atMillis)
    }

    /** A worker round that did not run its games, with why. */
    fun skippedEntry(reason: String, atMillis: Long = System.currentTimeMillis()): ActivityEntry =
        ActivityEntry.create(ActivityKind.AUTO_BACKUP, ActivityLevel.INFO, "自动备份已跳过：$reason", atMillis = atMillis)

    /** A worker round that stopped before its games, with why. */
    fun failedEntry(reason: String, retrying: Boolean, atMillis: Long = System.currentTimeMillis()): ActivityEntry =
        ActivityEntry.create(
            ActivityKind.AUTO_BACKUP,
            ActivityLevel.ERROR,
            if (retrying) "自动备份失败，稍后重试" else "自动备份失败",
            reason,
            atMillis = atMillis,
        )

    private const val MAX_DETAIL_LINES = 5

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
        val contentHash = runCatching {
            ContentHash.of(records, GameSnapshotManifest.read(manifest).locations)
        }.getOrDefault("")
        records += FileRecord(manifest.name, manifest.length(), Hashing.sha256(manifest))
        return AutoBackupFingerprint(Hashing.manifestHash(records), dataFileCount, contentHash)
    }
}

class AutoBackupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        if (AutoBackupPolicy.shouldYield(BatchCenter.isRunning)) {
            record(AutoBackupPolicy.skippedEntry("有批量任务正在运行"))
            return Result.success()
        }
        // An unexpected error still fails the run as before; it is only written down first.
        return runCatching { backUpChangedGames() }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            record(AutoBackupPolicy.failedEntry(error.message ?: error.javaClass.simpleName, retrying = false))
            Result.failure()
        }
    }

    private fun record(entry: ActivityEntry) = ActivityRecorder.record(applicationContext, entry, notify = true)

    /**
     * Each game takes the storage lock on its own (plan §5.5), so a user
     * batch or a single backup waits for one game at most; a batch that
     * starts meanwhile ends this round.
     */
    private suspend fun backUpChangedGames(): Result {
        val config = GameSaveConfigStore(applicationContext).load()
        if (!config.ready) {
            record(AutoBackupPolicy.skippedEntry("SaveVault 云端未配置、未开启或此设备的云端凭据已失效"))
            return Result.success()
        }

        val storage = SafStorage(applicationContext)
        val rootStorage = RootStorage(applicationContext)
        val installedGameCatalog = InstalledGameCatalog(applicationContext)
        val repository = SnapshotRepository.forApp(applicationContext)
        val client = GameSaveClient(
            config,
            GameSaveDeviceStore(applicationContext).identity(),
            GameSaveAuthGuard(applicationContext),
        )
        // Do not publish a local current-save head until the latest remote
        // heads have been inspected. A failed read is retried rather than
        // turning an offline device's progress into the new cloud current.
        val remoteSnapshots = client.list().getOrElse { error ->
            val retryable = (error as? GameSaveException)?.retryable ?: true
            val retrying = retryable && runAttemptCount < MAX_RETRIES
            record(AutoBackupPolicy.failedEntry("无法读取云端：${error.message ?: "未知错误"}", retrying))
            return if (retrying) Result.retry() else Result.success()
        }
        val headReader = GameSaveHeadReader(applicationContext, config, client)
        val tombstones = ScopeTombstoneStore(applicationContext)
        var retryableFailure = false
        var tally = AutoBackupTally()
        val verdicts = mutableMapOf<String, CloudVerdict>()

        val androidGames = installedGameCatalog.allApps()
        val excluded = AutoBackupExclusions(applicationContext).excluded()
        val sources = backupSources(storage, rootStorage, installedGameCatalog)
        for (source in sources) {
            val scan = scanSource(source.source, storage, rootStorage, androidGames)
            if (scan.isFailure) {
                retryableFailure = true
                tally = tally.copy(scanFailures = tally.scanFailures + "${source.label}：${scan.exceptionOrNull()?.message ?: "扫描失败"}")
                continue
            }
            val sourceId = SnapshotNaming.sourceId(source.source.idSeed)
            val games = AutoBackupPolicy.includedGames(sourceId, AutoBackupPolicy.eligibleGames(scan.getOrThrow()), excluded)
            for (game in games) {
                if (AutoBackupPolicy.shouldYield(BatchCenter.isRunning)) {
                    finishRun(tally, verdicts, repository)
                    return Result.success()
                }
                tally = tally.copy(games = tally.games + 1)
                val name = game.presentation.primaryName
                val identity = GameCloudNaming.identity(
                    source.source,
                    sourceId,
                    game.copy(saves = GameLibrary.batchBackupLocations(game)),
                )
                // A stopped scope gets no snapshot and no request; the user re-enables it by hand.
                if (ScopeTombstonePolicy.shouldSkip(tombstones.get(identity))) {
                    tally = tally.copy(cloudStopped = tally.cloudStopped + 1)
                    continue
                }
                when (val outcome = runCatching {
                    StorageOperationLock.withLock(StorageOperationLock.AUTO_BACKUP) {
                        backupGame(
                            source = source.source,
                            sourceId = sourceId,
                            game = game,
                            repository = repository,
                            storage = storage,
                            rootStorage = rootStorage,
                            config = config,
                            client = client,
                            remoteSnapshots = remoteSnapshots,
                            headReader = headReader,
                        )
                    }
                }.getOrElse { error -> backupFailure(error) }) {
                    GameBackupOutcome.Skipped -> tally = tally.copy(unchanged = tally.unchanged + 1)
                    GameBackupOutcome.Uploaded -> tally = tally.copy(uploaded = tally.uploaded + 1)
                    GameBackupOutcome.Restored -> {
                        tally = tally.copy(restored = tally.restored + 1)
                        record(
                            ActivityEntry.create(
                                ActivityKind.RESTORE,
                                ActivityLevel.SUCCESS,
                                "已在后台恢复 $name 的跨设备更新",
                                "恢复前的存档已保存为安全快照",
                            ),
                        )
                    }
                    is GameBackupOutcome.UploadFailed -> {
                        retryableFailure = retryableFailure || outcome.error.retryable
                        tally = tally.copy(failed = tally.failed + (name to (outcome.error.message ?: "上传失败")))
                    }
                    is GameBackupOutcome.CloudStopped -> {
                        // Reaching this branch means the tombstone was created in this round
                        // (a pre-existing one was skipped above), so the user is told once.
                        tally = tally.copy(cloudStopped = tally.cloudStopped + 1)
                        record(
                            ActivityEntry.create(
                                ActivityKind.CLOUD_CHECK,
                                ActivityLevel.WARNING,
                                if (outcome.error.retired) "云端存档已合并" else "云端存档已被删除",
                                "$name：已停止云同步，本机存档不受影响。请在游戏详情里处理。",
                                needsAction = true,
                            ),
                        )
                    }
                    is GameBackupOutcome.Deferred -> {
                        retryableFailure = retryableFailure || outcome.retryable
                        tally = tally.copy(deferred = tally.deferred + (name to outcome.reason))
                        // Conflicts and newer cloud versions wait for the user (plan Q7): remember the
                        // verdict so "同步与任务" lists the game, and tell the user once.
                        outcome.action?.let { action ->
                            verdicts["$sourceId::${game.gameKey}"] = CloudVerdict(action, "")
                            record(
                                ActivityEntry.create(
                                    ActivityKind.CLOUD_CHECK,
                                    ActivityLevel.WARNING,
                                    if (action == GameSaveRemoteAction.REVIEW_REQUIRED) "$name：云端有冲突，需要你确认" else "$name：云端有较新的版本，等待恢复",
                                    "${outcome.reason}。在“同步与任务”中选择用云端覆盖或保留本机。",
                                    needsAction = true,
                                ),
                            )
                        }
                    }
                }
            }
        }
        finishRun(tally, verdicts, repository)
        return if (retryableFailure && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    /** Writes the run's 动态 line and the cloud verdicts it found. */
    private fun finishRun(tally: AutoBackupTally, verdicts: Map<String, CloudVerdict>, repository: SnapshotRepository) {
        if (verdicts.isNotEmpty()) {
            // Tied to each game's newest ordinary snapshot, like the verdicts of a check in the app.
            val records = runCatching { repository.list() }.getOrDefault(emptyList())
            val tied = verdicts.mapValues { (key, verdict) -> verdict.copy(recordId = GameStatusResolver.latestRecordId(records, key)) }
            runCatching { CloudVerdictStore(applicationContext).update(verdicts = tied) }
        }
        record(AutoBackupPolicy.runEntry(tally))
    }

    /** Includes every selected source OpenSave can read; a missing preset is simply skipped. */
    private suspend fun backupSources(
        storage: SafStorage,
        rootStorage: RootStorage,
        installedGameCatalog: InstalledGameCatalog,
    ): List<SaveSourceOption> = ScheduledSources.readableSources(
        applicationContext,
        storage,
        rootStorage,
        installedGameCatalog,
        onRootUnavailable = { reason ->
            record(
                ActivityEntry.create(
                    ActivityKind.PERMISSION,
                    ActivityLevel.WARNING,
                    "后台没有获得 Root 权限，本次只备份了授权目录",
                    "请在 Magisk 中把 SaveVault 的超级用户授权设为“永久”：后台任务无法弹出授权提示。（$reason）",
                    needsAction = true,
                ),
            )
        },
        onFoldersLost = { labels ->
            record(
                ActivityEntry.create(
                    ActivityKind.PERMISSION,
                    ActivityLevel.WARNING,
                    "${labels.size} 个授权目录的权限已失效，已跳过",
                    labels.joinToString("、") + "；请在“来源”页重新添加授权目录。",
                    needsAction = true,
                ),
            )
        },
    )

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
        client: GameSaveClient,
        remoteSnapshots: List<RemoteSnapshot>,
        headReader: GameSaveHeadReader,
    ): GameBackupOutcome {
        val saves = GameLibrary.batchBackupLocations(game)
        val manifest = GameSnapshotManifest.from(game, saves)
        val selectedGame = game.copy(saves = saves)
        val cloudIdentity = GameCloudNaming.identity(source, sourceId, selectedGame)
        val workspace = File(applicationContext.filesDir, AUTO_GAME_WORKSPACE_DIRECTORY)
        // Read before the copy: content changed during it reads as a change next time, never the reverse.
        val signature = runCatching { SaveSignatureReader(storage, rootStorage).read(source, game) }.getOrNull()
        try {
            when (source) {
                is SourceSelection.Saf -> storage.copyGameLocations(source.uri, manifest.locations, workspace)
                is SourceSelection.Root -> rootStorage.copyGameLocations(source.path, manifest.locations, workspace)
            }.getOrThrow()
            val manifestFile = File(workspace, GameSnapshotManifest.FILE_NAME)
            manifest.write(manifestFile)
            val fingerprint = AutoBackupPolicy.fingerprint(workspace)
            if (fingerprint.dataFileCount == 0) return GameBackupOutcome.Skipped

            val native = GameSaveNativeSync(applicationContext, config, client)
            val nativeResult = native.check(cloudIdentity, fingerprint.manifestHash)
            val nativeDecision = nativeResult.getOrNull()
            if (nativeDecision == null) {
                val nativeError = nativeResult.exceptionOrNull()
                if (nativeError !is GameSaveScopesUnsupportedException) {
                    throw nativeError ?: error("读取 SaveVault 云端原生同步范围失败")
                }
            } else {
                when (nativeDecision.action) {
                    GameSaveRemoteAction.AUTO_RESTORE -> {
                        // Background writes remain limited to the Root Switch
                        // layout already proven portable with the desktop app.
                        return GameBackupOutcome.Deferred(
                            "检测到连续的云端更新，已等待在应用中确认恢复",
                            retryable = false,
                            action = GameSaveRemoteAction.AUTO_RESTORE,
                        )
                    }

                    GameSaveRemoteAction.REVIEW_REQUIRED -> {
                        return GameBackupOutcome.Deferred(
                            nativeDecision.message,
                            retryable = false,
                            action = GameSaveRemoteAction.REVIEW_REQUIRED,
                        )
                    }

                    GameSaveRemoteAction.WAIT_FOR_REMOTE -> {
                        return GameBackupOutcome.Deferred(nativeDecision.message, retryable = true)
                    }

                    GameSaveRemoteAction.UP_TO_DATE -> Unit
                }
            }

            if (nativeDecision == null && cloudIdentity.desktopCompatible) {
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
                            client = client,
                            identity = cloudIdentity,
                            candidate = candidate,
                        )
                    }

                    GameSaveRemoteAction.REVIEW_REQUIRED -> {
                        return GameBackupOutcome.Deferred(
                            decision.message,
                            retryable = false,
                            action = GameSaveRemoteAction.REVIEW_REQUIRED,
                        )
                    }

                    GameSaveRemoteAction.WAIT_FOR_REMOTE -> {
                        return GameBackupOutcome.Deferred(decision.message, retryable = true)
                    }

                    GameSaveRemoteAction.UP_TO_DATE -> Unit
                }
            }

            // Unchanged content reuses the newest snapshot (shared with manual
            // backups through the content hash). A snapshot can also finish
            // uploading while its current-save head fails to publish; reusing
            // it lets the desktop client eventually receive the sync signal.
            val existing = repository.findReusableRecord(
                sourceId = sourceId,
                gameKey = game.gameKey,
                contentHash = fingerprint.contentHash,
                currentManifestHash = fingerprint.manifestHash,
            )
            if (existing != null && signature != null) repository.setSaveSignature(existing.id, signature)
            if (nativeDecision == null && existing?.uploaded == true) {
                return publishCurrentHead(config, cloudIdentity, existing, repository.fileFor(existing))
                    .also { if (it == GameBackupOutcome.Uploaded) repository.markUploaded(existing.id) }
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
            if (pending == null && signature != null) repository.setSaveSignature(record.id, signature)

            if (nativeDecision != null) {
                return publishNativeCurrentHead(
                    native, cloudIdentity, record, repository.fileFor(record), repository, GameCloudFacts.of(selectedGame),
                )
            }

            return client.upload(repository.fileFor(record), cloudIdentity.saveType, record.remoteName).fold(
                onSuccess = {
                    // Without the head protocol the upload is all there is;
                    // with it, the record is the current save once the head moved.
                    repository.markUploaded(record.id, asCloudHead = !cloudIdentity.desktopCompatible)
                    publishCurrentHead(config, cloudIdentity, record, repository.fileFor(record))
                        .also { if (it == GameBackupOutcome.Uploaded) repository.markUploaded(record.id) }
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
        client: GameSaveClient,
        identity: GameCloudIdentity,
        candidate: GameSaveRemoteHead,
    ): GameBackupOutcome {
        val rootSource = source as? SourceSelection.Root
            ?: return GameBackupOutcome.Deferred("当前存档来源不支持安全自动恢复", retryable = false, action = GameSaveRemoteAction.AUTO_RESTORE)
        val location = manifest.locations.singleOrNull()
            ?: return GameBackupOutcome.Deferred("Switch 存档位置不是唯一目录，已等待确认", retryable = false, action = GameSaveRemoteAction.AUTO_RESTORE)
        val remoteArchive = remoteCacheFile(candidate.archive)
        client.download(candidate.archive, remoteArchive).getOrThrow()

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
                comment = "跨设备恢复前安全快照",
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
            contentHash = fingerprint.contentHash,
            hashVersion = if (fingerprint.contentHash.isBlank()) 0 else ContentHash.VERSION,
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
            onFailure = { error -> backupFailure(error) },
        )
    }

    private fun publishNativeCurrentHead(
        native: GameSaveNativeSync,
        cloudIdentity: GameCloudIdentity,
        record: SnapshotRecord,
        archiveFile: File,
        repository: SnapshotRepository,
        facts: GameCloudFacts,
    ): GameBackupOutcome = native.publishSnapshot(cloudIdentity, record, archiveFile, facts).fold(
        onSuccess = { result ->
            repository.markUploaded(record.id, asCloudHead = result is GameSaveNativePublishResult.Confirmed)
            when (result) {
                is GameSaveNativePublishResult.Confirmed -> GameBackupOutcome.Uploaded
                // The local newest is now a 冲突副本; "同步与任务" lists such games from the snapshots.
                is GameSaveNativePublishResult.Conflict -> GameBackupOutcome.Deferred(
                    "本机版本已保留为冲突副本，云端当前版本没有被覆盖",
                    retryable = false,
                )
            }
        },
        onFailure = { error -> backupFailure(error) },
    )

    private fun backupFailure(error: Throwable): GameBackupOutcome = when {
        // A deleted or merged scope is a state the user has to see, not a failed upload;
        // an unknown 410 keeps the generic failure and is never retried.
        error is GameSaveScopeGoneException && (error.deleted || error.retired) -> GameBackupOutcome.CloudStopped(error)
        else -> GameBackupOutcome.UploadFailed(asGameSaveError(error))
    }

    private fun asGameSaveError(error: Throwable): GameSaveException = when (error) {
        is GameSaveException -> error
        else -> GameSaveException(error.message ?: "SaveVault 云端上传失败", retryable = true)
    }

    private sealed interface GameBackupOutcome {
        data object Skipped : GameBackupOutcome
        data object Uploaded : GameBackupOutcome
        data object Restored : GameBackupOutcome
        data class UploadFailed(val error: GameSaveException) : GameBackupOutcome
        /** The cloud deleted this scope or merged it; the game is skipped until the user acts. */
        data class CloudStopped(val error: GameSaveScopeGoneException) : GameBackupOutcome

        /** [action] is the cloud verdict the user must decide on; null for waits that resolve themselves. */
        data class Deferred(
            val reason: String,
            val retryable: Boolean,
            val action: GameSaveRemoteAction? = null,
        ) : GameBackupOutcome
    }

    private companion object {
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val AUTO_GAME_WORKSPACE_DIRECTORY = "auto-game-workspace"
        const val MAX_RETRIES = 3
    }
}
