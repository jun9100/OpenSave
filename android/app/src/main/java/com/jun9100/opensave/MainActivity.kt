package com.jun9100.opensave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenSave")
                        Text(
                            text = "Android 存档基础版",
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
                            "需要通过系统文件选择器授权目录；不会扫描其他应用私有目录。"
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
                    Text("工作区验证", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "先复制到 OpenSave 私有工作区，再按 manifest 和 SHA-256 校验。当前阶段只验证目录读取与安全写回，快照和 WebDAV 在下一阶段接入。",
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
                        OutlinedButton(
                            onClick = sourceViewModel::restoreSource,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.UploadFile, contentDescription = "写回")
                            Text("写回授权目录", modifier = Modifier.padding(start = 6.dp))
                        }
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
            Text("最近一次结果", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("文件：${report.fileCount}    大小：${report.byteCount} bytes")
            Text(
                text = "Manifest SHA-256：${report.manifestHash}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
