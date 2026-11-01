package com.jun9100.opensave.ui.library

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.LibraryStatusFilter
import com.jun9100.opensave.PrimaryBackup
import com.jun9100.opensave.ui.LIBRARY_TOOLBAR_TAG
import com.jun9100.opensave.ui.LibrarySelectionActions
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * The fixed library toolbar above the list; it never scrolls away. Landscape
 * fits it into one 52dp row, portrait uses a chip row and an action row. The
 * chips are the save type filter, then the status filter (plan §5.2).
 *
 * Browsing shows the game count, "选择" and the main button ("备份有变更 (N)",
 * or "备份所选 (N)" with a selection); selection mode replaces them with the
 * contextual bar ✕ · 已选 N · 全选当前筛选 · 清除 · 备份所选 (plan §5.1).
 *
 * It is one focus group: coming back from the list returns to the control
 * used last. While [enabled] is false (an operation runs) the controls stay
 * focusable and dimmed and ignore clicks, so D-pad focus does not drop.
 */
@Composable
internal fun LibraryToolbar(
    typeOptions: List<Pair<String, String>>,
    selectedTypeId: String,
    statusCounts: Map<LibraryStatusFilter, Int>,
    selectedStatus: LibraryStatusFilter,
    visibleCount: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    primary: PrimaryBackup,
    enabled: Boolean,
    singleRow: Boolean,
    onTypeSelected: (String) -> Unit,
    onStatusSelected: (LibraryStatusFilter) -> Unit,
    selection: LibrarySelectionActions,
    onPrimaryBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips: @Composable (Modifier) -> Unit = { chipModifier ->
        LibraryFilterBar(
            typeOptions = typeOptions,
            selectedTypeId = selectedTypeId,
            statusCounts = statusCounts,
            selectedStatus = selectedStatus,
            enabled = enabled,
            onTypeSelected = onTypeSelected,
            onStatusSelected = onStatusSelected,
            modifier = chipModifier,
        )
    }
    val actions: @Composable RowScope.(Boolean) -> Unit = { fill ->
        if (selectionMode) {
            SelectionActions(selectedCount, primary, enabled, selection, onPrimaryBackup, fill)
        } else {
            BrowseActions(visibleCount, primary, enabled, selection.onEnter, onPrimaryBackup, fill)
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag(LIBRARY_TOOLBAR_TAG).focusRestorer().focusGroup(),
        color = if (selectionMode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        if (singleRow) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chips(Modifier.weight(1f))
                actions(false)
            }
        } else {
            Column {
                chips(Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions(true)
                }
            }
        }
    }
}

@Composable
private fun RowScope.BrowseActions(
    visibleCount: Int,
    primary: PrimaryBackup,
    enabled: Boolean,
    onEnterSelection: () -> Unit,
    onPrimaryBackup: () -> Unit,
    fill: Boolean,
) {
    Text(
        "$visibleCount 款",
        modifier = if (fill) Modifier.weight(1f).padding(start = 8.dp) else Modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    TextButton(
        onClick = whenIdle(!enabled, onEnterSelection),
        modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp)).busyDimmed(!enabled),
    ) {
        Icon(Icons.Outlined.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("选择", modifier = Modifier.padding(start = 4.dp), maxLines = 1)
    }
    PrimaryBackupButton(primary, enabled, onPrimaryBackup)
}

@Composable
private fun RowScope.SelectionActions(
    selectedCount: Int,
    primary: PrimaryBackup,
    enabled: Boolean,
    selection: LibrarySelectionActions,
    onPrimaryBackup: () -> Unit,
    fill: Boolean,
) {
    // Leaving selection mode is not an operation; it stays available while busy.
    IconButton(
        onClick = selection.onExit,
        modifier = Modifier.size(40.dp).handheldFocusRing(CircleShape),
    ) {
        Icon(Icons.Outlined.Close, contentDescription = "退出选择模式")
    }
    Text(
        "已选 $selectedCount",
        modifier = if (fill) Modifier.weight(1f) else Modifier.padding(end = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    TextButton(
        onClick = whenIdle(!enabled, selection.onSelectAll),
        modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp)).busyDimmed(!enabled),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text("全选当前筛选", maxLines = 1)
    }
    TextButton(
        onClick = whenIdle(!enabled, selection.onClear),
        modifier = Modifier.handheldFocusRing(RoundedCornerShape(20.dp)).busyDimmed(!enabled),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text("清除", maxLines = 1)
    }
    PrimaryBackupButton(primary, enabled, onPrimaryBackup)
}

