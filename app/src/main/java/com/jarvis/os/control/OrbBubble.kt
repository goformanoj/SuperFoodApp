package com.jarvis.os.control

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

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
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            // The glow needs room outside the body, so the body is a fraction of
            // the window rather than filling it.
            val body = minOf(w, h) * 0.36f

            val core = colourFor(state)

            // Soft halo. Wider and brighter while he is doing something.
            val reach = minOf(w, h) * (if (animates()) 0.50f else 0.44f)
            glow.shader = RadialGradient(
                cx, cy, reach,
                intArrayOf(
                    withAlpha(core, if (animates()) 0.42f else 0.26f),
                    withAlpha(core, 0.10f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.45f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, reach, glow)

            // The dome: lit from the upper left, falling to near-black at the
            // lower rim, which is what makes a flat circle read as a sphere.
            fill.shader = RadialGradient(
                cx - body * 0.32f, cy - body * 0.38f, body * 1.55f,
                intArrayOf(
                    lighten(core, 0.42f),
                    core,
                    blend(scheme.secondary, scheme.background, 0.45f),
                    scheme.background,
                ),
                floatArrayOf(0f, 0.34f, 0.74f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, body, fill)

            // A hairline rim so the orb has an edge against a light wallpaper.
            ring.shader = null
            ring.color = withAlpha(lighten(core, 0.55f), 0.55f)
            ring.strokeWidth = density(1.2f)
            canvas.drawCircle(cx, cy, body, ring)

            when (state) {
                // A ring that grows with what the microphone is actually hearing,
                // so a glance says whether he can hear you — the same job the
                // in-app waveform does, in the space of a full stop.
                OrbState.Listening -> {
                    val r = body * (1.16f + amplitude * 0.30f)
                    ring.color = withAlpha(scheme.accent, 0.30f + amplitude * 0.45f)
                    ring.strokeWidth = density(2f)
                    canvas.drawCircle(cx, cy, r, ring)
                }
                // One arc going round. Not a spinner made of dots: at this size a
                // single sweeping stroke is the only motion that stays legible.
                OrbState.Thinking -> {
                    val r = body * 1.20f
                    ring.color = withAlpha(scheme.highlight, 0.85f)
                    ring.strokeWidth = density(2.4f)
                    val box = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(box, phase * 57.29578f, 90f, false, ring)
                }
                // A slow breath outward while he talks.
                OrbState.Speaking -> {
                    val pulse = 1.14f + 0.10f * kotlin.math.sin(phase * 2.4f)
                    ring.color = withAlpha(scheme.accent, 0.55f)
                    ring.strokeWidth = density(2f)
                    canvas.drawCircle(cx, cy, body * pulse, ring)
                }
                else -> Unit
            }

            if (animates()) {
                phase += 0.09f
                if (phase > 1000f) phase = 0f
                postInvalidateOnAnimation()
            }
        }

        private fun colourFor(s: OrbState): Int = when (s) {
            OrbState.Listening -> scheme.accent
            OrbState.Thinking -> scheme.highlight
            OrbState.Speaking -> scheme.secondary
            OrbState.Error, OrbState.Offline -> 0xFF8A5A5A.toInt()
            // Idle is deliberately dimmer than every active state. A bubble that
            // sits at full brightness all day stops meaning anything.
            OrbState.Idle -> blend(scheme.accent, scheme.background, 0.42f)
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
        }
    }

    private companion object {
        /** Matches the reference the user pointed at, and Android's own bubbles. */
        const val BUBBLE_DP = 76f
        const val SNAP_MS = 180L
    }
}
