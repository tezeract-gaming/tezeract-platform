package com.tezeract.motion.tracking

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.core.RunningMode

/**
 * Wraps MediaPipe Hand Landmarker for hand pose detection (21 keypoints per hand).
 * Only initialized when tracking mode includes hands.
 */
class HandTracker(private val context: Context) {
    companion object {
        private const val TAG = "HandTracker"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val MAX_HANDS = 4 // 2 per person, max 2 people
        private const val MIN_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
    }

    private var handLandmarker: HandLandmarker? = null
    private var latestResult: HandLandmarkerResult? = null
    private var lastTimestampMs = 0L

    fun initialize() {
        Log.i(TAG, "Initializing HandTracker with GPU delegate")

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.GPU)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(MAX_HANDS)
            .setMinHandDetectionConfidence(MIN_DETECTION_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .setResultListener { result, _ ->
                latestResult = result
            }
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe hand error: ${error.message}", error)
            }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(context, options)
        Log.i(TAG, "HandTracker initialized")
    }

    /**
     * Returns list of (handedness, landmarks) pairs.
     * Handedness is "LEFT" or "RIGHT".
     */
    fun detect(bitmap: Bitmap): List<Pair<String, List<NormalizedLandmark>>>? {
        val landmarker = handLandmarker ?: return null

        val mpImage = BitmapImageBuilder(bitmap).build()
        val timestampMs = System.currentTimeMillis()

        if (timestampMs <= lastTimestampMs) {
            return formatResult(latestResult)
        }
        lastTimestampMs = timestampMs

        landmarker.detectAsync(mpImage, timestampMs)
        return formatResult(latestResult)
    }

    private fun formatResult(result: HandLandmarkerResult?): List<Pair<String, List<NormalizedLandmark>>>? {
        result ?: return null
        val landmarks = result.landmarks()
        val handedness = result.handednesses()
        if (landmarks.isEmpty()) return null

        return landmarks.zip(handedness).map { (lm, hand) ->
            val side = hand.firstOrNull()?.categoryName()?.uppercase() ?: "UNKNOWN"
            Pair(side, lm)
        }
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
        Log.i(TAG, "HandTracker closed")
    }
}
