package com.jarvis.os.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.OrbStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The orb, opened out.
 *
 * The brief: *"if i try and expand the orb like how u do with images on a screen,
 * it should be like a galaxy kind of thing, i can keep going deeper and deeper"*.
 * So the gesture is the one everybody already knows — pinch the orb apart — and
 * what it opens is not a screen with a picture of space on it but a place with a
 * depth you can travel through.
 *
 * The arithmetic lives in [UniverseMath] and is unit-tested; this file only draws
 * what that decides. The split is not tidiness. The renderer cannot be run in the
 * session that writes it — no SDK, no device, and Compose does not even compile
 * here — so everything that could be *wrong* rather than merely ugly was moved
 * into arithmetic that can be run: where each shell sits, how bright it is, and
 * whether the picture is continuous across a level boundary. That gate has
 * already earned it twice, catching a shell that flickered at every seam and a
 * core left burning over the level below it.
 *
 * What is left here is only appearance, which is the part a screenshot settles.
 */
@Composable
fun OrbUniverse(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    palette: JarvisPalette = LocalPalette.current,
    amplitude: Float = 0f,
) {
    val scope = rememberCoroutineScope()
    // One number holds the entire position in the universe. An Animatable rather
    // than a plain Float because the same value has to be draggable by a pinch
    // AND flown by a double-tap, and those cannot be two separate states without
    // them disagreeing the moment you do both.
    val zoom = remember { Animatable(UniverseMath.START_ZOOM) }
    // Where the pinch has got to, held separately from what is on screen.
    //
    // `snapTo` is a suspend function, so a gesture callback cannot apply it and
    // read the result in the same frame — reading `zoom.value` to compute the
    // next position would keep reading the value from before the launches that
    // are still queued, and a fast pinch would silently drop most of its travel.
    // The accumulator is updated synchronously; the Animatable only renders it.
    var target by remember { mutableFloatStateOf(UniverseMath.START_ZOOM) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var closing by remember { mutableStateOf(false) }

    fun close() {
        if (!closing) {
            closing = true
            onClose()
        }
    }

    BackHandler(enabled = true) { close() }

    // The clock everything turns on. Long period: each satellite multiplies it by
    // its own speed, so the visible motion comes from the spread, not the rate.
    val transition = rememberInfiniteTransition(label = "universe")
    val clock by transition.animateFloat(
        0f, OrbMath.TAU,
        infiniteRepeatable(tween(48_000, easing = LinearEasing)),
        label = "clock",
    )
    // A slow swell so a shell never sits perfectly still even between gestures.
    val breathe by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(5200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    val z = zoom.value
    val depth = UniverseMath.depthOf(z)
    val fraction = UniverseMath.fractionOf(z)
    // Four shells, rebuilt only when the depth counter ticks — once per level,
    // not once per frame. Generating them inside the draw would allocate a list
    // of satellites sixty times a second for something that changes once every
    // few seconds, which is the allocation mistake OrbDetail exists to remember.
    val shells = remember(depth) {
        UniverseMath.SHELLS.associateWith { UniverseMath.shellAt(UniverseMath.seedFor(depth, it)) }
    }

    val amp = amplitude.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ThemeArt.backdrop(palette.orbStyle).copy(alpha = 0.60f))
            .background(Color(0xFF03060E).copy(alpha = 0.86f))
            // The tap detector goes FIRST so the transform detector below it is
            // the inner one and sees a second finger before anything else does.
            // The other order lets the double-tap detector spend its timeout
            // deciding while a pinch is already under way.
            .pointerInput(Unit) {
                detectTapGestures(
                    // A dive per double-tap, because a phone held one-handed
                    // cannot pinch, and this is the whole feature.
                    onDoubleTap = {
                        target = floor(zoom.value) + 1f
                        scope.launch {
                            zoom.animateTo(target, tween(900, easing = FastOutSlowInEasing))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, gestureZoom, _ ->
                    // A pinch reports a MULTIPLIER, and depth is measured in
                    // powers of SCALE, so the conversion is a logarithm. Adding
                    // the raw multiplier instead would make one pinch mean
                    // wildly different amounts of travel depending on how deep
                    // you already were.
                    if (gestureZoom > 0f) {
                        // Taking the live value while a double-tap dive is still
                        // flying means grabbing hold of it wherever it has got
                        // to, rather than snapping to where it was going to end.
                        val from = if (zoom.isRunning) zoom.value else target
                        target = from + ln(gestureZoom) / LN_SCALE
                        scope.launch { zoom.snapTo(target) }
                        if (UniverseMath.shouldClose(target)) close()
                    }
                    val limit = minOf(size.width, size.height) * 0.45f
                    pan = Offset(
                        UniverseMath.clampPan(pan.x + panChange.x, limit),
                        UniverseMath.clampPan(pan.y + panChange.y, limit),
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.46f
            val eye = Offset(center.x + pan.x, center.y + pan.y)

            drawDeepField(clock, palette.accent)

            // Far to near, so a nearer shell overlays the one behind it. The
            // range runs parent-first, so it is walked backwards.
            for (j in UniverseMath.SHELLS.reversed()) {
                val level = UniverseMath.levelOf(j, fraction)
                val alpha = UniverseMath.shellAlpha(level)
                if (alpha <= 0.004f) continue
                val spec = shells[j] ?: continue
                drawShell(
                    spec = spec,
                    style = palette.orbStyle,
                    at = eye,
                    radius = base * UniverseMath.shellScale(level) * (1f + breathe * 0.012f + amp * 0.04f),
                    alpha = alpha,
                    core = UniverseMath.coreGlow(level),
                    clock = clock,
                    accent = palette.accent,
                    highlight = palette.highlight,
                    secondary = palette.secondary,
                    viewport = base,
                )
            }
        }

        UniverseHud(
            depth = depth,
            fraction = fraction,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The readout. Without it a descent of ten levels and a descent of one look the
 * same, because the structure is self-similar by construction — the label is the
 * only thing that says the travelling went anywhere.
 */
@Composable
private fun UniverseHud(
    depth: Int,
    fraction: Float,
    palette: JarvisPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.systemBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = UniverseMath.labelFor(depth),
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 6.sp),
                color = palette.highlight,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "DEPTH ${depth.coerceAtLeast(0)}·${(fraction * 100).toInt().toString().padStart(2, '0')}",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = palette.accent.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Text(
            text = "PINCH TO DIVE  ·  DOUBLE-TAP TO FALL  ·  PINCH BACK TO SURFACE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = palette.accent.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One structure, at whatever size the zoom has put it.
 *
 * Every shell is drawn by the same function on purpose. Self-similarity is what
 * makes the depth endless — if levels were authored individually they would run
 * out — so what has to differ between them is the *seed*, not the code. The four
 * [ShellKind]s exist so that similar does not become identical.
 */
private fun DrawScope.drawShell(
    spec: ShellSpec,
    style: OrbStyle,
    at: Offset,
    radius: Float,
    alpha: Float,
    core: Float,
    clock: Float,
    accent: Color,
    highlight: Color,
    secondary: Color,
    viewport: Float,
) {
    // Detail follows apparent size, not shell index. A shell can be anywhere from
    // a knot a few pixels across to three screens wide, and drawing 220 dust
    // motes into a nine-pixel dot costs exactly as much as drawing them into a
    // full-screen galaxy while showing none of it.
    val presence = (radius / viewport).coerceIn(0f, 1.6f)
    val turn = clock * 0.35f + spec.tilt * 3f
    val squash = 0.30f + abs(cos(spec.tilt)) * 0.62f

    // The halo. Everything else is drawn additively on top of it, so it is what
    // stops a shell reading as line art floating on black.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                accent.copy(alpha = 0.16f * alpha),
                secondary.copy(alpha = 0.06f * alpha),
                Color.Transparent,
            ),
            center = at,
            radius = radius * 1.25f,
        ),
        radius = radius * 1.25f,
        center = at,
    )

    when (spec.kind) {
        ShellKind.Spiral -> drawArms(spec, at, radius, alpha, presence, turn, squash, style, accent, highlight)
        ShellKind.Ringed -> drawDisc(spec, at, radius, alpha, presence, turn, squash, style, accent, highlight)
        ShellKind.Cluster -> drawSwarm(spec, at, radius, alpha, presence, turn, style, accent, highlight)
        ShellKind.Binary -> drawBinary(spec, at, radius, alpha, presence, clock, style, accent, highlight)
    }

    // Orbits and their bodies, on top of whatever the structure was.
    spec.satellites.forEach { moon ->
        drawSatellite(moon, at, radius, alpha, presence, clock, turn, accent, highlight)
    }

    if (core > 0.004f) {
        drawCore(at, radius * spec.coreSize, alpha * core, highlight, accent)
    }
}

/** Spiral arms: dust on a logarithmic curve, which is the shape a galaxy has. */
private fun DrawScope.drawArms(
    spec: ShellSpec,
    at: Offset,
    radius: Float,
    alpha: Float,
    presence: Float,
    turn: Float,
    squash: Float,
    style: OrbStyle,
    accent: Color,
    highlight: Color,
) {
    // Every scatter below is hashed with this, so two shells on screen at
    // once are two different places rather than the same one twice.
    val scatter0 = spec.seed * 7919
    val arms = 2 + (abs(spec.armTwist).toInt() % 2)
    val count = (spec.dust * presence * alpha).toInt()
    for (i in 0 until count) {
        val arm = i % arms
        // Distance is a square so motes bunch toward the hub, where a galaxy's
        // light actually is. Evenly spread dust reads as noise, not structure.
        val f = OrbMath.unitRandom(scatter0 + i * 3 + 11).let { it * it }
        val r = radius * (0.10f + f * 0.98f)
        val scatter = (OrbMath.unitRandom(scatter0 + i * 7 + 5) - 0.5f) * 0.55f * (1f - f * 0.6f)
        val a = turn + arm * (OrbMath.TAU / arms) + f * spec.armTwist + scatter
        val p = Offset(at.x + cos(a) * r, at.y + sin(a) * r * squash)
        drawCircle(
            color = tint(style, f, accent, highlight, scatter0 + i)
                .copy(alpha = alpha * (0.14f + (1f - f) * 0.55f) * OrbMath.range(scatter0 + i * 5 + 2, 0.4f, 1f)),
            radius = 0.6f + OrbMath.unitRandom(scatter0 + i * 13 + 3) * 2.1f * presence,
            center = p,
            blendMode = BlendMode.Plus,
        )
    }
}

/** A broad flat disc of debris, seen at a tilt — the ringed-world shape. */
private fun DrawScope.drawDisc(
    spec: ShellSpec,
    at: Offset,
    radius: Float,
    alpha: Float,
    presence: Float,
    turn: Float,
    squash: Float,
    style: OrbStyle,
    accent: Color,
    highlight: Color,
) {
    // Every scatter below is hashed with this, so two shells on screen at
    // once are two different places rather than the same one twice.
    val scatter0 = spec.seed * 7919
    // Concentric ovals rather than a filled shape: the gaps between them are what
    // make it read as debris in orbit instead of a painted ellipse.
    val bands = (5 + presence * 9).toInt()
    withTransform({
        rotate(turn * 18f, at)
        scale(1f, squash.coerceAtLeast(0.14f), at)
    }) {
        for (b in 0 until bands) {
            val f = 0.42f + (b.toFloat() / bands) * 0.62f
            val r = radius * f
            drawCircle(
                color = tint(style, f, accent, highlight, scatter0 + b)
                    .copy(alpha = alpha * 0.30f * OrbMath.range(scatter0 + b * 9 + 4, 0.35f, 1f)),
                radius = r,
                center = at,
                style = Stroke(px(0.7f + presence * 1.4f)),
                blendMode = BlendMode.Plus,
            )
        }
    }
    val count = (spec.dust * presence * alpha).toInt()
    for (i in 0 until count) {
        val f = OrbMath.range(scatter0 + i * 11 + 7, 0.42f, 1.04f)
        val a = turn * 1.4f + OrbMath.unitRandom(scatter0 + i * 17 + 9) * OrbMath.TAU
        val p = Offset(at.x + cos(a) * radius * f, at.y + sin(a) * radius * f * squash)
        drawCircle(
            color = highlight.copy(alpha = alpha * OrbMath.range(scatter0 + i * 3 + 1, 0.15f, 0.6f)),
            radius = 0.5f + presence * 1.3f,
            center = p,
            blendMode = BlendMode.Plus,
        )
    }
}

/** No structure at all — the loose swarm a globular cluster is. */
private fun DrawScope.drawSwarm(
    spec: ShellSpec,
    at: Offset,
    radius: Float,
    alpha: Float,
    presence: Float,
    turn: Float,
    style: OrbStyle,
    accent: Color,
    highlight: Color,
) {
    // Every scatter below is hashed with this, so two shells on screen at
    // once are two different places rather than the same one twice.
    val scatter0 = spec.seed * 7919
    val count = (spec.dust * presence * alpha).toInt()
    for (i in 0 until count) {
        // Two multiplied uniforms approximate a central concentration without
        // needing a real normal distribution, which is what a swarm looks like.
        val f = OrbMath.unitRandom(scatter0 + i * 5 + 3) * OrbMath.unitRandom(scatter0 + i * 29 + 13)
        val a = OrbMath.unitRandom(scatter0 + i * 7 + 1) * OrbMath.TAU + turn * (0.4f + f)
        val r = radius * (0.06f + f * 1.02f)
        drawCircle(
            color = tint(style, f, accent, highlight, scatter0 + i)
                .copy(alpha = alpha * (0.18f + (1f - f) * 0.6f)),
            radius = 0.6f + OrbMath.unitRandom(scatter0 + i * 3 + 8) * 2.3f * presence,
            center = Offset(at.x + cos(a) * r, at.y + sin(a) * r),
            blendMode = BlendMode.Plus,
        )
    }
}

/** Two bodies about a common centre, with the space between them swept clean. */
private fun DrawScope.drawBinary(
    spec: ShellSpec,
    at: Offset,
    radius: Float,
    alpha: Float,
    presence: Float,
    clock: Float,
    style: OrbStyle,
    accent: Color,
    highlight: Color,
) {
    // Every scatter below is hashed with this, so two shells on screen at
    // once are two different places rather than the same one twice.
    val scatter0 = spec.seed * 7919
    val a = clock * 0.55f + spec.tilt
    val sep = radius * 0.30f
    listOf(0f, OrbMath.PI_F).forEachIndexed { i, phase ->
        val p = Offset(at.x + cos(a + phase) * sep, at.y + sin(a + phase) * sep * 0.55f)
        val colour = if (i == 0) highlight else accent
        drawCircle(
            brush = Brush.radialGradient(
                listOf(colour.copy(alpha = alpha * 0.75f), colour.copy(alpha = alpha * 0.18f), Color.Transparent),
                center = p,
                radius = radius * 0.30f,
            ),
            radius = radius * 0.30f,
            center = p,
            blendMode = BlendMode.Plus,
        )
    }
    // A thin stream of matter drawn between them — the thing that says these two
    // are a pair rather than two bodies that happen to be near each other.
    val count = (spec.dust * presence * alpha).toInt()
    for (i in 0 until count) {
        val t = OrbMath.unitRandom(scatter0 + i * 13 + 5)
        val swing = a + t * OrbMath.PI_F + (OrbMath.unitRandom(scatter0 + i * 7 + 2) - 0.5f) * 0.5f
        val r = sep * (0.5f + OrbMath.unitRandom(scatter0 + i * 3 + 9) * 1.7f)
        drawCircle(
            color = tint(style, t, accent, highlight, scatter0 + i).copy(alpha = alpha * 0.35f),
            radius = 0.5f + presence * 1.2f,
            center = Offset(at.x + cos(swing) * r, at.y + sin(swing) * r * 0.6f),
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * One body on its orbit, with the ellipse it travels.
 *
 * The body is shaded by where it is on that ellipse: bright and large on the near
 * half, dim and small on the far half. That single cue is what makes the orbit
 * read as a circle lying in space rather than as an oval drawn on the glass.
 */
private fun DrawScope.drawSatellite(
    moon: Satellite,
    at: Offset,
    radius: Float,
    alpha: Float,
    presence: Float,
    clock: Float,
    turn: Float,
    accent: Color,
    highlight: Color,
) {
    val r = radius * moon.orbit
    if (r < 3f) return
    val squash = (0.16f + abs(cos(moon.tilt)) * 0.80f)
    val tilt = turn * 0.4f + moon.tilt * 40f
    val colour = lerpColour(accent, highlight, moon.warmth)

    withTransform({
        rotate(tilt, at)
        scale(1f, squash, at)
    }) {
        drawCircle(
            color = colour.copy(alpha = alpha * 0.22f * presence.coerceAtMost(1f)),
            radius = r,
            center = at,
            style = Stroke(px(0.8f)),
            blendMode = BlendMode.Plus,
        )
    }

    val a = moon.phase + clock * moon.speed
    // Position in the orbit's own plane, then tipped into the screen's.
    val ox = cos(a) * r
    val oy = sin(a) * r * squash
    val rad = tilt * (OrbMath.TAU / 360f)
    val p = Offset(
        at.x + ox * cos(rad) - oy * sin(rad),
        at.y + ox * sin(rad) + oy * cos(rad),
    )
    // sin(a) > 0 is the half of the orbit nearer the camera.
    val near = 0.5f + sin(a) * 0.5f
    val body = radius * moon.size * (0.65f + near * 0.6f)
    if (body < 0.6f) return
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = alpha * (0.35f + near * 0.5f)),
                colour.copy(alpha = alpha * (0.25f + near * 0.4f)),
                Color.Transparent,
            ),
            center = p,
            radius = body * 2.4f,
        ),
        radius = body * 2.4f,
        center = p,
        blendMode = BlendMode.Plus,
    )
    if (presence > 0.55f && near > 0.85f) {
        flare(p, body * 2.0f, Color.White, alpha * 0.30f)
    }
}

/** The hot point at a shell's centre — the thing you aim the next dive at. */
private fun DrawScope.drawCore(at: Offset, radius: Float, alpha: Float, highlight: Color, accent: Color) {
    val r = radius.coerceAtLeast(1.5f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = alpha * 0.90f),
                highlight.copy(alpha = alpha * 0.55f),
                accent.copy(alpha = alpha * 0.20f),
                Color.Transparent,
            ),
            center = at,
            radius = r * 3.0f,
        ),
        radius = r * 3.0f,
        center = at,
        blendMode = BlendMode.Plus,
    )
    flare(at, r * 1.8f, Color.White, alpha * 0.45f)
}

/**
 * The stars behind everything, fixed to the screen rather than to any shell.
 *
 * They do not move with the zoom on purpose. Something has to stay still, or the
 * whole frame scales together and the dive reads as a picture being enlarged
 * instead of as travel through a space that keeps existing around you.
 */
private fun DrawScope.drawDeepField(clock: Float, accent: Color) {
    for (i in 0 until 150) {
        val x = OrbMath.unitRandom(i * 2 + 1) * size.width
        val y = OrbMath.unitRandom(i * 2 + 2) * size.height
        val twinkle = 0.35f + 0.65f * abs(sin(clock * OrbMath.range(i, 0.6f, 2.4f) + i))
        drawCircle(
            color = (if (i % 9 == 0) accent else Color.White).copy(
                alpha = OrbMath.range(i * 3 + 7, 0.06f, 0.34f) * twinkle,
            ),
            radius = OrbMath.range(i * 5 + 4, 0.5f, 1.7f),
            center = Offset(x, y),
        )
    }
}

/** Colour at [fraction] of the way out, from the theme's own measured ramp. */
private fun tint(
    style: OrbStyle,
    fraction: Float,
    accent: Color,
    highlight: Color,
    seed: Int,
): Color {
    val sampled = ThemeArt.at(style, fraction)
    val live = lerpColour(accent, highlight, OrbMath.unitRandom(seed * 19 + 6))
    return lerpColour(sampled, live, 0.42f)
}

private fun lerpColour(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = 1f,
    )
}

