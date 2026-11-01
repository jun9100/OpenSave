package com.jun9100.opensave

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.jun9100.opensave.batch.BatchCenter
import com.jun9100.opensave.batch.BatchEligibility
import com.jun9100.opensave.batch.BatchJobs
import com.jun9100.opensave.batch.BatchOperation
import com.jun9100.opensave.batch.BatchProgress
import com.jun9100.opensave.batch.BatchReport
import com.jun9100.opensave.batch.BatchReportStore
import com.jun9100.opensave.batch.BatchRunner
import com.jun9100.opensave.batch.BatchWorker
import com.jun9100.opensave.ops.GameCaptureOutcome
import com.jun9100.opensave.ops.GameOperations
import com.jun9100.opensave.ops.KeepLocal
import com.jun9100.opensave.ops.ready
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/** Pages of the shell; the top-level ones are on the rail and the bottom bar, in this order. */
enum class OpenSavePage(val title: String, val topLevel: Boolean = true) {
    LIBRARY("游戏库"),
    SOURCES("来源"),
    SETTINGS("设置"),

    /** Secondary page "同步与任务": opened from the library banner, the Y panel or the settings. */
    SYNC("同步与任务", topLevel = false),
    ;

    companion object {
        val topLevelPages: List<OpenSavePage> = entries.filter(OpenSavePage::topLevel)

        /** A page name saved by this or an older build; "备份与设置" (MANAGEMENT) became 设置. */
        fun fromSavedName(name: String?): OpenSavePage? = when (name) {
            null -> null
            LEGACY_MANAGEMENT -> SETTINGS
            else -> entries.firstOrNull { it.name == name }
        }

        private const val LEGACY_MANAGEMENT = "MANAGEMENT"
    }
}

