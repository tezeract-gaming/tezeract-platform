package com.tezeract.input

import android.view.KeyEvent

/**
 * Logical input the SDK exposes to game developers — modeled on a standard
 * gamepad so games written against any gamepad library map naturally.
 *
 * Each value carries the Android [KeyEvent] keycode it would correspond to
 * on a physical controller. The synthetic-KeyEvent emission path (deferred
 * — needs INJECT_EVENTS / system signature) reads this directly.
 */
enum class TezeractInput(val keyCode: Int) {
    DPAD_UP(KeyEvent.KEYCODE_DPAD_UP),
    DPAD_DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    DPAD_LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    DPAD_RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),

    BUTTON_A(KeyEvent.KEYCODE_BUTTON_A),
    BUTTON_B(KeyEvent.KEYCODE_BUTTON_B),
    BUTTON_X(KeyEvent.KEYCODE_BUTTON_X),
    BUTTON_Y(KeyEvent.KEYCODE_BUTTON_Y),

    TRIGGER_L(KeyEvent.KEYCODE_BUTTON_L2),
    TRIGGER_R(KeyEvent.KEYCODE_BUTTON_R2),
}
