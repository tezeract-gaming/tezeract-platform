package com.tezeract.motion

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MotionFrame(
    val timestamp: Long,        // System.nanoTime()
    val frameNumber: Long,      // Sequential frame counter
    val latencyMs: Float,       // Camera-to-output latency
    val bodyCount: Int,         // Number of bodies detected (0-2)
    val bodies: List<BodyPose>, // Up to 2 tracked bodies
    val cameraWidth: Int,       // 1280
    val cameraHeight: Int       // 720
) : Parcelable
