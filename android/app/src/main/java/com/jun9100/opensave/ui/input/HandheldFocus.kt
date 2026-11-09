package com.jun9100.opensave.ui.input

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pad or D-pad navigation is in use: either our own gamepad flag or Compose's keyboard input mode. */
@Composable
fun handheldNavigationActive(): Boolean =
    LocalGamepadActive.current || LocalInputModeManager.current.inputMode == InputMode.Keyboard

/**
 * Visible focus for pad users (plan §4.2 item 4): a 3dp primary ring around
 * the control plus a light primary tint behind it. Nothing is drawn for touch
 * input. Put it before the clickable/toggleable of the control (or pass it in
 * the `modifier` of a Material control) so it sees that control's focus.
 * A positive [outset] draws the ring outside the bounds (buttons, chips); rows
 * that fill a clipped list use a negative one so the ring stays inside.
 */
@Composable
fun Modifier.handheldFocusRing(
    shape: Shape = RoundedCornerShape(12.dp),
    outset: Dp = 2.dp,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val show = focused && handheldNavigationActive()
    val ring = MaterialTheme.colorScheme.primary
    val tint = ring.copy(alpha = 0.14f)
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            if (show) drawOutline(shape.createOutline(size, layoutDirection, this), tint)
            drawContent()
            if (show) {
                val offset = outset.toPx()
                val ringSize = Size(size.width + 2 * offset, size.height + 2 * offset)
                if (ringSize.width > 0f && ringSize.height > 0f) {
                    translate(-offset, -offset) {
                        drawOutline(shape.createOutline(ringSize, layoutDirection, this), ring, style = Stroke(FOCUS_RING_WIDTH.toPx()))
                    }
                }
            }
        }
}

val FOCUS_RING_WIDTH = 3.dp

/** Whether anything inside a region holds focus; the shell uses it to decide when to place focus. */
@Stable
class FocusWatch {
    var hasFocus by mutableStateOf(false)
}

/** The whole app window; pages put focus back when it is empty and the pad is in use. */
val LocalShellFocus = staticCompositionLocalOf { FocusWatch() }

fun Modifier.watchFocus(watch: FocusWatch): Modifier = onFocusChanged { watch.hasFocus = it.hasFocus }

/**
 * A long, otherwise unfocusable text block (dialog bodies, reports): it takes
 * one focus stop when it overflows, and Up/Down scroll it until its end
 * before focus moves on.
 */
@Composable
fun Modifier.dpadScrollable(state: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { DPAD_SCROLL_STEP.toPx() }
    return this
        .handheldFocusRing(RoundedCornerShape(8.dp), outset = 0.dp)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.DirectionDown -> state.canScrollForward.also { if (it) scope.launch { state.animateScrollBy(step) } }
                Key.DirectionUp -> state.canScrollBackward.also { if (it) scope.launch { state.animateScrollBy(-step) } }
                else -> false
            }
        }
        // Text that fits needs no focus stop of its own.
        .focusProperties { canFocus = state.maxValue > 0 }
        .focusable()
}

private val DPAD_SCROLL_STEP = 96.dp

/**
 * A single-line text field keeps Up/Down to itself, so with the pad the user
 * could type an address but never reach the next field or the dialog's
 * buttons. Up/Down move focus out instead; Left/Right still move the cursor.
 */
@Composable
fun Modifier.dpadLeavesTextField(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/**
 * Dialogs are their own windows, so `MainActivity` never sees their keys.
 * Swallow the shortcut keys there too, otherwise X/Start fall back to
 * DPAD_CENTER and press whatever button holds focus.
 */
fun Modifier.consumeHandheldShortcuts(): Modifier =
    onPreviewKeyEvent { HandheldKeyMap.isConsumed(it.nativeKeyEvent.keyCode) }

/**
 * Controls stay enabled while an operation runs so D-pad focus never drops
 * (plan R4); the click is ignored instead and the control is dimmed.
 */
fun whenIdle(busy: Boolean, action: () -> Unit): () -> Unit = {
    if (busy) BusyNotice.notifyBlocked() else action()
}

fun <T> whenIdleWith(busy: Boolean, action: (T) -> Unit): (T) -> Unit = {
    if (busy) BusyNotice.notifyBlocked() else action(it)
}

/**
 * Presses ignored while something runs (plan R4). The status strip, which is
 * on screen exactly while busy, says "正在处理，稍后再试" for a moment so a pad
 * user can tell a refused press from a missed one.
 */
object BusyNotice {
    private val count = MutableStateFlow(0)

    /** Grows by one per refused press; the strip shows the notice on each change. */
    val blockedCount: StateFlow<Int> = count.asStateFlow()

    fun notifyBlocked() {
        count.update { it + 1 }
    }

    const val TEXT = "正在处理，稍后再试"
    const val SHOW_MILLIS = 2_000L
}

fun Modifier.busyDimmed(busy: Boolean): Modifier = if (busy) alpha(BUSY_ALPHA) else this

private const val BUSY_ALPHA = 0.5f

/** requestFocus throws when the requester is not attached yet (item scrolled away, dialog not laid out). */
fun FocusRequester.tryRequestFocus(): Boolean = runCatching { requestFocus() }.isSuccess

/**
 * A requester focused once when the dialog or page holding it appears. A new
 * dialog window may need a few frames before its nodes are attached and can
 * drop a request made before the window gains focus, so the request is
 * repeated until the target really holds focus (checked with captureFocus).
 */
@Composable
fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    val view = LocalView.current
    LaunchedEffect(requester) {
        repeat(INITIAL_FOCUS_ATTEMPTS) {
            withFrameNanos { }
            // A new dialog window starts in touch mode, where its ComposeView
            // refuses view focus; the Compose request is then accepted but
            // nothing gets focused (seen on the Odin2).
            if (!view.hasFocus()) view.requestFocusFromTouch()
            if (requester.tryRequestFocus() && requester.holdsFocus()) return@LaunchedEffect
            delay(INITIAL_FOCUS_RETRY_MS)
        }
    }
    return requester
}

/** True when the requester's node is the focused one; captureFocus only succeeds on a focused node. */
private fun FocusRequester.holdsFocus(): Boolean = runCatching {
    captureFocus().also { captured -> if (captured) freeFocus() }
}.getOrDefault(false)

private const val INITIAL_FOCUS_ATTEMPTS = 30
private const val INITIAL_FOCUS_RETRY_MS = 50L
