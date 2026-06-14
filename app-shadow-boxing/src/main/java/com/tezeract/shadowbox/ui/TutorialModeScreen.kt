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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tezeract.motion.MotionFrame
import com.tezeract.sdk.MotionListener
import com.tezeract.sdk.TezeractMotion
import com.tezeract.shadowbox.motion.PunchDetector
import com.tezeract.shadowbox.motion.PunchKind
import kotlinx.coroutines.delay

private val TUTORIAL_STEPS = listOf(
    PunchKind.LEFT_JAB,
    PunchKind.RIGHT_JAB,
    PunchKind.LEFT_HOOK,
    PunchKind.RIGHT_HOOK,
    PunchKind.LEFT_UPPERCUT,
    PunchKind.RIGHT_UPPERCUT,
    PunchKind.BLOCK,
)

// BLOCK is a held pose, not a strike — require ~400ms of consecutive
// BLOCK-classified frames so a fleeting hand-near-face doesn't count.
private const val BLOCK_HOLD_MS = 400L

// "Skip" affordance unlocks once the player has been on a step long
// enough to clearly fail at it — keeps the tutorial from being a wall.
private const val SKIP_UNLOCK_MS = 15_000L

// Time the green "✓ Got it!" confirmation stays up before advancing.
private const val ADVANCE_DELAY_MS = 800L

@Composable
fun TutorialModeScreen(@Suppress("UNUSED_PARAMETER") onExit: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        TutorialContent()
        BoxHUD(modifier = Modifier
            .align(AbsoluteAlignment.TopRight)
            .padding(top = 80.dp, end = 16.dp))
    }
}

