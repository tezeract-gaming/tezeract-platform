package com.tezeract.motion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tezeract.input.CalibrationProfile
import com.tezeract.input.InputEvent
import com.tezeract.motion.camera.CameraXFrameSource
import com.tezeract.motion.camera.FrameProcessor
import com.tezeract.motion.tracking.FaceMeshTracker
import com.tezeract.motion.tracking.GestureClassifier
import com.tezeract.motion.tracking.HandTracker
import com.tezeract.motion.tracking.InputClassifier
import com.tezeract.motion.tracking.PoseTracker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Motion Engine foreground service.
 * Captures camera frames via CameraX, runs MediaPipe pose/hand/face tracking,
 * classifies gestures + semantic input events, and delivers them to bound
 * AIDL clients.
 */
class MotionEngineService : LifecycleService() {
    companion object {
        private const val TAG = "MotionEngineService"
        private const val NOTIFICATION_CHANNEL_ID = "tezeract_motion"
        private const val NOTIFICATION_ID = 1
    }

    // Tracking components
    private lateinit var poseTracker: PoseTracker
    private lateinit var handTracker: HandTracker
    private lateinit var faceMeshTracker: FaceMeshTracker
    private lateinit var gestureClassifier: GestureClassifier
    private lateinit var inputClassifier: InputClassifier
    private lateinit var frameProcessor: FrameProcessor
    private lateinit var frameSource: CameraXFrameSource

    // AIDL client management
    private val motionListeners = RemoteCallbackList<IMotionListener>()
    private val gestureListeners = RemoteCallbackList<IGestureListener>()
    private val inputListeners = RemoteCallbackList<IInputListener>()

    private var latestFrame: MotionFrame? = null
    private var currentTrackingMode = 0

    // Per-user calibration. Persistence lives in TASK-026; for now, in-memory only.
    private val profiles = mutableMapOf<String, CalibrationProfile>()

    private fun profileFor(userId: String): CalibrationProfile =
        profiles.getOrPut(userId) { CalibrationProfile.default(userId) }

    // AIDL Binder implementation
    private val binder = object : IMotionEngine.Stub() {
        override fun getLatestFrame(): MotionFrame? = latestFrame

        override fun registerListener(listener: IMotionListener?) {
            listener?.let { motionListeners.register(it) }
        }
        override fun unregisterListener(listener: IMotionListener?) {
            listener?.let { motionListeners.unregister(it) }
        }

        override fun isCameraConnected(): Boolean = frameSource.isConnected.value
        override fun getCameraFps(): Int = frameSource.currentFps.value
        override fun getAverageLatency(): Float = frameProcessor.averageLatency.value

        override fun setTrackingMode(mode: Int) {
            currentTrackingMode = mode
            frameProcessor.setTrackingMode(TrackingMode.fromValue(mode))
            Log.i(TAG, "Tracking mode set to $mode")
        }

        override fun registerGestureCallback(listener: IGestureListener?) {
            listener?.let { gestureListeners.register(it) }
        }
        override fun unregisterGestureCallback(listener: IGestureListener?) {
            listener?.let { gestureListeners.unregister(it) }
        }

        override fun registerInputListener(listener: IInputListener?) {
            listener?.let { inputListeners.register(it) }
        }
        override fun unregisterInputListener(listener: IInputListener?) {
            listener?.let { inputListeners.unregister(it) }
        }

        override fun getCalibrationProfile(userId: String?): CalibrationProfile =
            profileFor(userId ?: "default")

        override fun setCalibrationProfile(profile: CalibrationProfile, userId: String?) {
            val key = userId ?: "default"
            profiles[key] = profile
            // For the single-user case the active classifiers swap immediately.
            if (key == "default") {
                gestureClassifier.setProfile(profile)
                inputClassifier.setProfile(profile)
            }
            Log.i(TAG, "Calibration profile updated for user=$key")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MotionEngineService creating")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val initialProfile = profileFor("default")

        poseTracker = PoseTracker(this)
        handTracker = HandTracker(this)
        faceMeshTracker = FaceMeshTracker(this)
        gestureClassifier = GestureClassifier(initialProfile)
        inputClassifier = InputClassifier(initialProfile)

        poseTracker.initialize()
        handTracker.initialize()

        frameProcessor = FrameProcessor(poseTracker, handTracker, faceMeshTracker)
        // Default to BODY_AND_HANDS so the TRIGGER_L/R inputs (grab) work
        // out of the box. Clients can downgrade to BODY_ONLY for pure
        // body-tracking games via setTrackingMode.
        frameProcessor.setTrackingMode(TrackingMode.BODY_AND_HANDS)
        currentTrackingMode = TrackingMode.BODY_AND_HANDS.value

        frameSource = CameraXFrameSource(this, this) { bitmap, w, h ->
            frameProcessor.processFrame(bitmap, w, h)
        }

        lifecycleScope.launch {
            frameProcessor.latestFrame.collectLatest { frame ->
                frame ?: return@collectLatest
                latestFrame = frame
                broadcastFrame(frame)
                classifyAndBroadcast(frame)
            }
        }

        lifecycleScope.launch {
            frameSource.isConnected.collectLatest { connected ->
                broadcastCameraStatus(connected)
            }
        }

        frameSource.start()
        Log.i(TAG, "MotionEngineService started")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.i(TAG, "Client binding to MotionEngineService")
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "MotionEngineService destroying")
        frameSource.stop()
        poseTracker.close()
        handTracker.close()
        faceMeshTracker.close()
        gestureClassifier.reset()
        inputClassifier.reset()
        motionListeners.kill()
        gestureListeners.kill()
        inputListeners.kill()
        super.onDestroy()
    }

