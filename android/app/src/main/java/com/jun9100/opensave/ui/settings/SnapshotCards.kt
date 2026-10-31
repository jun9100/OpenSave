package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CopyReport
import com.jun9100.opensave.GameSaveTypes
import com.jun9100.opensave.SnapshotNaming
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.ui.formatBytes
import com.jun9100.opensave.ui.formatTime
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.preview.PreviewSurface

@Composable
internal fun LocalSnapshotCard(state: SourceUiState, viewModel: SourceViewModel) {
    val snapshots = state.snapshots.filter { it.sourceId == state.activeSourceId && it.gameKey == null }
    if (snapshots.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("本地历史", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            snapshots.take(12).forEach { snapshot ->
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
                        onClick = whenIdle(state.busy) { viewModel.requestLocalRestore(snapshot) },
                        modifier = Modifier.settingButtonFocus().busyDimmed(state.busy),
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
internal fun RemoteSnapshotCard(state: SourceUiState, viewModel: SourceViewModel) {
    val sourceId = state.activeSourceId ?: return
    val snapshots = state.remoteSnapshots.filter { it.name.startsWith(SnapshotNaming.remotePrefix(sourceId)) }
    if (snapshots.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("云端快照", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            snapshots.take(20).forEach { remote ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(remote.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${GameSaveTypes.fromId(remote.type).label} · ${formatBytes(remote.sizeBytes)}${if (remote.modified.isBlank()) "" else " · ${remote.modified}"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = whenIdle(state.busy) { viewModel.requestRemoteRestore(remote) },
                        modifier = Modifier.settingButtonFocus().busyDimmed(state.busy),
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
internal fun ReportCard(report: CopyReport) {
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

@ComponentPreviews
@Composable
private fun ReportCardPreview() {
    PreviewSurface {
        ReportCard(PreviewData.report)
    }
}
