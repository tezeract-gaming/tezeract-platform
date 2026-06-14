package com.tezeract.shadowbox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as drawRotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tezeract.motion.MotionFrame
import com.tezeract.sdk.MotionListener
import com.tezeract.sdk.TezeractMotion
import com.tezeract.shadowbox.motion.PunchDetector
import com.tezeract.shadowbox.motion.PunchKind
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val MAX_HP = 100
private const val OPPONENT_PUNCH_DAMAGE = 15
private const val PLAYER_PUNCH_DAMAGE = 12
private const val OPPONENT_TELEGRAPH_MS = 900L
private const val OPPONENT_REST_MS_START = 1200L
private const val OPPONENT_REST_MS_FLOOR = 600L

// Player-glove animation timing (ms from punch detection).
private const val GLOVE_EXTEND_MS = 120L
private const val GLOVE_HOLD_MS = 80L
private const val GLOVE_RETURN_MS = 200L
private const val GLOVE_TOTAL_MS = GLOVE_EXTEND_MS + GLOVE_HOLD_MS + GLOVE_RETURN_MS

private enum class OpponentState { Idle, Telegraphing, Striking, KO }
private enum class StrikeKind { LEFT, RIGHT, OVERHEAD }
private enum class GlovePose { Guard, Jab, Hook, Uppercut }

private data class GloveAnim(val pose: GlovePose, val startedAt: Long)
private data class Callout(
    val id: Long,
    val text: String,
    val xFrac: Float,    // 0..1 of screen width
    val yFrac: Float,    // 0..1 of screen height
    val createdAt: Long,
    val color: Color,
)

private val POW_WORDS = listOf("POW!", "BAM!", "SOCK!", "BOOM!", "WHAM!", "CRACK!")

