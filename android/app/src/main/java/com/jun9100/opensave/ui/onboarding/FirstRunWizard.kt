package com.jun9100.opensave.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jun9100.opensave.AccessChoice
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.R
import com.jun9100.opensave.SourceUiState
import com.jun9100.opensave.WizardStep
import com.jun9100.opensave.ui.LocalAdaptiveLayout
import com.jun9100.opensave.ui.ProvideAdaptiveLayout
import com.jun9100.opensave.ui.input.busyDimmed
import com.jun9100.opensave.ui.input.consumeHandheldShortcuts
import com.jun9100.opensave.ui.input.dpadScrollable
import com.jun9100.opensave.ui.input.rememberInitialFocus
import com.jun9100.opensave.ui.input.whenIdle
import com.jun9100.opensave.ui.preview.OdinScreenPreviews
import com.jun9100.opensave.ui.settings.settingButtonFocus

/** What the wizard's controls do; previews pass no-ops. */
internal class WizardActions(
    val onPickFolder: () -> Unit = {},
    val onChooseRoot: () -> Unit = {},
    val onChooseSaf: () -> Unit = {},
    val onEditCloud: () -> Unit = {},
    val onImportFromClipboard: () -> Unit = {},
    /** Asks for the notification permission; null when it was asked already or needs no asking. */
    val onRequestNotifications: (() -> Unit)? = null,
    /** Opens the system notification settings of the app (after a refusal). */
    val onOpenNotificationSettings: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onSkip: () -> Unit = {},
)

/**
 * 首次使用向导 (plan §7 阶段 4): what OpenSave backs up, how it reaches the
 * saves (granted folders, or Root after an explicit choice: su is never
 * called before), and an optional game-save connection. A full-screen dialog,
 * so L1/R1 and the page shortcuts stay out; B goes one step back (on the first
 * step it skips the wizard). Every step starts with focus on its first control.
 */
@Composable
internal fun FirstRunWizard(
    state: SourceUiState,
    step: WizardStep,
    notificationsAllowed: Boolean,
    actions: WizardActions,
) {
    Dialog(
        onDismissRequest = { if (step.previous() == null) actions.onSkip() else actions.onBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // A dialog window has its own size; read it again for the two layouts.
        ProvideAdaptiveLayout(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize().consumeHandheldShortcuts().testTag(FIRST_RUN_WIZARD_TAG),
                color = MaterialTheme.colorScheme.background,
            ) {
                key(step) {
                    WizardContent(state, step, notificationsAllowed, actions, rememberInitialFocus())
                }
            }
        }
    }
}

@Composable
private fun WizardContent(
    state: SourceUiState,
    step: WizardStep,
    notificationsAllowed: Boolean,
    actions: WizardActions,
    initialFocus: FocusRequester,
) {
    val landscape = LocalAdaptiveLayout.current.isLandscape
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = if (landscape) 24.dp else 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.wizard_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(step.title, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                stringResource(R.string.wizard_step_counter, step.number, WizardStep.count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Step 1 has no controls of its own: "下一步" takes the initial focus.
        val firstControl = initialFocus.takeIf { step != WizardStep.WELCOME }
        if (landscape) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ExplanationColumn(Modifier.weight(1f).fillMaxHeight()) { Explanation(state, step) }
                if (step != WizardStep.WELCOME) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { StepControls(state, step, notificationsAllowed, actions, firstControl) }
                }
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Explanation(state, step)
                StepControls(state, step, notificationsAllowed, actions, firstControl)
            }
        }
        NavigationRow(step, actions, initialFocus.takeIf { step == WizardStep.WELCOME })
    }
}

