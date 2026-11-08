package com.jun9100.opensave.ui.library

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GamePresentation
import com.jun9100.opensave.GameSaveCloudGameMember
import com.jun9100.opensave.GameSyncStatus
import com.jun9100.opensave.OtherDeviceSaves
import com.jun9100.opensave.RemoteSnapshot
import com.jun9100.opensave.SnapshotRecord
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.ui.GAME_DETAIL_TAG
import com.jun9100.opensave.ui.formatBytes
import com.jun9100.opensave.ui.formatTime
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.kindLabel
import com.jun9100.opensave.ui.preview.OdinScreenPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.preview.PreviewSurface
import com.jun9100.opensave.ui.sourceAccessLabel

internal enum class DetailTab(val label: String) {
    VERSIONS("版本"),
    LOCATIONS("存档位置"),
}

/**
 * Detail of one game: the landscape right pane and the portrait full-screen page.
 * It carries what the old expanded library card showed: backup this game, local
 * and cloud versions with restore, and the save locations. Buttons stay
 * focusable while [busy] (clicks are ignored) so D-pad focus never drops;
 * [primaryFocusRequester] lets the list hand focus to "备份这款游戏".
 */
@Composable
internal fun GameDetailPane(
    entry: SourceGameEntry,
    presentation: GamePresentation,
    localHistory: List<SnapshotRecord>,
    remoteHistory: List<RemoteSnapshot>,
    busy: Boolean,
    gameSaveEnabled: Boolean,
    batchChecked: Boolean,
    batchEnabled: Boolean,
    coverRepository: CoverRepository?,
    onBatchCheckedChange: (Boolean) -> Unit,
    onBackup: () -> Unit,
    onRestoreLocal: (SnapshotRecord) -> Unit,
    onRestoreRemote: (RemoteSnapshot) -> Unit,
    modifier: Modifier = Modifier,
    /** Hoisted so the X menu can open a tab; null keeps the tab inside the pane. */
    tab: DetailTab? = null,
    onTabChange: (DetailTab) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    primaryFocusRequester: FocusRequester? = null,
    /** The game's sync status (plan §5.2); null while unknown. */
    status: GameSyncStatus? = null,
    /** Whether automatic backup covers this game (plan §5.4); null hides the switch. */
    autoBackupIncluded: Boolean? = null,
    /** False while automatic backup is off for the whole app. */
    autoBackupScheduled: Boolean = true,
    onAutoBackupChange: (Boolean) -> Unit = {},
    /** Other devices' cloud saves for this game; null hides the section. */
    otherDevices: OtherDeviceSaves? = null,
    onLoadOtherDevices: () -> Unit = {},
    onRestoreOtherDevice: (GameSaveCloudGameMember) -> Unit = {},
) {
    val game = entry.game
    var localTab by rememberSaveable(entry.libraryKey) { mutableStateOf(DetailTab.VERSIONS) }
    val shownTab = tab ?: localTab
    val selectTab: (DetailTab) -> Unit = { localTab = it; onTabChange(it) }
    // Entering the detail starts one read-only cloud lookup; it never runs while cloud sync is off.
    LaunchedEffect(entry.libraryKey, gameSaveEnabled) {
        if (gameSaveEnabled) onLoadOtherDevices()
    }
    Column(
        modifier = modifier
            .testTag(GAME_DETAIL_TAG)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (coverRepository != null) {
                GameArtwork(presentation = presentation, coverRepository = coverRepository, modifier = Modifier.size(64.dp))
            } else {
                GameIcon(presentation.iconKey, Modifier.size(64.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                status?.let { GameStatusBadge(it, Modifier.padding(vertical = 2.dp)) }
                Text(
                    "${entry.sourceLabel} · ${entry.saveType.label} · ${sourceAccessLabel(entry.sourceAccess)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(game.saves.map { kindLabel(it.kind) }.distinct().joinToString("、"))
                        append(" · ${game.locationCount} 个位置")
                        if (game.candidateCount > 0) append(" · 待确认 ${game.candidateCount}")
                        presentation.identity?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (game.isCandidateOnly) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = whenIdle(busy, onBackup),
                modifier = Modifier
                    .let { if (primaryFocusRequester != null) it.focusRequester(primaryFocusRequester) else it }
                    .handheldFocusRing(RoundedCornerShape(20.dp))
                    .busyDimmed(busy),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text("备份这款游戏", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .handheldFocusRing(RoundedCornerShape(8.dp))
                    .toggleable(
                        value = batchChecked,
                        enabled = batchEnabled,
                        role = Role.Checkbox,
                        onValueChange = onBatchCheckedChange,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = batchChecked, onCheckedChange = null, enabled = batchEnabled)
                Text(
                    if (batchEnabled) "加入批量选择" else "仅待确认位置，不参与批量备份",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        autoBackupIncluded?.let { included ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .handheldFocusRing(RoundedCornerShape(8.dp))
                    .toggleable(value = included, role = Role.Switch, onValueChange = onAutoBackupChange)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag(AUTO_BACKUP_SWITCH_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动备份这款游戏", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (autoBackupScheduled) "Wi-Fi 下定期备份并上传；只影响本设备" else "自动备份总开关未开启（设置）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = included, onCheckedChange = null)
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            DetailTab.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = shownTab == item,
                    onClick = { selectTab(item) },
                    modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp)),
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = DetailTab.entries.size),
                    label = {
                        Text(
                            when (item) {
                                DetailTab.VERSIONS -> "${item.label} (${localHistory.size + remoteHistory.size})"
                                DetailTab.LOCATIONS -> "${item.label} (${game.locationCount})"
                            },
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        when (shownTab) {
            DetailTab.VERSIONS -> VersionsTab(
                localHistory = localHistory,
                remoteHistory = remoteHistory,
                busy = busy,
                gameSaveEnabled = gameSaveEnabled,
                otherDevices = otherDevices,
                onLoadOtherDevices = onLoadOtherDevices,
                onRestoreOtherDevice = onRestoreOtherDevice,
                onRestoreLocal = onRestoreLocal,
                onRestoreRemote = onRestoreRemote,
            )
            DetailTab.LOCATIONS -> LocationsTab(entry)
        }
    }
}

@Composable
private fun VersionsTab(
    localHistory: List<SnapshotRecord>,
    remoteHistory: List<RemoteSnapshot>,
    busy: Boolean,
    gameSaveEnabled: Boolean,
    otherDevices: OtherDeviceSaves?,
    onLoadOtherDevices: () -> Unit,
    onRestoreOtherDevice: (GameSaveCloudGameMember) -> Unit,
    onRestoreLocal: (SnapshotRecord) -> Unit,
    onRestoreRemote: (RemoteSnapshot) -> Unit,
) {
    if (localHistory.isEmpty() && remoteHistory.isEmpty()) {
        Text(
            "还没有这款游戏的存档版本。",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (localHistory.isNotEmpty()) {
        Text("本地版本", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
        localHistory.take(MAX_GAME_HISTORY_ROWS).forEach { snapshot ->
            SnapshotHistoryRow(
                title = snapshot.comment.replace("安全副本", "安全快照").ifBlank { formatTime(snapshot.createdAtMillis) },
                detail = "${formatBytes(snapshot.sizeBytes)} · ${if (snapshot.uploaded) "已上传" else "仅本地"}",
                enabled = !busy,
                onRestore = { onRestoreLocal(snapshot) },
            )
        }
    }
    if (remoteHistory.isNotEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        Text("云端版本", style = MaterialTheme.typography.labelLarge)
        remoteHistory.take(MAX_GAME_HISTORY_ROWS).forEach { snapshot ->
            SnapshotHistoryRow(
                title = snapshot.modified.ifBlank { snapshot.name },
                detail = formatBytes(snapshot.sizeBytes),
                enabled = !busy,
                onRestore = { onRestoreRemote(snapshot) },
            )
        }
    }
    if (gameSaveEnabled && remoteHistory.isEmpty()) {
        Text(
            "云端记录为空时，可在“设置 › 高级”中列举云端版本。",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (gameSaveEnabled && otherDevices != null) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        Text("其他设备的存档", style = MaterialTheme.typography.labelLarge)
        when (otherDevices) {
            OtherDeviceSaves.Loading -> Text(
                "正在读取其他设备的存档…",
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is OtherDeviceSaves.Failed -> {
                Text(
                    "读取失败：${otherDevices.message}",
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onLoadOtherDevices,
                    modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp)),
                ) { Text("重试") }
            }
            is OtherDeviceSaves.Loaded -> if (otherDevices.members.isEmpty()) {
                Text(
                    "其他设备暂时没有这款游戏的存档",
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                otherDevices.members.forEach { member ->
                    OtherDeviceSaveRow(
                        member = member,
                        busy = busy,
                        onRestore = { onRestoreOtherDevice(member) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OtherDeviceSaveRow(
    member: GameSaveCloudGameMember,
    busy: Boolean,
    onRestore: () -> Unit,
) {
    // A member without a head stays focusable so D-pad focus never drops; the
    // click is ignored and only the button is dimmed.
    val enabled = member.head != null
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                OtherDeviceText.deviceName(member),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                OtherDeviceText.detail(member),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = whenIdle(busy || !enabled, onRestore),
            modifier = Modifier.handheldFocusRing(CircleShape).busyDimmed(busy || !enabled),
        ) {
            Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "用此设备的存档恢复")
        }
    }
}

@Composable
private fun LocationsTab(entry: SourceGameEntry) {
    val game = entry.game
    Spacer(Modifier.height(6.dp))
    game.saves.take(MAX_VISIBLE_LOCATIONS_PER_GAME).forEach { save ->
        Text(
            "${if (save.candidate) "待确认" else "已确认"} · ${save.relativePath}",
            modifier = Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (save.candidate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (game.saves.size > MAX_VISIBLE_LOCATIONS_PER_GAME) {
        Text(
            "还有 ${game.saves.size - MAX_VISIBLE_LOCATIONS_PER_GAME} 个位置未展开",
            modifier = Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.labelSmall,
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
        IconButton(
            onClick = whenIdle(!enabled, onRestore),
            modifier = Modifier.handheldFocusRing(CircleShape).busyDimmed(!enabled),
        ) {
            Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "恢复此版本")
        }
    }
}

internal const val AUTO_BACKUP_SWITCH_TAG = "auto-backup-switch"

private const val MAX_VISIBLE_LOCATIONS_PER_GAME = 24
private const val MAX_GAME_HISTORY_ROWS = 8

@OdinScreenPreviews
@Composable
private fun GameDetailPanePreview() {
    PreviewSurface {
        GameDetailPane(
            entry = PreviewData.switchEntry,
            presentation = PreviewData.switchEntry.game.presentation,
            localHistory = PreviewData.snapshots,
            remoteHistory = emptyList(),
            busy = false,
            gameSaveEnabled = true,
            batchChecked = true,
            batchEnabled = true,
            coverRepository = null,
            onBatchCheckedChange = {},
            onBackup = {},
            onRestoreLocal = {},
            onRestoreRemote = {},
            status = GameSyncStatus.NOT_UPLOADED,
            autoBackupIncluded = true,
        )
    }
}
