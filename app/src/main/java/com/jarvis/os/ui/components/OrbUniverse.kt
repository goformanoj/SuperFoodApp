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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.OrbStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
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
    /** Mic level as a lambda — see [HudOrb]; a value here recomposes the app. */
    amplitude: () -> Float = { 0f },
    /**
     * How far through the arrival this is, `0f..1f`, driven by the host so the
     * page and the thing it grew out of move as one. Used for the entry bloom
     * and to hold the readout back until there is something to read.
     */
    entry: Float = 1f,
) {
    val scope = rememberCoroutineScope()
    // The dive position within a dimension. An Animatable rather than a plain
    // Float because it is both snapped by a gesture and flown by the entry
    // animation, and those cannot be two separate states without them disagreeing
    // the moment both run.
    val zoom = remember { Animatable(UniverseMath.START_ZOOM) }
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
    // THREE stages now, not two.
    //
    //   orb      the JARVIS orb itself, enlarged, its ring bodies revealed as
    //            galaxies — "when I zoom in, i should only see the main screen
    //            orb without Jarvis system written, and ofc a enlarged picture"
    //   galaxy   one of those, opened: its arms, and the stars along them
    //   dimension a star's own universe, and the endless dive through it
    //
    // Null at each level means "still choosing at this one".
    var galaxy by remember { mutableStateOf<GalaxySpec?>(null) }
    var chosen by remember { mutableStateOf<StarSpec?>(null) }
    // Layer four: which world you are standing off, if any.
    var world by remember { mutableStateOf<WorldOrbit?>(null) }

    // ONE GALAXY PER RING OF THE ORB. Not a number I picked: "count the number
    // of moving orbs on the Jarvis (main orb) and build galaxies according to
    // that". Arc's five rings really do open onto five galaxies.
    val orbSpec = remember(palette.orbStyle) { specFor(palette.orbStyle) }
    val galaxies = remember(orbSpec.rings.size) { galaxiesFor(orbSpec.rings.size) }
    val stars = remember(galaxy) { galaxy?.let { starsIn(it) } ?: emptyList() }
    val system = remember(chosen) { chosen?.let { systemFor(it) } }
    val marks = remember(world) { world?.let { landmarksOn(it.planet) } ?: emptyList() }

    // Where the worlds were last drawn, so a tap is tested against exactly what
    // is on screen — the same one-source-of-position rule the galaxies follow.
    //
    // DELIBERATELY NOT COMPOSE STATE. The draw phase writes this every frame; a
    // MutableState written during draw and read during composition is an endless
    // recomposition loop, and the gesture lambda reading it is a composition
    // read. A plain holder has no observers and cannot loop.
    val placedWorlds = remember { WorldPlacements() }
    // The star field's geometry, held across frames rather than rebuilt in each.
    val deepField = remember { DeepFieldCache() }
    // The sky belongs to the PLACE, and is stable for it: a backdrop that
    // reshuffles every time you come back says the place is not real.
    val skySeed = chosen?.branch?.times(7919) ?: galaxy?.let { (it.ring + 1) * 104_729 } ?: 1
    val sky = remember(skySeed) { skyFor(skySeed) }
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

    /** Up one stage: dimension → galaxy → orb → gone. */
    fun surface() {
        when {
            world != null -> {
                world = null
                pan = Offset.Zero
            }
            chosen != null -> {
                chosen = null
                scope.launch { zoom.snapTo(UniverseMath.START_ZOOM) }
            }
            galaxy != null -> galaxy = null
            else -> close()
        }
    }

    BackHandler(enabled = true) { surface() }

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
    // The star's KIND, not just its seed, decides what its universe is like — a
    // red dwarf's sky is crowded and slow, a blue giant's is vast and empty.
    // Branching the seed alone gave nine different sets of numbers drawn from one
    // set of ranges, which is why every dimension looked the same.
    val trait = remember(branch) { traitOf(chosen?.kind) }
    val shells = remember(depth, branch) {
        UniverseMath.SHELLS.associateWith {
            UniverseMath.shellAt(UniverseMath.seedFor(branch, depth, it), trait)
        }
    }

    // The orb stage draws the real renderer, which needs its scratch buffers —
    // rebuilding ring geometry into fresh lists every frame is the allocation
    // fault OrbDetail exists to remember.
    val orbDetail = remember { OrbDetail(OrbQuality.High) }

    // LIVE HANDLES FOR THE GESTURE LAMBDAS.
    //
    // `pointerInput(Unit)` is created once and never restarted, so its lambda
    // captures plain vals BY VALUE from the composition that made it. `stars`
    // comes from `remember(galaxy) { … }` and changes every time a galaxy is
    // opened — so the gesture kept hit-testing the empty list from the first
    // frame, when no galaxy was open yet. `galaxy != null` read true (that one
    // goes through a MutableState) and the branch ran against nothing.
    //
    // That is exactly "it's not letting me go inside the stars": the tap was
    // landing, the code was running, and it was asking an empty list.
    val liveStars by rememberUpdatedState(stars)
    val liveGalaxies by rememberUpdatedState(galaxies)
    val liveSpec by rememberUpdatedState(orbSpec)
    // `palette` is a PARAMETER, so the gesture lambda captured the one it was
    // built with. Changing theme with the universe open would leave the hit test
    // sized to the old orb. Same rule as everything above it: nothing a gesture
    // reads may be captured by value.
    val liveStyle by rememberUpdatedState(palette.orbStyle)

    // How far the view is zoomed at THIS stage, and how far the orb has been
    // turned. Still reset per stage — a zoom set inside one galaxy means nothing
    // in the next — but reset by ASSIGNMENT, never by re-keying the `remember`.
    //
    // These were `remember(galaxy, chosen, world) { … }`, and that is why nothing
    // zoomed. A keyed `remember` builds a NEW state object when a key changes,
    // and `pointerInput(Unit)` is created once and holds the object it captured
    // at first composition forever. So the moment a galaxy opened, the Canvas
    // read the new `view` while the pinch went on writing to the abandoned one.
    // `pan` survived only because its `remember` has no keys — which is exactly
    // the reported symptom: *"instead of zoomable, they displace, i can move them
    // around my screen"*. The one state a gesture could still reach was the one
    // that moves things.
    //
    // Same fault as the stale `stars` capture, one layer up: anything a gesture
    // touches must be reached through a handle that outlives every stage change.
    var view by remember { mutableFloatStateOf(1f) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(galaxy, chosen, world) {
        view = 1f
        yaw = 0f
        pitch = 0f
    }

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
            // TWO WAYS IN, both wanted: "keep both options in that, tap and zoom".
            // A tap enters the thing under the finger straight away; a pinch grows
            // it until you fall into whichever one is under your fingers. Pinch
            // out to leave. The tap detector goes FIRST so the transform detector
            // below is the inner one and sees a second finger before the tap does.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { at ->
                        when {
                            // On a world nothing is below; the surface is read, not
                            // hunted.
                            world != null -> Unit
                            // In a system: the nearest world to the tap.
                            chosen != null -> {
                                var best: WorldOrbit? = null
                                var bestD = span * 0.12f
                                placedWorlds.at.forEach { (wld, p) ->
                                    val d = (p - at).getDistance()
                                    if (d < bestD) {
                                        bestD = d
                                        best = wld
                                    }
                                }
                                best?.let { world = it; pan = Offset.Zero; view = 1f }
                            }
                            // In a galaxy: the nearest star. The INVERSE of what
                            // the star map draws, so zoom and pan are undone first.
                            galaxy != null -> {
                                val (nx, ny) = UniverseMath.fromScreen(
                                    at.x, at.y,
                                    size.width.toFloat(), size.height.toFloat(),
                                    pan.x, pan.y, view,
                                )
                                UniverseMath.starAt(liveStars, nx, ny)?.let {
                                    chosen = it; pan = Offset.Zero; view = 1f
                                }
                            }
                            // On the orb: the nearest galaxy riding the rings.
                            else -> {
                                val hit = galaxyAt(
                                    galaxies = liveGalaxies,
                                    spec = liveSpec,
                                    clock = clock,
                                    yaw = yaw,
                                    pitch = pitch,
                                    at = at,
                                    centre = Offset(size.width / 2f, size.height / 2f),
                                    radius = span / 2f *
                                        fitFor(liveStyle) * ORB_STAGE_ZOOM * view,
                                )
                                hit?.let { galaxy = it; pan = Offset.Zero; view = 1f }
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, gestureZoom, _ ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val cx = w / 2f
                    val cy = h / 2f
                    // TWO FINGERS ZOOM. A pinch moves gestureZoom off 1; a single
                    // pointer reports exactly 1, so the branch below it is a drag.
                    // Split this way, a pinch never doubles as a shove — the fault
                    // that once made a scene slide sideways as it scaled.
                    if (gestureZoom > 0f && abs(gestureZoom - 1f) >= PAN_ZOOM_LOCK) {
                        val newView = UniverseMath.zoomView(view, gestureZoom)
                        // The REALISED ratio, after the ceiling clamps it, so the
                        // focal point stays put even on the frame the pinch caps.
                        val k = if (view > 1e-4f) newView / view else 1f
                        // On the flat stages the thing under the fingers has to
                        // STAY under the fingers as it grows, or "pinch directly
                        // on it" is a lie — so the pan follows the focal point. The
                        // orb is a 3D assembly drawn about its own centre and does
                        // not pan; there the zoom is centred and only the CHOICE of
                        // which galaxy to dive into comes from where the fingers are.
                        if (galaxy != null) {
                            val limit = UniverseMath.panLimit(span, newView)
                            pan = Offset(
                                UniverseMath.clampPan(
                                    UniverseMath.focalPan(pan.x, centroid.x, cx, k), limit),
                                UniverseMath.clampPan(
                                    UniverseMath.focalPan(pan.y, centroid.y, cy, k), limit),
                            )
                        }
                        view = newView
                        when {
                            // Pinched to the ceiling and still pushing in: fall
                            // into whatever is nearest the fingers. If that is
                            // empty space nothing is entered and the view simply
                            // holds — you can only dive into something that is
                            // actually there. `settled` keeps a pinch landing
                            // mid-arrival from entering before the last one has.
                            settled && UniverseMath.shouldEnter(view, gestureZoom) -> {
                                when {
                                    // A world is the deepest place; nothing further.
                                    world != null -> Unit
                                    // In a system: fall onto the nearest world.
                                    // `span`, not any raw `size`: the two `size`s
                                    // in this file are different types and reaching
                                    // for one in a gesture has broken the build
                                    // three times.
                                    chosen != null -> {
                                        var best: WorldOrbit? = null
                                        var bestD = span * 0.30f
                                        placedWorlds.at.forEach { (wld, p) ->
                                            val d = (p - centroid).getDistance()
                                            if (d < bestD) {
                                                bestD = d
                                                best = wld
                                            }
                                        }
                                        best?.let { world = it; pan = Offset.Zero; view = 1f }
                                    }
                                    // In a galaxy: fall into the nearest star. The
                                    // INVERSE of what the star map draws, so zoom
                                    // and pan are undone before the star is found.
                                    galaxy != null -> {
                                        val (nx, ny) = UniverseMath.fromScreen(
                                            centroid.x, centroid.y, w, h, pan.x, pan.y, view,
                                        )
                                        UniverseMath.starAt(liveStars, nx, ny)?.let {
                                            chosen = it
                                            pan = Offset.Zero
                                            view = 1f
                                        }
                                    }
                                    // On the orb: fall into the nearest galaxy
                                    // riding the rings.
                                    else -> {
                                        val hit = galaxyAt(
                                            galaxies = liveGalaxies,
                                            spec = liveSpec,
                                            clock = clock,
                                            yaw = yaw,
                                            pitch = pitch,
                                            at = centroid,
                                            centre = Offset(cx, cy),
                                            radius = span / 2f *
                                                fitFor(liveStyle) * ORB_STAGE_ZOOM * view,
                                        )
                                        hit?.let { galaxy = it; pan = Offset.Zero; view = 1f }
                                    }
                                }
                            }
                            // Pinched below the floor and still pulling out: leave
                            // this stage. The same gesture, mirrored — one way back
                            // up rather than two.
                            view <= UniverseMath.MIN_VIEW + 0.001f && gestureZoom < 1f -> surface()
                        }
                    } else if (abs(gestureZoom - 1f) < PAN_ZOOM_LOCK) {
                        // ONE FINGER MOVES: it turns the orb, or pans a flat stage.
                        if (galaxy == null) {
                            // TURNING THE ORB — a real rotation of the whole
                            // assembly, so the rings swing through each other and
                            // the galaxies riding them go round the back.
                            yaw -= panChange.x / w * TURN_PER_WIDTH
                            pitch = (pitch - panChange.y / h * TURN_PER_WIDTH)
                                // Clamped, or the orb tumbles past its poles.
                                .coerceIn(-1.2f, 1.2f)
                        } else {
                            // The allowance is the OVERHANG: at a view of 1 the
                            // content fits, the limit is zero, and nothing can be
                            // shoved off centre.
                            val limit = UniverseMath.panLimit(span, view)
                            pan = Offset(
                                UniverseMath.clampPan(pan.x + panChange.x, limit),
                                UniverseMath.clampPan(pan.y + panChange.y, limit),
                            )
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension * 0.46f
            val eye = Offset(center.x + pan.x, center.y + pan.y)

            // The sky behind everything. "why is there no background, it looks
            // bad" — and it was: an opaque near-black rectangle with 150 tiny
            // identical dots on it. A real sky has depth (gas you are looking
            // THROUGH), a band where the galaxy is denser, and stars of visibly
            // different brightnesses.
            // THE SKY belongs to wherever you are, not to the app theme. On the
            // orb it is the theme's, because the orb IS the theme; one level in
            // it becomes the galaxy's own, and inside a star it becomes that
            // dimension's. "a dimension of the forge not necessarily be it's
            // theme colours" — so past the orb, the theme stops.
            val here = chosen?.let { paletteFor(it.branch, it.kind) } ?: galaxy?.palette
            val skyA = here?.gas?.toColor() ?: palette.accent
            val skyB = here?.arm?.toColor() ?: palette.secondary
            val skyC = here?.spark?.toColor() ?: palette.highlight
            // Its SHAPE comes from the place too, not only its colour. One sky
            // recoloured per place gave every galaxy the same weather.
            drawSky(sky, clock, skyA, skyB, skyC)
            drawDeepField(sky, clock, skyA, skyC, deepField)

            val flight = enterStar.value

            // ── STAGE 1: THE ORB ────────────────────────────────────────────
            //
            // Your actual orb, enlarged, with no wordmark across it — and the
            // lights riding its rings turn out to be galaxies. Nothing else is
            // drawn at this stage; the orb is the whole picture.
            if (galaxy == null) {
                drawOrbStage(
                    spec = orbSpec,
                    galaxies = galaxies,
                    view = view,
                    yaw = yaw,
                    pitch = pitch,
                    style = palette.orbStyle,
                    detail = orbDetail,
                    clock = clock,
                    breathe = breathe,
                    amp = amplitude().coerceIn(0f, 1f),
                    accent = palette.accent,
                    highlight = palette.highlight,
                    secondary = palette.secondary,
                    fit = fitFor(palette.orbStyle),
                )
                return@Canvas
            }

            // ── STAGE 2: A GALAXY, and the stars along its arms ─────────────
            if (flight < 0.999f) {
                drawGalaxyStage(
                    galaxy = galaxy!!,
                    stars = stars,
                    clock = clock,
                    flight = flight,
                    focus = chosen,
                    view = view,
                    pan = pan,
                )
            }

            // No star entered yet — nothing below this is drawn.
            if (chosen == null) {
                placedWorlds.at = emptyList()
                return@Canvas
            }

            val ink = here ?: galaxy!!.palette
            val standing = world

            if (standing == null) {
                // ── STAGE 3: THE SYSTEM ─────────────────────────────────────
                placedWorlds.at = drawSystemStage(
                    system = system!!,
                    ink = ink,
                    clock = clock,
                    view = view,
                    pan = pan,
                    alpha = flight,
                )
            } else {
                // ── STAGE 4: A WORLD, and what is on it ─────────────────────
                placedWorlds.at = emptyList()
                drawPlanetStage(
                    world = standing,
                    marks = marks,
                    ink = ink,
                    clock = clock,
                    view = view,
                    pan = pan,
                    alpha = flight,
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
                            skyC.copy(alpha = bloom * 0.35f),
                            skyA.copy(alpha = bloom * 0.12f),
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
            val inGalaxy = galaxy
            if (star == null) {
                StageHud(
                    title = inGalaxy?.designation ?: "JARVIS",
                    subtitle = inGalaxy?.let { "${it.kind.label}  ·  ${it.stars} STARS" }
                        ?: "${galaxies.size} GALAXIES ON ${galaxies.size} RINGS",
                    hint = if (inGalaxy == null) {
                        "TAP OR PINCH A GALAXY TO DIVE IN  ·  DRAG TO TURN THE ORB"
                    } else {
                        "TAP OR PINCH A STAR TO ENTER IT  ·  PINCH OUT TO GO BACK"
                    },
                    tint = inGalaxy?.palette?.spark?.toColor() ?: palette.highlight,
                    accent = inGalaxy?.palette?.arm?.toColor() ?: palette.accent,
                    alpha = readout,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val ink = chosen?.let { paletteFor(it.branch, it.kind) }
                val standing = world
                StageHud(
                    title = standing?.planet?.designation ?: star.designation,
                    subtitle = if (standing != null) {
                        "${standing.planet.kind.label}  ·  ${marks.size} FEATURES"
                    } else {
                        "${star.kind.label}  ·  ${system?.worlds?.size ?: 0} WORLDS"
                    },
                    hint = if (standing != null) {
                        standing.planet.kind.summary.uppercase()
                    } else {
                        "TAP OR PINCH A WORLD TO LAND  ·  PINCH OUT TO GO BACK"
                    },
                    tint = ink?.spark?.toColor() ?: palette.highlight,
                    accent = ink?.arm?.toColor() ?: palette.accent,
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
            text = "PINCH IN TO DIVE  ·  PINCH OUT TO LEAVE",
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
private fun StageHud(
    title: String,
    subtitle: String,
    hint: String,
    tint: Color,
    accent: Color,
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
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 6.sp),
                color = tint.copy(alpha = alpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = accent.copy(alpha = 0.60f * alpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = accent.copy(alpha = 0.45f * alpha),
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
    /** The dimension's colour temperature: 0 cool toward accent, 1 hot toward highlight. */
    heat: Float,
    at: Offset,
    radius: Float,
    alpha: Float,
    core: Float,
    clock: Float,
    coolAccent: Color,
    hotHighlight: Color,
    secondary: Color,
    /** Which dimension this is, so its bodies grow worlds that suit it. */
    star: StarKind?,
    viewport: Float,
) {
    // Detail follows apparent size, not shell index. A shell can be anywhere from
    // a knot a few pixels across to three screens wide, and drawing 220 dust
    // motes into a nine-pixel dot costs exactly as much as drawing them into a
    // full-screen galaxy while showing none of it.
    val presence = (radius / viewport).coerceIn(0f, 1.6f)
    // Every colour in this shell is pulled toward the dimension's temperature, so
    // a blue giant's universe is visibly hotter than a red dwarf's before a single
    // structure is drawn.
    val accent = lerpColour(coolAccent, hotHighlight, heat * 0.55f)
    val highlight = lerpColour(coolAccent, hotHighlight, 0.45f + heat * 0.55f)
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
    spec.satellites.forEachIndexed { i, moon ->
        drawSatellite(
            moon, at, radius, alpha, presence, clock, turn, accent, highlight,
            planet = planetFor(spec.seed * 131 + i, star),
        )
    }

    if (core > 0.004f) {
        drawCore(at, radius * spec.coreSize, alpha * core, highlight, accent)
    }
}

/**
 * Where the worlds of a system were last drawn.
 *
 * A plain mutable holder, not Compose state, and that is the point: the draw
 * phase writes it every frame and the gesture reads it, so making it observable
 * would recompose on every frame and read-during-composition would loop.
 */
private class WorldPlacements {
    var at: List<Pair<WorldOrbit, Offset>> = emptyList()
}

/** How much larger the orb is here than on the home screen. */
/**
 * The shorter side of a gesture frame, as a **Float**.
 *
 * `PointerInputScope.size` is an [androidx.compose.ui.unit.IntSize]; `DrawScope.size`
 * is a [androidx.compose.ui.geometry.Size]. They read identically, they are
 * different types, and in this file the gesture handlers and the Canvas sit thirty
 * lines apart. That has now cost three separate build failures: twice for
 * `minDimension`, which only one of them has, and once for `size.width` coming out
 * as an `Int` where a `Float` was wanted — arithmetic hides it, because `Int * Float`
 * is a `Float`, so it only surfaces at a function boundary.
 *
 * One named spelling, used everywhere in the gesture code, is the fix. Reaching for
 * `size` directly inside a `pointerInput` is the smell.
 */
private val PointerInputScope.span: Float
    get() = minOf(size.width, size.height).toFloat()

// How large the orb is drawn on its own stage, before any pinch. At 1.55 the
// widest ring reached ~1.33x the frame — off both edges — so the galaxies riding
// it sat at the screen's corners and were awkward to aim at ("the main orb is way
// too big for me to pick a galaxy"). Pulled in so the whole assembly, rings and
// all, sits inside the frame with margin to spare; still clearly enlarged versus
// the home orb. A feel constant — tune on device.
private const val ORB_STAGE_ZOOM = 1.15f

// The zoom range of a stage — floor (pinch out to leave) and ceiling (pinch in
// to enter) — now lives in UniverseMath, where it is pure and tested off device.

/** A full drag across the screen turns the orb by about this much, in radians. */
private const val TURN_PER_WIDTH = 3.4f

/**
 * How much scaling a frame may carry and still count as a drag.
 *
 * A single pointer reports a zoom of exactly `1f`, so anything above this is two
 * fingers, and two fingers mean zoom. Small rather than zero because a
 * near-stationary second finger still jitters a fraction of a percent.
 */
private const val PAN_ZOOM_LOCK = 0.006f

/** Ink is Compose-free so it can be tested; this is the only place it converts. */
private fun Ink.toColor(): Color = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 1f)

/**
 * Where the galaxy riding ring [i] is, right now, on screen.
 *
 * Shared by the drawing and the hit test on purpose. Two copies of this — one to
 * place the light and one to decide what a tap landed on — is a bug waiting for
 * the day someone retunes one of them, and the symptom would be taps that miss by
 * a few degrees with nothing visibly wrong.
 */
private fun galaxyOn(
    spec: Orb3DSpec,
    i: Int,
    clock: Float,
    yaw: Float,
    pitch: Float,
    centre: Offset,
    radius: Float,
): Offset {
    val ring = spec.rings[i]
    val t = clock
    val rr = radius * ring.radius
    val tiltX = ring.tiltX + sin(t * ring.precession) * PRECESS_SWING_X + pitch
    val tiltY = ring.tiltY + cos(t * ring.precession * 0.7f) * PRECESS_SWING_Y + yaw
    val a = t * ring.spin
    val p = Orb3D.project(
        Orb3D.rotateY(Orb3D.rotateX(Vec3(cos(a) * rr, sin(a) * rr, 0f), tiltX), tiltY),
        radius * CAMERA_DISTANCE,
        radius * FOCAL,
    )
    return Offset(centre.x + p.x, centre.y + p.y)
}

/** Which galaxy a tap landed on, or null. Generous, because a finger is not a point. */
private fun galaxyAt(
    galaxies: List<GalaxySpec>,
    spec: Orb3DSpec,
    clock: Float,
    yaw: Float,
    pitch: Float,
    at: Offset,
    centre: Offset,
    radius: Float,
): GalaxySpec? {
    var best: GalaxySpec? = null
    var bestDistance = radius * 0.30f
    galaxies.forEachIndexed { i, g ->
        if (i >= spec.rings.size) return@forEachIndexed
        val p = galaxyOn(spec, i, clock, yaw, pitch, centre, radius)
        val d = (p - at).getDistance()
        if (d < bestDistance) {
            bestDistance = d
            best = g
        }
    }
    return best
}

/**
 * STAGE ONE — the orb itself, enlarged.
 *
 * *"when I zoom in, i should only see the main screen orb without Jarvis system
 * written, and ofc a enlarged picture"*. So this is the genuine renderer, the
 * user's genuine theme, at 1.55x and with no wordmark — not a picture of the orb
 * but the orb, which is the only version of this that survives changing theme.
 *
 * What is new is what rides it. Each ring carries one bright body, and each of
 * those is a galaxy — larger and hotter than the ring's own travelling highlight
 * so it reads as a destination rather than as decoration, with a halo that
 * breathes on its own clock so the eye is drawn to it.
 */
private fun DrawScope.drawOrbStage(
    spec: Orb3DSpec,
    galaxies: List<GalaxySpec>,
    view: Float,
    yaw: Float,
    pitch: Float,
    style: OrbStyle,
    detail: OrbDetail,
    clock: Float,
    breathe: Float,
    amp: Float,
    accent: Color,
    highlight: Color,
    secondary: Color,
    fit: Float,
) {
    val radius = size.minDimension / 2f * fit * ORB_STAGE_ZOOM * view
    drawOrb3D(
        style = style,
        detail = detail,
        f = OrbFrame(
            radius = radius,
            accent = accent,
            secondary = secondary,
            highlight = highlight,
            spin = clock * 57.3f,
            drift = clock * 57.3f,
            counter = -clock * 57.3f,
            breathe = breathe,
            amp = amp,
            yaw = yaw,
            pitch = pitch,
        ),
        accent = accent,
        highlight = highlight,
        secondary = secondary,
    )

    // The galaxies, riding the rings.
    galaxies.forEachIndexed { i, g ->
        if (i >= spec.rings.size) return@forEachIndexed
        val at = galaxyOn(spec, i, clock, yaw, pitch, center, radius)
        val pulse = 0.82f + 0.18f * sin(clock * 1.3f + i * 1.7f)
        // Size says how much is in it. A nine-star galaxy and a five-star one
        // were the same dot before, which threw away a difference that was
        // already known and already meaningful.
        val r = size.minDimension * 0.016f * pulse * (0.78f + (g.stars - 5) * 0.075f)
        drawGalaxyToken(at, r, g, clock)
    }
}

/**
 * A galaxy as it appears riding the orb's rings: small, but a miniature of the
 * thing itself rather than a coloured dot.
 *
 * Every one of these used to be identical — two halos, the same white cross, and
 * a **pure white core** at the same size, with only the palette differing. The
 * white core is the part that did the damage: it is the brightest thing in the
 * token, so it dominated, and the one axis that did vary was drowned by it.
 * *"it's hard to distinguish between different galaxies"*.
 *
 * Each now shows its own shape at a glance — a spiral has arms, a lenticular is a
 * ring with nothing in the middle, an irregular is lumpy and off-centre — and its
 * core carries its colour instead of being white. At seventeen pixels the shape
 * cue does more work than the colour, which is why it is worth drawing rather
 * than tinting.
 */
private fun DrawScope.drawGalaxyToken(at: Offset, r: Float, g: GalaxySpec, clock: Float) {
    val ink = g.palette
    val core = ink.core.toColor()
    val arm = ink.arm.toColor()
    val gas = ink.gas.toColor()
    val spark = ink.spark.toColor()
    // Its own lean, so a row of them is not a row of identically-tipped ovals.
    val lean = g.twist * 0.35f
    val flat = 0.35f + g.tilt * 0.55f

    halo(at, r * 6.0f, gas, 0.26f)

    withTransform({ rotate(lean * 57.3f, at) }) {
        when (g.kind) {
            GalaxyKind.Spiral, GalaxyKind.Barred -> {
                // Arms, as short arcs sweeping out of the middle. Two or three
                // strokes is enough to read as a spiral at this size — more just
                // fills the disc back in.
                for (a in 0 until g.arms.coerceAtMost(3)) {
                    val path = Path()
                    val base = a * (OrbMath.TAU / g.arms.coerceAtMost(3)) + clock * 0.25f
                    for (k in 0..10) {
                        val f = 0.25f + (k / 10f) * 0.95f
                        val ang = base + f * g.twist * 0.9f
                        val x = at.x + cos(ang) * r * f * 2.4f
                        val y = at.y + sin(ang) * r * f * 2.4f * flat
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = arm.copy(alpha = 0.75f),
                        style = Stroke(r * 0.42f, cap = StrokeCap.Round),
                        blendMode = BlendMode.Plus,
                    )
                }
                if (g.bar > 0.01f) {
                    drawLine(
                        color = core.copy(alpha = 0.85f),
                        start = Offset(at.x - r * 1.5f, at.y),
                        end = Offset(at.x + r * 1.5f, at.y),
                        strokeWidth = r * 0.5f,
                        cap = StrokeCap.Round,
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            GalaxyKind.Elliptical -> {
                // No structure at all, and that IS the shape: a smooth graded
                // oval. It earns the cross the others no longer get, because a
                // compact bright thing is what it is.
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(core.copy(alpha = 0.95f), arm.copy(alpha = 0.45f), Color.Transparent),
                        center = at,
                        radius = r * 2.6f,
                    ),
                    topLeft = Offset(at.x - r * 2.6f, at.y - r * 2.6f * flat),
                    size = Size(r * 5.2f, r * 5.2f * flat),
                    blendMode = BlendMode.Plus,
                )
                spikes(at, r * 5.0f, spark, 0.40f, clock * 0.2f, 2)
            }

            GalaxyKind.Irregular -> {
                // Lumpy and off-centre. Symmetry is the thing it must not have,
                // so the clumps are placed by seed and never balanced.
                for (k in 0 until 4) {
                    val a = OrbMath.range(g.ring * 211 + k, 0f, OrbMath.TAU)
                    val d = r * OrbMath.range(g.ring * 223 + k, 0.4f, 1.9f)
                    val q = Offset(at.x + cos(a) * d, at.y + sin(a) * d * flat)
                    val kr = r * OrbMath.range(g.ring * 227 + k, 0.7f, 1.5f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(arm.copy(alpha = 0.80f), Color.Transparent),
                            center = q,
                            radius = kr,
                        ),
                        radius = kr,
                        center = q,
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            GalaxyKind.Lenticular -> {
                // A ring with nothing in the middle — the one token you can
                // identify from the corner of your eye, because it is the only
                // one with a hole in it.
                drawOval(
                    color = arm.copy(alpha = 0.85f),
                    topLeft = Offset(at.x - r * 2.4f, at.y - r * 2.4f * flat),
                    size = Size(r * 4.8f, r * 4.8f * flat),
                    style = Stroke(r * 0.42f),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

    // The core, in the galaxy's OWN colour. This was pure white on every one of
    // them, which is the brightest thing in the token — so the only axis that did
    // vary was drowned by the one that did not.
    if (g.kind != GalaxyKind.Lenticular) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.85f), core.copy(alpha = 0.75f), Color.Transparent),
                center = at,
                radius = r * (0.6f + g.core * 2.4f),
            ),
            radius = r * (0.6f + g.core * 2.4f),
            center = at,
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * STAGE TWO — one galaxy, and the stars strung along its arms.
 *
 * Drawn in the galaxy's OWN colours. Five shapes, and the shape is the first
 * thing you see of one: a spiral winds, a barred spiral has a straight spine
 * across the middle, an elliptical is a smooth swarm with no structure left, an
 * irregular has had its structure destroyed, a ring has had its middle blown out.
 */
private fun DrawScope.drawGalaxyStage(
    galaxy: GalaxySpec,
    stars: List<StarSpec>,
    clock: Float,
    flight: Float,
    focus: StarSpec?,
    view: Float,
    pan: Offset,
) {
    val fade = (1f - flight).coerceIn(0f, 1f)
    if (fade <= 0.01f) return
    val ink = galaxy.palette
    val arm = ink.arm.toColor()
    val gas = ink.gas.toColor()
    val spark = ink.spark.toColor()
    val r = size.minDimension * 0.42f * view
    val at = center + pan
    val turn = clock * 0.06f
    val squash = galaxy.tilt

    // THE HUB, at this galaxy's own size. It was a flat 0.34 for every galaxy in
    // the universe — and the bulge is the most visible thing about one. A small
    // hub with long arms and a huge hub with stubs are not the same object at a
    // glance, and nothing else has to differ for that to read.
    val hub = r * galaxy.core
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                ink.core.toColor().copy(alpha = 0.75f * fade),
                spark.copy(alpha = 0.35f * fade),
                Color.Transparent,
            ),
            center = at,
            radius = hub,
        ),
        radius = hub,
        center = at,
        blendMode = BlendMode.Plus,
    )

    // A bar, where there is one — and there may be one on an ordinary spiral,
    // not only on a barred galaxy. Drawn before the disc so the arms lie over it.
    if (galaxy.bar > 0.01f) {
        val bx = cos(turn) * r * galaxy.bar
        val by = sin(turn) * r * galaxy.bar * squash
        drawLine(
            brush = Brush.linearGradient(
                listOf(Color.Transparent, ink.core.toColor().copy(alpha = 0.45f * fade), Color.Transparent),
                start = Offset(at.x - bx, at.y - by),
                end = Offset(at.x + bx, at.y + by),
            ),
            start = Offset(at.x - bx, at.y - by),
            end = Offset(at.x + bx, at.y + by),
            strokeWidth = r * 0.11f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
    }

    // The body, by shape.
    val motes = 900
    for (i in 0 until motes) {
        val seed = galaxy.ring * 3301 + i
        val f: Float
        val angle: Float
        when (galaxy.kind) {
            GalaxyKind.Spiral, GalaxyKind.Barred -> {
                val armIdx = i % galaxy.arms
                f = OrbMath.unitRandom(seed * 3 + 1).let { it * it }
                val scatter = (OrbMath.unitRandom(seed * 7 + 5) - 0.5f) *
                    galaxy.scatter * (1f - f * 0.55f)
                // A barred spiral holds its inner stars on a straight spine
                // instead of letting them wind — that bar IS the difference.
                val wind = if (galaxy.kind == GalaxyKind.Barred && f < 0.34f) 0f else f * galaxy.twist
                angle = turn + armIdx * (OrbMath.TAU / galaxy.arms) + wind + scatter
            }
            GalaxyKind.Elliptical -> {
                f = OrbMath.unitRandom(seed * 5 + 3) * OrbMath.unitRandom(seed * 11 + 7)
                angle = OrbMath.unitRandom(seed * 13 + 2) * OrbMath.TAU + turn * (0.3f + f)
            }
            GalaxyKind.Irregular -> {
                // Clumped rather than smooth: a few knots with gaps between them.
                val knot = i % 5
                f = (OrbMath.range(knot * 97 + 3, 0.25f, 0.9f) +
                    (OrbMath.unitRandom(seed * 3 + 1) - 0.5f) * 0.30f).coerceIn(0.05f, 1.05f)
                angle = OrbMath.range(knot * 53 + 7, 0f, OrbMath.TAU) +
                    (OrbMath.unitRandom(seed * 9 + 4) - 0.5f) * 0.8f + turn
            }
            GalaxyKind.Lenticular -> {
                // A ring: nothing in the middle at all.
                f = OrbMath.range(seed * 3 + 1, 0.62f, 1.0f)
                angle = OrbMath.unitRandom(seed * 7 + 2) * OrbMath.TAU + turn
            }
        }
        // LOPSIDED. Real galaxies are rarely balanced — a neighbour has usually
        // pulled one side out — and perfect symmetry is most of why a generated
        // one looks generated. One side reaches further than the other.
        val pull = 1f + galaxy.lopsided * 0.45f * cos(angle - turn)
        val rr = r * (0.06f + f * 0.98f) * pull
        val p = Offset(at.x + cos(angle) * rr, at.y + sin(angle) * rr * squash)
        val colour = if (i % 7 == 0) spark else if (i % 3 == 0) gas else arm
        drawCircle(
            color = colour.copy(alpha = fade * (0.10f + (1f - f) * 0.45f) * OrbMath.range(seed * 5 + 9, 0.4f, 1f)),
            radius = px(0.4f + OrbMath.unitRandom(seed * 17 + 3) * 1.5f),
            center = p,
            blendMode = BlendMode.Plus,
        )
    }

    // The gas it sits in, over the top so it reads as being in front of some of
    // the stars and behind others.
    for (i in 0 until 5) {
        val drift = turn * 3f + i * 1.9f
        val off = r * OrbMath.range(galaxy.ring * 71 + i, 0.10f, 0.55f)
        val q = Offset(at.x + cos(drift) * off, at.y + sin(drift) * off * squash)
        val size2 = r * OrbMath.range(galaxy.ring * 89 + i, 0.45f, 0.95f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(gas.copy(alpha = 0.10f * fade), Color.Transparent),
                center = q,
                radius = size2,
            ),
            radius = size2,
            center = q,
            blendMode = BlendMode.Plus,
        )
    }

    // DUST LANES. The only feature here that takes light AWAY, which is why it is
    // worth having: every other addition brightens the same shape, and a dark
    // lane cutting an arm changes the shape itself.
    if (galaxy.dust > 0.04f) {
        for (a in 0 until galaxy.arms) {
            val lane = Path()
            val steps = 26
            for (k in 0..steps) {
                val f = 0.14f + (k.toFloat() / steps) * 0.86f
                val ang = turn + a * (OrbMath.TAU / galaxy.arms) + f * galaxy.twist + 0.16f
                val rr = r * f * (1f + galaxy.lopsided * 0.45f * cos(ang - turn))
                val x = at.x + cos(ang) * rr
                val y = at.y + sin(ang) * rr * squash
                if (k == 0) lane.moveTo(x, y) else lane.lineTo(x, y)
            }
            drawPath(
                path = lane,
                color = Color.Black.copy(alpha = 0.34f * galaxy.dust * fade),
                style = Stroke(r * 0.055f, cap = StrokeCap.Round),
            )
        }
    }

    // STAR-FORMING KNOTS along the arms. A quiet galaxy has none; an irregular is
    // irregular precisely because something is happening to it, so it always has.
    if (galaxy.starburst > 0.05f) {
        val knots = (galaxy.starburst * 22f).toInt()
        for (i in 0 until knots) {
            val f = OrbMath.range(galaxy.ring * 137 + i, 0.22f, 1f)
            val a = turn + (i % galaxy.arms) * (OrbMath.TAU / galaxy.arms) +
                f * galaxy.twist + OrbMath.range(galaxy.ring * 149 + i, -0.18f, 0.18f)
            val rr = r * f * (1f + galaxy.lopsided * 0.45f * cos(a - turn))
            val q = Offset(at.x + cos(a) * rr, at.y + sin(a) * rr * squash)
            val kr = r * OrbMath.range(galaxy.ring * 151 + i, 0.02f, 0.055f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(spark.copy(alpha = 0.55f * fade), Color.Transparent),
                    center = q,
                    radius = kr,
                ),
                radius = kr,
                center = q,
                blendMode = BlendMode.Plus,
            )
        }
    }

    // GLOBULAR CLUSTERS in the halo, well outside the disc and not flattened with
    // it — a halo is a sphere, and drawing these on the disc's ellipse would say
    // the generator does not know that.
    for (i in 0 until galaxy.clusters) {
        val a = OrbMath.range(galaxy.ring * 163 + i, 0f, OrbMath.TAU)
        val d = r * OrbMath.range(galaxy.ring * 167 + i, 0.75f, 1.45f)
        val q = Offset(at.x + cos(a) * d, at.y + sin(a) * d * 0.92f)
        halo(q, r * 0.035f, arm, 0.30f * fade)
        point(q, r * 0.006f, 0.55f * fade, spark)
    }

    // A SATELLITE, where this galaxy has one. It is also the reason it is
    // lopsided, so the two belong together.
    if (galaxy.companion >= 0f) {
        val d = r * 1.15f
        val q = Offset(
            at.x + cos(galaxy.companion + turn * 0.3f) * d,
            at.y + sin(galaxy.companion + turn * 0.3f) * d * squash,
        )
        val cr = r * 0.16f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    ink.core.toColor().copy(alpha = 0.40f * fade),
                    gas.copy(alpha = 0.16f * fade),
                    Color.Transparent,
                ),
                center = q,
                radius = cr,
            ),
            radius = cr,
            center = q,
            blendMode = BlendMode.Plus,
        )
        for (i in 0 until 40) {
            val a = OrbMath.range(galaxy.ring * 173 + i, 0f, OrbMath.TAU)
            val rr = cr * sqrt(OrbMath.unitRandom(galaxy.ring * 179 + i))
            drawCircle(
                color = arm.copy(alpha = 0.35f * fade),
                radius = px(0.5f),
                center = Offset(q.x + cos(a) * rr, q.y + sin(a) * rr * 0.8f),
                blendMode = BlendMode.Plus,
            )
        }
    }

    // The stars you can enter, drawn over their own galaxy.
    drawStarMap(stars, clock, flight, focus, arm, spark, gas, view, pan)
}

/**
 * The chart you arrive on: nine stars, six kinds, each drawn as itself.
 *
 * REBUILT — "these don't look like stars in anyway". The first version drew each
 * star as a large soft radial gradient with a thin circle around it, which is a
 * photograph of an out-of-focus light, not a star. Two things were wrong and both
 * are structural:
 *
 *  - **No hard core.** A star is a point source. What makes it read as one is a
 *    small, almost pure-white centre that is *sharp* against a halo, not a blob
 *    that fades continuously from the middle outward.
 *  - **No spikes.** The four-point diffraction cross is the single most
 *    recognisable feature of a bright star in any photograph, and every kind here
 *    now has one, scaled to its brightness.
 *
 * The outline rings are gone too: they read as targeting reticles and were the
 * main reason the chart looked like a UI rather than a sky.
 */
private fun DrawScope.drawStarMap(
    stars: List<StarSpec>,
    clock: Float,
    flight: Float,
    focus: StarSpec?,
    accent: Color,
    highlight: Color,
    secondary: Color,
    /** The galaxy's own zoom and drag, so the stars travel WITH it. */
    view: Float,
    pan: Offset,
) {
    val fade = 1f - flight
    stars.forEach { star ->
        val picked = focus != null && focus.branch == star.branch
        // THROUGH THE SHARED TRANSFORM. This used to be a bare
        // `star.x * size.width`, which took neither the zoom nor the pan — so the
        // galaxy body scaled and panned out from under its own stars, and the hit
        // test (doing the same bare division in reverse) stopped agreeing with the
        // screen the moment anything moved.
        val (hx, hy) = UniverseMath.onScreen(
            star.x, star.y, size.width, size.height, pan.x, pan.y, view,
        )
        val home = Offset(hx, hy)
        val at: Offset
        val grow: Float
        val alpha: Float
        if (picked) {
            at = Offset(
                home.x + (center.x - home.x) * flight,
                home.y + (center.y - home.y) * flight,
            )
            grow = 1f + flight * 7f
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

        // MUCH smaller than the first version. A star is a point with light around
        // it; the previous radii made every one of them a disc.
        // Scaled by the view as well: a galaxy blown up to four times its size
        // with the same pinhead stars reads as a picture behind glass.
        val r = size.minDimension * 0.011f * star.size * star.kind.scale * grow *
            view.coerceIn(0.7f, 2.2f)
        // FROM THE STAR, NOT FROM THE THEME. This was
        // `lerpColour(accent, highlight, kind.heat)` — the app's two colours mixed
        // by kind, which gives a whole galaxy six possible star colours drawn from
        // the same pair. A star's colour is its temperature, and no theme gets a
        // say in it.
        val colour = starInk(star.kind, star.branch).toColor()
        val pulse = 0.88f + 0.12f * sin(clock * 1.7f + star.phase)
        // Per-star optics: how many spikes it throws and which way they lie. Two
        // stars of one kind are now a different figure, not the same cross twice.
        val arms = 2 + (OrbMath.unitRandom(star.branch * 3407 + 19) * 2.4f).toInt()
        val lean = OrbMath.unitRandom(star.branch * 3517 + 23) * OrbMath.PI_F

        when (star.kind) {
            StarKind.BlueGiant -> {
                halo(at, r * 7f, colour, alpha * 0.30f * pulse)
                spikes(at, r * 9f, colour, alpha * 0.55f, lean + clock * 0.05f, arms)
                point(at, r * 1.5f, alpha * pulse, colour)
            }
            StarKind.RedDwarf -> {
                halo(at, r * 3.2f, colour, alpha * 0.34f * pulse)
                spikes(at, r * 3.4f, colour, alpha * 0.28f, lean + clock * 0.03f, arms)
                point(at, r * 0.72f, alpha * 0.82f * pulse, colour)
            }
            StarKind.Binary -> {
                // Two points about a shared centre, close enough to read as a pair
                // rather than as two separate stars on the chart.
                val a = clock * 0.55f + star.phase
                val sep = r * 2.1f
                listOf(0f, OrbMath.PI_F).forEachIndexed { i, off ->
                    val q = Offset(at.x + cos(a + off) * sep, at.y + sin(a + off) * sep * 0.62f)
                    // A binary is TWO stars, so it gets two colours — the pair
                    // seeded apart rather than one colour used twice.
                    val c = if (i == 0) colour else starInk(StarKind.RedDwarf, star.branch + 7).toColor()
                    halo(q, r * 2.8f, c, alpha * 0.30f)
                    spikes(q, r * 3.0f, c, alpha * 0.30f, lean + i * 0.6f, arms)
                    point(q, r * 0.85f, alpha * 0.92f, c)
                }
            }
            StarKind.Pulsar -> {
                // The beam is what names it. Narrow, hard, and sweeping fast.
                val sweep = clock * 3.1f + star.phase
                halo(at, r * 2.6f, colour, alpha * 0.34f)
                for (dir in listOf(1f, -1f)) {
                    val len = r * 16f * dir
                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(colour.copy(alpha = alpha * 0.60f), Color.Transparent),
                            start = at,
                            end = Offset(at.x + cos(sweep) * len, at.y + sin(sweep) * len),
                        ),
                        start = at,
                        end = Offset(at.x + cos(sweep) * len, at.y + sin(sweep) * len),
                        strokeWidth = px(2.4f),
                        cap = StrokeCap.Round,
                        blendMode = BlendMode.Plus,
                    )
                }
                point(at, r * 0.9f, alpha, colour)
            }
            StarKind.Protostar -> {
                // Wrapped in its cloud: the halo is the subject, the star barely
                // visible through it, and no spikes because nothing gets out clean.
                for (i in 0 until 4) {
                    val drift = clock * 0.20f + i * 1.6f + star.phase
                    val off = r * OrbMath.range(star.branch * 31 + i, 1.0f, 3.4f)
                    val q = Offset(at.x + cos(drift) * off, at.y + sin(drift * 1.3f) * off * 0.8f)
                    halo(q, r * OrbMath.range(star.branch * 17 + i, 4f, 7f), secondary, alpha * 0.16f)
                }
                halo(at, r * 3f, colour, alpha * 0.28f)
                point(at, r * 0.8f, alpha * 0.55f, colour)
            }
            StarKind.WhiteDwarf -> {
                // Dense and dim: almost nothing but the point itself.
                halo(at, r * 1.9f, colour, alpha * 0.26f)
                spikes(at, r * 2.6f, colour, alpha * 0.34f, lean, arms)
                point(at, r * 0.62f, alpha * 0.95f, colour)
            }
        }
    }
}

/**
 * The hard centre. This is the whole difference between a star and a smudge: it
 * is nearly pure white, small, and does NOT fade gradually — a tight bright disc
 * with only a hairline of falloff at its edge.
 */
/**
 * The hard core of a light source.
 *
 * **Tinted, with a white centre** — not a white disc. Every star on the chart used
 * to be pure white here whatever kind it was, and a white disc is a white disc: the
 * halo around it carried all the colour, at a fraction of the brightness, so the
 * eye read nine identical lights. A real star shows its temperature in the core and
 * saturates to white only at the very middle, which is both truer and the single
 * change that makes a star chart look like one.
 */
private fun DrawScope.point(
    at: Offset,
    radius: Float,
    alpha: Float,
    tint: Color = Color.White,
) {
    if (radius <= 0f || alpha <= 0.004f) return
    val r = radius.coerceAtLeast(px(0.9f))
    drawCircle(
        color = tint.copy(alpha = alpha.coerceAtMost(1f)),
        radius = r,
        center = at,
        blendMode = BlendMode.Plus,
    )
    drawCircle(
        color = Color.White.copy(alpha = (alpha * 0.85f).coerceAtMost(1f)),
        radius = r * 0.46f,
        center = at,
        blendMode = BlendMode.Plus,
    )
}

/** The light around it. Soft, coloured, and ending in nothing. */
private fun DrawScope.halo(at: Offset, radius: Float, colour: Color, alpha: Float) {
    if (radius <= 0f || alpha <= 0.004f) return
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                colour.copy(alpha = alpha),
                colour.copy(alpha = alpha * 0.30f),
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
 * The four-point diffraction cross.
 *
 * The most recognisable thing about a bright star in a photograph, and the single
 * biggest reason the first chart did not read as a sky. Horizontal arm longer
 * than the vertical, both tapering to nothing, exactly as a real one does.
 */
/**
 * Diffraction spikes.
 *
 * [arms] is per star rather than fixed at the classic four: the number of spikes a
 * bright source shows depends on the optics it is seen through, and varying it is
 * a strong, cheap difference between two stars that would otherwise be the same
 * cross at two sizes.
 */
private fun DrawScope.spikes(
    at: Offset,
    reach: Float,
    colour: Color,
    alpha: Float,
    tilt: Float,
    arms: Int = 2,
) {
    if (reach <= 0f || alpha <= 0.004f) return
    val rays = (0 until arms).map { i ->
        val a = tilt + i * OrbMath.PI_F / arms
        // The first arm is the long one; the rest are shorter, so the figure has a
        // dominant axis instead of being a rosette.
        Triple(cos(a), sin(a), reach * (if (i == 0) 1f else 0.55f))
    }
    for ((dx, dy, len) in rays) {
        val from = Offset(at.x - dx * len, at.y - dy * len)
        val to = Offset(at.x + dx * len, at.y + dy * len)
        drawLine(
            brush = Brush.linearGradient(
                listOf(Color.Transparent, colour.copy(alpha = alpha), Color.Transparent),
                start = from,
                end = to,
            ),
            start = from,
            end = to,
            strokeWidth = px(1.1f),
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
    }
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
    planet: PlanetSpec,
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
        drawPlanet(planet, p, body * planet.kind.bulk, alpha, colour, accent, highlight)
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

/**
 * LAYER THREE — a star system.
 *
 * *"any one star, the actual content, the planets and everything"*. The sun at
 * the middle, its worlds on real orbits at real relative speeds, and the belt of
 * rubble most systems have. Orbits are drawn as thin ellipses so the mechanism is
 * legible even when a world is round the far side.
 *
 * This replaced the endless self-similar shells that used to be layer three.
 * Those were the same structure at every scale, which is elegant and tells you
 * nothing: a place with a fixed cast — a sun, six worlds, a belt — is somewhere
 * you can learn, and the previous version was somewhere you could only fall
 * through.
 */
private fun DrawScope.drawSystemStage(
    system: SystemSpec,
    ink: DimensionPalette,
    clock: Float,
    view: Float,
    pan: Offset,
    alpha: Float,
): List<Pair<WorldOrbit, Offset>> {
    val at = center + pan
    // Orbits arrive normalised — the outermost world is exactly 1 — so this is
    // literally "how wide the whole system is drawn", and every system fills the
    // frame the same way whether it holds three worlds or nine.
    val unit = size.minDimension * 0.42f * view
    val arm = ink.arm.toColor()
    val spark = ink.spark.toColor()

    // The sun.
    // Sized against the INNERMOST ORBIT, which normalisation now fixes at about
    // 0.17 of the system's width. A sun at 0.085 and a gas giant at 0.085 both
    // reach 0.085 from their own centres, and 0.085 + 0.085 is exactly the
    // innermost orbit — the biggest worlds would have touched their star.
    val sunR = unit * 0.06f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = alpha),
                ink.core.toColor().copy(alpha = alpha * 0.85f),
                spark.copy(alpha = alpha * 0.30f),
                Color.Transparent,
            ),
            center = at,
            radius = sunR * 4.5f,
        ),
        radius = sunR * 4.5f,
        center = at,
        blendMode = BlendMode.Plus,
    )
    point(at, sunR, alpha)
    spikes(at, sunR * 7f, Color.White, alpha * 0.45f, clock * 0.04f)

    // The belt, before the worlds so they pass in front of it.
    if (system.belt > 0f) {
        for (i in 0 until system.beltDensity) {
            val a = OrbMath.unitRandom(i * 7 + 3) * OrbMath.TAU + clock * 0.05f
            val rr = unit * system.belt * OrbMath.range(i * 11 + 5, 0.94f, 1.07f)
            drawCircle(
                color = arm.copy(alpha = alpha * OrbMath.range(i * 3 + 1, 0.10f, 0.42f)),
                radius = px(OrbMath.range(i * 5 + 9, 0.4f, 1.3f)),
                center = Offset(at.x + cos(a) * rr, at.y + sin(a) * rr * 0.34f),
                blendMode = BlendMode.Plus,
            )
        }
    }

    // Orbits and worlds. Returned so the caller can hit-test what was drawn —
    // one source of position for both, as with the galaxies on the orb.
    val placed = mutableListOf<Pair<WorldOrbit, Offset>>()
    system.worlds.forEach { w ->
        val rr = unit * w.orbit
        val squash = 0.30f + abs(cos(w.tilt)) * 0.16f
        drawOval(
            color = arm.copy(alpha = alpha * 0.18f),
            topLeft = Offset(at.x - rr, at.y - rr * squash),
            size = Size(rr * 2f, rr * squash * 2f),
            style = Stroke(px(0.8f)),
        )
        val a = w.phase + clock * w.speed * 0.5f
        val p = Offset(at.x + cos(a) * rr, at.y + sin(a) * rr * squash)
        placed += w to p

        // Big enough to be a WORLD. At the old 0.055 of a 0.34 unit these came
        // out around twenty pixels across on a phone — a shaded dot, with eight
        // kinds of surface detail drawn into something too small to show any of
        // it. That is most of what "the planets are sooo low quality" was: not
        // the drawing, the scale it was drawn at.
        val body = unit * 0.050f * w.planet.kind.bulk
        drawPlanet(w.planet, p, body, alpha, arm, arm, spark)
    }
    return placed
}

/**
 * LAYER FOUR — one world, and the things on it.
 *
 * *"zooming in on the planets i should find things"*. A planet that is only a
 * shaded sphere gets no better by being drawn larger — going closer has to REVEAL
 * something, or the journey ends in an anticlimax. So the surface carries named,
 * described landmarks that suit the kind of world they are on, and they fade in
 * as the view zooms rather than all being there from the first frame.
 */
private fun DrawScope.drawPlanetStage(
    world: WorldOrbit,
    marks: List<LandmarkSpec>,
    ink: DimensionPalette,
    clock: Float,
    view: Float,
    pan: Offset,
    alpha: Float,
): List<Pair<LandmarkSpec, Offset>> {
    val at = center + pan
    val r = size.minDimension * 0.34f * view
    val arm = ink.arm.toColor()
    val spark = ink.spark.toColor()

    drawPlanet(world.planet, at, r, alpha, arm, arm, spark)

    // Its moons, on their own orbits outside it.
    for (m in 0 until world.planet.moons) {
        val a = clock * (0.5f + m * 0.22f) + m * 2.1f
        val rr = r * (1.45f + m * 0.32f)
        val p = Offset(at.x + cos(a) * rr, at.y + sin(a) * rr * 0.42f)
        drawCircle(
            color = Color.White.copy(alpha = alpha * 0.75f),
            radius = r * 0.055f,
            center = p,
        )
        drawCircle(
            color = Color.Black.copy(alpha = alpha * 0.5f),
            radius = r * 0.055f,
            center = Offset(p.x + r * 0.02f, p.y + r * 0.015f),
        )
    }

    // TEXTURE, RESOLVING AS YOU CLOSE IN.
    //
    // *"when I zoom into a planet, i should find some things, the texture of the
    // planet or smth like that"*. A world that is merely drawn LARGER as you
    // approach gives nothing back for the approach — the same picture, bigger,
    // which is the definition of an anticlimax. Real detail has to appear that was
    // not there before, and it has to appear at a scale where it could not have
    // been visible earlier.
    //
    // So: grain over the whole surface, fading in from the moment the view starts
    // closing, at a size that is sub-pixel until it isn't. It costs one batched
    // call rather than a thousand circles, and it is what makes a planet feel like
    // ground rather than paint.
    val grain = ((view - 1.05f) / 0.9f).coerceIn(0f, 1f)
    if (grain > 0.01f) {
        val seed = world.planet.designation.hashCode()
        val motes = (260 * grain).toInt()
        val pts = ArrayList<Offset>(motes)
        for (i in 0 until motes) {
            // Square-rooted radius, or every mote piles into the middle: a
            // uniform random radius on a disc is not a uniform scatter over it.
            val a = OrbMath.range(seed + i * 71 + 3, 0f, OrbMath.TAU)
            val d = sqrt(OrbMath.unitRandom(seed + i * 73 + 5)) * 0.97f
            pts += Offset(at.x + cos(a) * r * d, at.y + sin(a) * r * d)
        }
        drawPoints(
            points = pts,
            pointMode = PointMode.Points,
            color = Color.White.copy(alpha = alpha * 0.16f * grain),
            strokeWidth = px(1.3f),
            cap = StrokeCap.Round,
        )
        // And the same again in shadow, offset, so the grain has relief instead of
        // being a dusting of white specks.
        drawPoints(
            points = pts.map { Offset(it.x + px(1.1f), it.y + px(1.1f)) },
            pointMode = PointMode.Points,
            color = Color.Black.copy(alpha = alpha * 0.20f * grain),
            strokeWidth = px(1.3f),
            cap = StrokeCap.Round,
        )
    }

    // The things. Held back until the view has actually closed in, so arriving
    // and then finding something are two separate moments rather than one.
    val reveal = ((view - LANDMARK_REVEAL) / 0.8f).coerceIn(0f, 1f)
    if (reveal <= 0.01f) return emptyList()

    val placed = mutableListOf<Pair<LandmarkSpec, Offset>>()
    marks.forEach { mark ->
        // Foreshortened toward the limb, so a landmark near the edge is squashed
        // the way a real surface feature is. Without it they read as stickers on
        // a flat circle.
        val depth = kotlin.math.sqrt((1f - mark.u * mark.u - mark.v * mark.v).coerceAtLeast(0f))
        val p = Offset(at.x + mark.u * r, at.y + mark.v * r)
        placed += mark to p
        val a = alpha * reveal * (0.35f + depth * 0.65f)
        val ms = r * mark.size
        val glow = lerpColour(spark, Color.White, 0.35f)

        when (mark.kind.shape) {
            LandmarkShape.Beacon -> {
                // In orbit rather than on the ground: a hard point with a slow
                // blink, which is what says "made" instead of "geology".
                val blink = 0.45f + 0.55f * abs(sin(clock * 2.2f + mark.angle))
                halo(p, ms * 1.6f, glow, a * 0.55f * blink)
                point(p, ms * 0.16f, a * blink)
            }
            LandmarkShape.Cluster -> {
                // Lights in a grid — a settlement reads by its regularity.
                for (i in 0 until 9) {
                    val gx = (i % 3 - 1) * ms * 0.36f
                    val gy = (i / 3 - 1) * ms * 0.30f * depth
                    drawCircle(
                        color = glow.copy(alpha = a * OrbMath.range(i * 13 + 3, 0.35f, 0.95f)),
                        radius = px(1.1f),
                        center = Offset(p.x + gx, p.y + gy),
                        blendMode = BlendMode.Plus,
                    )
                }
                halo(p, ms * 1.3f, glow, a * 0.22f)
            }
            LandmarkShape.Eye -> {
                // A ringed depression or a cyclone: concentric, with a bright pupil.
                for (k in 3 downTo 1) {
                    drawOval(
                        color = arm.copy(alpha = a * 0.30f / k),
                        topLeft = Offset(p.x - ms * k * 0.42f, p.y - ms * k * 0.42f * depth),
                        size = Size(ms * k * 0.84f, ms * k * 0.84f * depth),
                        style = Stroke(px(1.1f)),
                    )
                }
                drawCircle(glow.copy(alpha = a * 0.75f), ms * 0.16f, p, blendMode = BlendMode.Plus)
            }
            LandmarkShape.Scar -> {
                // A fracture: a long thin line following the curve of the body.
                val len = ms * 3.2f
                val dx = cos(mark.angle) * len
                val dy = sin(mark.angle) * len * depth
                drawLine(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, glow.copy(alpha = a * 0.85f), Color.Transparent),
                        start = Offset(p.x - dx, p.y - dy),
                        end = Offset(p.x + dx, p.y + dy),
                    ),
                    start = Offset(p.x - dx, p.y - dy),
                    end = Offset(p.x + dx, p.y + dy),
                    strokeWidth = px(1.6f),
                    cap = StrokeCap.Round,
                    blendMode = BlendMode.Plus,
                )
            }
            LandmarkShape.Veil -> {
                // An aurora: curtains over the pole, drawn as nested arcs.
                for (k in 0 until 5) {
                    val rr = ms * (1.1f + k * 0.22f)
                    drawArc(
                        color = glow.copy(alpha = a * 0.30f * (1f - k / 5f)),
                        startAngle = 200f + sin(clock * 0.7f + k) * 18f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(p.x - rr, p.y - rr * depth),
                        size = Size(rr * 2f, rr * 2f * depth),
                        style = Stroke(px(1.4f)),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
        }
    }
    return placed
}

/** How far in the view must be before the surface starts giving things up. */
private const val LANDMARK_REVEAL = 1.15f

/**
 * A WORLD, close enough to see what it is.
 *
 * **What it is made of and how it is marked are two different things**, and that
 * separation is the whole point of this function. There used to be one drawing
 * routine per [PlanetKind], so `kind` decided the entire picture and every gas
 * giant in the universe was the same gas giant in a different colour — which is
 * exactly what got reported: *"why do the planets look the same in each of the
 * stars, just the colours are different"*.
 *
 * Now the material picks the palette and the plausible patterns, and the pattern
 * draws the surface. On top of that go the things any world can have regardless:
 * polar caps, cloud, storms, a ring system, an atmosphere. Two worlds have to
 * agree on six independent rolls to look alike, and the whole surface is turned
 * by the planet's own axial tilt so even an exact match is posed differently.
 *
 * Every one of them is lit from the same side, with a terminator and a dark far
 * limb applied OVER the finished surface. That single cue is what makes a circle
 * read as a sphere — without it the most detailed surface in the world is a coin.
 */
private fun DrawScope.drawPlanet(
    planet: PlanetSpec,
    at: Offset,
    radius: Float,
    alpha: Float,
    base: Color,
    accent: Color,
    highlight: Color,
) {
    val r = radius.coerceAtLeast(2f)
    val seed = planet.designation.hashCode()
    val lit = Offset(at.x - r * 0.42f, at.y - r * 0.34f)
    val skin = lerpColour(base, highlight, planet.hue.coerceIn(-0.5f, 0.5f) + 0.5f)
    val dark = lerpColour(skin, Color.Black, 0.55f)
    val bright = lerpColour(skin, Color.White, 0.45f)

    // A ring system goes behind the body first, so the front half can overlap it.
    if (planet.rings > 0) {
        drawPlanetRings(planet, at, r, alpha, skin, back = true)
    }

    // The body: lit side to dark limb.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = alpha * planet.albedo),
                skin.copy(alpha = alpha * 0.92f),
                skin.copy(alpha = alpha * 0.45f),
                Color.Black.copy(alpha = alpha * 0.88f),
            ),
            center = lit,
            radius = r * 1.75f,
        ),
        radius = r,
        center = at,
    )

    // THE SURFACE, ACTUALLY CLIPPED TO THE DISC.
    //
    // The old comment claimed features were "clipped by construction" — placed by
    // a latitude whose half-width comes from the circle. That is true of where a
    // feature is CENTRED and false of where it ENDS: a band drawn with a round cap
    // bulges half a stroke past the limb, a crater centred at 0.80 with a radius
    // of 0.19 reaches 0.99 and its lit rim goes over, and the ice caps were sized
    // at 1.5x the chord and hung out of both sides like ears. Every one of those
    // reads as a sticker laid on a circle rather than as a surface.
    //
    // A real clip costs one path and removes the whole class, so features can be
    // drawn generously and trust the edge.
    val disc = Path().apply { addOval(Rect(at.x - r, at.y - r, at.x + r, at.y + r)) }
    clipPath(disc) {
        // TURNED BY ITS OWN AXIS. `tilt` has been generated for every world since
        // the beginning and was never once read — so every planet in the universe
        // was posed identically. Rotating the surface (and not the lighting, which
        // comes from the star and does not care how the world is leaning) is the
        // cheapest variety in this file.
        withTransform({ rotate(planet.tilt * 57.3f, at) }) {
            when (planet.pattern) {
                SurfacePattern.Banded -> bands(planet, at, r, alpha, skin, bright, dark, seed)
                SurfacePattern.Swirled -> swirls(planet, at, r, alpha, bright, dark, seed)
                SurfacePattern.Marbled -> marbling(planet, at, r, alpha, bright, dark, seed)
                SurfacePattern.Cratered -> craters(planet, at, r, alpha, seed)
                SurfacePattern.Cracked -> cracks(planet, at, r, alpha, highlight, seed)
                SurfacePattern.Mottled -> mottling(planet, at, r, alpha, bright, dark, seed)
                SurfacePattern.Veined -> veins(planet, at, r, alpha, bright, seed)
                SurfacePattern.Dappled -> dapples(planet, at, r, alpha, bright, dark, seed)
                SurfacePattern.Ridged -> ridges(planet, at, r, alpha, bright, dark, seed)
                SurfacePattern.Featureless -> Unit
            }

            // ── The things any world can have, whatever it is made of ────────
            //
            // These are what turn ten patterns into a space nobody can exhaust.
            // Each is an independent roll in the generator, so a mottled world
            // with caps and heavy cloud and a mottled world with neither share a
            // pattern and nothing else.

            if (planet.cap > 0f) {
                for (pole in listOf(-1f, 1f)) {
                    val y = pole * (1f - planet.cap)
                    val w = r * sqrt((1f - y * y).coerceAtLeast(0f)) * 1.25f
                    drawOval(
                        color = Color.White.copy(alpha = alpha * 0.62f * shade(y)),
                        topLeft = Offset(at.x - w, at.y + y * r - r * planet.cap * 0.7f),
                        size = Size(w * 2f, r * planet.cap * 1.5f),
                    )
                }
            }

            if (planet.cloud > 0.08f) {
                // Cloud is drawn as broken belts rather than as a haze over the
                // whole disc: a uniform white wash just lowers the contrast of
                // whatever is underneath and reads as fog on the lens.
                val decks = 2 + (planet.cloud * 5f).toInt()
                for (i in 0 until decks) {
                    val y = OrbMath.range(seed + i * 149 + 7, -0.86f, 0.86f)
                    val half = r * sqrt((1f - y * y).coerceAtLeast(0f))
                    val from = OrbMath.range(seed + i * 151 + 11, -1f, 0.2f) * half
                    val to = from + half * OrbMath.range(seed + i * 157 + 13, 0.7f, 1.9f)
                    drawLine(
                        color = Color.White.copy(alpha = alpha * 0.30f * planet.cloud * shade(y)),
                        start = Offset(at.x + from, at.y + y * r),
                        end = Offset(at.x + to.coerceAtMost(half), at.y + y * r),
                        strokeWidth = (r * 0.10f).coerceAtLeast(px(1f)),
                        cap = StrokeCap.Round,
                    )
                }
            }

            // Ray systems: bright streaks thrown out from a fresh impact, on the
            // worlds with no weather to erase them. They cross everything else on
            // the surface, which is what makes them read as recent.
            for (i in 0 until planet.rays) {
                val a = planet.spin + OrbMath.range(seed + i * 181 + 3, 0f, OrbMath.TAU)
                val from = Offset(
                    at.x + cos(a) * r * OrbMath.range(seed + i * 191, 0.15f, 0.7f),
                    at.y + sin(a) * r * OrbMath.range(seed + i * 193, 0.15f, 0.7f),
                )
                val fan = 5 + (OrbMath.unitRandom(seed + i * 197) * 5).toInt()
                for (k in 0 until fan) {
                    val ra = OrbMath.range(seed + i * 199 + k * 7, 0f, OrbMath.TAU)
                    val len = r * OrbMath.range(seed + i * 211 + k * 11, 0.25f, 0.85f)
                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = alpha * 0.30f),
                                Color.Transparent,
                            ),
                            start = from,
                            end = Offset(from.x + cos(ra) * len, from.y + sin(ra) * len),
                        ),
                        start = from,
                        end = Offset(from.x + cos(ra) * len, from.y + sin(ra) * len),
                        strokeWidth = px(OrbMath.range(seed + i * 223 + k, 0.7f, 2.0f)),
                        cap = StrokeCap.Round,
                    )
                }
            }

            for (i in 0 until planet.storms) {
                val sx = at.x + r * OrbMath.range(seed + i * 163 + 3, -0.55f, 0.55f)
                val sy = at.y + r * OrbMath.range(seed + i * 167 + 5, -0.45f, 0.45f)
                val sw = r * OrbMath.range(seed + i * 173 + 7, 0.14f, 0.30f)
                val sh = sw * OrbMath.range(seed + i * 179 + 9, 0.42f, 0.80f)
                // A storm is an eye inside a wall, not a coloured blob: the ring
                // is what makes it read as weather rather than as a paint mark.
                drawOval(
                    color = bright.copy(alpha = alpha * 0.42f),
                    topLeft = Offset(sx - sw, sy - sh),
                    size = Size(sw * 2f, sh * 2f),
                )
                drawOval(
                    color = dark.copy(alpha = alpha * 0.40f),
                    topLeft = Offset(sx - sw * 0.42f, sy - sh * 0.42f),
                    size = Size(sw * 0.84f, sh * 0.84f),
                )
            }
        }

        // LIGHT, APPLIED OVER THE SURFACE RATHER THAN UNDER IT.
        //
        // The body gradient runs first and every feature is painted on top of it,
        // so without this the night side is as bright as the day side and nothing
        // looks spherical. One gradient over the finished surface, outside the
        // tilt transform because the sun does not lean with the planet.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color.Transparent,
                    Color.Transparent,
                    Color.Black.copy(alpha = alpha * 0.42f),
                    Color.Black.copy(alpha = alpha * 0.92f),
                ),
                center = lit,
                radius = r * 2.05f,
            ),
            radius = r,
            center = at,
        )
        // A specular bloom where the light actually lands, so there is a highlight
        // to read the curvature against.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = alpha * 0.30f * planet.albedo), Color.Transparent),
                center = lit,
                radius = r * 0.85f,
            ),
            radius = r,
            center = at,
            blendMode = BlendMode.Plus,
        )
    }

    // Outside the body: the parts that are deliberately NOT on the surface.
    if (planet.kind == PlanetKind.Lava) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(highlight.copy(alpha = alpha * 0.30f), Color.Transparent),
                center = at,
                radius = r * 1.7f,
            ),
            radius = r * 1.7f,
            center = at,
            blendMode = BlendMode.Plus,
        )
    }
    if (planet.kind == PlanetKind.Shattered) {
        // The less of the world that survived, the more of it is out here and the
        // further it has spread. One number drives both, so a barely-cracked
        // world has a tight halo of chips and a mostly-destroyed one is a cloud.
        val lost = 1f - planet.intact
        val count = planet.features + (lost * 26f).toInt()
        for (i in 0 until count) {
            val a = OrbMath.range(seed + i * 11, 0f, OrbMath.TAU)
            val d = OrbMath.range(seed + i * 13, 1.02f, 1.35f + lost * 1.4f)
            drawCircle(
                color = skin.copy(alpha = alpha * 0.65f),
                radius = px(OrbMath.range(seed + i * 17, 0.7f, 1.4f + lost * 2.2f)),
                center = Offset(at.x + cos(a) * r * d, at.y + sin(a) * r * d * 0.55f),
                blendMode = BlendMode.Plus,
            )
        }
    }

    if (planet.rings > 0) {
        drawPlanetRings(planet, at, r, alpha, skin, back = false)
    }

    // The atmosphere, and the bright limb where it catches the light. Scaled by
    // how much air the world actually has, so an airless rock has a hard edge and
    // a thick-atmosphere world glows — a difference you read before any surface
    // detail resolves, and one more axis two worlds have to match on.
    if (planet.haze > 0.05f) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.Transparent, accent.copy(alpha = alpha * 0.26f * planet.haze), Color.Transparent),
                center = at,
                radius = r * (1.10f + planet.haze * 0.28f),
            ),
            radius = r * (1.10f + planet.haze * 0.28f),
            center = at,
            blendMode = BlendMode.Plus,
        )
    }
}

