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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
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
    /**
     * How far through the arrival this is, `0f..1f`, driven by the host so the
     * page and the thing it grew out of move as one. Used for the entry bloom
     * and to hold the readout back until there is something to read.
     */
    entry: Float = 1f,
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
    // Until the arrival has finished, a pinch cannot dismiss the view.
    //
    // Not defensive: the entrance STARTS below zero and flies inward, and
    // [UniverseMath.ENTRY_ZOOM] is deliberately kept above [UniverseMath.CLOSE_AT]
    // so that alone cannot trigger a dismissal. But a pinch landing during the
    // first half-second would be read against a zoom that is still arriving, and
    // closing a view the moment it opens is the worst possible answer to a
    // gesture the user almost certainly meant as "go deeper".
    var settled by remember { mutableStateOf(false) }

    // Which dimension we are inside. Null means the star map is still showing:
    // the universe now opens on a CHOICE, not on a structure.
    //
    // "begin with different kinds of stars on the screen, and depending on which
    // star the user clicks it goes into that dimension". Every star mixes its own
    // branch into every seed below it, so the six kinds are not six labels on the
    // same place — pick a different star and nothing at any depth repeats.
    var chosen by remember { mutableStateOf<StarSpec?>(null) }
    val stars = remember { UniverseMath.starMap() }
    // How far through the flight into the chosen star, 0..1. Held separately from
    // the zoom because the map has to keep drawing, receding, while the first
    // shell of the new dimension grows out of the star that was touched.
    val enterStar = remember { Animatable(0f) }

    fun close() {
        if (!closing) {
            closing = true
            onClose()
        }
    }

    BackHandler(enabled = true) {
        // Same two steps as pinching out, for the same reason.
        if (chosen != null) {
            chosen = null
            target = UniverseMath.START_ZOOM
            scope.launch { zoom.snapTo(UniverseMath.START_ZOOM) }
        } else {
            close()
        }
    }

    // The arrival, as MOVEMENT rather than as an appearance. The host grows this
    // page out of the orb; this flies the camera inward at the same time, so the
    // first thing that happens after the pinch is a shell rushing up to meet you
    // rather than a picture settling into place. Landing exactly on START_ZOOM
    // means the dive begins from a known position however it was entered.
    LaunchedEffect(chosen) {
        val star = chosen
        if (star == null) {
            // Back on the map: nothing is arriving, and a pinch means "leave".
            enterStar.snapTo(0f)
            settled = true
            return@LaunchedEffect
        }
        settled = false
        zoom.snapTo(UniverseMath.ENTRY_ZOOM)
        target = UniverseMath.START_ZOOM
        // The two run together: the map falls away as the dimension comes up.
        enterStar.snapTo(0f)
        scope.launch { enterStar.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        zoom.animateTo(UniverseMath.START_ZOOM, tween(900, easing = FastOutSlowInEasing))
        settled = true
    }

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
    val branch = chosen?.branch ?: UniverseMath.NO_BRANCH
    val shells = remember(depth, branch) {
        UniverseMath.SHELLS.associateWith {
            UniverseMath.shellAt(UniverseMath.seedFor(branch, depth, it))
        }
    }

    val amp = amplitude.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            // OPAQUE. Both layers were translucent in the first version, and the
            // home screen showed straight through a dive — "Good afternoon" and
            // the JARVIS wordmark ghosting over a galaxy, which reads as a
            // rendering fault rather than as depth. Deep space has nothing behind
            // it, so neither does this: the theme's own backdrop colour at full
            // opacity, darkened toward black by a second opaque layer.
            .background(deepSpace(palette.orbStyle))
            // The tap detector goes FIRST so the transform detector below it is
            // the inner one and sees a second finger before anything else does.
            // The other order lets the double-tap detector spend its timeout
            // deciding while a pinch is already under way.
            .pointerInput(Unit) {
                detectTapGestures(
                    // On the map a single tap PICKS a star. Inside a dimension a
                    // single tap means nothing, so it stays inert there rather
                    // than doing something arbitrary.
                    onTap = { at ->
                        if (chosen == null) {
                            val hit = UniverseMath.starAt(
                                stars,
                                at.x / size.width,
                                at.y / size.height,
                            )
                            if (hit != null) chosen = hit
                        }
                    },
                    // A dive per double-tap, because a phone held one-handed
                    // cannot pinch, and this is the whole feature.
                    onDoubleTap = {
                        if (chosen != null) {
                            target = floor(zoom.value) + 1f
                            scope.launch {
                                zoom.animateTo(target, tween(900, easing = FastOutSlowInEasing))
                            }
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
                        if (settled && UniverseMath.shouldClose(target)) {
                            // Surfacing out of a dimension goes back to the MAP,
                            // not out of the universe altogether. Dropping the
                            // user onto the home screen from six levels down
                            // would throw away the choice they made to get there.
                            if (chosen != null) {
                                chosen = null
                                target = UniverseMath.START_ZOOM
                                scope.launch { zoom.snapTo(UniverseMath.START_ZOOM) }
                            } else {
                                close()
                            }
                        }
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

            // THE MAP. Drawn while no star is chosen, and kept drawing while the
            // flight into one is under way — receding and dimming, so the star you
            // touched is visibly the thing you are travelling into rather than a
            // menu that closed and a scene that opened.
            val flight = enterStar.value
            if (flight < 0.999f) {
                val focus = chosen
                drawStarMap(
                    stars = stars,
                    clock = clock,
                    flight = flight,
                    focus = focus,
                    accent = palette.accent,
                    highlight = palette.highlight,
                    secondary = palette.secondary,
                )
            }

            // No dimension entered yet — nothing below this is drawn.
            if (chosen == null) return@Canvas

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
                    alpha = alpha * flight,
                    core = UniverseMath.coreGlow(level),
                    clock = clock,
                    accent = palette.accent,
                    highlight = palette.highlight,
                    secondary = palette.secondary,
                    viewport = base,
                )
            }

            // The bloom of arriving. Brightest at the instant the page appears
            // and gone by the time it has finished growing — light thrown by
            // going through something, which is what covers the moment when the
            // shells are still too small to be read as structure.
            val bloom = (1f - entry).coerceIn(0f, 1f)
            if (bloom > 0.01f) {
                val reach = base * (0.35f + entry * 2.2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = bloom * 0.55f),
                            palette.highlight.copy(alpha = bloom * 0.35f),
                            palette.accent.copy(alpha = bloom * 0.12f),
                            Color.Transparent,
                        ),
                        center = eye,
                        radius = reach,
                    ),
                    radius = reach,
                    center = eye,
                    blendMode = BlendMode.Plus,
                )
            }
        }

        // The readout waits for the arrival. Text at full strength over a page
        // that is still a third of its size and rushing inward reads as an
        // overlay stuck on top of the animation rather than as part of the place.
        val readout = ((entry - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (readout > 0.01f) {
            val star = chosen
            if (star == null) {
                StarMapHud(palette = palette, alpha = readout, modifier = Modifier.fillMaxSize())
            } else {
                UniverseHud(
                    depth = depth,
                    fraction = fraction,
                    here = shells[0],
                    star = star,
                    palette = palette,
                    alpha = readout * enterStar.value,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
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
    here: ShellSpec?,
    star: StarSpec,
    palette: JarvisPalette,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.systemBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Which dimension. Above the tier, because it does not change as you
            // descend and the tier does — the thing that stays put belongs on top.
            Text(
                text = "${star.kind.label}  ·  ${star.designation}",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = palette.secondary.copy(alpha = 0.85f * alpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
            Text(
                text = UniverseMath.labelFor(depth),
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 6.sp),
                color = palette.highlight.copy(alpha = alpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // The place you are actually in, by name. Without this the readout
            // said only how DEEP you were, and since every level is the same kind
            // of thing at a different scale, that made a descent of ten look
            // exactly like a descent of one.
            if (here != null) {
                Text(
                    text = here.designation,
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.wordmark.copy(alpha = alpha),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                Text(
                    text = UniverseMath.describe(here),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = palette.accent.copy(alpha = 0.75f * alpha),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Text(
                text = "DEPTH ${depth.coerceAtLeast(0)}·${(fraction * 100).toInt().toString().padStart(2, '0')}",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = palette.accent.copy(alpha = 0.50f * alpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        Text(
            text = "PINCH TO DIVE  ·  DOUBLE-TAP TO FALL  ·  PINCH BACK TO LEAVE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = palette.accent.copy(alpha = 0.45f * alpha),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The readout over the star map.
 *
 * Separate from [UniverseHud] rather than the same one with fields blanked out,
 * because it is answering a different question: the map asks "which of these?"
 * and a dimension reports "here is where you are". Sharing a layout between the
 * two would mean a row of empty labels on whichever screen did not use them.
 */
@Composable
private fun StarMapHud(
    palette: JarvisPalette,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.systemBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "CHART",
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 6.sp),
                color = palette.highlight.copy(alpha = alpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${UniverseMath.STAR_COUNT} STARS  ·  ${StarKind.entries.size} KINDS",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = palette.accent.copy(alpha = 0.60f * alpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Text(
            text = "TOUCH A STAR TO ENTER ITS DIMENSION  ·  PINCH BACK TO LEAVE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = palette.accent.copy(alpha = 0.45f * alpha),
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

    // Gas first, under everything: a broad tinted haze that gives the structure
    // something to sit IN. Without it a shell is bright specks on black, which is
    // what "more details" was pointing at — the specks were fine, the space
    // between them was empty.
    drawGas(spec, at, radius, alpha, turn, accent, highlight, secondary)

    when (spec.kind) {
        ShellKind.Spiral -> drawArms(spec, at, radius, alpha, presence, turn, squash, style, accent, highlight)
        ShellKind.Ringed -> drawDisc(spec, at, radius, alpha, presence, turn, squash, style, accent, highlight)
        ShellKind.Cluster -> drawSwarm(spec, at, radius, alpha, presence, turn, style, accent, highlight)
        ShellKind.Binary -> drawBinary(spec, at, radius, alpha, presence, clock, style, accent, highlight)
    }

    // Dark lanes cut across the structure after it is drawn, which is the only
    // order that works: a dust lane is an absence of light, so it has to subtract
    // from something that is already there.
    if (spec.lanes > 0 && presence > 0.35f) {
        drawLanes(spec, at, radius, alpha, presence, turn, squash)
    }

    // Orbits and their bodies, on top of whatever the structure was.
    spec.satellites.forEach { moon ->
        drawSatellite(moon, at, radius, alpha, presence, clock, turn, accent, highlight)
    }

    if (core > 0.004f) {
        drawCore(at, radius * spec.coreSize, alpha * core, highlight, accent)
    }
}

/**
 * The chart you arrive on: nine stars, six kinds, each drawn as itself.
 *
 * A chooser is only a chooser if the options look different. Nine identical dots
 * with different captions would be a list wearing a sky costume — so a blue giant
 * is genuinely huge and hot-white, a red dwarf is small and dim, a binary is
 * visibly two bodies turning about each other, a pulsar sweeps a beam, a
 * protostar sits inside the cloud it is condensing out of, and a white dwarf is a
 * hard little point with almost nothing around it.
 *
 * [flight] runs 0..1 as the touched star is entered. The whole map recedes and
 * dims while the chosen one rushes toward the camera, so the dimension is
 * visibly BEHIND the star you picked rather than replacing the screen it was on.
 */
private fun DrawScope.drawStarMap(
    stars: List<StarSpec>,
    clock: Float,
    flight: Float,
    focus: StarSpec?,
    accent: Color,
    highlight: Color,
    secondary: Color,
) {
    val fade = 1f - flight
    stars.forEach { star ->
        val picked = focus != null && focus.branch == star.branch
        // Everything not chosen falls away from the centre and dims out. The
        // chosen one instead grows and slides to the middle — it is what the
        // camera is flying at.
        val home = Offset(star.x * size.width, star.y * size.height)
        val at: Offset
        val grow: Float
        val alpha: Float
        if (picked) {
            at = Offset(
                home.x + (center.x - home.x) * flight,
                home.y + (center.y - home.y) * flight,
            )
            grow = 1f + flight * 7f
            // Bright until the dimension has come up behind it, then out of the
            // way — the same handover the shell cores make.
            alpha = (1f - flight * flight).coerceAtLeast(0f)
        } else {
            val push = 1f + flight * 0.55f
            at = Offset(
                center.x + (home.x - center.x) * push,
                center.y + (home.y - center.y) * push,
            )
            grow = 1f
            alpha = fade * fade
        }
        if (alpha <= 0.01f) return@forEach

        val r = size.minDimension * 0.028f * star.size * star.kind.scale * grow
        val colour = lerpColour(accent, highlight, star.kind.heat)
        val pulse = 0.85f + 0.15f * sin(clock * 1.7f + star.phase)

        when (star.kind) {
            StarKind.BlueGiant -> {
                // A huge halo, and far more of it than body — that ratio is the
                // whole read of "too bright for what surrounds it".
                drawGlow(at, r * 4.2f, Color.White, colour, alpha * 0.55f * pulse)
                drawCircle(Color.White.copy(alpha = alpha * 0.95f), r * 0.85f, at, blendMode = BlendMode.Plus)
                flare(at, r * 2.4f, Color.White, alpha * 0.45f)
            }
            StarKind.RedDwarf -> {
                // Small, cool, and almost no halo. The quiet one.
                drawGlow(at, r * 1.9f, colour, colour, alpha * 0.40f * pulse)
                drawCircle(colour.copy(alpha = alpha * 0.85f), r * 0.55f, at, blendMode = BlendMode.Plus)
            }
            StarKind.Binary -> {
                // Two bodies about a shared centre, turning slowly enough to see.
                val a = clock * 0.55f + star.phase
                val sep = r * 1.5f
                listOf(0f, OrbMath.PI_F).forEachIndexed { i, off ->
                    val p = Offset(at.x + cos(a + off) * sep, at.y + sin(a + off) * sep * 0.6f)
                    val c = if (i == 0) highlight else accent
                    drawGlow(p, r * 1.5f, Color.White, c, alpha * 0.60f)
                    drawCircle(Color.White.copy(alpha = alpha * 0.80f), r * 0.40f, p, blendMode = BlendMode.Plus)
                }
            }
            StarKind.Pulsar -> {
                // The beam is the identity. Two lobes sweeping opposite ways off a
                // small, very hard core.
                val sweep = clock * 3.1f + star.phase
                for (dir in listOf(1f, -1f)) {
                    val len = r * 5.5f * dir
                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(colour.copy(alpha = alpha * 0.55f), Color.Transparent),
                            start = at,
                            end = Offset(at.x + cos(sweep) * len, at.y + sin(sweep) * len),
                        ),
                        start = at,
                        end = Offset(at.x + cos(sweep) * len, at.y + sin(sweep) * len),
                        strokeWidth = px(2.2f),
                        cap = StrokeCap.Round,
                        blendMode = BlendMode.Plus,
                    )
                }
                drawCircle(Color.White.copy(alpha = alpha), r * 0.42f, at, blendMode = BlendMode.Plus)
            }
            StarKind.Protostar -> {
                // Wrapped in the cloud it is forming out of: several offset veils,
                // and only a soft knot at the middle.
                for (i in 0 until 4) {
                    val drift = clock * 0.20f + i * 1.6f + star.phase
                    val off = r * OrbMath.range(star.branch * 31 + i, 0.20f, 0.85f)
                    val p = Offset(at.x + cos(drift) * off, at.y + sin(drift * 1.3f) * off * 0.8f)
                    drawGlow(p, r * OrbMath.range(star.branch * 17 + i, 1.6f, 2.9f), colour, secondary, alpha * 0.22f)
                }
                drawCircle(Color.White.copy(alpha = alpha * 0.55f), r * 0.45f, at, blendMode = BlendMode.Plus)
            }
            StarKind.WhiteDwarf -> {
                // Dense and dim: a hard point with a tight halo and nothing else.
                drawGlow(at, r * 1.4f, Color.White, colour, alpha * 0.35f)
                drawCircle(Color.White.copy(alpha = alpha * 0.90f), r * 0.34f, at, blendMode = BlendMode.Plus)
            }
        }

        // The catalogue name under each, so a star is a place with an identity
        // before you commit to going there. Dropped once the flight starts —
        // labels sliding around during a camera move read as debris.
        if (!picked && fade > 0.6f) {
            drawCircle(
                color = colour.copy(alpha = fade * 0.14f),
                radius = r * 2.6f,
                center = at,
                style = Stroke(px(0.9f)),
            )
        }
    }
}

/** A soft two-stop halo — every star kind wants one, at different weights. */
private fun DrawScope.drawGlow(at: Offset, radius: Float, inner: Color, outer: Color, alpha: Float) {
    if (radius <= 0f || alpha <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                inner.copy(alpha = alpha),
                outer.copy(alpha = alpha * 0.45f),
                Color.Transparent,
            ),
            center = at,
            radius = radius,
        ),
        radius = radius,
        center = at,
        blendMode = BlendMode.Plus,
    )
}

/**
 * The gas a structure sits in.
 *
 * Five broad tinted veils, offset from the centre and drifting on their own
 * clocks. This is the cheapest thing in the file and it does more for the look
 * than anything else in it: bright points on black read as a screensaver, and the
 * same points inside a coloured cloud read as a place. Drawn under everything and
 * ending in full transparency, so it never puts an edge anywhere.
 */
private fun DrawScope.drawGas(
    spec: ShellSpec,
    at: Offset,
    radius: Float,
    alpha: Float,
    turn: Float,
    accent: Color,
    highlight: Color,
    secondary: Color,
) {
    val seed = spec.seed * 5171
    for (i in 0 until 5) {
        val drift = turn * OrbMath.range(seed + i * 9, 0.10f, 0.30f) + i * 1.7f
        val off = radius * OrbMath.range(seed + i * 7, 0.05f, 0.55f)
        val centre = Offset(
            at.x + cos(drift) * off,
            at.y + sin(drift * 1.21f) * off * 0.72f,
        )
        val size = radius * OrbMath.range(seed + i * 11, 0.55f, 1.35f)
        val colour = when (i % 3) {
            0 -> accent
            1 -> highlight
            else -> secondary
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    colour.copy(alpha = alpha * spec.haze * OrbMath.range(seed + i * 3, 0.05f, 0.13f)),
                    colour.copy(alpha = alpha * spec.haze * 0.03f),
                    Color.Transparent,
                ),
                center = centre,
                radius = size,
            ),
            radius = size,
            center = centre,
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * Dust lanes: dark bands cutting across the structure.
 *
 * The one element here drawn with ordinary alpha rather than [BlendMode.Plus],
 * because it is the only one that takes light AWAY. Every real spiral has these
 * and they are most of what makes the arms read as arms — an evenly bright disc
 * has no structure to see, however many motes are in it.
 */
private fun DrawScope.drawLanes(
    spec: ShellSpec,
    at: Offset,
    radius: Float,
    alpha: Float,
    presence: Float,
    turn: Float,
    squash: Float,
) {
    val seed = spec.seed * 6733
    for (i in 0 until spec.lanes) {
        val f = OrbMath.range(seed + i * 13, 0.34f, 0.92f)
        val r = radius * f
        val sweep = turn * (0.6f + f) + OrbMath.unitRandom(seed + i * 5) * OrbMath.TAU
        val span = OrbMath.range(seed + i * 7, 0.7f, 1.9f)
        // Drawn as a run of soft dark dabs along an arc, so it fades in and out
        // along its length instead of ending abruptly.
        val steps = (12 + presence * 20).toInt()
        for (k in 0 until steps) {
            val p = k.toFloat() / (steps - 1)
            val a = sweep + (p - 0.5f) * span
            val fade = kotlin.math.sin(p * OrbMath.PI_F)
            drawCircle(
                color = Color.Black.copy(alpha = alpha * 0.26f * fade),
                radius = radius * 0.055f * (0.6f + presence * 0.7f),
                center = Offset(at.x + cos(a) * r, at.y + sin(a) * r * squash),
            )
        }
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
    val arms = spec.arms
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

    val rad = tilt * (OrbMath.TAU / 360f)
    val cr = cos(rad)
    val sr = sin(rad)

    /** Where the body is at angle [ang], tipped from its own plane into the screen's. */
    fun placeAt(ang: Float): Offset {
        val ox = cos(ang) * r
        val oy = sin(ang) * r * squash
        return Offset(at.x + ox * cr - oy * sr, at.y + ox * sr + oy * cr)
    }

    val a = moon.phase + clock * moon.speed
    val p = placeAt(a)
    // sin(a) > 0 is the half of the orbit nearer the camera.
    val near = 0.5f + sin(a) * 0.5f
    val body = radius * moon.size * (0.65f + near * 0.6f)
    if (body < 0.6f) return

    // The trail: where it has just been, fading out behind it. This is what says
    // the body is TRAVELLING — a bright dot sitting on an ellipse is ambiguous
    // about which way it is going, or whether it is going anywhere.
    if (presence > 0.30f) {
        val back = if (moon.speed > 0f) -1f else 1f
        for (k in 1..10) {
            val t = k / 10f
            val q = placeAt(a + back * t * 0.55f)
            drawCircle(
                color = colour.copy(alpha = alpha * (1f - t) * 0.30f * near),
                radius = body * (1f - t) * 0.45f,
                center = q,
                blendMode = BlendMode.Plus,
            )
        }
    }

    // The glow it casts.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                colour.copy(alpha = alpha * (0.30f + near * 0.35f)),
                colour.copy(alpha = alpha * 0.10f),
                Color.Transparent,
            ),
            center = p,
            radius = body * 2.6f,
        ),
        radius = body * 2.6f,
        center = p,
        blendMode = BlendMode.Plus,
    )

    // Close up, it stops being a point of light and becomes a WORLD: a disc lit
    // from one side with a dark far limb. Below this size the shading is
    // indistinguishable from a plain dot and costs the same, so it is skipped.
    if (presence > 0.5f && body > 3.5f) {
        val lit = Offset(p.x - body * 0.38f, p.y - body * 0.30f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = alpha * 0.92f),
                    colour.copy(alpha = alpha * 0.80f),
                    Color.Black.copy(alpha = alpha * 0.55f),
                ),
                center = lit,
                radius = body * 1.7f,
            ),
            radius = body,
            center = p,
        )
    } else {
        drawCircle(
            color = Color.White.copy(alpha = alpha * (0.30f + near * 0.45f)),
            radius = body * 0.55f,
            center = p,
            blendMode = BlendMode.Plus,
        )
    }

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

/**
 * The void a dive happens in: the theme's own backdrop pulled most of the way to
 * black, and fully opaque.
 *
 * Opaque is the whole point — see the note at the call site. Themed rather than a
 * flat black because the four themes are meant to be four different places, and
 * a shared black void is the one surface that would make them identical again.
 */
private fun deepSpace(style: OrbStyle): Color {
    val base = ThemeArt.backdrop(style)
    return Color(base.red * 0.30f, base.green * 0.30f, base.blue * 0.30f, 1f)
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
