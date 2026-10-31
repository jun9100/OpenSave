package com.jun9100.opensave.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.ui.LIBRARY_TOOLBAR_TAG
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * The fixed library toolbar above the list; it never scrolls away. Landscape
 * fits it into one 52dp row, portrait uses a chip row and an action row.
 */
@Composable
internal fun LibraryToolbar(
    typeOptions: List<Pair<String, String>>,
    selectedTypeId: String,
    visibleCount: Int,
    selectedCount: Int,
    eligibleCount: Int,
    enabled: Boolean,
    singleRow: Boolean,
    onTypeSelected: (String) -> Unit,
    onToggleAll: () -> Unit,
    onBackupSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(LIBRARY_TOOLBAR_TAG),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        if (singleRow) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SaveTypeFilterBar(
                    options = typeOptions,
                    selectedTypeId = selectedTypeId,
                    enabled = enabled,
                    onSelected = onTypeSelected,
                    modifier = Modifier.weight(1f),
                )
                BatchActions(visibleCount, selectedCount, eligibleCount, enabled, onToggleAll, onBackupSelected)
            }
        } else {
            Column {
                SaveTypeFilterBar(
                    options = typeOptions,
                    selectedTypeId = selectedTypeId,
                    enabled = enabled,
                    onSelected = onTypeSelected,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BatchActions(visibleCount, selectedCount, eligibleCount, enabled, onToggleAll, onBackupSelected, fill = true)
                }
            }
        }
    }
}

@Composable
private fun RowScope.BatchActions(
    visibleCount: Int,
    selectedCount: Int,
    eligibleCount: Int,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onBackupSelected: () -> Unit,
    fill: Boolean = false,
) {
    val allSelected = eligibleCount > 0 && selectedCount == eligibleCount
    Text(
        "$visibleCount 款 · 已选 $selectedCount/$eligibleCount",
        modifier = if (fill) Modifier.weight(1f) else Modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    TextButton(onClick = onToggleAll, enabled = enabled && eligibleCount > 0) {
        Text(if (allSelected) "取消全选" else "全选", maxLines = 1)
    }
    // TODO(plan §5.2): becomes "备份有变更 (N)" once change detection exists.
    // Content-hash dedup (§5.3) already skips unchanged games, but knowing N
    // needs every game's saves copied and hashed, so the label stays honest.
    Button(
        onClick = onBackupSelected,
        enabled = enabled && selectedCount > 0,
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(Icons.Outlined.Save, contentDescription = null)
        Text("备份所选 ($selectedCount)", modifier = Modifier.padding(start = 6.dp), maxLines = 1)
    }
}

@Composable
private fun SaveTypeFilterBar(
    options: List<Pair<String, String>>,
    selectedTypeId: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = selectedTypeId == id,
                onClick = { onSelected(id) },
                enabled = enabled,
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

private val PreviewTypeOptions = listOf(
    "all" to "全部",
    "switch" to "Switch 游戏",
    "pc" to "PC 游戏",
    "emulator-ppsspp" to "PPSSPP",
)

@ComponentPreviews
@Composable
private fun LibraryToolbarPreview() {
    PreviewSurface {
        LibraryToolbar(
            typeOptions = PreviewTypeOptions,
            selectedTypeId = "switch",
            visibleCount = 12,
            selectedCount = 3,
            eligibleCount = 10,
            enabled = true,
            singleRow = false,
            onTypeSelected = {},
            onToggleAll = {},
            onBackupSelected = {},
        )
    }
}

@Preview(name = "横屏工具条", widthDp = 753, showBackground = true)
@Preview(name = "横屏工具条 · 字体 1.3", widthDp = 753, fontScale = 1.3f, showBackground = true)
@Composable
private fun LibraryToolbarLandscapePreview() {
    PreviewSurface {
        LibraryToolbar(
            typeOptions = PreviewTypeOptions,
            selectedTypeId = "all",
            visibleCount = 87,
            selectedCount = 0,
            eligibleCount = 80,
            enabled = true,
            singleRow = true,
            onTypeSelected = {},
            onToggleAll = {},
            onBackupSelected = {},
        )
    }
}
