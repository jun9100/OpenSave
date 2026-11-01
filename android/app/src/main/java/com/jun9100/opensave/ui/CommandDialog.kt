package com.jun9100.opensave.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.jun9100.opensave.LibraryStatusFilter
import com.jun9100.opensave.ui.batch.BATCH_REPORT_LABEL
import com.jun9100.opensave.ui.batch.CANCEL_BATCH_LABEL
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

/**
 * Commands of the game library, short and grouped (plan §4.1): what the
 * moment calls for first (cancel the running batch, the main backup,
 * "同步与任务", the last report), then the selection and the other batch
 * operations; the filters sit in their own "筛选…" panel.
 */
internal fun libraryCommands(
    busy: Boolean,
    selectionMode: Boolean,
    selectedCount: Int,
    /** "全部 · 有变更": the current type and status filter, shown on "筛选…". */
    filterSummary: String,
    /** Games "同步与任务" lists as needing a decision. */
    syncAttention: Int,
    onScan: () -> Unit,
    selection: LibrarySelectionActions,
    /** The batch operations ([com.jun9100.opensave.ui.batch.batchCommands]) over the selection or the type filter. */
    batch: List<MenuEntry>,
    onOpenFilters: () -> Unit,
    onOpenSyncTasks: () -> Unit,
): List<MenuEntry> = buildList {
    val cancel = batch.filter { it.label == CANCEL_BATCH_LABEL }
    val report = batch.filter { it.label == BATCH_REPORT_LABEL && it.enabled }
    val operations = batch.filterNot { it.label == CANCEL_BATCH_LABEL || it.label == BATCH_REPORT_LABEL }
    // While a batch runs, cancelling it is what Y is pressed for: first, so it takes the initial focus.
    addAll(cancel)
    addAll(operations.take(1))
    add(syncTasksEntry(syncAttention, onOpenSyncTasks))
    addAll(report)
    if (selectionMode) {
        add(MenuEntry("全选当前筛选", detail = "已选 $selectedCount", enabled = !busy, onClick = selection.onSelectAll))
        add(MenuEntry("清除选择", enabled = !busy, onClick = selection.onClear))
        add(MenuEntry("退出选择模式", detail = "B 键也可退出", onClick = selection.onExit))
    }
    addAll(operations.drop(1))
    if (!selectionMode) {
        add(MenuEntry("进入选择模式", detail = "Select 键或长按游戏也可进入", enabled = !busy, onClick = selection.onEnter))
    }
    add(MenuEntry("筛选…", detail = filterSummary, enabled = !busy, onClick = onOpenFilters))
    add(MenuEntry("扫描游戏", detail = "重新扫描已选的存档来源", enabled = !busy, onClick = onScan))
}

/** The "筛选…" panel of the library: type, then status; the current choices are marked. */
internal fun libraryFilterCommands(
    busy: Boolean,
    typeOptions: List<Pair<String, String>>,
    selectedTypeId: String,
    selectedStatus: LibraryStatusFilter,
    onTypeSelected: (String) -> Unit,
    onStatusSelected: (LibraryStatusFilter) -> Unit,
): List<MenuEntry> = buildList {
    // A single "全部" option means there is nothing to filter by type.
    if (typeOptions.size > 1) {
        typeOptions.forEach { (id, label) ->
            add(MenuEntry("类型：$label", enabled = !busy, checked = id == selectedTypeId) { onTypeSelected(id) })
        }
    }
    LibraryStatusFilter.entries.forEach { filter ->
        add(MenuEntry("状态：${filter.label}", enabled = !busy, checked = filter == selectedStatus) { onStatusSelected(filter) })
    }
}

/** The entry that opens "同步与任务"; not an operation, so never disabled. */
internal fun syncTasksEntry(attention: Int, onClick: () -> Unit) = MenuEntry(
    SYNC_TASKS_LABEL,
    detail = if (attention > 0) "$attention 款需要确认" else "冲突、云端较新和批量报告",
    onClick = onClick,
)

/** What the selection controls of the toolbar and the command panel call; previews pass no-ops. */
internal class LibrarySelectionActions(
    val onEnter: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/** Commands of the 来源 page. */
internal fun sourcesCommands(
    busy: Boolean,
    onPickFolder: () -> Unit,
    onScan: () -> Unit,
    onRefresh: () -> Unit,
): List<MenuEntry> = listOf(
    MenuEntry("添加并授权目录", detail = "没有 Root 时用系统授权访问存档目录", enabled = !busy, onClick = onPickFolder),
    MenuEntry("重新扫描来源", enabled = !busy, onClick = onScan),
    MenuEntry("刷新来源", detail = "重新读取已保存的来源与 Root 状态", enabled = !busy, onClick = onRefresh),
)

/** Commands of the 设置 page. */
internal fun settingsCommands(
    busy: Boolean,
    syncAttention: Int,
    onOpenSyncTasks: () -> Unit,
    onCheckCloudUpdates: () -> Unit,
    onListCloud: () -> Unit,
    onRefresh: () -> Unit,
    /** Opens the last batch report; null while there is none. */
    onOpenBatchReport: (() -> Unit)? = null,
): List<MenuEntry> = listOfNotNull(
    syncTasksEntry(syncAttention, onOpenSyncTasks),
    MenuEntry("检查跨设备更新", detail = "只读取云端，不修改存档", enabled = !busy, onClick = onCheckCloudUpdates),
    onOpenBatchReport?.let { MenuEntry(BATCH_REPORT_LABEL, onClick = it) },
    MenuEntry("列举云端快照", enabled = !busy, onClick = onListCloud),
    MenuEntry("刷新配置", detail = "重新读取已保存的 game-save 配置", enabled = !busy, onClick = onRefresh),
)

/** Commands of "同步与任务". */
internal fun syncCommands(
    busy: Boolean,
    batchRunning: Boolean,
    onCancelBatch: () -> Unit,
    onCheckCloudUpdates: () -> Unit,
    onOpenBatchReport: (() -> Unit)?,
    onBack: () -> Unit,
): List<MenuEntry> = listOfNotNull(
    MenuEntry(CANCEL_BATCH_LABEL, detail = "当前这款完成后停止", onClick = onCancelBatch).takeIf { batchRunning },
    MenuEntry("检查全部游戏的云端更新", detail = "只读取云端，不修改存档", enabled = !busy, onClick = onCheckCloudUpdates),
    onOpenBatchReport?.let { MenuEntry(BATCH_REPORT_LABEL, onClick = it) },
    MenuEntry("返回", detail = "B 键也可返回", onClick = onBack),
)

internal const val SYNC_TASKS_LABEL = "同步与任务"

internal const val COMMAND_DIALOG_TAG = "command-dialog"
