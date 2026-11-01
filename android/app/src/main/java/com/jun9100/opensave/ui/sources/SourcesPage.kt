package com.jun9100.opensave.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.settings.HandheldPageColumns
import com.jun9100.opensave.ui.settings.SourceSelectionCard
import com.jun9100.opensave.ui.settings.settingButtonFocus
import com.jun9100.opensave.ui.sourceAccessLabel
import com.jun9100.opensave.ui.sourcesCommands

/**
 * 来源 (plan §3.1): where saves are read from. The source choice and the
 * folders granted through the system picker (the only way in without Root)
 * on the left; what the last scan found per source on the right.
 */
@Composable
internal fun SourcesPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = state.messages[OpenSavePage.SOURCES]
    HandheldPageColumns(
        commandsTitle = "来源命令",
        commands = {
            sourcesCommands(
                busy = state.busy,
                onPickFolder = onPickFolder,
                onScan = viewModel::scanSource,
                onRefresh = viewModel::refreshPersistedSource,
            )
        },
        busy = state.busy,
        modifier = modifier,
        left = { firstControl ->
            messages.action?.takeIf { !state.busy }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            messages.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SourceSelectionCard(state, viewModel, onPickFolder, initialFocus = firstControl)
        },
        right = { firstControl ->
            ScanResultCard(state = state, onScan = viewModel::scanSource, focusRequester = firstControl)
        },
    )
}

/** One line per scanned source: access and what was found, or why it was skipped. */
@Composable
private fun ScanResultCard(state: SourceUiState, onScan: () -> Unit, focusRequester: FocusRequester) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("扫描结果", style = MaterialTheme.typography.titleMedium)
            val readable = state.sourceScans.count { it.access.readable }
            Text(
                if (state.sourceScans.isEmpty()) {
                    "尚未扫描"
                } else {
                    "$readable 个来源可读 · 跳过 ${state.sourceScans.size - readable} 个"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = whenIdle(state.busy, onScan),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).settingButtonFocus().busyDimmed(state.busy),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text("重新扫描", modifier = Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.height(4.dp))
            // Readable sources first: they are what the library shows.
            state.sourceScans.sortedByDescending { it.access.readable }.forEach { source ->
                val detail = source.scan?.let { scan -> "${scan.gameCount} 款已识别 · ${scan.detected.size} 个位置" }
                    ?: "跳过 · ${source.skippedReason}"
                Text(
                    "${source.label} · ${sourceAccessLabel(source.access)} · $detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (source.access.readable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
