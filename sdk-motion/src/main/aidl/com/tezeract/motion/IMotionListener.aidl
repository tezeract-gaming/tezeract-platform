package com.tezeract.motion;

import com.tezeract.motion.MotionFrame;

interface IMotionListener {
    void onMotionFrame(in MotionFrame frame);
    void onCameraStatusChanged(boolean connected);
    void onTrackingLost(int bodyId);
}
