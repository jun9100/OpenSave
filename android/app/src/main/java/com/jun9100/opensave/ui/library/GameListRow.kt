package com.jun9100.opensave.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GamePresentation
import com.jun9100.opensave.LibraryRowHistory
import com.jun9100.opensave.LibraryView
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.ui.formatShortTime
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * One compact library row (60dp, plan §3.4). The whole row is the single focus
 * stop: click shows the game in the detail pane or page, long press toggles the
 * batch selection. The checkbox stays tappable by touch but is skipped by D-pad.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameListRow(
    entry: SourceGameEntry,
    presentation: GamePresentation,
    history: LibraryRowHistory?,
    coverRepository: CoverRepository?,
    batchChecked: Boolean,
    /** Whether batch backup takes this game at all (confirmed locations). */
    batchEligible: Boolean,
    /** Whether the selection can change right now (nothing is running). */
    batchEnabled: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onBatchCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Called when the row gains D-pad focus; null leaves focus without effect. */
    onFocused: (() -> Unit)? = null,
) {
    val selectable = batchEligible && batchEnabled
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GAME_ROW_HEIGHT)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (selectable) ({ onBatchCheckedChange(!batchChecked) }) else null,
                onLongClickLabel = if (batchChecked) "移出批量选择" else "加入批量选择",
            )
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = batchChecked,
            onCheckedChange = onBatchCheckedChange,
            enabled = selectable,
            modifier = Modifier.focusProperties { canFocus = false },
        )
        if (coverRepository != null) {
            GameArtwork(presentation = presentation, coverRepository = coverRepository, modifier = Modifier.size(40.dp))
        } else {
            GameIcon(presentation.iconKey, Modifier.size(40.dp))
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                presentation.primaryName,
                style = MaterialTheme.typography.titleSmall,
                color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                LibraryView.rowSecondary(entry.sourceLabel, history, batchEligible, ::formatShortTime),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (batchEligible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal val GAME_ROW_HEIGHT = 60.dp

@ComponentPreviews
@Composable
private fun GameListRowPreview() {
    PreviewSurface {
        Column {
            GameListRow(
                entry = PreviewData.switchEntry,
                presentation = PreviewData.switchEntry.game.presentation,
                history = LibraryRowHistory(localCount = 3, lastBackupAtMillis = 1_793_000_000_000, latestUploaded = true),
                coverRepository = null,
                batchChecked = true,
                batchEligible = true,
                batchEnabled = true,
                current = true,
                onClick = {},
                onBatchCheckedChange = {},
            )
            GameListRow(
                entry = PreviewData.pcEntry,
                presentation = PreviewData.pcEntry.game.presentation,
                history = null,
                coverRepository = null,
                batchChecked = false,
                batchEligible = true,
                batchEnabled = true,
                current = false,
                onClick = {},
                onBatchCheckedChange = {},
            )
        }
    }
}
