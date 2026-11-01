package com.jun9100.opensave.ui.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.SyncInbox
import com.jun9100.opensave.SyncInboxItem
import com.jun9100.opensave.SyncItemKind
import com.jun9100.opensave.batch.BatchReport
import com.jun9100.opensave.ui.formatTime
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.settings.HandheldPageColumns
import com.jun9100.opensave.ui.settings.settingButtonFocus
import com.jun9100.opensave.ui.syncCommands

/**
 * 同步与任务 (plan §3.1): the games whose cloud version needs a decision, each
 * with 用云端覆盖 / 保留本机并发布 / 稍后, on the left; the running or last
 * batch and the kept batch reports on the right. Every decision re-checks the
 * cloud first and asks for confirmation; nothing here is automatic (plan Q7).
 */
@Composable
internal fun SyncTasksPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    modifier: Modifier = Modifier,
) {
    val messages = state.messages[OpenSavePage.SYNC]
    val items = remember(
        state.sourceScans,
        state.snapshots,
        state.saveSignatures,
        state.cloudUpdates,
        state.rememberedCloudVerdicts,
        state.gameSaveEnabled,
        state.syncDismissed,
    ) { SyncInbox.fromState(state) }
    val cloudReady = state.gameSaveEnabled && state.gameSaveUrl.isNotBlank() && state.gameSaveSyncKey.isNotBlank()
    HandheldPageColumns(
        commandsTitle = "同步与任务命令",
        commands = {
            syncCommands(
                busy = state.busy,
                batchRunning = state.batch != null,
                onCancelBatch = viewModel::cancelBatch,
                onCheckCloudUpdates = { viewModel.checkCloudUpdates(OpenSavePage.SYNC) },
                onOpenBatchReport = viewModel::openBatchReport.takeIf { state.lastBatchReport != null },
                onBack = { viewModel.showPage(state.syncReturnPage) },
            )
        },
        busy = state.busy,
        modifier = modifier.testTag(SYNC_TASKS_TAG),
        left = { firstControl ->
            Text("待处理", style = MaterialTheme.typography.titleMedium)
            Text(
                if (cloudReady) "game-save 已启用 · 处理前都会重新检查云端" else "game-save 未启用；请先在“设置”中保存并启用",
                style = MaterialTheme.typography.bodySmall,
                color = if (cloudReady) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            messages.action?.takeIf { !state.busy }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            messages.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(
                onClick = whenIdle(state.busy) { viewModel.checkCloudUpdates(OpenSavePage.SYNC) },
                modifier = Modifier.fillMaxWidth().focusRequester(firstControl).settingButtonFocus().busyDimmed(state.busy),
            ) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Text("检查全部游戏的云端更新", modifier = Modifier.padding(start = 6.dp))
            }
            if (items.isEmpty()) {
                Text(
                    if (state.syncDismissed.isEmpty()) "没有需要处理的冲突或云端更新。" else "没有需要处理的项目；标为“稍后”的游戏在重启应用后再次出现。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items.forEach { item ->
                SyncItemCard(
                    item = item,
                    busy = state.busy,
                    onUseCloud = { viewModel.requestSyncCloudRestore(item.key) },
                    onKeepLocal = { viewModel.requestKeepLocal(item.key) },
                    onLater = { viewModel.dismissSyncItem(item.key) },
                )
            }
        },
        right = { firstControl ->
            Text("任务", style = MaterialTheme.typography.titleMedium)
            val running = state.batch
            if (running != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("正在运行", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(running.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(
                            onClick = viewModel::cancelBatch,
                            modifier = Modifier.focusRequester(firstControl).settingButtonFocus(),
                        ) { Text(if (running.cancelRequested) "正在取消…" else "取消") }
                    }
                }
            }
            Text(
                "最近的批量报告",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state.batchReports.isEmpty()) {
                Text(
                    "还没有批量任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.batchReports.forEachIndexed { index, report ->
                ReportRow(
                    report = report,
                    onOpen = { viewModel.openStoredBatchReport(report) },
                    focusRequester = firstControl.takeIf { index == 0 && running == null },
                )
            }
        },
    )
}

@Composable
private fun SyncItemCard(
    item: SyncInboxItem,
    busy: Boolean,
    onUseCloud: () -> Unit,
    onKeepLocal: () -> Unit,
    onLater: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.entry.game.presentation.primaryName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(if (item.kind == SyncItemKind.CONFLICT) "⚠ " else "▼ ")
                    append(item.kind.label)
                    append(" · ")
                    append(item.entry.sourceLabel)
                    item.deviceName.takeIf(String::isNotBlank)?.let { append(" · 来自 ").append(it) }
                    item.receivedAtMillis.takeIf { it > 0L }?.let { append(" · ").append(formatTime(it)) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (item.kind == SyncItemKind.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = whenIdle(busy, onUseCloud),
                    contentPadding = ITEM_BUTTON_PADDING,
                    modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(busy),
                ) { Text("用云端覆盖…", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                OutlinedButton(
                    onClick = whenIdle(busy, onKeepLocal),
                    contentPadding = ITEM_BUTTON_PADDING,
                    modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(busy),
                ) { Text("保留本机并发布…", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Only hides the item for this session: never refused while busy.
                TextButton(onClick = onLater, modifier = Modifier.settingButtonFocus()) { Text("稍后") }
            }
        }
    }
}

@Composable
private fun ReportRow(report: BatchReport, onOpen: () -> Unit, focusRequester: FocusRequester?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .handheldFocusRing(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(report.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            formatTime(report.finishedAtMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ITEM_BUTTON_PADDING = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

internal const val SYNC_TASKS_TAG = "sync-tasks"
