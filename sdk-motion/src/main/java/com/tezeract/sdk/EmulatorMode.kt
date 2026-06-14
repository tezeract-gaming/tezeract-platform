package com.tezeract.sdk

import android.content.Context
import android.util.Log
import com.tezeract.motion.BodyPose
import com.tezeract.motion.Gesture
import com.tezeract.motion.Keypoint
import com.tezeract.motion.MotionFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Simulates motion tracking data from a JSON file for development without hardware.
 *
 * JSON format:
 * {
 *   "fps": 30,
 *   "cameraWidth": 1280,
 *   "cameraHeight": 720,
 *   "frames": [
 *     {
 *       "bodies": [
 *         {
 *           "id": 0,
 *           "keypoints": [
 *             { "x": 0.5, "y": 0.3, "z": 0.0, "confidence": 0.95 },
 *             ... (33 keypoints)
 *           ]
 *         }
 *       ],
 *       "gestures": [
 *         { "name": "JUMP", "bodyId": 0, "confidence": 0.9 }
 *       ]
 *     }
 *   ]
 * }
 */
class EmulatorMode(
    private val context: Context,
    private val jsonAssetPath: String,
    private val motionListeners: CopyOnWriteArrayList<MotionListener>,
    private val gestureListeners: CopyOnWriteArrayList<GestureListener>
) {
    companion object {
        private const val TAG = "EmulatorMode"
    }

    private var frames = listOf<MotionFrame>()
    private var frameGestures = listOf<List<Gesture>>()
    private var fps = 30
    private var cameraWidth = 1280
    private var cameraHeight = 720
    private var playbackJob: Job? = null
    private var currentFrameIndex = 0
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        loadJson()
    }

    private fun loadJson() {
        try {
            val jsonString = context.assets.open(jsonAssetPath).bufferedReader().readText()
            val root = JSONObject(jsonString)

            fps = root.optInt("fps", 30)
            cameraWidth = root.optInt("cameraWidth", 1280)
            cameraHeight = root.optInt("cameraHeight", 720)

            val framesArray = root.getJSONArray("frames")
            val parsedFrames = mutableListOf<MotionFrame>()
            val parsedGestures = mutableListOf<List<Gesture>>()

            for (i in 0 until framesArray.length()) {
                val frameJson = framesArray.getJSONObject(i)
                val bodiesJson = frameJson.getJSONArray("bodies")
                val bodies = mutableListOf<BodyPose>()

                for (b in 0 until bodiesJson.length()) {
                    val bodyJson = bodiesJson.getJSONObject(b)
                    val bodyId = bodyJson.optInt("id", b)
                    val keypointsJson = bodyJson.getJSONArray("keypoints")
                    val keypoints = mutableListOf<Keypoint>()

                    for (k in 0 until keypointsJson.length()) {
                        val kpJson = keypointsJson.getJSONObject(k)
                        keypoints.add(
                            Keypoint(
                                index = k,
                                name = if (k < Keypoint.NAMES.size) Keypoint.NAMES[k] else "UNKNOWN",
                                x = kpJson.getDouble("x").toFloat(),
                                y = kpJson.getDouble("y").toFloat(),
                                z = kpJson.optDouble("z", 0.0).toFloat(),
                                confidence = kpJson.optDouble("confidence", 0.9).toFloat()
                            )
                        )
                    }

                    bodies.add(BodyPose(id = bodyId, keypoints = keypoints, hands = null, face = null))
                }

                parsedFrames.add(
                    MotionFrame(
                        timestamp = System.nanoTime(),
                        frameNumber = i.toLong(),
                        latencyMs = 0f,
                        bodyCount = bodies.size,
                        bodies = bodies,
                        cameraWidth = cameraWidth,
                        cameraHeight = cameraHeight
                    )
                )

                // Parse gestures for this frame
                val gesturesJson = frameJson.optJSONArray("gestures")
                val gestures = mutableListOf<Gesture>()
                if (gesturesJson != null) {
                    for (g in 0 until gesturesJson.length()) {
                        val gJson = gesturesJson.getJSONObject(g)
                        gestures.add(
                            Gesture(
                                name = gJson.getString("name"),
                                bodyId = gJson.optInt("bodyId", 0),
                                confidence = gJson.optDouble("confidence", 0.9).toFloat(),
                                timestamp = System.nanoTime()
                            )
                        )
                    }
                }
                parsedGestures.add(gestures)
            }

            frames = parsedFrames
            frameGestures = parsedGestures
            Log.i(TAG, "Loaded ${frames.size} frames at ${fps}fps from $jsonAssetPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load emulator JSON: $jsonAssetPath", e)
            frames = listOf(generateDefaultFrame())
            frameGestures = listOf(emptyList())
        }
    }

    fun start() {
        if (frames.isEmpty()) {
            Log.w(TAG, "No frames to play")
            return
        }

        Log.i(TAG, "Starting emulator playback: ${frames.size} frames at ${fps}fps")
        currentFrameIndex = 0
        val frameDelayMs = 1000L / fps

        playbackJob = scope.launch {
            while (true) {
                val frame = frames[currentFrameIndex].copy(
                    timestamp = System.nanoTime(),
                    frameNumber = currentFrameIndex.toLong()
                )

                // Deliver frame to listeners
                for (listener in motionListeners) {
                    listener.onMotionFrame(frame)
                }

                // Deliver gestures
                val gestures = frameGestures[currentFrameIndex]
                for (gesture in gestures) {
                    val g = gesture.copy(timestamp = System.nanoTime())
                    for (listener in gestureListeners) {
                        listener.onGesture(g)
                    }
                }

                currentFrameIndex = (currentFrameIndex + 1) % frames.size
                delay(frameDelayMs)
            }
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        Log.i(TAG, "Emulator playback stopped")
    }

    fun getLatestFrame(): MotionFrame? {
        if (frames.isEmpty()) return null
        return frames[currentFrameIndex.coerceIn(0, frames.size - 1)]
    }

    private fun generateDefaultFrame(): MotionFrame {
        // Generate a standing pose as default
        val keypoints = Keypoint.NAMES.mapIndexed { index, name ->
            Keypoint(
                index = index,
                name = name,
                x = 0.5f,
                y = 0.3f + (index * 0.02f),
                z = 0f,
                confidence = 0.9f
            )
        }
        return MotionFrame(
            timestamp = System.nanoTime(),
            frameNumber = 0,
            latencyMs = 0f,
            bodyCount = 1,
            bodies = listOf(BodyPose(id = 0, keypoints = keypoints, hands = null, face = null)),
            cameraWidth = cameraWidth,
            cameraHeight = cameraHeight
        )
    }
}
