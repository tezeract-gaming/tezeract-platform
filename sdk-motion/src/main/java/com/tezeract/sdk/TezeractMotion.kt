package com.tezeract.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.tezeract.input.CalibrationProfile
import com.tezeract.input.InputEvent
import com.tezeract.input.InputListener
import com.tezeract.motion.Gesture
import com.tezeract.motion.IInputListener
import com.tezeract.motion.IMotionEngine
import com.tezeract.motion.IMotionListener
import com.tezeract.motion.IGestureListener
import com.tezeract.motion.MotionFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Main entry point for the Tezeract Motion SDK.
 *
 * Usage:
 *   TezeractMotion.initialize(context)
 *   TezeractMotion.addMotionListener(myListener)
 *   // ... receive callbacks ...
 *   TezeractMotion.release()
 */
object TezeractMotion {
    private const val TAG = "TezeractMotion"
    private const val SERVICE_ACTION = "com.tezeract.motion.BIND"
    private const val SERVICE_PACKAGE = "com.tezeract.motion"

    private var context: Context? = null
    private var motionEngine: IMotionEngine? = null
    private var isBound = false

    // Set to true on release() so any in-flight reconnect timer becomes a no-op.
    private var released = false

    private val motionListeners = CopyOnWriteArrayList<MotionListener>()
    private val gestureListeners = CopyOnWriteArrayList<GestureListener>()
    private val inputListeners = CopyOnWriteArrayList<InputListener>()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable

    private val _isCameraConnected = MutableStateFlow(false)
    val isCameraConnected: StateFlow<Boolean> = _isCameraConnected

    // True between the service binder dying (or onServiceDisconnected firing)
    // and the next successful onServiceConnected. UI can show a "Reconnecting
    // motion…" overlay while this is true. Auto-clears when service comes back.
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting

    // Mainthread handler for scheduling reconnect retries.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private const val INITIAL_RECONNECT_DELAY_MS = 1_500L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

    // Emulator mode for development without hardware
    private var emulatorMode: EmulatorMode? = null

