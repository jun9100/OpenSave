package com.jun9100.opensave.ui.library

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
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GameCloudNaming
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GamePresentation
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
import com.jun9100.opensave.ui.LocalAdaptiveLayout
import com.jun9100.opensave.ui.ProvideAdaptiveLayout
import com.jun9100.opensave.ui.StatusStrip
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.OdinScreenPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.preview.PreviewSurface
import com.jun9100.opensave.ui.sourceAccessLabel
import kotlinx.coroutines.delay

/** Everything the library page can ask for; the ViewModel in the app, no-ops in previews. */
internal class LibraryActions(
    val onTypeSelected: (String) -> Unit = {},
    val onToggleAll: (List<String>) -> Unit = {},
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
)

@Composable
internal fun GameLibraryPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onOpenManagement: () -> Unit,
) {
    val context = LocalContext.current
    val coverRepository = remember(context) { CoverRepository(context.applicationContext) }
    val metadataRepository = remember(context) { SteamMetadataRepository(context.applicationContext) }
    val actions = remember(viewModel, onOpenManagement) {
        LibraryActions(
            onTypeSelected = viewModel::setLibraryTypeFilter,
            onToggleAll = viewModel::toggleLibrarySelection,
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
    val visibleGames = remember(games, typeId) { LibraryView.filter(games, typeId) }
    val visibleEligibleKeys = remember(visibleGames) {
        GameLibrary.batchBackupEligibleEntries(visibleGames).map { it.libraryKey }
    }
    // Selection lives in the ViewModel: nothing is selected by default, and a
    // rescan or rotation keeps what the user chose.
    val visibleSelectedKeys = LibrarySelection.effective(state.librarySelection, visibleEligibleKeys)
    val history = remember(state.snapshots) { LibraryView.historyIndex(state.snapshots) }
    // Steam names resolved for rows are shared with the detail pane and its backup.
    val presentations = remember { mutableStateMapOf<String, GamePresentation>() }
    val presented: (SourceGameEntry) -> SourceGameEntry = { entry ->
        presentations[entry.libraryKey]?.let { entry.copy(game = entry.game.copy(presentation = it)) } ?: entry
    }
    val detail = LibraryView.detailEntry(visibleGames, state.selectedGameKey, fallbackToFirst = landscape)
    val messages = state.messages[OpenSavePage.LIBRARY]

    // A portrait detail whose game vanished after a rescan falls back to the list.
    val detailMissing = state.gameDetailOpen && detail == null && state.sourceScans.isNotEmpty() && !state.busy
    LaunchedEffect(detailMissing) {
        if (detailMissing && !landscape) actions.onCloseDetail()
    }

    if (!landscape && state.gameDetailOpen && detail != null) {
        // Results of the backup and restores started here are shown on top of the detail.
        Column(modifier = modifier.fillMaxSize()) {
            LibraryMessageLines(messages = messages, busy = state.busy)
            GameDetailFor(
                entry = presented(detail),
                state = state,
                actions = actions,
                coverRepository = coverRepository,
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
                visibleCount = visibleGames.size,
                selectedCount = visibleSelectedKeys.size,
                eligibleCount = visibleEligibleKeys.size,
                enabled = !state.busy,
                singleRow = landscape,
                onTypeSelected = actions.onTypeSelected,
                onToggleAll = { actions.onToggleAll(visibleEligibleKeys) },
                onBackupSelected = { actions.onBackupSelected(visibleSelectedKeys) },
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
                presentations = presentations,
                currentKey = detail?.libraryKey.takeIf { landscape },
                listState = listState,
                coverRepository = coverRepository,
                metadataRepository = metadataRepository,
                actions = actions,
                onRowClick = { key -> if (landscape) actions.onSelectGame(key) else actions.onOpenGame(key) },
                // Landscape: the detail follows D-pad focus; portrait focus never opens a page.
                onRowFocused = if (landscape) actions.onSelectGame else null,
                modifier = listModifier,
            )
        }
        if (landscape) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                list(Modifier.width(layout.listPaneWidthDp.dp).fillMaxHeight())
                VerticalDivider()
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    LibraryMessageLines(messages = messages, busy = state.busy)
                    if (detail != null) {
                        GameDetailFor(
                            entry = presented(detail),
                            state = state,
                            actions = actions,
                            coverRepository = coverRepository,
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
                    StatusStrip(busy = state.busy, progress = state.progress)
                }
            }
        } else {
            list(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun GameList(
    state: SourceUiState,
    games: List<SourceGameEntry>,
    visibleGames: List<SourceGameEntry>,
    history: Map<String, LibraryRowHistory>,
    presentations: MutableMap<String, GamePresentation>,
    currentKey: String?,
    listState: LazyListState,
    coverRepository: CoverRepository?,
    metadataRepository: SteamMetadataRepository?,
    actions: LibraryActions,
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
        modifier = modifier,
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
                )
            }
            games.isEmpty() -> item(key = "empty") {
                EmptyGameLibrary(
                    message = "已扫描所有可用来源，但没有发现明确的游戏存档位置。候选目录不会默认纳入备份。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenManagement = actions.onOpenManagement,
                )
            }
            else -> items(visibleGames, key = { it.libraryKey }) { entry ->
                val key = entry.libraryKey
                if (metadataRepository != null) {
                    LaunchedEffect(key) {
                        if (key !in presentations) presentations[key] = metadataRepository.enrich(entry.game.presentation)
                    }
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
                    onClick = { onRowClick(key) },
                    onFocused = onRowFocused?.let { { focusedKey = key } },
                    onBatchCheckedChange = { selected -> actions.onBatchSelected(key, selected) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 98.dp))
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

/** Detail pane wired to the library state: histories, batch toggle and the backup/restore requests. */
@Composable
private fun GameDetailFor(
    entry: SourceGameEntry,
    state: SourceUiState,
    actions: LibraryActions,
    coverRepository: CoverRepository?,
    modifier: Modifier = Modifier,
) {
    val game = entry.game
    val cloudIdentity = GameCloudNaming.identity(entry.source, entry.sourceId, game)
    val localHistory = state.snapshots.filter {
        it.gameKey == game.gameKey && it.sourceId == entry.sourceId
    }
    val remoteHistory = state.remoteSnapshots.filter {
        it.type == cloudIdentity.saveType.id && it.name.startsWith(GameCloudNaming.remotePrefix(cloudIdentity))
    }
    GameDetailPane(
        entry = entry,
        presentation = game.presentation,
        localHistory = localHistory,
        remoteHistory = remoteHistory,
        busy = state.busy,
        gameSaveEnabled = state.gameSaveEnabled,
        batchChecked = entry.libraryKey in state.librarySelection,
        batchEnabled = !state.busy && GameLibrary.canBatchBackup(game),
        coverRepository = coverRepository,
        onBatchCheckedChange = { selected -> actions.onBatchSelected(entry.libraryKey, selected) },
        onBackup = { actions.onBackupGame(entry) },
        onRestoreLocal = { snapshot -> actions.onRestoreLocal(entry, snapshot) },
        onRestoreRemote = { snapshot -> actions.onRestoreRemote(entry, snapshot) },
        modifier = modifier,
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
                    append("$availableSources 个来源已扫描 · 跳过 ${skippedSources.size} 个")
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
                modifier = Modifier.size(36.dp),
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

@Composable
private fun EmptyGameLibrary(
    message: String,
    modifier: Modifier = Modifier,
    onOpenManagement: () -> Unit,
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
        Button(onClick = onOpenManagement) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Text("存档来源", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@ComponentPreviews
@Composable
private fun EmptyGameLibraryPreview() {
    PreviewSurface {
        EmptyGameLibrary(
            message = "已扫描所有可用来源，但没有发现明确的游戏存档位置。候选目录不会默认纳入备份。",
            onOpenManagement = {},
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
