package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel

@Composable
internal fun SourceManagementPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = state.messages[OpenSavePage.MANAGEMENT]
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GameSaveCard(state, viewModel)
        CloudUpdateCard(state, viewModel)
        SourceSelectionCard(state, viewModel, onPickFolder)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("整来源快照", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("为当前选定目录创建 OpenSave ZIP 快照；游戏库中的单游戏备份只包含清单列出的存档路径。")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = viewModel::importSource, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = "复制")
                        Text("复制到工作区", modifier = Modifier.padding(start = 6.dp))
                    }
                    Button(onClick = viewModel::backupNow, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Save, contentDescription = "创建快照")
                        Text("创建快照", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::requestWorkspaceWriteBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = "写回")
                    Text("把工作区写回来源目录", modifier = Modifier.padding(start = 8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null)
                        Text("Wi-Fi 自动备份", modifier = Modifier.padding(start = 8.dp))
                    }
                    Switch(checked = state.autoBackupEnabled, onCheckedChange = viewModel::setAutoBackupEnabled, enabled = !state.busy)
                }
                if (state.busy) {
                    Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(state.progress.orEmpty(), modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        LocalSnapshotCard(state, viewModel)
        RemoteSnapshotCard(state, viewModel)
        messages.action?.takeIf { !state.busy }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        messages.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.report?.let { ReportCard(it) }
    }
}
