package com.jun9100.opensave.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.jun9100.opensave.SnapshotRecord
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.ui.dialogs.HandheldMenuDialog
import com.jun9100.opensave.ui.dialogs.MenuEntry

/**
 * The X menu of one game (plan §4.1, browse mode): everything the detail pane
 * offers, reachable from the list row without moving focus.
 */
@Composable
internal fun GameActionMenu(
    entry: SourceGameEntry,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
) {
    HandheldMenuDialog(
        title = entry.game.presentation.primaryName,
        subtitle = "${entry.sourceLabel} · ${entry.saveType.label}",
        entries = entries,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(GAME_ACTION_MENU_TAG),
    )
}

/**
 * Entries of the X menu. [latestLocal] is the newest backup of the game (not a
 * safety copy); restoring it still goes through the restore confirmation.
 */
internal fun gameMenuEntries(
    busy: Boolean,
    batchEligible: Boolean,
    batchChecked: Boolean,
    latestLocal: SnapshotRecord?,
    versionCount: Int,
    locationCount: Int,
    onBackup: () -> Unit,
    onToggleBatch: () -> Unit,
    onRestoreLatest: (SnapshotRecord) -> Unit,
    onShowVersions: () -> Unit,
    onShowLocations: () -> Unit,
    /** Whether automatic backup covers this game; null hides the entry (not an eligible game). */
    autoBackupIncluded: Boolean? = null,
    /** False while automatic backup is off for the whole app; the entry says so. */
    autoBackupScheduled: Boolean = true,
    onToggleAutoBackup: () -> Unit = {},
): List<MenuEntry> = buildList {
    add(MenuEntry("备份这款游戏", enabled = !busy, onClick = onBackup))
    add(
        MenuEntry(
            if (batchChecked) "移出批量选择" else "加入批量选择",
            detail = if (batchEligible) "Select 键也可切换" else "仅待确认位置，不参与批量备份",
            enabled = batchEligible && !busy,
            onClick = onToggleBatch,
        ),
    )
    latestLocal?.let { record ->
        add(
            MenuEntry(
                "恢复最近的本地版本…",
                detail = formatTime(record.createdAtMillis),
                enabled = !busy,
            ) { onRestoreLatest(record) },
        )
    }
    autoBackupIncluded?.let { included ->
        add(
            MenuEntry(
                if (included) "关闭这款游戏的自动备份" else "开启这款游戏的自动备份",
                detail = if (autoBackupScheduled) "只影响本设备" else "自动备份总开关未开启（备份与设置）",
                onClick = onToggleAutoBackup,
            ),
        )
    }
    add(MenuEntry("查看版本 ($versionCount)", onClick = onShowVersions))
    add(MenuEntry("查看存档位置 ($locationCount)", onClick = onShowLocations))
}

internal const val GAME_ACTION_MENU_TAG = "game-action-menu"
