package com.jarvis.os.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jarvis.os.ui.theme.BackdropStyle
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * The world each theme's orb sits in.
 *
 * This used to be one generic star field recoloured six ways, which is why the
 * themes still read as the same screen in different accents — every reference
 * puts something specific behind the orb, and that is half of what makes them
 * distinct. Each theme now gets its own: a dotted wireframe globe for the two
 * blue designs, a strut-and-ball geodesic shell for the faceted ones, HUD
 * brackets and warm haze for the forge, a nebula and circuit floor for the last.
 *
 * The base colour is measured from the corners of each reference render rather
 * than chosen, so the backdrop starts from the source's own light.
 */
@Composable
fun ThemeBackdrop(
    modifier: Modifier = Modifier,
    palette: JarvisPalette = LocalPalette.current,
    /** Which world to draw. Defaults to the one this theme ships with. */
    backdrop: BackdropStyle = BackdropStyle.defaultFor(palette.orbStyle),
    /**
     * Whether this instance runs its clocks, and how much detail it draws.
     *
     * The background picker shows ELEVEN of these at once. Live, that is eleven
     * infinite transitions each invalidating a Canvas at 60fps, each drawing a
     * 150-star field and four additive blooms — which is precisely the fault the
     * theme picker already had once with seven live orbs, and it made Settings
     * lag exactly the same way. A picker thumbnail is a still.
     */
    thumbnail: Boolean = false,
    /**
     * Whether the clocks run at all.
     *
     * A full-detail backdrop that is *still* costs nothing per frame, and behind a
     * scrolling list that is exactly what it should be: the sky's slowest clock
     * has a 150-second period and its fastest 38, so nobody has ever seen it move
     * while reading a settings screen — they have only felt it, as the list not
     * quite keeping up with their thumb.
     *
     * Separate from [thumbnail], which is about DETAIL rather than motion. A
     * picker tile is both; a settings background is still at full detail.
     */
    live: Boolean = true,
) {
    // The sky's geometry, kept across frames rather than rebuilt in each one.
    val sky = remember { SkyCache() }

    // Only created when it will be used: a rememberInfiniteTransition that exists
    // but is ignored still schedules frames, so gating the VALUES rather than the
    // transition would have saved nothing. The same lesson as HudOrb's.
    val drift: Float
    val flow: Float
    if (thumbnail || !live) {
        // Frozen off the zero mark, so a still shows the scene mid-motion rather
        // than at the degenerate moment where everything lines up.
        drift = STILL_DRIFT
        flow = STILL_FLOW
    } else {
        val live = liveClocks()
        drift = live.first
        flow = live.second
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawBackdrop(palette, backdrop, drift, flow, thumbnail, sky)
    }
}

/** Where a still backdrop is frozen — mid-drift, not at the aligned zero mark. */
private const val STILL_DRIFT = 1.9f
private const val STILL_FLOW = 3.4f

@Composable
private fun liveClocks(): Pair<Float, Float> {
    val transition = rememberInfiniteTransition(label = "backdrop")
    // Very slow. A backdrop that visibly turns competes with the orb; one that
    // drifts imperceptibly is atmosphere.
    val drift by transition.animateFloat(
        0f, Orb3D.TAU,
        infiniteRepeatable(tween(150_000, easing = LinearEasing)),
        label = "backdropDrift",
    )
    // A second, much faster clock. The aurora needs to be seen to move — the
    // 150-second drift above is deliberately imperceptible, and running the
    // clouds on it produced a backdrop nobody could tell was animated at all.
    val flow by transition.animateFloat(
        0f, Orb3D.TAU,
        infiniteRepeatable(tween(38_000, easing = LinearEasing)),
        label = "backdropFlow",
    )

    return drift to flow
}

