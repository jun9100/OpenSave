package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.ui.dialogs.dialogButtonFocus
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.preview.ComponentPreviews
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * A text setting shown as a read-only summary row (plan R7). D-pad traversal
 * passes over it without opening the IME; A or a tap opens [TextEditDialog],
 * and only that dialog's field takes the keyboard.
 */
@Composable
internal fun TextSettingRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    onValueChange: (String) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .handheldFocusRing(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "编辑$label") { editing = true }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                TextSettingSummary.display(value, placeholder, secret),
                style = MaterialTheme.typography.bodyMedium,
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (editing) {
        TextEditDialog(
            title = label,
            initial = value,
            placeholder = placeholder,
            secret = secret,
            keyboardType = keyboardType,
            onDismiss = { editing = false },
            onConfirm = {
                editing = false
                onValueChange(it)
            },
        )
    }
}

/**
 * A switch setting whose whole row is the toggle, so A anywhere on it flips it
 * and the row is one focus stop (plan R1).
 */
@Composable
internal fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .handheldFocusRing(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.padding(end = 8.dp)) }
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Focus ring for the pill-shaped buttons of the settings cards. */
@Composable
internal fun Modifier.settingButtonFocus(): Modifier = handheldFocusRing(RoundedCornerShape(20.dp))

/** What the summary row shows; pure so it is unit tested. */
internal object TextSettingSummary {
    fun display(value: String, placeholder: String, secret: Boolean): String = when {
        value.isBlank() -> if (placeholder.isBlank()) "未设置" else "未设置 · 例如 $placeholder"
        // Fixed width: the summary does not even reveal the length.
        secret -> "已设置 · ••••••••"
        else -> value
    }
}

/** One text field in a dialog; the field takes focus and the keyboard when it opens. */
@Composable
internal fun TextEditDialog(
    title: String,
    initial: String,
    placeholder: String,
    secret: Boolean,
    keyboardType: KeyboardType,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    confirmLabel: String = "确定",
    note: String? = null,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var visible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text(title) },
        text = {
            Column {
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Opened on purpose, so the field takes focus and the keyboard right away.
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
                    singleLine = true,
                    visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else keyboardType,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text.trim()) }),
                    trailingIcon = if (secret) {
                        {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (visible) "隐藏$title" else "显示$title",
                                )
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(rememberInitialFocus()),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }, modifier = Modifier.dialogButtonFocus()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.dialogButtonFocus()) { Text("取消") }
        },
    )
}

@ComponentPreviews
@Composable
private fun TextSettingRowPreview() {
    PreviewSurface {
        Column(modifier = Modifier.padding(16.dp)) {
            TextSettingRow(label = "game-save 服务地址", value = "", placeholder = "http://nas.example:8788", onValueChange = {})
            TextSettingRow(label = "同步密钥", value = "abcdefgh", secret = true, onValueChange = {})
        }
    }
}
