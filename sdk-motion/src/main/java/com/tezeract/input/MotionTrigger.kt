package com.tezeract.input

/**
 * Source motion that the service-side `InputClassifier` knows how to detect.
 * Bound to a [TezeractInput] via an [InputBinding] inside a
 * [CalibrationProfile].
 *
 * The first eight entries match the existing rule-based gestures in
 * `service-motion`'s `GestureClassifier`. Lean / hand-raise / hand-grab are
 * new and added to round out the gamepad mapping (see plan §"Default
 * gesture → input mapping").
 */
enum class MotionTrigger {
    GESTURE_JUMP,
    GESTURE_SQUAT,
    GESTURE_WAVE_LEFT,
    GESTURE_WAVE_RIGHT,
    GESTURE_ARMS_UP,
    GESTURE_ARMS_DOWN,
    GESTURE_CROSS_ARMS,
    GESTURE_HOME_TRIANGLE,
    GESTURE_CLAP,
    GESTURE_STEP_LEFT,
    GESTURE_STEP_RIGHT,

    LEAN_LEFT,
    LEAN_RIGHT,

    HAND_RAISE_LEFT,
    HAND_RAISE_RIGHT,

    HAND_GRAB_LEFT,
    HAND_GRAB_RIGHT,
}
