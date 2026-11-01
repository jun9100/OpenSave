package com.jun9100.opensave.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.jun9100.opensave.LibraryStatusFilter
import com.jun9100.opensave.ui.dialogs.HandheldMenuDialog
import com.jun9100.opensave.ui.dialogs.MenuEntry

/**
 * The Y command panel (plan §4.1): the page-wide actions that otherwise sit
 * in the toolbar, the rail or far down the settings, one D-pad press away.
 */
@Composable
internal fun CommandDialog(
    title: String,
    entries: List<MenuEntry>,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    HandheldMenuDialog(
        title = title,
        entries = entries,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(COMMAND_DIALOG_TAG),
        footer = if (busy) "正在处理，扫描和云端操作请稍后再试。" else null,
    )
}

/** Commands of the game library; operations that start work are unavailable while one runs. */
internal fun libraryCommands(
    busy: Boolean,
    typeOptions: List<Pair<String, String>>,
    selectedTypeId: String,
    selectedStatus: LibraryStatusFilter,
    selectionMode: Boolean,
    selectedCount: Int,
    onScan: () -> Unit,
    selection: LibrarySelectionActions,
    /** The batch operations ([com.jun9100.opensave.ui.batch.batchCommands]) over the selection or the type filter. */
    batch: List<MenuEntry>,
    onTypeSelected: (String) -> Unit,
    onStatusSelected: (LibraryStatusFilter) -> Unit,
    onOpenManagement: () -> Unit,
): List<MenuEntry> = buildList {
    add(MenuEntry("扫描游戏", detail = "重新扫描已选的存档来源", enabled = !busy, onClick = onScan))
    if (selectionMode) {
        add(MenuEntry("全选当前筛选", detail = "已选 $selectedCount", enabled = !busy, onClick = selection.onSelectAll))
        add(MenuEntry("清除选择", enabled = !busy, onClick = selection.onClear))
        add(MenuEntry("退出选择模式", detail = "B 键也可退出", onClick = selection.onExit))
    } else {
        add(MenuEntry("进入选择模式", detail = "Select 键或长按游戏也可进入", enabled = !busy, onClick = selection.onEnter))
    }
    addAll(batch)
    // A single "全部" option means there is nothing to filter.
    if (typeOptions.size > 1) {
        typeOptions.forEach { (id, label) ->
            add(MenuEntry("筛选：$label", enabled = !busy, checked = id == selectedTypeId) { onTypeSelected(id) })
        }
    }
    LibraryStatusFilter.entries.forEach { filter ->
        add(MenuEntry("状态：${filter.label}", enabled = !busy, checked = filter == selectedStatus) { onStatusSelected(filter) })
    }
    add(MenuEntry("前往 备份与设置", onClick = onOpenManagement))
}

/** What the selection controls of the toolbar and the command panel call; previews pass no-ops. */
internal class LibrarySelectionActions(
    val onEnter: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/** Commands of the backup and settings page. */
internal fun managementCommands(
    busy: Boolean,
    onRefresh: () -> Unit,
    onListCloud: () -> Unit,
    onCheckCloudUpdates: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenLibrary: () -> Unit,
    /** Opens the last batch report; null while there is none. */
    onOpenBatchReport: (() -> Unit)? = null,
): List<MenuEntry> = listOfNotNull(
    MenuEntry("刷新来源", detail = "重新读取已保存的来源与配置", enabled = !busy, onClick = onRefresh),
    MenuEntry("列举云端快照", enabled = !busy, onClick = onListCloud),
    MenuEntry("检查跨设备更新", enabled = !busy, onClick = onCheckCloudUpdates),
    MenuEntry("添加并授权目录", enabled = !busy, onClick = onPickFolder),
    onOpenBatchReport?.let { MenuEntry("最近的批量报告", onClick = it) },
    MenuEntry("前往 游戏库", onClick = onOpenLibrary),
)

internal const val COMMAND_DIALOG_TAG = "command-dialog"