/** Landscape: the text column takes a focus stop only when it overflows, and the D-pad scrolls it. */
@Composable
private fun ExplanationColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier.dpadScrollable(scroll).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun Explanation(state: SourceUiState, step: WizardStep) {
    val body = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (step) {
        WizardStep.WELCOME -> {
            Text(stringResource(R.string.wizard_welcome_body), style = body)
            listOf(
                R.string.wizard_welcome_point_versions,
                R.string.wizard_welcome_point_sources,
                R.string.wizard_welcome_point_conflicts,
                R.string.wizard_welcome_point_pad,
            ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = muted) }
        }
        WizardStep.ACCESS -> {
            Text(stringResource(R.string.wizard_access_body_saf), style = body)
            Text(stringResource(R.string.wizard_access_examples), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(
                if (state.authorizedTrees.isEmpty()) {
                    stringResource(R.string.wizard_access_folders_none)
                } else {
                    stringResource(R.string.wizard_access_folders, state.authorizedTrees.joinToString("、") { it.label })
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.authorizedTrees.isEmpty()) muted else MaterialTheme.colorScheme.primary,
            )
        }
        WizardStep.CLOUD -> {
            Text(stringResource(R.string.wizard_cloud_body), style = body)
            Text(
                if (state.gameSaveSaved) stringResource(R.string.wizard_cloud_connected, state.gameSaveUrl) else stringResource(R.string.wizard_cloud_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.gameSaveSaved) MaterialTheme.colorScheme.primary else muted,
            )
            val messages = state.messages[OpenSavePage.SETTINGS]
            messages.action?.takeIf { !state.busy }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            messages.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    state.progress?.takeIf { state.busy }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun StepControls(
    state: SourceUiState,
    step: WizardStep,
    notificationsAllowed: Boolean,
    actions: WizardActions,
    firstControl: FocusRequester?,
) {
    val busy = state.busy
    val first = Modifier.let { if (firstControl != null) it.focusRequester(firstControl) else it }
    when (step) {
        WizardStep.WELCOME -> Unit
        WizardStep.ACCESS -> {
            Button(
                onClick = whenIdle(busy, actions.onPickFolder),
                modifier = first.fillMaxWidth().settingButtonFocus().busyDimmed(busy),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text(stringResource(R.string.wizard_access_add_folder), modifier = Modifier.padding(start = 8.dp))
            }
            if (state.rootLikelyPresent || state.rootAllowed) {
                RootCard(state, actions)
            } else {
                Text(
                    stringResource(R.string.wizard_access_root_absent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WizardStep.CLOUD -> {
            OutlinedButton(
                onClick = whenIdle(busy, actions.onEditCloud),
                modifier = first.fillMaxWidth().settingButtonFocus().busyDimmed(busy),
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                Text(stringResource(R.string.wizard_cloud_edit), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = whenIdle(busy, actions.onImportFromClipboard),
                modifier = Modifier.fillMaxWidth().settingButtonFocus().busyDimmed(busy),
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                Text(stringResource(R.string.wizard_cloud_import), modifier = Modifier.padding(start = 8.dp))
            }
            NotificationCard(notificationsAllowed, actions)
        }
    }
}

@Composable
private fun RootCard(state: SourceUiState, actions: WizardActions) {
    val busy = state.busy
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.wizard_access_root_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(stringResource(R.string.wizard_access_root_body), style = MaterialTheme.typography.bodySmall)
            val chosen = state.wizardAccess ?: if (state.rootAllowed) AccessChoice.ROOT else null
            chosen?.let {
                Text(
                    stringResource(if (it == AccessChoice.ROOT) R.string.wizard_access_current_root else R.string.wizard_access_current_saf),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.wizardNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            state.wizardError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = whenIdle(busy, actions.onChooseRoot),
                    modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(busy),
                ) { Text(stringResource(R.string.wizard_access_use_root)) }
                OutlinedButton(
                    onClick = whenIdle(busy, actions.onChooseSaf),
                    modifier = Modifier.weight(1f).settingButtonFocus().busyDimmed(busy),
                ) { Text(stringResource(R.string.wizard_access_saf_only)) }
            }
        }
    }
}

/** Asked once: after that only the state and, when refused, the way to the system settings. */
@Composable
private fun NotificationCard(allowed: Boolean, actions: WizardActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.wizard_notifications_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(stringResource(R.string.wizard_notifications_body), style = MaterialTheme.typography.bodySmall)
            val request = actions.onRequestNotifications
            if (allowed) {
                Text(stringResource(R.string.notifications_allowed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else if (request == null) {
                Text(stringResource(R.string.notifications_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            // One button throughout, only its label and action change: removing the focused
            // button after the permission prompt would leave the gamepad with nothing focused.
            OutlinedButton(
                onClick = request ?: actions.onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth().settingButtonFocus(),
            ) {
                Text(
                    stringResource(
                        when {
                            request != null && !allowed -> R.string.wizard_notifications_allow
                            else -> R.string.notifications_open_settings
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun NavigationRow(step: WizardStep, actions: WizardActions, nextFocus: FocusRequester?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (step.previous() != null) {
            TextButton(onClick = actions.onBack, modifier = Modifier.settingButtonFocus()) { Text(stringResource(R.string.wizard_back)) }
        }
        Spacer(Modifier.weight(1f))
        if (step.next() != null) {
            // Skipping is never destructive: the wizard is in 设置 → 关于.
            TextButton(onClick = actions.onSkip, modifier = Modifier.settingButtonFocus()) { Text(stringResource(R.string.wizard_skip)) }
            Spacer(Modifier.width(4.dp))
        }
        Button(
            onClick = actions.onNext,
            modifier = Modifier
                .let { if (nextFocus != null) it.focusRequester(nextFocus) else it }
                .settingButtonFocus(),
        ) { Text(stringResource(if (step.next() == null) R.string.wizard_finish else R.string.wizard_next)) }
    }
}

internal const val FIRST_RUN_WIZARD_TAG = "first-run-wizard"

@OdinScreenPreviews
@Composable
private fun FirstRunWizardPreview() {
    OpenSaveTheme {
        FirstRunWizard(
            state = SourceUiState(rootLikelyPresent = true),
            step = WizardStep.ACCESS,
            notificationsAllowed = false,
            actions = WizardActions(),
        )
    }
}
