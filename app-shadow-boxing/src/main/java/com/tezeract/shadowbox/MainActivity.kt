package com.tezeract.shadowbox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.tezeract.sdk.TezeractMotion
import com.tezeract.shadowbox.motion.MotionInputBridge
import com.tezeract.shadowbox.ui.BoxColors
import com.tezeract.shadowbox.ui.FightModeScreen
import com.tezeract.shadowbox.ui.ModePickerScreen
import com.tezeract.shadowbox.ui.ReactionModeScreen
import com.tezeract.shadowbox.ui.TrainingModeScreen
import com.tezeract.shadowbox.ui.TutorialModeScreen

enum class Mode { Picker, Tutorial, Reaction, Training, Fight }

class MainActivity : ComponentActivity() {

    private lateinit var motionBridge: MotionInputBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        TezeractMotion.initialize(this)
        motionBridge = MotionInputBridge(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BoxColors.Background) {
                    var mode by remember { mutableStateOf(Mode.Picker) }

                    // Only the picker uses the focus-based input bridge — gameplay
                    // screens consume raw motion via PunchDetector and shouldn't
                    // emit DPAD events (a lean isn't navigation in the ring).
                    DisposableEffect(mode) {
                        if (mode == Mode.Picker) motionBridge.attach() else motionBridge.detach()
                        onDispose { motionBridge.detach() }
                    }

                    GymBackdrop {
                        when (mode) {
                            Mode.Picker -> ModePickerScreen(onPick = { mode = it })
                            Mode.Tutorial -> TutorialModeScreen(onExit = { mode = Mode.Picker })
                            Mode.Reaction -> ReactionModeScreen(onExit = { mode = Mode.Picker })
                            Mode.Training -> TrainingModeScreen(onExit = { mode = Mode.Picker })
                            Mode.Fight -> FightModeScreen(onExit = { mode = Mode.Picker })
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        motionBridge.detach()
        TezeractMotion.release()
        super.onDestroy()
    }
}

@Composable
private fun GymBackdrop(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(BoxColors.SurfaceVariant.copy(alpha = 0.7f), BoxColors.Background),
                    radius = 1200f,
                )
            )
    ) { content() }
}