@Composable
fun FightModeScreen(onExit: () -> Unit) {
    val detector = remember { PunchDetector() }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var sessionStart by remember { mutableStateOf(System.currentTimeMillis()) }

    var playerHp by remember { mutableStateOf(MAX_HP) }
    var opponentHp by remember { mutableStateOf(MAX_HP) }
    var combo by remember { mutableStateOf(0) }
    var lastBlockedAt by remember { mutableStateOf(0L) }
    var lastPlayerHitAt by remember { mutableStateOf(0L) }
    var lastOpponentHitAt by remember { mutableStateOf(0L) }
    val callouts = remember { mutableStateListOf<Callout>() }
    var nextCalloutId by remember { mutableStateOf(1L) }

    // Per-side glove state — independent left/right animations.
    var leftGlove by remember { mutableStateOf<GloveAnim?>(null) }
    var rightGlove by remember { mutableStateOf<GloveAnim?>(null) }

    var oppState by remember { mutableStateOf(OpponentState.Idle) }
    var oppStateChangedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var oppStrikeKind by remember { mutableStateOf(StrikeKind.LEFT) }
    var nextActionAt by remember { mutableStateOf(System.currentTimeMillis() + 1500) }
    var koAt by remember { mutableStateOf(0L) }

    val gameOver = playerHp <= 0 || opponentHp <= 0
    val playerWon = opponentHp <= 0

    // After a K.O. (either side), hold the result screen for 5s then return
    // to the mode picker automatically. The "triangle gesture exits" hint
    // still works for users who want to leave faster.
    LaunchedEffect(gameOver) {
        if (gameOver) {
            delay(5_000)
            onExit()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : MotionListener {
            override fun onMotionFrame(frame: MotionFrame) {
                if (gameOver) return
                val now = System.currentTimeMillis()
                val punches = detector.classify(frame, now)
                if (punches.isEmpty()) return
                for (p in punches) {
                    when (p.kind) {
                        PunchKind.BLOCK -> { lastBlockedAt = now }
                        PunchKind.LEFT_JAB -> {
                            leftGlove = GloveAnim(GlovePose.Jab, now)
                            opponentHp = (opponentHp - PLAYER_PUNCH_DAMAGE).coerceAtLeast(0)
                            combo += 1; lastOpponentHitAt = now
                            if (opponentHp <= 0 && koAt == 0L) {
                                koAt = now; oppState = OpponentState.KO
                            }
                            nextCalloutId = pushPow(now, callouts, nextCalloutId, side = -1)
                        }
                        PunchKind.RIGHT_JAB -> {
                            rightGlove = GloveAnim(GlovePose.Jab, now)
                            opponentHp = (opponentHp - PLAYER_PUNCH_DAMAGE).coerceAtLeast(0)
                            combo += 1; lastOpponentHitAt = now
                            if (opponentHp <= 0 && koAt == 0L) {
                                koAt = now; oppState = OpponentState.KO
                            }
                            nextCalloutId = pushPow(now, callouts, nextCalloutId, side = +1)
                        }
                        PunchKind.LEFT_HOOK -> {
                            leftGlove = GloveAnim(GlovePose.Hook, now)
                            opponentHp = (opponentHp - PLAYER_PUNCH_DAMAGE).coerceAtLeast(0)
                            combo += 1; lastOpponentHitAt = now
                            if (opponentHp <= 0 && koAt == 0L) {
                                koAt = now; oppState = OpponentState.KO
                            }
                            nextCalloutId = pushPow(now, callouts, nextCalloutId, side = -1)
                        }
                        PunchKind.RIGHT_HOOK -> {
                            rightGlove = GloveAnim(GlovePose.Hook, now)
                            opponentHp = (opponentHp - PLAYER_PUNCH_DAMAGE).coerceAtLeast(0)
                            combo += 1; lastOpponentHitAt = now
                            if (opponentHp <= 0 && koAt == 0L) {
                                koAt = now; oppState = OpponentState.KO
                            }
                            nextCalloutId = pushPow(now, callouts, nextCalloutId, side = +1)
                        }
                        PunchKind.LEFT_UPPERCUT -> {
                            leftGlove = GloveAnim(GlovePose.Uppercut, now)
                            opponentHp = (opponentHp - PLAYER_PUNCH_DAMAGE).coerceAtLeast(0)
                            combo += 1; lastOpponentHitAt = now
                            if (opponentHp <= 0 && koAt == 0L) {
                                koAt = now; oppState = OpponentState.KO
                            }
                            nextCalloutId = pushPow(now, callouts, nextCalloutId, side = -1)
                        }
                        PunchKind.RIGHT_UPPERCUT -> {
                            rightGlove = GloveAnim(GlovePose.Uppercut, now)
                            opponentHp = (opponentHp - PLAYER_PUNCH_DAMAGE).coerceAtLeast(0)
                            combo += 1; lastOpponentHitAt = now
                            if (opponentHp <= 0 && koAt == 0L) {
                                koAt = now; oppState = OpponentState.KO
                            }
                            nextCalloutId = pushPow(now, callouts, nextCalloutId, side = +1)
                        }
                    }
                }
            }
        }
        TezeractMotion.addMotionListener(listener)
        onDispose { TezeractMotion.removeMotionListener(listener) }
    }

    // Opponent AI loop.
    LaunchedEffect(Unit) {
        sessionStart = System.currentTimeMillis()
        while (true) {
            delay(40)
            val now = System.currentTimeMillis()
            clock = now

            // Expire glove animations once they've finished their cycle.
            leftGlove?.let { if (now - it.startedAt > GLOVE_TOTAL_MS) leftGlove = null }
            rightGlove?.let { if (now - it.startedAt > GLOVE_TOTAL_MS) rightGlove = null }
            // Fade callouts out after 700ms.
            callouts.removeAll { now - it.createdAt > 700 }

            if (gameOver) continue

            when (oppState) {
                OpponentState.Idle -> {
                    if (now >= nextActionAt) {
                        oppStrikeKind = StrikeKind.entries.random()
                        oppState = OpponentState.Telegraphing
                        oppStateChangedAt = now
                    }
                }
                OpponentState.Telegraphing -> {
                    if (now - oppStateChangedAt > OPPONENT_TELEGRAPH_MS) {
                        oppState = OpponentState.Striking
                        oppStateChangedAt = now
                        val recentBlock = now - lastBlockedAt < 600
                        if (recentBlock) {
                            callouts.add(Callout(nextCalloutId++, "BLOCKED!",
                                xFrac = 0.5f, yFrac = 0.40f,
                                createdAt = now, color = BoxColors.Cool))
                            combo += 1
                        } else {
                            playerHp = (playerHp - OPPONENT_PUNCH_DAMAGE).coerceAtLeast(0)
                            lastPlayerHitAt = now
                            combo = 0
                            if (playerHp <= 0 && koAt == 0L) koAt = now
                        }
                    }
                }
                OpponentState.Striking -> {
                    if (now - oppStateChangedAt > 220) {
                        oppState = OpponentState.Idle
                        val elapsed = now - sessionStart
                        val progress = (elapsed.toFloat() / 60_000f).coerceIn(0f, 1f)
                        val rest = (OPPONENT_REST_MS_START -
                            (OPPONENT_REST_MS_START - OPPONENT_REST_MS_FLOOR) * progress).toLong()
                        nextActionAt = now + rest + Random.nextLong(0, 400)
                    }
                }
                OpponentState.KO -> {
                    // Stay down.
                }
            }
        }
    }

    val opponentBob by animateFloatAsState(
        targetValue = if (oppState == OpponentState.Telegraphing) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "opp-bob"
    )
    val playerHurtFlash by animateFloatAsState(
        targetValue = if (clock - lastPlayerHitAt < 400) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "player-hurt"
    )

    Box(modifier = Modifier
        .fillMaxSize()
        .background(BoxColors.Background)
    ) {
        // === Background: ring + crowd ===
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArenaBackground()
        }

        // === Opponent figure ===
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val cx = w / 2f
            // baseY tuned so head top stays under HUD bar (HUD ~ h*0.18) and
            // torso bottom stays above the ring ropes (~ h*0.62).
            val baseY = h * 0.45f
            val bob = (sin(clock * 0.005) * 5).toFloat()
            val telegraphSway = if (oppState == OpponentState.Telegraphing)
                ((clock - oppStateChangedAt).toFloat() / OPPONENT_TELEGRAPH_MS) * 28f else 0f
            val struckOffset = if (oppState == OpponentState.Striking)
                (1f - (clock - oppStateChangedAt).toFloat() / 220f).coerceIn(0f, 1f) * 80f else 0f
            val cyOffset = -opponentBob * 12f + bob

            val koProgress = if (oppState == OpponentState.KO && koAt > 0L)
                ((clock - koAt).toFloat() / 1200f).coerceIn(0f, 1f) else 0f

            val drawCx = cx + when (oppStrikeKind) {
                StrikeKind.LEFT -> -telegraphSway - struckOffset * 0.5f
                StrikeKind.RIGHT -> telegraphSway + struckOffset * 0.5f
                StrikeKind.OVERHEAD -> 0f
            }
            val drawCy = baseY + cyOffset - if (oppStrikeKind == StrikeKind.OVERHEAD) struckOffset * 0.3f else 0f

            drawRotate(degrees = koProgress * 90f, pivot = Offset(drawCx, drawCy + 200f)) {
                drawArcadeOpponent(
                    cx = drawCx,
                    cy = drawCy + koProgress * 180f,
                    hpFraction = opponentHp.toFloat() / MAX_HP,
                    state = oppState,
                    strikeKind = oppStrikeKind,
                    lastHitAgo = (clock - lastOpponentHitAt).coerceAtLeast(0).toFloat(),
                )
            }
        }

        // === Player gloves at bottom corners ===
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val now = clock
            val bob = (sin(now * 0.006) * 4f).toFloat()
            drawPlayerGlove(this, side = -1, anim = leftGlove, w = w, h = h, now = now, bob = bob)
            drawPlayerGlove(this, side = +1, anim = rightGlove, w = w, h = h, now = now, bob = bob)
        }

        // === Hurt vignette ===
        if (playerHurtFlash > 0f) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(BoxColors.DangerRed.copy(alpha = 0.22f * playerHurtFlash))
            )
        }

        // === Top arcade HUD bar ===
        ArcadeHUD(
            playerHp = playerHp,
            opponentHp = opponentHp,
            combo = combo,
            elapsedMs = clock - sessionStart,
        )

        // === Telegraph warning ===
        if (oppState == OpponentState.Telegraphing) {
            val timeLeftFraction = 1f - ((clock - oppStateChangedAt).toFloat() / OPPONENT_TELEGRAPH_MS)
                .coerceIn(0f, 1f)
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 110.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArcadeWarning(
                    text = when (oppStrikeKind) {
                        StrikeKind.LEFT -> "DODGE RIGHT!"
                        StrikeKind.RIGHT -> "DODGE LEFT!"
                        StrikeKind.OVERHEAD -> "BLOCK!"
                    }
                )
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier
                    .fillMaxWidth(0.40f).height(6.dp)
                    .background(Color.Black, RoundedCornerShape(3.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(3.dp))) {
                    Box(modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(timeLeftFraction)
                        .background(BoxColors.ArcadeSparkle, RoundedCornerShape(3.dp)))
                }
            }
        }

        // === Hit callouts ===
        for (c in callouts) {
            CalloutText(c, clock)
        }

        // === KO sequence ===
        if (gameOver) {
            val koAge = if (koAt > 0L) (clock - koAt).toFloat() else 0f
            val flashAlpha = (1f - koAge / 350f).coerceIn(0f, 1f)
            // White flash
            if (flashAlpha > 0f) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flashAlpha))
                )
            }
            val showKo = koAge > 250f
            if (showKo) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val pulse = 1f + 0.06f * sin(clock * 0.012f).toFloat()
                    Text(
                        text = if (playerWon) "K.O.!" else "DOWN!",
                        color = if (playerWon) BoxColors.ArcadeSparkle else BoxColors.DangerRed,
                        fontSize = (148.sp),
                        fontWeight = FontWeight.Black,
                        letterSpacing = 6.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.scale(pulse)
                    )
                    Text(
                        text = if (playerWon) "★ ★ ★ NEW CHAMPION ★ ★ ★" else "rival took the round",
                        color = BoxColors.OnDarkMuted, fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(28.dp))
                    Text("triangle gesture exits", color = BoxColors.OnDarkMuted,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // === Bottom helper ===
        Text(
            text = "block (hands at face) · counter with any punch · triangle to exit",
            color = BoxColors.OnDarkMuted, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
        )

        // Reusable HUD viewport (skeleton mirror so the player knows the camera sees them).
        BoxHUD(modifier = Modifier
            .align(AbsoluteAlignment.TopRight)
            .padding(top = 88.dp, end = 12.dp))
    }
}

