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
    /**
     * Mic level, as a LAMBDA rather than a value.
     *
     * The microphone reports many times a second. Taken as a `Float` parameter,
     * every report recomposed this composable and everything above it that had to
     * pass the new value down — which reached the whole app, because the state it
     * lived in is read once at the top of `setContent`. Taken as a lambda and
     * called inside the Canvas, the same report invalidates one draw.
     */
    amplitude: () -> Float = { 0f },
    size: Dp = 300.dp,
    palette: JarvisPalette = LocalPalette.current,
    showLabel: Boolean = true,
    /**
     * Whether this orb runs its clocks.
     *
     * The theme picker draws one of these per theme, and every one of them was
     * animating whether or not it was the one being looked at — seven infinite
     * transitions each invalidating a Canvas at 60fps, which is what made the
     * Settings screen lag badly enough for the user to raise it twice. Only the
     * selected card moves now.
     *
     * The trade is real and worth naming: three of these themes differ mainly in
     * how they MOVE, so a still card shows less than a moving one. Tapping a card
     * selects it and it starts moving, which keeps that information reachable at
     * the cost of one tap instead of a permanently janky screen.
     */
    animated: Boolean = true,
) {
    val targetAccent = when (orb) {
        OrbState.Listening -> palette.accent
        OrbState.Thinking -> palette.secondary
        OrbState.Speaking -> SuccessGreen
        OrbState.Error -> ErrorRed
        else -> palette.accent.copy(alpha = 0.85f)
    }
    val accent by animateColorAsState(targetAccent, tween(400), label = "accent")

    val busy = orb == OrbState.Thinking
    // One master clock. Every ring multiplies it, so they keep a fixed
    // relationship however fast the whole assembly is running. Long period,
    // because each ring's own multiplier does the visible work.
    // The transition is only created when it will actually be used. A
    // rememberInfiniteTransition that exists but is ignored still schedules
    // frames, so gating the VALUES rather than the transition would have saved
    // nothing.
    val drift: Float
    val breathe: Float
    if (animated) {
        val transition = rememberInfiniteTransition(label = "orb")
        drift = transition.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(if (busy) 7000 else 20000, easing = LinearEasing)),
            label = "drift",
        ).value
        breathe = transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe",
        ).value
    } else {
        // A frozen frame chosen off the zero mark, so a still card shows rings
        // crossing rather than the degenerate moment where they all line up.
        drift = STILL_DRIFT
        breathe = STILL_BREATHE
    }

    // Held across frames: the picker draws six of these at once, and rebuilding
    // ring geometry into fresh lists every frame is what made Settings lag.
    // Keyed on the quality BAND, not the size — a selected card animates its orb
    // between 88dp and 104dp, and keying on size would rebuild the buffers on
    // every frame of that animation.
    val quality = OrbQuality.forSize(size)
    val detail = remember(quality) { OrbDetail(quality) }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // READ HERE, inside the draw, and nowhere above it. Calling the lambda
            // in composition would put the recomposition back exactly where it was.
            val amp = amplitude().coerceIn(0f, 1f)
            drawOrb3D(
                style = palette.orbStyle,
                detail = detail,
                f = OrbFrame(
                    // Sized so the WIDEST part of this theme's orb, at the worst
                    // phase of its precession, still lands inside the frame.
                    //
                    // It was a flat 0.86 for every theme, which is fine until a
                    // design puts a ring outside the body: Orbit's disc reached
                    // 1.46 half-frames once perspective magnified its near side,
                    // and shipped cut off at both edges. `fitFor` measures that
                    // per theme and only shrinks a design that would actually
                    // clip — none of the four does today, so this changes nothing
                    // on screen and stops the next retune from doing it again.
                    radius = this.size.minDimension / 2f * fitFor(palette.orbStyle),
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
            // Scaled by the orb's FIT as well as its box, so a theme drawn
            // smaller to fit its rings keeps the wordmark in proportion to its
            // body. Orbit is drawn at 0.657 against everyone else's 0.86; a
            // wordmark sized only by the box would overhang a body it used to
            // sit inside.
            val fit = fitFor(palette.orbStyle) / PREFERRED_FILL
            JarvisWordmark(
                palette = palette,
                scale = ((size / 280.dp) * fit).coerceIn(0.30f, 1.15f),
            )
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
fun OrbPreview(
    palette: JarvisPalette,
    size: Dp = 92.dp,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    CompositionLocalProvider(LocalPalette provides palette) {
        HudOrb(
            modifier = modifier,
            orb = OrbState.Listening,
            amplitude = { 0f },
            size = size,
            palette = palette,
            showLabel = false,
            animated = animated,
        )
    }
}

/** Where a still orb is frozen — mid-crossing, not at the aligned zero mark. */
private const val STILL_DRIFT = 84f
private const val STILL_BREATHE = 0.45f