@Composable
private fun TutorialContent() {
    val detector = remember { PunchDetector() }
    var stepIdx by remember { mutableStateOf(0) }
    var stepStartedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var blockHoldStartedAt by remember { mutableStateOf(0L) }
    var lastBlockSeenAt by remember { mutableStateOf(0L) }
    var confirmedAt by remember { mutableStateOf(0L) }
    var lastConfirmedKind by remember { mutableStateOf<PunchKind?>(null) }
    var done by remember { mutableStateOf(false) }

    val current = TUTORIAL_STEPS.getOrNull(stepIdx)

    DisposableEffect(stepIdx) {
        // Reset block-hold tracker between steps so the previous step's
        // half-held block can't auto-pass the next one.
        blockHoldStartedAt = 0L
        lastBlockSeenAt = 0L

        val listener = object : MotionListener {
            override fun onMotionFrame(frame: MotionFrame) {
                if (done || confirmedAt > 0) return
                val target = current ?: return
                val now = System.currentTimeMillis()
                val punches = detector.classify(frame, now)
                if (punches.isEmpty()) {
                    // BLOCK requires *continuous* frames. If we miss two frames
                    // in a row (~80ms), the hold counter resets.
                    if (target == PunchKind.BLOCK && now - lastBlockSeenAt > 100L) {
                        blockHoldStartedAt = 0L
                    }
                    return
                }
                if (target == PunchKind.BLOCK) {
                    val sawBlock = punches.any { it.kind == PunchKind.BLOCK }
                    if (sawBlock) {
                        lastBlockSeenAt = now
                        if (blockHoldStartedAt == 0L) blockHoldStartedAt = now
                        if (now - blockHoldStartedAt >= BLOCK_HOLD_MS) {
                            confirmedAt = now
                            lastConfirmedKind = PunchKind.BLOCK
                        }
                    } else if (now - lastBlockSeenAt > 100L) {
                        blockHoldStartedAt = 0L
                    }
                } else {
                    val match = punches.firstOrNull { it.kind == target }
                    if (match != null) {
                        confirmedAt = now
                        lastConfirmedKind = target
                    }
                }
            }
        }
        TezeractMotion.addMotionListener(listener)
        onDispose { TezeractMotion.removeMotionListener(listener) }
    }

    // Advance after confirmation pause.
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            val now = System.currentTimeMillis()
            clock = now
            if (confirmedAt > 0 && now - confirmedAt >= ADVANCE_DELAY_MS) {
                if (stepIdx + 1 >= TUTORIAL_STEPS.size) {
                    done = true
                } else {
                    stepIdx += 1
                    stepStartedAt = now
                }
                confirmedAt = 0L
            }
        }
    }

    if (done) {
        TutorialDone()
        return
    }

    val target = current ?: return
    val (color, label, sub) = visualForTutorial(target)
    val onStepFor = (clock - stepStartedAt).coerceAtLeast(0)
    val isConfirmed = confirmedAt > 0
    val skipReady = onStepFor > SKIP_UNLOCK_MS

    Column(
        modifier = Modifier.fillMaxSize().padding(36.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Step counter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TUTORIAL", color = BoxColors.Accent,
                fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("step ${stepIdx + 1} of ${TUTORIAL_STEPS.size}",
                color = BoxColors.OnDarkMuted, fontSize = 13.sp)
        }

        // Center prompt
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("THROW", color = BoxColors.OnDarkMuted, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            Spacer(Modifier.height(8.dp))
            Text(label, color = color, fontSize = 84.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(sub, color = BoxColors.OnDarkMuted, fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            // Diagram
            PunchDiagram(target, color, modifier = Modifier.size(220.dp, 160.dp))
            Spacer(Modifier.height(8.dp))

            // BLOCK hold progress bar — only meaningful while attempting the BLOCK step.
            if (target == PunchKind.BLOCK && !isConfirmed) {
                val holdFrac = if (blockHoldStartedAt == 0L) 0f
                else ((clock - blockHoldStartedAt).toFloat() / BLOCK_HOLD_MS).coerceIn(0f, 1f)
                Box(modifier = Modifier
                    .fillMaxWidth(0.4f).height(8.dp)
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(4.dp))) {
                    Box(modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(holdFrac)
                        .background(color, RoundedCornerShape(4.dp)))
                }
                Spacer(Modifier.height(6.dp))
                Text("hold the pose...", color = BoxColors.OnDarkMuted, fontSize = 11.sp)
            }
        }

        // Confirmation flash
        Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            if (isConfirmed) {
                val ageMs = (clock - confirmedAt).toFloat()
                val a = (1f - ageMs / ADVANCE_DELAY_MS).coerceIn(0f, 1f).coerceAtLeast(0.3f)
                val s = 1f + 0.25f * (1f - a)
                Text("✓ GOT IT!", color = BoxColors.SuccessGreen,
                    fontSize = 38.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.alpha(a).scale(s))
            } else if (skipReady) {
                // After 15s on the same step we assume detection is rough —
                // any punch will advance, and triangle leaves the tutorial.
                Text(
                    "any punch advances · triangle exits tutorial",
                    color = BoxColors.OnDarkMuted, fontSize = 12.sp
                )
            } else {
                Text("watch the diagram, then throw the punch",
                    color = BoxColors.OnDarkMuted, fontSize = 12.sp)
            }
        }

        // Progress dots
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in TUTORIAL_STEPS.indices) {
                val (c, _, _) = visualForTutorial(TUTORIAL_STEPS[i])
                val dotColor = when {
                    i < stepIdx -> BoxColors.SuccessGreen
                    i == stepIdx -> c
                    else -> Color.White.copy(alpha = 0.18f)
                }
                Box(modifier = Modifier
                    .size(width = 28.dp, height = 6.dp)
                    .background(dotColor, RoundedCornerShape(3.dp)))
            }
        }
    }

    // Fallback skip — armed after SKIP_UNLOCK_MS, fires on any non-target punch.
    if (skipReady && !isConfirmed) {
        DisposableEffect(stepIdx, skipReady) {
            val skipListener = object : MotionListener {
                override fun onMotionFrame(frame: MotionFrame) {
                    val now = System.currentTimeMillis()
                    val punches = detector.classify(frame, now)
                    if (punches.any { it.kind != PunchKind.BLOCK }) {
                        confirmedAt = now
                        lastConfirmedKind = null
                    }
                }
            }
            // Note: we deliberately keep this listener narrow — it only fires
            // once the user has spent 15s on a single step, indicating bad
            // detection rather than learning.
            TezeractMotion.addMotionListener(skipListener)
            onDispose { TezeractMotion.removeMotionListener(skipListener) }
        }
    }
}