data class RestoreRequest(
    val local: SnapshotRecord? = null,
    val remote: RemoteSnapshot? = null,
    val remoteHead: GameSaveRemoteHead? = null,
    val nativeHead: GameSaveNativeCandidate? = null,
    val game: DetectedGame? = null,
    val source: SourceSelection? = null,
    /** The page whose control asked for the restore; its result is shown there. */
    val page: OpenSavePage = OpenSavePage.LIBRARY,
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

/**
 * "保留本机并发布" waiting for its confirmation: [expectedHead] is the cloud's
 * current version found by the fresh check just before, the only head the
 * publish may replace.
 */
data class KeepLocalRequest(
    val entry: SourceGameEntry,
    val expectedHead: String,
    val deviceName: String,
    val reason: String,
)

/** A game-save config whose connection test failed, and why. */
data class GameSaveTestFailure(
    val config: GameSaveConfig,
    val message: String,
)

/** A destructive batch waiting for its one confirmation (plan §5.7); only "安全恢复云端较新" asks. */
data class BatchPlan(
    val operation: BatchOperation,
    val keys: List<String>,
    val names: List<String>,
    /** Games left out because their cloud version needs a decision. */
    val conflictNames: List<String>,
    val page: OpenSavePage,
)

data class SourceUiState(
    val storageMode: StorageMode = StorageMode.SAF,
    val selectedUri: Uri? = null,
    val hasPersistedPermission: Boolean = false,
    val authorizedTrees: List<AuthorizedSafTree> = emptyList(),
    val rootPath: String = "",
    val rootAvailable: Boolean = false,
    val rootChecked: Boolean = false,
    val busy: Boolean = false,
    /** What the running operation is doing; shown on every page while [busy]. */
    val progress: String? = null,
    /** Results and errors, each kept on the page whose control started the operation. */
    val messages: PageMessages = PageMessages(),
    val report: CopyReport? = null,
    val scan: SaveScanReport? = null,
    val sourceScans: List<SourceScanResult> = emptyList(),
    val sourceOptions: List<SaveSourceOption> = emptyList(),
    val scanRevision: Long = 0L,
    val snapshots: List<SnapshotRecord> = emptyList(),
    val remoteSnapshots: List<RemoteSnapshot> = emptyList(),
    val cloudUpdates: List<GameSaveCloudUpdate> = emptyList(),
    /** Verdicts of earlier sessions' cloud checks; see [GameStatusResolver.effectiveCloudActions]. */
    val rememberedCloudVerdicts: Map<String, CloudVerdict> = emptyMap(),
    val activeSourceId: String? = null,
    val page: OpenSavePage = OpenSavePage.LIBRARY,
    val gameSaveEnabled: Boolean = false,
    val gameSaveUrl: String = "",
    val gameSaveSyncKey: String = "",
    val gameSaveSaved: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    val pendingRestore: RestoreRequest? = null,
    val pendingGameBackup: SourceGameEntry? = null,
    val pendingWriteBack: WorkspaceWriteBackRequest? = null,
    /** Library batch selection by libraryKey; empty until the user chooses. */
    val librarySelection: Set<String> = LibrarySelection.initial,
    /** The game the detail pane or page shows, by libraryKey. */
    val selectedGameKey: String? = null,
    /** Portrait only: the full-screen game detail is on top of the list. */
    val gameDetailOpen: Boolean = false,
    /** Explicit selection mode: rows toggle on A or tap and the toolbar shows the selection actions. */
    val selectionMode: Boolean = false,
    /** Save type id of the library filter chip, or [LibraryView.ALL_TYPES]. */
    val libraryTypeFilter: String = LibraryView.ALL_TYPES,
    val libraryStatusFilter: LibraryStatusFilter = LibraryStatusFilter.ALL,
    /** Encoded [SaveSignature] of each game's locations now, by libraryKey; missing while unknown. */
    val saveSignatures: Map<String, String> = emptyMap(),
    val sourceDetailsExpanded: Boolean = false,
    /** The running batch (local or in [BatchWorker]); null while none runs. */
    val batch: BatchProgress? = null,
    /** The page whose control started the running or last batch; its summary is shown there. */
    val batchPage: OpenSavePage = OpenSavePage.LIBRARY,
    val lastBatchReport: BatchReport? = null,
    /** The last report finished since the user last looked; the status strip offers it. */
    val batchReportUnseen: Boolean = false,
    val batchReportOpen: Boolean = false,
    val pendingBatchPlan: BatchPlan? = null,
    /** libraryKeys whose automatic backup is switched off. */
    val autoBackupExcluded: Set<String> = emptySet(),
    /** Who holds the storage lock, e.g. [StorageOperationLock.AUTO_BACKUP]. */
    val lockOwner: String? = null,
    /** The top-level page "同步与任务" was opened from; B goes back there. */
    val syncReturnPage: OpenSavePage = OpenSavePage.LIBRARY,
    /** Games put off with "稍后" in "同步与任务"; this session only. */
    val syncDismissed: Set<String> = emptySet(),
    /** The kept batch reports, newest first ([BatchReportStore]); "同步与任务" lists them. */
    val batchReports: List<BatchReport> = emptyList(),
    /** The report the sheet shows; null shows [lastBatchReport]. */
    val viewedBatchReport: BatchReport? = null,
    val pendingKeepLocal: KeepLocalRequest? = null,
    /** Source toggles of the 来源 page not applied yet. */
    val sourceDraft: SourceDraft = SourceDraft(),
    /** Back on the 来源 page with [sourceDraft] pending asks first. */
    val confirmDiscardSourceDraft: Boolean = false,
    /** The game-save editor is open, starting from this config; null while closed. */
    val gameSaveEditor: GameSaveConfig? = null,
    /** "测试并保存" failed for this config; the user may still save it. */
    val gameSaveTestFailure: GameSaveTestFailure? = null,
    /** A config read from the clipboard, waiting for its confirmation. */
    val pendingConfigImport: ImportedGameSaveConfig? = null,
    /** Hide the status and gesture bars in landscape (plan Q2: off by default). */
    val immersiveLandscape: Boolean = false,
) {
    /** The rail and bottom bar item to highlight, and where L1/R1 cycle from. */
    val navigationPage: OpenSavePage get() = if (page.topLevel) page else syncReturnPage
}

class SourceViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application
    private val storage = SafStorage(application)
    private val rootStorage = RootStorage(application)
    private val selectionStore = SourceSelectionStore(application)
    private val installedGameCatalog = InstalledGameCatalog(application)
    private val snapshotEngine = SnapshotEngine()
    private val snapshotRepository = SnapshotRepository.forApp(application)
    private val gameSaveStore = GameSaveConfigStore(application)
    private val displayPreferences = DisplayPreferences(application)
    private val gameSaveDevice = GameSaveDeviceStore(application)
    private val autoBackupScheduler = AutoBackupScheduler(application)
    private val autoBackupExclusions = AutoBackupExclusions(application)
    private val operations = GameOperations(application, storage, rootStorage)
    private val reportStore = BatchReportStore.forApp(application)
    private val cloudVerdictStore = CloudVerdictStore(application)
    private var appliedFindingsId: String? = null
    private val signatureReader = SaveSignatureReader(storage, rootStorage)
    private var signatureJob: Job? = null
    private var signaturesReadAtMillis = 0L
    private val state = MutableStateFlow(restoreUiState(SourceUiState()))
    val uiState: StateFlow<SourceUiState> = state.asStateFlow()

    init {
        autoBackupScheduler.ensureScheduled()
        // Navigation, filter and selections survive rotation through the ViewModel
        // and process death through SavedStateHandle (plan S8).
        viewModelScope.launch { state.collect(::saveUiState) }
        // Before anything recreates the workspace, bring back one an interrupted
        // write-back left set aside.
        viewModelScope.launch(Dispatchers.IO) {
            StorageOperationLock.withLock {
                runCatching { WorkspaceWriteBackPolicy.recoverHeldWorkspace(storage.workspaceDirectory()) }
            }
        }
        observeBatches()
        refreshPersistedSource()
    }

    /**
     * Batches run outside this ViewModel's operations ([BatchCenter]): their
     * progress drives busy and the status strip, their cloud verdicts update
     * the statuses, and a finished one refreshes the library.
     */
    private fun observeBatches() {
        updateState {
            copy(
                lastBatchReport = runCatching { reportStore.latest() }.getOrNull(),
                batchReports = runCatching { reportStore.list() }.getOrDefault(emptyList()),
                autoBackupExcluded = autoBackupExclusions.excluded(),
                rememberedCloudVerdicts = runCatching { cloudVerdictStore.load() }.getOrDefault(emptyMap()),
            )
        }
        // Remember what cloud checks concluded so conflicts survive a restart.
        // Only a change of the live verdicts is written: a later backup or
        // restore makes the remembered one stale through its record id instead.
        viewModelScope.launch {
            state.map { it.cloudUpdates }.distinctUntilChanged().drop(1).collect { live ->
                val verdicts = GameStatusResolver.verdictsToRemember(live, state.value.snapshots)
                if (verdicts.isEmpty()) return@collect
                withContext(Dispatchers.IO) { runCatching { cloudVerdictStore.update(verdicts = verdicts) } }
                updateState { copy(rememberedCloudVerdicts = rememberedCloudVerdicts + verdicts) }
            }
        }
        viewModelScope.launch {
            BatchCenter.running.collect { progress ->
                updateState {
                    val ended = progress == null && batch != null
                    copy(
                        batch = progress,
                        busy = progress != null || (busy && !ended),
                        progress = progress?.text ?: this.progress.takeUnless { ended },
                    )
                }
            }
        }
        viewModelScope.launch {
            BatchCenter.cloudFindings.collect { findings ->
                if (findings == null || findings.batchId == appliedFindingsId) return@collect
                appliedFindingsId = findings.batchId
                // A checked game's old verdict is replaced, or dropped when it is now up to date.
                withContext(Dispatchers.IO) { runCatching { cloudVerdictStore.update(removedKeys = findings.checkedKeys) } }
                updateState {
                    copy(
                        rememberedCloudVerdicts = rememberedCloudVerdicts - findings.checkedKeys,
                        cloudUpdates = mergeCloudUpdates(
                            cloudUpdates.filterNot { it.entry.libraryKey in findings.checkedKeys },
                            findings.updates,
                        ),
                        remoteSnapshots = findings.remoteSnapshots ?: remoteSnapshots,
                    )
                }
            }
        }
        viewModelScope.launch {
            BatchCenter.lastFinished.collect { report ->
                if (report == null || report.id == state.value.lastBatchReport?.id) return@collect
                updateState {
                    copy(
                        lastBatchReport = report,
                        batchReports = (listOf(report) + batchReports.filterNot { it.id == report.id }).take(BatchReportStore.MAX_REPORTS),
                        batchReportUnseen = true,
                        snapshots = currentSnapshots(),
                        messages = messages.with(batchPage, action = report.summary),
                    )
                }
                // Backups and restores changed what the signatures last saw.
                readSaveSignatures(GameLibrary.sourceGames(state.value.sourceScans))
            }
        }
        viewModelScope.launch {
            StorageOperationLock.owner.collect { owner -> updateState { copy(lockOwner = owner) } }
        }
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
                    messages = state.value.messages.with(OpenSavePage.SOURCES, action = "已添加系统授权目录"),
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
                    messages = state.value.messages.withError(
                        OpenSavePage.SOURCES,
                        "无法保存目录权限：${error.message ?: "provider refused persistent access"}",
                    ),
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
                    messages = state.value.messages.with(OpenSavePage.SOURCES, action = "已移除系统授权目录"),
                )
                scanSource()
            }
            .onFailure { error ->
                updateState { copy(messages = messages.withError(OpenSavePage.SOURCES, "移除系统授权目录失败：${error.message}")) }
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
            immersiveLandscape = displayPreferences.immersiveLandscape(),
            scan = persistedScan,
            sourceOptions = sourceOptions,
            messages = if (fullDiscoveryEnabled) {
                previous.messages.with(previous.page, action = "已启用全设备自动存档来源")
            } else {
                previous.messages.withError(previous.page, null)
            },
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

    /** Opening "同步与任务" remembers the top-level page it came from. */
    fun showPage(page: OpenSavePage) = updateState {
        if (page.topLevel) copy(page = page) else copy(page = page, syncReturnPage = navigationPage)
    }

    fun openSyncTasks() = showPage(OpenSavePage.SYNC)

    /** "稍后": hides the game in "同步与任务" and the library banner until the app restarts. */
    fun dismissSyncItem(libraryKey: String) = updateState { copy(syncDismissed = syncDismissed + libraryKey) }

    /** Shows [libraryKey] in the detail pane; in portrait this opens the full-screen detail. */
    fun openGame(libraryKey: String) = updateState { copy(selectedGameKey = libraryKey, gameDetailOpen = true) }

    /** Selects the game for the landscape detail pane without opening the portrait page. */
    fun selectGame(libraryKey: String) = updateState { copy(selectedGameKey = libraryKey) }

    fun closeGameDetail() = updateState { copy(gameDetailOpen = false) }

    fun setLibraryTypeFilter(typeId: String) = updateState { copy(libraryTypeFilter = typeId) }

    fun setLibraryStatusFilter(filter: LibraryStatusFilter) = updateState { copy(libraryStatusFilter = filter) }

    fun enterSelectionMode() = updateState { copy(selectionMode = true) }

    /** Leaving selection mode drops the selection, so nothing hidden stays selected for "备份所选". */
    fun exitSelectionMode() = updateState { copy(selectionMode = false, librarySelection = LibrarySelection.initial) }

    fun selectAllLibraryGames(keys: Collection<String>) =
        updateState { copy(selectionMode = true, librarySelection = LibrarySelection.selectAll(librarySelection, keys)) }

    fun clearLibrarySelection() = updateState { copy(librarySelection = LibrarySelection.initial) }

    /** Start with nothing to back up says so instead of silently doing nothing. */
    fun reportNothingToBackup(selectionMode: Boolean) = updateState {
        copy(
            messages = messages.with(
                OpenSavePage.LIBRARY,
                action = if (selectionMode) "未选择游戏：按 A 或 Select 勾选后再按 Start" else "没有需要备份的变更",
            ),
        )
    }

    /**
     * Re-reads the cheap change signatures of the scanned games, e.g. when the
     * app comes back from an emulator. Skipped while an operation runs and
     * when the last read is only seconds old.
     */
    fun refreshSaveSignatures() {
        val current = state.value
        if (current.busy || current.sourceScans.isEmpty()) return
        if (System.currentTimeMillis() - signaturesReadAtMillis < SIGNATURE_REFRESH_MIN_MILLIS) return
        readSaveSignatures(GameLibrary.sourceGames(current.sourceScans))
    }

    private fun readSaveSignatures(entries: List<SourceGameEntry>) {
        signatureJob?.cancel()
        signaturesReadAtMillis = System.currentTimeMillis()
        val revision = state.value.scanRevision
        signatureJob = viewModelScope.launch {
            val signatures = withContext(Dispatchers.IO) {
                runCatching { signatureReader.read(GameLibrary.batchBackupEligibleEntries(entries)) }.getOrNull()
            } ?: return@launch
            // A newer scan replaced the rows; its own read follows.
            if (state.value.scanRevision != revision) return@launch
            updateState { copy(saveSignatures = signatures) }
        }
    }

    fun setSourceDetailsExpanded(expanded: Boolean) = updateState { copy(sourceDetailsExpanded = expanded) }

    /** Handles the back key; false means the system should handle it. */
    fun navigateBack(landscape: Boolean): Boolean {
        val current = state.value
        return when (LibraryView.backTarget(current.page, current.gameDetailOpen, landscape)) {
            BackTarget.CLOSE_DETAIL -> {
                closeGameDetail()
                true
            }
            BackTarget.LIBRARY -> {
                if (current.page == OpenSavePage.SOURCES && current.sourceDraft.pendingCount(current.sourceOptions) > 0) {
                    updateState { copy(confirmDiscardSourceDraft = true) }
                } else {
                    showPage(OpenSavePage.LIBRARY)
                }
                true
            }
            BackTarget.PARENT -> {
                showPage(current.syncReturnPage)
                true
            }
            BackTarget.SYSTEM -> false
        }
    }

    private fun restoreUiState(initial: SourceUiState): SourceUiState = initial.copy(
        page = OpenSavePage.fromSavedName(savedState.get<String>(KEY_PAGE)) ?: initial.page,
        syncReturnPage = OpenSavePage.fromSavedName(savedState.get<String>(KEY_SYNC_RETURN))
            ?.takeIf(OpenSavePage::topLevel)
            ?: initial.syncReturnPage,
        selectedGameKey = savedState.get<String>(KEY_SELECTED_GAME),
        gameDetailOpen = savedState.get<Boolean>(KEY_DETAIL_OPEN) ?: false,
        libraryTypeFilter = savedState.get<String>(KEY_TYPE_FILTER) ?: initial.libraryTypeFilter,
        libraryStatusFilter = savedState.get<String>(KEY_STATUS_FILTER)
            ?.let { name -> LibraryStatusFilter.entries.firstOrNull { it.name == name } }
            ?: initial.libraryStatusFilter,
        selectionMode = savedState.get<Boolean>(KEY_SELECTION_MODE) ?: false,
        sourceDetailsExpanded = savedState.get<Boolean>(KEY_SOURCE_DETAILS) ?: false,
        librarySelection = savedState.get<ArrayList<String>>(KEY_SELECTION)?.toSet() ?: initial.librarySelection,
    )

    private fun saveUiState(current: SourceUiState) {
        savedState[KEY_PAGE] = current.page.name
        savedState[KEY_SYNC_RETURN] = current.syncReturnPage.name
        savedState[KEY_SELECTED_GAME] = current.selectedGameKey
        savedState[KEY_DETAIL_OPEN] = current.gameDetailOpen
        savedState[KEY_TYPE_FILTER] = current.libraryTypeFilter
        savedState[KEY_STATUS_FILTER] = current.libraryStatusFilter.name
        savedState[KEY_SELECTION_MODE] = current.selectionMode
        savedState[KEY_SOURCE_DETAILS] = current.sourceDetailsExpanded
        if (savedState.get<ArrayList<String>>(KEY_SELECTION)?.toSet() != current.librarySelection) {
            savedState[KEY_SELECTION] = ArrayList(current.librarySelection)
        }
    }

    /** The target of the whole-source snapshot and write-back in 设置 → 高级 (plan Q13). */
    fun selectStorageMode(mode: StorageMode) {
        if (state.value.busy) return
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
                messages = messages.with(
                    OpenSavePage.SETTINGS,
                    action = if (mode == StorageMode.ROOT) "整来源快照改用 Root 路径" else "整来源快照改用系统授权目录",
                ),
            )
        }
        if (mode == StorageMode.ROOT) refreshRootAccess()
    }

    /**
     * "添加 Root 路径" on the 来源 page: stores [path] as the custom Root source
     * (it replaces an earlier one), switches it on and rescans. Only offered
     * while su grants uid 0.
     */
    fun addRootPath(path: String) {
        if (state.value.busy) return
        rootStorage.persistRootPath(path)
            .onSuccess { canonical ->
                selectionStore.setSourcesEnabled(listOf(SourceSelection.Root(canonical).idSeed), enabled = true)
                val current = state.value
                state.value = current.copy(
                    rootPath = canonical,
                    activeSourceId = if (current.storageMode == StorageMode.ROOT) {
                        SnapshotNaming.sourceId(SourceSelection.Root(canonical).idSeed)
                    } else {
                        current.activeSourceId
                    },
                    sourceOptions = buildSourceOptions(current.authorizedTrees, canonical),
                    messages = current.messages.with(OpenSavePage.SOURCES, action = "已添加 Root 路径 $canonical"),
                )
                scanSource()
            }
            .onFailure { error ->
                updateState { copy(messages = messages.withError(OpenSavePage.SOURCES, "Root 路径无效：${error.message}")) }
            }
    }

    private fun refreshRootAccess() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = rootStorage.checkRootAccess()
            val rootAvailable = result.isSuccess
            state.value = state.value.copy(
                rootAvailable = rootAvailable,
                rootChecked = true,
                messages = if (!rootAvailable && state.value.storageMode == StorageMode.ROOT) {
                    state.value.messages.withError(
                        OpenSavePage.SETTINGS,
                        "Root 不可用：${result.exceptionOrNull()?.message ?: "su 未返回 uid 0"}",
                    )
                } else {
                    state.value.messages
                },
            )
        }
    }

    /** Opens the game-save editor on the saved config; the draft lives in the dialog, so a refresh never overwrites it. */
    fun openGameSaveEditor() = updateState {
        copy(gameSaveEditor = savedGameSaveConfig(), gameSaveTestFailure = null)
    }

    fun closeGameSaveEditor() = updateState { copy(gameSaveEditor = null) }

    /**
     * "测试并保存": the address and key are saved only when the connection test
     * passes. A failure closes the editor and offers "仍然保存" or going back
     * to the draft ([editFailedGameSave]).
     */
    fun testAndSaveGameSave(serviceUrl: String, syncKey: String) {
        if (state.value.busy) return
        val saved = savedGameSaveConfig()
        val url = serviceUrl.trim()
        val key = syncKey.trim()
        val normalized = runCatching { GameSaveClient.normalizeServiceUrl(url) }
        val problem = when {
            url.isBlank() || key.isBlank() -> "请填写 game-save 服务地址和同步密钥"
            normalized.isFailure -> normalized.exceptionOrNull()?.message ?: "game-save 地址无效"
            else -> null
        }
        if (problem != null) {
            updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, problem)) }
            return
        }
        // Setting the cloud up for the first time also switches the upload on.
        val config = GameSaveConfig(
            enabled = saved.enabled || saved.serviceUrl.isBlank() || saved.syncKey.isBlank(),
            serviceUrl = normalized.getOrThrow(),
            syncKey = key,
        )
        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.SETTINGS, "正在测试 game-save 连接")
            val result = withContext(Dispatchers.IO) {
                runCatching { gameSaveClient(config).testConnection().getOrThrow() }
            }
            result.onSuccess {
                storeGameSave(config, "game-save 连接成功，配置已保存")
            }.onFailure { error ->
                state.value = state.value.finished(OpenSavePage.SETTINGS).copy(
                    gameSaveEditor = null,
                    gameSaveTestFailure = GameSaveTestFailure(config, error.message ?: "连接失败"),
                )
            }
        }
    }

    /** "仍然保存" after a failed test. */
    fun saveGameSaveAnyway() {
        val failure = state.value.gameSaveTestFailure ?: return
        if (state.value.busy) return
        updateState { copy(gameSaveTestFailure = null) }
        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.SETTINGS, "正在加密保存 game-save 配置")
            storeGameSave(failure.config, "game-save 配置已保存（连接测试未通过）")
        }
    }

    /** "返回修改": the editor again, on the draft that failed. */
    fun editFailedGameSave() = updateState {
        copy(gameSaveEditor = gameSaveTestFailure?.config ?: savedGameSaveConfig(), gameSaveTestFailure = null)
    }

    fun dismissGameSaveTestFailure() = updateState { copy(gameSaveTestFailure = null) }

    /** "从剪贴板导入配置": a valid link waits for the confirmation; anything else is refused with the reason. */
    fun importGameSaveConfig(clipboardText: String?) {
        GameSaveConfigLink.parse(clipboardText)
            .onSuccess { config -> updateState { copy(pendingConfigImport = config, gameSaveTestFailure = null) } }
            .onFailure { error ->
                updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, "无法导入配置：${error.message}")) }
            }
    }

    fun cancelConfigImport() = updateState { copy(pendingConfigImport = null) }

    /** The confirmed import runs "测试并保存" like the editor. */
    fun confirmConfigImport() {
        val config = state.value.pendingConfigImport ?: return
        updateState { copy(pendingConfigImport = null) }
        testAndSaveGameSave(config.serviceUrl, config.syncKey)
    }

    /** "新快照自动上传" (自动化): needs a saved game-save address and key. */
    fun setAutoUploadEnabled(enabled: Boolean) {
        if (state.value.busy) return
        val saved = savedGameSaveConfig()
        if (enabled && (saved.serviceUrl.isBlank() || saved.syncKey.isBlank())) {
            updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, "请先在“云端”配置并保存 game-save")) }
            return
        }
        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.SETTINGS, "正在保存自动上传设置")
            storeGameSave(saved.copy(enabled = enabled), if (enabled) "新快照会自动上传" else "已关闭自动上传")
        }
    }

    private suspend fun storeGameSave(config: GameSaveConfig, success: String) {
        val result = withContext(Dispatchers.IO) { runCatching { gameSaveStore.save(config) } }
        result.onSuccess {
            state.value = state.value.finished(OpenSavePage.SETTINGS, action = success).copy(
                gameSaveEnabled = config.enabled,
                gameSaveUrl = GameSaveClient.normalizeServiceUrl(config.serviceUrl),
                gameSaveSyncKey = config.syncKey,
                gameSaveSaved = config.serviceUrl.isNotBlank() && config.syncKey.isNotBlank(),
                gameSaveEditor = null,
            )
        }.onFailure { error ->
            state.value = state.value.finished(OpenSavePage.SETTINGS, error = "保存 game-save 配置失败：${error.message}")
        }
    }

    fun setImmersiveLandscape(enabled: Boolean) {
        displayPreferences.setImmersiveLandscape(enabled)
        updateState { copy(immersiveLandscape = enabled) }
    }

    fun importSource() {
        val source = requiredSource() ?: return
        runSourceOperation("正在复制存档到私有工作区") {
            importSelection(source)
        }
    }

    fun scanSource() {
        if (state.value.busy) return
        // Scans start from either page; the summary stays on the one that asked.
        val page = state.value.page
        viewModelScope.launch {
            state.value = state.value.started(page, "正在扫描已选存档来源")
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
            val eligibleKeys = GameLibrary.batchBackupEligibleEntries(games).map { it.libraryKey }
            state.value = state.value.finished(
                page,
                action = buildString {
                    append("扫描完成：${games.size} 个游戏条目，$availableSourceCount 个来源可读")
                    if (skippedSourceCount > 0) append("，跳过 $skippedSourceCount 个")
                },
            ).copy(
                rootAvailable = rootAvailable,
                rootChecked = true,
                sourceScans = scans,
                sourceOptions = options,
                scanRevision = state.value.scanRevision + 1,
                scan = activeScan,
                librarySelection = LibrarySelection.effective(state.value.librarySelection, eligibleKeys),
                // Signatures of games that are gone would never be read again.
                saveSignatures = state.value.saveSignatures.filterKeys { it in eligibleKeys },
            )
            readSaveSignatures(games)
        }
    }

    /** Stages source toggles of the 来源 page; nothing is stored until [applySourceDraft]. */
    fun updateSourceDraft(draft: SourceDraft) {
        if (state.value.busy) return
        updateState { copy(sourceDraft = draft) }
    }

    /** "应用并重新扫描": stores the staged toggles and rescans once. */
    fun applySourceDraft() {
        if (state.value.busy) return
        val current = state.value
        val pending = current.sourceDraft.pending(current.sourceOptions)
        if (pending.isEmpty()) {
            updateState { copy(sourceDraft = SourceDraft()) }
            return
        }
        pending.entries.groupBy({ it.value }, { it.key }).forEach { (enabled, ids) ->
            selectionStore.setSourcesEnabled(ids, enabled)
        }
        state.value = current.copy(
            sourceDraft = SourceDraft(),
            sourceOptions = buildSourceOptions(current.authorizedTrees, current.rootPath),
            messages = current.messages.with(OpenSavePage.SOURCES, action = "已应用 ${pending.size} 项来源改动"),
        )
        scanSource()
    }

    fun cancelDiscardSourceDraft() = updateState { copy(confirmDiscardSourceDraft = false) }

    /** "放弃改动" after back on the 来源 page: drops the draft and goes where back goes. */
    fun discardSourceDraftAndLeave() {
        updateState { copy(sourceDraft = SourceDraft(), confirmDiscardSourceDraft = false) }
        showPage(OpenSavePage.LIBRARY)
    }

    /** Selecting a game (long press, Select, the X menu, the detail pane) enters selection mode. */
    fun setLibrarySelected(libraryKey: String, selected: Boolean) = updateState {
        copy(
            librarySelection = LibrarySelection.set(librarySelection, libraryKey, selected),
            selectionMode = selectionMode || selected,
        )
    }

    /** Asks for confirmation before the private workspace replaces the saved source directory. */
    fun requestWorkspaceWriteBack() {
        if (state.value.busy) return
        val request = runCatching {
            WorkspaceWriteBackPolicy.requireWorkspace(storage.workspaceDirectory())
            persistedWriteTarget().getOrThrow()
        }.getOrElse { error ->
            updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, error.message ?: "无法写回工作区")) }
            return
        }
        updateState { copy(pendingWriteBack = request, messages = messages.withError(OpenSavePage.SETTINGS, null)) }
    }

    fun cancelWorkspaceWriteBack() = updateState { copy(pendingWriteBack = null) }

    fun confirmWorkspaceWriteBack() {
        val request = state.value.pendingWriteBack ?: return
        updateState { copy(pendingWriteBack = null) }
        if (state.value.busy) return
        // Re-resolve the target: the dialog must never write to a source other than the one it named.
        val target = persistedWriteTarget().getOrElse { error ->
            updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, error.message ?: "无法写回工作区")) }
            return
        }
        if (target.source.idSeed != request.source.idSeed) {
            updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, "来源目录已变更，请重新确认写回")) }
            return
        }
        runSourceOperation(
            action = "正在保存安全快照并把工作区写回来源目录",
            done = { report -> "已写回 ${request.label}：${report.fileCount} 个文件；写回前的内容已保存为安全快照" },
        ) {
            writeBackWorkspaceLocked(request.source)
        }
    }

    fun backupNow() {
        val source = requiredSource() ?: return
        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.SETTINGS, "正在检查并创建快照")
            val outcome = withContext(Dispatchers.IO) {
                runCatching { captureSnapshot(source, upload = true) }
            }
            outcome.onSuccess { captured ->
                val uploadSuffix = captured.uploadError?.let { "；$it" }.orEmpty()
                state.value = state.value.finished(
                    OpenSavePage.SETTINGS,
                    action = "快照已保存：${captured.record.remoteName}$uploadSuffix",
                    error = captured.uploadError,
                ).copy(
                    report = captured.report,
                    snapshots = currentSnapshots(),
                )
            }.onFailure { error ->
                state.value = state.value.finished(OpenSavePage.SETTINGS, error = "创建快照失败：${error.message}")
            }
        }
    }

    fun requestGameBackup(entry: SourceGameEntry) {
        if (state.value.busy) return
        val game = entry.game
        if (game.candidateCount > 0) {
            updateState { copy(pendingGameBackup = entry, messages = messages.withError(OpenSavePage.LIBRARY, null)) }
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

    fun requestBatchGameBackup(gameKeys: Set<String>) = requestBatch(BatchOperation.BACKUP, gameKeys)

    /**
     * Starts [operation] over the eligible games among [gameKeys], in library
     * order. "上传待传" takes the games with a pending upload and
     * "安全恢复云端较新" the safely newer ones, which first need the one
     * confirmation of [pendingBatchPlan]. Only one batch runs at a time.
     */
    fun requestBatch(
        operation: BatchOperation,
        gameKeys: Collection<String>,
        page: OpenSavePage = OpenSavePage.LIBRARY,
    ) {
        val current = state.value
        current.batch?.let { running ->
            updateState { copy(messages = messages.withError(page, "已有批量任务在运行：${running.operation.label}；请等待完成或取消")) }
            return
        }
        if (current.busy) return
        val requested = gameKeys.toSet()
        val entries = GameLibrary.batchBackupEligibleEntries(GameLibrary.sourceGames(current.sourceScans))
            .filter { it.libraryKey in requested }
        if (operation.usesNetwork && !operations.loadConfig().ready) {
            updateState { copy(messages = messages.withError(page, "请先在“设置”中启用并保存 game-save 配置")) }
            return
        }
        val scope = entries.map(SourceGameEntry::libraryKey)
        val statuses = libraryStatuses(current)
        val restorePlan = BatchEligibility.safeRestore(scope, statuses)
        val chosen = when (operation) {
            BatchOperation.BACKUP, BatchOperation.CHECK_CLOUD -> scope
            BatchOperation.UPLOAD_PENDING -> BatchEligibility.pendingUpload(scope, statuses)
            BatchOperation.RESTORE_CLOUD_NEWER -> restorePlan.restore
        }.toSet()
        val chosenEntries = entries.filter { it.libraryKey in chosen }
        if (chosenEntries.isEmpty()) {
            val reason = when (operation) {
                BatchOperation.BACKUP -> "请选择至少一款含有已识别存档位置的游戏"
                BatchOperation.UPLOAD_PENDING -> "没有待上传的版本"
                BatchOperation.CHECK_CLOUD -> "请先扫描含有已识别存档位置的来源"
                BatchOperation.RESTORE_CLOUD_NEWER -> if (restorePlan.conflicts.isNotEmpty()) {
                    "没有可安全恢复的游戏；${restorePlan.conflicts.size} 款冲突需逐个确认"
                } else {
                    "没有云端较新的游戏；请先检查云端更新"
                }
            }
            updateState { copy(messages = messages.withError(page, reason)) }
            return
        }
        if (operation == BatchOperation.RESTORE_CLOUD_NEWER) {
            val names = entries.associate { it.libraryKey to it.game.presentation.primaryName }
            updateState {
                copy(
                    pendingBatchPlan = BatchPlan(
                        operation = operation,
                        keys = chosenEntries.map(SourceGameEntry::libraryKey),
                        names = chosenEntries.map { it.game.presentation.primaryName },
                        conflictNames = restorePlan.conflicts.mapNotNull(names::get),
                        page = page,
                    ),
                    messages = messages.withError(page, null),
                )
            }
            return
        }
        startBatch(operation, chosenEntries, page)
    }

    fun confirmBatchPlan() {
        val plan = state.value.pendingBatchPlan ?: return
        updateState { copy(pendingBatchPlan = null) }
        val entries = GameLibrary.batchBackupEligibleEntries(GameLibrary.sourceGames(state.value.sourceScans))
            .filter { it.libraryKey in plan.keys.toSet() }
        if (entries.isEmpty()) {
            updateState { copy(messages = messages.withError(plan.page, "所选游戏已不在游戏库中；请重新扫描")) }
            return
        }
        startBatch(plan.operation, entries, plan.page)
    }

    fun cancelBatchPlan() = updateState { copy(pendingBatchPlan = null) }

    /** Asks the running batch to stop after the game it is on (plan S7). */
    fun cancelBatch() {
        BatchCenter.requestCancel()
    }

    fun openBatchReport() = updateState {
        copy(viewedBatchReport = null, batchReportOpen = lastBatchReport != null, batchReportUnseen = false)
    }

    /** Opens one of the kept reports listed in "同步与任务". */
    fun openStoredBatchReport(report: BatchReport) = updateState {
        copy(viewedBatchReport = report.takeUnless { it.id == lastBatchReport?.id }, batchReportOpen = true, batchReportUnseen = false)
    }

    fun closeBatchReport() = updateState { copy(batchReportOpen = false, batchReportUnseen = false, viewedBatchReport = null) }

    fun dismissBatchReport() = updateState { copy(batchReportUnseen = false) }

    /** "重试失败项": the same operation over the failed games of the shown report that are still in the library. */
    fun retryFailedBatch() {
        val current = state.value
        val report = current.viewedBatchReport ?: current.lastBatchReport ?: return
        val available = GameLibrary.batchBackupEligibleEntries(GameLibrary.sourceGames(current.sourceScans))
            .map(SourceGameEntry::libraryKey)
        val keys = BatchRunner.retryKeys(report, available)
        updateState { copy(batchReportOpen = false, batchReportUnseen = false, viewedBatchReport = null) }
        if (keys.isEmpty()) {
            updateState { copy(messages = messages.withError(current.batchPage, "没有可重试的失败项")) }
            return
        }
        requestBatch(report.operation, keys, current.batchPage)
    }

    /** Switches automatic backup of one game on or off (plan §5.4); stored on this device. */
    fun setGameAutoBackup(libraryKey: String, enabled: Boolean) {
        val excluded = autoBackupExclusions.setIncluded(listOf(libraryKey), enabled)
        updateState {
            copy(
                autoBackupExcluded = excluded,
                messages = messages.with(
                    OpenSavePage.LIBRARY,
                    action = if (enabled) "已开启这款游戏的自动备份" else "已关闭这款游戏的自动备份",
                ),
            )
        }
    }

    private fun startBatch(operation: BatchOperation, entries: List<SourceGameEntry>, page: OpenSavePage) {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            // A network batch WorkManager resumed after the process was killed
            // is not in BatchCenter yet; KEEP would drop this request silently.
            if (operation.usesNetwork && withContext(Dispatchers.IO) { BatchWorker.hasPendingWork(app) }) {
                updateState { copy(messages = messages.withError(page, "已有批量任务在运行；请等待完成")) }
                return@launch
            }
            if (!BatchCenter.tryBegin(id, operation, entries.size)) {
                updateState { copy(messages = messages.withError(page, "已有批量任务在运行；请等待完成或取消")) }
                return@launch
            }
            updateState { copy(batchPage = page, batchReportUnseen = false, messages = messages.with(page)) }
            if (operation.usesNetwork) {
                BatchCenter.putEntries(id, entries)
                runCatching { withContext(Dispatchers.IO) { BatchWorker.enqueue(app, id, operation, entries) } }
                    .onFailure { error ->
                        BatchCenter.finish(id, null)
                        updateState { copy(messages = messages.withError(page, "无法启动批量任务：${error.message}")) }
                    }
            } else {
                withContext(Dispatchers.IO) { BatchJobs.execute(app, id, operation, entries) }
            }
        }
    }

    private fun libraryStatuses(current: SourceUiState): Map<String, GameSyncStatus> = GameStatusResolver.resolveAll(
        entries = GameLibrary.sourceGames(current.sourceScans),
        snapshots = current.snapshots,
        signatures = current.saveSignatures,
        cloudActions = GameStatusResolver.effectiveCloudActions(current.cloudUpdates, current.rememberedCloudVerdicts, current.snapshots),
        cloudEnabled = current.gameSaveEnabled,
    )

    private fun createGameBackup(entry: SourceGameEntry, includeCandidates: Boolean) {
        val game = entry.game
        // A single-game backup is an explicit request, so it keeps Android app
        // data; games without such a location share the batch location set.
        val selectedSaves = GameLibrary.backupLocations(game, includeCandidates, includeAndroidApp = true)
        if (selectedSaves.isEmpty()) {
            updateState { copy(messages = messages.withError(OpenSavePage.LIBRARY, "该游戏只有候选存档位置；确认后才能备份")) }
            return
        }
        val manifest = runCatching { GameSnapshotManifest.from(game, selectedSaves) }
            .getOrElse { error ->
                updateState { copy(messages = messages.withError(OpenSavePage.LIBRARY, "无法创建游戏快照：${error.message}")) }
                return
            }

        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.LIBRARY, "正在备份 ${manifest.gameName}")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    StorageOperationLock.withLock { operations.captureLocked(entry, manifest) }
                }
            }
            result.onSuccess { captured ->
                val notice = listOfNotNull(captured.uploadError, captured.syncNotice).joinToString("；")
                val uploadSuffix = notice.takeIf { it.isNotBlank() }?.let { "；$it" }.orEmpty()
                val action = if (captured.reused) {
                    "${manifest.gameName}：$UNCHANGED_SKIPPED${reusedUploadSuffix(captured)}$uploadSuffix"
                } else {
                    "已备份 ${manifest.gameName}$uploadSuffix"
                }
                state.value = state.value.finished(
                    OpenSavePage.LIBRARY,
                    action = action,
                    error = captured.uploadError,
                ).copy(
                    snapshots = currentSnapshots(),
                    // A publish that reached game-save makes the game's earlier verdicts stale.
                    cloudUpdates = mergeCloudUpdates(
                        state.value.cloudUpdates.filterNot { captured.published && it.entry.libraryKey == entry.libraryKey },
                        listOfNotNull(captured.cloudUpdate),
                    ),
                    saveSignatures = captured.saveSignature
                        ?.let { state.value.saveSignatures + (entry.libraryKey to it) }
                        ?: state.value.saveSignatures,
                )
            }.onFailure { error ->
                state.value = state.value.finished(OpenSavePage.LIBRARY, error = "备份 ${manifest.gameName} 失败：${error.message}")
            }
        }
    }

    /** Notes that a reused snapshot was uploaded just now because it never had been. */
    private fun reusedUploadSuffix(captured: GameCaptureOutcome): String =
        if (captured.uploadedReused) "；已补传云端" else ""

    fun listCloud() {
        val config = savedGameSaveConfig()
        if (config.serviceUrl.isBlank() || config.syncKey.isBlank()) {
            updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, "请先填写并保存 game-save 服务地址和同步密钥")) }
            return
        }
        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.SETTINGS, "正在列举云端快照")
            val result = withContext(Dispatchers.IO) {
                runCatching { gameSaveClient(config).list().getOrThrow() }
            }
            result.onSuccess { files ->
                state.value = state.value.finished(OpenSavePage.SETTINGS, action = "找到 ${files.size} 个云端快照")
                    .copy(remoteSnapshots = files)
            }.onFailure { error ->
                state.value = state.value.finished(OpenSavePage.SETTINGS, error = "列举云端快照失败：${error.message}")
            }
        }
    }

    /** Checks the cloud heads of every game as a batch; the result is shown on the settings page. */
    /** Checks the cloud heads of every game as a batch; the summary is shown on [page]. */
    fun checkCloudUpdates(page: OpenSavePage = state.value.page) {
        if (state.value.busy) return
        val config = savedGameSaveConfig()
        if (config.serviceUrl.isBlank() || config.syncKey.isBlank()) {
            updateState { copy(messages = messages.withError(page, "请先填写 game-save 服务地址和同步密钥")) }
            return
        }
        val keys = GameLibrary.batchBackupEligibleEntries(GameLibrary.sourceGames(state.value.sourceScans))
            .map(SourceGameEntry::libraryKey)
        requestBatch(BatchOperation.CHECK_CLOUD, keys, page)
    }

    /**
     * "用云端覆盖" in "同步与任务": re-reads the game's cloud head now (the listed
     * verdict may be old or only remembered), then asks for the usual restore
     * confirmation, which saves the current local saves as a safety snapshot first.
     */
    fun requestSyncCloudRestore(libraryKey: String) {
        val entry = syncEntry(libraryKey) ?: return
        freshSyncCheck(entry) { fresh -> requestCloudUpdateRestore(fresh, OpenSavePage.SYNC) }
    }

    /**
     * "保留本机并发布" in "同步与任务": re-reads the cloud head, then asks for
     * confirmation to back up the saves on disk and publish them on top of
     * exactly that head ([confirmKeepLocal]).
     */
    fun requestKeepLocal(libraryKey: String) {
        val entry = syncEntry(libraryKey) ?: return
        if (!GameLibrary.canBatchBackup(entry.game)) {
            updateState { copy(messages = messages.withError(OpenSavePage.SYNC, "这款游戏只有待确认的存档位置，不能发布")) }
            return
        }
        freshSyncCheck(entry) { fresh ->
            val head = fresh.nativeHead
            if (head == null) {
                updateState { copy(messages = messages.withError(OpenSavePage.SYNC, KEEP_LOCAL_UNSUPPORTED)) }
                return@freshSyncCheck
            }
            updateState {
                copy(
                    pendingKeepLocal = KeepLocalRequest(
                        entry = entry,
                        expectedHead = head.snapshot.id,
                        deviceName = fresh.deviceName,
                        reason = fresh.message,
                    ),
                    messages = messages.withError(OpenSavePage.SYNC, null),
                )
            }
        }
    }

    fun cancelKeepLocal() = updateState { copy(pendingKeepLocal = null) }

    fun confirmKeepLocal() {
        val request = state.value.pendingKeepLocal ?: return
        updateState { copy(pendingKeepLocal = null) }
        if (state.value.busy) return
        val entry = request.entry
        val name = entry.game.presentation.primaryName
        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.SYNC, "正在备份并发布本机的 $name")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val config = operations.loadConfig()
                    require(config.ready) { "请先在“设置”中启用并保存 game-save 配置" }
                    StorageOperationLock.withLock { operations.keepLocalLocked(entry, request.expectedHead, config) }
                }
            }
            result.onSuccess { outcome ->
                when (outcome) {
                    is KeepLocal.Published -> {
                        state.value = state.value.finished(
                            OpenSavePage.SYNC,
                            action = "已把本机的 $name 发布为云端当前版本；原云端版本仍在云端历史中",
                        ).copy(
                            snapshots = currentSnapshots(),
                            cloudUpdates = state.value.cloudUpdates.filterNot { it.entry.libraryKey == entry.libraryKey },
                            saveSignatures = outcome.record.saveSignature.takeIf(String::isNotBlank)
                                ?.let { state.value.saveSignatures + (entry.libraryKey to it) }
                                ?: state.value.saveSignatures,
                        )
                        forgetCloudVerdict(entry.libraryKey)
                    }
                    KeepLocal.HeadMoved -> {
                        // The new local backup makes the old verdict stale: read the new head
                        // so the game stays listed with what the cloud has now.
                        val fresh = checkOneGame(entry).getOrNull()
                        state.value = state.value.finished(
                            OpenSavePage.SYNC,
                            error = "$name 的云端当前版本在确认后又有变化，本机版本只保存为本地备份、未发布；请重新查看",
                        ).copy(
                            snapshots = currentSnapshots(),
                            cloudUpdates = mergeCloudUpdates(
                                state.value.cloudUpdates.filterNot { it.entry.libraryKey == entry.libraryKey },
                                listOfNotNull(fresh),
                            ),
                        )
                    }
                    KeepLocal.Unsupported -> {
                        state.value = state.value.finished(OpenSavePage.SYNC, error = KEEP_LOCAL_UNSUPPORTED)
                            .copy(snapshots = currentSnapshots())
                    }
                }
            }.onFailure { error ->
                state.value = state.value.finished(OpenSavePage.SYNC, error = "发布 $name 失败：${error.message}")
                    .copy(snapshots = currentSnapshots())
            }
        }
    }

    private fun syncEntry(libraryKey: String): SourceGameEntry? {
        if (state.value.busy) return null
        val entry = GameLibrary.sourceGames(state.value.sourceScans).firstOrNull { it.libraryKey == libraryKey }
        if (entry == null) updateState { copy(messages = messages.withError(OpenSavePage.SYNC, "这款游戏已不在游戏库中；请重新扫描")) }
        return entry
    }

    /**
     * Re-checks one game's cloud head before a sync decision, replacing what
     * the list knew. Up to date clears the item; a head that is not
     * downloadable yet says so; otherwise [then] gets the fresh verdict.
     */
    private fun freshSyncCheck(entry: SourceGameEntry, then: (GameSaveCloudUpdate) -> Unit) {
        val name = entry.game.presentation.primaryName
        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.SYNC, "正在检查 $name 的云端当前版本")
            val result = checkOneGame(entry)
            result.onSuccess { fresh ->
                state.value = state.value.finished(
                    OpenSavePage.SYNC,
                    action = if (fresh == null) "$name：云端与本机一致，无需处理" else null,
                ).copy(
                    cloudUpdates = mergeCloudUpdates(
                        state.value.cloudUpdates.filterNot { it.entry.libraryKey == entry.libraryKey },
                        listOfNotNull(fresh),
                    ),
                )
                when {
                    fresh == null -> forgetCloudVerdict(entry.libraryKey)
                    fresh.action == GameSaveRemoteAction.WAIT_FOR_REMOTE || fresh.remote == null -> updateState {
                        copy(messages = messages.withError(OpenSavePage.SYNC, fresh.message.ifBlank { "云端版本尚未就绪，请稍后再试" }))
                    }
                    else -> then(fresh)
                }
            }.onFailure { error ->
                state.value = state.value.finished(OpenSavePage.SYNC, error = "检查 $name 的云端失败：${error.message}")
            }
        }
    }

    /** One game's cloud head now; null when the cloud and this device agree. */
    private suspend fun checkOneGame(entry: SourceGameEntry): Result<GameSaveCloudUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val config = operations.loadConfig()
            require(config.ready) { "请先在“设置”中启用并保存 game-save 配置" }
            val client = operations.client(config)
            StorageOperationLock.withLock {
                operations.checkCloudLocked(entry, config, client) { client.list().getOrThrow() }
            }
        }
    }

    private suspend fun forgetCloudVerdict(libraryKey: String) {
        withContext(Dispatchers.IO) { runCatching { cloudVerdictStore.update(removedKeys = setOf(libraryKey)) } }
        updateState { copy(rememberedCloudVerdicts = rememberedCloudVerdicts - libraryKey) }
    }

    fun requestCloudUpdateRestore(update: GameSaveCloudUpdate, page: OpenSavePage = OpenSavePage.SYNC) {
        val remote = update.remote ?: run {
            updateState { copy(messages = messages.withError(page, update.message.ifBlank { "远端快照尚未就绪" })) }
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
                    page = page,
                ),
                messages = messages.withError(page, null),
            )
        }
    }

    fun requestLocalRestore(record: SnapshotRecord) {
        if (record.gameKey != null) {
            updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, "请从对应游戏的历史记录中恢复此快照")) }
            return
        }
        updateState {
            copy(
                pendingRestore = RestoreRequest(local = record, page = OpenSavePage.SETTINGS),
                messages = messages.withError(OpenSavePage.SETTINGS, null),
            )
        }
    }

    fun requestRemoteRestore(remote: RemoteSnapshot) {
        updateState {
            copy(
                pendingRestore = RestoreRequest(remote = remote, page = OpenSavePage.SETTINGS),
                messages = messages.withError(OpenSavePage.SETTINGS, null),
            )
        }
    }

    fun requestLocalGameRestore(entry: SourceGameEntry, record: SnapshotRecord) {
        val game = entry.game
        if (record.gameKey != game.gameKey || record.sourceId != entry.sourceId) {
            updateState { copy(messages = messages.withError(OpenSavePage.LIBRARY, "快照不属于当前游戏或存档来源")) }
            return
        }
        updateState {
            copy(
                pendingRestore = RestoreRequest(local = record, game = game, source = entry.source, page = OpenSavePage.LIBRARY),
                messages = messages.withError(OpenSavePage.LIBRARY, null),
            )
        }
    }

    fun requestRemoteGameRestore(entry: SourceGameEntry, remote: RemoteSnapshot) {
        val game = entry.game
        val identity = GameCloudNaming.identity(entry.source, entry.sourceId, game)
        if (remote.type != identity.saveType.id || !remote.name.startsWith(GameCloudNaming.remotePrefix(identity))) {
            updateState { copy(messages = messages.withError(OpenSavePage.LIBRARY, "云端快照不属于当前游戏或存档来源")) }
            return
        }
        updateState {
            copy(
                pendingRestore = RestoreRequest(remote = remote, game = game, source = entry.source, page = OpenSavePage.LIBRARY),
                messages = messages.withError(OpenSavePage.LIBRARY, null),
            )
        }
    }

    fun cancelRestore() = updateState { copy(pendingRestore = null) }

    fun confirmRestore() {
        val request = state.value.pendingRestore ?: return
        val page = request.page
        // A whole-source restore replaces the source directory, so it may only
        // target the saved source, never an unsaved Root path draft.
        val source = request.source ?: persistedWriteTarget().getOrElse { error ->
            updateState { copy(pendingRestore = null, messages = messages.withError(page, error.message ?: "无法恢复快照")) }
            return
        }.source
        if (!canWriteTo(source)) {
            updateState { copy(pendingRestore = null, messages = messages.withError(page, "当前系统授权目录为只读，不能恢复；请重新授予读写权限")) }
            return
        }
        viewModelScope.launch {
            state.value = state.value.started(page, "正在下载并安全恢复快照").copy(pendingRestore = null)
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
                state.value = state.value.finished(page, action = outcome.message).copy(
                    report = outcome.report ?: state.value.report,
                    snapshots = currentSnapshots(),
                    cloudUpdates = state.value.cloudUpdates.filterNot { update ->
                        update.entry.source.idSeed == source.idSeed &&
                            update.entry.game.gameKey == request.game?.gameKey &&
                            update.remote?.name == request.remote?.name
                    },
                )
                // The restored saves differ from what the signatures last saw.
                readSaveSignatures(GameLibrary.sourceGames(state.value.sourceScans))
            }.onFailure { error ->
                state.value = state.value.finished(page, error = "恢复快照失败：${error.message}")
                    .copy(snapshots = currentSnapshots())
                readSaveSignatures(GameLibrary.sourceGames(state.value.sourceScans))
            }
        }
    }

    private suspend fun restoreGameRequest(
        request: RestoreRequest,
        source: SourceSelection,
        requestedGame: DetectedGame,
    ) {
        val sourceId = SnapshotNaming.sourceId(source.idSeed)
        val currentGame = state.value.sourceScans
            .firstOrNull { it.sourceId == sourceId }
            ?.scan
            ?.let { scan -> GameLibrary.group(scan.detected).firstOrNull { it.gameKey == requestedGame.gameKey } }
            ?: error("请先扫描当前存档来源")
        StorageOperationLock.withLock { operations.restoreGameLocked(request, source, requestedGame, currentGame) }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        runCatching { autoBackupScheduler.setEnabled(enabled) }
            .onSuccess {
                updateState {
                    copy(
                        autoBackupEnabled = enabled,
                        messages = messages.with(
                            OpenSavePage.SETTINGS,
                            action = if (enabled) "已启用 Wi-Fi 自动备份" else "已关闭自动备份",
                        ),
                    )
                }
            }
            .onFailure { error ->
                updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, "设置自动备份失败：${error.message}")) }
            }
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
            ?: request.remote?.let(operations::remoteCacheFile)
            ?: error("没有可恢复的快照")
        if (request.remote != null && !archive.isFile) {
            val config = gameSaveStore.load()
            gameSaveClient(config).download(request.remote, archive).getOrThrow()
        }
        require(archive.isFile) { "快照文件不存在" }

        // The safety copy is taken from the live source, never from a private
        // workspace that may be days old; without it the restore does not run.
        val workspace = storage.workspaceDirectory()
        importSelection(source).getOrThrow()
        addSafetySnapshot(sourceId, workspace, "恢复前安全副本")
        snapshotEngine.restoreSnapshot(archive, workspace).getOrThrow()
        return restoreSelection(source).getOrThrow()
    }

    /** Saves [contents] as a local whole-source snapshot before something overwrites it. */
    private fun addSafetySnapshot(sourceId: String, contents: File, comment: String) {
        val timestamp = System.currentTimeMillis()
        val safetyName = SnapshotNaming.remoteName(sourceId, timestamp)
        val safetyArchive = File(app.filesDir, "$SNAPSHOT_DIRECTORY/$safetyName")
        val safetyBuild = snapshotEngine.createSnapshot(contents, safetyArchive).getOrThrow()
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
                comment = comment,
            ),
        )
    }

    /**
     * Copies the source's current content into a safety snapshot, then writes
     * the private workspace back. The workspace is set aside by a same-directory
     * rename while the source is imported, so its files are never re-encoded.
     */
    private suspend fun writeBackWorkspaceLocked(source: SourceSelection): Result<CopyReport> = withContext(Dispatchers.IO) {
        runCatching {
            val workspace = storage.workspaceDirectory()
            // A workspace left set aside by an interrupted write-back comes back first.
            WorkspaceWriteBackPolicy.recoverHeldWorkspace(workspace)
            WorkspaceWriteBackPolicy.requireWorkspace(workspace)
            val parent = workspace.absoluteFile.parentFile ?: error("工作区没有上级目录")
            val held = File(parent, "${WorkspaceWriteBackPolicy.HELD_PREFIX}${UUID.randomUUID()}")
            require(workspace.renameTo(held)) { "无法暂存工作区" }
            try {
                importSelection(source).getOrThrow()
                addSafetySnapshot(SnapshotNaming.sourceId(source.idSeed), workspace, "写回前安全副本")
            } finally {
                val discarded = File(parent, "${WorkspaceWriteBackPolicy.DISCARDED_PREFIX}${UUID.randomUUID()}")
                if (workspace.exists()) require(workspace.renameTo(discarded)) { "无法清理来源副本" }
                require(held.renameTo(workspace)) { "无法还原工作区" }
                runCatching { discarded.deleteRecursively() }
            }
            restoreSelection(source).getOrThrow()
        }
    }

    private fun runSourceOperation(
        action: String,
        done: (CopyReport) -> String = { report -> "完成：${report.fileCount} 个文件，${report.byteCount} 字节" },
        operation: suspend () -> Result<CopyReport>,
    ) {
        viewModelScope.launch {
            state.value = state.value.started(OpenSavePage.SETTINGS, action)
            StorageOperationLock.withLock { operation() }
                .onSuccess { report ->
                    state.value = state.value.finished(OpenSavePage.SETTINGS, action = done(report)).copy(
                        report = report,
                        snapshots = currentSnapshots(),
                    )
                }
                .onFailure { error ->
                    state.value = state.value.finished(OpenSavePage.SETTINGS, error = error.message ?: "操作失败")
                        .copy(snapshots = currentSnapshots())
                }
        }
    }

    /**
     * The saved source that destructive whole-source writes may target: the
     * persisted SAF tree or the persisted Root path, never an unsaved draft.
     */
    private fun persistedWriteTarget(): Result<WorkspaceWriteBackRequest> = runCatching {
        val current = state.value
        val target = when (current.storageMode) {
            StorageMode.SAF -> {
                val tree = current.authorizedTrees.firstOrNull { it.uri == current.selectedUri }
                    ?: error("请先添加并授权一个系统目录")
                require(storage.hasReadPermission(tree.uri)) { "系统授权目录的权限已失效，请重新授权" }
                WorkspaceWriteBackRequest(SourceSelection.Saf(tree.uri), tree.label)
            }
            StorageMode.ROOT -> {
                val path = WorkspaceWriteBackPolicy.rootTarget(current.rootPath, rootStorage.persistedRootPath()).getOrThrow()
                WorkspaceWriteBackRequest(SourceSelection.Root(path), path)
            }
        }
        require(canWriteTo(target.source)) { "当前系统授权目录为只读，不能写回；请重新授予读写权限" }
        target
    }

    private fun requiredSource(): SourceSelection? {
        return when (state.value.storageMode) {
            StorageMode.SAF -> {
                val uri = state.value.selectedUri
                if (uri == null || !storage.hasReadPermission(uri)) {
                    updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, "请先添加并授权一个可读取目录")) }
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
                        updateState { copy(messages = messages.withError(OpenSavePage.SETTINGS, "请先填写有效的 Root 绝对路径：${it.message}")) }
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

    /** Every operation uses the saved config, never an editor draft. */
    private fun savedGameSaveConfig(): GameSaveConfig =
        runCatching { gameSaveStore.load() }.getOrDefault(GameSaveConfig())

    private fun gameSaveClient(config: GameSaveConfig) =
        GameSaveClient(config, gameSaveDevice.identity())

    /** Every game keeps its own newest versions, so status and history never drop a game backed up long ago. */
    private fun currentSnapshots(): List<SnapshotRecord> = LibraryView.retainedSnapshots(
        snapshotRepository.list()
            .filter { record -> runCatching { snapshotRepository.fileFor(record).isFile }.getOrDefault(false) },
        perGame = MAX_GAME_HISTORY,
        other = MAX_HISTORY,
    )

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

    /** Marks an operation started from [page]: clears that page's lines and shows [progress]. */
    private fun SourceUiState.started(page: OpenSavePage, progress: String) =
        copy(busy = true, progress = progress, messages = messages.with(page))

    /** A batch that is still running keeps the app busy and its progress line. */
    private fun SourceUiState.finished(page: OpenSavePage, action: String? = null, error: String? = null) =
        copy(busy = batch != null, progress = batch?.text, messages = messages.with(page, action, error))

    private data class CaptureOutcome(
        val report: CopyReport,
        val record: SnapshotRecord,
        val uploadError: String?,
    )

    private data class RestoreOutcome(
        val message: String,
        val report: CopyReport?,
    )

    private companion object {
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val UNCHANGED_SKIPPED = "无变化，已跳过"
        const val MAX_HISTORY = 40
        const val MAX_GAME_HISTORY = 100
        const val SIGNATURE_REFRESH_MIN_MILLIS = 5_000L
        const val KEEP_LOCAL_UNSUPPORTED =
            "当前 game-save 服务只支持旧的同步协议，不能“保留本机并发布”；请升级 game-save 服务，或在 PC 端处理"
        const val KEY_PAGE = "ui.page"
        const val KEY_SYNC_RETURN = "ui.syncReturnPage"
        const val KEY_SELECTED_GAME = "ui.selectedGameKey"
        const val KEY_DETAIL_OPEN = "ui.gameDetailOpen"
        const val KEY_TYPE_FILTER = "ui.libraryTypeFilter"
        const val KEY_SOURCE_DETAILS = "ui.sourceDetailsExpanded"
        const val KEY_SELECTION = "ui.librarySelection"
        const val KEY_SELECTION_MODE = "ui.selectionMode"
        const val KEY_STATUS_FILTER = "ui.libraryStatusFilter"
    }
}