/**
 * Pinch the orb apart to open [OrbUniverse].
 *
 * Deliberately NOT `detectTransformGestures`, for two reasons that both matter
 * here. That detector engages on a single-finger drag, and this orb sits inside a
 * vertically scrolling column — it would eat the scroll. And it reports a zoom
 * multiplier every frame, so it fires on the tiniest incidental spread.
 *
 * This waits for a genuine second finger, measures how far apart the two have
 * been pulled since they landed, and only then claims the gesture. Below the
 * threshold nothing is consumed, so a scroll that happens to involve two fingers
 * still scrolls.
 */
fun Modifier.pinchToOpen(onOpen: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var start = 0f
        var opened = false
        while (true) {
            val event = awaitPointerEvent()
            val down = event.changes.filter { it.pressed }
            if (down.size < 2) {
                if (down.isEmpty()) break
                // A finger was lifted: the next pair starts its own measurement,
                // rather than inheriting a stale separation.
                start = 0f
                continue
            }
            val spread = (down[0].position - down[1].position).getDistance()
            if (start <= 0f) {
                start = spread
            } else if (!opened && spread / start >= PINCH_OPEN_RATIO) {
                opened = true
                onOpen()
            }
            if (opened || spread / start > PINCH_CLAIM_RATIO) {
                down.forEach { it.consume() }
            }
        }
    }
}

private val LN_SCALE = ln(UniverseMath.SCALE)

/** How far apart two fingers must travel before the orb opens. */
private const val PINCH_OPEN_RATIO = 1.30f

/**
 * When to start consuming events. Lower than [PINCH_OPEN_RATIO] so the scroll
 * underneath has already let go by the time the universe appears — claiming both
 * at the same instant leaves the list mid-fling behind the new screen.
 */
private const val PINCH_CLAIM_RATIO = 1.12f
