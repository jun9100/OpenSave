package com.jun9100.opensave.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.jun9100.opensave.ui.batch.BatchProgressBar
import com.jun9100.opensave.ui.batch.BatchReportStrip
import com.jun9100.opensave.ui.batch.LocalBatchStrip
import com.jun9100.opensave.ui.input.BusyNotice
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * The one progress line of the app, at the bottom of every page. It only takes
 * space while an operation runs or a finished batch report waits to be seen,
 * so the list keeps its height otherwise.
 */
@Composable
internal fun StatusStrip(
    busy: Boolean,
    progress: String?,
    modifier: Modifier = Modifier,
) {
    // A batch replaces the plain line with its progress and "取消"; once it
    // finished, its report is offered here until looked at (plan §5.6).
    val batches = LocalBatchStrip.current
    batches.batch?.let { running ->
        BatchProgressBar(running, onCancel = batches.onCancel, modifier = modifier)
        return
    }
    if (!busy) {
        batches.report?.let { report ->
            BatchReportStrip(report, onOpen = batches.onOpenReport, onDismiss = batches.onDismissReport, modifier = modifier)
        }
        return
    }
    // A press refused while busy (whenIdle) swaps the progress for a short notice.
    val blocked by BusyNotice.blockedCount.collectAsState()
    val blockedOnShow = remember { blocked }
    var showNotice by remember { mutableStateOf(false) }
    LaunchedEffect(blocked) {
        if (blocked == blockedOnShow) return@LaunchedEffect
        showNotice = true
        delay(BusyNotice.SHOW_MILLIS)
        showNotice = false
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(STATUS_STRIP_HEIGHT).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                if (showNotice) BusyNotice.TEXT else progress?.takeIf(String::isNotBlank) ?: "正在处理",
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (showNotice) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val STATUS_STRIP_HEIGHT = 34.dp

@ComponentPreviews
@Composable
private fun StatusStripPreview() {
    PreviewSurface {
        StatusStrip(busy = true, progress = "批量备份 3/12 · 塞尔达传说 王国之泪")
    }
}
