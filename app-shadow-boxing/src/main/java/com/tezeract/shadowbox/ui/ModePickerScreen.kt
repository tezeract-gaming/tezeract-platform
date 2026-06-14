package com.tezeract.shadowbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.tezeract.input.TezeractInput
import com.tezeract.shadowbox.Mode

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ModePickerScreen(onPick: (Mode) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("SHADOW BOXING", color = BoxColors.OnDark, fontSize = 42.sp,
                fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Text("pick your training", color = BoxColors.Accent, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            Spacer(Modifier.height(28.dp))
            // Single row — Pi screen is 853×480dp, a 2×2 grid clips. Tutorial
            // is leftmost so new players' eyes land there first.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ModeTile("TUTORIAL", "Learn each punch", "✓",
                    BoxColors.SuccessGreen, onClick = { onPick(Mode.Tutorial) })
                ModeTile("REACTION", "Hit targets in time", "◎",
                    BoxColors.Cool, onClick = { onPick(Mode.Reaction) })
                ModeTile("TRAINING", "Coach calls combos", "★",
                    BoxColors.Accent, onClick = { onPick(Mode.Training) })
                ModeTile("FIGHT", "Block, dodge, KO", "✦",
                    BoxColors.Primary, onClick = { onPick(Mode.Fight) })
            }
            Spacer(Modifier.height(20.dp))
            Text("raise an arm to focus  ·  clap to select  ·  triangle to exit",
                color = BoxColors.OnDarkMuted, fontSize = 12.sp)
        }

        // Reusable HUD — shows the user their outline plus the active controls.
        BoxHUD(
            controls = listOf(
                BoxHint("Left arm  ←  scroll", TezeractInput.DPAD_LEFT),
                BoxHint("Right arm  →  scroll", TezeractInput.DPAD_RIGHT),
                BoxHint("Clap to select", TezeractInput.BUTTON_A),
                BoxHint("Triangle = exit", null),
            ),
            modifier = Modifier
                .align(AbsoluteAlignment.TopRight)
                .padding(16.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModeTile(title: String, subtitle: String, glyph: String, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(174.dp, 200.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(18.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.35f))
                    )
                )
                .border(2.dp, accent, RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(glyph, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Black)
                Column {
                    Text(title, color = Color.White, fontSize = 19.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                }
            }
        }
    }
}
