package com.tezeract.shadowbox.motion

import com.tezeract.motion.Keypoint
import com.tezeract.motion.MotionFrame

/** A move emitted by [PunchDetector]. */
enum class PunchKind { LEFT_JAB, RIGHT_JAB, LEFT_HOOK, RIGHT_HOOK, LEFT_UPPERCUT, RIGHT_UPPERCUT, BLOCK }

data class Punch(val kind: PunchKind, val confidence: Float, val timestampMs: Long)

/**
 * Boxing-specific motion classifier. Subscribes to MotionFrame, runs per-arm
 * wrist trajectory analysis, emits a [Punch] when:
 * - **JAB**: rapid wrist motion roughly forward/inward (toward body centerline)
 *           at chest height. Most common neutral punch.
 * - **HOOK**: rapid wrist motion ACROSS the body horizontally (high X velocity).
 * - **UPPERCUT**: rapid wrist motion UP from a low position (high Y velocity,
 *                 wrist Y crosses chest level going up).
 * - **BLOCK**: both wrists held in front of the face (between shoulders, near
 *              nose Y) — sustained pose, not a strike.
 *
 * Tuned for visual recognizability over precision: the user wants to feel
 * a punch register, not pass a martial-arts exam. Cooldown per arm prevents
 * double-fire. BLOCK is reported each frame the pose holds.
 */
class PunchDetector {

    companion object {
        private const val SPEED_THRESHOLD = 0.040f     // normalized units / frame for a "punch"
        private const val UPPERCUT_Y_DOMINANCE = 1.4f  // |dy| must beat |dx| by this factor for UPPERCUT
        private const val HOOK_X_DOMINANCE = 1.4f      // and vice versa for HOOK
        private const val PUNCH_COOLDOWN_MS = 350L
        private const val BLOCK_X_TOLERANCE = 0.12f    // wrists within this X of body midline
        private const val BLOCK_FACE_TOLERANCE = 0.10f // wrists within this Y of nose
    }

    private data class ArmHistory(
        var prevX: Float = -1f,
        var prevY: Float = -1f,
        var lastPunchAt: Long = 0L,
    )

    private val left = ArmHistory()
    private val right = ArmHistory()

    /**
     * Process the next frame and return any punches detected this tick.
     * Returns at most a handful per call.
     */
    fun classify(frame: MotionFrame, nowMs: Long): List<Punch> {
        if (frame.bodies.isEmpty()) return emptyList()
        val body = frame.bodies[0]
        val kp = body.keypoints
        if (kp.size < 33) return emptyList()

        val out = mutableListOf<Punch>()

        val leftWrist = kp[Keypoint.LEFT_WRIST]
        val rightWrist = kp[Keypoint.RIGHT_WRIST]
        val leftShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rightShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val nose = kp[Keypoint.NOSE]

        // --- BLOCK (both wrists near face, between shoulders) ---
        if (leftWrist.confidence > 0.5f && rightWrist.confidence > 0.5f && nose.confidence > 0.5f) {
            val midX = (leftShoulder.x + rightShoulder.x) / 2f
            val leftNearMid = kotlin.math.abs(leftWrist.x - midX) < BLOCK_X_TOLERANCE * 1.5f
            val rightNearMid = kotlin.math.abs(rightWrist.x - midX) < BLOCK_X_TOLERANCE * 1.5f
            val leftNearFace = kotlin.math.abs(leftWrist.y - nose.y) < BLOCK_FACE_TOLERANCE
            val rightNearFace = kotlin.math.abs(rightWrist.y - nose.y) < BLOCK_FACE_TOLERANCE
            if (leftNearMid && rightNearMid && leftNearFace && rightNearFace) {
                out.add(Punch(PunchKind.BLOCK, 1f, nowMs))
            }
        }

        // --- Per-arm punch classification ---
        classifyArm(leftWrist, leftShoulder, isLeft = true, history = left, nowMs = nowMs, out = out)
        classifyArm(rightWrist, rightShoulder, isLeft = false, history = right, nowMs = nowMs, out = out)

        return out
    }

    private fun classifyArm(
        wrist: Keypoint,
        shoulder: Keypoint,
        isLeft: Boolean,
        history: ArmHistory,
        nowMs: Long,
        out: MutableList<Punch>,
    ) {
        if (wrist.confidence < 0.5f) {
            history.prevX = -1f; history.prevY = -1f
            return
        }
        if (history.prevX < 0f) {
            history.prevX = wrist.x; history.prevY = wrist.y
            return
        }
        val dx = wrist.x - history.prevX
        val dy = wrist.y - history.prevY
        val speed = kotlin.math.sqrt(dx * dx + dy * dy)
        history.prevX = wrist.x; history.prevY = wrist.y

        if (speed < SPEED_THRESHOLD) return
        if (nowMs - history.lastPunchAt < PUNCH_COOLDOWN_MS) return

        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)
        val confidence = (speed / SPEED_THRESHOLD).coerceIn(1f, 2.5f) / 2.5f

        val kind = when {
            // UPPERCUT: dominant upward motion (dy negative since Y increases downward)
            absDy > absDx * UPPERCUT_Y_DOMINANCE && dy < 0f ->
                if (isLeft) PunchKind.LEFT_UPPERCUT else PunchKind.RIGHT_UPPERCUT
            // HOOK: dominant horizontal motion
            absDx > absDy * HOOK_X_DOMINANCE ->
                if (isLeft) PunchKind.LEFT_HOOK else PunchKind.RIGHT_HOOK
            // JAB: anything else fast (mostly forward/diagonal)
            else ->
                if (isLeft) PunchKind.LEFT_JAB else PunchKind.RIGHT_JAB
        }

        out.add(Punch(kind, confidence, nowMs))
        history.lastPunchAt = nowMs
    }

    fun reset() {
        left.prevX = -1f; left.prevY = -1f; left.lastPunchAt = 0L
        right.prevX = -1f; right.prevY = -1f; right.lastPunchAt = 0L
    }
}
