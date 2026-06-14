package com.tezeract.motion.camera

import android.graphics.Bitmap
import android.util.Log
import com.tezeract.motion.BodyPose
import com.tezeract.motion.FaceMesh
import com.tezeract.motion.HandPose
import com.tezeract.motion.Keypoint
import com.tezeract.motion.MotionFrame
import com.tezeract.motion.TrackingMode
import com.tezeract.motion.tracking.FaceMeshTracker
import com.tezeract.motion.tracking.HandTracker
import com.tezeract.motion.tracking.PoseTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates pose, hand, and face tracking pipelines.
 * Receives camera frames and produces MotionFrame objects.
 */
class FrameProcessor(
    private val poseTracker: PoseTracker,
    private val handTracker: HandTracker,
    private val faceMeshTracker: FaceMeshTracker
) {
    companion object {
        private const val TAG = "FrameProcessor"
    }

    private var frameNumber = 0L
    private var trackingMode = TrackingMode.BODY_ONLY
    // Throttle the diagnostic hand log so it fires roughly once per second.
    private var lastHandLogNs = 0L

    private val _latestFrame = MutableStateFlow<MotionFrame?>(null)
    val latestFrame: StateFlow<MotionFrame?> = _latestFrame

    // Rolling average latency
    private val latencyHistory = ArrayDeque<Float>(30)
    private val _averageLatency = MutableStateFlow(0f)
    val averageLatency: StateFlow<Float> = _averageLatency

    fun setTrackingMode(mode: TrackingMode) {
        trackingMode = mode
        Log.i(TAG, "Tracking mode set to: $mode")
    }

    fun processFrame(bitmap: Bitmap, cameraWidth: Int, cameraHeight: Int) {
        val startTime = System.nanoTime()

        // Run pose tracking (always on)
        val poseResults = poseTracker.detect(bitmap)

        val bodies = poseResults.mapIndexed { index, landmarks ->
            val keypoints = landmarks.mapIndexed { kpIndex, lm ->
                Keypoint(
                    index = kpIndex,
                    name = if (kpIndex < Keypoint.NAMES.size) Keypoint.NAMES[kpIndex] else "UNKNOWN",
                    // Mirror X for selfie-view semantics: a game that wires LEFT_WRIST.x
                    // to a left-side paddle should respond to the user's left hand. The
                    // raw camera image is not mirrored, so without this flip the user's
                    // left hand reads as a high X (right side of screen).
                    x = 1f - lm.x(),
                    y = lm.y(),
                    z = lm.z(),
                    confidence = lm.visibility().orElse(0f)
                )
            }

            // Optional hand tracking
            val hands = if (trackingMode >= TrackingMode.BODY_AND_HANDS) {
                handTracker.detect(bitmap)?.map { handResult ->
                    val handKeypoints = handResult.second.mapIndexed { hkIndex, hlm ->
                        Keypoint(
                            index = hkIndex,
                            // Mirror X so hand keypoints share selfie semantics
                            // with body keypoints (already mirrored above).
                            name = "HAND_$hkIndex",
                            x = 1f - hlm.x(),
                            y = hlm.y(),
                            z = hlm.z(),
                            confidence = hlm.visibility().orElse(0f)
                        )
                    }
                    val (closed, grabConf, curl) = computeGrab(handKeypoints)
                    val now = System.nanoTime()
                    if (now - lastHandLogNs > 1_000_000_000L) {
                        Log.i(TAG, "hand ${handResult.first} curl=${"%.2f".format(curl)} closed=$closed conf=${"%.2f".format(grabConf)}")
                        lastHandLogNs = now
                    }
                    HandPose(
                        side = handResult.first,
                        keypoints = handKeypoints,
                        isClosed = closed,
                        grabConfidence = grabConf,
                    )
                }
            } else null

            // Optional face mesh
            val face = if (trackingMode == TrackingMode.FULL) {
                faceMeshTracker.detect(bitmap)?.let { faceLandmarks ->
                    FaceMesh(
                        keypoints = faceLandmarks.mapIndexed { fkIndex, flm ->
                            Keypoint(
                                index = fkIndex,
                                name = "FACE_$fkIndex",
                                x = flm.x(),
                                y = flm.y(),
                                z = flm.z(),
                                confidence = flm.visibility().orElse(0f)
                            )
                        }
                    )
                }
            } else null

            BodyPose(
                id = index,
                keypoints = keypoints,
                hands = hands,
                face = face
            )
        }

        val endTime = System.nanoTime()
        val latencyMs = (endTime - startTime) / 1_000_000f

        // Update rolling latency
        if (latencyHistory.size >= 30) latencyHistory.removeFirst()
        latencyHistory.addLast(latencyMs)
        _averageLatency.value = latencyHistory.average().toFloat()

        val frame = MotionFrame(
            timestamp = System.nanoTime(),
            frameNumber = frameNumber++,
            latencyMs = latencyMs,
            bodyCount = bodies.size,
            bodies = bodies,
            cameraWidth = cameraWidth,
            cameraHeight = cameraHeight
        )

        _latestFrame.value = frame
    }

    /**
     * Hand-grab heuristic. MediaPipe Hand Landmark indices:
     *   0  = wrist
     *   9  = middle finger MCP (knuckle base) — used as palm-center reference
     *   8/12/16/20 = index/middle/ring/pinky tips
     *
     * Returns (isClosed, grabConfidence). Computes the average normalized
     * distance from each non-thumb fingertip to the palm center, divided by
     * the wrist→middle-MCP "hand size" so the result is scale-invariant.
     *
     * Open hand: ratio ≈ 2.5–3.5. Closed fist: ≈ 0.8–1.4. Threshold at 1.7.
     */
    /** @return Triple of (isClosed, grabConfidence 0..1, raw curl 0..1). */
    private fun computeGrab(kp: List<Keypoint>): Triple<Boolean, Float, Float> {
        if (kp.size < 21) return Triple(false, 0f, -1f)
        // For each non-thumb finger, ratio of straight-line (MCP→TIP) to
        // joint-path length (MCP→PIP→DIP→TIP). Straight finger ≈ 1.0;
        // fully curled ≈ 0.4–0.6. Insensitive to hand rotation/size, which
        // the distance-to-palm metric was not.
        val curls = listOf(
            fingerCurl(kp, 5, 6, 7, 8),     // index
            fingerCurl(kp, 9, 10, 11, 12),  // middle
            fingerCurl(kp, 13, 14, 15, 16), // ring
            fingerCurl(kp, 17, 18, 19, 20), // pinky
        )
        val avgCurl = curls.average().toFloat()
        // Open hand: avgCurl ~0.92–1.0. Closed fist: ~0.45–0.65.
        val closed = avgCurl < 0.75f
        // Grab confidence: 0 at the open boundary, 1.0 at fully curled.
        val grab = ((0.85f - avgCurl) / 0.4f).coerceIn(0f, 1f)
        return Triple(closed, grab, avgCurl)
    }

    private fun fingerCurl(kp: List<Keypoint>, mcp: Int, pip: Int, dip: Int, tip: Int): Float {
        val pathLen = dist(kp[mcp], kp[pip]) + dist(kp[pip], kp[dip]) + dist(kp[dip], kp[tip])
        val straightLen = dist(kp[mcp], kp[tip])
        return if (pathLen < 1e-4f) 1f else straightLen / pathLen
    }

    private fun dist(a: Keypoint, b: Keypoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