private fun DrawScope.drawBackdrop(
    palette: JarvisPalette,
    backdrop: BackdropStyle,
    drift: Float,
    flow: Float,
    thumbnail: Boolean,
    sky: SkyCache,
) {
    run {
        val w = size.width
        val h = size.height
        // A thumbnail is a fifth of the width of the real thing, so the same
        // counts buy nothing and cost the same. Detail follows apparent size, the
        // way OrbQuality already does for the orb.
        val detail = if (thumbnail) 0.25f else 1f
        val focus = Offset(w / 2f, h * 0.34f)
        val base = ThemeArt.backdrop(palette.orbStyle)

        // ── Depth ────────────────────────────────────────────────────────────
        // A vertical ramp under everything. Without it the screen is one flat
        // colour with objects sitting ON it; with it there is a top and a bottom,
        // which is most of what made the old backdrop read as dull.
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    base.copy(alpha = 0.34f),
                    Color.Transparent,
                    base.copy(alpha = 0.22f),
                ),
            ),
        )

        // ── Aurora ───────────────────────────────────────────────────────────
        // Four large soft blooms drifting across each other on slow lissajous
        // paths, added rather than painted over, so where two overlap the colour
        // genuinely brightens. This is the layer doing the work: one flat wash
        // reads as a background, several moving ones read as a place.
        aurora(w, h, flow, palette.accent, palette.secondary, palette.highlight)

        // Wash from behind the orb, in the reference's own background colour.
        // Stronger than it was: it now has an aurora to sit in front of.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    base.copy(alpha = 0.58f),
                    base.copy(alpha = 0.24f),
                    Color.Transparent,
                ),
                center = focus,
                radius = maxOf(w, h) * 0.8f,
            ),
        )

        // ONE signature element per theme, and nothing shared between two.
        //
        // "make every theme unique, right now all look almost the same" — and the
        // structure was the reason. Reactor and Lattice both drew `wireGlobe`;
        // Prism and Machine both drew `nodeShell`; Filigree and Orbit both drew
        // `hudBrackets`. So of seven themes, three were a neighbour recoloured.
        // Cutting Lattice, Prism and Core removed the duplicates; giving the four
        // survivors one distinct world each is what stops it recurring.
        when (backdrop) {
            // Blueprint: a drafting table. Wireframe globe and instrument brackets.
            BackdropStyle.Blueprint -> {
                wireGlobe(focus, minOf(w, h) * 0.62f, drift, palette.accent, dense = false)
                hudBrackets(palette.accent)
            }
            // Forge Floor: a workshop. Warm haze over a lit floor — no wire
            // anywhere, because the point of this one is heat rather than
            // instrumentation.
            BackdropStyle.ForgeFloor -> {
                warmHaze(focus, minOf(w, h) * 0.70f, palette.accent, palette.highlight)
                groundMesh(h * 0.66f, palette.highlight, rows = 12, columns = 18, alpha = 0.26f)
            }
            // Deep Sky: clouds and a circuit floor, no globe, no brackets.
            BackdropStyle.DeepSky -> {
                nebulaClouds(w, h, palette.accent, palette.secondary)
                circuitFloor(h * 0.66f, palette.accent)
            }
            // Low Orbit: a planet limb across the bottom and deliberately NOTHING
            // else. It is the strongest single element here and does not need
            // help; brackets over it were what made it look like the forge.
            BackdropStyle.LowOrbit -> planetHorizon(w, h, palette.accent, palette.highlight)

            BackdropStyle.DataRain -> dataRain(w, h, flow, palette.accent, palette.highlight)
            BackdropStyle.Canyon -> canyon(w, h, drift, palette.accent, palette.secondary, base)
            BackdropStyle.AuroraVeil -> auroraVeil(w, h, flow, palette.accent, palette.secondary, palette.highlight)
            BackdropStyle.Monolith -> monolith(w, h, palette.accent, palette.highlight)
            BackdropStyle.DeepReef -> deepReef(w, h, flow, palette.accent, palette.highlight)
            BackdropStyle.Dune -> dune(w, h, drift, flow, palette.accent, palette.highlight, base)
        }

        // Which scenes have a sky to put stars in. Underwater, in a canyon and in
        // a sandstorm there is nothing above you to see them through, and a star
        // field over any of the three is the detail that breaks the illusion.
        when (backdrop) {
            BackdropStyle.DeepReef, BackdropStyle.Canyon, BackdropStyle.Dune -> Unit
            BackdropStyle.DeepSky, BackdropStyle.LowOrbit, BackdropStyle.AuroraVeil ->
                starField(w, h, palette.highlight, heavy = !thumbnail, t = flow, scale = detail, sky = sky)
            else -> starField(w, h, palette.highlight, heavy = false, t = flow, scale = detail, sky = sky)
        }

        // A light source below the fold — skipped where the scene already has
        // its own and a second would fight it.
        when (backdrop) {
            BackdropStyle.LowOrbit, BackdropStyle.Dune, BackdropStyle.Monolith, BackdropStyle.DeepReef -> Unit
            else -> horizonGlow(w, h, palette.accent, palette.secondary)
        }

        // Framed last. A vignette is what stops a bright backdrop competing with
        // the text on top of it — the corners fall away and the eye goes to the
        // orb, which is the only reason the brightness is affordable at all.
        vignette(w, h)
    }
}

/**
 * Slow overlapping colour fields — the layer that turns a flat ground into
 * weather.
 *
 * Additive on purpose ([BlendMode.Plus]): painted normally, four translucent
 * circles just average toward mud, and the whole point is that the overlaps are
 * the brightest part. Alphas are low for the same reason — with Plus, four of
 * them stack, and values that look right alone blow out where they cross.
 *
 * Positions come from sin/cos of one clock rather than anything remembered, so
 * this stays a pure function of time and costs no state.
 */
