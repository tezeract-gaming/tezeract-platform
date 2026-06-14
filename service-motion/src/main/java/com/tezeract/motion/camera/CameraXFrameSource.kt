package com.tezeract.motion.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * CameraX-backed frame source. Talks to Android's standard camera HAL, which on
 * the Orange Pi 5 Plus exposes attached USB webcams via the external camera
 * provider (camera-provider-2-4-ext). Supersedes the old AUSBC-based UvcCameraManager.
 *
 * Selection order: external > back > front > any. Streams 1280x720 RGBA bitmaps
 * into [onFrame]; back-pressure strategy keeps only the latest frame so the
 * MediaPipe pipeline never queues up.
 */
class CameraXFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (Bitmap, Int, Int) -> Unit
) {
    companion object {
        private const val TAG = "CameraXFrameSource"
        private const val MAX_INFERENCE_WIDTH = 640
        // After this many consecutive frame-processing errors we conclude the
        // pipeline is unhealthy (bad URB stream, USB unplugged mid-flight,
        // bitmap allocation failure, etc.) and report the camera as down.
        // 10 frames is ~300ms at 30fps — quick enough to fail visibly,
        // tolerant enough to ride out one-off plane decode hiccups.
        private const val MAX_CONSECUTIVE_ERRORS = 10
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CameraXAnalysis").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps

    private var frameCount = 0L
    private var lastFpsTimestamp = System.nanoTime()
    private var loggedFirstFrame = false
    private var consecutiveErrors = 0
    private var totalFrameErrors = 0L

    fun start() {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CAMERA permission not granted. Run: " +
                "adb shell pm grant com.tezeract.motion android.permission.CAMERA")
            return
        }

        Log.i(TAG, "Requesting ProcessCameraProvider")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bind(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to obtain ProcessCameraProvider", e)
                _isConnected.value = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        Log.i(TAG, "Stopping CameraXFrameSource")
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll failed", e)
        }
        cameraProvider = null
        imageAnalysis = null
        _isConnected.value = false
        _currentFps.value = 0
        analysisExecutor.shutdown()
    }

    private fun bind(provider: ProcessCameraProvider) {
        provider.unbindAll()

        val selector = pickCameraSelector(provider)
        if (selector == null) {
            Log.w(TAG, "No camera available. Plug in a USB webcam and try again.")
            _isConnected.value = false
            return
        }

        // No target resolution — let CameraX pick whatever the camera natively supports.
        // The vendor HAL on RK3588 mis-reports formats for some webcams; over-constraining
        // here causes ERROR_CAMERA_DEVICE on stream configure. Once we confirm frames
        // flow we can revisit and request a target.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(analysisExecutor) { proxy -> handleFrame(proxy) }

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            imageAnalysis = analysis
            _isConnected.value = true
            Log.i(TAG, "Bound camera, awaiting frames")
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle failed", e)
            _isConnected.value = false
        }
    }

    private fun handleFrame(proxy: ImageProxy) {
        try {
            if (!loggedFirstFrame) {
                Log.i(TAG, "First frame: ${proxy.width}x${proxy.height} format=${proxy.format} planes=${proxy.planes.size}")
                loggedFirstFrame = true
            }
            val plane = proxy.planes.firstOrNull() ?: return
            val src = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            src.copyPixelsFromBuffer(plane.buffer)
            // Downscale to ~480p before pose inference. MediaPipe's full-body pose
            // model gains negligible accuracy above this size and the inference
            // cost is roughly quadratic in pixel count — going from 1280x1024 to
            // 640x512 cuts MediaPipe time ~4x.
            val scaled = if (src.width > MAX_INFERENCE_WIDTH) {
                val ratio = MAX_INFERENCE_WIDTH.toFloat() / src.width
                val newH = (src.height * ratio).toInt()
                val out = Bitmap.createScaledBitmap(src, MAX_INFERENCE_WIDTH, newH, true)
                src.recycle()
                out
            } else src
            trackFps()
            onFrame(scaled, scaled.width, scaled.height)
            // A successful frame ends any streak — flip the camera back to
            // healthy if it had been marked down.
            if (consecutiveErrors > 0) {
                consecutiveErrors = 0
                if (!_isConnected.value) {
                    Log.i(TAG, "Frame stream recovered; marking camera connected again")
                    _isConnected.value = true
                }
            }
        } catch (e: Exception) {
            consecutiveErrors++
            totalFrameErrors++
            Log.w(TAG, "Frame handling failed (consecutive=$consecutiveErrors, total=$totalFrameErrors)", e)
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS && _isConnected.value) {
                Log.e(TAG, "$MAX_CONSECUTIVE_ERRORS consecutive frame errors — marking camera disconnected")
                _isConnected.value = false
            }
        } finally {
            proxy.close()
        }
    }

    private fun pickCameraSelector(provider: ProcessCameraProvider): CameraSelector? {
        // Prefer external (USB), then back, then front, then anything available.
        val infos = provider.availableCameraInfos
        Log.i(TAG, "Available cameras: ${infos.size}")
        infos.forEachIndexed { i, info ->
            Log.i(TAG, "  [$i] lensFacing=${lensName(info)}")
        }

        val priorities = listOf(
            CameraSelector.LENS_FACING_EXTERNAL,
            CameraSelector.LENS_FACING_BACK,
            CameraSelector.LENS_FACING_FRONT
        )
        for (facing in priorities) {
            val match = infos.firstOrNull { it.lensFacing == facing }
            if (match != null) {
                Log.i(TAG, "Selected camera with lensFacing=${lensName(match)}")
                return CameraSelector.Builder().requireLensFacing(facing).build()
            }
        }
        return null
    }

    private fun lensName(info: CameraInfo): String = when (info.lensFacing) {
        CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
        CameraSelector.LENS_FACING_BACK -> "BACK"
        CameraSelector.LENS_FACING_FRONT -> "FRONT"
        else -> "UNKNOWN(${info.lensFacing})"
    }

    private fun trackFps() {
        frameCount++
        val now = System.nanoTime()
        val elapsed = (now - lastFpsTimestamp) / 1_000_000_000.0
        if (elapsed >= 1.0) {
            val fps = (frameCount / elapsed).toInt()
            _currentFps.value = fps
            Log.i(TAG, "fps=$fps")
            frameCount = 0
            lastFpsTimestamp = now
        }
    }
}