// ── The ten surfaces ────────────────────────────────────────────────────────
//
// Each takes the same arguments and draws inside a disc of radius [r] at [at],
// already clipped and already turned to the planet's axis. `spin` moves the
// pattern round the body so two worlds sharing one pattern are still not the
// same picture.

/** Latitude belts. The half-width at each latitude comes from the circle. */
private fun DrawScope.bands(
    p: PlanetSpec, at: Offset, r: Float, alpha: Float,
    skin: Color, bright: Color, dark: Color, seed: Int,
) {
    for (i in 0 until p.features) {
        val y = (i.toFloat() / p.features - 0.5f) * 1.94f
        val w = r * sqrt((1f - y * y).coerceAtLeast(0f))
        if (w < 1f) continue
        // Belt widths vary rather than alternating evenly — a regular stripe
        // pattern reads as fabric, an irregular one reads as weather.
        val band = lerpColour(skin, if (i % 2 == 0) bright else dark, OrbMath.range(seed + i * 37, 0.22f, 0.55f))
        drawLine(
            color = band.copy(alpha = alpha * 0.40f * shade(y)),
            start = Offset(at.x - w, at.y + y * r),
            end = Offset(at.x + w, at.y + y * r),
            strokeWidth = (r * OrbMath.range(seed + i * 41, 0.11f, 0.26f)).coerceAtLeast(px(1f)),
            cap = StrokeCap.Butt,
        )
    }
}

