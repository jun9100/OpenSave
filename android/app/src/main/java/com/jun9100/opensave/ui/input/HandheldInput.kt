package com.jun9100.opensave.ui.input

import android.view.KeyEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.OpenSavePage
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Semantic gamepad commands (plan §4.1). A, B and the D-pad are not here: they
 * reach Compose and the BackHandler chain untouched, so clickables activate
 * on A (Generic.kcm falls back to DPAD_CENTER) and B pops the back stack.
 */
enum class HandheldCommand {
    /** L1: previous top-level page. */
    PREVIOUS_PAGE,

    /** R1: next top-level page. */
    NEXT_PAGE,

    /** L2: one screen up in the current list. */
    PAGE_UP,

    /** R2: one screen down in the current list. */
    PAGE_DOWN,

    /** X: the action menu of the current game. */
    GAME_MENU,

    /** Y: the command panel (scan, cloud, filter, select all). */
    COMMAND_PANEL,

    /** Select: add the current game to the batch selection, or take it out. */
    TOGGLE_SELECTION,

    /** Start: back up the selection, or the current game when nothing is selected. */
    BACKUP,
}

/** What [HandheldKeyMap.decide] tells `MainActivity.dispatchKeyEvent` to do with one key event. */
sealed interface KeyDecision {
    /** Not ours: Compose, the kcm fallbacks and the system get it. */
    data object Pass : KeyDecision

    /** Ours but nothing to do (key up, auto-repeat): swallowed so no fallback key is generated. */
    data object Consume : KeyDecision

    data class Dispatch(val command: HandheldCommand) : KeyDecision
}

/** Pure key mapping, unit tested without a device. */
object HandheldKeyMap {
    fun commandFor(keyCode: Int): HandheldCommand? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L1 -> HandheldCommand.PREVIOUS_PAGE
        KeyEvent.KEYCODE_BUTTON_R1 -> HandheldCommand.NEXT_PAGE
        KeyEvent.KEYCODE_BUTTON_L2 -> HandheldCommand.PAGE_UP
        KeyEvent.KEYCODE_BUTTON_R2 -> HandheldCommand.PAGE_DOWN
        KeyEvent.KEYCODE_BUTTON_X -> HandheldCommand.GAME_MENU
        KeyEvent.KEYCODE_BUTTON_Y -> HandheldCommand.COMMAND_PANEL
        KeyEvent.KEYCODE_BUTTON_SELECT -> HandheldCommand.TOGGLE_SELECTION
        KeyEvent.KEYCODE_BUTTON_START -> HandheldCommand.BACKUP
        else -> null
    }

    /**
     * Both the down and the up of a mapped key are consumed: an unhandled one
     * makes Generic.kcm send its fallback (X/Start -> DPAD_CENTER would click
     * the focused button, Y -> BACK, Select -> MENU). Only the first down acts.
     */
    fun decide(keyCode: Int, isDown: Boolean, repeatCount: Int): KeyDecision {
        if (keyCode in SWALLOWED_KEYS) return KeyDecision.Consume
        val command = commandFor(keyCode) ?: return KeyDecision.Pass
        return if (isDown && repeatCount == 0) KeyDecision.Dispatch(command) else KeyDecision.Consume
    }

    /** Keys the app swallows without a command: mapped ones plus [SWALLOWED_KEYS]. Dialogs use it too. */
    fun isConsumed(keyCode: Int): Boolean = commandFor(keyCode) != null || keyCode in SWALLOWED_KEYS

    /**
     * The first D-pad press after touch (or since start) only switches to pad
     * mode: the page then puts focus on its preferred control (last row, first
     * row, first setting). Passing it on would let Compose's own focus search
     * land on whatever is topmost (a filter chip, a rail item) first.
     */
    fun isWakeKey(keyCode: Int, isDown: Boolean, repeatCount: Int, wasActive: Boolean): Boolean =
        !wasActive && isDown && repeatCount == 0 && keyCode in DPAD_DIRECTION_KEYS

    /** Whether a key means the user is on the pad or D-pad rather than the touchscreen. */
    fun marksHandheld(keyCode: Int, fromGamepadSource: Boolean): Boolean =
        fromGamepadSource || keyCode in DPAD_KEYS || keyCode in GAMEPAD_BUTTONS

    private val DPAD_DIRECTION_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    private val DPAD_KEYS = DPAD_DIRECTION_KEYS + KeyEvent.KEYCODE_DPAD_CENTER

    /**
     * Unused pad buttons, swallowed down and up: Generic.kcm falls the stick
     * clicks back to DPAD_CENTER, which would press the focused control while
     * the user pushes the right stick to scroll. C/Z/MODE for other pads.
     */
    private val SWALLOWED_KEYS = setOf(
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_C,
        KeyEvent.KEYCODE_BUTTON_Z,
        KeyEvent.KEYCODE_BUTTON_MODE,
    )

    /** BUTTON_A .. BUTTON_MODE; spelled out because KeyEvent.isGamepadButton is not in JVM unit tests. */
    private val GAMEPAD_BUTTONS = KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE
}

