package com.jarvis.os.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.voice.OrbState

/**
 * The JARVIS orb. Its geometry comes from the chosen theme's [JarvisPalette
 * .orbStyle]; its colours, motion and reactivity are shared.
 *
 * Three independent clocks feed every style: a fast [spin], a slow [drift] and a
 * counter-rotation, plus a breathing pulse. Styles pick whichever they need, so
 * a lattice turns as one piece while a reactor churns in opposite directions at
 * four radii. Live microphone [amplitude] widens strokes and brightens cores, so
 * the orb visibly answers the room rather than looping regardless.
 *
 * Thinking spins everything faster — that is the state where the user is waiting
 * and wants to see something happening.
 */
@Composable
fun HudOrb(
    modifier: Modifier = Modifier,
    orb: OrbState = OrbState.Idle,
    amplitude: Float = 0f,
    size: Dp = 300.dp,
    palette: JarvisPalette = LocalPalette.current,
    showLabel: Boolean = true,
) {
    // The wordmark has to shrink with the orb or it overflows a preview card.
    val orbScale = (size / 280.dp).coerceIn(0.35f, 1.2f)
    // State recolours the accent; the theme decides what the base colour IS.
    val targetAccent = when (orb) {
        OrbState.Listening -> palette.accent
        OrbState.Thinking -> palette.secondary
        OrbState.Speaking -> SuccessGreen
        OrbState.Error -> ErrorRed
        else -> palette.accent.copy(alpha = 0.75f)
    }
    val accent by animateColorAsState(targetAccent, tween(400), label = "accent")

    val transition = rememberInfiniteTransition(label = "orb")
    val busy = orb == OrbState.Thinking
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (busy) 5000 else 13000, easing = LinearEasing)),
        label = "spin",
    )
    val drift by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (busy) 12000 else 30000, easing = LinearEasing)),
        label = "drift",
    )
    val counter by transition.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(if (busy) 9000 else 21000, easing = LinearEasing)),
        label = "counter",
    )
    val breathe by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    val amp = amplitude.coerceIn(0f, 1f)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOrbStyle(
                palette.orbStyle,
                OrbFrame(
                    radius = this.size.minDimension / 2f,
                    accent = accent,
                    secondary = palette.secondary,
                    highlight = palette.highlight,
                    spin = spin,
                    drift = drift,
                    counter = counter,
                    breathe = breathe,
                    amp = amp,
                ),
            )
        }

        // The wordmark sits over the middle of the orb, as in every design.
        if (showLabel) {
            JarvisWordmark(palette = palette, scale = orbScale)
        }
    }
}

/**
 * A small, self-contained orb for the theme picker: same renderer, same motion,
 * fixed to one palette. The preview must move, or a still swatch cannot show the
 * difference between six animated designs.
 */
@Composable
fun OrbPreview(palette: JarvisPalette, size: Dp = 92.dp, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalPalette provides palette) {
        HudOrb(
            modifier = modifier,
            orb = OrbState.Listening,
            amplitude = 0f,
            size = size,
            palette = palette,
            showLabel = false,
        )
    }
}
