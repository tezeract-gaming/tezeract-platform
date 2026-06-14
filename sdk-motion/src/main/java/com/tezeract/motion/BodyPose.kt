package com.tezeract.motion

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BodyPose(
    val id: Int,                    // Body ID for multi-person tracking
    val keypoints: List<Keypoint>,  // 33 body keypoints
    val hands: List<HandPose>?,     // null if hand tracking disabled
    val face: FaceMesh?             // null if face mesh disabled
) : Parcelable
