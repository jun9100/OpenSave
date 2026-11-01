package com.jun9100.opensave.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
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
import com.jun9100.opensave.BatchPlan
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.batch.BatchOperation
import com.jun9100.opensave.ui.dialogs.dialogButtonFocus
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.dpadScrollable
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.preview.OdinScreenPreviews

/**
 * The one confirmation of a destructive batch (plan §5.7): how many games,
 * which ones, the safety snapshot taken first, and the conflicts left out.
 * Focus starts on "取消".
 */
@Composable
internal fun BatchPlanDialog(
    plan: BatchPlan,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("安全恢复 ${plan.keys.size} 款云端较新的存档？") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .dpadScrollable(scroll)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("每款游戏恢复前，会先把当前存档保存为安全快照；开始恢复时会重新检查云端，已不再安全的会跳过。")
                Text(BatchPlanText.names(plan.names), style = MaterialTheme.typography.bodySmall)
                if (plan.conflictNames.isNotEmpty()) {
                    Text(
                        "另有 ${plan.conflictNames.size} 款冲突需确认，不会处理：${BatchPlanText.names(plan.conflictNames)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
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

internal object BatchPlanText {
    private const val MAX_NAMES = 12

    /** "A、B、C 等 15 款" once the list is long. */
    fun names(names: List<String>): String =
        if (names.size <= MAX_NAMES) {
            names.joinToString("、")
        } else {
            names.take(MAX_NAMES).joinToString("、") + " 等 ${names.size} 款"
        }
}

@OdinScreenPreviews
@Composable
private fun BatchPlanDialogPreview() {
    OpenSaveTheme {
        BatchPlanDialog(
            plan = BatchPlan(
                operation = BatchOperation.RESTORE_CLOUD_NEWER,
                keys = listOf("a", "b"),
                names = listOf("塞尔达传说 王国之泪", "宝可梦 朱"),
                conflictNames = listOf("Hades"),
                page = OpenSavePage.LIBRARY,
            ),
            onDismiss = {},
            onConfirm = {},
        )
    }
}
