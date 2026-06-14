package com.tezeract.motion.tracking

import com.tezeract.input.CalibrationProfile
import com.tezeract.input.InputAction
import com.tezeract.input.InputEvent
import com.tezeract.input.MotionTrigger
import com.tezeract.input.TezeractInput
import com.tezeract.motion.BodyPose
import com.tezeract.motion.Gesture
import com.tezeract.motion.Keypoint

/**
 * Turns body keypoints + raw gestures into [InputEvent]s with proper
 * PRESS / HOLD / RELEASE lifecycle. Two detector families:
 *
 * - **One-shot** triggers come from [GestureClassifier]'s gesture stream
 *   (JUMP, CLAP, etc.). Each detection emits PRESS this frame and an
 *   automatic RELEASE on the next frame — gestures aren't really "held",
 *   so we synthesize the press/release pair.
 *
 * - **Continuous** triggers (LEAN_*, HAND_RAISE_*, HAND_GRAB_*) are
 *   evaluated per frame against the [CalibrationProfile] thresholds. PRESS
 *   on entering the active region, HOLD while in it, RELEASE on exit.
 *
 * Hand-grab triggers depend on `HandPose.isClosed` which is added in
 * TASK-025. Until then, those triggers never fire.
 */
class InputClassifier(private var profile: CalibrationProfile = CalibrationProfile.default()) {

    companion object {
        private const val MIN_CONFIDENCE = 0.5f
    }

    private data class ActiveState(val activatedAt: Long, val isOneShot: Boolean)

    private val activeInputs = mutableMapOf<Pair<Int, TezeractInput>, ActiveState>()

    fun setProfile(newProfile: CalibrationProfile) {
        profile = newProfile
        activeInputs.clear()
    }

    fun reset() = activeInputs.clear()

    /**
     * Classify one frame's worth of data. Caller passes the body and the
     * gestures already produced by [GestureClassifier] for the same frame.
     */
    fun classify(body: BodyPose, gestures: List<Gesture>, timestamp: Long): List<InputEvent> {
        val events = mutableListOf<InputEvent>()
        val binding = profile.binding

        // 1. Auto-release any one-shot inputs from the previous frame.
        val toRelease = activeInputs.entries
            .filter { it.key.first == body.id && it.value.isOneShot }
            .map { it.key }
        for (key in toRelease) {
            events.add(InputEvent(key.second, InputAction.RELEASE, 0f, body.id, timestamp))
            activeInputs.remove(key)
        }

        // 2. Map this frame's gestures to one-shot PRESS events.
        for (gesture in gestures) {
            val trigger = gestureNameToTrigger(gesture.name) ?: continue
            val input = binding.inputFor(trigger) ?: continue
            val key = body.id to input
            events.add(InputEvent(input, InputAction.PRESS, gesture.confidence, body.id, timestamp))
            activeInputs[key] = ActiveState(timestamp, isOneShot = true)
        }

        // 3. Continuous detectors. Each is a (active?, confidence) check.
        // ARMS_UP suppresses the per-hand raise detectors so a "both hands
        // above head" pose fires one input (BUTTON_Y) instead of three.
        val kp = body.keypoints
        val armsUpActive = kp.size >= 33 && armsUpHold(kp)
        if (kp.size >= 33) {
            val raiseL = if (armsUpActive) false to 0f else handRaiseLeft(kp)
            val raiseR = if (armsUpActive) false to 0f else handRaiseRight(kp)
            evaluateContinuous(body.id, timestamp, events,
                MotionTrigger.LEAN_LEFT to leanLeft(kp),
                MotionTrigger.LEAN_RIGHT to leanRight(kp),
                MotionTrigger.HAND_RAISE_LEFT to raiseL,
                MotionTrigger.HAND_RAISE_RIGHT to raiseR,
            )

            // Hand-grab triggers — wired up in TASK-025 once HandPose carries grab state.
            val hands = body.hands
            if (hands != null) {
                val left = hands.firstOrNull { it.side == "LEFT" }
                val right = hands.firstOrNull { it.side == "RIGHT" }
                evaluateContinuous(body.id, timestamp, events,
                    MotionTrigger.HAND_GRAB_LEFT to handGrab(left),
                    MotionTrigger.HAND_GRAB_RIGHT to handGrab(right),
                )
            }
        }

        return events
    }

    // --- Continuous detector helpers ---
    // Each returns (isActive, confidence). isActive == false means the input
    // is not currently triggered; confidence is meaningful only when active.

    private fun leanLeft(kp: List<Keypoint>): Pair<Boolean, Float> {
        val ls = kp[Keypoint.LEFT_SHOULDER]; val rs = kp[Keypoint.RIGHT_SHOULDER]
        if (ls.confidence < MIN_CONFIDENCE || rs.confidence < MIN_CONFIDENCE) return false to 0f
        val mid = (ls.x + rs.x) / 2f
        val delta = profile.shoulderMidXBaseline - mid // positive = leaned to user's left (mirrored)
        val active = delta > profile.leanThreshold
        val conf = if (active) (delta / (profile.leanThreshold * 2f)).coerceIn(0.3f, 1f) else 0f
        return active to conf
    }

