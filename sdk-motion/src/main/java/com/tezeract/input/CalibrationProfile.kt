package com.tezeract.input

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Per-user tuning for the input classifier.
 *
 * Two layers of data:
 *
 * 1. **Baselines** captured during the calibration wizard's "neutral stance"
 *    step — used as reference points for detectors (e.g. lean is "shoulder
 *    midpoint X moved Δ from [shoulderMidXBaseline]"). All in normalized
 *    image coordinates 0..1.
 *
 * 2. **Thresholds** — how far the user has to move before the corresponding
 *    input fires. Captured during the per-action wizard steps as
 *    `~0.6 × measured_max_amplitude`, giving a comfortable activation zone.
 *    [default] returns values that match the static constants in the
 *    pre-calibration `GestureClassifier`, so legacy behavior is preserved
 *    when no per-user file exists.
 *
 * Plus an [InputBinding] that maps logical inputs to source motions.
 */
@Parcelize
data class CalibrationProfile(
    val userId: String,

    // --- Baselines (normalized 0..1) ---
    val shoulderMidXBaseline: Float,
    val hipMidYBaseline: Float,
    val bodyHeight: Float,
    val armReach: Float,

    // --- Thresholds ---
    val jumpThreshold: Float,
    val squatThreshold: Float,
    val waveThreshold: Float,
    val clapDistance: Float,
    val stepThreshold: Float,
    val leanThreshold: Float,
    val handRaiseThreshold: Float,
    val grabThreshold: Float,

    // --- Hold / debounce times (milliseconds) ---
    val armsUpHoldMs: Int,
    val pressDebounceMs: Int,

    val binding: InputBinding,
) : Parcelable {

    companion object {
        /**
         * Returns a profile that reproduces today's pre-calibration
         * `GestureClassifier` constants. Used until the user runs the
         * calibration wizard.
         */
        fun default(userId: String = "default"): CalibrationProfile = CalibrationProfile(
            userId = userId,
            shoulderMidXBaseline = 0.5f,
            hipMidYBaseline = 0.6f,
            bodyHeight = 0.4f,
            armReach = 0.25f,

            jumpThreshold = 0.15f,        // matches GestureClassifier.JUMP_THRESHOLD
            squatThreshold = 0.20f,       // matches GestureClassifier.SQUAT_THRESHOLD
            waveThreshold = 0.30f,        // matches GestureClassifier.WAVE_THRESHOLD
            clapDistance = 0.10f,         // matches GestureClassifier.CLAP_DISTANCE
            stepThreshold = 0.20f,        // matches GestureClassifier.STEP_THRESHOLD
            leanThreshold = 0.08f,        // new — ~8% horizontal shift of shoulder midpoint
            handRaiseThreshold = 0.05f,   // new — wrist 5% above shoulder
            grabThreshold = 0.35f,        // new — fingertip-to-palm normalized distance

            armsUpHoldMs = 500,           // matches GestureClassifier.ARMS_UP_HOLD_NS
            pressDebounceMs = 200,

            binding = InputBinding.default(),
        )
    }
}
