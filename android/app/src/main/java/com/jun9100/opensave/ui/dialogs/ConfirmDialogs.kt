package com.jun9100.opensave.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.RestoreRequest
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.SourceSelection
import com.jun9100.opensave.StorageMode
import com.jun9100.opensave.WorkspaceWriteBackRequest
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.dpadScrollable
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.preview.OdinScreenPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.sourceAccessLabel

@Composable
internal fun GameBackupConfirmDialog(
    entry: SourceGameEntry,
    onDismiss: () -> Unit,
    onConfirm: (includeCandidates: Boolean) -> Unit,
) {
    val game = entry.game
    val hasConfirmed = game.saves.any { !it.candidate }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("确认备份 ${game.presentation.primaryName}") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .dpadScrollable(scroll)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("来源：${entry.sourceLabel}")
                Text("访问：${sourceAccessLabel(entry.sourceAccess)}")
                Text("已识别位置 ${game.saves.count { !it.candidate }} 个；候选位置 ${game.candidateCount} 个。")
                Text("候选位置只有在你明确确认后才会写入快照。")
                game.saves.forEach { save ->
                    Text(
                        "${if (save.candidate) "候选" else "已识别"} · ${save.relativePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (save.candidate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.dialogButtonFocus()) { Text("取消") }
        },
        confirmButton = {
            // Not destructive, so focus starts on a confirm button: the one that
            // leaves candidates out, since those need an explicit choice.
            val initialFocus = rememberInitialFocus()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onConfirm(false) },
                    enabled = hasConfirmed,
                    modifier = Modifier.let { if (hasConfirmed) it.focusRequester(initialFocus) else it }.dialogButtonFocus(),
                ) { Text("只备份已识别") }
                Button(
                    onClick = { onConfirm(true) },
                    modifier = Modifier.let { if (!hasConfirmed) it.focusRequester(initialFocus) else it }.dialogButtonFocus(),
                ) {
                    Text("包含候选")
                }
            }
        },
    )
}

@Composable
internal fun WorkspaceWriteBackDialog(
    request: WorkspaceWriteBackRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("把工作区写回来源目录？") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .dpadScrollable(scroll)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("目标：${request.label}")
                Text(
                    if (request.source is SourceSelection.Root) {
                        "私有工作区会替换该 Root 目录的全部内容。"
                    } else {
                        "私有工作区的文件会写入该系统授权目录，同名文件会被覆盖。"
                    },
                )
                Text("写回前会先把来源目录当前的内容保存为安全快照，可在“本地快照”中恢复。")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.dialogButtonFocus()) { Text("确认写回") }
        },
        dismissButton = {
            // Destructive: focus starts on the safe choice.
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(rememberInitialFocus()).dialogButtonFocus(),
            ) { Text("取消") }
        },
    )
}

@Composable
internal fun RestoreConfirmDialog(
    request: RestoreRequest,
    storageMode: StorageMode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val gameRestore = request.game != null
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text(if (gameRestore) "恢复游戏存档" else "恢复整份存档快照") },
        text = {
            val scroll = rememberScrollState()
            Text(
                modifier = Modifier.heightIn(max = 280.dp).dpadScrollable(scroll).verticalScroll(scroll),
                text = if (gameRestore) {
                    "将恢复 ${request.game?.presentation?.primaryName}。程序会验证当前扫描到的游戏身份和快照路径，只替换 ZIP 清单所列位置，并先把这些位置的当前内容保存为新的安全快照。"
                } else if (storageMode == StorageMode.ROOT) {
                    "此操作会替换 Root 来源目录内容。请确认路径为专用存档目录。"
                } else {
                    "此操作会删除授权目录内现有内容并写入快照。请确认选择的是专用存档目录。"
                },
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.dialogButtonFocus()) { Text("确认恢复") }
        },
        dismissButton = {
            // Destructive: focus starts on the safe choice.
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(rememberInitialFocus()).dialogButtonFocus(),
            ) { Text("取消") }
        },
    )
}

/** Focus ring for dialog buttons (pill shaped). */
@Composable
internal fun Modifier.dialogButtonFocus(): Modifier = handheldFocusRing(RoundedCornerShape(20.dp))

@OdinScreenPreviews
@Composable
private fun GameBackupConfirmDialogPreview() {
    OpenSaveTheme {
        GameBackupConfirmDialog(entry = PreviewData.switchEntry, onDismiss = {}, onConfirm = {})
    }
}

@OdinScreenPreviews
@Composable
private fun WorkspaceWriteBackDialogPreview() {
    OpenSaveTheme {
        WorkspaceWriteBackDialog(
            request = WorkspaceWriteBackRequest(
                source = SourceSelection.Root("/data/user/0/dev.eden.eden_emulator/files"),
                label = "Eden 模拟器",
            ),
            onDismiss = {},
            onConfirm = {},
        )
    }
}

@OdinScreenPreviews
@Composable
private fun RestoreConfirmDialogPreview() {
    OpenSaveTheme {
        RestoreConfirmDialog(
            request = RestoreRequest(game = PreviewData.switchGame),
            storageMode = StorageMode.ROOT,
            onDismiss = {},
            onConfirm = {},
        )
    }
}
