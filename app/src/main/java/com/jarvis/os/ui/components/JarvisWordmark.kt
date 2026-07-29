package com.jarvis.os.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shadow
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.Orbitron

/**
 * The "JARVIS / SYSTEM" wordmark that anchors every one of the reference
 * designs, sitting over the middle of the orb.
 *
 * Cut from metal rather than filled flat: a vertical gradient runs from a white
 * specular top, through the theme's [JarvisPalette.wordmark] colour, into its
 * secondary at the base, which is what gives the letters the brushed look in the
 * images. A coloured shadow with no offset acts as the glow behind them.
 *
 * Drawn in code, which is how it stays centred. When it was baked into sprite
 * artwork it ran wider than the sprite's circular fade, so it was clipped on the
 * right and read as off-centre. Here it is centred by construction, nothing
 * clips it, and renaming the app is a code change rather than new art.
 *
 * Orbitron is the family already shipped with the app and is the same squared
 * techno face the designs use, so no new font was needed.
 */
@Composable
fun JarvisWordmark(
    modifier: Modifier = Modifier,
    palette: JarvisPalette = LocalPalette.current,
    /** Scales the whole mark; 1f is the home screen size. */
    scale: Float = 1f,
    showSubtitle: Boolean = true,
) {
    val brush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.96f),
            palette.wordmark,
            palette.wordmark,
            palette.secondary,
        ),
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = "JARVIS",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = Orbitron,
                fontWeight = FontWeight(900),
                fontSize = (38 * scale).sp,
                letterSpacing = (2 * scale).sp,
                brush = brush,
                shadow = Shadow(
                    color = palette.accent.copy(alpha = 0.75f),
                    offset = Offset.Zero,
                    blurRadius = 30f * scale,
                ),
            ),
        )
        if (showSubtitle) {
            Spacer(Modifier.height((2 * scale).dp))
            Text(
                text = "SYSTEM",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = Orbitron,
                    fontWeight = FontWeight(600),
                    fontSize = (15 * scale).sp,
                    // Wide tracking is what makes it read as a subtitle rather
                    // than a second word.
                    letterSpacing = (9 * scale).sp,
                    brush = brush,
                    shadow = Shadow(
                        color = palette.accent.copy(alpha = 0.55f),
                        offset = Offset.Zero,
                        blurRadius = 16f * scale,
                    ),
                ),
            )
        }
    }
}