/** Belts pulled into a vortex — the same material, stirred. */
private fun DrawScope.swirls(
    p: PlanetSpec, at: Offset, r: Float, alpha: Float,
    bright: Color, dark: Color, seed: Int,
) {
    for (i in 0 until p.features) {
        val path = Path()
        val turn = OrbMath.range(seed + i * 53, 1.1f, 2.6f)
        val from = r * OrbMath.range(seed + i * 59, 0.10f, 0.40f)
        val to = r * OrbMath.range(seed + i * 61, 0.70f, 1.02f)
        val steps = 22
        for (k in 0..steps) {
            val f = k.toFloat() / steps
            val rad = from + (to - from) * f
            val a = p.spin + i * 1.7f + f * turn
            val x = at.x + cos(a) * rad
            val y = at.y + sin(a) * rad
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = (if (i % 2 == 0) bright else dark).copy(alpha = alpha * 0.34f),
            style = Stroke((r * 0.10f).coerceAtLeast(px(1f)), cap = StrokeCap.Round),
        )
    }
}

/** Large soft masses running into each other: continents, or cloud decks. */
private fun DrawScope.marbling(
    p: PlanetSpec, at: Offset, r: Float, alpha: Float,
    bright: Color, dark: Color, seed: Int,
) {
    for (i in 0 until p.features) {
        val a = p.spin + OrbMath.range(seed + i * 23, 0f, OrbMath.TAU)
        val d = OrbMath.range(seed + i * 29, 0.05f, 0.72f)
        val c = Offset(at.x + cos(a) * r * d, at.y + sin(a) * r * d)
        val tone = if (OrbMath.unitRandom(seed + i * 67) > 0.45f) bright else dark
        // Several overlapping blobs, not one ellipse: a single shape reads as a
        // logo, a cluster reads as a coastline.
        for (k in 0 until 6) {
            val q = Offset(
                c.x + OrbMath.range(seed + i * 31 + k, -0.26f, 0.26f) * r,
                c.y + OrbMath.range(seed + i * 37 + k, -0.20f, 0.20f) * r,
            )
            drawCircle(
                color = tone.copy(alpha = alpha * 0.30f * shade((q.y - at.y) / r)),
                radius = r * OrbMath.range(seed + i * 41 + k, 0.10f, 0.24f),
                center = q,
            )
        }
    }
}

