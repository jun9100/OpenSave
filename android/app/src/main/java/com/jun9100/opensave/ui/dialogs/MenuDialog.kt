package com.jun9100.opensave.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.preview.OdinScreenPreviews

/** One line of a pad menu; plain data so the entry lists are unit tested. */
internal data class MenuEntry(
    val label: String,
    val detail: String? = null,
    val enabled: Boolean = true,
    /** Marks the current choice of a group, e.g. the active filter. */
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A list of actions in a plain Dialog (plan R6: no ModalBottomSheet, whose D-pad
 * focus is unreliable). The first enabled entry has focus when it opens, every
 * entry is one 48dp+ focus stop, B or "取消" closes it, and choosing an entry
 * closes it before running the action.
 */
@Composable
internal fun HandheldMenuDialog(
    title: String,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    footer: String? = null,
) {
    val firstEnabled = entries.indexOfFirst(MenuEntry::enabled)
    Dialog(onDismissRequest = onDismiss) {
        val initialFocus = rememberInitialFocus()
        Surface(
            modifier = modifier.widthIn(min = 280.dp, max = 440.dp).consumeHandheldShortcuts(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = MENU_LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEachIndexed { index, entry ->
                        MenuRow(
                            entry = entry,
                            focusRequester = initialFocus.takeIf { index == firstEnabled },
                            onChosen = {
                                onDismiss()
                                entry.onClick()
                            },
                        )
                    }
                }
                footer?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .handheldFocusRing(RoundedCornerShape(20.dp))
                            .let { if (firstEnabled < 0) it.focusRequester(initialFocus) else it },
                    ) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry, focusRequester: FocusRequester?, onChosen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .handheldFocusRing(RoundedCornerShape(12.dp), outset = (-2).dp)
            .clickable(enabled = entry.enabled, role = Role.Button, onClick = onChosen)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(MENU_ENTRY_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.checked) {
            Text("当前", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private val MENU_LIST_MAX_HEIGHT = 300.dp

internal const val MENU_ENTRY_TAG = "menu-entry"

@OdinScreenPreviews
@Composable
private fun HandheldMenuDialogPreview() {
    OpenSaveTheme {
        HandheldMenuDialog(
            title = "塞尔达传说 王国之泪",
            subtitle = "Eden 模拟器 · Switch 游戏",
            entries = listOf(
                MenuEntry("备份这款游戏") {},
                MenuEntry("加入批量选择") {},
                MenuEntry("恢复最近的本地版本…", detail = "2026-10-30 21:14") {},
                MenuEntry("查看版本 (3)") {},
                MenuEntry("查看存档位置 (2)", enabled = false) {},
            ),
            onDismiss = {},
        )
    }
}
