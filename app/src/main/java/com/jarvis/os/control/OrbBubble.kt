package com.jarvis.os.control

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.jarvis.os.data.UserPreferences
import com.jarvis.os.debug.DebugLog
import com.jarvis.os.voice.OrbState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating JARVIS orb: a small draggable bubble that sits over whatever app
 * the user is in, shows what JARVIS is doing, and opens him when tapped.
 *
 * ## No permission, on purpose
 *
 * The obvious way to build this is `SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`,
 * which is what most floating widgets use. It is also a permission the user must
 * be sent to a settings screen to grant, and one Play scrutinises hard.
 *
 * JARVIS does not need it. It already runs an `AccessibilityService`, and an
 * accessibility overlay can be **touchable** — the tap outline only looks
 * untouchable because it deliberately sets `FLAG_NOT_TOUCHABLE` so JARVIS's own
 * gestures pass through it. Leave that flag off and the same
 * window type accepts drags and taps. So: no new permission, no settings trip,
 * nothing extra to disclose.
 *
 * The cost is that the bubble exists only while the accessibility service is
 * running. That is the same condition screen control already requires, so it buys
 * no new failure mode.
 *
 * ## Not Compose
 *
 * A Compose overlay needs a lifecycle owner, a saved-state registry and a
 * recomposer bolted onto a raw window. A `View` with a `Canvas` needs none of
 * that, matches how [ScreenControlService] already draws its tap outline,
 * and keeps this whole file out of the Compose dependency — which is what lets
 * the off-device gate compile it. See [BubbleColors].
 */
