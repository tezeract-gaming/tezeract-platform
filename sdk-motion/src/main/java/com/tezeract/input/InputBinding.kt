package com.tezeract.input

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Maps each [TezeractInput] to the [MotionTrigger] that produces it.
 *
 * Lives inside [CalibrationProfile] so a user / game can customize bindings
 * (e.g. a fitness game might rebind BUTTON_A from CLAP to JUMP). Default
 * binding follows the gamepad mapping table in the plan.
 */
@Parcelize
data class InputBinding(
    val mappings: Map<TezeractInput, MotionTrigger>
) : Parcelable {

    fun triggerFor(input: TezeractInput): MotionTrigger? = mappings[input]

    fun inputFor(trigger: MotionTrigger): TezeractInput? =
        mappings.entries.firstOrNull { it.value == trigger }?.key

    companion object {
        /**
         * Default mapping per plan §"Default gesture → input mapping (v1)":
         * - DPAD = JUMP / SQUAT / LEAN_L / LEAN_R
         * - BUTTON_A = CLAP, BUTTON_B = right hand raise,
         *   BUTTON_X = left hand raise, BUTTON_Y = ARMS_UP
         * - TRIGGER_L/R = hand grabs
         */
        fun default(): InputBinding = InputBinding(
            mappings = mapOf(
                // The launcher's primary navigation: raise an arm to scroll
                // that direction; arms-down to select; arms-up to back.
                // These pose-based gestures are easier for first-time users
                // to discover than lean/clap.
                TezeractInput.DPAD_LEFT to MotionTrigger.HAND_RAISE_LEFT,
                TezeractInput.DPAD_RIGHT to MotionTrigger.HAND_RAISE_RIGHT,
                TezeractInput.DPAD_UP to MotionTrigger.GESTURE_JUMP,
                TezeractInput.DPAD_DOWN to MotionTrigger.GESTURE_SQUAT,
                // BUTTON_A = select via clap. Back / home is handled
                // out-of-band by HOME_TRIANGLE (see TezeractMotion auto-
                // handler + MotionInputBridge gesture listener) — not bound
                // to a TezeractInput slot here, so we don't double-fire.
                // CROSS_ARMS used to live on BUTTON_Y but was removed because
                // the wrist trajectory through the centerline fought with
                // CLAP detection.
                TezeractInput.BUTTON_A to MotionTrigger.GESTURE_CLAP,
                // Secondary inputs — kept for in-game use.
                TezeractInput.BUTTON_B to MotionTrigger.GESTURE_ARMS_UP,
                TezeractInput.BUTTON_X to MotionTrigger.LEAN_LEFT,
                TezeractInput.BUTTON_Y to MotionTrigger.GESTURE_CROSS_ARMS,
                TezeractInput.TRIGGER_L to MotionTrigger.HAND_GRAB_LEFT,
                TezeractInput.TRIGGER_R to MotionTrigger.HAND_GRAB_RIGHT,
            )
        )
    }
}
