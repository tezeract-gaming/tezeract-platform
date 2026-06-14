package com.tezeract.flappy

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import com.tezeract.input.InputAction
import com.tezeract.input.InputListener
import com.tezeract.input.TezeractInput
import com.tezeract.motion.BodyPose
import com.tezeract.motion.Keypoint
import com.tezeract.motion.MotionFrame
import com.tezeract.sdk.MotionListener
import com.tezeract.sdk.TezeractMotion

/**
 * Flappy Arms — flap your arms to fly through pipes!
 *
 * Motion control: raise both wrists above shoulders rapidly = flap (bird goes up).
 * Gravity pulls the bird down. Pipes scroll from right to left.
 */
class MainActivity : AppCompatActivity(), MotionListener {

    companion object {
        private const val TAG = "FlappyArms"
        // Gravity / flap tuned for accessible play. The bird falls noticeably
        // slower than vanilla Flappy Bird and the gap is wider — the natural
        // motion-driven flap rate is the limiting factor, not pipe difficulty.
        private const val GRAVITY = 0.40f
        private const val FLAP_VELOCITY = -8.5f
        private const val PIPE_SPEED = 2.6f
        private const val PIPE_WIDTH = 100f
        private const val PIPE_GAP = 320f
        private const val PIPE_SPAWN_INTERVAL = 170
        private const val BIRD_SIZE = 32f
        // Easier flap: any rapid upward wrist motion at any height fires.
        // Holding arms above the shoulders to flap was too tiring.
        private const val FLAP_VELOCITY_THRESHOLD = 0.028f   // normalized Y delta per frame
    }

    private lateinit var surfaceView: SurfaceView
    private var gameThread: GameThread? = null
    private val motion = TezeractMotion

    // Game state
    private var birdY = 0f
    private var birdVelocity = 0f
    private var score = 0
    private var gameOver = false
    private var gameStarted = false
    private val pipes = mutableListOf<Pipe>()
    private var framesSinceLastPipe = 0
    private var screenWidth = 0f
    private var screenHeight = 0f

    // Flap detection
    private var lastLeftWristY = -1f
    private var lastRightWristY = -1f
    private var flapCooldown = 0

    // Latest body for HUD rendering (writes happen on binder thread, reads
    // on game thread — Volatile is enough since we only read references).
    @Volatile private var latestBody: BodyPose? = null

    /**
     * Cross-arms (BUTTON_Y in the default binding) exits the game and
     * returns to the launcher. We explicitly fire a HOME intent — calling
     * `finish()` on its own can leave the user on Android's default home
     * (or task history) rather than the Tezeract launcher.
     */
    private val exitListener = InputListener { event ->
        if (event.action == InputAction.PRESS && event.input == TezeractInput.BUTTON_Y) {
            runOnUiThread {
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(home)
                finish()
            }
        }
    }

    data class Pipe(var x: Float, val gapTop: Float, var scored: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        motion.initialize(this)
        motion.addMotionListener(this)
        motion.addInputListener(exitListener)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                screenWidth = surfaceView.width.toFloat()
                screenHeight = surfaceView.height.toFloat()
                resetGame()
                gameThread = GameThread(holder).also { it.start() }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                screenWidth = width.toFloat()
                screenHeight = height.toFloat()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                gameThread?.running = false
                gameThread?.join()
                gameThread = null
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        motion.removeMotionListener(this)
        motion.removeInputListener(exitListener)
        motion.release()
    }

    private fun resetGame() {
        birdY = screenHeight / 2f
        birdVelocity = 0f
        score = 0
        gameOver = false
        gameStarted = false
        pipes.clear()
        framesSinceLastPipe = PIPE_SPAWN_INTERVAL - 30  // first pipe comes quickly
        flapCooldown = 0
    }

    // ── Motion callbacks ──

