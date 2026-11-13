package com.jun9100.opensave.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.AUTH_BLOCKED_SHARED_TOKEN_DISABLED
import com.jun9100.opensave.AUTH_BLOCKED_TOKEN_REVOKED
import com.jun9100.opensave.GameSaveAuthUi
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.whenIdle

/** The credential status wording and colour level; pure, so 设置's lines are unit tested. */
internal object GameSaveAuthText {
    enum class Level { NORMAL, TERTIARY, ERROR }

    fun status(ui: GameSaveAuthUi): Pair<String, Level>? {
        val whoami = ui.whoami
        return when {
            ui.blocked == AUTH_BLOCKED_TOKEN_REVOKED ->
                "此设备已被 SaveVault 云端移除，自动同步已停止。请用配对码重新连接。" to Level.ERROR
            ui.blocked == AUTH_BLOCKED_SHARED_TOKEN_DISABLED ->
                "SaveVault 云端已停用共享同步密钥，请用配对码连接本设备。" to Level.ERROR
            whoami?.auth == AUTH_DEVICE -> "已用设备令牌连接：${whoami.name.ifBlank { "本设备" }}" to Level.NORMAL
            whoami?.auth == "shared" -> "正在使用共享同步密钥" to Level.NORMAL
            else -> null
        }
    }

    /** The second line under "正在使用共享同步密钥"; null when the shared key is not in its warning stage. */
    fun warning(ui: GameSaveAuthUi): String? {
        val whoami = ui.whoami ?: return null
        if (ui.blocked.isNotEmpty() || whoami.auth != "shared") return null
        if (!ui.sharedWarning && whoami.sharedTokenMode != "warn") return null
        return "共享同步密钥即将停用，请配对本设备。"
    }

    private const val AUTH_DEVICE = "device"
}

/**
 * 云端 (plan §3.4): the saved game-save address and key as summary rows; A on
 * either opens the editor. Everything here uses the saved config.
 */
@Composable
internal fun GameSaveCard(
    state: SourceUiState,
    viewModel: SourceViewModel,
    /** The first control of the settings page, focused when the page opens with the pad. */
    initialFocus: FocusRequester? = null,
) {
    val context = LocalContext.current
    val configured = state.gameSaveUrl.isNotBlank() && state.gameSaveSyncKey.isNotBlank()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("云端", style = MaterialTheme.typography.titleMedium)
            Text(
                if (configured) {
                    "SaveVault 云端已配置；PC、Switch 和各模拟器的存档在云端分开存放。"
                } else {
                    "配置 SaveVault 云端后，备份可以上传，并能收到其他设备的更新。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SummaryRow(
                label = "服务地址",
                value = TextSettingSummary.display(state.gameSaveUrl, "http://nas.example:8788", secret = false),
                busy = state.busy,
                onClick = viewModel::openGameSaveEditor,
                modifier = if (initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier,
            )
            SummaryRow(
                label = "账号",
                value = TextSettingSummary.display(state.gameSaveUsername, "未登录", secret = false),
                busy = state.busy,
                onClick = viewModel::openGameSaveEditor,
            )
            GameSaveAuthText.status(state.gameSaveAuth)?.let { (text, level) ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (level) {
                        GameSaveAuthText.Level.ERROR -> MaterialTheme.colorScheme.error
                        GameSaveAuthText.Level.TERTIARY -> MaterialTheme.colorScheme.tertiary
                        GameSaveAuthText.Level.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            GameSaveAuthText.warning(state.gameSaveAuth)?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            OutlinedButton(
                onClick = whenIdle(state.busy) { viewModel.openPairing() },
                modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
            ) {
                Icon(Icons.Outlined.QrCode, contentDescription = null)
                Text("用配对码连接", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = whenIdle(state.busy) { viewModel.importGameSaveConfig(readClipboardText(context)) },
                modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                Text("从剪贴板导入配置", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = whenIdle(state.busy) { viewModel.checkCloudUpdates(OpenSavePage.SETTINGS) },
                modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(state.busy),
            ) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Text("检查跨设备更新", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** A saved value: one focus stop, A opens the editor (plan R7). */
@Composable
private fun SummaryRow(
    label: String,
    value: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .handheldFocusRing(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "编辑$label", onClick = whenIdle(busy, onClick))
            .busyDimmed(busy)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The clipboard's text, read only when the user asks; null when it is empty or unreadable. */
internal fun readClipboardText(context: Context): String? = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()