private fun DrawScope.aurora(
    w: Float,
    h: Float,
    t: Float,
    accent: Color,
    secondary: Color,
    highlight: Color,
) {
    val span = maxOf(w, h)
    val blooms = listOf(
        Triple(accent, Offset(w * (0.26f + 0.10f * kotlin.math.sin(t)), h * (0.20f + 0.06f * kotlin.math.cos(t * 0.7f))), 0.62f),
        Triple(secondary, Offset(w * (0.78f + 0.09f * kotlin.math.cos(t * 0.8f)), h * (0.33f + 0.07f * kotlin.math.sin(t * 1.1f))), 0.70f),
        Triple(highlight, Offset(w * (0.60f + 0.12f * kotlin.math.sin(t * 0.6f + 2f)), h * (0.72f + 0.05f * kotlin.math.cos(t))), 0.52f),
        Triple(accent, Offset(w * (0.14f + 0.08f * kotlin.math.cos(t * 1.3f)), h * (0.86f + 0.04f * kotlin.math.sin(t * 0.9f))), 0.46f),
    )
    blooms.forEach { (colour, centre, scale) ->
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    colour.copy(alpha = 0.20f),
                    colour.copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                center = centre,
                radius = span * scale,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}

/** A band of light along the bottom edge, as if something is lit off-screen. */
private fun DrawScope.horizonGlow(w: Float, h: Float, accent: Color, secondary: Color) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                secondary.copy(alpha = 0.26f),
                accent.copy(alpha = 0.10f),
                Color.Transparent,
            ),
            center = Offset(w * 0.5f, h * 1.06f),
            radius = maxOf(w, h) * 0.62f,
        ),
        blendMode = BlendMode.Plus,
    )
}

/** Darkened corners, so the bright middle stays the subject. */
private fun DrawScope.vignette(w: Float, h: Float) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = 0.30f),
                Color.Black.copy(alpha = 0.62f),
            ),
            center = Offset(w * 0.5f, h * 0.42f),
            radius = maxOf(w, h) * 0.78f,
        ),
    )
}

/** A dotted wireframe globe in real 3D — latitudes and meridians, slowly turning. */
private fun DrawScope.wireGlobe(
    centre: Offset,
    radius: Float,
    t: Float,
    colour: Color,
    dense: Boolean,
) {
    val dots = PathEffect.dashPathEffect(floatArrayOf(px(1.4f), px(5f)))
    val cam = radius * 3.6f
    val focal = radius * 2.7f
    val lats = if (dense) 7 else 5
    val meridians = if (dense) 12 else 9

    for (i in 1..lats) {
        val phi = (i.toFloat() / (lats + 1)) * Orb3D.TAU / 2f - Orb3D.TAU / 4f
        val ringR = radius * kotlin.math.cos(phi)
        val yOff = radius * kotlin.math.sin(phi)
        val pts = Orb3D.ring(ringR, 64, Orb3D.TAU / 4f, 0f, t * 0.4f)
            .map { Orb3D.project(Vec3(it.x, it.z + yOff, it.y), cam, focal) }
        strokeProjected(pts, centre, colour.copy(alpha = 0.20f), 1f, dots)
    }
    for (m in 0 until meridians) {
        val pts = Orb3D.ring(radius, 64, 0f, (m.toFloat() / meridians) * Orb3D.TAU + t * 0.4f, 0f)
            .map { Orb3D.project(it, cam, focal) }
        strokeProjected(pts, centre, colour.copy(alpha = 0.13f), 1f, dots)
    }
}


/** Warm concentric haze for the forge. */
private fun DrawScope.warmHaze(centre: Offset, radius: Float, accent: Color, highlight: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(highlight.copy(alpha = 0.10f), accent.copy(alpha = 0.05f), Color.Transparent),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
        blendMode = BlendMode.Plus,
    )
    val dots = PathEffect.dashPathEffect(floatArrayOf(px(1.2f), px(6f)))
    listOf(0.62f, 0.78f, 0.94f).forEachIndexed { i, f ->
        drawCircle(
            colour(accent, 0.09f - i * 0.02f),
            radius * f,
            centre,
            style = Stroke(px(1f), pathEffect = dots),
        )
    }
}

/** Corner brackets, as in the forge reference's HUD panels. */
private fun DrawScope.hudBrackets(colour: Color) {
    val w = size.width
    val h = size.height
    val len = minOf(w, h) * 0.07f
    val inset = len * 0.5f
    val c = colour.copy(alpha = 0.22f)
    listOf(
        Triple(Offset(inset, inset), Offset(1f, 0f), Offset(0f, 1f)),
        Triple(Offset(w - inset, inset), Offset(-1f, 0f), Offset(0f, 1f)),
        Triple(Offset(inset, h - inset), Offset(1f, 0f), Offset(0f, -1f)),
        Triple(Offset(w - inset, h - inset), Offset(-1f, 0f), Offset(0f, -1f)),
    ).forEach { (p, dx, dy) ->
        drawLine(c, p, Offset(p.x + dx.x * len, p.y), px(1.4f), cap = StrokeCap.Round)
        drawLine(c, p, Offset(p.x, p.y + dy.y * len), px(1.4f), cap = StrokeCap.Round)
    }
}

