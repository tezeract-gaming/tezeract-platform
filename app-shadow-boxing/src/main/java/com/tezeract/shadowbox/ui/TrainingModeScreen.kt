package com.tezeract.shadowbox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tezeract.motion.MotionFrame
import com.tezeract.sdk.MotionListener
import com.tezeract.sdk.TezeractMotion
import com.tezeract.shadowbox.motion.PunchDetector
import com.tezeract.shadowbox.motion.PunchKind
import kotlinx.coroutines.delay

private const val PROMPT_DURATION_START_MS = 2400L
private const val PROMPT_DURATION_FLOOR_MS = 900L
private const val SESSION_DURATION_MS = 60_000L

private val DRILL_KINDS = listOf(
    PunchKind.LEFT_JAB, PunchKind.RIGHT_JAB,
    PunchKind.LEFT_HOOK, PunchKind.RIGHT_HOOK,
    PunchKind.LEFT_UPPERCUT, PunchKind.RIGHT_UPPERCUT,
)

@Composable
fun TrainingModeScreen(@Suppress("UNUSED_PARAMETER") onExit: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        TrainingContent()
        BoxHUD(modifier = Modifier
            .align(AbsoluteAlignment.TopRight)
            .padding(top = 80.dp, end = 16.dp))
    }
}

@Composable
private fun TrainingContent() {
    val detector = remember { PunchDetector() }
    var current by remember { mutableStateOf(DRILL_KINDS.random()) }
    var promptStartedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var sessionStart by remember { mutableStateOf(System.currentTimeMillis()) }
    var score by remember { mutableStateOf(0) }
    var combo by remember { mutableStateOf(0) }
    var maxCombo by remember { mutableStateOf(0) }
    var hits by remember { mutableStateOf(0) }
    var misses by remember { mutableStateOf(0) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    var lastResultColor by remember { mutableStateOf(BoxColors.Accent) }
    var lastResultAt by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val listener = object : MotionListener {
            override fun onMotionFrame(frame: MotionFrame) {
                val now = System.currentTimeMillis()
                val punches = detector.classify(frame, now)
                if (punches.isEmpty()) return
                val correct = punches.firstOrNull { it.kind == current }
                if (correct != null) {
                    val timing = (now - promptStartedAt).toFloat() / promptDuration(now - sessionStart)
                    val pts = when {
                        timing < 0.4f -> 100
                        timing < 0.7f -> 60
                        else -> 30
                    }
                    score += pts
                    hits += 1
                    combo += 1
                    maxCombo = maxOf(maxCombo, combo)
                    lastResult = "+$pts"; lastResultColor = BoxColors.SuccessGreen; lastResultAt = now
                    nextPrompt(now) { newKind, newStart -> current = newKind; promptStartedAt = newStart }
                } else if (punches.any { it.kind != PunchKind.BLOCK && it.kind != current }) {
                    combo = 0
                    misses += 1
                    lastResult = "wrong!"; lastResultColor = BoxColors.DangerRed; lastResultAt = now
                }
            }
        }
        TezeractMotion.addMotionListener(listener)
        onDispose { TezeractMotion.removeMotionListener(listener) }
    }

    LaunchedEffect(Unit) {
        sessionStart = System.currentTimeMillis()
        var lastSwitch = sessionStart
        promptStartedAt = sessionStart
        while (true) {
            delay(40)
            val now = System.currentTimeMillis()
            clock = now
            // Auto-advance if user takes too long.
            if (now - promptStartedAt > promptDuration(now - sessionStart)) {
                misses += 1; combo = 0
                lastResult = "TOO SLOW"; lastResultColor = BoxColors.DangerRed; lastResultAt = now
                nextPrompt(now) { newKind, newStart -> current = newKind; promptStartedAt = newStart }
            }
            // 60s session — keep counting upward, no hard end.
        }
    }

    val timerProgress by animateFloatAsState(
        targetValue = ((clock - promptStartedAt).toFloat() / promptDuration(clock - sessionStart))
            .coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "training-timer"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top HUD
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("SCORE", score.toString(), BoxColors.Accent, big = true)
            Stat("COMBO", if (combo == 0) "—" else "${combo}x",
                 if (combo >= 5) BoxColors.Accent else BoxColors.OnDark, big = true)
            Stat("HITS", "$hits / ${hits + misses}", BoxColors.OnDarkMuted, big = false)
        }

        // Center prompt — big, dramatic
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (color, label, sub) = visualForTraining(current)
            Text("COACH SAYS", color = BoxColors.OnDarkMuted, fontSize = 14.sp,
                 fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            Spacer(Modifier.height(12.dp))
            Text(label, color = color, fontSize = 96.sp, fontWeight = FontWeight.Black)
            Text(sub, color = BoxColors.OnDarkMuted, fontSize = 18.sp)
            // Result flash
            val ageMs = (clock - lastResultAt).toFloat()
            val visible = lastResult != null && ageMs < 700f
            val a = (1f - ageMs / 700f).coerceIn(0f, 1f)
            val s = 1f + 0.4f * (1f - a)
            Box(modifier = Modifier.height(64.dp), contentAlignment = Alignment.Center) {
                if (visible) Text(lastResult!!,
                    color = lastResultColor, fontSize = 32.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.alpha(a).scale(s))
            }
        }

        // Timer bar
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(5.dp))
            ) {
                Box(modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(1f - timerProgress)
                    .background(BoxColors.Primary, RoundedCornerShape(5.dp))
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("triangle gesture exits — combos increase the tempo",
                 color = BoxColors.OnDarkMuted, fontSize = 11.sp)
        }
    }
}

private fun promptDuration(elapsed: Long): Float {
    val progress = (elapsed.toFloat() / SESSION_DURATION_MS).coerceIn(0f, 1f)
    return PROMPT_DURATION_START_MS - (PROMPT_DURATION_START_MS - PROMPT_DURATION_FLOOR_MS) * progress
}

private fun nextPrompt(now: Long, set: (PunchKind, Long) -> Unit) {
    set(DRILL_KINDS.random(), now)
}

private fun visualForTraining(kind: PunchKind): Triple<Color, String, String> = when (kind) {
    PunchKind.LEFT_JAB -> Triple(BoxColors.Cool, "LEFT JAB", "straight punch with your left")
    PunchKind.RIGHT_JAB -> Triple(BoxColors.Cool, "RIGHT JAB", "straight punch with your right")
    PunchKind.LEFT_HOOK -> Triple(BoxColors.Accent, "LEFT HOOK", "swing across with your left")
    PunchKind.RIGHT_HOOK -> Triple(BoxColors.Accent, "RIGHT HOOK", "swing across with your right")
    PunchKind.LEFT_UPPERCUT -> Triple(BoxColors.Primary, "LEFT UPPERCUT", "drive your left fist UP")
    PunchKind.RIGHT_UPPERCUT -> Triple(BoxColors.Primary, "RIGHT UPPERCUT", "drive your right fist UP")
    PunchKind.BLOCK -> Triple(Color.White, "BLOCK", "hands up at your face")
}

@Composable
private fun Stat(label: String, value: String, color: Color, big: Boolean) {
    Column {
        Text(label, color = BoxColors.OnDarkMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = color, fontSize = if (big) 36.sp else 18.sp, fontWeight = FontWeight.Black)
    }
}