    override fun onMotionFrame(frame: MotionFrame) {
        if (frame.bodies.isEmpty()) return
        val body = frame.bodies[0]
        latestBody = body
        val keypoints = body.keypoints

        val leftWrist = keypoints.firstOrNull { it.index == Keypoint.LEFT_WRIST }
        val rightWrist = keypoints.firstOrNull { it.index == Keypoint.RIGHT_WRIST }
        if (leftWrist == null || rightWrist == null) return

        // Easier flap: both wrists moving up rapidly (Y decreasing) — at any
        // height. The previous "wrists above shoulders" gate made the gesture
        // exhausting; this lets you flap with arms in front of you, like a
        // bird, anywhere in the frame.
        val leftMovingUp = lastLeftWristY > 0 && (lastLeftWristY - leftWrist.y) > FLAP_VELOCITY_THRESHOLD
        val rightMovingUp = lastRightWristY > 0 && (lastRightWristY - rightWrist.y) > FLAP_VELOCITY_THRESHOLD

        if (leftMovingUp && rightMovingUp && flapCooldown <= 0) {
            flap()
            flapCooldown = 8
        }

        lastLeftWristY = leftWrist.y
        lastRightWristY = rightWrist.y
    }

    private fun flap() {
        if (gameOver) {
            resetGame()
            return
        }
        gameStarted = true
        birdVelocity = FLAP_VELOCITY
    }

    // ── Game loop ──

