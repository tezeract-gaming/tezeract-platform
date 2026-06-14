package com.tezeract.motion.tracking

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.tasks.vision.core.RunningMode

/**
 * Wraps MediaPipe Pose Landmarker for real-time body pose detection.
 * Runs in LIVE_STREAM mode with GPU delegate for low-latency inference.
 */
class PoseTracker(private val context: Context) {
    companion object {
        private const val TAG = "PoseTracker"
        private const val MODEL_ASSET = "pose_landmarker_full.task"
        private const val MAX_POSES = 2
        private const val MIN_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val MIN_PRESENCE_CONFIDENCE = 0.5f
    }

    private var poseLandmarker: PoseLandmarker? = null
    private var latestResult: PoseLandmarkerResult? = null
    private var lastTimestampMs = 0L

    fun initialize() {
        Log.i(TAG, "Initializing PoseTracker with GPU delegate")

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.GPU)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(MAX_POSES)
            .setMinPoseDetectionConfidence(MIN_DETECTION_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .setMinPosePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
            .setResultListener { result, _ ->
                latestResult = result
            }
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe error: ${error.message}", error)
            }
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
        Log.i(TAG, "PoseTracker initialized")
    }

    /**
     * Runs pose detection on a camera frame.
     * Returns a list of pose landmark lists (one per detected body).
     * Each inner list has 33 NormalizedLandmark objects.
     */
    fun detect(bitmap: Bitmap): List<List<NormalizedLandmark>> {
        val landmarker = poseLandmarker ?: return emptyList()

        val mpImage = BitmapImageBuilder(bitmap).build()
        val timestampMs = System.currentTimeMillis()

        // LIVE_STREAM mode requires monotonically increasing timestamps
        if (timestampMs <= lastTimestampMs) return latestResult?.landmarks() ?: emptyList()
        lastTimestampMs = timestampMs

        landmarker.detectAsync(mpImage, timestampMs)

        // Return the latest available result (async, may be from previous frame)
        return latestResult?.landmarks() ?: emptyList()
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        Log.i(TAG, "PoseTracker closed")
    }
}