/** Impacts, each with a lit rim on the sunward side and a shadow opposite. */
private fun DrawScope.craters(p: PlanetSpec, at: Offset, r: Float, alpha: Float, seed: Int) {
    for (i in 0 until p.features) {
        val a = p.spin + OrbMath.range(seed + i * 13, 0f, OrbMath.TAU)
        val d = OrbMath.range(seed + i * 17, 0f, 0.94f)
        val c = Offset(at.x + cos(a) * r * d, at.y + sin(a) * r * d)
        val cr = r * OrbMath.range(seed + i * 7, 0.05f, 0.21f)
        val s = shade((c.y - at.y) / r)
        // A flat grey ring reads as a hole. The pair — floor in shadow, rim
        // catching the light — is what reads as a crater.
        drawCircle(Color.Black.copy(alpha = alpha * 0.36f * s), cr, c)
        drawCircle(
            color = Color.White.copy(alpha = alpha * 0.26f * s),
            radius = cr,
            center = Offset(c.x - cr * 0.20f, c.y - cr * 0.20f),
            style = Stroke(px(1.0f)),
        )
    }
}

/** Fractures, lit from beneath where the crust is thin. */
private fun DrawScope.cracks(
    p: PlanetSpec, at: Offset, r: Float, alpha: Float, glow: Color, seed: Int,
) {
    drawCircle(Color.Black.copy(alpha = alpha * 0.42f), r, at)
    for (i in 0 until p.features) {
        val path = Path()
        var a = p.spin + OrbMath.range(seed + i * 19, 0f, OrbMath.TAU)
        var pt = Offset(
            at.x + cos(a) * r * OrbMath.range(seed + i * 23, 0f, 0.35f),
            at.y + sin(a) * r * OrbMath.range(seed + i * 29, 0f, 0.35f),
        )
        path.moveTo(pt.x, pt.y)
        // A crack WANDERS. A straight line from the middle to the edge is a
        // spoke, and eight spokes is a wheel, not a broken crust.
        for (k in 0 until 5) {
            a += OrbMath.range(seed + i * 31 + k * 7, -0.75f, 0.75f)
            val step = r * OrbMath.range(seed + i * 37 + k * 11, 0.14f, 0.30f)
            pt = Offset(pt.x + cos(a) * step, pt.y + sin(a) * step)
            path.lineTo(pt.x, pt.y)
        }
        drawPath(
            path = path,
            color = glow.copy(alpha = alpha * 0.80f),
            style = Stroke(px(OrbMath.range(seed + i * 43, 0.9f, 2.2f)), cap = StrokeCap.Round),
            blendMode = BlendMode.Plus,
        )
    }
}

