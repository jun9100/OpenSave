package com.jun9100.opensave.ui.sources

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.AuthorizedSafTree
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SaveSourceOption
import com.jun9100.opensave.SourceAccessCapability
import com.jun9100.opensave.SourceAccessStatus
import com.jun9100.opensave.SourceDraft
import com.jun9100.opensave.SourceGroup
import com.jun9100.opensave.SourceGroups
import com.jun9100.opensave.SourceRow
import com.jun9100.opensave.SourceSelection
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.ui.LocalAdaptiveLayout
import com.jun9100.opensave.ui.dialogs.DiscardSourceChangesDialog
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.settings.HandheldPageColumns
import com.jun9100.opensave.ui.settings.TextEditDialog
import com.jun9100.opensave.ui.settings.settingButtonFocus
import com.jun9100.opensave.ui.sourceAccessLabel
import com.jun9100.opensave.ui.sourcesCommands

/**
 * 来源 (plan §3.1): where saves are read from, grouped as 已授权目录 /
 * 内置来源·可用 / 需要 Root / 不存在. Landscape: the actions and the groups on
 * the left, the rows of the focused group on the right. Portrait: one list,
 * the last two groups folded. Toggles are staged and stored together by
 * "应用并重新扫描"; sources that need a missing Root or a missing directory
 * cannot be checked.
 */
@Composable
internal fun SourcesPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = state.messages[OpenSavePage.SOURCES]
    val landscape = LocalAdaptiveLayout.current.isLandscape
    val rows = remember(state.sourceOptions, state.sourceScans, state.rootChecked, state.rootAvailable, state.sourceDraft) {
        SourceGroups.rows(state.sourceOptions, state.sourceScans, state.rootChecked, state.rootAvailable, state.sourceDraft)
    }
    val groups = remember(rows) { SourceGroups.grouped(rows) }
    val pending = state.sourceDraft.pendingCount(state.sourceOptions)
    var addingRootPath by rememberSaveable { mutableStateOf(false) }
    var selectedGroupName by rememberSaveable { mutableStateOf(SourceGroup.AUTHORIZED.name) }
    var unfolded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val selectedGroup = groups.firstOrNull { it.first.name == selectedGroupName } ?: groups.first()
    val stage: (SourceDraft) -> Unit = viewModel::updateSourceDraft
    val canAddRoot = state.rootAvailable

    HandheldPageColumns(
        commandsTitle = "来源命令",
        commands = {
            sourcesCommands(
                busy = state.busy,
                pendingChanges = pending,
                rootAvailable = canAddRoot,
                onPickFolder = onPickFolder,
                onAddRootPath = { addingRootPath = true },
                onApply = viewModel::applySourceDraft,
                onScan = viewModel::scanSource,
                onRefresh = viewModel::refreshPersistedSource,
            )
        },
        busy = state.busy,
        modifier = modifier,
        dialogOpen = addingRootPath || state.confirmDiscardSourceDraft,
        left = { firstControl ->
            messages.action?.takeIf { !state.busy }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            messages.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SourceActionsCard(
                state = state,
                pending = pending,
                canAddRoot = canAddRoot,
                onPickFolder = onPickFolder,
                onAddRootPath = { addingRootPath = true },
                onApply = viewModel::applySourceDraft,
                focusRequester = firstControl,
            )
            if (landscape) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { (group, members) ->
                            GroupNavigationItem(
                                group = group,
                                rows = members,
                                selected = group == selectedGroup.first,
                                onSelect = { selectedGroupName = group.name },
                            )
                        }
                    }
                }
            } else {
                groups.forEach { (group, members) ->
                    GroupCard(
                        group = group,
                        rows = members,
                        state = state,
                        expanded = !group.foldedByDefault || group.name in unfolded,
                        onExpandedChange = { expand ->
                            unfolded = if (expand) unfolded + group.name else unfolded - group.name
                        },
                        onStage = stage,
                        onForgetTree = viewModel::forgetAuthorizedTree,
                    )
                }
            }
        },
        right = { firstControl ->
            if (landscape) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Always first and focusable: the folded groups have no row the pad can stop on.
                        OutlinedButton(
                            onClick = whenIdle(state.busy, viewModel::scanSource),
                            modifier = Modifier.fillMaxWidth().focusRequester(firstControl).settingButtonFocus().busyDimmed(state.busy),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Text("重新扫描", modifier = Modifier.padding(start = 6.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        GroupHeader(selectedGroup.first, selectedGroup.second)
                        GroupRows(selectedGroup.first, selectedGroup.second, state, stage, viewModel::forgetAuthorizedTree)
                    }
                }
            }
        },
    )

    if (addingRootPath) {
        TextEditDialog(
            title = "添加 Root 路径",
            initial = state.rootPath,
            placeholder = "/data/user/0/com.xiaoji.egggame/files",
            secret = false,
            keyboardType = KeyboardType.Uri,
            confirmLabel = "添加并扫描",
            note = if (state.rootPath.isNotBlank()) "会替换现有的自定义 Root 路径。" else null,
            onDismiss = { addingRootPath = false },
            onConfirm = { path ->
                addingRootPath = false
                viewModel.addRootPath(path)
            },
        )
    }
    if (state.confirmDiscardSourceDraft) {
        DiscardSourceChangesDialog(
            count = pending,
            onDismiss = viewModel::cancelDiscardSourceDraft,
            onConfirm = viewModel::discardSourceDraftAndLeave,
        )
    }
}

