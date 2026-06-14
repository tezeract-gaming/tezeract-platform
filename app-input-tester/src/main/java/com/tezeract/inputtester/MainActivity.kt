package com.tezeract.inputtester

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.tezeract.input.InputAction
import com.tezeract.input.InputEvent
import com.tezeract.input.InputListener
import com.tezeract.input.TezeractInput
import com.tezeract.motion.Keypoint
import com.tezeract.motion.MotionFrame
import com.tezeract.sdk.MotionListener
import com.tezeract.sdk.TezeractMotion

/**
 * Live visualization of every InputEvent the Tezeract Motion Engine produces.
 *
 * Renders a virtual gamepad — DPAD, ABXY, and the two triggers — and lights
 * each control up as the matching event fires. PRESS turns the control on,
 * RELEASE turns it off; the brightness of an active control is driven by
 * the latest event confidence.
 */
class MainActivity : ComponentActivity() {

    private val activeStates = mutableStateMapOf<TezeractInput, Float>() // input -> confidence (0..1)
    private val log = mutableStateOf<List<String>>(emptyList())
    private val latestFrame = mutableStateOf<MotionFrame?>(null)

    private val inputCb = InputListener { event ->
        runOnUiThread {
            when (event.action) {
                InputAction.PRESS, InputAction.HOLD ->
                    activeStates[event.input] = event.confidence
                InputAction.RELEASE ->
                    activeStates.remove(event.input)
            }
            val tag = "${event.input}/${event.action} ${"%.2f".format(event.confidence)}"
            log.value = (listOf(tag) + log.value).take(30)
        }
    }

    private val motionCb = object : MotionListener {
        override fun onMotionFrame(frame: MotionFrame) {
            latestFrame.value = frame
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        TezeractMotion.initialize(this)
        TezeractMotion.addInputListener(inputCb)
        TezeractMotion.addMotionListener(motionCb)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF101018)
                ) {
                    GamepadScreen(activeStates, log.value, latestFrame)
                }
            }
        }
    }

    override fun onDestroy() {
        TezeractMotion.removeInputListener(inputCb)
        TezeractMotion.removeMotionListener(motionCb)
        TezeractMotion.release()
        super.onDestroy()
    }
}

