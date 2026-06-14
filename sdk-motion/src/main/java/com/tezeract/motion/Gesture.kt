package com.tezeract.motion

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Gesture(
    val name: String,       // "JUMP", "SQUAT", "WAVE_LEFT", etc.
    val bodyId: Int,        // Which body triggered the gesture
    val confidence: Float,  // 0.0-1.0
    val timestamp: Long     // System.nanoTime()
) : Parcelable {

    companion object {
        const val JUMP = "JUMP"
        const val SQUAT = "SQUAT"
        const val WAVE_LEFT = "WAVE_LEFT"
        const val WAVE_RIGHT = "WAVE_RIGHT"
        const val ARMS_UP = "ARMS_UP"
        const val ARMS_DOWN = "ARMS_DOWN"
        const val CROSS_ARMS = "CROSS_ARMS"
        const val HOME_TRIANGLE = "HOME_TRIANGLE"
        const val CLAP = "CLAP"
        const val STEP_LEFT = "STEP_LEFT"
        const val STEP_RIGHT = "STEP_RIGHT"
    }
}