/** How the device reaches sources, the two ways to add one, and the staged changes. */
@Composable
private fun SourceActionsCard(
    state: SourceUiState,
    pending: Int,
    canAddRoot: Boolean,
    onPickFolder: () -> Unit,
    onAddRootPath: () -> Unit,
    onApply: () -> Unit,
    focusRequester: FocusRequester,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("存档来源", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !state.rootChecked -> "正在检测 Root 权限…"
                    state.rootAvailable -> "Root 可用 · 内置来源可直接扫描，也可添加授权目录"
                    !state.rootAllowed -> "只用授权目录 · 要使用 Root，请在 设置 → 关于 → 首次使用向导 中选择"
                    else -> "未检测到 Root · 通过系统授权读取你选择的目录"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.rootAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = whenIdle(state.busy, onPickFolder),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).settingButtonFocus().busyDimmed(state.busy),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text("添加授权目录", modifier = Modifier.padding(start = 8.dp))
            }
            if (canAddRoot) {
                OutlinedButton(
                    onClick = whenIdle(state.busy, onAddRootPath),
                    modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
                ) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null)
                    Text("添加 Root 路径", modifier = Modifier.padding(start = 8.dp))
                }
            }
            // Never hidden or disabled: a control that vanishes under A would drop D-pad focus (plan R4).
            OutlinedButton(
                onClick = whenIdle(state.busy) { if (pending > 0) onApply() },
                modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy || pending == 0),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(if (pending > 0) "应用并重新扫描 ($pending)" else "应用并重新扫描")
                    Text(
                        if (pending > 0) "$pending 项来源改动尚未应用" else "勾选或取消来源后，在这里一次应用",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pending > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A landscape group entry: focusing it shows its rows on the right. */
@Composable
private fun GroupNavigationItem(
    group: SourceGroup,
    rows: List<SourceRow>,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let { if (selected) it.background(MaterialTheme.colorScheme.secondaryContainer, shape) else it }
            .onFocusChanged { if (it.isFocused) onSelect() }
            .handheldFocusRing(shape)
            .clickable(role = Role.Tab, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(group.title, style = MaterialTheme.typography.bodyLarge)
            Text(groupSummary(group, rows), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
    }
}

/** A portrait group: the folded ones open with A (or a tap) on their header. */
@Composable
private fun GroupCard(
    group: SourceGroup,
    rows: List<SourceRow>,
    state: SourceUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onStage: (SourceDraft) -> Unit,
    onForgetTree: (Uri) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (group.foldedByDefault) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .handheldFocusRing(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.title, style = MaterialTheme.typography.titleSmall)
                        Text(groupSummary(group, rows), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起${group.title}" else "展开${group.title}",
                    )
                }
            } else {
                GroupHeader(group, rows)
            }
            if (expanded) GroupRows(group, rows, state, onStage, onForgetTree)
        }
    }
}

@Composable
private fun GroupHeader(group: SourceGroup, rows: List<SourceRow>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(group.title, style = MaterialTheme.typography.titleSmall)
        Text(groupSummary(group, rows), style = MaterialTheme.typography.labelMedium)
    }
}

