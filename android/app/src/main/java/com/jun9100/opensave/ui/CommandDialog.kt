package com.jun9100.opensave.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    selectedCount: Int,
    eligibleCount: Int,
    onScan: () -> Unit,
    onToggleAll: () -> Unit,
    onCheckCloudUpdates: () -> Unit,
    onTypeSelected: (String) -> Unit,
    onOpenManagement: () -> Unit,
): List<MenuEntry> = buildList {
    add(MenuEntry("扫描游戏", detail = "重新扫描已选的存档来源", enabled = !busy, onClick = onScan))
    if (eligibleCount > 0) {
        val allSelected = selectedCount == eligibleCount
        add(
            MenuEntry(
                if (allSelected) "取消全选" else "全选当前筛选",
                detail = "已选 $selectedCount/$eligibleCount",
                enabled = !busy,
                onClick = onToggleAll,
            ),
        )
    }
    add(MenuEntry("检查跨设备更新", detail = "结果显示在“备份与设置”", enabled = !busy, onClick = onCheckCloudUpdates))
    // A single "全部" option means there is nothing to filter.
    if (typeOptions.size > 1) {
        typeOptions.forEach { (id, label) ->
            add(MenuEntry("筛选：$label", enabled = !busy, checked = id == selectedTypeId) { onTypeSelected(id) })
        }
    }
    add(MenuEntry("前往 备份与设置", onClick = onOpenManagement))
}

/** Commands of the backup and settings page. */
internal fun managementCommands(
    busy: Boolean,
    onRefresh: () -> Unit,
    onListCloud: () -> Unit,
    onCheckCloudUpdates: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenLibrary: () -> Unit,
): List<MenuEntry> = listOf(
    MenuEntry("刷新来源", detail = "重新读取已保存的来源与配置", enabled = !busy, onClick = onRefresh),
    MenuEntry("列举云端快照", enabled = !busy, onClick = onListCloud),
    MenuEntry("检查跨设备更新", enabled = !busy, onClick = onCheckCloudUpdates),
    MenuEntry("添加并授权目录", enabled = !busy, onClick = onPickFolder),
    MenuEntry("前往 游戏库", onClick = onOpenLibrary),
)

internal const val COMMAND_DIALOG_TAG = "command-dialog"