    private inner class GameThread(private val holder: SurfaceHolder) : Thread("FlappyGameThread") {
        var running = true
        private val targetFrameTime = 1000L / 60  // 60fps

        private val birdPaint = Paint().apply { color = Color.rgb(255, 200, 50); isAntiAlias = true }
        private val pipePaint = Paint().apply { color = Color.rgb(80, 200, 80) }
        private val bgPaint = Paint().apply { color = Color.rgb(20, 20, 40) }
        private val textPaint = Paint().apply {
            color = Color.WHITE; textSize = 64f; textAlign = Paint.Align.CENTER; isAntiAlias = true
        }
        private val smallTextPaint = Paint().apply {
            color = Color.rgb(180, 180, 180); textSize = 32f; textAlign = Paint.Align.CENTER; isAntiAlias = true
        }
        private val scorePaint = Paint().apply {
            color = Color.WHITE; textSize = 80f; textAlign = Paint.Align.CENTER
            isAntiAlias = true; isFakeBoldText = true
        }
        private val groundPaint = Paint().apply { color = Color.rgb(60, 40, 20) }

        override fun run() {
            while (running) {
                val startTime = System.currentTimeMillis()

                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        synchronized(holder) {
                            update()
                            draw(canvas)
                        }
                    }
                } finally {
                    canvas?.let {
                        try { holder.unlockCanvasAndPost(it) } catch (_: Exception) { }
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                val sleepTime = targetFrameTime - elapsed
                if (sleepTime > 0) {
                    try { sleep(sleepTime) } catch (_: InterruptedException) { }
                }

                if (flapCooldown > 0) flapCooldown--
            }
        }

        private fun update() {
            if (!gameStarted || gameOver) return

            // Bird physics
            birdVelocity += GRAVITY
            birdY += birdVelocity

            // Ground / ceiling collision
            val groundY = screenHeight - 60f
            if (birdY > groundY - BIRD_SIZE || birdY < BIRD_SIZE) {
                gameOver = true
                return
            }

            // Spawn pipes
            framesSinceLastPipe++
            if (framesSinceLastPipe >= PIPE_SPAWN_INTERVAL) {
                val minGapTop = 100f
                val maxGapTop = groundY - PIPE_GAP - 100f
                val gapTop = minGapTop + (Math.random() * (maxGapTop - minGapTop)).toFloat()
                pipes.add(Pipe(x = screenWidth, gapTop = gapTop))
                framesSinceLastPipe = 0
            }

            // Move + check pipes
            val birdLeft = screenWidth * 0.2f - BIRD_SIZE
            val birdRight = screenWidth * 0.2f + BIRD_SIZE
            val birdTop = birdY - BIRD_SIZE
            val birdBottom = birdY + BIRD_SIZE

            val iterator = pipes.iterator()
            while (iterator.hasNext()) {
                val pipe = iterator.next()
                pipe.x -= PIPE_SPEED

                // Remove off-screen pipes
                if (pipe.x + PIPE_WIDTH < 0) {
                    iterator.remove()
                    continue
                }

                // Score when bird passes pipe center
                if (!pipe.scored && pipe.x + PIPE_WIDTH < screenWidth * 0.2f) {
                    score++
                    pipe.scored = true
                }

                // Collision detection
                if (birdRight > pipe.x && birdLeft < pipe.x + PIPE_WIDTH) {
                    // Check if bird is in the gap
                    if (birdTop < pipe.gapTop || birdBottom > pipe.gapTop + PIPE_GAP) {
                        gameOver = true
                        return
                    }
                }
            }
        }

        private fun draw(canvas: Canvas) {
            // Background
            canvas.drawRect(0f, 0f, screenWidth, screenHeight, bgPaint)

            // Ground
            val groundY = screenHeight - 60f
            canvas.drawRect(0f, groundY, screenWidth, screenHeight, groundPaint)

            // Pipes
            for (pipe in pipes) {
                // Top pipe
                canvas.drawRect(pipe.x, 0f, pipe.x + PIPE_WIDTH, pipe.gapTop, pipePaint)
                // Bottom pipe
                canvas.drawRect(pipe.x, pipe.gapTop + PIPE_GAP, pipe.x + PIPE_WIDTH, groundY, pipePaint)

                // Pipe caps (slightly wider)
                val capHeight = 30f
                val capExtra = 10f
                canvas.drawRect(
                    pipe.x - capExtra, pipe.gapTop - capHeight,
                    pipe.x + PIPE_WIDTH + capExtra, pipe.gapTop,
                    pipePaint
                )
                canvas.drawRect(
                    pipe.x - capExtra, pipe.gapTop + PIPE_GAP,
                    pipe.x + PIPE_WIDTH + capExtra, pipe.gapTop + PIPE_GAP + capHeight,
                    pipePaint
                )
            }

            // Bird (yellow circle with wing)
            val birdX = screenWidth * 0.2f
            canvas.drawCircle(birdX, birdY, BIRD_SIZE, birdPaint)
            // Eye
            val eyePaint = Paint().apply { color = Color.BLACK; isAntiAlias = true }
            canvas.drawCircle(birdX + 15f, birdY - 12f, 8f, eyePaint)
            // Beak
            val beakPaint = Paint().apply { color = Color.rgb(255, 120, 50); isAntiAlias = true }
            canvas.drawRect(birdX + 30f, birdY - 5f, birdX + 55f, birdY + 8f, beakPaint)

            // Score
            if (gameStarted) {
                canvas.drawText(score.toString(), screenWidth / 2f, 100f, scorePaint)
            }

            // Start screen
            if (!gameStarted && !gameOver) {
                canvas.drawText("FLAPPY ARMS", screenWidth / 2f, screenHeight / 2f - 60f, textPaint)
                canvas.drawText("Flap both arms together to fly!", screenWidth / 2f, screenHeight / 2f + 10f, smallTextPaint)
                canvas.drawText("Move them up quickly — any height works", screenWidth / 2f, screenHeight / 2f + 55f, smallTextPaint)
            }

            // Game over
            if (gameOver) {
                canvas.drawText("GAME OVER", screenWidth / 2f, screenHeight / 2f - 60f, textPaint)
                canvas.drawText("Score: $score", screenWidth / 2f, screenHeight / 2f + 10f, textPaint)
                canvas.drawText("Flap arms to restart", screenWidth / 2f, screenHeight / 2f + 65f, smallTextPaint)
            }

            drawHUD(canvas)
        }

        // ── Bird-themed motion HUD ───────────────────────────────────────
        private val hudBgPaint = Paint().apply { color = Color.argb(220, 16, 6, 32); isAntiAlias = true }
        private val hudBorderPaint = Paint().apply {
            color = Color.rgb(255, 200, 50); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true
        }
        private val hudGuidePaint = Paint().apply {
            color = Color.argb(60, 255, 200, 50); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
        }
        private val hudBonePaint = Paint().apply {
            color = Color.rgb(255, 200, 50); style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true
        }
        private val hudWingPaint = Paint().apply {
            color = Color.rgb(255, 240, 120); style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true
        }
        private val hudPointPaint = Paint().apply { color = Color.rgb(255, 240, 120); style = Paint.Style.FILL; isAntiAlias = true }
        private val hudTextPaint = Paint().apply { color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.LEFT; isAntiAlias = true }
        private val hudHintPaint = Paint().apply {
            color = Color.rgb(180, 220, 255); textSize = 14f; textAlign = Paint.Align.CENTER; isAntiAlias = true
        }
        private val skeletonBones = listOf(
            Keypoint.LEFT_SHOULDER to Keypoint.RIGHT_SHOULDER,
            Keypoint.LEFT_SHOULDER to Keypoint.LEFT_HIP,
            Keypoint.RIGHT_SHOULDER to Keypoint.RIGHT_HIP,
            Keypoint.LEFT_HIP to Keypoint.RIGHT_HIP,
        )
        private val wingBones = listOf(
            Keypoint.LEFT_SHOULDER to Keypoint.LEFT_ELBOW,
            Keypoint.LEFT_ELBOW to Keypoint.LEFT_WRIST,
            Keypoint.RIGHT_SHOULDER to Keypoint.RIGHT_ELBOW,
            Keypoint.RIGHT_ELBOW to Keypoint.RIGHT_WRIST,
        )

        private fun drawHUD(canvas: Canvas) {
            // Top-right HUD: a viewport showing the live skeleton (arms drawn
            // as bright "wings"), a faded reference T-pose to guide the user
            // into a good standing position, and a "FLAP!" hint text.
            val pad = 16f
            val w = 240f
            val h = 180f
            val left = screenWidth - w - pad
            val top = pad
            val right = left + w
            val bottom = top + h
            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, hudBgPaint)
            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, hudBorderPaint)

