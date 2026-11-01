package com.jun9100.opensave.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.batch.BatchOperation
import com.jun9100.opensave.batch.BatchProgress
import com.jun9100.opensave.batch.BatchReport
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * What the status strip shows about batches: the running one with its
 * cancel button, or the report of the last one until it is looked at.
 * Provided once by the screen, so every page's strip shows the same.
 */
@Stable
internal class BatchStripState(
    val batch: BatchProgress? = null,
    /** The finished report offered until it is opened or dismissed. */
    val report: BatchReport? = null,
    val onCancel: () -> Unit = {},
    val onOpenReport: () -> Unit = {},
    val onDismissReport: () -> Unit = {},
)

internal val LocalBatchStrip = compositionLocalOf { BatchStripState() }

/**
 * The running batch in the status strip area (plan §5.6): name, k/N and the
 * current game over a progress line, and "取消", which is never blocked by
 * busy and is one D-pad stop.
 */
@Composable
internal fun BatchProgressBar(
    progress: BatchProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StripSurface(modifier.testTag(BATCH_PROGRESS_TAG)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                progress.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        TextButton(
            onClick = onCancel,
            enabled = !progress.cancelRequested,
            modifier = Modifier.padding(start = 8.dp).height(30.dp).handheldFocusRing(RoundedCornerShape(16.dp)),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(if (progress.cancelRequested) "正在取消" else "取消", maxLines = 1)
        }
    }
}

/** The last batch's summary with "查看报告" and a close button, shown after it finished. */
@Composable
internal fun BatchReportStrip(
    report: BatchReport,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StripSurface(modifier.testTag(BATCH_REPORT_STRIP_TAG)) {
        Text(
            report.summary,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onOpen,
            modifier = Modifier.height(30.dp).handheldFocusRing(RoundedCornerShape(16.dp)),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text("查看报告", maxLines = 1)
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(30.dp).handheldFocusRing(CircleShape),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭批量结果", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StripSurface(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BATCH_STRIP_HEIGHT).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private val BATCH_STRIP_HEIGHT = 34.dp

internal const val BATCH_PROGRESS_TAG = "batch-progress"
internal const val BATCH_REPORT_STRIP_TAG = "batch-report-strip"

@ComponentPreviews
@Composable
private fun BatchProgressBarPreview() {
    PreviewSurface {
        Column {
            BatchProgressBar(
                progress = BatchProgress("p", BatchOperation.UPLOAD_PENDING, total = 12, done = 3, currentName = "塞尔达传说 王国之泪"),
                onCancel = {},
            )
            BatchReportStrip(
                report = PreviewBatchReport,
                onOpen = {},
                onDismiss = {},
            )
        }
    }
}