@Composable
private fun GamepadScreen(
    active: Map<TezeractInput, Float>,
    log: List<String>,
    latestFrame: State<MotionFrame?>,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: virtual gamepad
        Column(verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tezeract Input Tester", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Move in front of the camera to see events fire.",
                color = Color(0xAAFFFFFF), fontSize = 13.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Dpad(active)
                ButtonCluster(active)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                ControlPill(labelOf(TezeractInput.TRIGGER_L), active[TezeractInput.TRIGGER_L])
                ControlPill(labelOf(TezeractInput.TRIGGER_R), active[TezeractInput.TRIGGER_R])
            }
        }

        // Middle: live skeleton view of what the camera + MediaPipe see.
        SkeletonView(latestFrame, modifier = Modifier.size(320.dp, 240.dp))

        // Right: scrolling event feed
        Column(modifier = Modifier.widthIn(min = 220.dp, max = 300.dp)) {
            Text("event feed", color = Color(0xCCFFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .background(Color(0xFF1A1A24), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(log) { line ->
                    Text(line, color = Color(0xFFB0E0E6), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
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
private fun SkeletonView(latestFrame: State<MotionFrame?>, modifier: Modifier = Modifier) {
    val frame = latestFrame.value
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("motion capture", color = Color(0xCCFFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = modifier
                .background(Color(0xFF1A1A24), RoundedCornerShape(8.dp))
                .border(2.dp, Color(0xFF9333EA), RoundedCornerShape(8.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                val w = size.width
                val h = size.height
                val body = frame?.bodies?.firstOrNull()
                if (body == null) return@Canvas

                // Service already mirrors X for selfie semantics — render straight.
                val boneColor = Color(0xFF4ECDC4)
                val pointColor = Color(0xFFFFE66D)
                for ((a, b) in skeletonBones) {
                    val ka = body.keypoints.getOrNull(a) ?: continue
                    val kb = body.keypoints.getOrNull(b) ?: continue
                    if (ka.confidence < 0.3f || kb.confidence < 0.3f) continue
                    drawLine(
                        color = boneColor,
                        start = Offset(ka.x * w, ka.y * h),
                        end = Offset(kb.x * w, kb.y * h),
                        strokeWidth = 3f
                    )
                }
                for (kp in body.keypoints) {
                    if (kp.confidence < 0.3f) continue
                    drawCircle(pointColor, radius = 3f, center = Offset(kp.x * w, kp.y * h))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val label = if (frame == null || frame.bodyCount == 0) "no body detected"
                    else "body 0  •  ${frame.latencyMs.toInt()}ms"
        Text(label, color = Color(0xAAFFFFFF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Dpad(active: Map<TezeractInput, Float>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Pad(TezeractInput.DPAD_UP, active[TezeractInput.DPAD_UP])
        Row {
            Pad(TezeractInput.DPAD_LEFT, active[TezeractInput.DPAD_LEFT])
            Spacer(Modifier.size(64.dp))
            Pad(TezeractInput.DPAD_RIGHT, active[TezeractInput.DPAD_RIGHT])
        }
        Pad(TezeractInput.DPAD_DOWN, active[TezeractInput.DPAD_DOWN])
    }
}

@Composable
private fun ButtonCluster(active: Map<TezeractInput, Float>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Pad(TezeractInput.BUTTON_Y, active[TezeractInput.BUTTON_Y], shape = CircleShape, accent = Color(0xFFFFE66D))
        Row {
            Pad(TezeractInput.BUTTON_X, active[TezeractInput.BUTTON_X], shape = CircleShape, accent = Color(0xFF4ECDC4))
            Spacer(Modifier.size(64.dp))
            Pad(TezeractInput.BUTTON_B, active[TezeractInput.BUTTON_B], shape = CircleShape, accent = Color(0xFFFF6B6B))
        }
        Pad(TezeractInput.BUTTON_A, active[TezeractInput.BUTTON_A], shape = CircleShape, accent = Color(0xFF9333EA))
    }
}

@Composable
private fun Pad(
    input: TezeractInput,
    confidence: Float?,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    accent: Color = Color(0xFF9333EA)
) {
    val isActive = confidence != null
    val intensity = (confidence ?: 0f).coerceIn(0.3f, 1f)
    val fill = if (isActive) {
        Color(
            red = accent.red,
            green = accent.green,
            blue = accent.blue,
            alpha = intensity
        )
    } else Color(0xFF2A2A3A)
    val border = if (isActive) accent else Color(0xFF3A3A4A)
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(width = 84.dp, height = 64.dp)
            .clip(shape)
            .background(fill, shape)
            .border(2.dp, border, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = labelOf(input),
            color = if (isActive) Color.White else Color(0xAAFFFFFF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Names match the default InputBinding (`InputBinding.default()`). */
private fun labelOf(input: TezeractInput): String = when (input) {
    TezeractInput.DPAD_UP -> "JUMP"
    TezeractInput.DPAD_DOWN -> "SQUAT"
    TezeractInput.DPAD_LEFT -> "LEAN L"
    TezeractInput.DPAD_RIGHT -> "LEAN R"
    TezeractInput.BUTTON_A -> "CLAP"
    TezeractInput.BUTTON_B -> "RAISE R"
    TezeractInput.BUTTON_X -> "RAISE L"
    TezeractInput.BUTTON_Y -> "ARMS UP"
    TezeractInput.TRIGGER_L -> "GRAB L"
    TezeractInput.TRIGGER_R -> "GRAB R"
}

@Composable
private fun ControlPill(label: String, confidence: Float?) {
    val isActive = confidence != null
    val intensity = (confidence ?: 0f).coerceIn(0.3f, 1f)
    Box(
        modifier = Modifier
            .height(36.dp)
            .width(96.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isActive) Color(0xFF9333EA).copy(alpha = intensity) else Color(0xFF2A2A3A),
                RoundedCornerShape(18.dp)
            )
            .border(2.dp, if (isActive) Color(0xFF9333EA) else Color(0xFF3A3A4A), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
