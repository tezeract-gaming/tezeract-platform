package com.tezeract.shadowbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tezeract.motion.MotionFrame
import com.tezeract.sdk.MotionListener
import com.tezeract.sdk.TezeractMotion
import com.tezeract.shadowbox.motion.Punch
import com.tezeract.shadowbox.motion.PunchDetector
import com.tezeract.shadowbox.motion.PunchKind
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val TARGET_LIFETIME_MS = 2200L
private const val SPAWN_INTERVAL_MS_START = 1800L
private const val SPAWN_INTERVAL_MS_FLOOR = 700L

private data class Target(
    val id: Long,
    val kind: PunchKind,
    val xLane: Float,        // 0..1 across screen
    val spawnedAt: Long,
)

private data class HitFlash(val id: Long, val xLane: Float, val color: Color, val createdAt: Long, val text: String)

private val ScoreableKinds = listOf(
    PunchKind.LEFT_JAB, PunchKind.RIGHT_JAB,
    PunchKind.LEFT_HOOK, PunchKind.RIGHT_HOOK,
    PunchKind.LEFT_UPPERCUT, PunchKind.RIGHT_UPPERCUT,
)

@Composable
fun ReactionModeScreen(onExit: () -> Unit) {
    val detector = remember { PunchDetector() }
    val targets = remember { mutableStateListOf<Target>() }
    val flashes = remember { mutableStateListOf<HitFlash>() }
    var score by remember { mutableStateOf(0) }
    var combo by remember { mutableStateOf(0) }
    var maxCombo by remember { mutableStateOf(0) }
    var hits by remember { mutableStateOf(0) }
    var misses by remember { mutableStateOf(0) }
    var nextId by remember { mutableStateOf(1L) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val listener = object : MotionListener {
            override fun onMotionFrame(frame: MotionFrame) {
                val now = System.currentTimeMillis()
                val punches = detector.classify(frame, now)
                if (punches.isEmpty()) return
                for (p in punches) onPunch(p, targets, flashes,
                    onScore = { add -> score += add; combo += 1; maxCombo = maxOf(maxCombo, combo); hits += 1 },
                    onMiss = { combo = 0 })
            }
        }
        TezeractMotion.addMotionListener(listener)
        onDispose { TezeractMotion.removeMotionListener(listener) }
    }

    // Game loop tick. Spawns targets on a curve, expires old ones.
    LaunchedEffect(Unit) {
        var lastSpawn = 0L
        val gameStart = System.currentTimeMillis()
        while (true) {
            delay(40)
            val now = System.currentTimeMillis()
            clock = now
            elapsed = now - gameStart

            // Difficulty curve: spawn interval drops over 60s.
            val progress = (elapsed.toFloat() / 60_000f).coerceIn(0f, 1f)
            val spawnInterval = (SPAWN_INTERVAL_MS_START -
                (SPAWN_INTERVAL_MS_START - SPAWN_INTERVAL_MS_FLOOR) * progress).toLong()
            if (now - lastSpawn > spawnInterval) {
                targets.add(
                    Target(
                        id = nextId++,
                        kind = ScoreableKinds.random(),
                        xLane = Random.nextFloat() * 0.8f + 0.1f,
                        spawnedAt = now,
                    )
                )
                lastSpawn = now
            }
            // Expire targets — count as misses.
            val toRemove = targets.filter { now - it.spawnedAt > TARGET_LIFETIME_MS }
            if (toRemove.isNotEmpty()) {
                misses += toRemove.size
                combo = 0
                targets.removeAll(toRemove)
            }
            // Trim flashes after 600ms.
            flashes.removeAll { now - it.createdAt > 600 }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Field
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            // Hit zone (where the punch is supposed to register)
            val hitY = h * 0.78f
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, hitY), Offset(w, hitY), strokeWidth = 2f)

            for (t in targets) {
                val ageMs = (clock - t.spawnedAt).coerceAtLeast(0)
                val progress = (ageMs.toFloat() / TARGET_LIFETIME_MS).coerceIn(0f, 1f)
                val cx = t.xLane * w
                val cy = h * 0.20f + (hitY - h * 0.20f) * progress
                val radius = 28f + 24f * progress
                val (color, _) = visualFor(t.kind)
                // Glow
                drawCircle(color.copy(alpha = 0.20f), radius * 1.6f, Offset(cx, cy))
                drawCircle(color.copy(alpha = 0.85f), radius, Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = 0.85f), radius, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            }

            for (f in flashes) {
                val ageMs = (clock - f.createdAt).toFloat()
                val a = (1f - ageMs / 600f).coerceIn(0f, 1f)
                drawCircle(f.color.copy(alpha = a * 0.7f), radius = 80f + 80f * (1f - a), center = Offset(f.xLane * size.width, hitY))
            }
        }

        // Target labels (Compose Text floats over the canvas)
        for (t in targets) {
            val ageMs = (clock - t.spawnedAt).coerceAtLeast(0)
            val progress = (ageMs.toFloat() / TARGET_LIFETIME_MS).coerceIn(0f, 1f)
            val (_, label) = visualFor(t.kind)
            TargetLabel(label = label, xLane = t.xLane, progress = progress)
        }
        for (f in flashes) {
            FlashText(text = f.text, color = f.color, xLane = f.xLane, createdAt = f.createdAt, nowMs = clock)
        }

        // HUD top
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat(label = "SCORE", value = score.toString(), color = BoxColors.Accent, big = true)
            Stat(label = "COMBO", value = if (combo == 0) "—" else "${combo}x", color = if (combo >= 5) BoxColors.Accent else BoxColors.OnDark, big = true)
            Stat(label = "TIME", value = "${(elapsed / 1000).toInt()}s", color = BoxColors.OnDarkMuted, big = false)
            Stat(label = "HIT/MISS", value = "$hits / $misses", color = BoxColors.OnDarkMuted, big = false)
        }

        // Bottom helper text
        Text(
            text = "punch each target on the line  ·  triangle to exit",
            color = BoxColors.OnDarkMuted,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
        // Reusable HUD — skeleton mirror so the user can see their stance.
        BoxHUD(
            modifier = Modifier
                .align(AbsoluteAlignment.TopRight)
                .padding(top = 80.dp, end = 16.dp)
        )

        // onExit hooked for completeness — universal HOME_TRIANGLE in SDK exits the activity entirely.
        Box(modifier = Modifier.size(0.dp)) { /* parking */ if (false) onExit() }
    }
}

