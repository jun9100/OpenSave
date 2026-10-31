package com.jun9100.opensave.ui.library

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GameCloudNaming
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GamePresentation
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.SteamMetadataRepository
import com.jun9100.opensave.ui.formatBytes
import com.jun9100.opensave.ui.formatTime
import com.jun9100.opensave.ui.kindLabel
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.preview.PreviewSurface
import com.jun9100.opensave.ui.sourceAccessLabel

@Composable
internal fun GameLibraryCard(
    entry: SourceGameEntry,
    state: SourceUiState,
    viewModel: SourceViewModel,
    coverRepository: CoverRepository,
    metadataRepository: SteamMetadataRepository,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    val game = entry.game
    val compactArtwork = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val cloudIdentity = GameCloudNaming.identity(entry.source, entry.sourceId, game)
    var expanded by remember(entry.libraryKey) { mutableStateOf(false) }
    var presentation by remember(entry.libraryKey) { mutableStateOf(game.presentation) }
    LaunchedEffect(entry.libraryKey) {
        presentation = metadataRepository.enrich(game.presentation)
    }
    val sourceId = entry.sourceId
    val localHistory = state.snapshots.filter {
        it.gameKey == game.gameKey && it.sourceId == sourceId
    }
    val remoteHistory = state.remoteSnapshots.filter {
        it.type == cloudIdentity.saveType.id && it.name.startsWith(GameCloudNaming.remotePrefix(cloudIdentity))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (compactArtwork) {
                Row(verticalAlignment = Alignment.Top) {
                    GameArtwork(
                        presentation = presentation,
                        coverRepository = coverRepository,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    GameLibraryTitleBlock(
                        entry = entry,
                        presentation = presentation,
                        modifier = Modifier.weight(1f),
                    )
                    GameLibrarySelectionControls(
                        selected = selected,
                        enabled = !state.busy && GameLibrary.canBatchBackup(game),
                        expanded = expanded,
                        onSelectedChange = onSelectedChange,
                        onExpandedChange = { expanded = !expanded },
                    )
                }
                Spacer(Modifier.height(8.dp))
            } else {
                GameArtwork(
                    presentation = presentation,
                    coverRepository = coverRepository,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    GameLibraryTitleBlock(
                        entry = entry,
                        presentation = presentation,
                        modifier = Modifier.weight(1f),
                    )
                    GameLibrarySelectionControls(
                        selected = selected,
                        enabled = !state.busy && GameLibrary.canBatchBackup(game),
                        expanded = expanded,
                        onSelectedChange = onSelectedChange,
                        onExpandedChange = { expanded = !expanded },
                    )
                }
            }
            Text(
                buildString {
                    append(game.saves.map { kindLabel(it.kind) }.distinct().joinToString("、"))
                    append(" · ${game.locationCount} 个位置")
                    if (game.candidateCount > 0) append(" · 候选 ${game.candidateCount}")
                    presentation.identity?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (game.isCandidateOnly) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        "本地 ${localHistory.size} · 云端 ${remoteHistory.size}",
                        modifier = Modifier.padding(start = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(if (expanded) "收起" else "详情") }
            }
            Button(
                onClick = {
                    viewModel.requestGameBackup(entry.copy(game = game.copy(presentation = presentation)))
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = "备份游戏存档")
                Text("备份游戏存档", modifier = Modifier.padding(start = 8.dp))
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text("存档位置", style = MaterialTheme.typography.labelLarge)
                game.saves.take(MAX_VISIBLE_LOCATIONS_PER_GAME).forEach { save ->
                    Text(
                        "${if (save.candidate) "候选" else "已识别"} · ${save.relativePath}",
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (save.candidate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (game.saves.size > MAX_VISIBLE_LOCATIONS_PER_GAME) {
                    Text("还有 ${game.saves.size - MAX_VISIBLE_LOCATIONS_PER_GAME} 个位置未展开", style = MaterialTheme.typography.labelSmall)
                }
                if (localHistory.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text("本地快照", style = MaterialTheme.typography.labelLarge)
                    localHistory.take(MAX_GAME_HISTORY_ROWS).forEach { snapshot ->
                        SnapshotHistoryRow(
                            title = snapshot.comment.ifBlank { formatTime(snapshot.createdAtMillis) },
                            detail = "${formatBytes(snapshot.sizeBytes)} · ${if (snapshot.uploaded) "已上传" else "仅本地"}",
                            enabled = !state.busy,
                            onRestore = {
                                viewModel.requestLocalGameRestore(
                                    entry.copy(game = game.copy(presentation = presentation)),
                                    snapshot,
                                )
                            },
                        )
                    }
                }
                if (remoteHistory.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text("云端快照", style = MaterialTheme.typography.labelLarge)
                    remoteHistory.take(MAX_GAME_HISTORY_ROWS).forEach { snapshot ->
                        SnapshotHistoryRow(
                            title = snapshot.modified.ifBlank { snapshot.name },
                            detail = formatBytes(snapshot.sizeBytes),
                            enabled = !state.busy,
                            onRestore = {
                                viewModel.requestRemoteGameRestore(
                                    entry.copy(game = game.copy(presentation = presentation)),
                                    snapshot,
                                )
                            },
                        )
                    }
                }
                if (state.gameSaveEnabled && remoteHistory.isEmpty()) {
                    Text(
                        "云端记录为空时，可在“备份与设置”中列举 game-save 快照。",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameLibraryTitleBlock(
    entry: SourceGameEntry,
    presentation: GamePresentation,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            entry.sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sourceAccessLabel(entry.sourceAccess),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.saveType.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            presentation.primaryName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        presentation.originalName?.takeIf { it != presentation.localizedName }?.let { original ->
            Text(
                original,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GameLibrarySelectionControls(
    selected: Boolean,
    enabled: Boolean,
    expanded: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onExpandedChange: () -> Unit,
) {
    Checkbox(
        checked = selected,
        onCheckedChange = onSelectedChange,
        enabled = enabled,
    )
    IconButton(onClick = onExpandedChange) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起游戏详情" else "展开游戏详情",
        )
    }
}

@Composable
private fun SnapshotHistoryRow(
    title: String,
    detail: String,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRestore, enabled = enabled) {
            Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "恢复此游戏快照")
        }
    }
}

private const val MAX_VISIBLE_LOCATIONS_PER_GAME = 24
private const val MAX_GAME_HISTORY_ROWS = 8

/** The card header without its repositories: title block and selection controls. */
@ComponentPreviews
@Composable
private fun GameLibraryCardHeaderPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(PreviewData.switchEntry to true, PreviewData.pcEntry to false).forEach { (entry, selected) ->
                Row(verticalAlignment = Alignment.Top) {
                    GameIcon(entry.game.presentation.iconKey, Modifier.size(56.dp))
                    Spacer(Modifier.size(10.dp))
                    GameLibraryTitleBlock(
                        entry = entry,
                        presentation = entry.game.presentation,
                        modifier = Modifier.weight(1f),
                    )
                    GameLibrarySelectionControls(
                        selected = selected,
                        enabled = true,
                        expanded = false,
                        onSelectedChange = {},
                        onExpandedChange = {},
                    )
                }
            }
        }
    }
}

@ComponentPreviews
@Composable
private fun SnapshotHistoryRowPreview() {
    PreviewSurface {
        Column {
            SnapshotHistoryRow(title = "2026/10/31 20:15", detail = "1.5 MiB · 已上传", enabled = true, onRestore = {})
            SnapshotHistoryRow(title = "备份前安全快照", detail = "1.4 MiB · 仅本地", enabled = false, onRestore = {})
        }
    }
}
