package com.tezeract.samplegame

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.tezeract.motion.Gesture
import com.tezeract.motion.Keypoint
import com.tezeract.motion.MotionFrame
import com.tezeract.sdk.GestureListener
import com.tezeract.sdk.MotionListener
import com.tezeract.sdk.TezeractMotion
import kotlin.random.Random

/**
 * "Catch Falling Objects" — a simple validation game for the Tezeract Motion SDK.
 *
 * Objects fall from the top of the screen. Move your hands left/right to catch them.
 * Uses left and right wrist keypoints to position two "paddles".
 * Jump gesture = power-up (catch all objects on screen).
 */
class MainActivity : Activity(), MotionListener, GestureListener, SurfaceHolder.Callback {
    companion object {
        private const val TAG = "SampleGame"
        private const val OBJECT_SPAWN_INTERVAL_MS = 800L
        private const val MAX_OBJECTS = 10
        private const val PADDLE_WIDTH = 120f
        private const val PADDLE_HEIGHT = 30f
        private const val OBJECT_SIZE = 40f
        private const val FALL_SPEED_BASE = 4f

        val OBJECT_COLORS = intArrayOf(
            Color.parseColor("#FF6B6B"), // Red
            Color.parseColor("#4ECDC4"), // Teal
            Color.parseColor("#FFE66D"), // Yellow
            Color.parseColor("#A8E6CF"), // Mint
            Color.parseColor("#FF8A5C"), // Orange
        )
    }

    private var surfaceView: SurfaceView? = null
    private var gameThread: GameThread? = null

    // Game state
    private var score = 0
    private var missed = 0
    private var leftPaddleX = 0.3f  // Normalized 0-1
    private var rightPaddleX = 0.7f // Normalized 0-1
    private val fallingObjects = mutableListOf<FallingObject>()
    private var lastSpawnTime = 0L
    private var screenWidth = 1280f
    private var screenHeight = 720f
    private var isRunning = false
    private var powerUpActive = false
    private var powerUpEndTime = 0L

    data class FallingObject(
        var x: Float,      // Normalized 0-1
        var y: Float,      // Normalized 0-1
        val color: Int,
        val speed: Float
    )