/** Pure decisions behind the commands, so they are unit tested apart from Compose. */
object HandheldDispatch {
    /** Top-level pages in rail order; L1/R1 cycle through them. */
    fun pageAfter(page: OpenSavePage, command: HandheldCommand): OpenSavePage? {
        val pages = OpenSavePage.entries
        val step = when (command) {
            HandheldCommand.PREVIOUS_PAGE -> -1
            HandheldCommand.NEXT_PAGE -> 1
            else -> return null
        }
        return pages[(pages.indexOf(page) + step + pages.size) % pages.size]
    }

    /**
     * The game X, Select and Start act on: the open portrait detail, else the
     * focused row, else the game the landscape pane shows. Portrait with no
     * focused row has none: nothing on screen says which game it would be.
     */
    fun targetGame(
        focusedKey: String?,
        selectedKey: String?,
        visibleKeys: List<String>,
        landscape: Boolean,
        detailOpen: Boolean,
    ): String? {
        if (!landscape && detailOpen) return selectedKey?.takeIf { it in visibleKeys }
        focusedKey?.takeIf { it in visibleKeys }?.let { return it }
        return if (landscape) selectedKey?.takeIf { it in visibleKeys } ?: visibleKeys.firstOrNull() else null
    }

    /** The row that gets focus when the list (re)gains it: the last focused, the shown game, the first. */
    fun preferredFocusKey(lastFocusedKey: String?, selectedKey: String?, visibleKeys: List<String>): String? =
        lastFocusedKey?.takeIf { it in visibleKeys }
            ?: selectedKey?.takeIf { it in visibleKeys }
            ?: visibleKeys.firstOrNull()

    /** Row index L2/R2 jump to: one screen of rows up or down, clamped to the list. */
    fun pageJumpIndex(currentIndex: Int, visibleRows: Int, total: Int, forward: Boolean): Int {
        if (total <= 0) return 0
        val step = (visibleRows - 1).coerceAtLeast(1)
        return (currentIndex + if (forward) step else -step).coerceIn(0, total - 1)
    }

    /** What the library does with a command (pages and the panel are handled by the shell). */
    fun libraryAction(
        command: HandheldCommand,
        targetKey: String?,
        targetEligible: Boolean,
        targetSelected: Boolean,
        selectedKeys: Set<String>,
    ): LibraryHandheldAction = when (command) {
        HandheldCommand.GAME_MENU -> targetKey?.let(LibraryHandheldAction::OpenMenu) ?: LibraryHandheldAction.None
        HandheldCommand.TOGGLE_SELECTION -> if (targetKey != null && targetEligible) {
            LibraryHandheldAction.SetSelected(targetKey, !targetSelected)
        } else {
            LibraryHandheldAction.None
        }
        // Same as the toolbar's "备份所选 (N)"; with nothing selected, the game in front of the user.
        HandheldCommand.BACKUP -> when {
            selectedKeys.isNotEmpty() -> LibraryHandheldAction.BackupSelected(selectedKeys)
            targetKey != null -> LibraryHandheldAction.BackupGame(targetKey)
            else -> LibraryHandheldAction.BackupSelected(emptySet())
        }
        HandheldCommand.PAGE_UP -> LibraryHandheldAction.Page(forward = false)
        HandheldCommand.PAGE_DOWN -> LibraryHandheldAction.Page(forward = true)
        HandheldCommand.PREVIOUS_PAGE, HandheldCommand.NEXT_PAGE, HandheldCommand.COMMAND_PANEL ->
            LibraryHandheldAction.None
    }

    /** Right-stick value with the dead zone removed and rescaled to -1..1. */
    fun stickValue(raw: Float, deadZone: Float = STICK_DEAD_ZONE): Float {
        val magnitude = abs(raw)
        if (magnitude <= deadZone) return 0f
        return sign(raw) * ((magnitude - deadZone) / (1f - deadZone)).coerceAtMost(1f)
    }

    const val STICK_DEAD_ZONE = 0.2f
}

