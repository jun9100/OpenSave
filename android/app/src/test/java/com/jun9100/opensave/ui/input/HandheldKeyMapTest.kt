package com.jun9100.opensave.ui.input

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldKeyMapTest {
    @Test
    fun mapsTheOdin2ShortcutButtons() {
        assertEquals(HandheldCommand.PREVIOUS_PAGE, HandheldKeyMap.commandFor(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(HandheldCommand.NEXT_PAGE, HandheldKeyMap.commandFor(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(HandheldCommand.PAGE_UP, HandheldKeyMap.commandFor(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(HandheldCommand.PAGE_DOWN, HandheldKeyMap.commandFor(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(HandheldCommand.GAME_MENU, HandheldKeyMap.commandFor(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(HandheldCommand.COMMAND_PANEL, HandheldKeyMap.commandFor(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(HandheldCommand.TOGGLE_SELECTION, HandheldKeyMap.commandFor(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(HandheldCommand.BACKUP, HandheldKeyMap.commandFor(KeyEvent.KEYCODE_BUTTON_START))
    }

    @Test
    fun aBDpadAndBackAreNeverIntercepted() {
        listOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
        ).forEach { key ->
            assertNull(HandheldKeyMap.commandFor(key))
            assertEquals(KeyDecision.Pass, HandheldKeyMap.decide(key, isDown = true, repeatCount = 0))
            assertEquals(KeyDecision.Pass, HandheldKeyMap.decide(key, isDown = false, repeatCount = 0))
        }
    }

    @Test
    fun onlyTheFirstDownDispatchesButUpAndRepeatsAreSwallowed() {
        val x = KeyEvent.KEYCODE_BUTTON_X
        assertEquals(KeyDecision.Dispatch(HandheldCommand.GAME_MENU), HandheldKeyMap.decide(x, isDown = true, repeatCount = 0))
        // Unconsumed, Generic.kcm would turn X into DPAD_CENTER and click the focused button.
        assertEquals(KeyDecision.Consume, HandheldKeyMap.decide(x, isDown = true, repeatCount = 3))
        assertEquals(KeyDecision.Consume, HandheldKeyMap.decide(x, isDown = false, repeatCount = 0))
    }

    @Test
    fun padKeysAndDpadMarkHandheldButTouchKeyboardLettersDoNot() {
        assertTrue(HandheldKeyMap.marksHandheld(KeyEvent.KEYCODE_BUTTON_A, fromGamepadSource = false))
        assertTrue(HandheldKeyMap.marksHandheld(KeyEvent.KEYCODE_BUTTON_MODE, fromGamepadSource = false))
        assertTrue(HandheldKeyMap.marksHandheld(KeyEvent.KEYCODE_DPAD_DOWN, fromGamepadSource = false))
        assertTrue(HandheldKeyMap.marksHandheld(KeyEvent.KEYCODE_BACK, fromGamepadSource = true))
        assertFalse(HandheldKeyMap.marksHandheld(KeyEvent.KEYCODE_X, fromGamepadSource = false))
        assertFalse(HandheldKeyMap.marksHandheld(KeyEvent.KEYCODE_VOLUME_UP, fromGamepadSource = false))
    }

    @Test
    fun inputConsumesMappedKeysEmitsCommandsAndTracksTheLastInput() = runBlocking {
        val input = HandheldInput()
        assertFalse(input.gamepadActive.value)
        val received = async(start = CoroutineStart.UNDISPATCHED) { input.commands.first() }

        assertFalse(input.onKey(KeyEvent.KEYCODE_BUTTON_A, isDown = true, repeatCount = 0, fromGamepadSource = true))
        assertTrue(input.gamepadActive.value)
        assertTrue(input.onKey(KeyEvent.KEYCODE_BUTTON_START, isDown = true, repeatCount = 0, fromGamepadSource = true))
        assertTrue(input.onKey(KeyEvent.KEYCODE_BUTTON_START, isDown = false, repeatCount = 0, fromGamepadSource = true))
        assertEquals(HandheldCommand.BACKUP, received.await())

        input.onTouch()
        assertFalse(input.gamepadActive.value)
    }

    @Test
    fun rightStickIgnoresTheDeadZoneAndMarksHandheld() {
        val input = HandheldInput()
        input.onRightStick(0.1f)
        assertEquals(0f, input.rightStickY.value)
        assertFalse(input.gamepadActive.value)
        input.onRightStick(-1f)
        assertEquals(-1f, input.rightStickY.value)
        assertTrue(input.gamepadActive.value)
        input.onTouch()
        assertEquals(0f, input.rightStickY.value)
    }
}