            // Inner viewport.
            val vpInset = 8f
            val vL = left + vpInset; val vT = top + vpInset + 18f
            val vR = right - vpInset; val vB = bottom - vpInset - 22f
            val vW = vR - vL; val vH = vB - vT

            // Title.
            hudTextPaint.textSize = 14f
            canvas.drawText("YOU", left + 10f, top + 18f, hudTextPaint)

            val body = latestBody
            // Faded reference silhouette (T-pose, arms out) — user aligns to it.
            drawReferenceSilhouette(canvas, vL, vT, vW, vH)

            if (body == null || body.keypoints.size < 33) {
                hudHintPaint.color = Color.rgb(255, 200, 50)
                canvas.drawText("step into the frame",
                    left + w / 2f, bottom - 6f, hudHintPaint)
                return
            }

            // Live skeleton — torso in muted gold, arms ("wings") in bright yellow.
            val kp = body.keypoints
            for ((a, b) in skeletonBones) drawBone(canvas, kp, a, b, vL, vT, vW, vH, hudBonePaint)
            for ((a, b) in wingBones) drawBone(canvas, kp, a, b, vL, vT, vW, vH, hudWingPaint)
            for (i in listOf(Keypoint.LEFT_WRIST, Keypoint.RIGHT_WRIST,
                              Keypoint.LEFT_SHOULDER, Keypoint.RIGHT_SHOULDER)) {
                val k = kp.getOrNull(i) ?: continue
                if (k.confidence < 0.3f) continue
                canvas.drawCircle(vL + k.x * vW, vT + k.y * vH, 4f, hudPointPaint)
            }

            // Footer hint changes with state.
            val hint = when {
                gameOver -> "flap to restart"
                !gameStarted -> "flap both arms to start"
                else -> "FLAP!"
            }
            canvas.drawText(hint, left + w / 2f, bottom - 6f, hudHintPaint)
        }

        private fun drawReferenceSilhouette(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
            // A faded T-pose to teach framing: head at top, arms outstretched
            // horizontally, torso + legs centered. Lines = good standing position.
            val cx = x + w / 2f
            val headR = w * 0.06f
            val headCY = y + h * 0.18f
            canvas.drawCircle(cx, headCY, headR, hudGuidePaint)
            val shoulderY = y + h * 0.32f
            val hipY = y + h * 0.62f
            canvas.drawLine(cx, shoulderY, cx, hipY, hudGuidePaint)              // torso
            canvas.drawLine(x + w * 0.08f, shoulderY, x + w * 0.92f, shoulderY, hudGuidePaint) // arms
            canvas.drawLine(cx, hipY, x + w * 0.30f, y + h * 0.92f, hudGuidePaint) // left leg
            canvas.drawLine(cx, hipY, x + w * 0.70f, y + h * 0.92f, hudGuidePaint) // right leg
        }

        private fun drawBone(canvas: Canvas, kp: List<Keypoint>,
                              a: Int, b: Int,
                              x: Float, y: Float, w: Float, h: Float,
                              paint: Paint) {
            val ka = kp.getOrNull(a) ?: return
            val kb = kp.getOrNull(b) ?: return
            if (ka.confidence < 0.3f || kb.confidence < 0.3f) return
            canvas.drawLine(
                x + ka.x * w, y + ka.y * h,
                x + kb.x * w, y + kb.y * h,
                paint
            )
        }
    }
}
