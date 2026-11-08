package com.jun9100.opensave.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import com.jun9100.opensave.LibraryRowGroup
import androidx.compose.foundation.lazy.itemsIndexed
import com.jun9100.opensave.AndroidAppConfirmations
import androidx.compose.ui.text.input.KeyboardType
import com.jun9100.opensave.ui.settings.TextEditDialog
import com.jun9100.opensave.GameNames
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GameCloudNaming
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GamePresentation
import com.jun9100.opensave.GameSaveCloudGameMember
import com.jun9100.opensave.GameStatusResolver
import com.jun9100.opensave.GameSyncStatus
import com.jun9100.opensave.LibraryEmptyState
import com.jun9100.opensave.LibraryStatusFilter
import com.jun9100.opensave.LibraryRowHistory
import com.jun9100.opensave.LibrarySelection
import com.jun9100.opensave.LibraryView
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.PageMessage
import com.jun9100.opensave.R
import com.jun9100.opensave.RemoteSnapshot
import com.jun9100.opensave.SnapshotRecord
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.SteamMetadataRepository
import com.jun9100.opensave.SyncInbox
import com.jun9100.opensave.SyncItemKind
import com.jun9100.opensave.batch.BatchEligibility
import com.jun9100.opensave.batch.BatchOperation
import com.jun9100.opensave.ui.CommandDialog
import com.jun9100.opensave.ui.batch.BatchMenuActions
import com.jun9100.opensave.ui.batch.BatchScope
import com.jun9100.opensave.ui.batch.batchCommands
import com.jun9100.opensave.ui.dialogs.HandheldMenuDialog
import com.jun9100.opensave.ui.GameActionMenu
import com.jun9100.opensave.ui.LibrarySelectionActions
import com.jun9100.opensave.ui.LocalAdaptiveLayout
import com.jun9100.opensave.ui.ProvideAdaptiveLayout
import com.jun9100.opensave.ui.StatusStrip
import com.jun9100.opensave.ui.gameMenuEntries
import com.jun9100.opensave.ui.input.HandheldCommand
import com.jun9100.opensave.ui.input.HandheldCommandEffect
import com.jun9100.opensave.ui.input.HandheldDispatch
import com.jun9100.opensave.ui.input.LibraryHandheldAction
import com.jun9100.opensave.ui.input.LocalShellFocus
import com.jun9100.opensave.ui.input.RightStickScroll
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.handheldNavigationActive
import com.jun9100.opensave.ui.input.tryRequestFocus
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.input.whenIdleWith
import com.jun9100.opensave.ui.libraryCommands
import com.jun9100.opensave.ui.librarySelectionCommands
import com.jun9100.opensave.ui.libraryFilterCommands
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.OdinScreenPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.preview.PreviewSurface
import com.jun9100.opensave.ui.sourceAccessLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything the library page can ask for; the ViewModel in the app, no-ops in previews. */
internal class LibraryActions(
    val onTypeSelected: (String) -> Unit = {},
    val onStatusSelected: (LibraryStatusFilter) -> Unit = {},
    val onEnterSelection: () -> Unit = {},
    val onExitSelection: () -> Unit = {},
    val onSelectAll: (List<String>) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    /** Start or the main button with nothing to back up; true when in selection mode. */
    val onNothingToBackup: (Boolean) -> Unit = {},
    val onBackupSelected: (Set<String>) -> Unit = {},
    val onBatchSelected: (String, Boolean) -> Unit = { _, _ -> },
    /** Landscape: show the game in the right pane. */
    val onSelectGame: (String) -> Unit = {},
    /** Portrait: open the full-screen detail page. */
    val onOpenGame: (String) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onBackupGame: (SourceGameEntry) -> Unit = {},
    val onRestoreLocal: (SourceGameEntry, SnapshotRecord) -> Unit = { _, _ -> },
    val onRestoreRemote: (SourceGameEntry, RemoteSnapshot) -> Unit = { _, _ -> },
    /** Read the other devices' cloud saves for one game. */
    val onLoadOtherDevices: (SourceGameEntry) -> Unit = {},
    /** Open the mapping dialog for one other device's save location. */
    val onRestoreOtherDevice: (SourceGameEntry, GameSaveCloudGameMember) -> Unit = { _, _ -> },
    val onSourceDetailsExpanded: (Boolean) -> Unit = {},
    val onOpenSources: () -> Unit = {},
    val onOpenSyncTasks: () -> Unit = {},
    /** Opens the system folder picker to grant a save directory. */
    val onPickFolder: () -> Unit = {},
    val onScan: () -> Unit = {},
    /** A batch operation (plan §5.4) over libraryKeys; the ViewModel narrows them to the eligible ones. */
    val onBatch: (BatchOperation, List<String>) -> Unit = { _, _ -> },
    val onCancelBatch: () -> Unit = {},
    val onOpenBatchReport: () -> Unit = {},
    val onSetAutoBackup: (String, Boolean) -> Unit = { _, _ -> },
    /** Names a game by its game key; a blank name restores the detected one. */
    val onRename: (String, String) -> Unit = { _, _ -> },
    val onSetAndroidAppIsGame: (String, Boolean) -> Unit = { _, _ -> },
    /** Opens the photo picker for a game's cover, by game key. */
    val onPickCover: (String) -> Unit = {},
    val onClearCover: (String) -> Unit = {},
    /** "清除筛选" of the empty state: back to every type and status. */
    val onClearFilters: () -> Unit = {},
    val onOpenWizard: () -> Unit = {},
)

/**
 * Pad focus of the library: one requester per composed row, the row focused
 * last (restored after a rescan, a closed detail or a dialog) and the primary
 * button of the detail pane that A on a landscape row moves to.
 */
@Stable
internal class LibraryFocusState {
    val rows = mutableMapOf<String, FocusRequester>()
    var lastFocusedKey by mutableStateOf<String?>(null)
    var listHasFocus by mutableStateOf(false)
    var detailHasFocus by mutableStateOf(false)
    val detailPrimary = FocusRequester()

