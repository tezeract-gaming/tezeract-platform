package com.tezeract.motion.tracking

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.core.RunningMode

/**
 * Wraps MediaPipe Face Landmarker for face mesh detection (468 keypoints).
 * Only initialized when tracking mode is FULL.
 */
class FaceMeshTracker(private val context: Context) {
    companion object {
        private const val TAG = "FaceMeshTracker"
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val MAX_FACES = 2
        private const val MIN_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var latestResult: FaceLandmarkerResult? = null
    private var lastTimestampMs = 0L

    fun initialize() {
        Log.i(TAG, "Initializing FaceMeshTracker with GPU delegate")

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.GPU)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(MAX_FACES)
            .setMinFaceDetectionConfidence(MIN_DETECTION_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .setResultListener { result, _ ->
                latestResult = result
            }
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe face error: ${error.message}", error)
            }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        Log.i(TAG, "FaceMeshTracker initialized")
    }

    /**
     * Returns the face landmarks for the first detected face (468 keypoints),
     * or null if no face is detected or tracker not initialized.
     */
    fun detect(bitmap: Bitmap): List<NormalizedLandmark>? {
        val landmarker = faceLandmarker ?: return null

        val mpImage = BitmapImageBuilder(bitmap).build()
        val timestampMs = System.currentTimeMillis()

        if (timestampMs <= lastTimestampMs) {
            return latestResult?.faceLandmarks()?.firstOrNull()
        }
        lastTimestampMs = timestampMs

        landmarker.detectAsync(mpImage, timestampMs)
        return latestResult?.faceLandmarks()?.firstOrNull()
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
        Log.i(TAG, "FaceMeshTracker closed")
    }
}
