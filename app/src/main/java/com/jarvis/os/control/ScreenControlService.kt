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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that lets JARVIS act on the screen: it finds a control by
 * its visible label — preferring an exact name over a partial one, and real text
 * over a content-description — draws a glowing outline around it, and taps it. If
 * the target isn't on screen it scrolls to look for it. Runs independently of the
 * app's Activity so it can act on whatever app is in front. It only ever does
 * something when the app explicitly calls [tapWhenReady]; it never reacts on its
 * own.
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
     * null, until any app other than JARVIS is in front — then find [label]
     * (scrolling to look for it if needed), outline it, and tap it. [onDone]
     * reports whether a tap happened.
     */
    fun tapWhenReady(targetPackage: String?, label: String, onDone: (Boolean) -> Unit = {}) {
        awaitApp(targetPackage, label, 0, onDone)
    }

    private fun awaitApp(targetPackage: String?, label: String, tries: Int, onDone: (Boolean) -> Unit) {
        val root = rootInActiveWindow
        val frontPkg = root?.packageName?.toString()
        val ready = root != null && when {
            targetPackage != null -> frontPkg == targetPackage
            else -> frontPkg != null && frontPkg != packageName
        }
        if (ready) {
            seek(label, 0, onDone)
        } else if (tries < APP_WAIT_TRIES) {
            handler.postDelayed({ awaitApp(targetPackage, label, tries + 1, onDone) }, STEP_MS)
        } else {
            onDone(false)
        }
    }

    /**
     * Look for [label] on the current screen. Tap immediately on a confident match;
     * otherwise scroll and try again. When there's nothing left to scroll, tap the
     * best weak match we saw, or give up.
     */
    private fun seek(label: String, scrolls: Int, onDone: (Boolean) -> Unit) {
        val root = rootInActiveWindow
        if (root == null) {
            onDone(false)
            return
        }
        val (node, score) = bestMatch(root, label)
        if (node != null && score >= GOOD_SCORE) {
            tapNode(node)
            onDone(true)
            return
        }
        if (scrolls < MAX_SCROLLS && scrollForward(root)) {
            handler.postDelayed({ seek(label, scrolls + 1, onDone) }, SCROLL_SETTLE_MS)
            return
        }
        if (node != null) {
            tapNode(node)
            onDone(true)
        } else {
            onDone(false)
        }
    }

    /** Best-scoring clickable node for [label] anywhere in the tree, with its score. */
    private fun bestMatch(root: AccessibilityNodeInfo, label: String): Pair<AccessibilityNodeInfo?, Int> {
        val query = label.trim().lowercase()
        if (query.isEmpty()) return null to 0
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val score = matchScore(node, query)
            if (score > bestScore) {
                best = clickableSelfOrAncestor(node) ?: node
                bestScore = score
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best to bestScore
    }

    /** Higher = better. Exact beats partial; visible text beats content-description. */
    private fun matchScore(node: AccessibilityNodeInfo, query: String): Int {
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        return maxOf(fieldScore(text, query, isText = true), fieldScore(desc, query, isText = false))
    }

    private fun fieldScore(value: String, query: String, isText: Boolean): Int {
        if (value.isEmpty()) return 0
        return when {
            value == query -> if (isText) 100 else 85
            startsWithWord(value, query) -> if (isText) 90 else 60
            containsWord(value, query) -> if (isText) 65 else 45
            value.contains(query) -> if (isText) 55 else 35
            else -> 0
        }
    }

    /** [s] starts with [q] followed by a word boundary (so "mom" matches "mom (dad)" but not "mom's status"). */
    private fun startsWithWord(s: String, q: String): Boolean {
        if (!s.startsWith(q)) return false
        if (s.length == q.length) return true
        val next = s[q.length]
        return !next.isLetterOrDigit() && next != '\''
    }

    /** [q] appears in [s] as a standalone word. */
    private fun containsWord(s: String, q: String): Boolean {
        var idx = s.indexOf(q)
        while (idx >= 0) {
            val before = if (idx == 0) ' ' else s[idx - 1]
            val afterIdx = idx + q.length
            val after = if (afterIdx >= s.length) ' ' else s[afterIdx]
            if (!before.isLetterOrDigit() && before != '\'' && !after.isLetterOrDigit() && after != '\'') {
                return true
            }
            idx = s.indexOf(q, idx + 1)
        }
        return false
    }

    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        val node = findVerticalScrollable(root) ?: return false
        // Prefer an explicit downward scroll (API 30+) so we never flip sideways
        // through tabs; fall back to generic forward on the chosen vertical node.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            if (node.actionList.contains(down)) return node.performAction(down.id)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /**
     * The largest VERTICALLY-scrollable node (a chat/message list) — not a
     * horizontal tab pager (Chats / Updates / Calls), which the old code grabbed
     * first and scrolled sideways.
     */
    private fun findVerticalScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = -1
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable && isVertical(node)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    /** True if [node] scrolls vertically rather than horizontally. */
    private fun isVertical(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val actions = node.actionList
            val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            val up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            val right = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            val left = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            if (actions.contains(down) || actions.contains(up)) return true
            if (actions.contains(right) || actions.contains(left)) return false
        }
        // A vertical list has one (or few) columns and many rows.
        node.collectionInfo?.let { ci ->
            if (ci.columnCount <= 1) return true
            if (ci.rowCount <= 1) return false
        }
        // Last resort: taller than wide is usually a vertical list.
        val r = Rect()
        node.getBoundsInScreen(r)
        return r.height() >= r.width()
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

        // How long to wait for the launched app to reach the foreground.
        private const val APP_WAIT_TRIES = 20
        private const val STEP_MS = 300L
        // Scrolling to hunt for an off-screen target.
        private const val MAX_SCROLLS = 8
        private const val SCROLL_SETTLE_MS = 550L
        // A match this good is tapped immediately; weaker ones make us scroll first.
        private const val GOOD_SCORE = 70
        private const val OUTLINE_MS = 1100L
    }
}
