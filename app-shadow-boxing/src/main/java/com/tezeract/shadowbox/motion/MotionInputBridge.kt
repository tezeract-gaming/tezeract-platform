package com.tezeract.shadowbox.motion

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import com.tezeract.input.InputAction
import com.tezeract.input.InputEvent
import com.tezeract.input.InputListener
import com.tezeract.input.TezeractInput
import com.tezeract.sdk.TezeractMotion

/**
 * Mirror of `app-launcher`'s MotionInputBridge — translates Tezeract
 * InputEvents into Android KeyEvents dispatched to the host activity so the
 * standard TV focus engine drives the mode picker via raise-arm / clap.
 *
 * Lives only on the picker screen. Once the user enters a gameplay mode
 * (Reaction / Training / Fight), each screen registers its own raw motion
 * listener via PunchDetector and we don't want lean → DPAD events.
 */
class MotionInputBridge(private val activity: Activity) {

    private val listener = InputListener { event -> onEvent(event) }
    private var attached = false

    fun attach() {
        if (attached) return
        TezeractMotion.addInputListener(listener)
        attached = true
    }

    fun detach() {
        if (!attached) return
        TezeractMotion.removeInputListener(listener)
        attached = false
    }

    private fun onEvent(event: InputEvent) {
        if (event.action != InputAction.PRESS) return
        val keyCode = when (event.input) {
            TezeractInput.DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            TezeractInput.DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            TezeractInput.DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
            TezeractInput.DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            TezeractInput.BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER  // CLAP = select
            else -> return
        }
        activity.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }
}