private fun pushPow(
    now: Long,
    callouts: MutableList<Callout>,
    nextId: Long,
    side: Int,
): Long {
    val xf = if (side < 0) 0.30f + Random.nextFloat() * 0.10f else 0.60f + Random.nextFloat() * 0.10f
    val yf = 0.34f + Random.nextFloat() * 0.10f
    val text = POW_WORDS.random()
    callouts.add(Callout(nextId, text, xf, yf, now, BoxColors.ArcadeSparkle))
    return nextId + 1
}

// ===================== Drawing =====================

private fun DrawScope.drawArenaBackground() {
    val w = size.width; val h = size.height
    // Dark gradient sky behind opponent (keeps hero figure pop)
    val ringFloorTop = h * 0.62f

    // Crowd silhouette at the very back — wave fill behind the ring
    val crowdTop = h * 0.30f
    val crowdPath = Path().apply {
        moveTo(0f, crowdTop)
        var x = 0f
        while (x <= w) {
            val y = crowdTop + (sin(x * 0.04f) * 10f) + (cos(x * 0.012f) * 6f)
            lineTo(x, y)
            x += 6f
        }
        lineTo(w, ringFloorTop)
        lineTo(0f, ringFloorTop)
        close()
    }
    drawPath(crowdPath, BoxColors.ArcadeCrowd)

    // Crowd specks (highlights on heads in the dark mass)
    repeat(140) { i ->
        val cx = (i * 91 % w.toInt()).toFloat()
        val cy = crowdTop + ((i * 53) % 90).toFloat() + 4f
        if (cy < ringFloorTop) {
            drawCircle(Color(0xFF2A1F36), 3f, Offset(cx, cy))
        }
    }

    // Ring floor (canvas mat) — perspective-tilted trapezoid
    val floorPath = Path().apply {
        moveTo(w * 0.06f, ringFloorTop)
        lineTo(w * 0.94f, ringFloorTop)
        lineTo(w + 60f, h)
        lineTo(-60f, h)
        close()
    }
    drawPath(floorPath, BoxColors.ArcadeRingFloor)

    // Floor lines (perspective)
    val floorLine = Color(0xFF5C4327)
    for (i in 1..3) {
        val frac = i / 4f
        val y = ringFloorTop + (h - ringFloorTop) * frac
        drawLine(floorLine, Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
    }

    // Corner posts (left + right edges, capped with red turnbuckle pads)
    val postW = w * 0.05f
    drawRoundRect(
        BoxColors.ArcadeCorner,
        topLeft = Offset(-2f, h * 0.20f),
        size = Size(postW, h - h * 0.20f),
        cornerRadius = CornerRadius(8f, 8f)
    )
    drawRoundRect(
        BoxColors.ArcadeCorner,
        topLeft = Offset(w - postW + 2f, h * 0.20f),
        size = Size(postW, h - h * 0.20f),
        cornerRadius = CornerRadius(8f, 8f)
    )
    // Turnbuckle pads
    val padY = ringFloorTop - 18f
    drawRoundRect(
        BoxColors.ArcadeSinglet,
        topLeft = Offset(-2f, padY),
        size = Size(postW + 4f, 36f),
        cornerRadius = CornerRadius(6f, 6f)
    )
    drawRoundRect(
        BoxColors.ArcadeSinglet,
        topLeft = Offset(w - postW - 2f, padY),
        size = Size(postW + 4f, 36f),
        cornerRadius = CornerRadius(6f, 6f)
    )

    // Ring ropes — three horizontal yellow-ish bars across the front
    val ropeColor = BoxColors.ArcadeRopeYellow
    val ropeShadow = Color(0xFF8C7530)
    for (i in 0..2) {
        val ropeY = ringFloorTop + 6f + i * 38f
        drawLine(ropeShadow, Offset(0f, ropeY + 3f), Offset(w, ropeY + 3f), strokeWidth = 6f)
        drawLine(ropeColor, Offset(0f, ropeY), Offset(w, ropeY), strokeWidth = 6f)
    }
}

private fun DrawScope.drawArcadeOpponent(
    cx: Float, cy: Float,
    hpFraction: Float,
    state: OpponentState, strikeKind: StrikeKind,
    lastHitAgo: Float,
) {
    val hitTint = (1f - (lastHitAgo / 250f).coerceIn(0f, 1f))
    val skin = if (hitTint > 0f)
        lerp(BoxColors.ArcadeSkin, Color.White, hitTint * 0.7f)
    else BoxColors.ArcadeSkin
    val singletColor = lerp(
        BoxColors.ArcadeSinglet,
        Color(0xFF7B1818),
        1f - hpFraction.coerceIn(0f, 1f)
    )

    val headR = 80f

    // Torso (singlet) — chunky rounded rectangle
    val bodyW = 280f
    val bodyH = 220f
    val bodyTop = cy - 30f
    drawRoundRect(
        singletColor,
        topLeft = Offset(cx - bodyW / 2f, bodyTop),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(40f, 40f)
    )
    // Singlet straps (white V across shoulders)
    drawLine(Color.White, Offset(cx - 40f, bodyTop + 8f), Offset(cx - 80f, bodyTop + 80f), strokeWidth = 14f)
    drawLine(Color.White, Offset(cx + 40f, bodyTop + 8f), Offset(cx + 80f, bodyTop + 80f), strokeWidth = 14f)

    // Head
    drawCircle(skin, headR, Offset(cx, cy - 110f))
    // Hair (flat top)
    val hairColor = Color(0xFF332014)
    val hairPath = Path().apply {
        moveTo(cx - headR * 0.95f, cy - 110f - headR * 0.2f)
        lineTo(cx - headR * 0.85f, cy - 110f - headR * 0.95f)
        lineTo(cx + headR * 0.85f, cy - 110f - headR * 0.95f)
        lineTo(cx + headR * 0.95f, cy - 110f - headR * 0.2f)
        close()
    }
    drawPath(hairPath, hairColor)

    // Chin shadow
    drawArc(
        color = BoxColors.ArcadeShadow.copy(alpha = 0.5f),
        startAngle = 30f, sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(cx - headR * 0.85f, cy - 110f - headR * 0.4f),
        size = Size(headR * 1.7f, headR * 1.6f),
        style = Stroke(width = 6f)
    )
    // Eyes (chunky black squares)
    val eyeY = cy - 120f
    drawRect(Color.Black, Offset(cx - 30f, eyeY - 8f), Size(16f, 12f))
    drawRect(Color.Black, Offset(cx + 14f, eyeY - 8f), Size(16f, 12f))
    // Eye whites (small dots inside)
    drawRect(Color.White, Offset(cx - 26f, eyeY - 6f), Size(4f, 4f))
    drawRect(Color.White, Offset(cx + 18f, eyeY - 6f), Size(4f, 4f))
    // Mouth — frown if hurt, neutral otherwise
    if (lastHitAgo < 600f) {
        drawArc(
            color = Color(0xFF6A2C1A),
            startAngle = 200f, sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(cx - 26f, cy - 88f),
            size = Size(52f, 24f),
            style = Stroke(width = 5f)
        )
    } else {
        drawLine(Color(0xFF6A2C1A), Offset(cx - 22f, cy - 75f),
            Offset(cx + 22f, cy - 75f), strokeWidth = 5f)
    }

    // Belt (gold band at waist)
    drawRect(
        BoxColors.ArcadeSparkle,
        topLeft = Offset(cx - bodyW / 2f, bodyTop + bodyH - 24f),
        size = Size(bodyW, 14f)
    )

    // Opponent gloves
    val (lgX, lgY, rgX, rgY, gloveScale) = opponentGloveOffsets(state, strikeKind, cx, cy)
    drawOpponentGlove(this, lgX, lgY, gloveScale)
    drawOpponentGlove(this, rgX, rgY, gloveScale)
}

private data class GlovePos(val lx: Float, val ly: Float, val rx: Float, val ry: Float, val scale: Float)

private fun opponentGloveOffsets(state: OpponentState, kind: StrikeKind, cx: Float, cy: Float): GlovePos {
    val guardLY = cy + 40f; val guardRY = cy + 40f
    val guardLX = cx - 130f; val guardRX = cx + 130f
    return when (state) {
        OpponentState.Idle -> GlovePos(guardLX, guardLY, guardRX, guardRY, 1f)
        OpponentState.Telegraphing -> when (kind) {
            StrikeKind.LEFT -> GlovePos(guardLX - 30f, guardLY + 10f, guardRX, guardRY, 1.15f)
            StrikeKind.RIGHT -> GlovePos(guardLX, guardLY, guardRX + 30f, guardRY + 10f, 1.15f)
            StrikeKind.OVERHEAD -> GlovePos(guardLX + 30f, guardLY - 80f, guardRX - 30f, guardRY - 80f, 1.15f)
        }
        OpponentState.Striking -> when (kind) {
            StrikeKind.LEFT -> GlovePos(cx - 260f, cy + 90f, guardRX, guardRY, 1.4f)
            StrikeKind.RIGHT -> GlovePos(guardLX, guardLY, cx + 260f, cy + 90f, 1.4f)
            StrikeKind.OVERHEAD -> GlovePos(cx - 50f, cy - 220f, cx + 50f, cy - 220f, 1.4f)
        }
        OpponentState.KO -> GlovePos(guardLX - 60f, guardLY + 80f, guardRX + 60f, guardRY + 80f, 1f)
    }
}

private fun drawOpponentGlove(scope: DrawScope, x: Float, y: Float, scale: Float) {
    with(scope) {
        val r = 38f * scale
        drawCircle(Color.Black.copy(alpha = 0.4f), r * 1.05f, Offset(x + 4f, y + 6f))
        drawCircle(BoxColors.Primary, r, Offset(x, y))
        drawCircle(Color.Black, r, Offset(x, y), style = Stroke(width = 3f))
        // Lace highlight
        drawLine(BoxColors.ArcadeGloveLace, Offset(x - r * 0.4f, y - r * 0.2f),
            Offset(x + r * 0.4f, y - r * 0.2f), strokeWidth = 3f)
        drawLine(BoxColors.ArcadeGloveLace, Offset(x - r * 0.4f, y + r * 0.1f),
            Offset(x + r * 0.4f, y + r * 0.1f), strokeWidth = 3f)
    }
}

// === Player gloves at bottom corners (first-person POV) ===
private fun drawPlayerGlove(
    scope: DrawScope,
    side: Int,                  // -1 = left, +1 = right
    anim: GloveAnim?,
    w: Float,
    h: Float,
    now: Long,
    bob: Float,
) {
    with(scope) {
        // Guard pose: bottom-corner anchor
        val guardX = if (side < 0) w * 0.20f else w * 0.80f
        val guardY = h * 0.86f
        val guardR = 70f
        // Compute extension target based on punch kind
        val (tx, ty, tr) = if (anim == null) Triple(guardX, guardY, guardR) else {
            val ageMs = (now - anim.startedAt).toFloat()
            val factor = when {
                ageMs < GLOVE_EXTEND_MS -> ageMs / GLOVE_EXTEND_MS
                ageMs < GLOVE_EXTEND_MS + GLOVE_HOLD_MS -> 1f
                else -> 1f - ((ageMs - GLOVE_EXTEND_MS - GLOVE_HOLD_MS) / GLOVE_RETURN_MS).coerceIn(0f, 1f)
            }
            val (extX, extY, extR) = when (anim.pose) {
                GlovePose.Jab -> Triple(
                    if (side < 0) w * 0.34f else w * 0.66f,
                    h * 0.58f,
                    96f
                )
                GlovePose.Hook -> Triple(
                    if (side < 0) w * 0.50f else w * 0.50f,
                    h * 0.66f,
                    100f
                )
                GlovePose.Uppercut -> Triple(
                    if (side < 0) w * 0.36f else w * 0.64f,
                    h * 0.42f,
                    104f
                )
                GlovePose.Guard -> Triple(guardX, guardY, guardR)
            }
            Triple(
                guardX + (extX - guardX) * factor,
                guardY + (extY - guardY) * factor + bob * (1f - factor),
                guardR + (extR - guardR) * factor
            )
        }

        // Wrist/forearm — drawn from the bottom edge up to the glove
        val forearmAnchor = Offset(if (side < 0) w * 0.18f else w * 0.82f, h + 20f)
        drawLine(
            color = BoxColors.ArcadeSkin,
            start = forearmAnchor,
            end = Offset(tx, ty + tr * 0.4f),
            strokeWidth = tr * 0.95f
        )
        // Wrist tape (white band at glove cuff)
        drawLine(
            color = Color.White,
            start = Offset(tx - tr * 0.55f, ty + tr * 0.55f),
            end = Offset(tx + tr * 0.55f, ty + tr * 0.55f),
            strokeWidth = 8f
        )

        // Glove body
        drawCircle(Color.Black.copy(alpha = 0.5f), tr * 1.05f, Offset(tx + 5f, ty + 8f))
        drawCircle(BoxColors.ArcadeGloveRed, tr, Offset(tx, ty))
        drawCircle(Color.Black, tr, Offset(tx, ty), style = Stroke(width = 4f))
        // Knuckle highlight (inside the curve)
        drawArc(
            color = Color(0xFFFF8A8A),
            startAngle = 180f, sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(tx - tr * 0.85f, ty - tr * 0.85f),
            size = Size(tr * 1.7f, tr * 1.7f),
            style = Stroke(width = 6f)
        )
        // Laces
        for (i in 0..2) {
            val ly = ty - tr * 0.3f + i * tr * 0.3f
            drawLine(BoxColors.ArcadeGloveLace,
                Offset(tx - tr * 0.45f, ly),
                Offset(tx + tr * 0.45f, ly),
                strokeWidth = 4f)
        }
    }
}

// ===================== HUD overlays =====================

@Composable
private fun ArcadeHUD(playerHp: Int, opponentHp: Int, combo: Int, elapsedMs: Long) {
    val totalSec = (elapsedMs / 1000).toInt()
    val mm = totalSec / 60
    val ss = totalSec % 60
    val timer = "%02d:%02d".format(mm, ss)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f))
            .border(width = 2.dp, color = BoxColors.ArcadeSparkle.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ROUND 1", color = BoxColors.ArcadeSparkle,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(if (combo >= 2) "${combo}× COMBO" else " ",
                color = if (combo >= 4) BoxColors.ArcadeSparkle else BoxColors.OnDark,
                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(timer, color = Color.White,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BlockyHpBar("P1", BoxColors.SuccessGreen, playerHp)
            BlockyHpBar("RIVAL", BoxColors.DangerRed, opponentHp, alignRight = true)
        }
    }
}