@Composable
private fun PrimaryBackupButton(primary: PrimaryBackup, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = whenIdle(!enabled, onClick),
        enabled = primary.enabled,
        modifier = Modifier
            .height(40.dp)
            .testTag(PRIMARY_BACKUP_TAG)
            .handheldFocusRing(RoundedCornerShape(20.dp))
            .busyDimmed(!enabled),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(Icons.Outlined.Save, contentDescription = null)
        Text(primary.label, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
    }
}

@Composable
private fun LibraryFilterBar(
    typeOptions: List<Pair<String, String>>,
    selectedTypeId: String,
    statusCounts: Map<LibraryStatusFilter, Int>,
    selectedStatus: LibraryStatusFilter,
    enabled: Boolean,
    onTypeSelected: (String) -> Unit,
    onStatusSelected: (LibraryStatusFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A single "全部" type means there is nothing to filter by type.
        if (typeOptions.size > 1) {
            typeOptions.forEach { (id, label) ->
                FilterChip(
                    selected = selectedTypeId == id,
                    onClick = whenIdle(!enabled) { onTypeSelected(id) },
                    modifier = Modifier.handheldFocusRing(RoundedCornerShape(8.dp)).busyDimmed(!enabled),
                    label = { Text(label, maxLines = 1) },
                )
            }
            VerticalDivider(modifier = Modifier.height(24.dp))
        }
        LibraryStatusFilter.entries.forEach { filter ->
            val count = statusCounts[filter] ?: 0
            FilterChip(
                selected = selectedStatus == filter,
                onClick = whenIdle(!enabled) { onStatusSelected(filter) },
                modifier = Modifier.handheldFocusRing(RoundedCornerShape(8.dp)).busyDimmed(!enabled),
                label = {
                    Text(if (filter == LibraryStatusFilter.ALL) filter.label else "${filter.label} $count", maxLines = 1)
                },
            )
        }
    }
}

internal const val PRIMARY_BACKUP_TAG = "primary-backup"

private val PreviewTypeOptions = listOf(
    "all" to "全部",
    "switch" to "Switch 游戏",
    "pc" to "PC 游戏",
    "emulator-ppsspp" to "PPSSPP",
)

private val PreviewStatusCounts = mapOf(
    LibraryStatusFilter.ALL to 87,
    LibraryStatusFilter.CHANGED to 5,
    LibraryStatusFilter.NOT_UPLOADED to 2,
    LibraryStatusFilter.NEVER_BACKED_UP to 7,
    LibraryStatusFilter.CONFLICT to 0,
)

@ComponentPreviews
@Composable
private fun LibraryToolbarPreview() {
    PreviewSurface {
        LibraryToolbar(
            typeOptions = PreviewTypeOptions,
            selectedTypeId = "switch",
            statusCounts = PreviewStatusCounts,
            selectedStatus = LibraryStatusFilter.ALL,
            visibleCount = 12,
            selectionMode = false,
            selectedCount = 0,
            primary = PrimaryBackup("备份有变更 (12)", setOf("a"), fromSelection = false),
            enabled = true,
            singleRow = false,
            onTypeSelected = {},
            onStatusSelected = {},
            selection = LibrarySelectionActions(),
            onPrimaryBackup = {},
        )
    }
}

@Preview(name = "横屏工具条", widthDp = 753, showBackground = true)
@Preview(name = "横屏工具条 · 字体 1.3", widthDp = 753, fontScale = 1.3f, showBackground = true)
@Composable
private fun LibraryToolbarLandscapePreview() {
    PreviewSurface {
        Column {
            LibraryToolbar(
                typeOptions = PreviewTypeOptions,
                selectedTypeId = "all",
                statusCounts = PreviewStatusCounts,
                selectedStatus = LibraryStatusFilter.CHANGED,
                visibleCount = 5,
                selectionMode = false,
                selectedCount = 0,
                primary = PrimaryBackup("备份有变更 (12)", setOf("a"), fromSelection = false),
                enabled = true,
                singleRow = true,
                onTypeSelected = {},
                onStatusSelected = {},
                selection = LibrarySelectionActions(),
                onPrimaryBackup = {},
            )
            LibraryToolbar(
                typeOptions = PreviewTypeOptions,
                selectedTypeId = "all",
                statusCounts = PreviewStatusCounts,
                selectedStatus = LibraryStatusFilter.ALL,
                visibleCount = 87,
                selectionMode = true,
                selectedCount = 3,
                primary = PrimaryBackup("备份所选 (3)", setOf("a", "b", "c"), fromSelection = true),
                enabled = true,
                singleRow = true,
                onTypeSelected = {},
                onStatusSelected = {},
                selection = LibrarySelectionActions(),
                onPrimaryBackup = {},
            )
        }
    }
}
