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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * The one progress line of the app, at the bottom of every page. It only takes
 * space while an operation runs, so the list keeps its height otherwise.
 */
@Composable
internal fun StatusStrip(
    busy: Boolean,
    progress: String?,
    modifier: Modifier = Modifier,
) {
    if (!busy) return
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
                progress?.takeIf(String::isNotBlank) ?: "正在处理",
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.bodySmall,
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
