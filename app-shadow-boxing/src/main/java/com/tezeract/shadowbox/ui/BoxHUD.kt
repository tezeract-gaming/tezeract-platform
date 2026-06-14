package com.tezeract.shadowbox.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tezeract.input.InputAction
import com.tezeract.input.InputListener
import com.tezeract.input.TezeractInput
import com.tezeract.motion.Keypoint
import com.tezeract.motion.MotionFrame
import com.tezeract.sdk.MotionListener
import com.tezeract.sdk.TezeractMotion
import kotlinx.coroutines.delay

/**
 * Compact motion HUD for shadow-boxing screens. Same idea as the launcher's
 * MotionHUD — skeleton mirror so the user knows the camera sees them, plus
 * a small legend of the active controls. Flashes when the matching input
 * fires so feedback is immediate.
 *
 * @param controls list of (label, input) pairs to display under the viewport.
 *                 If a TezeractInput in the list fires PRESS, that row flashes.
 *                 Pass an empty list to show only the skeleton.
 */
data class BoxHint(val label: String, val input: TezeractInput?)

@Composable
fun BoxHUD(
    controls: List<BoxHint> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableStateOf<MotionFrame?>(null) }
    val lastFireMs = remember { mutableStateMapOf<TezeractInput, Long>() }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        val motion = object : MotionListener {
            override fun onMotionFrame(f: MotionFrame) { frame = f }
        }
        val input = InputListener { ev ->
            if (ev.action == InputAction.PRESS) lastFireMs[ev.input] = System.currentTimeMillis()
        }
        TezeractMotion.addMotionListener(motion)
        TezeractMotion.addInputListener(input)
        onDispose {
            TezeractMotion.removeMotionListener(motion)
            TezeractMotion.removeInputListener(input)
        }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(60); clock = System.currentTimeMillis() }
    }

    Column(
        modifier = modifier
            .width(168.dp)
            .background(Color(0xE60A0612), RoundedCornerShape(10.dp))
            .border(1.dp, BoxColors.Primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SkeletonViewport(frame, modifier = Modifier.size(width = 152.dp, height = 108.dp))
        val tracking = frame?.let { it.bodyCount > 0 } == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (tracking) BoxColors.SuccessGreen else Color(0x88FFFFFF),
                        RoundedCornerShape(3.dp)
                    )
            )
            Text(
                text = if (tracking) "  in frame" else "  step in",
                color = if (tracking) BoxColors.SuccessGreen else BoxColors.OnDarkMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (controls.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x33FFFFFF))
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (hint in controls) HintRow(hint, hint.input?.let { lastFireMs[it] }, clock)
            }
        }
    }
}

@Composable
private fun HintRow(hint: BoxHint, fireAtMs: Long?, nowMs: Long) {
    val flashProgress = if (fireAtMs == null) 1f
    else ((nowMs - fireAtMs).toFloat() / 500f).coerceIn(0f, 1f)
    val active = flashProgress < 1f
    val intensity = if (active) 1f - flashProgress else 0f
    val bg by animateColorAsState(
        targetValue = if (active) BoxColors.Accent.copy(alpha = 0.20f * intensity) else Color.Transparent,
        animationSpec = tween(80),
        label = "hud-bg-${hint.input}"
    )
    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hint.label,
            color = if (active) Color.White else Color(0xDDFFFFFF),
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold,
        )
    }
}

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

@Composable
private fun SkeletonViewport(frame: MotionFrame?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFF1A0F2E), RoundedCornerShape(6.dp))
            .border(1.dp, BoxColors.Primary.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val w = size.width; val h = size.height
            val body = frame?.bodies?.firstOrNull() ?: return@Canvas
            val bone = BoxColors.Cool
            val point = BoxColors.Accent
            for ((a, b) in skeletonBones) {
                val ka = body.keypoints.getOrNull(a) ?: continue
                val kb = body.keypoints.getOrNull(b) ?: continue
                if (ka.confidence < 0.3f || kb.confidence < 0.3f) continue
                drawLine(bone, Offset(ka.x * w, ka.y * h), Offset(kb.x * w, kb.y * h), strokeWidth = 2.5f)
            }
            for (kp in body.keypoints) {
                if (kp.confidence < 0.3f) continue
                drawCircle(point, radius = 2.5f, center = Offset(kp.x * w, kp.y * h))
            }
        }
    }
}