    // Paints
    private val paddlePaint = Paint().apply {
        color = Color.parseColor("#9333EA") // Tezeract purple
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val objectPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1A0A2E") // Dark purple background
        style = Paint.Style.FILL
    }
    private val hudPaint = Paint().apply {
        color = Color.parseColor("#2D1B4E")
        style = Paint.Style.FILL
    }
    private val powerUpPaint = Paint().apply {
        color = Color.parseColor("#FFD700") // Gold
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val overlayBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val overlayBorderPaint = Paint().apply {
        color = Color.parseColor("#9333EA")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val skeletonPaint = Paint().apply {
        color = Color.parseColor("#4ECDC4")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val keypointPaint = Paint().apply {
        color = Color.parseColor("#FFE66D")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val overlayLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        isAntiAlias = true
    }

    @Volatile private var latestFrame: MotionFrame? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        surfaceView = SurfaceView(this).also {
            it.holder.addCallback(this)
            setContentView(it)
        }

        TezeractMotion.initialize(this)
        TezeractMotion.addMotionListener(this)
        TezeractMotion.addGestureListener(this)

        Log.i(TAG, "Sample game started")
    }

    override fun onDestroy() {
        isRunning = false
        gameThread?.join(1000)
        TezeractMotion.removeMotionListener(this)
        TezeractMotion.removeGestureListener(this)
        TezeractMotion.release()
        super.onDestroy()
    }

    // --- MotionListener ---

    override fun onMotionFrame(frame: MotionFrame) {
        latestFrame = frame
        if (frame.bodyCount > 0) {
            val body = frame.bodies[0]
            val leftWrist = body.keypoints.getOrNull(Keypoint.LEFT_WRIST)
            val rightWrist = body.keypoints.getOrNull(Keypoint.RIGHT_WRIST)

            if (leftWrist != null && leftWrist.confidence > 0.5f) {
                leftPaddleX = leftWrist.x
            }
            if (rightWrist != null && rightWrist.confidence > 0.5f) {
                rightPaddleX = rightWrist.x
            }
        }
    }

    // MediaPipe Pose connection topology — pairs of keypoint indices to draw as bones.
    private val skeletonBones = listOf(
        Keypoint.LEFT_SHOULDER to Keypoint.RIGHT_SHOULDER,
        Keypoint.LEFT_SHOULDER to Keypoint.LEFT_ELBOW,
        Keypoint.LEFT_ELBOW to Keypoint.LEFT_WRIST,
        Keypoint.RIGHT_SHOULDER to Keypoint.RIGHT_ELBOW,
        Keypoint.RIGHT_ELBOW to Keypoint.RIGHT_WRIST,
        Keypoint.LEFT_SHOULDER to Keypoint.LEFT_HIP,
        Keypoint.RIGHT_SHOULDER to Keypoint.RIGHT_HIP,
        Keypoint.LEFT_HIP to Keypoint.RIGHT_HIP,
        Keypoint.LEFT_HIP to Keypoint.LEFT_KNEE,
        Keypoint.LEFT_KNEE to Keypoint.LEFT_ANKLE,
        Keypoint.RIGHT_HIP to Keypoint.RIGHT_KNEE,
        Keypoint.RIGHT_KNEE to Keypoint.RIGHT_ANKLE,
    )

    private fun drawDebugOverlay(canvas: Canvas) {
        val frame = latestFrame ?: return
        val pad = 16f
        val w = 280f
        val h = 200f
        val left = pad
        val top = 80f
        val right = left + w
        val bottom = top + h

        canvas.drawRect(left, top, right, bottom, overlayBgPaint)
        canvas.drawRect(left, top, right, bottom, overlayBorderPaint)

        if (frame.bodyCount == 0) {
            canvas.drawText("no body detected", left + 10f, top + 30f, overlayLabelPaint)
            return
        }

        val body = frame.bodies[0]
        // Coordinates are already mirrored at the service so left-of-body reads
        // as left-of-screen — render as-is.
        fun sx(x: Float) = left + x * w
        fun sy(y: Float) = top + y * h

        for ((a, b) in skeletonBones) {
            val ka = body.keypoints.getOrNull(a) ?: continue
            val kb = body.keypoints.getOrNull(b) ?: continue
            if (ka.confidence < 0.3f || kb.confidence < 0.3f) continue
            canvas.drawLine(sx(ka.x), sy(ka.y), sx(kb.x), sy(kb.y), skeletonPaint)
        }
        for (kp in body.keypoints) {
            if (kp.confidence < 0.3f) continue
            canvas.drawCircle(sx(kp.x), sy(kp.y), 3f, keypointPaint)
        }
        canvas.drawText("body 0 / 33 kpts / ${frame.latencyMs.toInt()}ms",
            left + 8f, bottom - 8f, overlayLabelPaint)
    }

    // --- GestureListener ---

    override fun onGesture(gesture: Gesture) {
        when (gesture.name) {
            Gesture.JUMP -> {
                // Power-up: catch all objects
                powerUpActive = true
                powerUpEndTime = System.currentTimeMillis() + 500
                synchronized(fallingObjects) {
                    score += fallingObjects.size
                    fallingObjects.clear()
                }
                Log.i(TAG, "JUMP power-up! Score: $score")
            }
        }
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        screenWidth = holder.surfaceFrame.width().toFloat()
        screenHeight = holder.surfaceFrame.height().toFloat()
        isRunning = true
        gameThread = GameThread(holder).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        gameThread?.join(1000)
    }

    // --- Game Loop ---

    inner class GameThread(private val holder: SurfaceHolder) : Thread("GameThread") {
        override fun run() {
            while (isRunning) {
                val startTime = System.currentTimeMillis()

                update()

                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    canvas?.let { draw(it) }
                } finally {
                    canvas?.let {
                        try { holder.unlockCanvasAndPost(it) } catch (_: Exception) {}
                    }
                }

                // Target 60fps
                val elapsed = System.currentTimeMillis() - startTime
                val sleepTime = (16 - elapsed).coerceAtLeast(0)
                if (sleepTime > 0) sleep(sleepTime)
            }
        }
    }

    private fun update() {
        val now = System.currentTimeMillis()

        // Power-up timeout
        if (powerUpActive && now > powerUpEndTime) {
            powerUpActive = false
        }

        // Spawn new objects
        if (now - lastSpawnTime > OBJECT_SPAWN_INTERVAL_MS && fallingObjects.size < MAX_OBJECTS) {
            synchronized(fallingObjects) {
                fallingObjects.add(
                    FallingObject(
                        x = Random.nextFloat() * 0.8f + 0.1f,
                        y = 0f,
                        color = OBJECT_COLORS[Random.nextInt(OBJECT_COLORS.size)],
                        speed = FALL_SPEED_BASE + Random.nextFloat() * 2f
                    )
                )
            }
            lastSpawnTime = now
        }

        // Move objects and check collisions
        val paddleY = 0.85f // Paddles near bottom
        val catchRadius = 0.06f

        synchronized(fallingObjects) {
            val iterator = fallingObjects.iterator()
            while (iterator.hasNext()) {
                val obj = iterator.next()
                obj.y += obj.speed / screenHeight

                if (obj.y >= paddleY) {
                    // Check if caught by either paddle
                    val leftDist = kotlin.math.abs(obj.x - leftPaddleX)
                    val rightDist = kotlin.math.abs(obj.x - rightPaddleX)

                    if (leftDist < catchRadius || rightDist < catchRadius) {
                        score++
                        iterator.remove()
                    } else if (obj.y > 1.0f) {
                        missed++
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun draw(canvas: Canvas) {
        // Background
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, bgPaint)

        // Power-up flash
        if (powerUpActive) {
            canvas.drawRect(0f, 0f, screenWidth, screenHeight, powerUpPaint.apply { alpha = 30 })
        }

        // Falling objects
        synchronized(fallingObjects) {
            for (obj in fallingObjects) {
                objectPaint.color = obj.color
                canvas.drawCircle(
                    obj.x * screenWidth,
                    obj.y * screenHeight,
                    OBJECT_SIZE,
                    objectPaint
                )
            }
        }

        // Paddles
        val paddleY = 0.85f * screenHeight
        drawPaddle(canvas, leftPaddleX * screenWidth, paddleY)
        drawPaddle(canvas, rightPaddleX * screenWidth, paddleY)

        // HUD
        canvas.drawRect(0f, 0f, screenWidth, 70f, hudPaint)
        canvas.drawText("Score: $score", 20f, 50f, textPaint)
        canvas.drawText("Missed: $missed", 300f, 50f, textPaint)

        val fps = TezeractMotion.getCameraFps()
        val latency = TezeractMotion.getAverageLatency()
        canvas.drawText("${fps}fps | ${latency.toInt()}ms", screenWidth - 300f, 50f,
            textPaint.apply { textSize = 36f })
        textPaint.textSize = 48f

        if (!TezeractMotion.isAvailable()) {
            canvas.drawText("Motion Engine not connected", screenWidth / 2 - 300f,
                screenHeight / 2, textPaint.apply { color = Color.RED })
            textPaint.color = Color.WHITE
        }

        // Skeleton wireframe is a developer aid — keep it out of release builds
        // so the bundled sample game looks clean for the first wave of buyers.
        if (BuildConfig.DEBUG) {
            drawDebugOverlay(canvas)
        }
    }

    private fun drawPaddle(canvas: Canvas, x: Float, y: Float) {
        val rect = RectF(
            x - PADDLE_WIDTH / 2, y - PADDLE_HEIGHT / 2,
            x + PADDLE_WIDTH / 2, y + PADDLE_HEIGHT / 2
        )
        canvas.drawRoundRect(rect, 10f, 10f, paddlePaint)
    }

}
