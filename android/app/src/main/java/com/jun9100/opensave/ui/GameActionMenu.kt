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
    /** Opens the rename dialog; null hides the entry. */
    onRename: (() -> Unit)? = null,
    /** Opens the photo picker for a cover; null hides the entry. */
    onChangeCover: (() -> Unit)? = null,
    /** Drops the picked cover; null (no picked cover) hides the entry. */
    onResetCover: (() -> Unit)? = null,
    /** Takes an app the user had marked as a game back out of the library; null hides the entry. */
    onNotAGame: (() -> Unit)? = null,
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
                detail = if (autoBackupScheduled) "只影响本设备" else "自动备份总开关未开启（设置）",
                onClick = onToggleAutoBackup,
            ),
        )
    }
    add(MenuEntry("查看版本 ($versionCount)", onClick = onShowVersions))
    add(MenuEntry("查看存档位置 ($locationCount)", onClick = onShowLocations))
    onRename?.let { add(MenuEntry("改名…", detail = "未识别的游戏可以手动命名", onClick = it)) }
    onChangeCover?.let { add(MenuEntry("换封面…", detail = "从相册选一张图，只用于本设备", onClick = it)) }
    onResetCover?.let { add(MenuEntry("恢复默认封面", onClick = it)) }
    onNotAGame?.let { add(MenuEntry("不是游戏", detail = "移出游戏库，回到“来源”页的待确认应用", enabled = !busy, onClick = it)) }
}

internal const val GAME_ACTION_MENU_TAG = "game-action-menu"
