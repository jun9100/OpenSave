package com.jun9100.opensave

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

enum class OpenSavePage {
    LIBRARY,
    MANAGEMENT,
}

data class RestoreRequest(
    val local: SnapshotRecord? = null,
    val remote: RemoteSnapshot? = null,
    val remoteHead: GameSaveRemoteHead? = null,
    val nativeHead: GameSaveNativeCandidate? = null,
    val game: DetectedGame? = null,
    val source: SourceSelection? = null,
)

/** A remote head that is deliberately waiting for a user-confirmed restore. */
data class GameSaveCloudUpdate(
    val entry: SourceGameEntry,
    val legacyDecision: GameSaveHeadDecision? = null,
    val nativeDecision: GameSaveNativeDecision? = null,
) {
    init {
        require((legacyDecision != null) xor (nativeDecision != null)) { "云端更新必须属于一种同步协议" }
    }

    val action: GameSaveRemoteAction
        get() = nativeDecision?.action ?: checkNotNull(legacyDecision).action

    val message: String
        get() = nativeDecision?.message ?: checkNotNull(legacyDecision).message

    val remote: RemoteSnapshot?
        get() = nativeDecision?.candidate?.remote ?: legacyDecision?.candidate?.archive

    val legacyHead: GameSaveRemoteHead?
        get() = legacyDecision?.candidate

    val nativeHead: GameSaveNativeCandidate?
        get() = nativeDecision?.candidate

    val deviceName: String
        get() = nativeHead?.snapshot?.deviceName ?: legacyHead?.head?.deviceName.orEmpty()

    val receivedAtMillis: Long
        get() = nativeHead?.snapshot?.receivedAt?.let { value ->
            runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
        } ?: legacyHead?.savedAtMillis ?: 0L
}

data class SourceUiState(
    val storageMode: StorageMode = StorageMode.SAF,
    val selectedUri: Uri? = null,
    val hasPersistedPermission: Boolean = false,
    val authorizedTrees: List<AuthorizedSafTree> = emptyList(),
    val rootPath: String = "",
    val rootAvailable: Boolean = false,
    val rootChecked: Boolean = false,
    val busy: Boolean = false,
    val action: String? = null,
    val error: String? = null,
    val report: CopyReport? = null,
    val scan: SaveScanReport? = null,
    val sourceScans: List<SourceScanResult> = emptyList(),
    val sourceOptions: List<SaveSourceOption> = emptyList(),
    val scanRevision: Long = 0L,
    val snapshots: List<SnapshotRecord> = emptyList(),
    val remoteSnapshots: List<RemoteSnapshot> = emptyList(),
    val cloudUpdates: List<GameSaveCloudUpdate> = emptyList(),
    val activeSourceId: String? = null,
    val page: OpenSavePage = OpenSavePage.LIBRARY,
    val gameSaveEnabled: Boolean = false,
    val gameSaveUrl: String = "",
    val gameSaveSyncKey: String = "",
    val gameSaveSaved: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    val pendingRestore: RestoreRequest? = null,
    val pendingGameBackup: SourceGameEntry? = null,
)

class SourceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val storage = SafStorage(application)
    private val rootStorage = RootStorage(application)
    private val selectionStore = SourceSelectionStore(application)
    private val installedGameCatalog = InstalledGameCatalog(application)
    private val snapshotEngine = SnapshotEngine()
    private val snapshotRepository = SnapshotRepository(File(application.filesDir, SNAPSHOT_DIRECTORY))
    private val gameSaveStore = GameSaveConfigStore(application)
    private val gameSaveDevice = GameSaveDeviceStore(application)
    private val autoBackupScheduler = AutoBackupScheduler(application)
    private val state = MutableStateFlow(SourceUiState())
    val uiState: StateFlow<SourceUiState> = state.asStateFlow()

    init {
        autoBackupScheduler.ensureScheduled()
        refreshPersistedSource()
    }

    fun onTreeSelected(uri: Uri) {
        storage.persistTreePermission(uri)
            .onSuccess {
                selectionStore.setSourcesEnabled(listOf(SourceSelection.Saf(uri).idSeed), enabled = true)
                val trees = storage.persistedTrees()
                selectionStore.saveMode(StorageMode.SAF)
                state.value = state.value.copy(
                    storageMode = StorageMode.SAF,
                    selectedUri = uri,
                    hasPersistedPermission = storage.hasReadPermission(uri),
                    authorizedTrees = trees,
                    action = "已添加系统授权目录",
                    error = null,
                    scan = null,
                    activeSourceId = SnapshotNaming.sourceId(uri.toString()),
                    sourceOptions = buildSourceOptions(trees, state.value.rootPath),
                )
                scanSource()
            }
            .onFailure { error ->
                state.value = state.value.copy(
                    selectedUri = uri,
                    hasPersistedPermission = false,
                    error = "无法保存目录权限：${error.message ?: "provider refused persistent access"}",
                )
            }
    }

    fun forgetAuthorizedTree(uri: Uri) {
        if (state.value.busy) return
        storage.forgetTree(uri)
            .onSuccess {
                val trees = storage.persistedTrees()
                val selectedUri = state.value.selectedUri.takeIf { it != uri } ?: trees.firstOrNull()?.uri
                val activeSourceId = if (state.value.storageMode == StorageMode.SAF) {
                    selectedUri?.let { SnapshotNaming.sourceId(it.toString()) }
                } else {
                    state.value.activeSourceId
                }
                state.value = state.value.copy(
                    selectedUri = selectedUri,
                    hasPersistedPermission = selectedUri != null && storage.hasReadPermission(selectedUri),
                    authorizedTrees = trees,
                    activeSourceId = activeSourceId,
                    sourceOptions = buildSourceOptions(trees, state.value.rootPath),
                    action = "已移除系统授权目录",
                    error = null,
                )
                scanSource()
            }
            .onFailure { error ->
                updateState { copy(error = "移除系统授权目录失败：${error.message}") }
            }
    }

    fun refreshPersistedSource() {
        val trees = storage.persistedTrees()
        val uri = storage.persistedTreeUri()
        val mode = selectionStore.mode()
        val rootPath = rootStorage.persistedRootPath().orEmpty()
        val initialOptions = buildSourceOptions(trees, rootPath)
        val fullDiscoveryEnabled = selectionStore.enableFullDeviceDiscoveryOnce(initialOptions.map(SaveSourceOption::id))
        val sourceOptions = if (fullDiscoveryEnabled) buildSourceOptions(trees, rootPath) else initialOptions
        val config = runCatching { gameSaveStore.load() }.getOrDefault(GameSaveConfig())
        val previous = state.value
        val sameSource = previous.storageMode == mode &&
            previous.selectedUri == uri &&
            previous.authorizedTrees == trees &&
            previous.rootPath == rootPath
        val persistedScan = previous.scan.takeIf { sameSource }
        val source = when (mode) {
            StorageMode.SAF -> uri?.let { SourceSelection.Saf(it) }
            StorageMode.ROOT -> rootPath.takeIf(String::isNotBlank)?.let { SourceSelection.Root(it) }
        }
        state.value = state.value.copy(
            storageMode = mode,
            selectedUri = uri,
            hasPersistedPermission = uri != null && storage.hasReadPermission(uri),
            authorizedTrees = trees,
            rootPath = rootPath,
            rootChecked = false,
            snapshots = currentSnapshots(),
            activeSourceId = source?.let { SnapshotNaming.sourceId(it.idSeed) },
            gameSaveEnabled = config.enabled,
            gameSaveUrl = config.serviceUrl,
            gameSaveSyncKey = config.syncKey,
            gameSaveSaved = config.serviceUrl.isNotBlank() && config.syncKey.isNotBlank(),
            autoBackupEnabled = autoBackupScheduler.isEnabled(),
            scan = persistedScan,
            sourceOptions = sourceOptions,
            error = null,
            action = if (fullDiscoveryEnabled) "已启用全设备自动存档来源" else previous.action,
        )
        viewModelScope.launch {
            val rootAvailable = withContext(Dispatchers.IO) { rootStorage.checkRootAccess().isSuccess }
            state.value = state.value.copy(
                rootAvailable = rootAvailable,
                rootChecked = true,
            )
            scanSource()
        }
    }

    fun showPage(page: OpenSavePage) = updateState { copy(page = page) }

    fun selectStorageMode(mode: StorageMode) {
        selectionStore.saveMode(mode)
        updateState {
            copy(
                storageMode = mode,
                activeSourceId = when (mode) {
                    StorageMode.SAF -> selectedUri?.let { SnapshotNaming.sourceId(it.toString()) }
                    StorageMode.ROOT -> rootPath.takeIf(String::isNotBlank)?.let {
                        SnapshotNaming.sourceId(SourceSelection.Root(it).idSeed)
                    }
                },
                action = if (mode == StorageMode.ROOT) "已切换到 Root 路径模式" else "已切换到系统授权模式",
                error = null,
            )
        }
        if (mode == StorageMode.ROOT) {
            refreshRootAccess()
        } else {
            scanSource()
        }
    }

    fun updateRootPath(value: String) = updateState { copy(rootPath = value) }

    fun applyRootPath() {
        rootStorage.persistRootPath(state.value.rootPath)
            .onSuccess { canonical ->
                selectionStore.saveMode(StorageMode.ROOT)
                state.value = state.value.copy(
                    storageMode = StorageMode.ROOT,
                    rootPath = canonical,
                    action = "已保存 Root 路径",
                    error = null,
                    scan = null,
                    activeSourceId = SnapshotNaming.sourceId(SourceSelection.Root(canonical).idSeed),
                    sourceOptions = buildSourceOptions(state.value.authorizedTrees, canonical),
                )
                refreshRootAccess()
            }
            .onFailure { error ->
                state.value = state.value.copy(error = "Root 路径无效：${error.message}")
            }
    }

    private fun refreshRootAccess() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = rootStorage.checkRootAccess()
            val rootAvailable = result.isSuccess
            state.value = state.value.copy(
                rootAvailable = rootAvailable,
                rootChecked = true,
                error = if (!rootAvailable && state.value.storageMode == StorageMode.ROOT) {
                    "Root 不可用：${result.exceptionOrNull()?.message ?: "su 未返回 uid 0"}"
                } else {
                    state.value.error
                },
            )
            if (rootAvailable && state.value.storageMode == StorageMode.ROOT) scanSource()
        }
    }

    fun updateGameSaveUrl(value: String) = updateState { copy(gameSaveUrl = value, gameSaveSaved = false) }

    fun updateGameSaveSyncKey(value: String) = updateState { copy(gameSaveSyncKey = value, gameSaveSaved = false) }

    fun updateGameSaveEnabled(value: Boolean) = updateState { copy(gameSaveEnabled = value, gameSaveSaved = false) }

    fun saveGameSave() {
        val config = gameSaveConfigFromState()
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在加密保存 game-save 配置", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { gameSaveStore.save(config) }
            }
            result.onSuccess {
                state.value = state.value.copy(
                    busy = false,
                    action = "game-save 配置已保存",
                    gameSaveUrl = GameSaveClient.normalizeServiceUrl(config.serviceUrl),
                    gameSaveSaved = true,
                )
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "保存 game-save 配置失败：${error.message}")
            }
        }
    }

    fun testGameSave() {
        val config = gameSaveConfigFromState()
        if (config.serviceUrl.isBlank() || config.syncKey.isBlank()) {
            updateState { copy(error = "请先填写 game-save 服务地址和同步密钥") }
            return
        }
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在测试 game-save 连接", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { gameSaveClient(config).testConnection().getOrThrow() }
            }
            result.onSuccess {
                state.value = state.value.copy(busy = false, action = "game-save 连接成功")
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "game-save 连接失败：${error.message}")
            }
        }
    }

    fun importSource() {
        val source = requiredSource() ?: return
        runSourceOperation("正在复制存档到私有工作区") {
            importSelection(source)
        }
    }

    fun scanSource() {
        if (state.value.busy) return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在扫描已选存档来源", error = null)
            val (rootAvailable, options, scans) = withContext(Dispatchers.IO) {
                val rootAvailable = rootStorage.checkRootAccess().isSuccess
                val current = state.value
                val androidGames = installedGameCatalog.allApps()
                val options = buildSourceOptions(current.authorizedTrees, current.rootPath)
                val results = options.filter { it.selected }.map { option ->
                    val source = option.source
                    val label = option.label
                    when (source) {
                        is SourceSelection.Saf -> if (!storage.hasReadPermission(source.uri)) {
                            SourceScanResult(
                                source,
                                label,
                                skippedReason = "目录授权已失效",
                                access = SourceAccessStatus.unavailable(
                                    SourceAccessCapability.SAF,
                                    "目录授权已失效",
                                ),
                            )
                        } else {
                            storage.scanTree(source.uri, androidGames).fold(
                                onSuccess = {
                                    SourceScanResult(
                                        source,
                                        label,
                                        scan = it,
                                        access = SourceAccessStatus.safAccessible(
                                            storage.hasWritePermission(source.uri),
                                        ),
                                    )
                                },
                                onFailure = {
                                    val reason = it.message ?: "读取失败"
                                    SourceScanResult(
                                        source,
                                        label,
                                        skippedReason = reason,
                                        access = SourceAccessStatus.unavailable(
                                            SourceAccessCapability.SAF,
                                            reason,
                                        ),
                                    )
                                },
                            )
                        }

                        is SourceSelection.Root -> when {
                            !rootAvailable -> SourceScanResult(
                                source,
                                label,
                                skippedReason = "Root 未授权",
                                access = SourceAccessStatus.unavailable(
                                    SourceAccessCapability.ROOT,
                                    "Root 未授权",
                                ),
                            )
                            else -> {
                                val exists = rootStorage.pathExists(source.path)
                                when {
                                    exists.isFailure -> {
                                        val reason = exists.exceptionOrNull()?.message ?: "无法检查目录"
                                        SourceScanResult(
                                            source,
                                            label,
                                            skippedReason = reason,
                                            access = SourceAccessStatus.unavailable(
                                                SourceAccessCapability.ROOT,
                                                reason,
                                            ),
                                        )
                                    }
                                    !exists.getOrThrow() -> SourceScanResult(
                                        source,
                                        label,
                                        skippedReason = "目录不存在",
                                        access = SourceAccessStatus.unavailable(
                                            SourceAccessCapability.ROOT,
                                            "目录不存在",
                                        ),
                                    )
                                    else -> rootStorage.scanPath(source.path, androidGames).fold(
                                        onSuccess = {
                                            SourceScanResult(
                                                source,
                                                label,
                                                scan = it,
                                                access = SourceAccessStatus.rootOnly(),
                                            )
                                        },
                                        onFailure = {
                                            val reason = it.message ?: "扫描失败"
                                            SourceScanResult(
                                                source,
                                                label,
                                                skippedReason = reason,
                                                access = SourceAccessStatus.unavailable(
                                                    SourceAccessCapability.ROOT,
                                                    reason,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Triple(rootAvailable, options, results)
            }
            val games = GameLibrary.sourceGames(scans)
            val availableSourceCount = scans.count { it.access.readable }
            val skippedSourceCount = scans.size - availableSourceCount
            val activeScan = scans.firstOrNull { it.sourceId == state.value.activeSourceId }?.scan
            state.value = state.value.copy(
                busy = false,
                rootAvailable = rootAvailable,
                rootChecked = true,
                action = "扫描完成：${games.size} 个游戏条目，$availableSourceCount 个来源可读，跳过 $skippedSourceCount 个",
                sourceScans = scans,
                sourceOptions = options,
                scanRevision = state.value.scanRevision + 1,
                scan = activeScan,
                error = null,
            )
        }
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        if (state.value.busy) return
        selectionStore.setSourcesEnabled(listOf(sourceId), enabled)
        val current = state.value
        state.value = current.copy(
            sourceOptions = buildSourceOptions(current.authorizedTrees, current.rootPath),
            action = if (enabled) "已启用存档来源" else "已停用存档来源",
            error = null,
        )
        scanSource()
    }

    fun setAllSourcesEnabled(enabled: Boolean) {
        if (state.value.busy) return
        val current = state.value
        val sourceIds = buildSourceOptions(current.authorizedTrees, current.rootPath).map(SaveSourceOption::id)
        selectionStore.setSourcesEnabled(sourceIds, enabled)
        state.value = current.copy(
            sourceOptions = buildSourceOptions(current.authorizedTrees, current.rootPath),
            action = if (enabled) "已启用全部存档来源" else "已停用全部存档来源",
            error = null,
        )
        scanSource()
    }

    fun restoreSource() {
        val source = requiredSource() ?: return
        if (!canWriteTo(source)) {
            updateState { copy(error = "当前系统授权目录为只读，不能恢复；请重新授予读写权限") }
            return
        }
        runSourceOperation("正在把工作区写回授权目录") {
            restoreSelection(source)
        }
    }

    fun backupNow() {
        val source = requiredSource() ?: return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在检查并创建快照", error = null)
            val outcome = withContext(Dispatchers.IO) {
                runCatching { captureSnapshot(source, upload = true) }
            }
            outcome.onSuccess { captured ->
                val uploadSuffix = captured.uploadError?.let { "；$it" }.orEmpty()
                state.value = state.value.copy(
                    busy = false,
                    action = "快照已保存：${captured.record.remoteName}$uploadSuffix",
                    report = captured.report,
                    snapshots = currentSnapshots(),
                    error = captured.uploadError,
                )
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "创建快照失败：${error.message}")
            }
        }
    }

    fun requestGameBackup(entry: SourceGameEntry) {
        if (state.value.busy) return
        val game = entry.game
        if (game.candidateCount > 0) {
            updateState { copy(pendingGameBackup = entry, error = null) }
        } else {
            createGameBackup(entry, includeCandidates = false)
        }
    }

    fun confirmGameBackup(includeCandidates: Boolean) {
        val entry = state.value.pendingGameBackup ?: return
        updateState { copy(pendingGameBackup = null) }
        createGameBackup(entry, includeCandidates)
    }

    fun cancelGameBackup() = updateState { copy(pendingGameBackup = null) }

    fun requestBatchGameBackup(gameKeys: Set<String>) {
        if (state.value.busy) return
        val selectedEntries = GameLibrary.batchBackupEligibleEntries(
            GameLibrary.sourceGames(state.value.sourceScans),
        ).filter { it.libraryKey in gameKeys }
        if (selectedEntries.isEmpty()) {
            updateState { copy(error = "请选择至少一款含有已识别存档位置的游戏") }
            return
        }

        viewModelScope.launch {
            var completed = 0
            val failures = mutableListOf<String>()
            val uploadFailures = mutableListOf<String>()
            val syncNotices = mutableListOf<String>()
            val cloudUpdates = mutableListOf<GameSaveCloudUpdate>()
            selectedEntries.forEachIndexed { index, entry ->
                val game = entry.game
                state.value = state.value.copy(
                    busy = true,
                    action = "批量备份 ${index + 1}/${selectedEntries.size}：${entry.sourceLabel} · ${game.presentation.primaryName}",
                    error = null,
                )
                val manifest = runCatching {
                    GameSnapshotManifest.from(game, GameLibrary.batchBackupLocations(game))
                }
                if (manifest.isFailure) {
                    failures += "${game.presentation.primaryName}：${manifest.exceptionOrNull()?.message ?: "无法生成快照清单"}"
                    return@forEachIndexed
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        StorageOperationLock.withLock {
                            captureGameSnapshotLocked(entry, manifest.getOrThrow())
                        }
                    }
                }
                result.onSuccess { captured ->
                    completed++
                    captured.uploadError?.let { uploadFailures += "${game.presentation.primaryName}：$it" }
                    captured.syncNotice?.let { syncNotices += "${game.presentation.primaryName}：$it" }
                    captured.cloudUpdate?.let(cloudUpdates::add)
                }.onFailure { error ->
                    failures += "${game.presentation.primaryName}：${error.message ?: "备份失败"}"
                }
            }

            state.value = state.value.copy(
                busy = false,
                action = "批量备份完成：本地快照 $completed 款，失败 ${failures.size} 款，云端上传失败 ${uploadFailures.size} 款，跨设备待处理 ${cloudUpdates.size} 款",
                snapshots = currentSnapshots(),
                cloudUpdates = mergeCloudUpdates(state.value.cloudUpdates, cloudUpdates),
                error = (failures + uploadFailures + syncNotices).take(6).takeIf { it.isNotEmpty() }?.joinToString("\n"),
            )
        }
    }

    private fun createGameBackup(entry: SourceGameEntry, includeCandidates: Boolean) {
        val game = entry.game
        val selectedSaves = game.saves.filter { includeCandidates || !it.candidate }
        if (selectedSaves.isEmpty()) {
            updateState { copy(error = "该游戏只有候选存档位置；确认后才能备份") }
            return
        }
        val manifest = runCatching { GameSnapshotManifest.from(game, selectedSaves) }
            .getOrElse { error ->
                updateState { copy(error = "无法创建游戏快照：${error.message}") }
                return
            }

        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在备份 ${manifest.gameName}", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    StorageOperationLock.withLock { captureGameSnapshotLocked(entry, manifest) }
                }
            }
            result.onSuccess { captured ->
                val notice = listOfNotNull(captured.uploadError, captured.syncNotice).joinToString("；")
                val uploadSuffix = notice.takeIf { it.isNotBlank() }?.let { "；$it" }.orEmpty()
                state.value = state.value.copy(
                    busy = false,
                    action = "已备份 ${manifest.gameName}：${captured.record.remoteName}$uploadSuffix",
                    snapshots = currentSnapshots(),
                    cloudUpdates = mergeCloudUpdates(state.value.cloudUpdates, listOfNotNull(captured.cloudUpdate)),
                    error = captured.uploadError,
                )
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "备份 ${manifest.gameName} 失败：${error.message}")
            }
        }
    }

    private suspend fun captureGameSnapshotLocked(
        entry: SourceGameEntry,
        manifest: GameSnapshotManifest,
    ): GameCaptureOutcome {
        val source = entry.source
        val selectedGame = entry.game.copy(
            saves = entry.game.saves.filter { save -> manifest.locations.any { it.relativePath == save.relativePath } },
        )
        val cloudIdentity = GameCloudNaming.identity(source, entry.sourceId, selectedGame)
        val workspace = File(app.filesDir, GAME_WORKSPACE_DIRECTORY)
        try {
            copyGameLocations(source, manifest.locations, workspace).getOrThrow()
            val manifestFile = File(workspace, GameSnapshotManifest.FILE_NAME)
            manifest.write(manifestFile)
            val contentFingerprint = AutoBackupPolicy.fingerprint(workspace)

            val sourceId = entry.sourceId
            val timestamp = maxOf(
                System.currentTimeMillis(),
                (snapshotRepository.list().maxOfOrNull { it.createdAtMillis } ?: 0L) + 1L,
            )
            val remoteName = GameCloudNaming.remoteName(cloudIdentity, timestamp)
            val archive = File(app.filesDir, "$SNAPSHOT_DIRECTORY/$remoteName")
            val build = if (cloudIdentity.desktopCompatible) {
                val saveRoot = File(workspace, manifest.locations.single().archivePath)
                require(saveRoot.isDirectory) { "Switch 存档暂存目录不完整" }
                snapshotEngine.createSnapshot(saveRoot, archive).getOrThrow()
            } else {
                snapshotEngine.createSnapshot(workspace, archive).getOrThrow()
            }
            val record = SnapshotRecord(
                id = "game_${UUID.randomUUID()}",
                sourceId = sourceId,
                remoteName = remoteName,
                localPath = remoteName,
                createdAtMillis = timestamp,
                sizeBytes = build.archive.length(),
                sha256 = build.archiveSha256,
                manifestHash = contentFingerprint.manifestHash,
                gameKey = manifest.gameKey,
                gameName = manifest.gameName,
                saveType = cloudIdentity.saveType.id,
                cloudGameId = cloudIdentity.gameId,
            )
            snapshotRepository.add(record)

            val config = gameSaveStore.load()
            var uploadError: String? = null
            var syncNotice: String? = null
            var cloudUpdate: GameSaveCloudUpdate? = null
            if (config.enabled && config.serviceUrl.isNotBlank() && config.syncKey.isNotBlank()) {
                val client = gameSaveClient(config)
                val native = GameSaveNativeSync(app, config, client)
                val nativeUpload = native.publishSnapshot(cloudIdentity, record, archive)
                nativeUpload.onSuccess { result ->
                    // A confirmed head and an immutable conflict are both durable;
                    // neither should be retried later as a head-changing write.
                    snapshotRepository.markUploaded(record.id)
                    if (result is GameSaveNativePublishResult.Conflict) {
                        val decision = native.check(cloudIdentity, contentFingerprint.manifestHash).getOrNull()
                        cloudUpdate = decision
                            ?.takeUnless { it.action == GameSaveRemoteAction.UP_TO_DATE }
                            ?.let { GameSaveCloudUpdate(entry, nativeDecision = it) }
                        syncNotice = "本机版本已保留为云端冲突副本，当前存档没有被覆盖"
                    }
                }.onFailure { error ->
                    if (error !is GameSaveScopesUnsupportedException) {
                        uploadError = "云端原生同步失败：${error.message ?: "未知错误"}"
                        return@onFailure
                    }

                    // Older service images have no native scope route. Keep this
                    // compatibility path isolated so any other native error can
                    // never downgrade to the timestamped ZIP-head protocol.
                    val upload = client.upload(archive, cloudIdentity.saveType, remoteName)
                    if (upload.isFailure) {
                        uploadError = "云端上传失败：${upload.exceptionOrNull()?.message ?: "未知错误"}"
                        return@onFailure
                    }
                    snapshotRepository.markUploaded(record.id)
                    if (!cloudIdentity.desktopCompatible) return@onFailure
                    val decision = runCatching {
                        GameSaveHeadReader(app, config, client).check(
                            identity = cloudIdentity,
                            currentManifestHash = contentFingerprint.manifestHash,
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
                                .onFailure { legacyError ->
                                    uploadError = "云端同步公告失败：${legacyError.message ?: "未知错误"}"
                                }
                        }

                        else -> {
                            cloudUpdate = GameSaveCloudUpdate(entry, legacyDecision = decision)
                            val device = decision.candidate?.head?.deviceName?.let { "$it 的" }.orEmpty()
                            syncNotice = "检测到${device}跨设备版本，当前快照已上传但未更新云端当前存档"
                        }
                    }
                }
            }
            return GameCaptureOutcome(
                record = snapshotRepository.list().first { it.id == record.id },
                fileCount = build.fileCount,
                uploadError = uploadError,
                syncNotice = syncNotice,
                cloudUpdate = cloudUpdate,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    private suspend fun copyGameLocations(
        source: SourceSelection,
        locations: List<GameSnapshotLocation>,
        destination: File,
    ): Result<Unit> = when (source) {
        is SourceSelection.Saf -> storage.copyGameLocations(source.uri, locations, destination)
        is SourceSelection.Root -> rootStorage.copyGameLocations(source.path, locations, destination)
    }

    private suspend fun inspectCloudUpdateLocked(
        entry: SourceGameEntry,
        config: GameSaveConfig,
        client: GameSaveClient,
        reader: GameSaveHeadReader,
        remoteSnapshots: List<RemoteSnapshot>,
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
            val decision = reader.check(identity, fingerprint.manifestHash, remoteSnapshots)
            return decision.takeUnless { it.action == GameSaveRemoteAction.UP_TO_DATE }
                ?.let { GameSaveCloudUpdate(entry, legacyDecision = it) }
        } finally {
            workspace.deleteRecursively()
        }
    }

    fun listCloud() {
        val config = gameSaveConfigFromState()
        if (config.serviceUrl.isBlank() || config.syncKey.isBlank()) {
            updateState { copy(error = "请先填写并保存 game-save 服务地址和同步密钥") }
            return
        }
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在列举云端快照", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { gameSaveClient(config).list().getOrThrow() }
            }
            result.onSuccess { files ->
                state.value = state.value.copy(busy = false, action = "找到 ${files.size} 个云端快照", remoteSnapshots = files)
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "列举云端快照失败：${error.message}")
            }
        }
    }

    /** Inspects every selected save without writing outside a private workspace. */
    fun checkCloudUpdates() {
        if (state.value.busy) return
        val config = gameSaveConfigFromState()
        if (config.serviceUrl.isBlank() || config.syncKey.isBlank()) {
            updateState { copy(error = "请先填写 game-save 服务地址和同步密钥") }
            return
        }
        val entries = GameLibrary.batchBackupEligibleEntries(GameLibrary.sourceGames(state.value.sourceScans))
        if (entries.isEmpty()) {
            updateState { copy(error = "请先扫描含有已识别存档位置的来源") }
            return
        }

        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在检查跨设备存档更新", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    StorageOperationLock.withLock {
                        val client = gameSaveClient(config)
                        val remoteSnapshots = client.list().getOrThrow()
                        val reader = GameSaveHeadReader(app, config, client)
                        val updates = mutableListOf<GameSaveCloudUpdate>()
                        val failures = mutableListOf<String>()
                        entries.forEach { entry ->
                            runCatching {
                                inspectCloudUpdateLocked(entry, config, client, reader, remoteSnapshots)
                            }
                                .onSuccess { update -> update?.let(updates::add) }
                                .onFailure { error ->
                                    failures += "${entry.game.presentation.primaryName}：${error.message ?: "无法检查"}"
                                }
                        }
                        CloudCheckResult(remoteSnapshots, updates, failures)
                    }
                }
            }
            result.onSuccess { checked ->
                state.value = state.value.copy(
                    busy = false,
                    action = "跨设备检查完成：${checked.updates.size} 项待处理",
                    remoteSnapshots = checked.remoteSnapshots,
                    cloudUpdates = checked.updates,
                    error = checked.failures.take(5).takeIf { it.isNotEmpty() }?.joinToString("\n"),
                )
            }.onFailure { error ->
                state.value = state.value.copy(
                    busy = false,
                    action = null,
                    error = "检查跨设备更新失败：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    fun requestCloudUpdateRestore(update: GameSaveCloudUpdate) {
        val remote = update.remote ?: run {
            updateState { copy(error = update.message.ifBlank { "远端快照尚未就绪" }) }
            return
        }
        updateState {
            copy(
                pendingRestore = RestoreRequest(
                    remote = remote,
                    remoteHead = update.legacyHead,
                    nativeHead = update.nativeHead,
                    game = update.entry.game,
                    source = update.entry.source,
                ),
                error = null,
            )
        }
    }

    fun requestLocalRestore(record: SnapshotRecord) {
        if (record.gameKey != null) {
            updateState { copy(error = "请从对应游戏的历史记录中恢复此快照") }
            return
        }
        updateState { copy(pendingRestore = RestoreRequest(local = record), error = null) }
    }

    fun requestRemoteRestore(remote: RemoteSnapshot) {
        updateState { copy(pendingRestore = RestoreRequest(remote = remote), error = null) }
    }

    fun requestLocalGameRestore(entry: SourceGameEntry, record: SnapshotRecord) {
        val game = entry.game
        if (record.gameKey != game.gameKey || record.sourceId != entry.sourceId) {
            updateState { copy(error = "快照不属于当前游戏或存档来源") }
            return
        }
        updateState {
            copy(pendingRestore = RestoreRequest(local = record, game = game, source = entry.source), error = null)
        }
    }

    fun requestRemoteGameRestore(entry: SourceGameEntry, remote: RemoteSnapshot) {
        val game = entry.game
        val identity = GameCloudNaming.identity(entry.source, entry.sourceId, game)
        if (remote.type != identity.saveType.id || !remote.name.startsWith(GameCloudNaming.remotePrefix(identity))) {
            updateState { copy(error = "云端快照不属于当前游戏或存档来源") }
            return
        }
        updateState {
            copy(pendingRestore = RestoreRequest(remote = remote, game = game, source = entry.source), error = null)
        }
    }

    fun cancelRestore() = updateState { copy(pendingRestore = null) }

    fun confirmRestore() {
        val request = state.value.pendingRestore ?: return
        val source = request.source ?: requiredSource() ?: return
        if (!canWriteTo(source)) {
            updateState { copy(pendingRestore = null, error = "当前系统授权目录为只读，不能恢复；请重新授予读写权限") }
            return
        }
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, pendingRestore = null, action = "正在下载并安全恢复快照", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    request.game?.let { game ->
                        restoreGameRequest(request, source, game)
                        RestoreOutcome("已安全恢复 ${game.presentation.primaryName} 的游戏存档", null)
                    } ?: restoreRequest(request, source).let { report ->
                        RestoreOutcome("已恢复并写回授权目录：${report.fileCount} 个文件", report)
                    }
                }
            }
            result.onSuccess { outcome ->
                state.value = state.value.copy(
                    busy = false,
                    action = outcome.message,
                    report = outcome.report ?: state.value.report,
                    snapshots = currentSnapshots(),
                    cloudUpdates = state.value.cloudUpdates.filterNot { update ->
                        update.entry.source.idSeed == source.idSeed &&
                            update.entry.game.gameKey == request.game?.gameKey &&
                            update.remote?.name == request.remote?.name
                    },
                )
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "恢复快照失败：${error.message}")
            }
        }
    }

    private suspend fun restoreGameRequest(
        request: RestoreRequest,
        source: SourceSelection,
        requestedGame: DetectedGame,
    ) = StorageOperationLock.withLock {
        restoreGameRequestLocked(request, source, requestedGame)
    }

    private suspend fun restoreGameRequestLocked(
        request: RestoreRequest,
        source: SourceSelection,
        requestedGame: DetectedGame,
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
                    val config = gameSaveStore.load()
                    gameSaveClient(config).download(request.remote, downloaded).getOrThrow()
                }
                downloaded
            }
            else -> error("没有可恢复的游戏快照")
        }
        require(archive.isFile) { "游戏快照文件不存在" }
        request.local?.let { record ->
            require(Hashing.sha256(archive) == record.sha256) { "本地游戏快照校验失败" }
        }

        val currentGame = state.value.sourceScans
            .firstOrNull { it.sourceId == sourceId }
            ?.scan
            ?.let { scan -> GameLibrary.group(scan.detected).firstOrNull { it.gameKey == requestedGame.gameKey } }
            ?: error("请先扫描当前存档来源")
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
            val restoredManifestHash = AutoBackupPolicy.fingerprint(extractedRoot).manifestHash

            copyGameLocations(source, manifest.locations, safetyRoot).getOrThrow()
            val safetyManifest = File(safetyRoot, GameSnapshotManifest.FILE_NAME)
            manifest.write(safetyManifest)
            val safetyFingerprint = AutoBackupPolicy.fingerprint(safetyRoot)
            val timestamp = maxOf(
                System.currentTimeMillis(),
                (snapshotRepository.list().maxOfOrNull { it.createdAtMillis } ?: 0L) + 1L,
            )
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

            when (source) {
                is SourceSelection.Saf -> storage.restoreGameLocations(source.uri, manifest.locations, extractedRoot, safetyRoot)
                is SourceSelection.Root -> rootStorage.restoreGameLocations(source.path, manifest.locations, extractedRoot)
            }.getOrThrow()
            val identity = GameCloudNaming.identity(source, sourceId, requestedGame)
            request.nativeHead?.let { native ->
                GameSaveNativeSync(app, gameSaveStore.load()).markRemoteRestore(
                    identity = identity,
                    candidate = native,
                    manifestHash = restoredManifestHash,
                    archiveFile = archive,
                ).getOrThrow()
            } ?: request.remote?.takeIf { identity.desktopCompatible }?.let { remote ->
                GameSaveHeadPublisher(app, gameSaveStore.load()).markRemoteRestore(
                    identity = identity,
                    remote = remote,
                    manifestHash = restoredManifestHash,
                    archiveFile = archive,
                    remoteHead = request.remoteHead,
                ).getOrThrow()
            }
        } finally {
            workingRoot.deleteRecursively()
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        runCatching { autoBackupScheduler.setEnabled(enabled) }
            .onSuccess {
                updateState { copy(autoBackupEnabled = enabled, action = if (enabled) "已启用 Wi-Fi 自动备份" else "已关闭自动备份") }
            }
            .onFailure { error -> updateState { copy(error = "设置自动备份失败：${error.message}") } }
    }

    private suspend fun captureSnapshot(source: SourceSelection, upload: Boolean): CaptureOutcome =
        StorageOperationLock.withLock { captureSnapshotLocked(source, upload) }

    private suspend fun captureSnapshotLocked(source: SourceSelection, upload: Boolean): CaptureOutcome {
        val report = importSelection(source).getOrThrow()
        val sourceId = SnapshotNaming.sourceId(source.idSeed)
        val saveType = GameSaveTypes.forSource(source)
        val config = gameSaveStore.load()
        val previous = snapshotRepository.list().firstOrNull {
            it.sourceId == sourceId && it.manifestHash == report.manifestHash && snapshotRepository.fileFor(it).isFile
        }
        if (previous != null) {
            var uploadError: String? = null
            if (upload && config.enabled && config.serviceUrl.isNotBlank() && config.syncKey.isNotBlank() && !previous.uploaded) {
                val recordType = GameSaveTypes.fromId(previous.saveType)
                val uploadResult = gameSaveClient(config).upload(snapshotRepository.fileFor(previous), recordType, previous.remoteName)
                if (uploadResult.isSuccess) {
                    snapshotRepository.markUploaded(previous.id)
                } else {
                    uploadError = "云端上传失败：${uploadResult.exceptionOrNull()?.message ?: "未知错误"}"
                }
            }
            return CaptureOutcome(
                report,
                snapshotRepository.list().first { it.id == previous.id },
                uploadError,
            )
        }

        val timestamp = System.currentTimeMillis()
        val remoteName = SnapshotNaming.remoteName(sourceId, timestamp)
        val archive = File(app.filesDir, "$SNAPSHOT_DIRECTORY/$remoteName")
        val build = snapshotEngine.createSnapshot(storage.workspaceDirectory(), archive).getOrThrow()
        val record = SnapshotRecord(
            id = "snap_$timestamp",
            sourceId = sourceId,
            remoteName = remoteName,
            localPath = remoteName,
            createdAtMillis = timestamp,
            sizeBytes = build.archive.length(),
            sha256 = build.archiveSha256,
            manifestHash = report.manifestHash,
            saveType = saveType.id,
            cloudGameId = sourceId,
        )
        snapshotRepository.add(record)

        var uploadError: String? = null
        if (upload && config.enabled && config.serviceUrl.isNotBlank() && config.syncKey.isNotBlank()) {
            val uploadResult = gameSaveClient(config).upload(build.archive, saveType, remoteName)
            if (uploadResult.isSuccess) {
                snapshotRepository.markUploaded(record.id)
            } else {
                uploadError = "云端上传失败：${uploadResult.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
        return CaptureOutcome(report, snapshotRepository.list().first { it.id == record.id }, uploadError)
    }

    private suspend fun importSelection(source: SourceSelection): Result<CopyReport> = when (source) {
        is SourceSelection.Saf -> storage.importTree(source.uri)
        is SourceSelection.Root -> rootStorage.importPath(source.path)
    }

    private suspend fun restoreSelection(source: SourceSelection): Result<CopyReport> = when (source) {
        is SourceSelection.Saf -> storage.restoreWorkspace(source.uri)
        is SourceSelection.Root -> rootStorage.restoreWorkspace(source.path)
    }

    private suspend fun restoreRequest(request: RestoreRequest, source: SourceSelection): CopyReport =
        StorageOperationLock.withLock { restoreRequestLocked(request, source) }

    private suspend fun restoreRequestLocked(request: RestoreRequest, source: SourceSelection): CopyReport {
        val sourceId = SnapshotNaming.sourceId(source.idSeed)
        val archive = request.local?.let { snapshotRepository.fileFor(it) }
            ?: request.remote?.let(::remoteCacheFile)
            ?: error("没有可恢复的快照")
        if (request.remote != null && !archive.isFile) {
            val config = gameSaveStore.load()
            gameSaveClient(config).download(request.remote, archive).getOrThrow()
        }
        require(archive.isFile) { "快照文件不存在" }

        val workspace = storage.workspaceDirectory()
        if (workspace.isDirectory) {
            val timestamp = System.currentTimeMillis()
            val safetyName = SnapshotNaming.remoteName(sourceId, timestamp)
            val safetyArchive = File(app.filesDir, "$SNAPSHOT_DIRECTORY/$safetyName")
            val safetyBuild = snapshotEngine.createSnapshot(workspace, safetyArchive).getOrThrow()
            snapshotRepository.add(
                SnapshotRecord(
                    id = "snap_$timestamp",
                    sourceId = sourceId,
                    remoteName = safetyName,
                    localPath = safetyName,
                    createdAtMillis = timestamp,
                    sizeBytes = safetyBuild.archive.length(),
                    sha256 = safetyBuild.archiveSha256,
                    manifestHash = "safety",
                    comment = "恢复前安全副本",
                ),
            )
        }
        snapshotEngine.restoreSnapshot(archive, workspace).getOrThrow()
        return restoreSelection(source).getOrThrow()
    }

    private fun runSourceOperation(action: String, operation: suspend () -> Result<CopyReport>) {
        if (requiredSource() == null) return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = action, error = null)
            StorageOperationLock.withLock { operation() }
                .onSuccess { report ->
                    state.value = state.value.copy(
                        busy = false,
                        action = "完成：${report.fileCount} 个文件，${report.byteCount} 字节",
                        report = report,
                    )
                }
                .onFailure { error ->
                    state.value = state.value.copy(busy = false, action = null, error = error.message ?: "操作失败")
                }
        }
    }

    private fun requiredSource(): SourceSelection? {
        return when (state.value.storageMode) {
            StorageMode.SAF -> {
                val uri = state.value.selectedUri
                if (uri == null || !storage.hasReadPermission(uri)) {
                    updateState { copy(error = "请先添加并授权一个可读取目录") }
                    null
                } else {
                    SourceSelection.Saf(uri)
                }
            }

            StorageMode.ROOT -> {
                val result = runCatching { RootPathPolicy.canonicalize(state.value.rootPath) }
                result.fold(
                    onSuccess = { SourceSelection.Root(it) },
                    onFailure = {
                        updateState { copy(error = "请先填写有效的 Root 绝对路径：${it.message}") }
                        null
                    },
                )
            }
        }
    }

    private fun canWriteTo(source: SourceSelection): Boolean = when (source) {
        is SourceSelection.Saf -> storage.hasWritePermission(source.uri)
        is SourceSelection.Root -> true
    }

    private fun gameSaveConfigFromState() = GameSaveConfig(
        enabled = state.value.gameSaveEnabled,
        serviceUrl = state.value.gameSaveUrl,
        syncKey = state.value.gameSaveSyncKey,
    )

    private fun gameSaveClient(config: GameSaveConfig) =
        GameSaveClient(config, gameSaveDevice.identity())

    private fun remoteCacheFile(remote: RemoteSnapshot): File = File(
        app.filesDir,
        "$SNAPSHOT_DIRECTORY/remote-${remote.type}-${Hashing.sha256(remote.name).take(16)}.zip",
    )

    private fun currentSnapshots(): List<SnapshotRecord> =
        snapshotRepository.list()
            .filter { record -> runCatching { snapshotRepository.fileFor(record).isFile }.getOrDefault(false) }
            .take(MAX_HISTORY)

    private fun mergeCloudUpdates(
        existing: List<GameSaveCloudUpdate>,
        incoming: List<GameSaveCloudUpdate>,
    ): List<GameSaveCloudUpdate> = (existing + incoming)
        .associateBy { update ->
            "${update.entry.libraryKey}\u0000${update.remote?.name.orEmpty()}\u0000${update.action}"
        }
        .values
        .sortedWith(
            compareBy<GameSaveCloudUpdate> { it.entry.game.presentation.primaryName }
                .thenBy { it.entry.sourceLabel },
        )

    private fun buildSourceOptions(
        authorizedTrees: List<AuthorizedSafTree>,
        rootPath: String,
    ): List<SaveSourceOption> = SaveSourceOptions.build(
        authorizedTrees = authorizedTrees,
        rootPath = rootPath,
        disabledSourceIds = selectionStore.disabledSourceIds(),
        explicitlyEnabledSourceIds = selectionStore.explicitlyEnabledSourceIds(),
        rootPresets = RootSourcePresets.all +
            installedGameCatalog.rootPresets() +
            installedGameCatalog.candidateRootPresets(),
    )

    private fun updateState(update: SourceUiState.() -> SourceUiState) {
        state.value = state.value.update()
    }

    private data class CaptureOutcome(
        val report: CopyReport,
        val record: SnapshotRecord,
        val uploadError: String?,
    )

    private data class GameCaptureOutcome(
        val record: SnapshotRecord,
        val fileCount: Int,
        val uploadError: String?,
        val syncNotice: String? = null,
        val cloudUpdate: GameSaveCloudUpdate? = null,
    )

    private data class CloudCheckResult(
        val remoteSnapshots: List<RemoteSnapshot>,
        val updates: List<GameSaveCloudUpdate>,
        val failures: List<String>,
    )

    private data class RestoreOutcome(
        val message: String,
        val report: CopyReport?,
    )

    private companion object {
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val GAME_WORKSPACE_DIRECTORY = "game-workspace"
        const val MAX_HISTORY = 40
    }
}
