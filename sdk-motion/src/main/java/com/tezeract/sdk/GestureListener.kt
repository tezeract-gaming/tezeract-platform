package com.tezeract.sdk

import com.tezeract.motion.Gesture

/**
 * Listener for gesture events detected by the Tezeract Motion Engine.
 */
interface GestureListener {
    /**
     * Called when a gesture is detected.
     *
     * @param gesture The detected gesture with name, bodyId, confidence, and timestamp.
     */
    fun onGesture(gesture: Gesture)
}
