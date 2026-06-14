package com.tezeract.sdk

import com.tezeract.motion.MotionFrame

/**
 * Listener for real-time motion frame updates from the Tezeract Motion Engine.
 */
interface MotionListener {
    /**
     * Called on every processed camera frame with body tracking data.
     * Runs on a binder thread — offload heavy work.
     */
    fun onMotionFrame(frame: MotionFrame)

    /**
     * Called when a new body is detected and tracking begins.
     */
    fun onTrackingStarted(bodyId: Int) {}

    /**
     * Called when tracking is lost for a body.
     */
    fun onTrackingLost(bodyId: Int) {}

    /**
     * Called when the USB camera connection status changes.
     */
    fun onCameraStatusChanged(connected: Boolean) {}
}
