package com.jun9100.opensave.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.ui.CommandDialog
import com.jun9100.opensave.ui.LocalAdaptiveLayout
import com.jun9100.opensave.ui.dialogs.MenuEntry
import com.jun9100.opensave.ui.input.HandheldCommand
import com.jun9100.opensave.ui.input.HandheldCommandEffect
import com.jun9100.opensave.ui.input.LocalShellFocus
import com.jun9100.opensave.ui.input.RightStickScroll
import com.jun9100.opensave.ui.input.handheldNavigationActive
import com.jun9100.opensave.ui.input.tryRequestFocus
import kotlinx.coroutines.launch

/**
 * The frame of the 来源, 设置 and 同步与任务 pages. Landscape: two columns that
 * scroll on their own, each a focus group that remembers its last control.
 * Portrait: one column, [left] then [right]. Pad: the first control (the
 * requester handed to [left]) gets focus when the page opens or focus drops,
 * Y opens the page's command panel, L2/R2 and the right stick scroll the
 * column that holds focus.
 */
@Composable
internal fun HandheldPageColumns(
    commandsTitle: String,
    commands: () -> List<MenuEntry>,
    busy: Boolean,
    modifier: Modifier = Modifier,
    /** Blocks the initial focus while the page shows a dialog of its own. */
    dialogOpen: Boolean = false,
    left: @Composable ColumnScope.(firstControl: FocusRequester) -> Unit,
    right: @Composable ColumnScope.(firstControl: FocusRequester) -> Unit = {},
) {
    val landscape = LocalAdaptiveLayout.current.isLandscape
    val handheld = handheldNavigationActive()
    val shellFocus = LocalShellFocus.current
    val scope = rememberCoroutineScope()
    val firstControl = remember { FocusRequester() }
    // Landscape: focus that drops in the right column goes back to its top, not across to the left.
    val rightFirstControl = remember { FocusRequester() }
    var lastFocusRight by remember { mutableStateOf(false) }
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    var rightHasFocus by remember { mutableStateOf(false) }
    var commandsOpen by rememberSaveable { mutableStateOf(false) }
    val activeScroll = if (landscape && rightHasFocus) rightScroll else leftScroll

    LaunchedEffect(handheld, shellFocus.hasFocus, commandsOpen, dialogOpen) {
        if (!handheld || shellFocus.hasFocus || commandsOpen || dialogOpen) return@LaunchedEffect
        withFrameNanos { }
        val restored = landscape && lastFocusRight && rightFirstControl.tryRequestFocus()
        if (!restored && !firstControl.tryRequestFocus()) rightFirstControl.tryRequestFocus()
    }
    // Entering the page with the pad (L1/R1, rail) starts on its first control:
    // focus left over on the rail would otherwise keep the effect above idle.
    LaunchedEffect(Unit) {
        if (!handheld) return@LaunchedEffect
        withFrameNanos { }
        if (!firstControl.tryRequestFocus()) rightFirstControl.tryRequestFocus()
    }
    HandheldCommandEffect { command ->
        when (command) {
            HandheldCommand.COMMAND_PANEL -> commandsOpen = true
            HandheldCommand.PAGE_UP, HandheldCommand.PAGE_DOWN -> scope.launch {
                val step = activeScroll.viewportSize * PAGE_SCROLL_FRACTION
                activeScroll.animateScrollBy(if (command == HandheldCommand.PAGE_DOWN) step else -step)
            }
            else -> Unit
        }
    }
    RightStickScroll(activeScroll)
    if (commandsOpen) {
        CommandDialog(
            title = commandsTitle,
            entries = commands(),
            busy = busy,
            onDismiss = { commandsOpen = false },
        )
    }

    if (landscape) {
        Row(
            modifier = modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { if (it.hasFocus) lastFocusRight = false }
                    .focusRestorer()
                    .focusGroup()
                    .verticalScroll(leftScroll)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { left(firstControl) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged {
                        rightHasFocus = it.hasFocus
                        if (it.hasFocus) lastFocusRight = true
                    }
                    .focusRestorer()
                    .focusGroup()
                    .verticalScroll(rightScroll)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { right(rightFirstControl) }
        }
    } else {
        Column(
            modifier = modifier.verticalScroll(leftScroll).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            left(firstControl)
            right(rightFirstControl)
        }
    }
}

private const val PAGE_SCROLL_FRACTION = 0.85f
