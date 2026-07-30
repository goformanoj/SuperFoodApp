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
import androidx.compose.runtime.remember
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
 * The JARVIS orb: rings built in three dimensions and rendered every frame.
 *
 * Four approaches, and the reason for this one is worth keeping. Flat vector
 * shapes never resembled the references. Shipping the reference renders as
 * sprites was an exact likeness that could not move. Slicing those sprites into
 * bands and rotating each sheared them into wedges — a flat photograph has no
 * depth to turn through — and the user's verdict was plain: "the rings don't
 * look natural… absolutely horrible. Can you not make proper 3D rings."
 *
 * So the rings are real geometry now: circles in space, each tilted, precessing
 * and spinning on its own clock, projected through a perspective camera and
 * shaded by depth, with every stroke blended additively so crossing rings bloom.
 * The images went with it — no drawables, nothing to scale or crop, and the
 * whole orb is a few hundred lines that run at any size.
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
    val targetAccent = when (orb) {
        OrbState.Listening -> palette.accent
        OrbState.Thinking -> palette.secondary
        OrbState.Speaking -> SuccessGreen
        OrbState.Error -> ErrorRed
        else -> palette.accent.copy(alpha = 0.85f)
    }
    val accent by animateColorAsState(targetAccent, tween(400), label = "accent")

    val transition = rememberInfiniteTransition(label = "orb")
    val busy = orb == OrbState.Thinking
    // One master clock. Every ring multiplies it, so they keep a fixed
    // relationship however fast the whole assembly is running. Long period,
    // because each ring's own multiplier does the visible work.
    val drift by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (busy) 7000 else 20000, easing = LinearEasing)),
        label = "drift",
    )
    val breathe by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    val amp = amplitude.coerceIn(0f, 1f)
    // Held across frames: the picker draws six of these at once, and rebuilding
    // ring geometry into fresh lists every frame is what made Settings lag.
    // Keyed on the quality BAND, not the size — a selected card animates its orb
    // between 88dp and 104dp, and keying on size would rebuild the buffers on
    // every frame of that animation.
    val quality = OrbQuality.forSize(size)
    val detail = remember(quality) { OrbDetail(quality) }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOrb3D(
                style = palette.orbStyle,
                detail = detail,
                f = OrbFrame(
                    radius = this.size.minDimension / 2f * 0.86f,
                    accent = accent,
                    secondary = palette.secondary,
                    highlight = palette.highlight,
                    spin = drift,
                    drift = drift,
                    counter = -drift,
                    breathe = breathe,
                    amp = amp,
                ),
                accent = accent,
                highlight = palette.highlight,
                secondary = palette.secondary,
            )
        }

        if (showLabel) {
            JarvisWordmark(palette = palette, scale = (size / 280.dp).coerceIn(0.30f, 1.15f))
        }
    }
}

/**
 * A small orb for the theme picker. The same geometry at a smaller radius —
 * being procedural, it simply scales, with none of the cropping a sprite needed.
 * The wordmark is dropped: at this size it would be an illegible smear across
 * the design the card exists to show.
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
