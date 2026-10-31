package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.GameSaveRemoteAction
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.ui.formatTime
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.whenIdle

@Composable
internal fun GameSaveCard(
    state: SourceUiState,
    viewModel: SourceViewModel,
    /** The first control of the settings page, focused when the page opens with the pad. */
    initialFocus: FocusRequester? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("game-save 云端备份", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "OpenSave 直接调用 game-save API。同步密钥使用 Android Keystore 加密保存；PC、Switch 和各模拟器存档在云端分开存放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            TextSettingRow(
                label = "game-save 服务地址",
                value = state.gameSaveUrl,
                placeholder = "http://nas.example:8788",
                keyboardType = KeyboardType.Uri,
                focusRequester = initialFocus,
                onValueChange = viewModel::updateGameSaveUrl,
            )
            TextSettingRow(
                label = "同步密钥",
                value = state.gameSaveSyncKey,
                secret = true,
                onValueChange = viewModel::updateGameSaveSyncKey,
            )
            SwitchSettingRow(
                label = "启用新快照自动上传",
                checked = state.gameSaveEnabled,
                onCheckedChange = viewModel::updateGameSaveEnabled,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::saveGameSave, modifier = Modifier.weight(1f).settingButtonFocus()) {
                    Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "保存")
                    Text("保存", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = viewModel::testGameSave, modifier = Modifier.weight(1f).settingButtonFocus()) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = "测试")
                    Text("测试连接", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = whenIdle(state.busy, viewModel::listCloud),
                    modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "列举云端")
                    Text("列举云端快照", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = whenIdle(state.busy, viewModel::checkCloudUpdates),
                    modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "检查跨设备更新")
                    Text("检查跨设备更新", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
internal fun CloudUpdateCard(state: SourceUiState, viewModel: SourceViewModel) {
    if (state.cloudUpdates.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("跨设备更新", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            state.cloudUpdates.take(16).forEach { update ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            update.entry.game.presentation.primaryName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(update.entry.sourceLabel)
                                append(" · ")
                                append(
                                    when (update.action) {
                                        GameSaveRemoteAction.AUTO_RESTORE -> "可安全恢复"
                                        GameSaveRemoteAction.REVIEW_REQUIRED -> "需要确认"
                                        GameSaveRemoteAction.WAIT_FOR_REMOTE -> "远端尚未就绪"
                                        GameSaveRemoteAction.UP_TO_DATE -> "已同步"
                                    },
                                )
                                update.deviceName.takeIf(String::isNotBlank)?.let {
                                    append(" · ")
                                    append(it)
                                }
                                update.receivedAtMillis.takeIf { it > 0L }?.let {
                                    append(" · ")
                                    append(formatTime(it))
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        update.message.takeIf(String::isNotBlank)?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (update.remote != null) {
                        IconButton(
                            onClick = whenIdle(state.busy) { viewModel.requestCloudUpdateRestore(update) },
                            modifier = Modifier.handheldFocusRing(CircleShape).busyDimmed(state.busy),
                        ) {
                            Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "确认恢复此跨设备更新")
                        }
                    }
                }
            }
        }
    }
}