/** Soft nebula clouds, deterministic so they do not swim between frames. */
private fun DrawScope.nebulaClouds(w: Float, h: Float, accent: Color, secondary: Color) {
    for (i in 0 until 5) {
        val x = OrbMath.range(i * 31 + 5, 0.1f, 0.9f) * w
        val y = OrbMath.range(i * 31 + 9, 0.05f, 0.85f) * h
        val r = OrbMath.range(i * 31 + 13, 0.18f, 0.42f) * minOf(w, h)
        val c = if (i % 2 == 0) accent else secondary
        drawCircle(
            brush = Brush.radialGradient(
                listOf(c.copy(alpha = 0.075f), Color.Transparent),
                center = Offset(x, y),
                radius = r,
            ),
            radius = r,
            center = Offset(x, y),
            blendMode = BlendMode.Plus,
        )
    }
}

/** A circuit-board floor, as under the nebula reference. */
private fun DrawScope.circuitFloor(horizonY: Float, colour: Color) {
    groundMesh(horizonY, colour, rows = 9, columns = 14, alpha = 0.14f)
    val w = size.width
    val h = size.height
    // Right-angled traces running along the floor.
    for (i in 0 until 7) {
        val y = horizonY + (h - horizonY) * OrbMath.range(i * 17 + 3, 0.15f, 0.95f)
        val x0 = OrbMath.range(i * 17 + 7, 0f, 0.6f) * w
        val x1 = x0 + OrbMath.range(i * 17 + 11, 0.15f, 0.4f) * w
        val drop = OrbMath.range(i * 17 + 19, 0.02f, 0.06f) * h
        val path = Path().apply {
            moveTo(x0, y)
            lineTo(x1, y)
            lineTo(x1, y + drop)
            lineTo(x1 + w * 0.12f, y + drop)
        }
        drawPath(path, colour.copy(alpha = 0.16f), style = Stroke(px(1.2f)))
    }
}

/**
 * Stars.
 *
 * Placement is deterministic ([OrbMath.unitRandom], never `Math.random`) for the
 * reason this project has already paid for once: a Canvas redraws every frame, so
 * anything deciding WHERE a star sits is asked sixty times a second, and a real
 * random re-scatters the sky into static.
 *
 * Brightness is the opposite — it is *meant* to change, so it takes the clock.
 * Each star gets its own phase from its index, so they breathe out of step
 * instead of the whole field pulsing as one. That is the difference between a sky
 * and a string of fairy lights.
 */
private fun DrawScope.starField(
    w: Float,
    h: Float,
    warm: Color,
    heavy: Boolean,
    t: Float,
    scale: Float = 1f,
    sky: SkyCache,
) {
    val count = ((if (heavy) 220 else 150) * scale).toInt()
    val field = sky.of(w, h, count, warm, density)

    // THE FAINT MAJORITY, IN A HANDFUL OF CALLS RATHER THAN TWO HUNDRED.
    //
    // Two hundred `drawCircle`s a frame, behind every screen in the app, is a
    // real bill on a phone that is also trying to scroll a list. Bucketed by
    // colour, size and brightness they collapse into a dozen `drawPoints` calls
    // drawing the same pixels — the sky is identical and the draw list is a
    // fifteenth the length.
    //
    // They are also STILL. Only the bright few twinkle now, which is both cheaper
    // and truer: the faint stars in a real sky are the ones that do not visibly
    // scintillate, and a whole field of them shimmering was always slightly wrong.
    field.buckets.forEach { bucket ->
        drawPoints(
            points = bucket.points,
            pointMode = PointMode.Points,
            color = bucket.colour,
            strokeWidth = bucket.diameter,
            cap = StrokeCap.Round,
        )
    }

    // The bright few, drawn properly and breathing. Each of these allocates three
    // gradient shaders, so the count is the cost — and a dozen genuinely bright
    // stars carry a sky better than fifteen competing ones.
    field.flares.forEach { f ->
        val twinkle = 0.78f + 0.22f * kotlin.math.sin(t * f.rate + f.phase)
        flare(f.at, f.size, Color.White, f.alpha * twinkle)
    }
}

/** One `drawPoints` call: every star that shares a colour, a size and a brightness. */
private class StarBucket(
    val points: List<Offset>,
    val colour: Color,
    val diameter: Float,
)

/** A star bright enough to be worth a shader and a twinkle of its own. */
private class FlareStar(
    val at: Offset,
    val size: Float,
    val alpha: Float,
    val rate: Float,
    val phase: Float,
)

private class SkyLayout(val buckets: List<StarBucket>, val flares: List<FlareStar>)

/**
 * The sky's geometry, built once and kept.
 *
 * Positions, sizes and colours come out of a hash of the star's index, so they
 * are the same every frame — and were being recomputed every frame anyway, along
 * with the bucket lists they now sort into. This holds the result until the frame
 * size or the star count actually changes.
 *
 * **Deliberately not Compose state.** It is written from the draw phase, and a
 * `MutableState` written during draw and read during composition is an endless
 * recomposition loop. A plain holder has no observers, exactly as
 * `WorldPlacements` does for the same reason.
 */
private class SkyCache {
    private var key: String = ""
    private var layout: SkyLayout = SkyLayout(emptyList(), emptyList())

