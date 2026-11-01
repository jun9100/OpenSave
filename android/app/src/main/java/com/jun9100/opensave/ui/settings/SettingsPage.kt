package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.R
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.StorageMode
import com.jun9100.opensave.SyncInbox
import com.jun9100.opensave.ui.NotificationAccess
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.input.whenIdleWith
import com.jun9100.opensave.ui.settingsCommands

/**
 * 设置 (plan §3.1): 云端 (the game-save account) on the left; "同步与任务 ›",
 * 自动化, 显示, the collapsed 高级 (whole-source snapshot, write-back and their
 * histories, plan Q13) and 关于 on the right.
 */
@Composable
internal fun SettingsPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    notifications: NotificationAccess,
    modifier: Modifier = Modifier,
) {
    val messages = state.messages[OpenSavePage.SETTINGS]
    val context = LocalContext.current
    val attention = remember(
        state.sourceScans,
        state.snapshots,
        state.saveSignatures,
        state.cloudUpdates,
        state.rememberedCloudVerdicts,
        state.gameSaveEnabled,
        state.syncDismissed,
    ) { SyncInbox.fromState(state).size }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    HandheldPageColumns(
        commandsTitle = "设置命令",
        commands = {
            settingsCommands(
                busy = state.busy,
                syncAttention = attention,
                onOpenSyncTasks = viewModel::openSyncTasks,
                onCheckCloudUpdates = { viewModel.checkCloudUpdates(OpenSavePage.SETTINGS) },
                onEditCloud = viewModel::openGameSaveEditor,
                onImportFromClipboard = { viewModel.importGameSaveConfig(readClipboardText(context)) },
                onRefresh = viewModel::refreshPersistedSource,
                onOpenBatchReport = viewModel::openBatchReport.takeIf { state.lastBatchReport != null },
                onOpenWizard = viewModel::openWizard,
            )
        },
        busy = state.busy,
        modifier = modifier,
        dialogOpen = state.gameSaveEditor != null || state.gameSaveTestFailure != null || state.pendingConfigImport != null ||
            state.wizardStep != null,
        startOnRight = state.previousPage == OpenSavePage.SYNC,
        left = { firstControl ->
            messages.action?.takeIf { !state.busy }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            messages.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            GameSaveCard(state, viewModel, initialFocus = firstControl)
        },
        right = { firstControl ->
            SyncTasksCard(attention = attention, onOpen = viewModel::openSyncTasks, focusRequester = firstControl)
            AutomationCard(state, viewModel, notifications)
            DisplayCard(state, viewModel)
            AdvancedCard(
                state = state,
                viewModel = viewModel,
                expanded = advancedOpen,
                onExpandedChange = { advancedOpen = it },
            )
            AboutCard(onOpenWizard = viewModel::openWizard)
        },
    )
}

