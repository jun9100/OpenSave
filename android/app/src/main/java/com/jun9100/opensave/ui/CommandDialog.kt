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
 * "同步与任务", the last report), then the other batch operations; the
 * selection controls and the filters sit in their own "选择…" and "筛选…"
 * panels, so the panel stays at ten entries at most.
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
    /** Opens the "选择…" panel ([librarySelectionCommands]); shown in selection mode only. */
    onOpenSelection: () -> Unit = {},
): List<MenuEntry> = buildList {
    val cancel = batch.filter { it.label == CANCEL_BATCH_LABEL }
    val report = batch.filter { it.label == BATCH_REPORT_LABEL && it.enabled }
    val operations = batch.filterNot { it.label == CANCEL_BATCH_LABEL || it.label == BATCH_REPORT_LABEL }
    // While a batch runs, cancelling it is what Y is pressed for: first, so it takes the initial focus.
    addAll(cancel)
    addAll(operations.take(1))
    add(syncTasksEntry(syncAttention, onOpenSyncTasks))
    addAll(report)
    addAll(operations.drop(1))
    if (selectionMode) {
        // Never disabled: it holds "退出选择模式", which is not an operation.
        add(MenuEntry(SELECTION_MENU_LABEL, detail = "已选 $selectedCount · 全选、清除或退出", onClick = onOpenSelection))
    } else {
        add(MenuEntry("进入选择模式", detail = "Select 键或长按游戏也可进入", enabled = !busy, onClick = selection.onEnter))
    }
    add(MenuEntry("筛选…", detail = filterSummary, enabled = !busy, onClick = onOpenFilters))
    add(MenuEntry("扫描游戏", detail = "重新扫描已选的存档来源", enabled = !busy, onClick = onScan))
}

/** The "选择…" panel of the library in selection mode. */
internal fun librarySelectionCommands(
    busy: Boolean,
    selectedCount: Int,
    selection: LibrarySelectionActions,
): List<MenuEntry> = listOf(
    MenuEntry("全选当前筛选", detail = "已选 $selectedCount", enabled = !busy, onClick = selection.onSelectAll),
    MenuEntry("清除选择", enabled = !busy, onClick = selection.onClear),
    MenuEntry("退出选择模式", detail = "B 键或 Select 键也可退出", onClick = selection.onExit),
)

internal const val SELECTION_MENU_LABEL = "选择…"

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

/** Commands of the 来源 page: the two ways to add a source, then the staged changes and the scan. */
internal fun sourcesCommands(
    busy: Boolean,
    /** Staged source toggles not applied yet. */
    pendingChanges: Int,
    rootAvailable: Boolean,
    onPickFolder: () -> Unit,
    onAddRootPath: () -> Unit,
    onApply: () -> Unit,
    onScan: () -> Unit,
    onRefresh: () -> Unit,
): List<MenuEntry> = listOfNotNull(
    MenuEntry(
        "应用并重新扫描 ($pendingChanges)",
        detail = "保存勾选的改动并扫描一次",
        enabled = !busy,
        onClick = onApply,
    ).takeIf { pendingChanges > 0 },
    MenuEntry("添加授权目录", detail = "没有 Root 时用系统授权访问存档目录", enabled = !busy, onClick = onPickFolder),
    MenuEntry("添加 Root 路径", detail = "扫描 Root 才能读取的目录", enabled = !busy, onClick = onAddRootPath).takeIf { rootAvailable },
    MenuEntry("重新扫描来源", enabled = !busy, onClick = onScan),
    MenuEntry("刷新来源", detail = "重新读取已保存的来源与 Root 状态", enabled = !busy, onClick = onRefresh),
)

/** Commands of the 设置 page. */
internal fun settingsCommands(
    busy: Boolean,
    syncAttention: Int,
    onOpenSyncTasks: () -> Unit,
    onCheckCloudUpdates: () -> Unit,
    onEditCloud: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onRefresh: () -> Unit,
    /** Opens the last batch report; null while there is none. */
    onOpenBatchReport: (() -> Unit)? = null,
    /** Opens the first-run wizard again; null leaves it out. */
    onOpenWizard: (() -> Unit)? = null,
): List<MenuEntry> = listOfNotNull(
    syncTasksEntry(syncAttention, onOpenSyncTasks),
    MenuEntry("检查跨设备更新", detail = "只读取云端，不修改存档", enabled = !busy, onClick = onCheckCloudUpdates),
    onOpenBatchReport?.let { MenuEntry(BATCH_REPORT_LABEL, onClick = it) },
    MenuEntry("编辑云端配置…", detail = "服务地址和同步密钥，测试通过后保存", enabled = !busy, onClick = onEditCloud),
    MenuEntry("从剪贴板导入配置", detail = "opensave://game-save 链接", enabled = !busy, onClick = onImportFromClipboard),
    MenuEntry("刷新配置", detail = "重新读取已保存的 SaveVault 云端配置", enabled = !busy, onClick = onRefresh),
    onOpenWizard?.let { MenuEntry(WIZARD_LABEL, detail = "访问方式、授权目录和 SaveVault 云端", onClick = it) },
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

internal const val WIZARD_LABEL = "首次使用向导…"

internal const val COMMAND_DIALOG_TAG = "command-dialog"
