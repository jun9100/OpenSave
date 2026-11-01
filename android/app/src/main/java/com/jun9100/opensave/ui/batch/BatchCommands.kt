package com.jun9100.opensave.ui.batch

import com.jun9100.opensave.PrimaryBackup
import com.jun9100.opensave.ui.dialogs.MenuEntry

/**
 * What the batch operations of plan §5.4 would take in the current scope (the
 * selection in selection mode, else the games of the type filter); counted by
 * the library from the statuses.
 */
internal data class BatchScope(
    /** "已选 3 款" / "全部游戏" / "当前类型". */
    val label: String,
    val keys: List<String>,
    val pendingUpload: Int,
    val restorable: Int,
    val conflicts: Int,
)

/** Callbacks of the batch entries; previews and tests pass no-ops. */
internal class BatchMenuActions(
    val onBackup: () -> Unit = {},
    val onUploadPending: () -> Unit = {},
    val onCheckCloud: () -> Unit = {},
    val onRestoreCloudNewer: () -> Unit = {},
    val onCancelBatch: () -> Unit = {},
    val onOpenReport: () -> Unit = {},
)

/**
 * The batch entries of the toolbar's "批量" menu and the Y command panel.
 * Starting one is unavailable while anything runs; cancelling the running
 * batch and opening the last report always are.
 */
internal fun batchCommands(
    busy: Boolean,
    batchRunning: Boolean,
    scope: BatchScope,
    primary: PrimaryBackup,
    cloudReady: Boolean,
    hasReport: Boolean,
    actions: BatchMenuActions,
): List<MenuEntry> = buildList {
    if (batchRunning) add(MenuEntry("取消当前批量任务", detail = "当前这款完成后停止", onClick = actions.onCancelBatch))
    add(MenuEntry(primary.label, detail = "不弹确认；可在进度条上取消", enabled = !busy && primary.enabled, onClick = actions.onBackup))
    val cloudDetail: (String) -> String = { detail -> if (cloudReady) detail else "需先在“备份与设置”中启用 game-save" }
    add(
        MenuEntry(
            "上传待传 (${scope.pendingUpload})",
            detail = cloudDetail("${scope.label}中未上传的最新版本"),
            enabled = !busy && cloudReady && scope.pendingUpload > 0,
            onClick = actions.onUploadPending,
        ),
    )
    add(
        MenuEntry(
            "检查云端更新 (${scope.keys.size})",
            detail = cloudDetail("只读取云端，不修改存档"),
            enabled = !busy && cloudReady && scope.keys.isNotEmpty(),
            onClick = actions.onCheckCloud,
        ),
    )
    add(
        MenuEntry(
            "安全恢复云端较新 (${scope.restorable})…",
            detail = cloudDetail(
                if (scope.conflicts > 0) "冲突 ${scope.conflicts} 款需逐个确认，不会处理" else "恢复前先保存安全快照",
            ),
            enabled = !busy && cloudReady && scope.restorable > 0,
            onClick = actions.onRestoreCloudNewer,
        ),
    )
    add(MenuEntry("最近的批量报告", enabled = hasReport, onClick = actions.onOpenReport))
}
