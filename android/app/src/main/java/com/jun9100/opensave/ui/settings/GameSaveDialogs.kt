package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.GameSaveConfig
import com.jun9100.opensave.GameSaveTestFailure
import com.jun9100.opensave.ImportedGameSaveConfig
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.ui.dialogs.dialogButtonFocus
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.dpadLeavesTextField
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.preview.OdinScreenPreviews

/**
 * The game-save address and sync key. The draft lives here, so a refresh of
 * the saved config never overwrites it; "测试并保存" stores it only when the
 * connection test passes.
 */
@Composable
internal fun GameSaveEditorDialog(
    initial: GameSaveConfig,
    busy: Boolean,
    onDismiss: () -> Unit,
    onLogin: (url: String, username: String, password: String) -> Unit,
    initialUsername: String = "",
) {
    var url by rememberSaveable { mutableStateOf(initial.serviceUrl) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    val submit = whenIdle(busy) { onLogin(url.trim(), username.trim(), password) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("SaveVault 云端") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Opened on purpose, so the address takes focus and the keyboard right away.
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务地址") },
                    placeholder = { Text("http://nas.example:8788") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().dpadLeavesTextField().focusRequester(rememberInitialFocus()),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().dpadLeavesTextField(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }, modifier = Modifier.handheldFocusRing(CircleShape)) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().dpadLeavesTextField(),
                )
                Text(
                    if (busy) {
                        "正在登录…"
                    } else {
                        "用账号密码登录，成功后把令牌用 Android Keystore 加密保存；密码本身不会存到本机。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = submit, modifier = Modifier.dialogButtonFocus().busyDimmed(busy)) { Text("登录并保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.dialogButtonFocus()) { Text("取消") }
        },
    )
}

/** The connection test failed: save anyway, go back to the draft, or leave the saved config as it is (the default). */
@Composable
internal fun GameSaveTestFailedDialog(
    failure: GameSaveTestFailure,
    busy: Boolean,
    onSaveAnyway: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("连接测试未通过") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(failure.message)
                Text("地址：${failure.config.serviceUrl}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "配置还没有保存。“仍然保存”会改用这个配置，服务可连接之前上传和检查更新都会失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit, modifier = Modifier.dialogButtonFocus()) { Text("返回修改") }
                Button(onClick = whenIdle(busy, onSaveAnyway), modifier = Modifier.dialogButtonFocus().busyDimmed(busy)) { Text("仍然保存") }
            }
        },
        dismissButton = {
            // Saving a config that does not connect replaces a working one: focus starts on the safe choice.
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(rememberInitialFocus()).dialogButtonFocus(),
            ) { Text("取消") }
        },
    )
}

/** A config read from the clipboard: what it would set, the key masked, before "测试并保存". */
@Composable
internal fun ConfigImportConfirmDialog(
    config: ImportedGameSaveConfig,
    replacing: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("导入 SaveVault 云端配置？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务地址：${config.serviceUrl}")
                Text("同步密钥：${config.maskedSyncKey}")
                Text(
                    buildString {
                        append("确认后先测试连接，通过后才保存")
                        if (replacing) append("，并替换当前的 SaveVault 云端配置")
                        append("。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = whenIdle(busy, onConfirm), modifier = Modifier.dialogButtonFocus().busyDimmed(busy)) { Text("测试并保存") }
        },
        dismissButton = {
            // A config from outside the app: nothing changes unless the user moves to confirm.
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(rememberInitialFocus()).dialogButtonFocus(),
            ) { Text("取消") }
        },
    )
}

@OdinScreenPreviews
@Composable
private fun ConfigImportConfirmDialogPreview() {
    OpenSaveTheme {
        ConfigImportConfirmDialog(
            config = ImportedGameSaveConfig("http://192.168.1.5:8788", "super-secret-sync-key"),
            replacing = true,
            busy = false,
            onDismiss = {},
            onConfirm = {},
        )
    }
}