    private fun broadcastFrame(frame: MotionFrame) {
        val count = motionListeners.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    motionListeners.getBroadcastItem(i).onMotionFrame(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deliver frame to listener $i", e)
                }
            }
        } finally {
            motionListeners.finishBroadcast()
        }
    }

    private fun broadcastCameraStatus(connected: Boolean) {
        val count = motionListeners.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    motionListeners.getBroadcastItem(i).onCameraStatusChanged(connected)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deliver camera status to listener $i", e)
                }
            }
        } finally {
            motionListeners.finishBroadcast()
        }
    }

    /**
     * For each tracked body: run gesture classifier, broadcast gestures,
     * feed gestures + body into input classifier, broadcast input events.
     */
    private fun classifyAndBroadcast(frame: MotionFrame) {
        for (body in frame.bodies) {
            val gestures = gestureClassifier.classify(body, frame.timestamp)
            for (gesture in gestures) {
                broadcastGesture(gesture)
            }
            val events = inputClassifier.classify(body, gestures, frame.timestamp)
            for (event in events) {
                broadcastInputEvent(event)
            }
        }
    }

    private fun broadcastGesture(gesture: Gesture) {
        val count = gestureListeners.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    gestureListeners.getBroadcastItem(i)
                        .onGesture(gesture.name, gesture.bodyId, gesture.confidence)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deliver gesture to listener $i", e)
                }
            }
        } finally {
            gestureListeners.finishBroadcast()
        }
    }

    private fun broadcastInputEvent(event: InputEvent) {
        if (event.action != com.tezeract.input.InputAction.HOLD) {
            Log.i(TAG, "input ${event.input}/${event.action} conf=${"%.2f".format(event.confidence)} body=${event.bodyId}")
        }
        val count = inputListeners.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    inputListeners.getBroadcastItem(i).onInputEvent(event)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deliver input event to listener $i", e)
                }
            }
        } finally {
            inputListeners.finishBroadcast()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Tezeract Motion Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Motion tracking is active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Tezeract Motion Engine")
            .setContentText("Motion tracking active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