    fun of(w: Float, h: Float, count: Int, warm: Color, density: Float): SkyLayout {
        val k = "$w:$h:$count:${warm.hashCode()}:$density"
        if (k == key) return layout

        // Three sizes, two colours, four brightnesses: twelve buckets, which is
        // enough that the sky still has depth in it and few enough that the draw
        // list stays short.
        val buckets = LinkedHashMap<Int, MutableList<Offset>>()
        val sizes = listOf(0.8f, 1.4f, 2.2f)
        val alphas = listOf(0.28f, 0.44f, 0.62f, 0.85f)
        val flares = mutableListOf<FlareStar>()

        for (i in 0 until count) {
            val at = Offset(OrbMath.unitRandom(i * 3 + 1) * w, OrbMath.unitRandom(i * 3 + 2) * h)
            if (OrbMath.unitRandom(i * 17 + 7) > 0.945f) {
                flares += FlareStar(
                    at = at,
                    size = OrbMath.range(i, 3f, 9f) * density,
                    alpha = OrbMath.range(i * 7 + 11, 0.55f, 0.95f),
                    rate = 1.4f + OrbMath.unitRandom(i * 5 + 2) * 2.2f,
                    phase = i.toFloat(),
                )
                continue
            }
            // Brightness squared, so most of the sky is faint and only a few
            // stand out — the distribution a real field has.
            val b = OrbMath.unitRandom(i * 7 + 11).let { it * it }
            val ai = (b * alphas.size).toInt().coerceAtMost(alphas.size - 1)
            val si = (OrbMath.unitRandom(i * 3 + 3) * sizes.size).toInt().coerceAtMost(sizes.size - 1)
            val ci = if (OrbMath.unitRandom(i * 13 + 5) > 0.68f) 1 else 0
            buckets.getOrPut(ci * 100 + si * 10 + ai) { mutableListOf() } += at
        }

        layout = SkyLayout(
            buckets = buckets.map { (id, pts) ->
                val ci = id / 100
                val si = (id / 10) % 10
                val ai = id % 10
                StarBucket(
                    points = pts,
                    colour = (if (ci == 1) warm else Color.White).copy(alpha = alphas[ai]),
                    diameter = sizes[si] * 2f * density,
                )
            },
            flares = flares,
        )
        key = k
        return layout
    }
}

private fun DrawScope.strokeProjected(
    pts: List<Projected>,
    centre: Offset,
    colour: Color,
    width: Float,
    effect: PathEffect?,
) {
    val path = Path()
    pts.forEachIndexed { i, p ->
        val x = centre.x + p.x
        val y = centre.y + p.y
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, colour, style = Stroke(px(width), pathEffect = effect))
}

private fun colour(c: Color, alpha: Float) = c.copy(alpha = alpha)

/**
 * A planet's limb across the bottom of the screen: the curve of a world, a thin
 * lit atmosphere along its edge, and scattered city lights on the dark side.
 *
 * Drawn as a circle far larger than the screen whose top edge crosses the lower
 * third, which is what gives the shallow, almost-straight curve of a horizon seen
 * from orbit — a small circle would read as a ball sitting on the screen.
 *
 * The lights are placed from [OrbMath.unitRandom] rather than a random, for the
 * reason this project has already paid for once: a Canvas redraws every frame, so
 * anything deciding WHERE a light sits is asked sixty times a second, and a real
 * random re-scatters them into static instead of a city.
 */
private fun DrawScope.planetHorizon(w: Float, h: Float, edge: Color, warm: Color) {
    // A very large circle whose top sits just below the waveform.
    val horizonY = h * 0.82f
    val planetRadius = w * 2.6f
    val centre = Offset(w * 0.5f, horizonY + planetRadius)

    // The dark body itself, so stars do not shine through the planet.
    drawCircle(color = Color(0xFF01050C), radius = planetRadius, center = centre)

    // City lights: only just inside the limb, where a night side is actually lit.
    val lights = 140
    for (i in 0 until lights) {
        val a = OrbMath.unitRandom(i * 3 + 1)          // along the limb
        val d = OrbMath.unitRandom(i * 3 + 2)          // depth below it
        val b = OrbMath.unitRandom(i * 3 + 3)          // brightness
        val angle = (-0.5f + a) * 1.15f            // radians either side of straight up
        val depth = planetRadius * (0.002f + d * 0.05f)
        val x = centre.x + kotlin.math.sin(angle) * (planetRadius - depth)
        val y = centre.y - kotlin.math.cos(angle) * (planetRadius - depth)
        if (y < horizonY - 4f || y > h) continue
        drawCircle(
            color = warm.copy(alpha = 0.10f + b * 0.45f),
            radius = 0.7f + b * 1.5f,
            center = Offset(x, y),
        )
    }

    // The atmosphere: a bright hairline on the limb over a soft outward bloom.
    drawCircle(
        color = edge.copy(alpha = 0.10f),
        radius = planetRadius + h * 0.020f,
        center = centre,
        style = Stroke(width = h * 0.040f),
    )
    drawCircle(
        color = edge.copy(alpha = 0.30f),
        radius = planetRadius + h * 0.004f,
        center = centre,
        style = Stroke(width = h * 0.010f),
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.55f),
        radius = planetRadius,
        center = centre,
        style = Stroke(width = 1.6f),
    )
}

