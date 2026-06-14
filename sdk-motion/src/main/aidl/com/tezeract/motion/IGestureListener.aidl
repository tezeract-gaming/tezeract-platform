package com.tezeract.motion;

interface IGestureListener {
    void onGesture(String gestureName, int bodyId, float confidence);
}
