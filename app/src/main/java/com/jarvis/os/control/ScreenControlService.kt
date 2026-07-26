package com.jarvis.os.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that lets JARVIS act on the screen: it can find a
 * control by its visible label, draw a glowing outline around it, and tap it.
 * Runs independently of the app's Activity, so it can act on whatever app is in
 * front (e.g. one JARVIS just opened). It only ever does something when the app
 * explicitly calls [tapWhenReady]; it does not react on its own.
 */
class ScreenControlService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var outline: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* command-driven only */ }

    override fun onInterrupt() { }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOutline()
        instance = null
        super.onDestroy()
    }

    /**
     * Wait (briefly) until [targetPackage] is the foreground app — or, if it is
     * null, until any app other than JARVIS is in front — then find [label],
     * outline it, and tap it. [onDone] reports whether a tap happened.
     */
    fun tapWhenReady(targetPackage: String?, label: String, onDone: (Boolean) -> Unit = {}) {
        attempt(targetPackage, label, 0, onDone)
    }

    private fun attempt(targetPackage: String?, label: String, tries: Int, onDone: (Boolean) -> Unit) {
        val root = rootInActiveWindow
        val frontPkg = root?.packageName?.toString()
        val ready = root != null && when {
            targetPackage != null -> frontPkg == targetPackage
            else -> frontPkg != null && frontPkg != packageName
        }
        if (ready && root != null) {
            val node = findByLabel(root, label)
            if (node != null) {
                tapNode(node)
                onDone(true)
                return
            }
        }
        if (tries < MAX_TRIES) {
            handler.postDelayed({ attempt(targetPackage, label, tries + 1, onDone) }, STEP_MS)
        } else {
            onDone(false)
        }
    }

    /** Breadth-first search for a node whose text or description contains [label]. */
    private fun findByLabel(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val query = label.lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var textFallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (text.contains(query) || desc.contains(query)) {
                val clickable = clickableSelfOrAncestor(node)
                if (clickable != null) return clickable
                if (textFallback == null) textFallback = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return textFallback
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun tapNode(node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        showOutline(bounds)
        // Prefer a real click on the node; fall back to a tap gesture at its centre.
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun showOutline(rect: Rect) {
        removeOutline()
        val view = OutlineView(this, RectF(rect))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(view, params)
            outline = view
            handler.postDelayed({ removeOutline() }, OUTLINE_MS)
        } catch (e: Exception) {
            outline = null
        }
    }

    private fun removeOutline() {
        outline?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        outline = null
    }

    /** Full-screen transparent view that draws a cyan glow rectangle at [target]. */
    private class OutlineView(context: Context, private val target: RectF) : View(context) {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = Color.parseColor("#3300D4FF")
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.parseColor("#00D4FF")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = RectF(target).apply { inset(-6f, -6f) }
            canvas.drawRoundRect(r, 18f, 18f, glow)
            canvas.drawRoundRect(r, 18f, 18f, border)
        }
    }

    companion object {
        @Volatile
        var instance: ScreenControlService? = null
            private set

        /** True when the user has enabled JARVIS under Accessibility settings. */
        fun isRunning(): Boolean = instance != null

        private const val MAX_TRIES = 20      // ~ MAX_TRIES * STEP_MS before giving up
        private const val STEP_MS = 350L
        private const val OUTLINE_MS = 1100L
    }
}
