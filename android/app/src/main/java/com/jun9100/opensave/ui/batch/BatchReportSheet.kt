package com.jun9100.opensave.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.batch.BatchItemResult
import com.jun9100.opensave.batch.BatchItemStatus
import com.jun9100.opensave.batch.BatchOperation
import com.jun9100.opensave.batch.BatchReport
import com.jun9100.opensave.ui.dialogs.dialogButtonFocus
import com.jun9100.opensave.ui.formatTime
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.dpadScrollable
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.preview.OdinScreenPreviews

/**
 * The last batch report (plan §5.6), as a plain Dialog like the pad menus:
 * the summary with only the non-zero counts, then every game grouped by
 * result, failures first. "重试失败项" runs the same operation again on the
 * failed games only; focus starts on "关闭" and D-pad up/down scrolls.
 */
@Composable
internal fun BatchReportSheet(
    report: BatchReport,
    busy: Boolean,
    onRetryFailed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val failed = report.count(BatchItemStatus.FAILED)
    Dialog(onDismissRequest = onDismiss) {
        val initialFocus = rememberInitialFocus()
        Surface(
            modifier = Modifier.widthIn(min = 300.dp, max = 520.dp).consumeHandheldShortcuts().testTag(BATCH_REPORT_TAG),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)) {
                Text(
                    "${report.operation.label}报告",
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${formatTime(report.finishedAtMillis)} · ${report.summary}",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = REPORT_LIST_MAX_HEIGHT)
                        .dpadScrollable(scroll)
                        .verticalScroll(scroll)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    report.groups().forEach { (status, items) ->
                        Text(
                            "${report.operation.statusLabel(status)} (${items.size})",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor(status),
                        )
                        items.forEach { item -> ReportLine(item) }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (failed > 0) {
                        TextButton(
                            onClick = whenIdle(busy, onRetryFailed),
                            modifier = Modifier.dialogButtonFocus().busyDimmed(busy),
                        ) { Text("重试失败项 ($failed)") }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(initialFocus).dialogButtonFocus(),
                    ) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun ReportLine(item: BatchItemResult) {
    Text(
        if (item.detail.isBlank()) item.name else "${item.name} · ${item.detail}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun statusColor(status: BatchItemStatus): Color = when (status) {
    BatchItemStatus.FAILED -> MaterialTheme.colorScheme.error
    BatchItemStatus.NEEDS_REVIEW -> MaterialTheme.colorScheme.tertiary
    BatchItemStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val REPORT_LIST_MAX_HEIGHT = 260.dp

internal const val BATCH_REPORT_TAG = "batch-report"

internal val PreviewBatchReport = BatchReport(
    id = "preview",
    operation = BatchOperation.UPLOAD_PENDING,
    startedAtMillis = 1_761_800_000_000,
    finishedAtMillis = 1_761_800_090_000,
    items = listOf(
        BatchItemResult("a", "塞尔达传说 王国之泪", BatchItemStatus.SUCCESS),
        BatchItemResult("b", "宝可梦 朱", BatchItemStatus.FAILED, "云端原生同步失败：HTTP 502"),
        BatchItemResult("c", "Hades", BatchItemStatus.SKIPPED, "存档在备份后又有变更，请先备份"),
        BatchItemResult("d", "星露谷物语", BatchItemStatus.UNCHANGED, "最新版本已在云端"),
    ),
)

@OdinScreenPreviews
@Composable
private fun BatchReportSheetPreview() {
    OpenSaveTheme {
        BatchReportSheet(report = PreviewBatchReport, busy = false, onRetryFailed = {}, onDismiss = {})
    }
}
