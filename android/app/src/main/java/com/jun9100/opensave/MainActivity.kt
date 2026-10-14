package com.jun9100.opensave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenSaveTheme {
                OpenSaveScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSaveScreen(sourceViewModel: SourceViewModel = viewModel()) {
    val state by sourceViewModel.uiState.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let(sourceViewModel::onTreeSelected) },
    )

    if (state.pendingRestore != null) {
        AlertDialog(
            onDismissRequest = sourceViewModel::cancelRestore,
            title = { Text("确认替换授权目录") },
            text = {
                Text("恢复前会在应用私有目录保留当前工作区安全副本，然后删除授权目录内现有内容并写入快照。请确认选择的是专用存档目录。")
            },
            confirmButton = {
                Button(onClick = sourceViewModel::confirmRestore) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = sourceViewModel::cancelRestore) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenSave")
                        Text(
                            text = "Android 存档管理",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = sourceViewModel::refreshPersistedSource,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        Text("刷新", modifier = Modifier.padding(start = 4.dp))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DeviceBaselineCard()

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("存档来源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.selectedUri?.toString() ?: "尚未授权目录",
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "选择目录")
                        Text("选择并授权存档目录", modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.hasPersistedPermission) {
                            "已取得可持久化读写权限，重启应用后仍可使用。"
                        } else {
                            "通过系统文件选择器授权目录；不会扫描其他应用私有目录。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.hasPersistedPermission) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("本地快照", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "先复制到应用私有工作区，再生成 OpenSave 兼容 ZIP。文件按流处理，不会一次性载入内存。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = sourceViewModel::importSource,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = "复制")
                            Text("复制到工作区", modifier = Modifier.padding(start = 6.dp))
                        }
                        Button(
                            onClick = sourceViewModel::backupNow,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = "创建快照")
                            Text("创建快照", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = sourceViewModel::restoreSource,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = "写回")
                        Text("把工作区写回授权目录", modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, contentDescription = null)
                            Text("Wi-Fi 自动备份", modifier = Modifier.padding(start = 8.dp))
                        }
                        Switch(
                            checked = state.autoBackupEnabled,
                            onCheckedChange = sourceViewModel::setAutoBackupEnabled,
                            enabled = !state.busy,
                        )
                    }
                    if (state.busy) {
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = state.action.orEmpty(),
                                modifier = Modifier.padding(start = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            WebDavCard(state, sourceViewModel)
            LocalSnapshotCard(state, sourceViewModel)
            RemoteSnapshotCard(state, sourceViewModel)

            state.action?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            state.report?.let { report ->
                ReportCard(report)
            }
        }
    }
}

@Composable
private fun WebDavCard(state: SourceUiState, viewModel: SourceViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("WebDAV 云端", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "使用 HTTPS WebDAV 地址。用户名和密码保存在 Android Keystore 加密区域，不写入日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.webDavUrl,
                onValueChange = viewModel::updateWebDavUrl,
                label = { Text("WebDAV 地址") },
                placeholder = { Text("https://nas.example/dav/OpenSave") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.webDavUsername,
                onValueChange = viewModel::updateWebDavUsername,
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.webDavPassword,
                onValueChange = viewModel::updateWebDavPassword,
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("启用新快照自动上传")
                Switch(checked = state.webDavEnabled, onCheckedChange = viewModel::updateWebDavEnabled)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::saveWebDav, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "保存")
                    Text("保存", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = viewModel::testWebDav, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = "测试")
                    Text("测试连接", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::listCloud,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "列举云端")
                Text("列举云端快照", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun LocalSnapshotCard(state: SourceUiState, viewModel: SourceViewModel) {
    if (state.snapshots.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("本地历史", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            state.snapshots.take(12).forEach { snapshot ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(snapshot.remoteName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatTime(snapshot.createdAtMillis)} · ${formatBytes(snapshot.sizeBytes)} · ${if (snapshot.uploaded) "已上传" else "未上传"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.requestLocalRestore(snapshot) },
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "恢复")
                        Text("恢复")
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteSnapshotCard(state: SourceUiState, viewModel: SourceViewModel) {
    if (state.remoteSnapshots.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("云端快照", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            state.remoteSnapshots.take(20).forEach { remote ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(remote.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatBytes(remote.sizeBytes)}${if (remote.modified.isBlank()) "" else " · ${remote.modified}"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.requestRemoteRestore(remote) },
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = "下载并恢复")
                        Text("恢复")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceBaselineCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("测试基线", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("目标设备：Odin2 / Android 13", style = MaterialTheme.typography.bodyLarge)
            Text("目标 SDK：35，最低支持 Android 8.0", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReportCard(report: CopyReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("最近一次工作区结果", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("文件：${report.fileCount}    大小：${report.byteCount} bytes")
            Text(
                text = "Manifest SHA-256：${report.manifestHash}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (report.detected.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("工作区识别提示", style = MaterialTheme.typography.labelLarge)
                report.detected.take(8).forEach { detected ->
                    val title = detected.titleId?.let { " · TitleID $it" }.orEmpty()
                    Text(
                        "${detected.note}$title\n${detected.relativePath}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (report.detected.size > 8) {
                    Text("还有 ${report.detected.size - 8} 个识别提示", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (report.files.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                report.files.take(12).forEach { file ->
                    Text(
                        text = "${file.relativePath}  (${file.sizeBytes} bytes)",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (report.files.size > 12) {
                    Text("还有 ${report.files.size - 12} 个文件未展开", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance().format(Date(millis))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}
