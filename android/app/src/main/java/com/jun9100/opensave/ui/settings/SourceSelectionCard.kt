package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.AuthorizedSafTree
import com.jun9100.opensave.SaveSourceOption
import com.jun9100.opensave.SourceAccessCapability
import com.jun9100.opensave.SourceAccessStatus
import com.jun9100.opensave.SourceOptionPolicy
import com.jun9100.opensave.SourceScanResult
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.StorageMode
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.input.whenIdleWith
import com.jun9100.opensave.ui.sourceAccessLabel

@Composable
internal fun SourceSelectionCard(
    state: SourceUiState,
    viewModel: SourceViewModel,
    onPickFolder: () -> Unit,
    initialFocus: FocusRequester? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("存档来源", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.storageMode == StorageMode.SAF,
                    onClick = whenIdle(state.busy) { viewModel.selectStorageMode(StorageMode.SAF) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier
                        .weight(1f)
                        .let { if (initialFocus != null) it.focusRequester(initialFocus) else it }
                        .settingButtonFocus()
                        .busyDimmed(state.busy),
                ) { Text("系统授权") }
                SegmentedButton(
                    selected = state.storageMode == StorageMode.ROOT,
                    onClick = whenIdle(state.busy) { viewModel.selectStorageMode(StorageMode.ROOT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(state.busy),
                ) { Text("Root 路径") }
            }
            Spacer(Modifier.height(12.dp))
            if (state.storageMode == StorageMode.ROOT) {
                TextSettingRow(
                    label = "Root 扫描路径",
                    value = state.rootPath,
                    placeholder = "/data/user/0/com.xiaoji.egggame/files",
                    keyboardType = KeyboardType.Uri,
                    onValueChange = viewModel::updateRootPath,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = whenIdle(state.busy, viewModel::applyRootPath),
                    modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "保存 Root 路径")
                    Text("保存并扫描", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !state.rootChecked -> "正在检测 su 权限..."
                        state.rootAvailable -> "Root 可用；目录扫描为只读操作。"
                        else -> "未检测到 uid 0，请确认 Magisk 已授权 OpenSave。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.rootAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (state.authorizedTrees.isEmpty()) {
                    Text(
                        "尚未添加系统授权目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.authorizedTrees.forEach { tree ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tree.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    when {
                                        tree.readable && tree.writable -> "已授权读写 · 自动扫描"
                                        tree.readable -> "已授权只读 · 可备份，不能恢复"
                                        else -> "目录授权已失效"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tree.readable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                            IconButton(
                                onClick = whenIdle(state.busy) { viewModel.forgetAuthorizedTree(tree.uri) },
                                modifier = Modifier.handheldFocusRing(CircleShape).busyDimmed(state.busy),
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除授权目录")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = whenIdle(state.busy, onPickFolder),
                    modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "选择目录")
                    Text("添加并授权目录", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.authorizedTrees.isNotEmpty()) {
                        "可重复添加多个目录；应用启动和 Wi-Fi 自动备份都会扫描所有仍有读取权限的目录。"
                    } else {
                        "系统授权模式只访问你选择的目录。Android 不允许非 Root 应用读取其他应用的私有数据目录。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.authorizedTrees.any(AuthorizedSafTree::readable)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.sourceOptions.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                val scanBySourceId = state.sourceScans.associateBy(SourceScanResult::sourceId)
                val unreachableIds = state.sourceOptions.filter { option ->
                    SourceOptionPolicy.unreachable(
                        option = option,
                        scannedAccess = scanBySourceId[option.sourceId]?.access,
                        rootChecked = state.rootChecked,
                        rootAvailable = state.rootAvailable,
                    )
                }.map(SaveSourceOption::id).toSet()
                val selectedCount = state.sourceOptions.count { option ->
                    SourceOptionPolicy.displayedChecked(option, option.id in unreachableIds)
                }
                val checkableCount = state.sourceOptions.count { it.id !in unreachableIds }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("自动扫描来源", style = MaterialTheme.typography.titleSmall)
                    Text("$selectedCount / ${state.sourceOptions.size} 已选", style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // Never disabled: a button that disables itself on A would drop D-pad focus (plan R4).
                    val canSelectAll = selectedCount < checkableCount
                    val canSelectNone = selectedCount > 0
                    TextButton(
                        onClick = whenIdle(state.busy) { if (canSelectAll) viewModel.setAllSourcesEnabled(true) },
                        modifier = Modifier.settingButtonFocus().busyDimmed(state.busy || !canSelectAll),
                    ) { Text("全选") }
                    TextButton(
                        onClick = whenIdle(state.busy) { if (canSelectNone) viewModel.setAllSourcesEnabled(false) },
                        modifier = Modifier.settingButtonFocus().busyDimmed(state.busy || !canSelectNone),
                    ) { Text("全不选") }
                }
                state.sourceOptions.forEach { option ->
                    val access = scanBySourceId[option.sourceId]?.access
                        ?: plannedAccessStatus(option, state.rootChecked, state.rootAvailable)
                    val unreachable = option.id in unreachableIds
                    val checked = SourceOptionPolicy.displayedChecked(option, unreachable)
                    // The whole row is the checkbox (plan R1/I8): one focus stop, A toggles it.
                    // Busy only ignores the change, so focus does not drop.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .heightIn(min = 48.dp)
                            .handheldFocusRing(RoundedCornerShape(12.dp))
                            .toggleable(
                                value = checked,
                                enabled = !unreachable,
                                role = Role.Checkbox,
                                onValueChange = whenIdleWith(state.busy) { selected -> viewModel.setSourceEnabled(option.id, selected) },
                            )
                            .busyDimmed(state.busy),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = !unreachable,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(if (option.enabledByDefault) option.saveType.label else "未归类应用候选")
                                    append(" · ")
                                    append(sourceAccessLabel(access))
                                    if (!option.enabledByDefault) append(" · 默认关闭，避免扫描非游戏数据")
                                    if (unreachable) append(" · 不可勾选")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (access.readable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (!state.rootAvailable) {
                    Text(
                        "未 Root 的设备会自动跳过 Root 来源；请为可访问的模拟器或游戏目录添加系统授权。",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun plannedAccessStatus(
    option: SaveSourceOption,
    rootChecked: Boolean,
    rootAvailable: Boolean,
): SourceAccessStatus = when (option.requiredAccess) {
    SourceAccessCapability.APP -> SourceAccessStatus.appAccessible()
    SourceAccessCapability.SAF -> SourceAccessStatus.pending(SourceAccessCapability.SAF)
    SourceAccessCapability.ROOT -> if (rootChecked && !rootAvailable) {
        SourceAccessStatus.unavailable(SourceAccessCapability.ROOT, "Root 未授权")
    } else {
        SourceAccessStatus.pending(SourceAccessCapability.ROOT)
    }
    SourceAccessCapability.UNAVAILABLE -> SourceAccessStatus.pending(SourceAccessCapability.UNAVAILABLE)
}