@Composable
private fun BlockyHpBar(label: String, color: Color, hp: Int, alignRight: Boolean = false) {
    val cells = 12
    val animated by animateFloatAsState(
        targetValue = (hp.toFloat() / MAX_HP).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200), label = "hp-bar"
    )
    val animatedFilled = (animated * cells).toInt().coerceIn(0, cells)
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!alignRight) {
            Text(label, color = Color.White, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(end = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until cells) {
                val on = i < animatedFilled
                Box(modifier = Modifier
                    .size(width = 14.dp, height = 14.dp)
                    .background(if (on) color else Color.White.copy(alpha = 0.15f))
                    .border(1.dp, Color.Black.copy(alpha = 0.6f))
                )
            }
        }
        if (alignRight) {
            Text(label, color = Color.White, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun ArcadeWarning(text: String) {
    // Comic-style red on white outline
    Box {
        // Outline copies
        for (dx in listOf(-2, -2, 2, 2)) {
            for (dy in listOf(-2, 2)) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(start = (dx + 4).dp, top = (dy + 4).dp)
                )
            }
        }
        Text(
            text = text,
            color = BoxColors.DangerRed,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }
}

@Composable
private fun CalloutText(c: Callout, now: Long) {
    val ageMs = (now - c.createdAt).toFloat()
    val a = (1f - ageMs / 700f).coerceIn(0f, 1f)
    val s = 0.7f + 0.4f * (1f - a)
    val rotation = if (c.id % 2L == 0L) -6f else 8f
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Box(
            modifier = Modifier
                .padding(start = w * c.xFrac - 60.dp, top = h * c.yFrac - 30.dp)
                .alpha(a)
                .scale(s)
                .rotate(rotation)
        ) {
            // Yellow burst (drawn behind via canvas)
            Canvas(modifier = Modifier.size(120.dp, 60.dp)) {
                val cx = size.width / 2f; val cy = size.height / 2f
                val rays = 12
                for (i in 0 until rays) {
                    val theta = (i.toFloat() / rays) * 2f * PI.toFloat()
                    val r1 = 22f
                    val r2 = 50f
                    drawLine(
                        c.color.copy(alpha = a),
                        Offset(cx + r1 * cos(theta), cy + r1 * sin(theta)),
                        Offset(cx + r2 * cos(theta), cy + r2 * sin(theta)),
                        strokeWidth = 6f
                    )
                }
                drawCircle(c.color.copy(alpha = a), 28f, Offset(cx, cy))
            }
        }
        Box(
            modifier = Modifier
                .padding(start = w * c.xFrac - 50.dp, top = h * c.yFrac - 20.dp)
                .alpha(a)
                .scale(s)
                .rotate(rotation)
        ) {
            // Text on top
            Text(
                text = c.text,
                color = Color.Black,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            )
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = a.alpha + (b.alpha - a.alpha) * k,
    )
}