    private fun leanRight(kp: List<Keypoint>): Pair<Boolean, Float> {
        val ls = kp[Keypoint.LEFT_SHOULDER]; val rs = kp[Keypoint.RIGHT_SHOULDER]
        if (ls.confidence < MIN_CONFIDENCE || rs.confidence < MIN_CONFIDENCE) return false to 0f
        val mid = (ls.x + rs.x) / 2f
        val delta = mid - profile.shoulderMidXBaseline
        val active = delta > profile.leanThreshold
        val conf = if (active) (delta / (profile.leanThreshold * 2f)).coerceIn(0.3f, 1f) else 0f
        return active to conf
    }

    /** Both wrists above nose — same condition GestureClassifier uses for ARMS_UP. */
    private fun armsUpHold(kp: List<Keypoint>): Boolean {
        val lw = kp[Keypoint.LEFT_WRIST]; val rw = kp[Keypoint.RIGHT_WRIST]
        val nose = kp[Keypoint.NOSE]
        if (lw.confidence < MIN_CONFIDENCE || rw.confidence < MIN_CONFIDENCE
            || nose.confidence < MIN_CONFIDENCE) return false
        return lw.y < nose.y && rw.y < nose.y
    }

    private fun handRaiseLeft(kp: List<Keypoint>): Pair<Boolean, Float> {
        val w = kp[Keypoint.LEFT_WRIST]; val s = kp[Keypoint.LEFT_SHOULDER]
        if (w.confidence < MIN_CONFIDENCE || s.confidence < MIN_CONFIDENCE) return false to 0f
        val above = s.y - w.y // positive = wrist higher on screen than shoulder (lower Y)
        val active = above > profile.handRaiseThreshold
        val conf = if (active) (above / (profile.handRaiseThreshold * 2f)).coerceIn(0.3f, 1f) else 0f
        return active to conf
    }

    private fun handRaiseRight(kp: List<Keypoint>): Pair<Boolean, Float> {
        val w = kp[Keypoint.RIGHT_WRIST]; val s = kp[Keypoint.RIGHT_SHOULDER]
        if (w.confidence < MIN_CONFIDENCE || s.confidence < MIN_CONFIDENCE) return false to 0f
        val above = s.y - w.y
        val active = above > profile.handRaiseThreshold
        val conf = if (active) (above / (profile.handRaiseThreshold * 2f)).coerceIn(0.3f, 1f) else 0f
        return active to conf
    }

    private fun handGrab(hand: com.tezeract.motion.HandPose?): Pair<Boolean, Float> {
        hand ?: return false to 0f
        return hand.isClosed to hand.grabConfidence
    }

    private fun evaluateContinuous(
        bodyId: Int, timestamp: Long, events: MutableList<InputEvent>,
        vararg checks: Pair<MotionTrigger, Pair<Boolean, Float>>
    ) {
        for ((trigger, result) in checks) {
            val input = profile.binding.inputFor(trigger) ?: continue
            val key = bodyId to input
            val (isActive, confidence) = result
            val state = activeInputs[key]
            val wasActive = state != null && !state.isOneShot

            when {
                isActive && !wasActive -> {
                    events.add(InputEvent(input, InputAction.PRESS, confidence, bodyId, timestamp))
                    activeInputs[key] = ActiveState(timestamp, isOneShot = false)
                }
                isActive && wasActive -> {
                    events.add(InputEvent(input, InputAction.HOLD, confidence, bodyId, timestamp))
                }
                !isActive && wasActive -> {
                    events.add(InputEvent(input, InputAction.RELEASE, 0f, bodyId, timestamp))
                    activeInputs.remove(key)
                }
            }
        }
    }

    private fun gestureNameToTrigger(name: String): MotionTrigger? = when (name) {
        Gesture.JUMP -> MotionTrigger.GESTURE_JUMP
        Gesture.SQUAT -> MotionTrigger.GESTURE_SQUAT
        Gesture.WAVE_LEFT -> MotionTrigger.GESTURE_WAVE_LEFT
        Gesture.WAVE_RIGHT -> MotionTrigger.GESTURE_WAVE_RIGHT
        Gesture.ARMS_UP -> MotionTrigger.GESTURE_ARMS_UP
        Gesture.ARMS_DOWN -> MotionTrigger.GESTURE_ARMS_DOWN
        Gesture.CROSS_ARMS -> MotionTrigger.GESTURE_CROSS_ARMS
        Gesture.HOME_TRIANGLE -> MotionTrigger.GESTURE_HOME_TRIANGLE
        Gesture.CLAP -> MotionTrigger.GESTURE_CLAP
        Gesture.STEP_LEFT -> MotionTrigger.GESTURE_STEP_LEFT
        Gesture.STEP_RIGHT -> MotionTrigger.GESTURE_STEP_RIGHT
        else -> null
    }
}