@Composable
private fun TutorialDone() {
    val pulse by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(900),
        label = "tutorial-done"
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✓", color = BoxColors.SuccessGreen,
            fontSize = (96 + 12 * pulse).sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        Text("ALL 7 CONFIRMED", color = BoxColors.OnDark,
            fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Spacer(Modifier.height(12.dp))
        Text("you're ready to step into the ring",
            color = BoxColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        Box(modifier = Modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .border(1.dp, BoxColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text("triangle gesture → back to picker",
                color = BoxColors.OnDark, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun visualForTutorial(kind: PunchKind): Triple<Color, String, String> = when (kind) {
    PunchKind.LEFT_JAB -> Triple(BoxColors.Cool, "LEFT JAB",
        "punch straight forward with your left hand")
    PunchKind.RIGHT_JAB -> Triple(BoxColors.Cool, "RIGHT JAB",
        "punch straight forward with your right hand")
    PunchKind.LEFT_HOOK -> Triple(BoxColors.Accent, "LEFT HOOK",
        "swing your left fist sideways across your body")
    PunchKind.RIGHT_HOOK -> Triple(BoxColors.Accent, "RIGHT HOOK",
        "swing your right fist sideways across your body")
    PunchKind.LEFT_UPPERCUT -> Triple(BoxColors.Primary, "LEFT UPPERCUT",
        "drive your left fist UP from your hip")
    PunchKind.RIGHT_UPPERCUT -> Triple(BoxColors.Primary, "RIGHT UPPERCUT",
        "drive your right fist UP from your hip")
    PunchKind.BLOCK -> Triple(BoxColors.SuccessGreen, "BLOCK",
        "raise both hands to your face and HOLD")
}

@Composable
private fun PunchDiagram(kind: PunchKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        // Stick-figure body — head + shoulders + torso, fists drawn separately.
        val cx = w / 2f
        val headR = h * 0.10f
        val headY = h * 0.22f
        val shoulderY = headY + headR + 6f
        val shoulderHalf = w * 0.12f
        val hipY = h * 0.78f
        val hipHalf = w * 0.08f
        val white = Color.White.copy(alpha = 0.85f)
        // Head
        drawCircle(white, headR, Offset(cx, headY), style = Stroke(width = 3f))
        // Body outline (shoulder-line, torso, hip-line)
        drawLine(white, Offset(cx - shoulderHalf, shoulderY),
            Offset(cx + shoulderHalf, shoulderY), strokeWidth = 3f)
        drawLine(white, Offset(cx - shoulderHalf, shoulderY),
            Offset(cx - hipHalf, hipY), strokeWidth = 3f)
        drawLine(white, Offset(cx + shoulderHalf, shoulderY),
            Offset(cx + hipHalf, hipY), strokeWidth = 3f)
        drawLine(white, Offset(cx - hipHalf, hipY),
            Offset(cx + hipHalf, hipY), strokeWidth = 3f)

        val leftShoulder = Offset(cx - shoulderHalf, shoulderY)
        val rightShoulder = Offset(cx + shoulderHalf, shoulderY)

        // Each kind draws an arm + an arrow showing the motion direction.
        when (kind) {
            PunchKind.LEFT_JAB -> {
                val fist = Offset(cx - shoulderHalf - w * 0.18f, shoulderY + 4f)
                drawArm(leftShoulder, fist, white)
                drawArrow(fist, fist + Offset(w * 0.13f, -h * 0.04f), color)
                drawFist(fist, color)
            }
            PunchKind.RIGHT_JAB -> {
                val fist = Offset(cx + shoulderHalf + w * 0.18f, shoulderY + 4f)
                drawArm(rightShoulder, fist, white)
                drawArrow(fist, fist + Offset(-w * 0.13f, -h * 0.04f), color)
                drawFist(fist, color)
            }
            PunchKind.LEFT_HOOK -> {
                val fist = Offset(cx - shoulderHalf - w * 0.18f, shoulderY + h * 0.04f)
                drawArm(leftShoulder, fist, white)
                drawArrow(fist, Offset(cx + w * 0.10f, fist.y), color)
                drawFist(fist, color)
            }
            PunchKind.RIGHT_HOOK -> {
                val fist = Offset(cx + shoulderHalf + w * 0.18f, shoulderY + h * 0.04f)
                drawArm(rightShoulder, fist, white)
                drawArrow(fist, Offset(cx - w * 0.10f, fist.y), color)
                drawFist(fist, color)
            }
            PunchKind.LEFT_UPPERCUT -> {
                val fist = Offset(cx - shoulderHalf - w * 0.06f, hipY - h * 0.04f)
                drawArm(leftShoulder, fist, white)
                drawArrow(fist, Offset(fist.x + w * 0.06f, headY + headR), color)
                drawFist(fist, color)
            }
            PunchKind.RIGHT_UPPERCUT -> {
                val fist = Offset(cx + shoulderHalf + w * 0.06f, hipY - h * 0.04f)
                drawArm(rightShoulder, fist, white)
                drawArrow(fist, Offset(fist.x - w * 0.06f, headY + headR), color)
                drawFist(fist, color)
            }
            PunchKind.BLOCK -> {
                val leftFist = Offset(cx - w * 0.06f, headY + headR + 4f)
                val rightFist = Offset(cx + w * 0.06f, headY + headR + 4f)
                drawArm(leftShoulder, leftFist, white)
                drawArm(rightShoulder, rightFist, white)
                drawFist(leftFist, color)
                drawFist(rightFist, color)
                // Shield arc above the head
                drawArc(
                    color = color.copy(alpha = 0.7f),
                    startAngle = 200f, sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(cx - headR * 1.6f, headY - headR * 1.4f),
                    size = Size(headR * 3.2f, headR * 3f),
                    style = Stroke(width = 4f)
                )
            }
        }
    }
}

private fun DrawScope.drawArm(shoulder: Offset, fist: Offset, color: Color) {
    val mid = Offset((shoulder.x + fist.x) / 2f, (shoulder.y + fist.y) / 2f + 6f)
    drawLine(color, shoulder, mid, strokeWidth = 4f)
    drawLine(color, mid, fist, strokeWidth = 4f)
    drawCircle(color, 4f, mid)
}

private fun DrawScope.drawFist(at: Offset, color: Color) {
    drawCircle(color, 14f, at)
    drawCircle(Color.Black.copy(alpha = 0.4f), 14f, at, style = Stroke(width = 2f))
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color) {
    drawLine(color, from, to, strokeWidth = 5f)
    val dx = to.x - from.x; val dy = to.y - from.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    if (len < 1f) return
    val ux = dx / len; val uy = dy / len
    val head = 14f
    val perpX = -uy; val perpY = ux
    val baseX = to.x - ux * head; val baseY = to.y - uy * head
    val left = Offset(baseX + perpX * head * 0.6f, baseY + perpY * head * 0.6f)
    val right = Offset(baseX - perpX * head * 0.6f, baseY - perpY * head * 0.6f)
    drawLine(color, to, left, strokeWidth = 5f)
    drawLine(color, to, right, strokeWidth = 5f)
}
