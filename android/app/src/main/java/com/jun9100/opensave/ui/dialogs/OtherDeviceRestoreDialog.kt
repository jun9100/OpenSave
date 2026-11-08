package com.jun9100.opensave.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CrossDeviceArchive
import com.jun9100.opensave.CrossDeviceArchiveFormat
import com.jun9100.opensave.CrossDeviceArchivePart
import com.jun9100.opensave.CrossDevicePlan
import com.jun9100.opensave.CrossDeviceTarget
import com.jun9100.opensave.GameSaveCloudGameMember
import com.jun9100.opensave.GameSaveScopeSnapshot
import com.jun9100.opensave.OtherDeviceRestoreRequest
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.dpadScrollable
import com.jun9100.opensave.ui.input.handheldFocusRing
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.kindLabel
import com.jun9100.opensave.ui.library.OtherDeviceText
import com.jun9100.opensave.ui.preview.OdinScreenPreviews
import com.jun9100.opensave.ui.preview.PreviewData
import com.jun9100.opensave.ui.preview.PreviewSurface

/**
 * Maps each folder of another device's archive onto one local location. The
 * user must decide every part; nothing is restored until then. Focus starts on
 * cancel because the restore overwrites local saves (a safety snapshot is taken).
 */
@Composable
internal fun OtherDeviceRestoreDialog(
    request: OtherDeviceRestoreRequest,
    onChoose: (Int, String?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val plan = request.plan
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.consumeHandheldShortcuts(),
        title = { Text("用其他设备的存档恢复") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .dpadScrollable(scroll)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "将用「${OtherDeviceText.deviceName(request.member)}」（${OtherDeviceText.detail(request.member)}）的存档" +
                        "覆盖本机「${request.entry.game.presentation.primaryName}」中下面选中的位置。" +
                        "恢复前会把这些位置的当前内容保存为安全快照；云端的版本和父链不会改动，下次备份会作为本机的新版本上传。",
                )
                Text(
                    when (plan.archive.format) {
                        CrossDeviceArchiveFormat.ANDROID -> "存档格式：Android（按存档类型对应）"
                        CrossDeviceArchiveFormat.PC -> "存档格式：PC（一个存档文件夹）"
                    },
                )
                plan.archive.parts.forEachIndexed { index, part ->
                    PartChoices(
                        part = part,
                        plan = plan,
                        index = index,
                        onChoose = onChoose,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = plan.complete && plan.restoresAnything,
                modifier = Modifier.dialogButtonFocus(),
            ) { Text("确认恢复") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(rememberInitialFocus()).dialogButtonFocus(),
            ) { Text("取消") }
        },
    )
}

@Composable
private fun PartChoices(
    part: CrossDeviceArchivePart,
    plan: CrossDevicePlan,
    index: Int,
    onChoose: (Int, String?) -> Unit,
) {
    val decided = index in plan.mapping
    val title = "${OtherDeviceText.partLabel(part)}恢复到："
    val undecidedColor = MaterialTheme.colorScheme.tertiary
    Text(
        text = buildAnnotatedString {
            append(title)
            if (!decided) withStyle(SpanStyle(color = undecidedColor)) { append("（请选择）") }
        },
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
    plan.targets.forEach { target ->
        ChoiceRow(
            label = "${kindLabel(target.kind)} · ${target.relativePath}",
            selected = plan.mapping[index] == target.relativePath,
            onClick = { onChoose(index, target.relativePath) },
        )
    }
    ChoiceRow(
        label = "跳过",
        selected = index in plan.mapping && plan.mapping[index] == null,
        onClick = { onChoose(index, null) },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .handheldFocusRing(RoundedCornerShape(8.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OdinScreenPreviews
@Composable
private fun OtherDeviceRestoreDialogPreview() {
    val targets = listOf(
        CrossDeviceTarget("nand/user/save/0000000000000000/0123456789ABCDEF/0100F2C0115B6000", "yuzu-switch"),
        CrossDeviceTarget("nand/user/save/cache/0100F2C0115B6000", "yuzu-switch"),
    )
    val plan = CrossDevicePlan(
        archive = CrossDeviceArchive(
            format = CrossDeviceArchiveFormat.ANDROID,
            parts = listOf(
                CrossDeviceArchivePart("part-0000", "yuzu-switch"),
                CrossDeviceArchivePart("part-0001", "yuzu-switch"),
            ),
        ),
        targets = targets,
        mapping = mapOf(0 to targets.first().relativePath),
    )
    val member = GameSaveCloudGameMember(
        type = "switch",
        gameId = "switch-0100f2c0115b6000",
        link = "manual",
        name = "塞尔达传说 王国之泪",
        head = GameSaveScopeSnapshot(
            id = "snap-1",
            archive = "switch-0100f2c0115b6000-20251108.zip",
            parent = "",
            contentDigest = "a".repeat(64),
            createdAt = "2025-11-08T20:13:00Z",
            receivedAt = "2025-11-08T20:13:00Z",
            device = "0123456789abcdef",
            deviceName = "MAR-AL00",
            platform = "opensave-android",
        ),
        snapshotCount = 3,
        conflictCount = 1,
    )
    val request = OtherDeviceRestoreRequest(
        entry = PreviewData.switchEntry,
        member = member,
        archivePath = "/tmp/other-device.zip",
        plan = plan,
    )
    PreviewSurface {
        OtherDeviceRestoreDialog(
            request = request,
            onChoose = { _, _ -> },
            onDismiss = {},
            onConfirm = {},
        )
    }
}
