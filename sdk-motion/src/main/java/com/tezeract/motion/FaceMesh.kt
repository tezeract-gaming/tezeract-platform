package com.tezeract.motion

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FaceMesh(
    val keypoints: List<Keypoint>  // 468 face keypoints
) : Parcelable
