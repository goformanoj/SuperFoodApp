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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.os.debug.DebugLog

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

    /** Run an ordered sequence of steps (open / tap / type / enter), one after another. */
    fun runSteps(steps: List<ScreenStep>, onDone: (Boolean) -> Unit = {}) {
        runStep(steps, 0, null, onDone)
    }

    private fun runStep(
        steps: List<ScreenStep>,
        index: Int,
        expectedPackage: String?,
        onDone: (Boolean) -> Unit,
    ) {
        if (index >= steps.size) {
            onDone(true)
            return
        }
        val advance = { pkg: String? ->
            handler.postDelayed({ runStep(steps, index + 1, pkg, onDone) }, STEP_SETTLE_MS)
        }
        // Per-step tracing, so a shared log shows WHICH step failed rather than
        // just that the sequence ran.
        val position = "step ${index + 1}/${steps.size}"
        val failed = { reason: String ->
            DebugLog.log(DebugLog.Stage.SCREEN, "$position ${steps[index]} FAILED — $reason")
            onDone(false)
        }
        DebugLog.log(DebugLog.Stage.SCREEN, "$position ${steps[index]}")
        when (val step = steps[index]) {
            is ScreenStep.Open -> {
                val pkg = AppLauncher.launch(this, step.app)
                // Give the app a moment to come to the foreground before the next step.
                handler.postDelayed({ runStep(steps, index + 1, pkg, onDone) }, APP_OPEN_MS)
            }
            is ScreenStep.Tap ->
                awaitApp(expectedPackage, step.label, 0) { ok ->
                    if (ok) advance(expectedPackage) else failed("no control matching \"${step.label}\"")
                }
            is ScreenStep.Type ->
                typeWhenReady(step.text, 0) { ok ->
                    if (ok) advance(expectedPackage) else failed("no editable field appeared")
                }
            is ScreenStep.Enter -> {
                // Submitting a search changes the whole screen, and a fixed delay
                // is a guess — results routinely take longer than one. Wait for
                // the content to actually change before the next step runs,
                // otherwise a following tap resolves against the OLD screen.
                val before = contentFingerprint()
                pressImeAction()
                awaitContentChange(before, 0) { advance(expectedPackage) }
            }
        }
    }

    /**
     * A cheap signature of the visible text, used to tell "the screen changed"
     * from "nothing has happened yet". Bounded so it stays cheap on deep trees.
     */
    private fun contentFingerprint(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < FINGERPRINT_NODES) {
            val node = queue.removeFirst()
            seen++
            node.text?.let { sb.append(it).append(SEP) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return sb.toString()
    }

    /** Poll until the screen differs from [before], then let it finish rendering. */
    private fun awaitContentChange(before: String, tries: Int, onChanged: () -> Unit) {
        val changed = contentFingerprint() != before
        if (changed || tries >= CONTENT_WAIT_TRIES) {
            if (!changed) {
                DebugLog.log(DebugLog.Stage.SCREEN, "screen never changed after enter — continuing anyway")
            }
            // A list can be present but still painting; give it a moment.
            handler.postDelayed(onChanged, CONTENT_SETTLE_MS)
            return
        }
        handler.postDelayed({ awaitContentChange(before, tries + 1, onChanged) }, STEP_MS)
    }

    /** Wait for an editable field, then set its text. */
    private fun typeWhenReady(text: String, tries: Int, onDone: (Boolean) -> Unit) {
        val field = rootInActiveWindow?.let { findEditable(it) }
        if (field != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            onDone(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        } else if (tries < FIELD_WAIT_TRIES) {
            handler.postDelayed({ typeWhenReady(text, tries + 1, onDone) }, STEP_MS)
        } else {
            onDone(false)
        }
    }

    /** Press the IME action (search / go / enter) on the focused field. */
    private fun pressImeAction(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val field = rootInActiveWindow?.let { findEditable(it) } ?: return false
        return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** The focused input field, or the first editable field on screen. */
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
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
            // A search box still holds the query text after you search, so it
            // scores a perfect exact match and beats the actual result — tapping
            // it just puts the cursor back in the search bar. Heavily demote
            // editable fields rather than excluding them, so "<<TAP|Search>>"
            // still works in apps whose search entry really is a text field.
            val score = matchScore(node, query).let { if (node.isEditable) it / EDITABLE_PENALTY else it }
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

    /**
     * Scroll DOWN by simulating a real finger swipe UP over the scrollable area.
     * This doesn't depend on the list advertising any scroll action (some don't),
     * and a vertical swipe can't flip horizontal tabs. Returns false only if there
     * is nothing scrollable to swipe over.
     */
    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        val area = largestScrollableBounds(root) ?: return false
        if (area.height() < 200) return false
        val x = area.exactCenterX()
        val startY = area.top + area.height() * 0.75f
        val endY = area.top + area.height() * 0.30f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 250L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** On-screen bounds of the largest scrollable region (the list/content area). */
    private fun largestScrollableBounds(root: AccessibilityNodeInfo): Rect? {
        var best: Rect? = null
        var bestArea = -1
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) {
                    best = r
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
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
        // Sequence pacing: settle between steps, wait after launching an app, and
        // how long to wait for a text field to appear before typing.
        private const val STEP_SETTLE_MS = 700L
        private const val APP_OPEN_MS = 1200L
        private const val FIELD_WAIT_TRIES = 15
        // Scrolling to hunt for an off-screen target.
        private const val MAX_SCROLLS = 8
        private const val SCROLL_SETTLE_MS = 550L
        // A match this good is tapped immediately; weaker ones make us scroll first.
        private const val GOOD_SCORE = 70

        /** Separator between node texts in the screen fingerprint. */
        private const val SEP = '\u0001'

        /** How far the content fingerprint walks the tree before stopping. */
        private const val FINGERPRINT_NODES = 250

        /** Polls (of STEP_MS each) to wait for the screen to change after enter. */
        private const val CONTENT_WAIT_TRIES = 12

        /** Extra settle once the screen has changed, so a list finishes painting. */
        private const val CONTENT_SETTLE_MS = 450L

        /** Divisor demoting editable fields, which hold the query text after a search. */
        private const val EDITABLE_PENALTY = 4
        private const val OUTLINE_MS = 1100L
    }
}