/** Many small patches — something weathered unevenly. */
private fun DrawScope.mottling(
    p: PlanetSpec, at: Offset, r: Float, alpha: Float,
    bright: Color, dark: Color, seed: Int,
) {
    val n = p.features * 4
    for (i in 0 until n) {
        val a = p.spin + OrbMath.range(seed + i * 11, 0f, OrbMath.TAU)
        val d = sqrt(OrbMath.unitRandom(seed + i * 13)) * 0.98f
        val c = Offset(at.x + cos(a) * r * d, at.y + sin(a) * r * d)
        drawCircle(
            color = (if (OrbMath.unitRandom(seed + i * 17) > 0.5f) bright else dark)
                .copy(alpha = alpha * OrbMath.range(seed + i * 19, 0.08f, 0.26f)),
            radius = r * OrbMath.range(seed + i * 23, 0.04f, 0.13f),
            center = c,
        )
    }
}

/** Long branching lines: rivers, rilles, or lava tubes. */
private fun DrawScope.veins(
    p: PlanetSpec, at: Offset, r: Float, alpha: Float, tone: Color, seed: Int,
) {
    for (i in 0 until p.features) {
        var a = p.spin + OrbMath.range(seed + i * 29, 0f, OrbMath.TAU)
        var pt = Offset(at.x, at.y)
        val path = Path()
        path.moveTo(pt.x, pt.y)
        for (k in 0 until 7) {
            a += OrbMath.range(seed + i * 31 + k * 13, -0.5f, 0.5f)
            val step = r * 0.17f
            pt = Offset(pt.x + cos(a) * step, pt.y + sin(a) * step)
            path.lineTo(pt.x, pt.y)
        }
        drawPath(
            path = path,
            color = tone.copy(alpha = alpha * 0.30f),
            style = Stroke(px(OrbMath.range(seed + i * 37, 0.8f, 1.8f)), cap = StrokeCap.Round),
        )
    }
}

