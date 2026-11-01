package com.jun9100.opensave.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GameCloudNaming
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GamePresentation
import com.jun9100.opensave.GameStatusResolver
import com.jun9100.opensave.GameSyncStatus
import com.jun9100.opensave.LibraryStatusFilter
import com.jun9100.opensave.LibraryRowHistory
import com.jun9100.opensave.LibrarySelection
import com.jun9100.opensave.LibraryView
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.PageMessage
import com.jun9100.opensave.RemoteSnapshot
import com.jun9100.opensave.SnapshotRecord
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.SteamMetadataRepository
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
    val onSourceDetailsExpanded: (Boolean) -> Unit = {},
    val onOpenManagement: () -> Unit = {},
    /** Opens the system folder picker to grant a save directory. */
    val onPickFolder: () -> Unit = {},
    val onScan: () -> Unit = {},
    /** A batch operation (plan §5.4) over libraryKeys; the ViewModel narrows them to the eligible ones. */
    val onBatch: (BatchOperation, List<String>) -> Unit = { _, _ -> },
    val onCancelBatch: () -> Unit = {},
    val onOpenBatchReport: () -> Unit = {},
    val onSetAutoBackup: (String, Boolean) -> Unit = { _, _ -> },
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
    onOpenManagement: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val context = LocalContext.current
    val coverRepository = remember(context) { CoverRepository(context.applicationContext) }
    val metadataRepository = remember(context) { SteamMetadataRepository(context.applicationContext) }
    val actions = remember(viewModel, onOpenManagement) {
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
            onSourceDetailsExpanded = viewModel::setSourceDetailsExpanded,
            onOpenManagement = onOpenManagement,
            onPickFolder = onPickFolder,
            onScan = viewModel::scanSource,
            onBatch = { operation, keys -> viewModel.requestBatch(operation, keys) },
            onCancelBatch = viewModel::cancelBatch,
            onOpenBatchReport = viewModel::openBatchReport,
            onSetAutoBackup = viewModel::setGameAutoBackup,
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
    val games = remember(state.sourceScans) { GameLibrary.sourceGames(state.sourceScans) }
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
    val visibleKeys = remember(visibleGames) { visibleGames.map { it.libraryKey } }
    val visibleEligibleKeys = remember(visibleGames) {
        GameLibrary.batchBackupEligibleEntries(visibleGames).map { it.libraryKey }
    }
    val eligibleKeys = remember(games) { GameLibrary.batchBackupEligibleEntries(games).map { it.libraryKey } }
    val typeEligibleKeys = remember(typeGames) { GameLibrary.batchBackupEligibleEntries(typeGames).map { it.libraryKey } }
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
    var commandsOpen by rememberSaveable { mutableStateOf(false) }
    var batchMenuOpen by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(handheld, shellFocus.hasFocus, visibleKeys, showingPortraitDetail, menuKey, commandsOpen, batchMenuOpen) {
        if (!handheld || shellFocus.hasFocus || menuKey != null || commandsOpen || batchMenuOpen) return@LaunchedEffect
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
        if (!handheld || focus.listHasFocus || focus.detailHasFocus || menuKey != null || commandsOpen || batchMenuOpen) return@LaunchedEffect
        withFrameNanos { }
        if (showingPortraitDetail) {
            focus.detailPrimary.tryRequestFocus()
        } else {
            HandheldDispatch.preferredFocusKey(focus.lastFocusedKey, state.selectedGameKey, visibleKeys)
                ?.let { focusRow(it) }
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
            targetSelected = target in state.librarySelection,
            primary = primary,
            selectionMode = selecting,
        )
        when (action) {
            LibraryHandheldAction.None -> Unit
            is LibraryHandheldAction.OpenMenu -> menuKey = action.key
            is LibraryHandheldAction.SetSelected ->
                whenIdle(state.busy) { actions.onBatchSelected(action.key, action.selected) }()
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
            )
        }
    }
    if (commandsOpen) {
        CommandDialog(
            title = "游戏库命令",
            entries = libraryCommands(
                busy = state.busy,
                typeOptions = typeOptions,
                selectedTypeId = typeId,
                selectedStatus = state.libraryStatusFilter,
                selectionMode = selecting,
                selectedCount = selectedKeys.size,
                onScan = actions.onScan,
                selection = selectionActions,
                batch = batchEntries,
                onTypeSelected = actions.onTypeSelected,
                onStatusSelected = actions.onStatusSelected,
                onOpenManagement = actions.onOpenManagement,
            ),
            busy = state.busy,
            onDismiss = { commandsOpen = false },
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
                visibleCount = visibleGames.size,
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
        // Landscape keeps the whole list height (plan S1/S2): messages and the
        // progress strip live in the right pane there.
        if (!landscape) LibraryMessageLines(messages = messages, busy = state.busy)
        val list: @Composable (Modifier) -> Unit = { listModifier ->
            GameList(
                state = state,
                games = games,
                visibleGames = visibleGames,
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
                        whenIdle(state.busy) { actions.onBatchSelected(key, key !in state.librarySelection) }()
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
        when {
            state.sourceScans.isEmpty() -> item(key = "empty") {
                EmptyGameLibrary(
                    message = "正在扫描已知的游戏与模拟器存档来源。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenManagement = actions.onOpenManagement,
                    onPickFolder = actions.onPickFolder,
                )
            }
            games.isEmpty() && LibraryView.needsFolderGrant(state) -> item(key = "empty") {
                EmptyGameLibrary(
                    message = NO_ROOT_GUIDE,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenManagement = actions.onOpenManagement,
                    onPickFolder = actions.onPickFolder,
                )
            }
            games.isEmpty() -> item(key = "empty") {
                EmptyGameLibrary(
                    message = "已扫描所有可用来源，但没有发现明确的游戏存档位置。候选目录不会默认纳入备份。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenManagement = actions.onOpenManagement,
                    onPickFolder = actions.onPickFolder,
                )
            }
            visibleGames.isEmpty() -> item(key = "empty") {
                Text(
                    "当前筛选下没有游戏。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(visibleGames, key = { it.libraryKey }) { entry ->
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
                    batchChecked = key in state.librarySelection,
                    batchEligible = GameLibrary.canBatchBackup(entry.game),
                    batchEnabled = !state.busy,
                    current = key == currentKey,
                    status = statuses[key],
                    selectionMode = selectionMode,
                    onClick = { onRowClick(key) },
                    onFocused = {
                        focus.lastFocusedKey = key
                        if (onRowFocused != null) focusedKey = key
                    },
                    onBatchCheckedChange = { selected -> actions.onBatchSelected(key, selected) },
                    focusRequester = requester,
                )
                HorizontalDivider(modifier = Modifier.padding(start = gameRowTextStart(selectionMode)))
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

internal const val GAME_LIST_TAG = "game-list"
internal const val BATCH_MENU_TAG = "batch-menu"

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
                    if (candidateLocations > 0) append(" · 候选 $candidateLocations 个")
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
                        "${scan.gameCount} 款已识别 · ${scan.detected.size} 个位置"
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

private const val NO_ROOT_GUIDE =
    "没有 Root 时，OpenSave 只能读取你授权的目录。请添加模拟器的存档目录，" +
        "例如 PPSSPP 的 PSP 文件夹、RetroArch 或 Dolphin 的存档文件夹，添加后会自动扫描。"

@Composable
private fun EmptyGameLibrary(
    message: String,
    modifier: Modifier = Modifier,
    onOpenManagement: () -> Unit,
    onPickFolder: () -> Unit,
) {
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
                Text("添加存档目录", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onOpenManagement, modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp))) {
                Text("全部来源")
            }
        }
    }
}

@ComponentPreviews
@Composable
private fun EmptyGameLibraryPreview() {
    PreviewSurface {
        EmptyGameLibrary(
            message = NO_ROOT_GUIDE,
            onOpenManagement = {},
            onPickFolder = {},
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