sealed interface LibraryHandheldAction {
    data object None : LibraryHandheldAction
    data class OpenMenu(val key: String) : LibraryHandheldAction
    data class SetSelected(val key: String, val selected: Boolean) : LibraryHandheldAction
    data class BackupSelected(val keys: Set<String>) : LibraryHandheldAction
    data class BackupGame(val key: String) : LibraryHandheldAction
    data class Page(val forward: Boolean) : LibraryHandheldAction
}

/**
 * Bridge from `MainActivity` (which sees keys even when no Compose node has
 * focus) to the screens. Every collector of [commands] gets every command and
 * acts on those of its own page.
 */
class HandheldInput {
    private val commandFlow = MutableSharedFlow<HandheldCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<HandheldCommand> = commandFlow.asSharedFlow()

    private val active = MutableStateFlow(false)

    /** The last input came from the pad or D-pad (not touch): focus rings and hints show. */
    val gamepadActive: StateFlow<Boolean> = active.asStateFlow()

    private val stick = MutableStateFlow(0f)

    /** Right stick vertical position after the dead zone, -1 (up) .. 1 (down). */
    val rightStickY: StateFlow<Float> = stick.asStateFlow()

    /** Returns true when the event is consumed. */
    /** D-pad keys whose down woke pad mode; their repeats and up are swallowed as well. */
    private val wakeKeys = mutableSetOf<Int>()

    fun onKey(keyCode: Int, isDown: Boolean, repeatCount: Int, fromGamepadSource: Boolean): Boolean {
        val wasActive = active.value
        if (HandheldKeyMap.marksHandheld(keyCode, fromGamepadSource)) active.value = true
        if (HandheldKeyMap.isWakeKey(keyCode, isDown, repeatCount, wasActive)) {
            wakeKeys += keyCode
            return true
        }
        if (keyCode in wakeKeys) {
            if (!isDown) wakeKeys -= keyCode
            return true
        }
        return when (val decision = HandheldKeyMap.decide(keyCode, isDown, repeatCount)) {
            KeyDecision.Pass -> false
            KeyDecision.Consume -> true
            is KeyDecision.Dispatch -> {
                commandFlow.tryEmit(decision.command)
                true
            }
        }
    }

    fun onRightStick(rawY: Float) {
        val value = HandheldDispatch.stickValue(rawY)
        if (value != 0f) active.value = true
        stick.value = value
    }

    fun onTouch() {
        active.value = false
        stick.value = 0f
    }

    /** For tests and previews: acts as if the pad sent [command]. */
    fun send(command: HandheldCommand) {
        active.value = true
        commandFlow.tryEmit(command)
    }
}

/** Null in previews and tests that do not provide one; screens then ignore the pad. */
val LocalHandheldInput = staticCompositionLocalOf<HandheldInput?> { null }

/** Whether the last input came from the pad; read by focus rings, hints and initial focus. */
val LocalGamepadActive = compositionLocalOf { false }

@Composable
fun ProvideHandheldInput(input: HandheldInput, content: @Composable () -> Unit) {
    val active by input.gamepadActive.collectAsState()
    CompositionLocalProvider(
        LocalHandheldInput provides input,
        LocalGamepadActive provides active,
        content = content,
    )
}

/** Collects pad commands while composed; each page installs its own. */
@Composable
fun HandheldCommandEffect(onCommand: (HandheldCommand) -> Unit) {
    val input = LocalHandheldInput.current ?: return
    val current = rememberUpdatedState(onCommand)
    LaunchedEffect(input) {
        input.commands.collect { current.value(it) }
    }
}

/**
 * Scrolls [target] while the right stick is held: the stick only reports when
 * it moves, so a held position keeps scrolling frame by frame.
 */
@Composable
fun RightStickScroll(target: ScrollableState?) {
    val input = LocalHandheldInput.current ?: return
    if (target == null) return
    val speed = with(LocalDensity.current) { STICK_SCROLL_SPEED.toPx() }
    LaunchedEffect(input, target, speed) {
        input.rightStickY.collectLatest { value ->
            if (value == 0f) return@collectLatest
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                target.scrollBy(value * speed * (now - last) / 1_000_000_000f)
                last = now
            }
        }
    }
}

/** Full deflection scrolls this far per second. */
private val STICK_SCROLL_SPEED = 900.dp

/** The button hints at the bottom of the landscape rail; only the keys that do something on [page]. */
object HandheldHints {
    fun forPage(page: OpenSavePage): List<Pair<String, String>> = buildList {
        add("A" to "确定")
        add("B" to "返回")
        if (page == OpenSavePage.LIBRARY) {
            add("X" to "菜单")
            add("Select" to "选择")
            add("Start" to "备份")
        }
        add("Y" to "命令")
        add("L1/R1" to "切页")
        add("L2/R2" to "翻页")
    }
}