/** Discrete spots at every scale. */
private fun DrawScope.dapples(
    p: PlanetSpec, at: Offset, r: Float, alpha: Float,
    bright: Color, dark: Color, seed: Int,
) {
    val n = p.features * 3
    for (i in 0 until n) {
        val a = p.spin + OrbMath.range(seed + i * 41, 0f, OrbMath.TAU)
        val d = sqrt(OrbMath.unitRandom(seed + i * 43)) * 0.92f
        // Size falls off sharply, so a few large spots sit among many small ones
        // rather than everything being the same middling size.
        val g = OrbMath.unitRandom(seed + i * 47)
        val rad = r * (0.03f + g * g * g * 0.22f)
        drawCircle(
            color = (if (OrbMath.unitRandom(seed + i * 53) > 0.6f) bright else dark)
                .copy(alpha = alpha * 0.34f),
            radius = rad,
            center = Offset(at.x + cos(a) * r * d, at.y + sin(a) * r * d),
        )
    }
}

/** Parallel ridges catching the light along one edge. */
private fun DrawScope.ridges(
    p: PlanetSpec, at: Offset, r: Float, alpha: Float,
    bright: Color, dark: Color, seed: Int,
) {
    val n = p.features + 4
    for (i in 0 until n) {
        val off = (i.toFloat() / n - 0.5f) * 1.96f * r
        val half = sqrt((r * r - off * off).coerceAtLeast(0f))
        if (half < 1f) continue
        val wob = OrbMath.range(seed + i * 59, -0.06f, 0.06f) * r
        // Shadow and highlight one pixel apart along the ridge line: that offset
        // pair is the whole trick, and it is what makes them look raised.
        drawLine(
            color = dark.copy(alpha = alpha * 0.34f),
            start = Offset(at.x - half, at.y + off + wob),
            end = Offset(at.x + half, at.y + off - wob),
            strokeWidth = px(1.6f),
        )
        drawLine(
            color = bright.copy(alpha = alpha * 0.30f),
            start = Offset(at.x - half, at.y + off + wob - px(1.4f)),
            end = Offset(at.x + half, at.y + off - wob - px(1.4f)),
            strokeWidth = px(1.1f),
        )
    }
}

