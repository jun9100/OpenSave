package com.jun9100.opensave.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GameSaveType
import com.jun9100.opensave.LibrarySelection
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.SteamMetadataRepository
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewSurface
import com.jun9100.opensave.ui.sourceAccessLabel

@Composable
internal fun GameLibraryPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    modifier: Modifier = Modifier,
    onOpenManagement: () -> Unit,
) {
    val games = remember(state.sourceScans) { GameLibrary.sourceGames(state.sourceScans) }
    val batchEligibleKeys = GameLibrary.batchBackupEligibleEntries(games).map { it.libraryKey }
    // Selection lives in the ViewModel: nothing is selected by default, and a
    // rescan or rotation keeps what the user chose.
    val selectedGameKeys = state.librarySelection
    val messages = state.messages[OpenSavePage.LIBRARY]
    val typeOptions = remember(games) {
        listOf("all" to "全部") + games
            .map(SourceGameEntry::saveType)
            .distinctBy(GameSaveType::id)
            .sortedBy(GameSaveType::label)
            .map { it.id to it.label }
    }
    var selectedTypeId by remember(state.scanRevision) { mutableStateOf("all") }
    val visibleGames = remember(games, selectedTypeId) {
        if (selectedTypeId == "all") games else games.filter { it.saveType.id == selectedTypeId }
    }
    val visibleEligibleKeys = remember(visibleGames) {
        GameLibrary.batchBackupEligibleEntries(visibleGames).map { it.libraryKey }
    }
    val visibleSelectedKeys = LibrarySelection.effective(selectedGameKeys, visibleEligibleKeys)
    var sourceDetailsExpanded by remember(state.scanRevision) { mutableStateOf(false) }
    val context = LocalContext.current
    val coverRepository = remember(context) { CoverRepository(context.applicationContext) }
    val metadataRepository = remember(context) { SteamMetadataRepository(context.applicationContext) }
    val availableSources = state.sourceScans.count { it.scan != null }
    val skippedSources = state.sourceScans.filter { it.scan == null }
    val gameHubLocations = state.sourceScans.sumOf { it.scan?.gameHubPcCount ?: 0 }
    val candidateLocations = state.sourceScans.sumOf { it.scan?.candidateCount ?: 0 }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (state.sourceScans.isEmpty()) "游戏存档" else "${visibleGames.size} / ${games.size} 个存档条目 · ${batchEligibleKeys.size} 款可批量备份",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$availableSources 个来源已扫描 · 跳过 ${skippedSources.size} 个",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { sourceDetailsExpanded = !sourceDetailsExpanded },
                                enabled = state.sourceScans.isNotEmpty(),
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    if (sourceDetailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = if (sourceDetailsExpanded) "收起来源扫描结果" else "展开来源扫描结果",
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::scanSource,
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = "扫描存档")
                        Text("扫描", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (sourceDetailsExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
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
                if (gameHubLocations > 0 || candidateLocations > 0) {
                    Text(
                        buildString {
                            if (gameHubLocations > 0) append("PC 容器 $gameHubLocations 个位置")
                            if (candidateLocations > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("候选 $candidateLocations 个")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(state.progress.orEmpty(), modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                messages.error?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
                }
                if (games.isNotEmpty()) {
                    SaveTypeFilterBar(
                        options = typeOptions,
                        selectedTypeId = selectedTypeId,
                        enabled = !state.busy,
                        onSelected = { selectedTypeId = it },
                    )
                }
                if (visibleEligibleKeys.isNotEmpty()) {
                    BatchSelectionToolbar(
                        selectedCount = visibleSelectedKeys.size,
                        eligibleKeys = visibleEligibleKeys,
                        enabled = !state.busy,
                        onToggleAll = { viewModel.toggleLibrarySelection(visibleEligibleKeys) },
                        onBackup = { viewModel.requestBatchGameBackup(visibleSelectedKeys) },
                    )
                }
                messages.action?.takeIf { !state.busy && !it.startsWith("扫描完成：") }?.let {
                    Text(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        when {
            state.sourceScans.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyGameLibrary(
                    message = "正在扫描已知的游戏与模拟器存档来源。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenManagement = onOpenManagement,
                )
            }
            games.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyGameLibrary(
                    message = "已扫描所有可用来源，但没有发现明确的游戏存档位置。候选目录不会默认纳入备份。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenManagement = onOpenManagement,
                )
            }
            else -> items(visibleGames, key = { it.libraryKey }) { entry ->
                GameLibraryCard(
                    entry = entry,
                    state = state,
                    viewModel = viewModel,
                    coverRepository = coverRepository,
                    metadataRepository = metadataRepository,
                    selected = entry.libraryKey in selectedGameKeys,
                    onSelectedChange = { selected -> viewModel.setLibrarySelected(entry.libraryKey, selected) },
                )
            }
        }
    }
}

@Composable
private fun SaveTypeFilterBar(
    options: List<Pair<String, String>>,
    selectedTypeId: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = selectedTypeId == id,
                onClick = { onSelected(id) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun BatchSelectionToolbar(
    selectedCount: Int,
    eligibleKeys: List<String>,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onBackup: () -> Unit,
) {
    val allSelected = eligibleKeys.isNotEmpty() && selectedCount == eligibleKeys.size
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("已选 $selectedCount / ${eligibleKeys.size} 款", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = onToggleAll, enabled = enabled && eligibleKeys.isNotEmpty()) {
            Text(if (allSelected) "取消全选" else "全选")
        }
        Button(
            onClick = onBackup,
            enabled = enabled && selectedCount > 0,
            modifier = Modifier.height(44.dp),
        ) {
            Icon(Icons.Outlined.Save, contentDescription = null)
            Text("批量备份", modifier = Modifier.padding(start = 8.dp))
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
private fun LibraryToolbarPreview() {
    PreviewSurface {
        Column {
            SaveTypeFilterBar(
                options = listOf("all" to "全部", "switch" to "Switch 游戏", "pc" to "PC 游戏", "emulator-ppsspp" to "PPSSPP"),
                selectedTypeId = "switch",
                enabled = true,
                onSelected = {},
            )
            BatchSelectionToolbar(
                selectedCount = 0,
                eligibleKeys = listOf("a", "b", "c"),
                enabled = true,
                onToggleAll = {},
                onBackup = {},
            )
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