    /**
     * Fires when the service process dies (kill, crash, OOM). Faster signal
     * than [ServiceConnection.onServiceDisconnected] — the binder is gone
     * the instant Android notices the host process is no longer alive.
     */
    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Motion Engine binder died — entering reconnect loop")
        mainHandler.post { onConnectionLost() }
    }

    private fun onConnectionLost() {
        motionEngine = null
        isBound = false
        _isAvailable.value = false
        _isCameraConnected.value = false
        if (released) return
        _isReconnecting.value = true
        scheduleReconnect()
    }

    /**
     * Best-effort manual rebind. Android's [Context.BIND_AUTO_CREATE] usually
     * brings the service back on its own, but on some vendor builds the
     * auto-restart can stall — this gives us a deterministic backstop with
     * exponential backoff capped at 30s.
     */
    private fun scheduleReconnect() {
        if (released) return
        val ctx = context ?: return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        mainHandler.postDelayed({
            if (released || isBound) return@postDelayed
            Log.i(TAG, "Reconnect attempt — rebinding to Motion Engine service")
            try {
                // unbind first in case Android has us in a half-bound state
                try { ctx.unbindService(serviceConnection) } catch (_: Exception) {}
                val intent = Intent(SERVICE_ACTION).apply { setPackage(SERVICE_PACKAGE) }
                ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect bind failed; will retry", e)
                scheduleReconnect()
            }
        }, delay)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to Motion Engine service")
            motionEngine = IMotionEngine.Stub.asInterface(service)
            isBound = true
            _isAvailable.value = true
            _isReconnecting.value = false
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

            // Fast-path crash detection — fires before onServiceDisconnected
            // when the service process is killed.
            try {
                service?.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                Log.w(TAG, "linkToDeath failed", e)
            }

            // Register AIDL listeners to bridge to SDK listeners. This also
            // re-registers them after a reconnect — service-side state was
            // wiped when the process died, so we resubscribe from scratch.
            motionEngine?.registerListener(aidlMotionListener)
            motionEngine?.registerGestureCallback(aidlGestureListener)
            motionEngine?.registerGestureCallback(aidlHomeGestureListener)
            motionEngine?.registerInputListener(aidlInputListener)

            // Sync camera state
            _isCameraConnected.value = motionEngine?.isCameraConnected ?: false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Slow-path signal. If the binder died, deathRecipient already
            // fired; this is mostly belt-and-suspenders.
            Log.w(TAG, "Disconnected from Motion Engine service")
            mainHandler.post { onConnectionLost() }
        }
    }

    // AIDL listener that bridges to SDK MotionListener callbacks
    private val aidlMotionListener = object : IMotionListener.Stub() {
        override fun onMotionFrame(frame: MotionFrame) {
            for (listener in motionListeners) {
                listener.onMotionFrame(frame)
            }
        }

        override fun onCameraStatusChanged(connected: Boolean) {
            _isCameraConnected.value = connected
            for (listener in motionListeners) {
                listener.onCameraStatusChanged(connected)
            }
        }

        override fun onTrackingLost(bodyId: Int) {
            for (listener in motionListeners) {
                listener.onTrackingLost(bodyId)
            }
        }
    }

    // AIDL listener that bridges to SDK GestureListener callbacks
    private val aidlGestureListener = object : IGestureListener.Stub() {
        override fun onGesture(gestureName: String, bodyId: Int, confidence: Float) {
            val gesture = com.tezeract.motion.Gesture(
                name = gestureName,
                bodyId = bodyId,
                confidence = confidence,
                timestamp = System.nanoTime()
            )
            for (listener in gestureListeners) {
                listener.onGesture(gesture)
            }
        }
    }

    /**
     * Universal HOME_TRIANGLE handler: any app using the SDK gets a free
     * "go home" escape gesture. We fire ACTION_MAIN + CATEGORY_HOME, which
     * Android routes to whichever launcher is the system HOME app — by
     * default ours (`com.tezeract.launcher`). Games don't have to wire any
     * exit logic themselves.
     */
    private val aidlHomeGestureListener = object : IGestureListener.Stub() {
        override fun onGesture(gestureName: String, bodyId: Int, confidence: Float) {
            if (gestureName != Gesture.HOME_TRIANGLE) return
            val ctx = context ?: return
            try {
                Log.i(TAG, "HOME_TRIANGLE → firing system HOME intent")
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                ctx.startActivity(home)
            } catch (e: Exception) {
                Log.w(TAG, "HOME_TRIANGLE: failed to start home intent", e)
            }
        }
    }

    // AIDL listener that bridges to SDK InputListener callbacks
    private val aidlInputListener = object : IInputListener.Stub() {
        override fun onInputEvent(event: InputEvent) {
            for (listener in inputListeners) {
                listener.onInputEvent(event)
            }
        }
    }

    /**
     * Initialize the SDK and bind to the Motion Engine service.
     * Call in Application.onCreate() or Activity.onCreate().
     */
    fun initialize(context: Context) {
        this.context = context.applicationContext
        released = false
        Log.i(TAG, "Initializing TezeractMotion SDK")

        val intent = Intent(SERVICE_ACTION).apply {
            setPackage(SERVICE_PACKAGE)
        }

        try {
            context.applicationContext.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Motion Engine service not found. Running on non-Tezeract device?", e)
            _isAvailable.value = false
        }
    }

    /**
     * Returns true if running on a Tezeract device with Motion Engine installed.
     */
    fun isAvailable(): Boolean = _isAvailable.value

    /**
     * Returns true if the USB camera is connected and streaming.
     */
    fun isCameraConnected(): Boolean = _isCameraConnected.value

    /**
     * Set the tracking mode.
     * @param mode TrackingMode.BODY_ONLY (default, fastest),
     *             TrackingMode.BODY_AND_HANDS, or TrackingMode.FULL
     */
    fun setTrackingMode(mode: com.tezeract.motion.TrackingMode) {
        motionEngine?.setTrackingMode(mode.value)
            ?: emulatorMode?.let { Log.d(TAG, "Emulator mode: tracking mode set to $mode") }
            ?: Log.w(TAG, "setTrackingMode called but service not connected")
    }

    /**
     * Add a listener for real-time motion frame updates.
     */
    fun addMotionListener(listener: MotionListener) {
        motionListeners.add(listener)
    }

    /**
     * Remove a motion listener.
     */
    fun removeMotionListener(listener: MotionListener) {
        motionListeners.remove(listener)
    }

    /**
     * Add a listener for gesture events.
     */
    fun addGestureListener(listener: GestureListener) {
        gestureListeners.add(listener)
    }

    /**
     * Remove a gesture listener.
     */
    fun removeGestureListener(listener: GestureListener) {
        gestureListeners.remove(listener)
    }

    /**
     * Add a listener for semantic input events (gamepad-style PRESS/HOLD/RELEASE).
     * Prefer this over [addGestureListener] when wiring game controls — it
     * gives you a stable, calibrated input model instead of raw gesture names.
     */
    fun addInputListener(listener: InputListener) {
        inputListeners.add(listener)
    }

    /**
     * Remove an input listener.
     */
    fun removeInputListener(listener: InputListener) {
        inputListeners.remove(listener)
    }

    /**
     * Get the currently active calibration profile for [userId]. Returns
     * [CalibrationProfile.default] if the user has not been calibrated yet.
     * Returns null if the SDK isn't bound to the service.
     */
    fun getCalibrationProfile(userId: String = "default"): CalibrationProfile? =
        motionEngine?.getCalibrationProfile(userId)

    /**
     * Replace the calibration profile for [userId]. Service-side classifiers
     * pick up the new thresholds immediately for the "default" user.
     */
    fun setCalibrationProfile(profile: CalibrationProfile, userId: String = "default") {
        motionEngine?.setCalibrationProfile(profile, userId)
    }

    /**
     * Poll-based access to the latest motion frame.
     * Use this in game loops instead of listeners.
     */
    fun getLatestFrame(): MotionFrame? {
        return motionEngine?.latestFrame
            ?: emulatorMode?.getLatestFrame()
    }

    /**
     * Get the current camera FPS.
     */
    fun getCameraFps(): Int = motionEngine?.cameraFps ?: 0

    /**
     * Get the average camera-to-callback latency in milliseconds.
     */
    fun getAverageLatency(): Float = motionEngine?.averageLatency ?: 0f

    /**
     * Enable emulator mode for development without Tezeract hardware.
     * Loads motion data from a JSON file to simulate tracking.
     */
    fun enableEmulatorMode(jsonAssetPath: String) {
        val ctx = context ?: throw IllegalStateException("Call initialize() before enableEmulatorMode()")
        emulatorMode = EmulatorMode(ctx, jsonAssetPath, motionListeners, gestureListeners)
        _isAvailable.value = true
        _isCameraConnected.value = true
        Log.i(TAG, "Emulator mode enabled with: $jsonAssetPath")
    }

    /**
     * Start emulator playback. Only works if emulator mode is enabled.
     */
    fun startEmulator() {
        emulatorMode?.start() ?: Log.w(TAG, "Emulator mode not enabled")
    }

    /**
     * Stop emulator playback.
     */
    fun stopEmulator() {
        emulatorMode?.stop()
    }

    /**
     * Unbind from the Motion Engine service and clean up.
     * Call in Activity.onDestroy().
     */
    fun release() {
        Log.i(TAG, "Releasing TezeractMotion SDK")
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        emulatorMode?.stop()
        emulatorMode = null

        if (isBound) {
            try {
                motionEngine?.unregisterListener(aidlMotionListener)
                motionEngine?.unregisterGestureCallback(aidlGestureListener)
                motionEngine?.unregisterGestureCallback(aidlHomeGestureListener)
                motionEngine?.unregisterInputListener(aidlInputListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering listeners", e)
            }
            // Unlink the death recipient — leaking it would keep this object
            // alive across the unbind. Best-effort; the binder may already
            // be gone if we're tearing down because the service died.
            try {
                motionEngine?.asBinder()?.unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {}
            try {
                context?.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service", e)
            }
            isBound = false
        }

        motionEngine = null
        _isAvailable.value = false
        _isCameraConnected.value = false
        _isReconnecting.value = false
        motionListeners.clear()
        gestureListeners.clear()
        inputListeners.clear()
        context = null
    }
}