// ─── The six scenes that belong to no theme ─────────────────────────────────
//
// Each is a different KIND of place rather than a different palette of the same
// place, which is the lesson the themes taught: three of the original seven
// shared backdrop geometry and no amount of recolouring made them read as
// distinct. So one has a strong vertical, one has real distance, one stands
// still on purpose, one is underwater, one is weather.

/**
 * DATA RAIN — columns of falling light.
 *
 * The only scene with a strong vertical read, and the only one whose motion is
 * meant to be noticed rather than felt: everything else here drifts on a
 * 150-second clock, and this runs fast enough to look like a machine working.
 *
 * Each column falls at its own rate and wraps independently, so the field never
 * pulses in step. The dashes shorten and dim toward the tail, which is what makes
 * a column read as one thing falling rather than as a dotted line sliding.
 */
private fun DrawScope.dataRain(
    w: Float,
    h: Float,
    t: Float,
    accent: Color,
    highlight: Color,
) {
    val columns = 26
    for (c in 0 until columns) {
        val x = (c + 0.5f) * w / columns + OrbMath.range(c * 13 + 1, -6f, 6f)
        val speed = OrbMath.range(c * 7 + 3, 0.35f, 1.25f)
        val length = OrbMath.range(c * 11 + 5, 5f, 14f).toInt()
        val bright = OrbMath.range(c * 5 + 9, 0.25f, 1f)
        // The head's position wraps through the column's own cycle. Offsetting by
        // the column index keeps two neighbours from ever falling together.
        val head = Orb3D.wrap01(t * speed * 0.16f + OrbMath.unitRandom(c * 17 + 2)) * (h * 1.25f) - h * 0.12f
        for (k in 0 until length) {
            val y = head - k * h * 0.026f
            if (y < -20f || y > h + 20f) continue
            val fade = (1f - k.toFloat() / length)
            val colour = if (k == 0) highlight else accent
            drawLine(
                color = colour.copy(alpha = fade * fade * 0.55f * bright),
                start = Offset(x, y),
                end = Offset(x, y + h * 0.016f * fade),
                strokeWidth = px(1.2f + fade * 1.4f),
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
        }
    }
}

/**
 * CANYON — ridgelines receding into haze.
 *
 * The one scene with real DISTANCE in it. Five silhouettes, each higher on the
 * frame, paler, and drifting more slowly than the one in front — parallax by
 * layer, which is the whole trick and the reason it reads as depth rather than
 * as five shapes stacked. Each ridge is a run of straight segments with heights
 * hashed from its index, so the profile is jagged and unrepeating without
 * anything being stored.
 */
private fun DrawScope.canyon(
    w: Float,
    h: Float,
    t: Float,
    accent: Color,
    secondary: Color,
    base: Color,
) {
    val layers = 5
    for (layer in layers - 1 downTo 0) {
        val far = layer.toFloat() / (layers - 1)
        // Far ridges sit higher and are washed out toward the sky colour.
        val baseY = h * (0.44f + far * 0.06f) + h * 0.20f * (1f - far)
        val amplitude = h * (0.16f - far * 0.10f)
        val colour = lerpTo(if (layer % 2 == 0) accent else secondary, base, 0.35f + far * 0.45f)
        val slide = sin(t * (0.10f + (1f - far) * 0.22f)) * w * 0.04f * (1f - far)

        val ridge = Path().apply {
            moveTo(-w * 0.1f, h * 1.1f)
            val steps = 22
            for (i in 0..steps) {
                val x = -w * 0.1f + (w * 1.2f) * i / steps + slide
                val seed = layer * 977 + i * 31
                // Two octaves: broad peaks with smaller teeth on them.
                val peak = OrbMath.unitRandom(seed) * 0.7f + OrbMath.unitRandom(seed * 3 + 7) * 0.3f
                lineTo(x, baseY - amplitude * peak)
            }
            lineTo(w * 1.1f, h * 1.1f)
            close()
        }
        drawPath(ridge, colour.copy(alpha = 0.55f + far * 0.25f))
        // A lit rim on the near ridges only, where a real light would catch.
        if (layer <= 1) {
            drawPath(
                ridge,
                accent.copy(alpha = 0.20f - layer * 0.08f),
                style = Stroke(px(1.2f)),
                blendMode = BlendMode.Plus,
            )
        }
    }
}

/**
 * AURORA — curtains standing on a dark horizon.
 *
 * Not the same thing as the `aurora` blooms every backdrop already carries:
 * those are soft circles drifting behind everything, and these are vertical
 * SHEETS with a defined bottom edge that ripples along its length. The
 * difference is the edge — a curtain you can see the bottom of has a position in
 * space, and a bloom does not.
 *
 * Drawn as many thin vertical gradients side by side rather than as one shape,
 * so each slice can have its own height and the fold moves along the curtain.
 */
private fun DrawScope.auroraVeil(
    w: Float,
    h: Float,
    t: Float,
    accent: Color,
    secondary: Color,
    highlight: Color,
) {
    val curtains = 3
    for (c in 0 until curtains) {
        val colour = when (c % 3) {
            0 -> accent
            1 -> secondary
            else -> highlight
        }
        val originX = w * OrbMath.range(c * 23 + 5, -0.15f, 0.75f)
        val width = w * OrbMath.range(c * 19 + 3, 0.45f, 0.85f)
        val phase = t * OrbMath.range(c * 11 + 7, 0.20f, 0.45f) + c * 2.1f
        val slices = 34
        for (i in 0 until slices) {
            val f = i.toFloat() / (slices - 1)
            val x = originX + width * f
            if (x < -20f || x > w + 20f) continue
            // The fold: two waves of different rates along the curtain, so the
            // bottom edge never looks like a single sine.
            val fold = sin(phase + f * 6.2f) * 0.5f + sin(phase * 1.7f + f * 11.4f) * 0.25f
            val bottom = h * (0.52f + 0.16f * fold)
            val top = h * (0.02f + 0.05f * OrbMath.unitRandom(c * 31 + i))
            val bright = (0.10f + 0.10f * (1f + fold)) * (0.4f + 0.6f * sin(f * 3.14f))
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        colour.copy(alpha = bright * 0.55f),
                        colour.copy(alpha = bright),
                        Color.Transparent,
                    ),
                    startY = top,
                    endY = bottom,
                ),
                topLeft = Offset(x, top),
                size = androidx.compose.ui.geometry.Size(width / slices + px(1.5f), bottom - top),
                blendMode = BlendMode.Plus,
            )
        }
    }
}

