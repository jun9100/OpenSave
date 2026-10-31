package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.ui.CommandDialog
import com.jun9100.opensave.ui.LocalAdaptiveLayout
import com.jun9100.opensave.ui.input.HandheldCommand
import com.jun9100.opensave.ui.input.HandheldCommandEffect
import com.jun9100.opensave.ui.input.LocalShellFocus
import com.jun9100.opensave.ui.input.RightStickScroll
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldNavigationActive
import com.jun9100.opensave.ui.input.tryRequestFocus
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.input.whenIdleWith
import com.jun9100.opensave.ui.managementCommands
import kotlinx.coroutines.launch

/**
 * Backup and settings. Pad: the first setting gets focus when the page opens,
 * each landscape column is a focus group that remembers its last control, Y
 * opens the command panel and L2/R2 or the right stick scroll the column that
 * holds focus.
 */
@Composable
internal fun SourceManagementPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = state.messages[OpenSavePage.MANAGEMENT]
    val messageLines: @Composable () -> Unit = {
        messages.action?.takeIf { !state.busy }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        messages.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    val landscape = LocalAdaptiveLayout.current.isLandscape
    val handheld = handheldNavigationActive()
    val shellFocus = LocalShellFocus.current
    val scope = rememberCoroutineScope()
    val firstControl = remember { FocusRequester() }
    // Landscape: focus that drops in the right column goes back to its top, not across to the left.
    val rightFirstControl = remember { FocusRequester() }
    var lastFocusRight by remember { mutableStateOf(false) }
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    var rightHasFocus by remember { mutableStateOf(false) }
    var commandsOpen by rememberSaveable { mutableStateOf(false) }
    val activeScroll = if (landscape && rightHasFocus) rightScroll else leftScroll

    LaunchedEffect(handheld, shellFocus.hasFocus, commandsOpen) {
        if (!handheld || shellFocus.hasFocus || commandsOpen) return@LaunchedEffect
        withFrameNanos { }
        val restored = landscape && lastFocusRight && rightFirstControl.tryRequestFocus()
        if (!restored) firstControl.tryRequestFocus()
    }
    HandheldCommandEffect { command ->
        when (command) {
            HandheldCommand.COMMAND_PANEL -> commandsOpen = true
            HandheldCommand.PAGE_UP, HandheldCommand.PAGE_DOWN -> scope.launch {
                val step = activeScroll.viewportSize * PAGE_SCROLL_FRACTION
                activeScroll.animateScrollBy(if (command == HandheldCommand.PAGE_DOWN) step else -step)
            }
            else -> Unit
        }
    }
    RightStickScroll(activeScroll)
    if (commandsOpen) {
        CommandDialog(
            title = "备份与设置命令",
            entries = managementCommands(
                busy = state.busy,
                onRefresh = viewModel::refreshPersistedSource,
                onListCloud = viewModel::listCloud,
                onCheckCloudUpdates = viewModel::checkCloudUpdates,
                onPickFolder = onPickFolder,
                onOpenLibrary = { viewModel.showPage(OpenSavePage.LIBRARY) },
            ),
            busy = state.busy,
            onDismiss = { commandsOpen = false },
        )
    }

    if (landscape) {
        // Two independently scrolling columns instead of one ~1500dp strip:
        // cloud, whole-source snapshot and results on the left; sources and
        // snapshot lists on the right.
        Row(
            modifier = modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { if (it.hasFocus) lastFocusRight = false }
                    .focusRestorer()
                    .focusGroup()
                    .verticalScroll(leftScroll)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                messageLines()
                GameSaveCard(state, viewModel, initialFocus = firstControl)
                CloudUpdateCard(state, viewModel)
                WholeSourceSnapshotCard(state, viewModel)
                state.report?.let { ReportCard(it) }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged {
                        rightHasFocus = it.hasFocus
                        if (it.hasFocus) lastFocusRight = true
                    }
                    .focusRestorer()
                    .focusGroup()
                    .verticalScroll(rightScroll)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SourceSelectionCard(state, viewModel, onPickFolder, initialFocus = rightFirstControl)
                LocalSnapshotCard(state, viewModel)
                RemoteSnapshotCard(state, viewModel)
            }
        }
    } else {
        Column(
            modifier = modifier.verticalScroll(leftScroll).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GameSaveCard(state, viewModel, initialFocus = firstControl)
            CloudUpdateCard(state, viewModel)
            SourceSelectionCard(state, viewModel, onPickFolder)
            WholeSourceSnapshotCard(state, viewModel)
            LocalSnapshotCard(state, viewModel)
            RemoteSnapshotCard(state, viewModel)
            messageLines()
            state.report?.let { ReportCard(it) }
        }
    }
}

private const val PAGE_SCROLL_FRACTION = 0.85f

@Composable
private fun WholeSourceSnapshotCard(state: SourceUiState, viewModel: SourceViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("整来源快照", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("为当前选定目录创建 OpenSave ZIP 快照；游戏库中的单游戏备份只包含清单列出的存档路径。")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = whenIdle(state.busy, viewModel::importSource),
                    modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(state.busy),
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "复制")
                    Text("复制到工作区", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = whenIdle(state.busy, viewModel::backupNow),
                    modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(state.busy),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = "创建快照")
                    Text("创建快照", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = whenIdle(state.busy, viewModel::requestWorkspaceWriteBack),
                modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = "写回")
                Text("把工作区写回来源目录", modifier = Modifier.padding(start = 8.dp))
            }
            SwitchSettingRow(
                label = "Wi-Fi 自动备份",
                checked = state.autoBackupEnabled,
                onCheckedChange = whenIdleWith(state.busy, viewModel::setAutoBackupEnabled),
                modifier = Modifier.padding(top = 12.dp).busyDimmed(state.busy),
                icon = Icons.Outlined.Schedule,
            )
        }
    }
}