class OrbBubble(private val service: AccessibilityService) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = UserPreferences(service)

    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

    /** The ✕ that appears at the bottom of the screen while the orb is dragged. */
    private var dismissView: DismissTarget? = null

    /**
     * Set by the engine so a dismissal can turn the preference off, rather than
     * hiding an orb that reappears on the next session and looks like a bug.
     */
    var onDismissed: (() -> Unit)? = null

    /** Called on a tap. Set by the engine; null while no engine is alive. */
    var onTap: (() -> Unit)? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val size = (BUBBLE_DP * service.resources.displayMetrics.density).toInt()
        val bubble = BubbleView(service, BubbleColors.forTheme(prefs.themeId))
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Deliberately NOT `FLAG_NOT_TOUCHABLE` — unlike the tap outline,
            // this window is the one thing on screen that IS meant to take a touch. Still not
            // focusable, so it never steals the keyboard from the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = prefs.bubbleX.takeIf { it >= 0 } ?: (screenWidth() - size)
            y = prefs.bubbleY.takeIf { it >= 0 } ?: (screenHeight() / 2)
        }
        bubble.onDragStarted = { showDismissTarget() }
        bubble.onDragged = { dx, dy -> moveBy(dx, dy) }
        bubble.onReleased = { if (overDismissTarget()) dismiss() else { hideDismissTarget(); snapToEdge() } }
        bubble.onTapped = { onTap?.invoke() }
        try {
            windowManager.addView(bubble, lp)
            view = bubble
            params = lp
            DebugLog.log(DebugLog.Stage.SCREEN, "floating orb shown")
        } catch (e: Exception) {
            view = null
            params = null
            DebugLog.log(
                DebugLog.Stage.ERROR,
                "floating orb failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    fun hide() {
        hideDismissTarget()
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        view = null
        params = null
    }

    /** Repaint for a new assistant state. Cheap and safe to call every turn. */
    fun setState(state: OrbState, amplitude: Float) {
        view?.setAssistantState(state, amplitude)
    }

    /** Pick up a theme change without tearing the window down. */
    fun refreshTheme() {
        view?.setScheme(BubbleColors.forTheme(prefs.themeId))
    }

    /**
     * The ✕ the orb is dragged onto to get rid of it.
     *
     * The user's report was "the orb has no button to remove it from the screen
     * when I click it". A close button ON the orb was the obvious answer and is
     * the wrong one: the orb is 76dp, a second target inside it would be a
     * fingernail wide, and tap already means talk.
     *
     * Drag-to-dismiss is what every floating bubble on Android does, and it is
     * discoverable in the only way that matters — the target appears the moment
     * you start dragging, so you find it by doing the thing you were already
     * doing. It also cannot fire by accident, which a long-press can.
     */
    private fun showDismissTarget() {
        if (dismissView != null) return
        val target = DismissTarget(service)
        val size = (DISMISS_DP * service.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not touchable: the finger is on the ORB, and this only has to be
            // seen and aimed at. Taking touches would steal the drag mid-gesture.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = (screenWidth() - size) / 2
            y = screenHeight() - size - (DISMISS_MARGIN_DP * service.resources.displayMetrics.density).toInt()
        }
        try {
            windowManager.addView(target, lp)
            dismissView = target
        } catch (e: Exception) {
            dismissView = null
        }
    }

    private fun hideDismissTarget() {
        dismissView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        dismissView = null
        dismissArmed = false
    }

    /** True while the orb's centre is close enough to the ✕ to drop onto it. */
    private fun overDismissTarget(): Boolean {
        val lp = params ?: return false
        val v = view ?: return false
        val d = dismissView ?: return false
        val orbX = lp.x + v.width / 2f
        val orbY = lp.y + v.height / 2f
        val targetX = (screenWidth() / 2f)
        val targetY = screenHeight() - d.height / 2f -
            DISMISS_MARGIN_DP * service.resources.displayMetrics.density
        val reach = d.height * DISMISS_REACH
        return hypot(orbX - targetX, orbY - targetY) <= reach
    }

    private var dismissArmed = false

    /** Highlights the ✕ as the orb comes within range, so the drop is predictable. */
    private fun updateDismissHighlight() {
        val over = overDismissTarget()
        if (over != dismissArmed) {
            dismissArmed = over
            dismissView?.setArmed(over)
        }
    }

    private fun dismiss() {
        DebugLog.log(DebugLog.Stage.SCREEN, "floating orb dismissed by drag")
        hideDismissTarget()
        hide()
        // Turns the setting off rather than hiding for now. An orb that comes
        // back on the next session after the user deliberately threw it away
        // reads as the dismissal not working.
        onDismissed?.invoke()
    }

    private fun moveBy(dx: Int, dy: Int) {
        val lp = params ?: return
        val v = view ?: return
        lp.x = (lp.x + dx).coerceIn(0, (screenWidth() - v.width).coerceAtLeast(0))
        lp.y = (lp.y + dy).coerceIn(0, (screenHeight() - v.height).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(v, lp)
        } catch (e: Exception) {
            // the window went away mid-drag
        }
        updateDismissHighlight()
    }

    /**
     * Slide to whichever side edge is nearer once the finger lifts.
     *
     * A bubble left in the middle of the screen covers whatever the user is
     * reading, and the one thing every floating widget gets right is that it
     * clings to an edge.
     */
    private fun snapToEdge() {
        val lp = params ?: return
        val v = view ?: return
        val right = (screenWidth() - v.width).coerceAtLeast(0)
        val target = if (lp.x + v.width / 2 < screenWidth() / 2) 0 else right
        val from = lp.x
        if (from == target) {
            savePosition()
            return
        }
        ValueAnimator.ofInt(from, target).apply {
            duration = SNAP_MS
            addUpdateListener { a ->
                lp.x = a.animatedValue as Int
                try {
                    windowManager.updateViewLayout(v, lp)
                } catch (e: Exception) {
                    cancel()
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = savePosition()
            })
            start()
        }
    }

    /** Remember where it was put, so it is in the same place next time. */
    private fun savePosition() {
        val lp = params ?: return
        prefs.bubbleX = lp.x
        prefs.bubbleY = lp.y
    }

    private fun screenWidth(): Int = service.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = service.resources.displayMetrics.heightPixels

    /**
     * The orb itself.
     *
     * Kept close to the reference the user pointed at: a domed circle with a soft
     * glow, not a scale model of the in-app 3D orb. It is 56dp across on someone
     * else's screen — filigree and geodesic struts would be mud at that size, and
     * the thing that has to read instantly is *what JARVIS is doing*, which is
     * carried by colour and one ring.
     */
    private class BubbleView(
        context: Context,
        private var scheme: BubbleColors.Scheme,
    ) : View(context) {

        // No stroke paint. The first version drew the state as rings and arcs
        // OUTSIDE the circle, which is what made it read as a widget with
        // decorations bolted on rather than as one object. Everything now happens
        // inside the disc.
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wave = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbit = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dust = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wavePath = Path()
        private val clip = Path()

        private var state: OrbState = OrbState.Idle
        private var amplitude: Float = 0f
        private var phase: Float = 0f

        var onDragStarted: (() -> Unit)? = null
        var onDragged: ((Int, Int) -> Unit)? = null
        var onReleased: (() -> Unit)? = null
        var onTapped: (() -> Unit)? = null

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var downAt = 0L
        private var dragging = false

        fun setAssistantState(next: OrbState, level: Float) {
            val was = state
            state = next
            amplitude = level.coerceIn(0f, 1f)
            // Only repaint when something visible changed. This view sits over
            // every app on the phone, so a redraw it did not need is battery
            // somebody else's foreground app is paying for.
            if (was != next || animates()) invalidate()
        }

        fun setScheme(next: BubbleColors.Scheme) {
            scheme = next
            invalidate()
        }

        /** Idle draws once and stops. Everything else is alive and must tick. */
        private fun animates(): Boolean =
            state == OrbState.Listening || state == OrbState.Thinking || state == OrbState.Speaking

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.38f
            val core = colourFor(state)

            // Halo. Diffuse, no edge anywhere, so it reads as light coming off
            // the orb rather than as a second circle drawn round the first.
            val reach = minOf(width, height) * 0.50f
            glow.shader = RadialGradient(
                cx, cy, reach,
                intArrayOf(
                    withAlpha(core, if (animates()) 0.34f else 0.20f),
                    withAlpha(core, 0.07f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.58f, 0.80f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, reach, glow)

            // The body: deep space, darkest at the rim. Everything else is drawn
            // inside it, which is what makes this one object rather than a disc
            // with decorations stuck on.
            fill.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    blend(scheme.background, scheme.secondary, 0.45f),
                    blend(scheme.background, scheme.secondary, 0.18f),
                    darken(scheme.background, 0.30f),
                ),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, fill)

            clip.reset()
            clip.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)

            starDust(canvas, cx, cy, r)
            orbits(canvas, cx, cy, r)
            // Kept from the previous design, and only where it was asked for:
            // while he is TALKING, a swell runs across the lower part of the disc.
            if (state == OrbState.Speaking) waves(canvas, cx, cy, r, core)
            burningCore(canvas, cx, cy, r, core)

            canvas.restore()

            if (animates()) {
                phase += 0.10f
                if (phase > 10_000f) phase = 0f
                postInvalidateOnAnimation()
            }
        }

        /**
         * Two tilted ellipses turning around the core — the in-app orb's shape,
         * shrunk to a thumbnail.
         *
         * They live INSIDE the disc, which is the distinction that matters: the
         * version before this drew rings AROUND the circle and the user's note was
         * that it looked bolted on. An orbit crossing in front of and behind a
         * core is part of the object; a ring around a circle is a border.
         */
        private fun orbits(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val turn = phase * (if (animates()) 0.55f else 0.12f)
            for (i in 0 until ORBITS) {
                val tilt = ORBIT_TILTS[i] + turn * (if (i == 0) 1f else -0.7f)
                // Breathing the minor axis is what sells rotation in 3D from a
                // 2D ellipse — a ring that only spins in place looks flat.
                val squash = 0.20f + 0.26f * kotlin.math.abs(kotlin.math.sin(turn * 0.8f + i))
                val rx = r * (0.86f - i * 0.16f)
                val ry = rx * squash

                canvas.save()
                canvas.rotate(Math.toDegrees(tilt.toDouble()).toFloat(), cx, cy)
                orbit.style = Paint.Style.STROKE
                orbit.strokeWidth = (if (i == 0) 1.6f else 1.2f) * density(1f)
                orbit.color = withAlpha(scheme.highlight, if (i == 0) 0.60f else 0.38f)
                canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, orbit)
                canvas.restore()
            }
        }

        /** A handful of motes, so the inside of the disc is space and not paint. */
        private fun starDust(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            dust.style = Paint.Style.FILL
            for (i in 0 until DUST) {
                // Deterministic placement. A Canvas redraws every frame, so a real
                // random would re-scatter these into static instead of a sky.
                val a = unit(i * 3 + 1) * 6.2831853f
                val d = 0.20f + unit(i * 3 + 2) * 0.74f
                val x = cx + kotlin.math.cos(a) * r * d
                val y = cy + kotlin.math.sin(a) * r * d
                val twinkle = 0.55f + 0.45f * kotlin.math.sin(phase * 1.3f + i)
                dust.color = withAlpha(
                    if (unit(i * 7 + 3) > 0.6f) scheme.highlight else Color.WHITE,
                    (0.20f + unit(i * 5 + 4) * 0.45f) * twinkle,
                )
                canvas.drawCircle(x, y, (0.6f + unit(i * 11 + 5) * 1.2f) * density(1f), dust)
            }
        }

        /** The lit centre. Its size and brightness are the state at a glance. */
        private fun burningCore(canvas: Canvas, cx: Float, cy: Float, r: Float, core: Int) {
            val pulse = when (state) {
                OrbState.Speaking -> 1f + 0.22f * kotlin.math.sin(phase * 2.6f)
                OrbState.Listening -> 1f + amplitude * 0.55f
                OrbState.Thinking -> 1f + 0.12f * kotlin.math.sin(phase * 1.2f)
                else -> 0.86f
            }
            val cr = r * 0.30f * pulse
            fill.shader = RadialGradient(
                cx, cy, cr * 2.4f,
                intArrayOf(
                    Color.WHITE,
                    lighten(core, 0.45f),
                    withAlpha(core, 0.55f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.20f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, cr * 2.4f, fill)
        }

        /**
         * The swell the user asked for, kept for the one state it describes.
         *
         * Three stacked bands rather than a single line: one stroke at this size
         * reads as a scratch, while translucent fills overlapping give the depth
         * that makes it look like motion in a volume.
         */
        private fun waves(canvas: Canvas, cx: Float, cy: Float, r: Float, core: Int) {
            val level = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(phase * 1.7f))
            for (layer in 0 until WAVE_LAYERS) {
                val depth = layer.toFloat() / WAVE_LAYERS
                val baseline = cy + r * (0.30f + depth * 0.28f)
                val height = r * (0.16f * level) * (1f - depth * 0.35f)
                val length = 2.1f + layer * 0.75f
                val speed = phase * (1.5f + layer * 0.45f) * (if (layer % 2 == 0) 1f else -1f)

                wavePath.reset()
                wavePath.moveTo(cx - r, cy + r)
                var x = cx - r
                while (x <= cx + r) {
                    val t = (x - cx) / r
                    wavePath.lineTo(x, baseline - kotlin.math.sin(t * length + speed) * height)
                    x += WAVE_STEP
                }
                wavePath.lineTo(cx + r, cy + r)
                wavePath.close()

                wave.shader = null
                wave.style = Paint.Style.FILL
                wave.color = withAlpha(core, (0.34f - depth * 0.10f) * level)
                canvas.drawPath(wavePath, wave)
            }
        }

        /** Deterministic 0..1 from an index — never `Math.random` on a draw path. */
        private fun unit(seed: Int): Float {
            val x = kotlin.math.sin(seed * 12.9898f) * 43758.5453f
            return (x - kotlin.math.floor(x))
        }

        private fun colourFor(s: OrbState): Int = when (s) {
            OrbState.Listening -> scheme.accent
            OrbState.Thinking -> scheme.highlight
            OrbState.Speaking -> scheme.accent
            OrbState.Error, OrbState.Offline -> 0xFF8A5A5A.toInt()
            // Idle is deliberately dimmer than every active state. An orb sitting
            // at full brightness all day stops meaning anything.
            OrbState.Idle -> blend(scheme.accent, scheme.background, 0.34f)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    lastX = event.rawX
                    lastY = event.rawY
                    downAt = System.currentTimeMillis()
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (!dragging && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                        dragging = true
                        // The ✕ appears now, not on touch-down: a tap must not
                        // flash a dismiss target the user never asked to see.
                        onDragStarted?.invoke()
                    }
                    if (dragging) {
                        onDragged?.invoke(dx.toInt(), dy.toInt())
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val quick = System.currentTimeMillis() - downAt < TAP_MS
                    val still = abs(event.rawX - downX) <= slop && abs(event.rawY - downY) <= slop
                    if (!dragging && quick && still) onTapped?.invoke() else onReleased?.invoke()
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onReleased?.invoke()
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun density(dp: Float) = dp * resources.displayMetrics.density

        private fun withAlpha(colour: Int, alpha: Float): Int =
            (colour and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f) * 255).toInt()) shl 24)

        private fun lighten(colour: Int, amount: Float): Int = blend(colour, Color.WHITE, amount)

        private fun darken(colour: Int, amount: Float): Int = blend(colour, Color.BLACK, amount)

        private fun blend(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.argb(
                255,
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
            )
        }

        private companion object {
            const val TAP_MS = 400L

            /** Enough for depth, few enough to stay legible at 76dp. */
            const val WAVE_LAYERS = 3

            /** Two reads as orbits; three at this size reads as scribble. */
            const val ORBITS = 2
            val ORBIT_TILTS = floatArrayOf(-0.42f, 0.66f)

            /** Motes inside the disc. Enough to be space, few enough to be cheap. */
            const val DUST = 14

            /** Pixels between points along a wave. Small enough to look smooth. */
            const val WAVE_STEP = 3f
        }
    }

    /**
     * The ✕ at the bottom of the screen, shown only while the orb is being
     * dragged. Grows and brightens when the orb is close enough to drop.
     */
    private class DismissTarget(context: Context) : View(context) {

        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var armed = false

        fun setArmed(value: Boolean) {
            if (armed == value) return
            armed = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f * (if (armed) 0.98f else 0.80f)
            val d = resources.displayMetrics.density

            // A soft halo so the target is findable against any wallpaper.
            disc.shader = RadialGradient(
                cx, cy, r * 1.6f,
                intArrayOf(
                    Color.argb(if (armed) 130 else 80, 0, 0, 0),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r * 1.6f, disc)

            disc.shader = null
            disc.color = if (armed) Color.argb(235, 200, 60, 60) else Color.argb(180, 40, 40, 46)
            canvas.drawCircle(cx, cy, r, disc)

            cross.color = Color.WHITE
            cross.strokeWidth = 2.4f * d
            val arm = r * 0.36f
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
            canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, cross)
        }
    }

    private companion object {
        /** Matches the reference the user pointed at, and Android's own bubbles. */
        const val BUBBLE_DP = 76f
        const val SNAP_MS = 180L

        /** The ✕ target. Bigger than the orb, because it is aimed at blind. */
        const val DISMISS_DP = 64f
        const val DISMISS_MARGIN_DP = 48f

        /**
         * How close counts as "on" the target, as a multiple of its size.
         * Generous on purpose — the finger covers the orb during a drag, so the
         * drop is aimed by feel and a tight radius reads as the target not working.
         */
        const val DISMISS_REACH = 1.25f
    }
}