/**
 * MONOLITH — a slab standing in front of the light.
 *
 * The still one, deliberately. Nine of these scenes move, and a set where
 * everything drifts has no quiet member — this is the one you choose when the
 * orb should be the only thing on screen doing anything.
 *
 * All of its effect is in the edges: a hard black rectangle would be a hole, so
 * the slab carries a lit rim down one side, a thin bounce down the other, and
 * light spilling out from behind it.
 */
private fun DrawScope.monolith(w: Float, h: Float, accent: Color, highlight: Color) {
    val left = w * 0.30f
    val right = w * 0.70f
    val top = h * 0.10f
    val bottom = h * 0.86f

    // The light behind it, which is what makes the slab read as blocking rather
    // than as painted on.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.38f),
            radius = maxOf(w, h) * 0.62f,
        ),
        radius = maxOf(w, h) * 0.62f,
        center = Offset(w * 0.5f, h * 0.38f),
        blendMode = BlendMode.Plus,
    )

    // The slab: not flat black, but a face that falls off downward, so it has a
    // surface rather than being an absence.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.80f), Color.Black.copy(alpha = 0.94f)),
            startY = top,
            endY = bottom,
        ),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
    )

    // The lit edge, and a dimmer bounce down the far side.
    drawLine(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, highlight.copy(alpha = 0.75f), highlight.copy(alpha = 0.15f)),
            startY = top,
            endY = bottom,
        ),
        start = Offset(left, top),
        end = Offset(left, bottom),
        strokeWidth = px(2.0f),
        blendMode = BlendMode.Plus,
    )
    drawLine(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, accent.copy(alpha = 0.30f), Color.Transparent),
            startY = top,
            endY = bottom,
        ),
        start = Offset(right, top),
        end = Offset(right, bottom),
        strokeWidth = px(1.2f),
        blendMode = BlendMode.Plus,
    )
    // Where it meets the ground.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, accent.copy(alpha = 0.22f)),
            startY = bottom - h * 0.06f,
            endY = bottom,
        ),
        topLeft = Offset(left, bottom - h * 0.06f),
        size = androidx.compose.ui.geometry.Size(right - left, h * 0.06f),
        blendMode = BlendMode.Plus,
    )
}

/**
 * DEEP REEF — light bending down through water.
 *
 * The one scene lit from ABOVE rather than from behind, and the only one where
 * things rise instead of falling or drifting sideways. Both are the same
 * decision: a set of ten backdrops needs a member that inverts the others, or
 * they all end up being weather over a horizon.
 *
 * The caustics are the point — overlapping shafts of different widths swaying on
 * their own clocks, brightest where two cross, which is what light through a
 * moving surface actually does.
 */
