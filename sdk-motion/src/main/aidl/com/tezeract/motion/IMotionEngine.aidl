package com.tezeract.motion;

import com.tezeract.motion.MotionFrame;
import com.tezeract.motion.IMotionListener;
import com.tezeract.motion.IGestureListener;
import com.tezeract.motion.IInputListener;
import com.tezeract.input.CalibrationProfile;

interface IMotionEngine {
    // Get current motion state (poll-based)
    MotionFrame getLatestFrame();

    // Subscribe to real-time motion updates
    void registerListener(IMotionListener listener);
    void unregisterListener(IMotionListener listener);

    // Camera status
    boolean isCameraConnected();
    int getCameraFps();
    float getAverageLatency();

    // Configuration
    // mode: 0=body_only, 1=body+hands, 2=body+hands+face
    void setTrackingMode(int mode);

    // Gesture recognition (raw — fire-and-forget gesture name + confidence)
    void registerGestureCallback(IGestureListener listener);
    void unregisterGestureCallback(IGestureListener listener);

    // Semantic input layer — gamepad-style PRESS/HOLD/RELEASE events
    void registerInputListener(IInputListener listener);
    void unregisterInputListener(IInputListener listener);

    // Per-user calibration. userId="default" for single-user devices.
    CalibrationProfile getCalibrationProfile(String userId);
    void setCalibrationProfile(in CalibrationProfile profile, String userId);
}
