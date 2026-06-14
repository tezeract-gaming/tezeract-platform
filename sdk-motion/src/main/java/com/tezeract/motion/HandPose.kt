package com.tezeract.motion

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One detected hand. Carries the raw 21 MediaPipe hand landmarks plus a
 * derived "is the hand closed into a fist" signal that the input classifier
 * uses to fire the TRIGGER_L / TRIGGER_R inputs.
 *
 * @property side  "LEFT" or "RIGHT"
 * @property keypoints  21 hand landmarks (MediaPipe topology)
 * @property isClosed   true when the four non-thumb fingers have curled in
 *                      toward the palm. Computed from [keypoints] by
 *                      `FrameProcessor`; see [grabConfidence] for the raw
 *                      score.
 * @property grabConfidence Higher = more closed. The detector reports a
 *                      normalized "openness" — values above ~1.8 are clearly
 *                      open, below ~1.5 clearly closed; this property is
 *                      `2 - openness` clamped to 0..1 so it reads naturally.
 */
@Parcelize
data class HandPose(
    val side: String,
    val keypoints: List<Keypoint>,
    val isClosed: Boolean = false,
    val grabConfidence: Float = 0f,
) : Parcelable