/**
 * How lit a point at latitude [y] is, given a light coming from up and left.
 * Everything on a planet's surface uses it, so the whole world agrees about
 * where its sun is — features shaded independently look like stickers.
 */
private fun shade(y: Float): Float = (0.35f + (1f - (y + 1f) / 2f) * 0.9f).coerceIn(0.15f, 1f)

/** A ring system, drawn in halves so the planet can sit inside it. */
private fun DrawScope.drawPlanetRings(
    planet: PlanetSpec,
    at: Offset,
    r: Float,
    alpha: Float,
    skin: Color,
    back: Boolean,
) {
    // Ring systems used to differ only in how many bands they had, which at this
    // size is barely a difference at all. Now the OPENING varies — one world
    // shows its rings almost edge-on as a bright line through the planet, another
    // face-on as a full disc — and a division sits somewhere up the system, at a
    // place that moves. Those two together read as different worlds instantly,
    // long before anyone counts bands.
    val squash = planet.ringTilt
    val gapAt = (planet.rings * planet.ringGap).toInt()
    withTransform({
        rotate(planet.tilt * 40f, at)
        scale(1f, squash, at)
    }) {
        for (i in 0 until planet.rings) {
            if (i == gapAt && planet.rings > 2) continue
            val rr = r * (1.35f + i * 0.26f)
            // Outer bands are thinner and fainter, so the system fades outward
            // rather than stopping at a hard edge.
            val fade = 1f - i.toFloat() / (planet.rings + 1f) * 0.55f
            // The near half is drawn over the body, the far half under it.
            drawArc(
                color = skin.copy(alpha = alpha * fade * (if (back) 0.34f else 0.52f)),
                startAngle = if (back) 180f else 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(at.x - rr, at.y - rr),
                size = Size(rr * 2f, rr * 2f),
                style = Stroke((r * 0.13f * fade).coerceAtLeast(px(1f))),
            )
        }
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

/** One `drawPoints` call: every field star sharing a colour, size and brightness. */
private class DeepBucket(val points: List<Offset>, val colour: Color, val diameter: Float)

/** A field star bright enough to be worth a diffraction cross and a twinkle. */
private class DeepStar(val at: Offset, val reach: Float, val alpha: Float, val rate: Float)

private class DeepLayout(val buckets: List<DeepBucket>, val bright: List<DeepStar>)

/**
 * The star field behind the universe, batched and held across frames.
 *
 * It drew 420 individual circles a frame and gave a diffraction cross to every
 * star above 0.86 brightness — around sixty of them, each allocating two gradient
 * shaders. Roughly 120 shader allocations **per frame** for a backdrop, on the one
 * screen that is also running a 3D orb. The identical fault the theme backdrop
 * had, in the other file, found the same way.
 *
 * Same fix: the faint majority sort into a dozen buckets and go out as
 * `drawPoints`, only a handful are worth a shader, and the geometry is built once
 * rather than recomputed every frame to produce the same answer.
 *
 * **Deliberately not Compose state** — written from the draw phase, like
 * `WorldPlacements` and for the same reason.
 */
private class DeepFieldCache {
    private var key: String = ""
    private var layout = DeepLayout(emptyList(), emptyList())

    fun of(
        w: Float,
        h: Float,
        count: Int,
        accent: Color,
        highlight: Color,
        density: Float,
    ): DeepLayout {
        val k = "$w:$h:$count:${accent.hashCode()}:${highlight.hashCode()}:$density"
        if (k == key) return layout

        val sizes = listOf(0.45f, 0.85f, 1.35f)
        val alphas = listOf(0.10f, 0.22f, 0.38f, 0.58f)
        val buckets = LinkedHashMap<Int, MutableList<Offset>>()
        val bright = mutableListOf<DeepStar>()

        for (i in 0 until count) {
            val at = Offset(
                OrbMath.unitRandom(i * 2 + 1) * w,
                OrbMath.unitRandom(i * 2 + 2) * h,
            )
            // Squared, so most stars are faint and a few genuinely bright — the
            // distribution a real field has, and why it reads as one.
            val d = OrbMath.unitRandom(i * 7 + 5)
            val near = d * d
            // 0.93, not the old 0.86: sixty crosses in one sky is a lens fault,
            // not a star field, and each of them costs two shaders a frame.
            if (near > 0.93f) {
                bright += DeepStar(
                    at = at,
                    reach = (3.2f + near * 3.5f) * density,
                    alpha = (0.05f + near * 0.55f) * 0.5f,
                    rate = OrbMath.range(i, 0.6f, 2.4f),
                )
                continue
            }
            val ci = when {
                i % 23 == 0 -> 1
                i % 17 == 0 -> 2
                else -> 0
            }
            val ai = (near * alphas.size).toInt().coerceAtMost(alphas.size - 1)
            val si = (near * sizes.size).toInt().coerceAtMost(sizes.size - 1)
            buckets.getOrPut(ci * 100 + si * 10 + ai) { mutableListOf() } += at
        }

        layout = DeepLayout(
            buckets = buckets.map { (id, pts) ->
                val ci = id / 100
                val si = (id / 10) % 10
                val ai = id % 10
                DeepBucket(
                    points = pts,
                    colour = (
                        when (ci) {
                            1 -> accent
                            2 -> highlight
                            else -> Color.White
                        }
                        ).copy(alpha = alphas[ai]),
                    diameter = sizes[si] * 2f * density,
                )
            },
            bright = bright,
        )
        key = k
        return layout
    }
}

/**
 * The star field, at whatever density this place's sky calls for.
 *
 * Density is per place now. A sky with 150 stars and one with 420 are different
 * places before anything is drawn in front of them, and it costs nothing to say
 * so.
 */
private fun DrawScope.drawDeepField(
    sky: SkySpec,
    clock: Float,
    accent: Color,
    highlight: Color,
    cache: DeepFieldCache,
) {
    val count = (420 * sky.density).toInt()
    val field = cache.of(size.width, size.height, count, accent, highlight, density)
    field.buckets.forEach { b ->
        drawPoints(
            points = b.points,
            pointMode = PointMode.Points,
            color = b.colour,
            strokeWidth = b.diameter,
            cap = StrokeCap.Round,
        )
    }
    field.bright.forEach { s ->
        val twinkle = 0.62f + 0.38f * abs(sin(clock * s.rate))
        spikes(s.at, s.reach, Color.White, s.alpha * twinkle, 0f)
        point(s.at, s.reach * 0.13f, s.alpha * twinkle * 1.6f)
    }
}

/**
 * The sky of wherever you are, built to [SkySpec] rather than to one recipe.
 *
 * There was one sky: seven clouds, one diagonal band at one fixed angle, one
 * density, recoloured per place and identical in every other respect — so every
 * galaxy you entered had the same weather as the last. *"better backdrops for all
 * these"*. Now the band runs at the place's own angle and may be absent
 * altogether, the nebulosity is anywhere from none to nine, and some skies are
 * genuinely empty, which is what lets a busy one read as busy.
 */
private fun DrawScope.drawSky(
    spec: SkySpec,
    clock: Float,
    accent: Color,
    secondary: Color,
    highlight: Color,
) {
    // A little light on the ground, so the darkest skies are not all identically
    // black and a washed one reads as somewhere with something behind it.
    if (spec.depth > 0.02f) {
        drawRect(
            brush = Brush.radialGradient(
                listOf(
                    secondary.copy(alpha = 0.05f * spec.depth),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.5f, size.height * 0.42f),
                radius = size.maxDimension * 0.75f,
            ),
            blendMode = BlendMode.Plus,
        )
    }

    // Nebulosity, drifting slowly and overlapping additively.
    for (i in 0 until spec.clouds) {
        val drift = clock * OrbMath.range(i * 13 + 3, 0.04f, 0.12f) + i * 1.9f
        val cx = size.width * (0.5f + cos(drift) * spec.cloudSpread *
            OrbMath.range(i * 5 + 1, 0.35f, 1.15f))
        val cy = size.height * (0.5f + sin(drift * 1.2f) * spec.cloudSpread *
            OrbMath.range(i * 9 + 4, 0.30f, 1.05f))
        val r = size.minDimension * OrbMath.range(i * 11 + 7, 0.35f, 0.95f)
        val colour = when (i % 3) {
            0 -> accent
            1 -> secondary
            else -> highlight
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    colour.copy(alpha = OrbMath.range(i * 3 + 2, 0.04f, 0.10f)),
                    colour.copy(alpha = 0.02f),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = r,
            ),
            radius = r,
            center = Offset(cx, cy),
            blendMode = BlendMode.Plus,
        )
    }

    // The band, at THIS place's angle. Drawn as a run of overlapping soft discs
    // so it has ragged edges instead of the hard boundary a rotated rectangle
    // gives — and skipped entirely where the sky is a quiet one.
    if (spec.bandWeight > 0.05f) {
        val steps = 22
        val dx = cos(spec.bandAngle)
        val dy = sin(spec.bandAngle)
        val half = size.maxDimension * 0.72f
        for (k in 0 until steps) {
            val t = k.toFloat() / (steps - 1) - 0.5f
            val x = center.x + dx * half * t * 2f
            val y = center.y + dy * half * t * 2f
            val r = size.minDimension * spec.bandWidth * (0.75f + 0.35f * sin(t * 12.4f))
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        accent.copy(alpha = 0.055f * spec.bandWeight),
                        highlight.copy(alpha = 0.022f * spec.bandWeight),
                        Color.Transparent,
                    ),
                    center = Offset(x, y),
                    radius = r,
                ),
                radius = r,
                center = Offset(x, y),
                blendMode = BlendMode.Plus,
            )
        }
    }

    // Other galaxies, far enough away to be smudges. The cheapest possible cue
    // that this sky is somewhere in particular rather than a texture: a shape you
    // recognise, at a size that says how far off it is.
    for (i in 0 until spec.distant) {
        val gx = size.width * OrbMath.range(i * 71 + 5, 0.06f, 0.94f)
        val gy = size.height * OrbMath.range(i * 73 + 7, 0.06f, 0.94f)
        val gr = size.minDimension * OrbMath.range(i * 79 + 11, 0.012f, 0.038f)
        val lean = OrbMath.range(i * 83 + 13, 0f, OrbMath.PI_F)
        val flat = OrbMath.range(i * 89 + 17, 0.16f, 0.85f)
        withTransform({ rotate(lean * 57.3f, Offset(gx, gy)) }) {
            drawOval(
                brush = Brush.radialGradient(
                    listOf(
                        highlight.copy(alpha = 0.22f),
                        accent.copy(alpha = 0.09f),
                        Color.Transparent,
                    ),
                    center = Offset(gx, gy),
                    radius = gr * 1.6f,
                ),
                topLeft = Offset(gx - gr * 1.6f, gy - gr * 1.6f * flat),
                size = Size(gr * 3.2f, gr * 3.2f * flat),
                blendMode = BlendMode.Plus,
            )
        }
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