    /** Landscape A on a row: focus the detail once it shows this game. */
    var pendingDetailFocus by mutableStateOf<String?>(null)
}

@Composable
internal fun GameLibraryPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onOpenSources: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val context = LocalContext.current
    val coverRepository = remember(context) { CoverRepository(context.applicationContext) }
    val metadataRepository = remember(context) { SteamMetadataRepository(context.applicationContext) }
    var coverGameKey by rememberSaveable { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val gameKey = coverGameKey
        coverGameKey = null
        if (uri != null && gameKey != null) viewModel.setCustomCover(gameKey, uri)
    }
    val actions = remember(viewModel, onOpenSources) {
        LibraryActions(
            onTypeSelected = viewModel::setLibraryTypeFilter,
            onStatusSelected = viewModel::setLibraryStatusFilter,
            onEnterSelection = viewModel::enterSelectionMode,
            onExitSelection = viewModel::exitSelectionMode,
            onSelectAll = viewModel::selectAllLibraryGames,
            onClearSelection = viewModel::clearLibrarySelection,
            onNothingToBackup = viewModel::reportNothingToBackup,
            onBackupSelected = viewModel::requestBatchGameBackup,
            onBatchSelected = viewModel::setLibrarySelected,
            onSelectGame = viewModel::selectGame,
            onOpenGame = viewModel::openGame,
            onCloseDetail = viewModel::closeGameDetail,
            onBackupGame = viewModel::requestGameBackup,
            onRestoreLocal = viewModel::requestLocalGameRestore,
            onRestoreRemote = viewModel::requestRemoteGameRestore,
            onLoadOtherDevices = { entry -> viewModel.loadOtherDeviceSaves(entry) },
            onRestoreOtherDevice = viewModel::requestOtherDeviceRestore,
            onSourceDetailsExpanded = viewModel::setSourceDetailsExpanded,
            onOpenSources = onOpenSources,
            onOpenSyncTasks = viewModel::openSyncTasks,
            onPickFolder = onPickFolder,
            onScan = viewModel::scanSource,
            onBatch = { operation, keys -> viewModel.requestBatch(operation, keys) },
            onCancelBatch = viewModel::cancelBatch,
            onOpenBatchReport = viewModel::openBatchReport,
            onSetAutoBackup = viewModel::setGameAutoBackup,
            onRename = viewModel::renameGame,
            onSetAndroidAppIsGame = viewModel::setAndroidAppIsGame,
            onPickCover = { gameKey ->
                coverGameKey = gameKey
                coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onClearCover = viewModel::clearCustomCover,
            onClearFilters = viewModel::clearLibraryFilters,
            onOpenWizard = viewModel::openWizard,
        )
    }
    GameLibraryContent(
        state = state,
        actions = actions,
        listState = listState,
        coverRepository = coverRepository,
        metadataRepository = metadataRepository,
        modifier = modifier,
    )
}

/**
 * Landscape: fixed toolbar, compact list on the left, detail of the current game
 * on the right, with the page messages and the progress strip above and below
 * the detail so the list keeps its full height. Portrait: fixed toolbar over a single column of compact rows; a
 * tapped row opens the full-screen detail instead.
 *
 * Pad (plan §4): toolbar, list and detail are focus groups that remember their
 * last child; the list gets focus when the page opens or loses it (rescan,
 * closed detail); X opens the game menu, Y the command panel, Select selects
 * the game (entering selection mode, where A toggles and B leaves), Start is
 * the toolbar's main button, L2/R2 page and the right stick scrolls.
 */