private fun DrawScope.deepReef(
    w: Float,
    h: Float,
    t: Float,
    accent: Color,
    highlight: Color,
) {
    // The column of water: bright at the surface, black at depth.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                highlight.copy(alpha = 0.26f),
                accent.copy(alpha = 0.16f),
                Color.Black.copy(alpha = 0.55f),
            ),
        ),
    )

    // Shafts, fanning slightly as they descend.
    for (i in 0 until 9) {
        val sway = sin(t * OrbMath.range(i * 13 + 3, 0.20f, 0.55f) + i) * w * 0.06f
        val topX = w * OrbMath.range(i * 7 + 1, -0.05f, 1.05f) + sway
        val spread = w * OrbMath.range(i * 11 + 5, 0.03f, 0.11f)
        val depth = h * OrbMath.range(i * 5 + 9, 0.55f, 1.0f)
        val bright = OrbMath.range(i * 17 + 2, 0.06f, 0.17f)
        val shaft = Path().apply {
            moveTo(topX - spread * 0.35f, -10f)
            lineTo(topX + spread * 0.35f, -10f)
            lineTo(topX + spread * 1.6f + sway, depth)
            lineTo(topX - spread * 1.6f + sway, depth)
            close()
        }
        drawPath(
            shaft,
            brush = Brush.verticalGradient(
                listOf(highlight.copy(alpha = bright), Color.Transparent),
                startY = 0f,
                endY = depth,
            ),
            blendMode = BlendMode.Plus,
        )
    }

    // Everything drifts UP. Nothing else in the set does.
    for (i in 0 until 70) {
        val x = OrbMath.unitRandom(i * 3 + 1) * w + sin(t * 0.4f + i) * w * 0.012f
        val rise = Orb3D.wrap01(OrbMath.unitRandom(i * 5 + 2) + t * OrbMath.range(i * 7 + 4, 0.010f, 0.035f))
        val y = h - rise * h * 1.05f
        drawCircle(
            color = highlight.copy(alpha = OrbMath.range(i * 11 + 6, 0.10f, 0.36f) * (1f - rise * 0.6f)),
            radius = px(OrbMath.range(i * 13 + 8, 0.6f, 2.0f)),
            center = Offset(x, y),
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * DUNE — sand ridges under a low sun.
 *
 * Weather rather than architecture: the ridges are lit hard along their crests
 * and fall to shadow behind them, and grain moves ACROSS the frame rather than
 * up or down, so it reads as wind. The sun sits low and off-centre and is the
 * only light — no horizon glow is drawn over this one, because there already is
 * one and a second would flatten it.
 */
private fun DrawScope.dune(
    w: Float,
    h: Float,
    slow: Float,
    fast: Float,
    accent: Color,
    highlight: Color,
    base: Color,
) {
    val sun = Offset(w * 0.72f, h * 0.30f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(highlight.copy(alpha = 0.55f), highlight.copy(alpha = 0.14f), Color.Transparent),
            center = sun,
            radius = maxOf(w, h) * 0.55f,
        ),
        radius = maxOf(w, h) * 0.55f,
        center = sun,
        blendMode = BlendMode.Plus,
    )

    // Four ridges. Each crest is a smooth wave with its own rate, and the body
    // below it falls to shadow — the contrast between the two is the whole read.
    for (layer in 3 downTo 0) {
        val far = layer.toFloat() / 3f
        val crestY = h * (0.52f + (1f - far) * 0.26f)
        val amp = h * (0.05f + (1f - far) * 0.05f)
        val phase = slow * (0.06f + (1f - far) * 0.10f) + layer
        val lit = lerpTo(highlight, base, 0.30f + far * 0.40f)
        val shade = lerpTo(accent, Color.Black, 0.45f + far * 0.25f)

        val ridge = Path().apply {
            moveTo(-10f, h + 10f)
            val steps = 34
            for (i in 0..steps) {
                val f = i.toFloat() / steps
                val x = -10f + (w + 20f) * f
                val y = crestY - amp * (sin(phase + f * 5.4f) + 0.45f * sin(phase * 1.6f + f * 9.1f))
                lineTo(x, y)
            }
            lineTo(w + 10f, h + 10f)
            close()
        }
        drawPath(
            ridge,
            brush = Brush.verticalGradient(
                listOf(shade.copy(alpha = 0.92f), shade.copy(alpha = 0.72f)),
                startY = crestY,
                endY = h,
            ),
        )
        // The crest catches the sun.
        val crest = Path().apply {
            val steps = 34
            for (i in 0..steps) {
                val f = i.toFloat() / steps
                val x = -10f + (w + 20f) * f
                val y = crestY - amp * (sin(phase + f * 5.4f) + 0.45f * sin(phase * 1.6f + f * 9.1f))
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            crest,
            lit.copy(alpha = 0.30f + (1f - far) * 0.35f),
            style = Stroke(px(1.0f + (1f - far) * 1.4f), cap = StrokeCap.Round),
            blendMode = BlendMode.Plus,
        )
    }

    // Wind: grain crossing the frame, fast clock, near-horizontal.
    for (i in 0 until 90) {
        val lane = OrbMath.unitRandom(i * 5 + 3)
        val y = h * (0.48f + lane * 0.50f)
        val across = Orb3D.wrap01(OrbMath.unitRandom(i * 7 + 1) + fast * OrbMath.range(i * 11 + 2, 0.03f, 0.10f))
        val x = across * (w + 40f) - 20f
        drawLine(
            color = highlight.copy(alpha = OrbMath.range(i * 13 + 5, 0.04f, 0.16f)),
            start = Offset(x, y),
            end = Offset(x + px(OrbMath.range(i * 3 + 9, 4f, 14f)), y - px(1.5f)),
            strokeWidth = px(0.9f),
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
    }
}

/** Straight-line colour mix. The backdrops need it and ThemeArt's is private. */
private fun lerpTo(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = 1f,
    )
}
