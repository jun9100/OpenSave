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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CoverRepository
import com.jun9100.opensave.GamePresentation
import com.jun9100.opensave.GameSyncStatus
import com.jun9100.opensave.LibraryRowHistory
import com.jun9100.opensave.LibraryView
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.ui.formatShortTime
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * One compact library row (60dp, plan §3.4) with its status badge at the end.
 * The whole row is the single focus stop: click shows the game in the detail
 * pane or page (in selection mode the caller makes it toggle instead), long
 * press selects it and enters selection mode. The checkbox only shows in
 * selection mode; it stays tappable by touch but is skipped by D-pad, pad
 * users toggle with A or Select.
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
    /** Null while the status is not known yet (nothing scanned). */
    status: GameSyncStatus?,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onBatchCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Called when the row gains D-pad focus; null leaves focus without effect. */
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    /** Save locations of this game in different sources; more than one replaces the source label. */
    locationCount: Int = 1,
) {
    val selectable = batchEligible && batchEnabled
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GAME_ROW_HEIGHT)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .handheldFocusRing(RectangleShape, outset = (-2).dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (selectable) ({ onBatchCheckedChange(!batchChecked) }) else null,
                onLongClickLabel = if (batchChecked) "取消选择" else "选择",
            )
            .padding(start = if (selectionMode) 0.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = batchChecked,
                onCheckedChange = onBatchCheckedChange,
                enabled = selectable,
                modifier = Modifier.focusProperties { canFocus = false },
            )
        }
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
                LibraryView.rowSecondary(
                    if (locationCount > 1) "$locationCount 个存档位置" else entry.sourceLabel,
                    history,
                    batchEligible,
                    ::formatShortTime,
                ),
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (batchEligible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        status?.let { GameStatusBadge(it, Modifier.padding(start = 8.dp), short = true) }
    }
}

/** "● 已同步"-style status label; [short] is the row badge, otherwise the detail wording. */
@Composable
internal fun GameStatusBadge(
    status: GameSyncStatus,
    modifier: Modifier = Modifier,
    short: Boolean = false,
    /** Overrides the enum wording where only the caller knows the detail (e.g. a merged successor). */
    label: String? = null,
) {
    Text(
        "${status.symbol} ${label ?: if (short) status.shortLabel else status.label}",
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = statusColor(status),
        maxLines = 1,
    )
}

@Composable
private fun statusColor(status: GameSyncStatus): Color = when (status) {
    GameSyncStatus.SYNCED, GameSyncStatus.LOCAL_ONLY -> MaterialTheme.colorScheme.primary
    GameSyncStatus.CLOUD_NEWER -> MaterialTheme.colorScheme.primary
    GameSyncStatus.LOCAL_CHANGED -> MaterialTheme.colorScheme.tertiary
    // A stopped scope is a state to review, not an error of this device.
    GameSyncStatus.CLOUD_DELETED, GameSyncStatus.CLOUD_MERGED -> MaterialTheme.colorScheme.tertiary
    GameSyncStatus.NOT_UPLOADED -> MaterialTheme.colorScheme.secondary
    GameSyncStatus.NEVER_BACKED_UP -> MaterialTheme.colorScheme.onSurfaceVariant
    GameSyncStatus.CONFLICT -> MaterialTheme.colorScheme.error
}

/** Where the row text starts: the divider under a row is indented this far. */
internal fun gameRowTextStart(selectionMode: Boolean) = if (selectionMode) 98.dp else 62.dp

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
                status = GameSyncStatus.SYNCED,
                selectionMode = true,
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
                status = GameSyncStatus.NEVER_BACKED_UP,
                selectionMode = true,
                onClick = {},
                onBatchCheckedChange = {},
            )
        }
    }
}