@Composable
internal fun GameLibraryContent(
    state: SourceUiState,
    actions: LibraryActions,
    listState: LazyListState,
    coverRepository: CoverRepository?,
    metadataRepository: SteamMetadataRepository?,
    modifier: Modifier = Modifier,
) {
    val layout = LocalAdaptiveLayout.current
    val landscape = layout.isLandscape
    val games = remember(state.sourceScans, state.nameRevision) {
        LibraryView.libraryEntries(GameLibrary.sourceGames(state.sourceScans))
    }
    val typeOptions = remember(games) { LibraryView.typeOptions(games) }
    val typeId = LibraryView.effectiveTypeId(state.libraryTypeFilter, typeOptions)
    val statuses = remember(games, state.snapshots, state.saveSignatures, state.cloudUpdates, state.rememberedCloudVerdicts, state.gameSaveEnabled) {
        GameStatusResolver.resolveAll(
            entries = games,
            snapshots = state.snapshots,
            signatures = state.saveSignatures,
            cloudActions = GameStatusResolver.effectiveCloudActions(state.cloudUpdates, state.rememberedCloudVerdicts, state.snapshots),
            cloudEnabled = state.gameSaveEnabled,
        )
    }
    val typeGames = remember(games, typeId) { LibraryView.filter(games, typeId) }
    val statusCounts = remember(typeGames, statuses) { LibraryView.statusCounts(typeGames, statuses) }
    val visibleGames = remember(typeGames, statuses, state.libraryStatusFilter) {
        LibraryView.filterByStatus(typeGames, statuses, state.libraryStatusFilter)
    }
    // One row per game: the same game found in several sources shares a row,
    // and the detail switches between its save locations.
    val rowGroups = remember(visibleGames) { LibraryView.groupRows(visibleGames) }
    val rowEntries = remember(rowGroups, state.selectedGameKey) {
        rowGroups.map { LibraryView.rowEntry(it, state.selectedGameKey) }
    }
    val groupByEntryKey = remember(rowGroups) {
        rowGroups.flatMap { group -> group.members.map { it.libraryKey to group } }.toMap()
    }
    val visibleKeys = remember(rowEntries) { rowEntries.map { it.libraryKey } }
    val visibleEligibleKeys = remember(visibleGames) {
        GameLibrary.batchBackupEligibleEntries(visibleGames).map { it.libraryKey }
    }
    val eligibleKeys = remember(games) { GameLibrary.batchBackupEligibleEntries(games).map { it.libraryKey } }
    val typeEligibleKeys = remember(typeGames) { GameLibrary.batchBackupEligibleEntries(typeGames).map { it.libraryKey } }
    // Selecting a row selects every save location of it that batch backup takes.
    val selectRow: (String, Boolean) -> Unit = { key, selected ->
        val members = groupByEntryKey[key]?.members?.map { it.libraryKey } ?: listOf(key)
        members.filter { it in eligibleKeys }.ifEmpty { listOf(key) }.forEach { actions.onBatchSelected(it, selected) }
    }
    val rowSelected: (String) -> Boolean = { key ->
        val members = groupByEntryKey[key]?.members?.map { it.libraryKey }?.filter { it in eligibleKeys }.orEmpty()
        if (members.isEmpty()) key in state.librarySelection else members.all { it in state.librarySelection }
    }
    // "备份有变更" follows the type filter only: it is a status set of its own.
    val changedKeys = remember(typeEligibleKeys, statuses) { LibraryView.changedKeys(typeEligibleKeys, statuses) }
    // Selection lives in the ViewModel: nothing is selected by default, a rescan
    // or rotation keeps what the user chose, and a filter only hides choices.
    val selectedKeys = LibrarySelection.effective(state.librarySelection, eligibleKeys)
    val selecting = state.selectionMode || selectedKeys.isNotEmpty()
    val primary = LibraryView.primaryBackup(selectedKeys, changedKeys, selecting)
    val selectionActions = remember(actions, visibleEligibleKeys) {
        LibrarySelectionActions(
            onEnter = actions.onEnterSelection,
            onExit = actions.onExitSelection,
            onSelectAll = { actions.onSelectAll(visibleEligibleKeys) },
            onClear = actions.onClearSelection,
        )
    }
    val runPrimary: () -> Unit = {
        if (primary.enabled) actions.onBackupSelected(primary.keys) else actions.onNothingToBackup(selecting)
    }
    // The other batch operations take the selection in selection mode, else the type filter's games.
    val batchScope = remember(selecting, selectedKeys, eligibleKeys, typeEligibleKeys, typeId, statuses) {
        val keys = if (selecting) eligibleKeys.filter { it in selectedKeys } else typeEligibleKeys
        val restorePlan = BatchEligibility.safeRestore(keys, statuses)
        BatchScope(
            label = when {
                selecting -> "已选 ${keys.size} 款"
                typeId == LibraryView.ALL_TYPES -> "全部游戏"
                else -> "当前类型"
            },
            keys = keys,
            pendingUpload = BatchEligibility.pendingUpload(keys, statuses).size,
            restorable = restorePlan.restore.size,
            conflicts = restorePlan.conflicts.size,
        )
    }
    val cloudReady = state.gameSaveEnabled && state.gameSaveUrl.isNotBlank() && state.gameSaveSyncKey.isNotBlank()
    val batchEntries = batchCommands(
        busy = state.busy,
        batchRunning = state.batch != null,
        scope = batchScope,
        primary = primary,
        cloudReady = cloudReady,
        hasReport = state.lastBatchReport != null,
        actions = BatchMenuActions(
            onBackup = runPrimary,
            onUploadPending = { actions.onBatch(BatchOperation.UPLOAD_PENDING, batchScope.keys) },
            onCheckCloud = { actions.onBatch(BatchOperation.CHECK_CLOUD, batchScope.keys) },
            onRestoreCloudNewer = { actions.onBatch(BatchOperation.RESTORE_CLOUD_NEWER, batchScope.keys) },
            onCancelBatch = actions.onCancelBatch,
            onOpenReport = actions.onOpenBatchReport,
        ),
    )
    val history = remember(state.snapshots) { LibraryView.historyIndex(state.snapshots) }
    val cloudActions = remember(state.cloudUpdates, state.rememberedCloudVerdicts, state.snapshots) {
        GameStatusResolver.effectiveCloudActions(state.cloudUpdates, state.rememberedCloudVerdicts, state.snapshots)
    }
    val syncItems = remember(games, statuses, state.cloudUpdates, cloudActions, state.syncDismissed) {
        SyncInbox.items(games, statuses, state.cloudUpdates, cloudActions, state.syncDismissed)
    }
    val syncBanner = SyncInbox.bannerText(syncItems)
    // Steam names resolved for rows are shared with the detail pane and its backup.
    val presentations = remember { mutableStateMapOf<String, GamePresentation>() }
    val presented: (SourceGameEntry) -> SourceGameEntry = { entry ->
        presentations[entry.libraryKey]?.let { entry.copy(game = entry.game.copy(presentation = it)) } ?: entry
    }
    val detail = LibraryView.detailEntry(visibleGames, state.selectedGameKey, fallbackToFirst = landscape)
    val messages = state.messages[OpenSavePage.LIBRARY]
    val showingPortraitDetail = !landscape && state.gameDetailOpen && detail != null

    val handheld = handheldNavigationActive()
    val shellFocus = LocalShellFocus.current
    val focus = remember { LibraryFocusState() }
    val scope = rememberCoroutineScope()
    var detailTab by rememberSaveable { mutableStateOf(DetailTab.VERSIONS) }
    val detailScroll = rememberScrollState()
    var menuKey by rememberSaveable { mutableStateOf<String?>(null) }
    var renameKey by rememberSaveable { mutableStateOf<String?>(null) }
    var commandsOpen by rememberSaveable { mutableStateOf(false) }
    var batchMenuOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var selectionMenuOpen by rememberSaveable { mutableStateOf(false) }
    val dialogOpen = menuKey != null || renameKey != null || commandsOpen || batchMenuOpen || filtersOpen || selectionMenuOpen
    // The summary line is item 0 of the list once anything was scanned.
    val rowOffset = if (state.sourceScans.isNotEmpty()) 1 else 0

    suspend fun focusRow(key: String) {
        val index = visibleKeys.indexOf(key)
        if (index < 0) return
        val itemIndex = index + rowOffset
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) listState.scrollToItem(itemIndex)
        withFrameNanos { }
        focus.rows[key]?.tryRequestFocus()
    }

    fun showDetail(key: String, tab: DetailTab) {
        detailTab = tab
        if (landscape) {
            actions.onSelectGame(key)
            if (handheld) focus.pendingDetailFocus = key
        } else {
            actions.onOpenGame(key)
        }
    }

    // A portrait detail whose game vanished after a rescan falls back to the list.
    val detailMissing = state.gameDetailOpen && detail == null && state.sourceScans.isNotEmpty() && !state.busy
    LaunchedEffect(detailMissing) {
        if (detailMissing && !landscape) actions.onCloseDetail()
    }

    // Initial focus, and focus back after it dropped (rescan replaced the
    // rows, the portrait detail closed, the page was switched with L1/R1).
    LaunchedEffect(handheld, shellFocus.hasFocus, visibleKeys, showingPortraitDetail, dialogOpen) {
        if (!handheld || shellFocus.hasFocus || dialogOpen) return@LaunchedEffect
        withFrameNanos { }
        if (showingPortraitDetail) {
            focus.detailPrimary.tryRequestFocus()
        } else {
            HandheldDispatch.preferredFocusKey(focus.lastFocusedKey, state.selectedGameKey, visibleKeys)
                ?.let { focusRow(it) }
        }
    }

    // Waking pad mode starts on the preferred row even when the system already
    // gave focus to the first focusable (the filter chip) in non-touch mode.
    LaunchedEffect(handheld) {
        if (!handheld || focus.listHasFocus || focus.detailHasFocus || dialogOpen) return@LaunchedEffect
        withFrameNanos { }
        if (showingPortraitDetail) {
            focus.detailPrimary.tryRequestFocus()
        } else {
            HandheldDispatch.preferredFocusKey(focus.lastFocusedKey, state.selectedGameKey, visibleKeys)
                ?.let { focusRow(it) }
        }
    }

    // Back from the background (or the home screen) the window regains focus and,
    // in non-touch mode, the system hands it to the first focusable: the "全部"
    // chip. Focus that was on a row goes back to that row.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var rowFocusedBeforeBlur by remember { mutableStateOf(false) }
    val currentHandheld by rememberUpdatedState(handheld)
    val currentDialogOpen by rememberUpdatedState(dialogOpen)
    val currentPortraitDetail by rememberUpdatedState(showingPortraitDetail)
    val currentVisibleKeys by rememberUpdatedState(visibleKeys)
    val currentSelectedKey by rememberUpdatedState(state.selectedGameKey)
    LaunchedEffect(windowFocused) {
        if (!windowFocused) {
            rowFocusedBeforeBlur = focus.listHasFocus
            return@LaunchedEffect
        }
        if (!rowFocusedBeforeBlur) return@LaunchedEffect
        rowFocusedBeforeBlur = false
        // The system's own focus move may land a few frames after the window focus.
        repeat(WINDOW_FOCUS_CHECKS) {
            withFrameNanos { }
            if (!currentHandheld || currentDialogOpen || currentPortraitDetail) return@LaunchedEffect
            if (HandheldDispatch.shouldRestoreRowFocus(focus.listHasFocus, focus.detailHasFocus, focus.pendingDetailFocus != null)) {
                HandheldDispatch.preferredFocusKey(focus.lastFocusedKey, currentSelectedKey, currentVisibleKeys)
                    ?.let { focusRow(it) }
            }
            delay(WINDOW_FOCUS_CHECK_DELAY_MILLIS)
        }
    }

    // Landscape A on a row: once the pane shows that game, focus its "备份这款游戏".
    LaunchedEffect(focus.pendingDetailFocus, detail?.libraryKey) {
        val pending = focus.pendingDetailFocus ?: return@LaunchedEffect
        if (pending != detail?.libraryKey) return@LaunchedEffect
        withFrameNanos { }
        focus.detailPrimary.tryRequestFocus()
        focus.pendingDetailFocus = null
    }

    // B leaves selection mode (plan §4.1). Registered first, so the detail
    // pane's own B (back to its row) still comes before it.
    BackHandler(enabled = selecting && !showingPortraitDetail) { actions.onExitSelection() }

    // B in the landscape detail pane returns to the row it belongs to (plan §4.1).
    BackHandler(enabled = landscape && focus.detailHasFocus && detail != null) {
        detail?.let { scope.launch { focusRow(it.libraryKey) } }
    }

    HandheldCommandEffect { command ->
        if (command == HandheldCommand.COMMAND_PANEL) {
            commandsOpen = true
            return@HandheldCommandEffect
        }
        val target = HandheldDispatch.targetGame(
            focusedKey = focus.lastFocusedKey.takeIf { focus.listHasFocus },
            selectedKey = if (landscape || showingPortraitDetail) detail?.libraryKey else state.selectedGameKey,
            visibleKeys = visibleKeys,
            landscape = landscape,
            detailOpen = showingPortraitDetail,
        )
        val targetEntry = target?.let { key -> visibleGames.firstOrNull { it.libraryKey == key } }
        val action = HandheldDispatch.libraryAction(
            command = command,
            targetKey = target,
            targetEligible = targetEntry?.let { GameLibrary.canBatchBackup(it.game) } == true,
            targetSelected = target?.let(rowSelected) == true,
            primary = primary,
            selectionMode = selecting,
        )
        when (action) {
            LibraryHandheldAction.None -> Unit
            is LibraryHandheldAction.OpenMenu -> menuKey = action.key
            is LibraryHandheldAction.SetSelected ->
                whenIdle(state.busy) { selectRow(action.key, action.selected) }()
            LibraryHandheldAction.EnterSelection -> whenIdle(state.busy, actions.onEnterSelection)()
            is LibraryHandheldAction.BackupSelected -> whenIdle(state.busy) { actions.onBackupSelected(action.keys) }()
            LibraryHandheldAction.NothingToBackup -> whenIdle(state.busy) { actions.onNothingToBackup(selecting) }()
            is LibraryHandheldAction.Page -> scope.launch {
                if (showingPortraitDetail) {
                    val step = detailScroll.viewportSize * PAGE_SCROLL_FRACTION
                    detailScroll.animateScrollBy(if (action.forward) step else -step)
                    return@launch
                }
                if (visibleKeys.isEmpty()) return@launch
                val current = focus.lastFocusedKey?.takeIf { focus.listHasFocus }?.let(visibleKeys::indexOf)?.takeIf { it >= 0 }
                    ?: (listState.firstVisibleItemIndex - rowOffset).coerceAtLeast(0)
                val visibleRows = listState.layoutInfo.visibleItemsInfo.count { it.index >= rowOffset }
                val index = HandheldDispatch.pageJumpIndex(current, visibleRows, visibleKeys.size, action.forward)
                if (handheld) focusRow(visibleKeys[index]) else listState.scrollToItem(index + rowOffset)
            }
        }
    }

    // The right stick scrolls the detail where one is shown, the list otherwise.
    RightStickScroll(if (landscape && detail != null || showingPortraitDetail) detailScroll else listState)

    menuKey?.let { key ->
        val entry = visibleGames.firstOrNull { it.libraryKey == key }
        if (entry == null) {
            LaunchedEffect(key) { menuKey = null }
        } else {
            GameMenuFor(
                entry = presented(entry),
                state = state,
                actions = actions,
                onShowDetail = { tab -> showDetail(key, tab) },
                onDismiss = { menuKey = null },
                onRename = { renameKey = key },
                hasCustomCover = CoverRepository.revision.let { coverRepository?.hasCustomCover(entry.game.gameKey) == true },
            )
        }
    }
    renameKey?.let { key ->
        val entry = games.firstOrNull { it.libraryKey == key }
        if (entry == null) {
            LaunchedEffect(key) { renameKey = null }
        } else {
            TextEditDialog(
                title = "给游戏改名",
                initial = GameNames.override(entry.game.gameKey)
                    ?: entry.game.presentation.primaryName.takeUnless { it == UNNAMED_GAME }.orEmpty(),
                placeholder = entry.game.presentation.identity ?: entry.game.gameKey,
                secret = false,
                keyboardType = KeyboardType.Text,
                note = "只改本设备上显示的名字；留空恢复自动识别的名字。",
                onDismiss = { renameKey = null },
                onConfirm = { name ->
                    actions.onRename(entry.game.gameKey, name)
                    renameKey = null
                },
            )
        }
    }
    if (commandsOpen) {
        CommandDialog(
            title = "游戏库命令",
            entries = libraryCommands(
                busy = state.busy,
                selectionMode = selecting,
                selectedCount = selectedKeys.size,
                filterSummary = listOf(
                    typeOptions.firstOrNull { it.first == typeId }?.second ?: "全部",
                    "状态：${state.libraryStatusFilter.label}",
                ).joinToString(" · "),
                syncAttention = syncItems.size,
                onScan = actions.onScan,
                selection = selectionActions,
                batch = batchEntries,
                onOpenFilters = { filtersOpen = true },
                onOpenSyncTasks = actions.onOpenSyncTasks,
                onOpenSelection = { selectionMenuOpen = true },
            ),
            busy = state.busy,
            onDismiss = { commandsOpen = false },
        )
    }

    if (selectionMenuOpen && selecting) {
        HandheldMenuDialog(
            title = "选择",
            subtitle = "已选 ${selectedKeys.size}",
            entries = librarySelectionCommands(
                busy = state.busy,
                selectedCount = selectedKeys.size,
                selection = selectionActions,
            ),
            onDismiss = { selectionMenuOpen = false },
            modifier = Modifier.testTag(SELECTION_MENU_TAG),
        )
    } else if (selectionMenuOpen) {
        LaunchedEffect(Unit) { selectionMenuOpen = false }
    }

    if (filtersOpen) {
        HandheldMenuDialog(
            title = "筛选",
            subtitle = "${visibleGames.size} 款符合",
            entries = libraryFilterCommands(
                busy = state.busy,
                typeOptions = typeOptions,
                selectedTypeId = typeId,
                selectedStatus = state.libraryStatusFilter,
                onTypeSelected = actions.onTypeSelected,
                onStatusSelected = actions.onStatusSelected,
            ),
            onDismiss = { filtersOpen = false },
            modifier = Modifier.testTag(FILTER_MENU_TAG),
        )
    }

    if (batchMenuOpen) {
        HandheldMenuDialog(
            title = "批量操作",
            subtitle = batchScope.label,
            entries = batchEntries,
            onDismiss = { batchMenuOpen = false },
            modifier = Modifier.testTag(BATCH_MENU_TAG),
        )
    }

    if (showingPortraitDetail) {
        // Results of the backup and restores started here are shown on top of the detail.
        Column(modifier = modifier.fillMaxSize()) {
            LibraryMessageLines(messages = messages, busy = state.busy)
            SaveLocationSwitcher(
                members = groupByEntryKey[detail.libraryKey]?.members.orEmpty(),
                currentKey = detail.libraryKey,
                enabled = !state.busy,
                onSelect = actions.onOpenGame,
            )
            GameDetailFor(
                entry = presented(detail),
                state = state,
                actions = actions,
                coverRepository = coverRepository,
                status = statuses[detail.libraryKey],
                tab = detailTab,
                onTabChange = { detailTab = it },
                scrollState = detailScroll,
                primaryFocusRequester = focus.detailPrimary,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        return
    }

    Column(modifier = modifier) {
        if (games.isNotEmpty()) {
            LibraryToolbar(
                typeOptions = typeOptions,
                selectedTypeId = typeId,
                statusCounts = statusCounts,
                selectedStatus = state.libraryStatusFilter,
                visibleCount = rowGroups.size,
                selectionMode = selecting,
                selectedCount = selectedKeys.size,
                primary = primary,
                enabled = !state.busy,
                singleRow = landscape,
                onTypeSelected = actions.onTypeSelected,
                onStatusSelected = actions.onStatusSelected,
                selection = selectionActions,
                onPrimaryBackup = runPrimary,
                onOpenBatchMenu = { batchMenuOpen = true },
            )
        }
        // Only while something needs a decision: absent, it costs no list height.
        syncBanner?.let { text ->
            SyncStatusBanner(text = text, conflict = syncItems.any { it.kind == SyncItemKind.CONFLICT }, onClick = actions.onOpenSyncTasks)
        }
        // Landscape keeps the whole list height (plan S1/S2): messages and the
        // progress strip live in the right pane there.
        if (!landscape) LibraryMessageLines(messages = messages, busy = state.busy)
        val list: @Composable (Modifier) -> Unit = { listModifier ->
            GameList(
                state = state,
                games = games,
                visibleGames = visibleGames,
                rowGroups = rowGroups,
                rowEntries = rowEntries,
                rowSelected = rowSelected,
                onSelectRow = selectRow,
                history = history,
                statuses = statuses,
                selectionMode = selecting,
                presentations = presentations,
                currentKey = detail?.libraryKey.takeIf { landscape },
                listState = listState,
                coverRepository = coverRepository,
                metadataRepository = metadataRepository,
                actions = actions,
                focus = focus,
                onRowClick = { key ->
                    val eligible = key in eligibleKeys
                    if (selecting && eligible) {
                        // Selection mode: A or a tap toggles the row (plan §4.1).
                        whenIdle(state.busy) { selectRow(key, !rowSelected(key)) }()
                        if (landscape) actions.onSelectGame(key)
                    } else if (landscape) {
                        actions.onSelectGame(key)
                        // Pad: A on a row moves to the pane's [备份], so A A backs it up (plan §4.1).
                        if (handheld) focus.pendingDetailFocus = key
                    } else {
                        actions.onOpenGame(key)
                    }
                },
                // Landscape: the detail follows D-pad focus; portrait focus never opens a page.
                onRowFocused = if (landscape) actions.onSelectGame else null,
                modifier = listModifier,
            )
        }
        if (landscape) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                list(Modifier.width(layout.listPaneWidthDp.dp).fillMaxHeight())
                VerticalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onFocusChanged { focus.detailHasFocus = it.hasFocus }
                        .focusRestorer()
                        .focusGroup(),
                ) {
                    LibraryMessageLines(messages = messages, busy = state.busy)
                    if (detail != null) {
                        SaveLocationSwitcher(
                            members = groupByEntryKey[detail.libraryKey]?.members.orEmpty(),
                            currentKey = detail.libraryKey,
                            enabled = !state.busy,
                            onSelect = actions.onSelectGame,
                        )
                        GameDetailFor(
                            entry = presented(detail),
                            state = state,
                            actions = actions,
                            coverRepository = coverRepository,
                            status = statuses[detail.libraryKey],
                            tab = detailTab,
                            onTabChange = { detailTab = it },
                            scrollState = detailScroll,
                            primaryFocusRequester = focus.detailPrimary,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "选择左侧的游戏查看详情",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    StatusStrip(busy = state.busy, progress = LibraryView.progressLine(state))
                }
            }
        } else {
            list(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private const val PAGE_SCROLL_FRACTION = 0.85f

@Composable
private fun GameList(
    state: SourceUiState,
    games: List<SourceGameEntry>,
    visibleGames: List<SourceGameEntry>,
    rowGroups: List<LibraryRowGroup>,
    rowEntries: List<SourceGameEntry>,
    rowSelected: (String) -> Boolean,
    onSelectRow: (String, Boolean) -> Unit,
    history: Map<String, LibraryRowHistory>,
    statuses: Map<String, GameSyncStatus>,
    selectionMode: Boolean,
    presentations: MutableMap<String, GamePresentation>,
    currentKey: String?,
    listState: LazyListState,
    coverRepository: CoverRepository?,
    metadataRepository: SteamMetadataRepository?,
    actions: LibraryActions,
    focus: LibraryFocusState,
    onRowClick: (String) -> Unit,
    onRowFocused: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // D-pad focus selects a row only after it rests there, so scrolling through
    // the list does not rebuild the detail pane for every row passed.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    if (onRowFocused != null) {
        LaunchedEffect(focusedKey) {
            val key = focusedKey ?: return@LaunchedEffect
            delay(FOCUS_SELECT_DEBOUNCE_MILLIS)
            if (key != currentKey) onRowFocused(key)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .testTag(GAME_LIST_TAG)
            .onFocusChanged { focus.listHasFocus = it.hasFocus }
            // Coming back from the toolbar, rail or detail lands on the row left last.
            .focusRestorer()
            .focusGroup(),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        if (state.sourceScans.isNotEmpty()) {
            item(key = "summary") {
                LibrarySummary(
                    state = state,
                    expanded = state.sourceDetailsExpanded,
                    onExpandedChange = actions.onSourceDetailsExpanded,
                )
            }
        }
        val emptyState = LibraryView.emptyState(
            scanned = state.scanRevision > 0,
            readableSources = state.sourceScans.count { it.access.readable },
            gameCount = games.size,
            visibleCount = visibleGames.size,
        )
        when (emptyState) {
            LibraryEmptyState.SCANNING, LibraryEmptyState.NO_ACCESS, LibraryEmptyState.NOTHING_FOUND -> item(key = "empty") {
                EmptyGameLibrary(
                    state = emptyState,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenSources = actions.onOpenSources,
                    onPickFolder = actions.onPickFolder,
                    onOpenWizard = actions.onOpenWizard,
                )
            }
            LibraryEmptyState.FILTERED_OUT -> item(key = "empty") {
                val typeLabel = games.firstOrNull { it.saveType.id == state.libraryTypeFilter }?.saveType?.label ?: "全部类型"
                FilteredOutLibrary(
                    filterSummary = "$typeLabel · ${state.libraryStatusFilter.label}",
                    onClearFilters = actions.onClearFilters,
                )
            }
            null -> itemsIndexed(rowEntries, key = { index, _ -> rowGroups[index].key }) { index, entry ->
                val group = rowGroups[index]
                val key = entry.libraryKey
                if (metadataRepository != null) {
                    LaunchedEffect(key) {
                        if (key !in presentations) presentations[key] = metadataRepository.enrich(entry.game.presentation)
                    }
                }
                val requester = remember(key) { FocusRequester() }
                DisposableEffect(key, requester) {
                    focus.rows[key] = requester
                    onDispose { focus.rows.remove(key, requester) }
                }
                GameListRow(
                    entry = entry,
                    presentation = presentations[key] ?: entry.game.presentation,
                    history = history[key],
                    coverRepository = coverRepository,
                    batchChecked = rowSelected(key),
                    batchEligible = group.members.any { GameLibrary.canBatchBackup(it.game) },
                    batchEnabled = !state.busy,
                    current = group.members.any { it.libraryKey == currentKey },
                    status = LibraryView.groupStatus(group, statuses),
                    locationCount = group.members.size,
                    selectionMode = selectionMode,
                    onClick = { onRowClick(key) },
                    onFocused = {
                        focus.lastFocusedKey = key
                        if (onRowFocused != null) focusedKey = key
                    },
                    onBatchCheckedChange = { selected -> onSelectRow(key, selected) },
                    focusRequester = requester,
                )
                HorizontalDivider(modifier = Modifier.padding(start = gameRowTextStart(selectionMode)))
            }
        }
    }
}

/**
 * The same game in several sources (e.g. Eden's private, Android/data and
 * public folders): one chip per save location. Each location keeps its own
 * versions and restores; the chip picks which one the detail below shows.
 */
@Composable
private fun SaveLocationSwitcher(
    members: List<SourceGameEntry>,
    currentKey: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (members.size < 2) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            "${members.size} 个存档位置 · 各自备份、各自恢复",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            members.forEach { member ->
                FilterChip(
                    selected = member.libraryKey == currentKey,
                    onClick = { if (enabled && member.libraryKey != currentKey) onSelect(member.libraryKey) },
                    label = { Text(LibraryView.locationLabel(member), maxLines = 1) },
                    modifier = Modifier.handheldFocusRing(RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

/** The library's error line and, once nothing runs, its last result. */
@Composable
private fun LibraryMessageLines(messages: PageMessage, busy: Boolean) {
    messages.error?.let {
        Text(
            it,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    messages.action?.takeIf { !busy && !it.startsWith("扫描完成：") }?.let {
        Text(
            it,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val FOCUS_SELECT_DEBOUNCE_MILLIS = 150L
private const val WINDOW_FOCUS_CHECKS = 4
private const val WINDOW_FOCUS_CHECK_DELAY_MILLIS = 60L

/**
 * One line over the list, "2 款冲突需确认 · 1 款云端较新 ›", that opens
 * "同步与任务"; one focus stop between the toolbar and the list.
 */
@Composable
private fun SyncStatusBanner(text: String, conflict: Boolean, onClick: () -> Unit) {
    val color = if (conflict) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val onColor = if (conflict) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = 40.dp)
            .handheldFocusRing(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(SYNC_BANNER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (conflict) "⚠ $text" else "▼ $text",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = onColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("同步与任务 ›", style = MaterialTheme.typography.labelMedium, color = onColor, maxLines = 1)
    }
}

internal const val GAME_LIST_TAG = "game-list"
internal const val BATCH_MENU_TAG = "batch-menu"
internal const val FILTER_MENU_TAG = "filter-menu"
internal const val SELECTION_MENU_TAG = "selection-menu"
internal const val SYNC_BANNER_TAG = "sync-banner"

/** Local and cloud versions of one game, as the detail pane and the X menu list them. */
private fun localHistoryOf(entry: SourceGameEntry, snapshots: List<SnapshotRecord>): List<SnapshotRecord> =
    snapshots.filter { it.gameKey == entry.game.gameKey && it.sourceId == entry.sourceId }

private fun remoteHistoryOf(entry: SourceGameEntry, remoteSnapshots: List<RemoteSnapshot>): List<RemoteSnapshot> {
    val cloudIdentity = GameCloudNaming.identity(entry.source, entry.sourceId, entry.game)
    return remoteSnapshots.filter {
        it.type == cloudIdentity.saveType.id && it.name.startsWith(GameCloudNaming.remotePrefix(cloudIdentity))
    }
}

/** Detail pane wired to the library state: histories, batch toggle and the backup/restore requests. */
@Composable
private fun GameDetailFor(
    entry: SourceGameEntry,
    state: SourceUiState,
    actions: LibraryActions,
    coverRepository: CoverRepository?,
    status: GameSyncStatus?,
    tab: DetailTab,
    onTabChange: (DetailTab) -> Unit,
    scrollState: ScrollState,
    primaryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val game = entry.game
    GameDetailPane(
        entry = entry,
        presentation = game.presentation,
        localHistory = localHistoryOf(entry, state.snapshots),
        remoteHistory = remoteHistoryOf(entry, state.remoteSnapshots),
        busy = state.busy,
        gameSaveEnabled = state.gameSaveEnabled,
        batchChecked = entry.libraryKey in state.librarySelection,
        // Stays focusable while busy; the change is ignored instead.
        batchEnabled = GameLibrary.canBatchBackup(game),
        coverRepository = coverRepository,
        onBatchCheckedChange = whenIdleWith(state.busy) { selected -> actions.onBatchSelected(entry.libraryKey, selected) },
        onBackup = { actions.onBackupGame(entry) },
        onRestoreLocal = { snapshot -> actions.onRestoreLocal(entry, snapshot) },
        onRestoreRemote = { snapshot -> actions.onRestoreRemote(entry, snapshot) },
        otherDevices = state.otherDeviceSaves[entry.libraryKey],
        onLoadOtherDevices = { actions.onLoadOtherDevices(entry) },
        onRestoreOtherDevice = { member -> actions.onRestoreOtherDevice(entry, member) },
        tab = tab,
        onTabChange = onTabChange,
        scrollState = scrollState,
        primaryFocusRequester = primaryFocusRequester,
        modifier = modifier,
        status = status,
        autoBackupIncluded = (entry.libraryKey !in state.autoBackupExcluded).takeIf { GameLibrary.canBatchBackup(game) },
        autoBackupScheduled = state.autoBackupEnabled,
        onAutoBackupChange = { enabled -> actions.onSetAutoBackup(entry.libraryKey, enabled) },
    )
}

/** The X menu of [entry], wired like the detail pane. */
@Composable
private fun GameMenuFor(
    entry: SourceGameEntry,
    state: SourceUiState,
    actions: LibraryActions,
    onShowDetail: (DetailTab) -> Unit,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    hasCustomCover: Boolean,
) {
    val localHistory = localHistoryOf(entry, state.snapshots)
    val remoteCount = remoteHistoryOf(entry, state.remoteSnapshots).size
    val batchChecked = entry.libraryKey in state.librarySelection
    GameActionMenu(
        entry = entry,
        entries = gameMenuEntries(
            busy = state.busy,
            batchEligible = GameLibrary.canBatchBackup(entry.game),
            batchChecked = batchChecked,
            latestLocal = localHistory.filterNot(SnapshotRecord::isSafetyCopy).maxByOrNull(SnapshotRecord::createdAtMillis),
            versionCount = localHistory.size + remoteCount,
            locationCount = entry.game.locationCount,
            onBackup = { actions.onBackupGame(entry) },
            onToggleBatch = { actions.onBatchSelected(entry.libraryKey, !batchChecked) },
            onRestoreLatest = { record -> actions.onRestoreLocal(entry, record) },
            onShowVersions = { onShowDetail(DetailTab.VERSIONS) },
            onShowLocations = { onShowDetail(DetailTab.LOCATIONS) },
            autoBackupIncluded = (entry.libraryKey !in state.autoBackupExcluded).takeIf { GameLibrary.canBatchBackup(entry.game) },
            autoBackupScheduled = state.autoBackupEnabled,
            onToggleAutoBackup = { actions.onSetAutoBackup(entry.libraryKey, entry.libraryKey in state.autoBackupExcluded) },
            onRename = onRename,
            onChangeCover = { actions.onPickCover(entry.game.gameKey) },
            onResetCover = { actions.onClearCover(entry.game.gameKey) }.takeIf { hasCustomCover },
            onNotAGame = entry.game.presentation.androidPackageName
                ?.takeIf(AndroidAppConfirmations::isConfirmed)
                ?.let { packageName -> { actions.onSetAndroidAppIsGame(packageName, false) } },
        ),
        onDismiss = onDismiss,
    )
}

/** Source scan overview: one line that scrolls with the list, with the per-source details on demand. */
@Composable
private fun LibrarySummary(
    state: SourceUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val availableSources = state.sourceScans.count { it.scan != null }
    val skippedSources = state.sourceScans.filter { it.scan == null }
    val gameHubLocations = state.sourceScans.sumOf { it.scan?.gameHubPcCount ?: 0 }
    val candidateLocations = state.sourceScans.sumOf { it.scan?.candidateCount ?: 0 }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("$availableSources 个来源已扫描")
                    if (skippedSources.isNotEmpty()) append(" · 跳过 ${skippedSources.size} 个")
                    if (gameHubLocations > 0) append(" · PC 容器 $gameHubLocations 个位置")
                    if (candidateLocations > 0) append(" · 待确认位置 $candidateLocations 个")
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.size(40.dp).handheldFocusRing(CircleShape),
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起来源扫描结果" else "展开来源扫描结果",
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.sourceScans.forEach { source ->
                    val detail = source.scan?.let { scan ->
                        "${scan.gameCount} 款游戏 · ${scan.detected.size} 个存档位置"
                    } ?: "跳过 · ${source.skippedReason}"
                    Text(
                        "${source.label} · ${sourceAccessLabel(source.access)} · $detail",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source.access.readable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.sourceScans
                    .filter { it.scan?.let { scan -> scan.truncated || scan.unreadableDirectoryCount > 0 } == true }
                    .take(4)
                    .forEach { source ->
                        Text(
                            "${source.label}：扫描结果可能不完整，请检查权限和范围。",
                            modifier = Modifier.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                skippedSources.take(4).forEach { source ->
                    Text(
                        "${source.label}：${source.skippedReason}",
                        modifier = Modifier.padding(vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** No games at all, by [state]: scanning, nothing readable yet, or nothing found. */
@Composable
private fun EmptyGameLibrary(
    state: LibraryEmptyState,
    modifier: Modifier = Modifier,
    onOpenSources: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenWizard: () -> Unit,
) {
    val message = stringResource(
        when (state) {
            LibraryEmptyState.SCANNING -> R.string.library_empty_scanning
            LibraryEmptyState.NO_ACCESS -> R.string.library_empty_no_access
            else -> R.string.library_empty_nothing_found
        },
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPickFolder, modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp))) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text(stringResource(R.string.library_empty_add_folder), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onOpenSources, modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp))) {
                Text(stringResource(R.string.library_empty_sources))
            }
            if (state == LibraryEmptyState.NO_ACCESS) {
                OutlinedButton(onClick = onOpenWizard, modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp))) {
                    Text(stringResource(R.string.library_empty_wizard))
                }
            }
        }
    }
}

/** Games exist, but the filters hide them all. */
@Composable
private fun FilteredOutLibrary(filterSummary: String, onClearFilters: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.library_empty_filtered, filterSummary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onClearFilters, modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp))) {
            Icon(Icons.Outlined.FilterAltOff, contentDescription = null)
            Text(stringResource(R.string.library_empty_clear_filters), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@ComponentPreviews
@Composable
private fun EmptyGameLibraryPreview() {
    PreviewSurface {
        EmptyGameLibrary(
            state = LibraryEmptyState.NO_ACCESS,
            onOpenSources = {},
            onPickFolder = {},
            onOpenWizard = {},
        )
    }
}

/** The whole library page in the Odin2 window, with fake data and no repositories. */
@OdinScreenPreviews
@Composable
private fun GameLibraryContentPreview() {
    PreviewSurface {
        ProvideAdaptiveLayout(modifier = Modifier.fillMaxSize()) {
            GameLibraryContent(
                state = PreviewData.libraryState,
                actions = LibraryActions(),
                listState = rememberLazyListState(),
                coverRepository = null,
                metadataRepository = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val UNNAMED_GAME = "未命名游戏"
