package com.tezeract.motion.tracking

import com.tezeract.input.CalibrationProfile
import com.tezeract.motion.BodyPose
import com.tezeract.motion.Gesture
import com.tezeract.motion.Keypoint

/**
 * Rule-based gesture classifier. Detects the eight original Tezeract
 * gestures from body keypoints. Thresholds come from a [CalibrationProfile]
 * so per-user tuning works — pass `CalibrationProfile.default()` to keep
 * pre-calibration behavior.
 */
class GestureClassifier(private var profile: CalibrationProfile = CalibrationProfile.default()) {

    companion object {
        private const val MIN_CONFIDENCE = 0.5f
        // Time windows are part of detection state, not user-tunable.
        private const val JUMP_WINDOW_NS = 300_000_000L      // 300ms
        private const val WAVE_WINDOW_NS = 500_000_000L      // 500ms
        private const val CLAP_WINDOW_NS = 200_000_000L      // 200ms
    }

    private val bodyStates = mutableMapOf<Int, BodyGestureState>()

    data class BodyGestureState(
        var prevHipY: Float = -1f,
        var prevLeftWristX: Float = -1f,
        var prevRightWristX: Float = -1f,
        var prevLeftFootX: Float = -1f,
        var prevRightFootX: Float = -1f,
        var hipBaselineY: Float = -1f,
        var armsUpStartTime: Long = 0L,
        var armsElevatedAt: Long = 0L,    // last frame either wrist was at/above shoulder
        var clapApartTime: Long = 0L,     // last frame wrists were clearly separated
        var clapCloseSince: Long = 0L,    // first frame in a "close together at center" run
        var notCrossedTime: Long = 0L,    // last frame arms were clearly NOT in X-cross
        var triangleStartTime: Long = 0L, // first frame in a "wrists together above head" run
        var lastHomeTriangleTime: Long = 0L,
        var lastJumpTime: Long = 0L,
        var lastSquatTime: Long = 0L,
        var lastWaveLeftTime: Long = 0L,
        var lastWaveRightTime: Long = 0L,
        var lastArmsUpTime: Long = 0L,
        var lastArmsDownTime: Long = 0L,
        var lastClapTime: Long = 0L,
        var lastCrossArmsTime: Long = 0L,
        var lastStepLeftTime: Long = 0L,
        var lastStepRightTime: Long = 0L,
        var timestamp: Long = 0L
    )

    fun setProfile(newProfile: CalibrationProfile) {
        profile = newProfile
        // Calibration changed — drop per-body baselines so they re-capture.
        bodyStates.clear()
    }