private fun onPunch(
    punch: Punch,
    targets: MutableList<Target>,
    flashes: MutableList<HitFlash>,
    onScore: (Int) -> Unit,
    onMiss: () -> Unit,
) {
    if (punch.kind == PunchKind.BLOCK) return
    // Match the OLDEST target of the same kind that's currently within hit window.
    val now = punch.timestampMs
    val candidate = targets.minByOrNull { it.spawnedAt }?.takeIf {
        it.kind == punch.kind && now - it.spawnedAt > TARGET_LIFETIME_MS * 0.55
    }
    if (candidate != null) {
        val timing = ((now - candidate.spawnedAt).toFloat() / TARGET_LIFETIME_MS).coerceIn(0f, 1f)
        val (points, label, color) = when {
            timing > 0.85f -> Triple(150, "PERFECT", BoxColors.Accent)
            timing > 0.70f -> Triple(80, "GREAT", BoxColors.SuccessGreen)
            else -> Triple(35, "GOOD", BoxColors.Cool)
        }
        targets.remove(candidate)
        flashes.add(HitFlash(System.nanoTime(), candidate.xLane, color, now, label))
        onScore(points)
    } else {
        // Wrong-kind punch or no target — small visual ack at center bottom.
        flashes.add(HitFlash(System.nanoTime(), 0.5f, BoxColors.DangerRed, now, "MISS"))
        onMiss()
    }
}

@Composable
private fun TargetLabel(label: String, xLane: Float, progress: Float) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val xDp = (w * xLane) - 36.dp
        val yDp = (h * 0.20f) + (h * 0.78f - h * 0.20f) * progress - 12.dp
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .padding(start = xDp, top = yDp)
        )
    }
}

@Composable
private fun FlashText(text: String, color: Color, xLane: Float, createdAt: Long, nowMs: Long) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val ageMs = (nowMs - createdAt).toFloat()
        val a = (1f - ageMs / 600f).coerceIn(0f, 1f)
        val xDp = (w * xLane) - 60.dp
        val yDp = (h * 0.78f) - 80.dp - (40.dp * (1f - a))
        Text(
            text = text,
            color = color.copy(alpha = a),
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(start = xDp, top = yDp)
        )
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, big: Boolean) {
    Column {
        Text(label, color = BoxColors.OnDarkMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = color, fontSize = if (big) 38.sp else 18.sp, fontWeight = FontWeight.Black)
    }
}

private fun visualFor(kind: PunchKind): Pair<Color, String> = when (kind) {
    PunchKind.LEFT_JAB -> BoxColors.Cool to "L · JAB"
    PunchKind.RIGHT_JAB -> BoxColors.Cool to "R · JAB"
    PunchKind.LEFT_HOOK -> BoxColors.Accent to "L · HOOK"
    PunchKind.RIGHT_HOOK -> BoxColors.Accent to "R · HOOK"
    PunchKind.LEFT_UPPERCUT -> BoxColors.Primary to "L · UPCUT"
    PunchKind.RIGHT_UPPERCUT -> BoxColors.Primary to "R · UPCUT"
    PunchKind.BLOCK -> Color.White to "BLOCK"
}
