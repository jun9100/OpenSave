package com.jun9100.opensave

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jun9100.opensave.ui.OpenSaveScreen
import com.jun9100.opensave.ui.input.HandheldInput
import com.jun9100.opensave.ui.input.ProvideHandheldInput

class MainActivity : ComponentActivity() {
    private val handheld = HandheldInput()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status and gesture bars; Compose pads with the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenSaveTheme {
                ProvideHandheldInput(handheld) {
                    OpenSaveScreen()
                }
            }
        }
    }

    /**
     * Global pad keys (plan §4.2 item 1). Handled here rather than in Compose so
     * they work when no node has focus. Only X/Y/Start/Select/L1/R1/L2/R2 are
     * taken, down and up; A, B, the D-pad and BACK go on so clickables see the
     * DPAD_CENTER fallback of A and the BackHandler chain sees B.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromGamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_DPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        val consumed = handheld.onKey(
            keyCode = event.keyCode,
            isDown = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            fromGamepadSource = fromGamepad,
        )
        return consumed || super.dispatchKeyEvent(event)
    }

    /**
     * Right stick (AXIS_Z/AXIS_RZ on the Odin2) scrolls the current pane. The
     * event is never consumed here: the left stick and HAT reach the system's
     * synthetic D-pad only when the views leave the motion event unhandled.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            handheld.onRightStick(event.getAxisValue(MotionEvent.AXIS_RZ))
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) handheld.onTouch()
        return super.dispatchTouchEvent(event)
    }
}