    /**
     * Classify gestures for a body pose.
     */
    fun classify(body: BodyPose, timestamp: Long): List<Gesture> {
        val state = bodyStates.getOrPut(body.id) { BodyGestureState() }
        val gestures = mutableListOf<Gesture>()
        val kp = body.keypoints

        if (kp.size < 33) return gestures

        val leftHip = kp[Keypoint.LEFT_HIP]
        val rightHip = kp[Keypoint.RIGHT_HIP]
        val leftShoulder = kp[Keypoint.LEFT_SHOULDER]
        val rightShoulder = kp[Keypoint.RIGHT_SHOULDER]
        val leftWrist = kp[Keypoint.LEFT_WRIST]
        val rightWrist = kp[Keypoint.RIGHT_WRIST]
        val leftAnkle = kp[Keypoint.LEFT_ANKLE]
        val rightAnkle = kp[Keypoint.RIGHT_ANKLE]
        val nose = kp[Keypoint.NOSE]

        val hipMidY = (leftHip.y + rightHip.y) / 2f
        val shoulderMidY = (leftShoulder.y + rightShoulder.y) / 2f
        val bodyHeight = hipMidY - shoulderMidY

        if (state.hipBaselineY < 0) state.hipBaselineY = hipMidY

        val armsUpHoldNs = profile.armsUpHoldMs * 1_000_000L

        // --- JUMP ---
        if (state.prevHipY > 0 && areConfident(leftHip, rightHip)) {
            val hipRise = state.prevHipY - hipMidY
            val activeBodyHeight = bodyHeight.coerceAtLeast(0.1f)
            if (hipRise > profile.jumpThreshold * activeBodyHeight &&
                timestamp - state.lastJumpTime > JUMP_WINDOW_NS
            ) {
                val confidence = (hipRise / (profile.jumpThreshold * activeBodyHeight)).coerceIn(0.5f, 1f)
                gestures.add(Gesture(Gesture.JUMP, body.id, confidence, timestamp))
                state.lastJumpTime = timestamp
            }
        }

        // --- SQUAT ---
        if (areConfident(leftHip, rightHip, leftAnkle, rightAnkle)) {
            val hipDrop = hipMidY - state.hipBaselineY
            val feetStationary = state.prevLeftFootX < 0 ||
                (kotlin.math.abs(leftAnkle.x - state.prevLeftFootX) < 0.05f &&
                 kotlin.math.abs(rightAnkle.x - state.prevRightFootX) < 0.05f)

            if (hipDrop > profile.squatThreshold && feetStationary) {
                if (timestamp - state.lastSquatTime > JUMP_WINDOW_NS) {
                    val confidence = (hipDrop / profile.squatThreshold).coerceIn(0.5f, 1f)
                    gestures.add(Gesture(Gesture.SQUAT, body.id, confidence, timestamp))
                    state.lastSquatTime = timestamp
                }
            } else if (hipDrop < 0.05f) {
                state.hipBaselineY = hipMidY
            }
        }

        // --- WAVE_LEFT ---
        if (areConfident(leftWrist, leftShoulder) && state.prevLeftWristX > 0) {
            val wristMovement = kotlin.math.abs(leftWrist.x - state.prevLeftWristX)
            val armAboveShoulder = leftWrist.y < leftShoulder.y
            if (wristMovement > profile.waveThreshold && armAboveShoulder &&
                timestamp - state.lastWaveLeftTime > WAVE_WINDOW_NS
            ) {
                gestures.add(Gesture(Gesture.WAVE_LEFT, body.id, 0.8f, timestamp))
                state.lastWaveLeftTime = timestamp
            }
        }

        // --- WAVE_RIGHT ---
        if (areConfident(rightWrist, rightShoulder) && state.prevRightWristX > 0) {
            val wristMovement = kotlin.math.abs(rightWrist.x - state.prevRightWristX)
            val armAboveShoulder = rightWrist.y < rightShoulder.y
            if (wristMovement > profile.waveThreshold && armAboveShoulder &&
                timestamp - state.lastWaveRightTime > WAVE_WINDOW_NS
            ) {
                gestures.add(Gesture(Gesture.WAVE_RIGHT, body.id, 0.8f, timestamp))
                state.lastWaveRightTime = timestamp
            }
        }

        // --- ARMS_UP ---
        // Suppressed when the wrists are close together above the head — that
        // shape belongs to HOME_TRIANGLE and we don't want both gestures
        // firing for the same pose.
        if (areConfident(leftWrist, rightWrist, nose)) {
            val bothAboveHead = leftWrist.y < nose.y && rightWrist.y < nose.y
            val wristsCloseAbove = bothAboveHead &&
                kotlin.math.abs(leftWrist.x - rightWrist.x) < 0.10f
            if (bothAboveHead && !wristsCloseAbove) {
                if (state.armsUpStartTime == 0L) {
                    state.armsUpStartTime = timestamp
                } else if (timestamp - state.armsUpStartTime > armsUpHoldNs &&
                    timestamp - state.lastArmsUpTime > armsUpHoldNs * 2
                ) {
                    gestures.add(Gesture(Gesture.ARMS_UP, body.id, 0.9f, timestamp))
                    state.lastArmsUpTime = timestamp
                }
            } else {
                state.armsUpStartTime = 0L
            }
        }

        // --- HOME_TRIANGLE (universal "go home" escape) ---
        // Both wrists clearly above the head AND close together horizontally,
        // held for ≥ 300ms. The "close together" requirement (< 10% of frame)
        // is what distinguishes this from ARMS_UP. 1.2s cooldown stops a
        // hold from re-firing every 300ms.
        if (areConfident(leftWrist, rightWrist, nose)) {
            val bothAboveHead = leftWrist.y < nose.y - 0.02f && rightWrist.y < nose.y - 0.02f
            val wristsClose = kotlin.math.abs(leftWrist.x - rightWrist.x) < 0.10f
            val isTriangle = bothAboveHead && wristsClose
            if (isTriangle) {
                if (state.triangleStartTime == 0L) state.triangleStartTime = timestamp
                val held = timestamp - state.triangleStartTime > 300_000_000L      // 300ms
                val cooldownOk = timestamp - state.lastHomeTriangleTime > 1_200_000_000L
                if (held && cooldownOk) {
                    gestures.add(Gesture(Gesture.HOME_TRIANGLE, body.id, 0.95f, timestamp))
                    state.lastHomeTriangleTime = timestamp
                    state.triangleStartTime = 0L
                }
            } else {
                state.triangleStartTime = 0L
            }
        }

        // --- ARMS_DOWN (deliberate "press down" gesture) ---
        // Fires when the user transitions from arms-elevated (either wrist at
        // or above the shoulder line) to arms-at-sides (both wrists below the
        // hip line). One-shot, requires recent elevation so rest pose doesn't
        // fire it. 500ms cooldown.
        if (areConfident(leftWrist, rightWrist, leftHip, rightHip, leftShoulder, rightShoulder)) {
            val avgHipY = (leftHip.y + rightHip.y) / 2f
            val avgShoulderY = (leftShoulder.y + rightShoulder.y) / 2f
            val eitherAboveShoulder = leftWrist.y <= avgShoulderY || rightWrist.y <= avgShoulderY
            if (eitherAboveShoulder) {
                state.armsElevatedAt = timestamp
            }
            val bothBelowHip = leftWrist.y > avgHipY + 0.04f && rightWrist.y > avgHipY + 0.04f
            val recentlyElevated = state.armsElevatedAt > 0L &&
                (timestamp - state.armsElevatedAt) < 1_500_000_000L
            val cooldownOk = (timestamp - state.lastArmsDownTime) > 500_000_000L
            if (bothBelowHip && recentlyElevated && cooldownOk) {
                gestures.add(Gesture(Gesture.ARMS_DOWN, body.id, 0.9f, timestamp))
                state.lastArmsDownTime = timestamp
                // Require re-elevation before firing again.
                state.armsElevatedAt = 0L
            }
        }

        // --- CLAP ---
        // Transition-based: wrists must have been clearly APART within the
        // last 1.5s, then come close together NEAR THE BODY CENTERLINE.
        // The "each wrist on its own side" check is what prevents an
        // X-cross transition from false-firing CLAP — during an X, the
        // left wrist crosses past midline en route to the right shoulder,
        // briefly satisfying the close-together condition. By requiring
        // each wrist to stay on its own side (with a small overshoot
        // margin) we exclude that pattern.
        if (areConfident(leftWrist, rightWrist, leftShoulder, rightShoulder)) {
            val dx = leftWrist.x - rightWrist.x
            val dy = leftWrist.y - rightWrist.y
            val wristDistance = kotlin.math.sqrt(dx * dx + dy * dy)
            val apartThreshold = 0.30f
            val closeThreshold = profile.clapDistance * 1.6f  // default 0.16
            val midX = (leftShoulder.x + rightShoulder.x) / 2f

            if (wristDistance > apartThreshold) {
                state.clapApartTime = timestamp
            }
            val wasApartRecently = state.clapApartTime > 0L &&
                (timestamp - state.clapApartTime) < 1_500_000_000L
            // Must not be ARMS_UP territory (wrists above head) or arms-at-sides.
            val wristsInPlayArea = leftWrist.y > shoulderMidY - 0.05f &&
                leftWrist.y < hipMidY + 0.05f &&
                rightWrist.y > shoulderMidY - 0.05f &&
                rightWrist.y < hipMidY + 0.05f
            // Each wrist must be on its own side (≤5% overshoot allowed for a
            // natural clap collision). This rejects most of the X-cross
            // trajectory but the wrists still pass through the centerline
            // briefly during an X — which is why we ALSO require the close
            // pose to be HELD for a minimum duration. A real clap holds for
            // ~150–300ms; an X-cross transit through center is <80ms.
            val leftOnOwnSide = leftWrist.x <= midX + 0.05f
            val rightOnOwnSide = rightWrist.x >= midX - 0.05f
            val notCrossingOver = leftOnOwnSide && rightOnOwnSide
            val nearAndCentered = wristDistance < closeThreshold &&
                wristsInPlayArea && notCrossingOver
            if (nearAndCentered) {
                if (state.clapCloseSince == 0L) state.clapCloseSince = timestamp
            } else {
                state.clapCloseSince = 0L
            }
            val heldLongEnough = state.clapCloseSince > 0L &&
                (timestamp - state.clapCloseSince) > 120_000_000L   // 120ms
            val cooldownOk = (timestamp - state.lastClapTime) > 600_000_000L

            if (nearAndCentered && wasApartRecently && heldLongEnough && cooldownOk) {
                gestures.add(Gesture(Gesture.CLAP, body.id, 0.9f, timestamp))
                state.lastClapTime = timestamp
                state.clapApartTime = 0L
                state.clapCloseSince = 0L
            }
        }

        // --- CROSS_ARMS (forearms making an X across chest) ---
        // Each wrist must be PAST the body midline toward the opposite
        // side. The "past midline" condition alone is what distinguishes
        // an X from a clap — CLAP is now gated on "each wrist on its OWN
        // side", so the two never both match. Width-apart check removed
        // because a tight X (forearms more vertical) has wrists closer
        // together than 25% but is still clearly an X.
        if (areConfident(leftWrist, rightWrist, leftShoulder, rightShoulder, leftHip, rightHip)) {
            val avgHipY = (leftHip.y + rightHip.y) / 2f
            val midX = (leftShoulder.x + rightShoulder.x) / 2f
            val leftCrossed = leftWrist.x > midX + 0.06f
            val rightCrossed = rightWrist.x < midX - 0.06f
            val leftAtChest = leftWrist.y > shoulderMidY - 0.10f && leftWrist.y < avgHipY + 0.05f
            val rightAtChest = rightWrist.y > shoulderMidY - 0.10f && rightWrist.y < avgHipY + 0.05f
            val similarHeight = kotlin.math.abs(leftWrist.y - rightWrist.y) < 0.15f
            val isCrossed = leftCrossed && rightCrossed && leftAtChest && rightAtChest &&
                similarHeight
            if (!isCrossed) {
                state.notCrossedTime = timestamp
            }
            val recentlyNotCrossed = state.notCrossedTime > 0L &&
                (timestamp - state.notCrossedTime) < 1_500_000_000L
            val cooldownOk = (timestamp - state.lastCrossArmsTime) > 700_000_000L
            if (isCrossed && recentlyNotCrossed && cooldownOk) {
                gestures.add(Gesture(Gesture.CROSS_ARMS, body.id, 0.9f, timestamp))
                state.lastCrossArmsTime = timestamp
                state.notCrossedTime = 0L
            }
        }

        // --- STEP_LEFT ---
        if (areConfident(leftAnkle) && state.prevLeftFootX > 0) {
            val footMovement = state.prevLeftFootX - leftAnkle.x
            if (footMovement > profile.stepThreshold &&
                timestamp - state.lastStepLeftTime > WAVE_WINDOW_NS
            ) {
                gestures.add(Gesture(Gesture.STEP_LEFT, body.id, 0.75f, timestamp))
                state.lastStepLeftTime = timestamp
            }
        }

        // --- STEP_RIGHT ---
        if (areConfident(rightAnkle) && state.prevRightFootX > 0) {
            val footMovement = rightAnkle.x - state.prevRightFootX
            if (footMovement > profile.stepThreshold &&
                timestamp - state.lastStepRightTime > WAVE_WINDOW_NS
            ) {
                gestures.add(Gesture(Gesture.STEP_RIGHT, body.id, 0.75f, timestamp))
                state.lastStepRightTime = timestamp
            }
        }

        // Update state for next frame
        state.prevHipY = hipMidY
        state.prevLeftWristX = leftWrist.x
        state.prevRightWristX = rightWrist.x
        state.prevLeftFootX = leftAnkle.x
        state.prevRightFootX = rightAnkle.x
        state.timestamp = timestamp

        return gestures
    }

    fun removeBody(bodyId: Int) { bodyStates.remove(bodyId) }
    fun reset() { bodyStates.clear() }

    private fun areConfident(vararg keypoints: Keypoint): Boolean =
        keypoints.all { it.confidence >= MIN_CONFIDENCE }
}