/** The group's note, "全选 / 全不选" when more than one row can be checked, and the rows. */
@Composable
private fun GroupRows(
    group: SourceGroup,
    rows: List<SourceRow>,
    state: SourceUiState,
    onStage: (SourceDraft) -> Unit,
    onForgetTree: (Uri) -> Unit,
) {
    groupNote(group, state)?.let { note ->
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (group == SourceGroup.AUTHORIZED && rows.isEmpty()) {
        Text("尚未添加授权目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val checkable = rows.filter(SourceRow::checkable)
    if (checkable.size > 1) {
        val canSelectAll = checkable.any { !it.checked }
        val canSelectNone = checkable.any { it.checked }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // Never disabled: a button that disables itself on A would drop D-pad focus (plan R4).
            TextButton(
                onClick = whenIdle(state.busy) { if (canSelectAll) onStage(state.sourceDraft.setAll(rows, true)) },
                modifier = Modifier.settingButtonFocus().busyDimmed(state.busy || !canSelectAll),
            ) { Text("全选") }
            TextButton(
                onClick = whenIdle(state.busy) { if (canSelectNone) onStage(state.sourceDraft.setAll(rows, false)) },
                modifier = Modifier.settingButtonFocus().busyDimmed(state.busy || !canSelectNone),
            ) { Text("全不选") }
        }
    }
    val trees = state.authorizedTrees.associateBy(AuthorizedSafTree::uri)
    rows.forEach { row ->
        val tree = (row.option.source as? SourceSelection.Saf)?.let { trees[it.uri] }
        SourceRowItem(
            row = row,
            tree = tree,
            access = row.scan?.access ?: plannedAccessStatus(row.option, state.rootChecked, state.rootAvailable),
            busy = state.busy,
            onToggle = { enabled -> onStage(state.sourceDraft.toggle(row, enabled)) },
            onForget = tree?.let { { onForgetTree(it.uri) } },
        )
    }
}

/**
 * The whole row is the checkbox (plan R1/I8): one focus stop, A stages the
 * change. A source that cannot be checked is not a focus stop.
 */
@Composable
private fun SourceRowItem(
    row: SourceRow,
    tree: AuthorizedSafTree?,
    access: SourceAccessStatus,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onForget: (() -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 2.dp)
                .heightIn(min = 48.dp)
                .handheldFocusRing(RoundedCornerShape(12.dp))
                .toggleable(
                    value = row.checked,
                    enabled = row.checkable,
                    role = Role.Checkbox,
                    // Busy only ignores the change, so focus does not drop.
                    onValueChange = { if (!busy) onToggle(it) },
                )
                .busyDimmed(busy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = row.checked,
                onCheckedChange = null,
                enabled = row.checkable,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.option.label + if (row.changed) " · 未应用" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.changed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sourceRowDetail(row, tree, access),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (access.readable || !row.option.selected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onForget != null) {
            IconButton(
                onClick = whenIdle(busy, onForget),
                modifier = Modifier.handheldFocusRing(CircleShape).busyDimmed(busy),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除授权目录")
            }
        }
    }
}

private fun sourceRowDetail(row: SourceRow, tree: AuthorizedSafTree?, access: SourceAccessStatus): String = buildString {
    append(if (row.option.enabledByDefault) row.option.saveType.label else "未归类应用（待确认）")
    append(" · ")
    when {
        tree != null && !tree.readable -> append("目录授权已失效，请移除后重新添加")
        tree != null && !tree.writable -> append("已授权只读 · 可备份，不能恢复")
        // Switched-off sources are not scanned, so their access is unknown.
        row.scan == null && !row.option.selected && row.checkable -> append("未启用 · 勾选并应用后扫描")
        else -> append(sourceAccessLabel(access))
    }
    row.scan?.scan?.let { report -> append(" · ${report.gameCount} 款游戏 · ${report.detected.size} 个存档位置") }
    if (!row.option.enabledByDefault && row.checkable) append(" · 默认关闭，避免扫描非游戏数据")
}

private fun groupSummary(group: SourceGroup, rows: List<SourceRow>): String = when (group) {
    SourceGroup.NEEDS_ROOT, SourceGroup.MISSING -> "${rows.size} 个 · 不能勾选"
    else -> "${rows.size} 个 · 已选 ${rows.count(SourceRow::checked)}"
}

private fun groupNote(group: SourceGroup, state: SourceUiState): String? = when (group) {
    SourceGroup.AUTHORIZED -> if (state.authorizedTrees.isEmpty()) {
        "没有 Root 时，用“添加授权目录”选择模拟器或游戏的存档目录。Android 不允许普通应用读取其他应用的私有数据目录。"
    } else {
        null
    }
    SourceGroup.NEEDS_ROOT -> if (state.rootAllowed) {
        "这些来源在其他应用的私有目录里，需要 Root（在 Magisk 中永久授权 OpenSave）才能读取；没有 Root 时请改用授权目录。"
    } else {
        "这些来源在其他应用的私有目录里，需要 Root 才能读取。当前只用授权目录；要使用 Root，请在 设置 → 关于 → 首次使用向导 中选择。"
    }
    SourceGroup.MISSING -> "上次扫描时这些目录不存在或无法读取；目录出现后重新扫描即可。"
    SourceGroup.AVAILABLE -> null
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
