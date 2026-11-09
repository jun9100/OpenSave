package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.PairingCode
import com.jun9100.opensave.PairingDraft
import com.jun9100.opensave.PairingLink
import com.jun9100.opensave.ui.dialogs.dialogButtonFocus
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.dpadLeavesTextField
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.preview.OdinScreenPreviews

/**
 * "用配对码连接": the address and a code, or a pasted `savevault://pair` link.
 * Nothing is sent here; "下一步" only moves to [PairingConfirmDialog], so a
 * mistyped host is seen before any request carries the code.
 */
@Composable
internal fun PairingDialog(
    draft: PairingDraft,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (url: String, codeOrLink: String) -> Unit,
    /** The clipboard's text, or null when it is empty/unreadable; read only when the user asks. */
    onPaste: (String?) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(draft.serviceUrl) }
    var code by rememberSaveable { mutableStateOf(draft.code) }
    val context = LocalContext.current
    val submit = whenIdle(busy) { onSubmit(url.trim(), code.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("用配对码连接") },
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
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("配对码") },
                    placeholder = { Text("ABCD-EFGH") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().dpadLeavesTextField(),
                )
                Text(
                    "在 SaveVault 云端网页生成配对码。也可以用系统相机扫描配对二维码，或粘贴 savevault://pair 链接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onPaste(readClipboardText(context)) },
                    modifier = Modifier.dialogButtonFocus(),
                ) { Text("粘贴配对链接") }
            }
        },
        confirmButton = {
            Button(onClick = submit, modifier = Modifier.dialogButtonFocus().busyDimmed(busy)) { Text("下一步") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.dialogButtonFocus()) { Text("取消") }
        },
    )
}

/**
 * The server a pairing link points at, shown before the code is claimed: the
 * host decides where every later save is uploaded (the link may be forged).
 */
@Composable
internal fun PairingConfirmDialog(
    link: PairingLink,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("确认 SaveVault 云端") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(link.host, style = MaterialTheme.typography.titleMedium)
                Text("配对后，本设备的存档会上传到这台服务器。只有在确认这是你自己的 SaveVault 云端时才继续。")
                Text(
                    link.serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "配对码：${PairingCode.display(link.code)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = whenIdle(busy, onConfirm),
                modifier = Modifier.dialogButtonFocus().busyDimmed(busy),
            ) { Text("确认并配对") }
        },
        dismissButton = {
            // The link decides where saves go: focus starts on the safe choice.
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(rememberInitialFocus()).dialogButtonFocus(),
            ) { Text("取消") }
        },
    )
}

@OdinScreenPreviews
@Composable
private fun PairingDialogPreview() {
    OpenSaveTheme {
        PairingDialog(
            draft = PairingDraft("http://192.168.1.5:8788", ""),
            busy = false,
            onDismiss = {},
            onSubmit = { _, _ -> },
            onPaste = {},
        )
    }
}

@OdinScreenPreviews
@Composable
private fun PairingConfirmDialogPreview() {
    OpenSaveTheme {
        PairingConfirmDialog(
            link = PairingLink("https://nas.example/game-save", "ABCDEFGH"),
            busy = false,
            onDismiss = {},
            onConfirm = {},
        )
    }
}