/** "同步与任务 ›" with what waits there. */
@Composable
private fun SyncTasksCard(attention: Int, onOpen: () -> Unit, focusRequester: FocusRequester) {
    Card(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .focusRequester(focusRequester)
                .settingButtonFocus(),
        ) {
            Icon(Icons.Outlined.Sync, contentDescription = null)
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text("同步与任务")
                Text(
                    if (attention > 0) "$attention 款需要确认" else "冲突、云端较新和最近的批量报告",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (attention > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Automatic backup and upload, each with what it depends on. */
@Composable
private fun AutomationCard(state: SourceUiState, viewModel: SourceViewModel, notifications: NotificationAccess) {
    val cloudConfigured = state.gameSaveUrl.isNotBlank() && state.gameSaveSyncKey.isNotBlank()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("自动化", style = MaterialTheme.typography.titleMedium)
            SwitchSettingRow(
                label = "Wi-Fi 自动备份",
                checked = state.autoBackupEnabled,
                onCheckedChange = whenIdleWith(state.busy, viewModel::setAutoBackupEnabled),
                modifier = Modifier.padding(top = 8.dp).busyDimmed(state.busy),
                icon = Icons.Outlined.Schedule,
            )
            // The worker backs up only what it can upload (AutoBackup.backUpChangedGames).
            val uploadReady = cloudConfigured && state.gameSaveEnabled
            Text(
                if (uploadReady) {
                    "连接 Wi-Fi 时定期备份有变化的游戏并上传；只扫描“来源”页已启用的来源，冲突只记录，不会自动覆盖。"
                } else {
                    "依赖下面的“新版本自动上传”：game-save 配置好并开启上传之前，自动备份不会运行。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (uploadReady || !state.autoBackupEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
            SwitchSettingRow(
                label = "新版本自动上传",
                checked = state.gameSaveEnabled && cloudConfigured,
                onCheckedChange = whenIdleWith(state.busy, viewModel::setAutoUploadEnabled),
                modifier = Modifier.padding(top = 8.dp).busyDimmed(state.busy || !cloudConfigured),
                icon = Icons.Outlined.CloudUpload,
            )
            Text(
                if (cloudConfigured) {
                    "备份产生的新版本会上传到 game-save；Wi-Fi 自动备份也需要开启它。"
                } else {
                    "需要先在“云端”配置并保存 game-save。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (cloudConfigured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
            NotificationRow(notifications)
        }
    }
}

/** "需要处理时通知": the permission is asked at most once; afterwards only the system settings. */
@Composable
private fun NotificationRow(notifications: NotificationAccess) {
    val request = notifications.request
    OutlinedButton(
        onClick = when {
            notifications.allowed -> notifications.openSettings
            request != null -> request
            else -> notifications.openSettings
        },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).settingButtonFocus(),
    ) {
        Icon(Icons.Outlined.Notifications, contentDescription = null)
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(stringResource(R.string.wizard_notifications_title))
            Text(
                when {
                    notifications.allowed -> stringResource(R.string.notifications_allowed)
                    request != null -> stringResource(R.string.wizard_notifications_body)
                    else -> stringResource(R.string.notifications_denied)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (notifications.allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            when {
                request != null && !notifications.allowed -> stringResource(R.string.wizard_notifications_allow)
                else -> "设置"
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 显示: landscape immersive mode, off by default (plan Q2). */
@Composable
private fun DisplayCard(state: SourceUiState, viewModel: SourceViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("显示", style = MaterialTheme.typography.titleMedium)
            SwitchSettingRow(
                label = "横屏沉浸模式",
                checked = state.immersiveLandscape,
                onCheckedChange = viewModel::setImmersiveLandscape,
                modifier = Modifier.padding(top = 8.dp),
                icon = Icons.Outlined.Fullscreen,
            )
            Text(
                "横屏时隐藏状态栏和手势条，页面多出约 48dp，但看不到电量和时间；从屏幕边缘滑动可临时显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 关于: the installed version and the first-run wizard. */
@Composable
private fun AboutCard(onOpenWizard: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("未知")
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text("OpenSave $version", style = MaterialTheme.typography.bodyMedium)
            Text(
                "游戏存档备份与跨设备同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenWizard,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).settingButtonFocus(),
            ) {
                Icon(Icons.Outlined.School, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(stringResource(R.string.about_open_wizard))
                    Text(
                        stringResource(R.string.about_open_wizard_detail),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Whole-source snapshot, write-back and their histories: tools from before
 * the per-game library, kept behind one collapsed header (plan Q13).
 */
@Composable
private fun AdvancedCard(
    state: SourceUiState,
    viewModel: SourceViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.fillMaxWidth().settingButtonFocus(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("高级")
                    Text(
                        "整来源快照、写回工作区和它们的历史",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起高级" else "展开高级",
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
                    Text(
                        "⚠ 这些是整个来源目录的工具：写回会覆盖来源目录里的文件。日常备份和恢复请用游戏库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    WholeSourceSnapshotSection(state, viewModel)
                }
            }
        }
    }
    if (expanded) {
        LocalSnapshotCard(state, viewModel)
        RemoteSnapshotCard(state, viewModel)
        state.report?.let { ReportCard(it) }
    }
}

@Composable
private fun WholeSourceSnapshotSection(state: SourceUiState, viewModel: SourceViewModel) {
    Text("整来源快照", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Text(
        "把整个目录做成一份 OpenSave ZIP 快照；游戏库里的备份只包含已确认的存档位置。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    val rootTarget = state.rootAvailable || state.storageMode == StorageMode.ROOT
    if (rootTarget) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.storageMode == StorageMode.SAF,
                onClick = whenIdle(state.busy) { viewModel.selectStorageMode(StorageMode.SAF) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(state.busy),
            ) { Text("授权目录") }
            SegmentedButton(
                selected = state.storageMode == StorageMode.ROOT,
                onClick = whenIdle(state.busy) { viewModel.selectStorageMode(StorageMode.ROOT) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(state.busy),
            ) { Text("Root 路径") }
        }
        Spacer(Modifier.height(6.dp))
    }
    Text(
        "目标：" + when (state.storageMode) {
            StorageMode.SAF -> state.authorizedTrees.firstOrNull { it.uri == state.selectedUri }?.label ?: "尚未添加授权目录"
            StorageMode.ROOT -> state.rootPath.ifBlank { "尚未在“来源”页添加 Root 路径" }
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(10.dp))
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
        Text("把工作区写回来源目录…", modifier = Modifier.padding(start = 8.dp))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = whenIdle(state.busy, viewModel::listCloud),
        modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
    ) {
        Icon(Icons.Outlined.Refresh, contentDescription = null)
        Text("列举云端整来源快照", modifier = Modifier.padding(start = 8.dp))
    }
}
