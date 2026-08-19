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
 * accessibility overlay can be **touchable** — the existing scrim and tap outline
 * only look untouchable because they deliberately set `FLAG_NOT_TOUCHABLE` so
 * JARVIS's own gestures pass through them. Leave that flag off and the same
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
 * that, matches how [ScreenControlService] already draws its scrim and outline,
 * and keeps this whole file out of the Compose dependency — which is what lets
 * the off-device gate compile it. See [BubbleColors].
 */
class OrbBubble(private val service: AccessibilityService) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = UserPreferences(service)

    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

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
            // Deliberately NOT `FLAG_NOT_TOUCHABLE` — unlike the scrim, this window
            // is the one thing on screen that IS meant to take a touch. Still not
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
        bubble.onDragged = { dx, dy -> moveBy(dx, dy) }
        bubble.onReleased = { snapToEdge() }
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
        private val wavePath = Path()
        private val clip = Path()

        private var state: OrbState = OrbState.Idle
        private var amplitude: Float = 0f
        private var phase: Float = 0f

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
            // Room is left around the body for the halo. The disc itself is a
            // fraction of the window, not the whole of it.
            val r = minOf(width, height) * 0.38f
            val core = colourFor(state)

            // A diffuse halo — not a ring. It has no edge anywhere, so it reads
            // as light coming off the orb rather than as a second circle drawn
            // around the first, which is exactly the thing that was wrong before.
            val reach = minOf(width, height) * 0.50f
            glow.shader = RadialGradient(
                cx, cy, reach,
                intArrayOf(
                    withAlpha(core, if (animates()) 0.30f else 0.18f),
                    withAlpha(core, 0.06f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.62f, 0.82f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, reach, glow)

            // The body: near-black across the top falling to a lit floor. A
            // vertical ramp rather than a centred radial, because the light in
            // the reference comes from BELOW — that is what stops it looking like
            // a button with a highlight on it.
            fill.shader = LinearGradient(
                cx, cy - r, cx, cy + r,
                intArrayOf(
                    darken(scheme.background, 0.35f),
                    blend(scheme.background, scheme.secondary, 0.30f),
                    blend(scheme.secondary, core, 0.65f),
                    darken(blend(scheme.secondary, core, 0.35f), 0.30f),
                ),
                floatArrayOf(0f, 0.42f, 0.80f, 1f),
                Shader.TileMode.CLAMP,
            )
            fill.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r, fill)

            // Waves, inside the disc and clipped to it, so they behave like
            // something moving within the orb instead of over it.
            clip.reset()
            clip.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            drawWaves(canvas, cx, cy, r, core)
            canvas.restore()

            if (animates()) {
                phase += 0.10f
                if (phase > 10_000f) phase = 0f
                postInvalidateOnAnimation()
            }
        }

        /**
         * Three stacked sine bands filling the lower part of the disc, like
         * liquid with a swell running through it.
         *
         * Layered rather than one line because a single stroke at this size reads
         * as a scratch; three translucent fills overlapping give the depth that
         * makes it look like motion in a volume. Each has its own wavelength,
         * speed and height, so they never line up into one thick band.
         *
         * How high the swell runs is the whole state display:
         *  - **Speaking** — tall and quick. This is the one the user asked for.
         *  - **Listening** — driven by the real microphone level, so a glance says
         *    whether he can hear you.
         *  - **Thinking** — a slow, even roll that is plainly not silence.
         *  - **Idle** — nearly flat. Still water, drawn once, not animating.
         */
        private fun drawWaves(canvas: Canvas, cx: Float, cy: Float, r: Float, core: Int) {
            val level = when (state) {
                OrbState.Speaking -> 0.62f + 0.38f * kotlin.math.abs(kotlin.math.sin(phase * 1.7f))
                OrbState.Listening -> 0.20f + amplitude * 0.80f
                OrbState.Thinking -> 0.34f
                else -> 0.06f
            }
            val crest = lighten(core, 0.42f)

            for (layer in 0 until WAVE_LAYERS) {
                val depth = layer.toFloat() / WAVE_LAYERS
                // Each band sits a little lower and swells a little less, so the
                // front one leads and the ones behind follow.
                val baseline = cy + r * (0.06f + depth * 0.34f)
                val height = r * (0.30f * level) * (1f - depth * 0.35f)
                val length = 2.1f + layer * 0.75f
                val speed = phase * (1.5f + layer * 0.45f) * (if (layer % 2 == 0) 1f else -1f)

                wavePath.reset()
                wavePath.moveTo(cx - r, cy + r)
                var x = cx - r
                while (x <= cx + r) {
                    val t = (x - cx) / r
                    val y = baseline - kotlin.math.sin(t * length + speed) * height
                    wavePath.lineTo(x, y)
                    x += WAVE_STEP
                }
                wavePath.lineTo(cx + r, cy + r)
                wavePath.close()

                wave.shader = null
                wave.style = Paint.Style.FILL
                wave.color = withAlpha(
                    if (layer == 0) crest else core,
                    (0.55f - depth * 0.22f) * (0.45f + level * 0.55f),
                )
                canvas.drawPath(wavePath, wave)
            }
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

            /** Pixels between points along a wave. Small enough to look smooth. */
            const val WAVE_STEP = 3f
        }
    }

    private companion object {
        /** Matches the reference the user pointed at, and Android's own bubbles. */
        const val BUBBLE_DP = 76f
        const val SNAP_MS = 180L
    }
}
