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
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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

    // Identifies the sequence currently allowed to run. A new command bumps it,
    // so any callback from an older chain sees a stale token and stops.
    private var runToken = 0
    private var sequenceRunning = false
    private var recoveriesLeft = 0

    // A translucent full-screen tint shown while JARVIS is driving the screen, so
    // the user can see at a glance that the taps are JARVIS's, not theirs. Drawn
    // as a non-touchable accessibility overlay — the same mechanism as the tap
    // outline, so it needs no "draw over other apps" permission.
    private var scrim: View? = null
    private val scrimSafety = Runnable { removeScrim() }

    // What the user was last looking at. Written from accessibility events and
    // from each successful read, so JARVIS still knows the state of the app the
    // user is in even while its own UI is the foreground window. Volatile because
    // the engine reads these from a background thread when building context.
    @Volatile private var lastAppPackage: String? = null
    @Volatile private var lastAppScreen: String = ""
    @Volatile private var lastAppSeenAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    /**
     * Never acts on its own — it only remembers which app is in front. That memory
     * is what lets JARVIS answer "carry on from here" correctly when its own UI is
     * the foreground window and it therefore cannot read the user's app directly.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != packageName) lastAppPackage = pkg
    }

    override fun onInterrupt() { }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeScrim()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOutline()
        removeScrim()
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

    /**
     * Run an ordered sequence of steps (open / tap / type / enter), one after
     * another. A new sequence SUPERSEDES one still in flight: without that, two
     * chains interleave and fight over the same screen — seen on a device as one
     * sequence typing while another was tapping Voice search.
     *
     * [onDone] reports `ok` — the sequence finished — and `clean`, meaning it
     * finished WITHOUT any step failing into recovery. The two are not the same,
     * and conflating them is why the playbook learned three broken routes in one
     * session: recovery rescued each run, the run reported success, and the
     * original wrong steps were stored as if they had worked.
     */
    fun runSteps(
        steps: List<ScreenStep>,
        recover: Boolean = true,
        onDone: (ok: Boolean, clean: Boolean) -> Unit = { _, _ -> },
    ) {
        if (sequenceRunning) {
            DebugLog.log(DebugLog.Stage.SCREEN, "new command superseded the sequence still running")
        }
        handler.removeCallbacksAndMessages(null)
        sequenceRunning = true
        // Bounded: recovery that can re-plan forever would loop on an app that
        // simply cannot do what was asked.
        recoveriesLeft = MAX_RECOVERIES
        recoveredThisRun = false
        // The errand loop drives one step at a time and decides the next itself.
        // Leaving recovery on there would put two loops on the same screen.
        recoverEnabled = recover
        runStep(steps, 0, null, ++runToken, onDone)
    }

    /** True once any step in the current run has failed and been recovered. */
    private var recoveredThisRun = false

    /** False when the caller is driving the loop and will handle failure itself. */
    private var recoverEnabled = true

    private fun runStep(
        steps: List<ScreenStep>,
        index: Int,
        expectedPackage: String?,
        token: Int,
        onDone: (ok: Boolean, clean: Boolean) -> Unit,
    ) {
        // A later command has taken over; abandon this chain silently.
        if (token != runToken) return
        // Keep the control tint alive while steps are actually running, so its
        // safety timer only fires if the engine truly stalls without clearing it.
        if (scrim != null) {
            handler.removeCallbacks(scrimSafety)
            handler.postDelayed(scrimSafety, SCRIM_SAFETY_MS)
        }
        if (index >= steps.size) {
            sequenceRunning = false
            onDone(true, !recoveredThisRun)
            return
        }
        val advance = { pkg: String? ->
            handler.postDelayed({ runStep(steps, index + 1, pkg, token, onDone) }, STEP_SETTLE_MS)
        }
        // Per-step tracing, so a shared log shows WHICH step failed rather than
        // just that the sequence ran.
        val position = "step ${index + 1}/${steps.size}"
        val failed = { reason: String ->
            DebugLog.log(DebugLog.Stage.SCREEN, "$position ${steps[index]} FAILED — $reason")
            val recover = onRecover
            // A failed step used to end the task. Now the screen is handed back
            // for a fresh plan: the label was guessed before this screen existed,
            // so the answer is usually visible right now (Amazon Music's search
            // is simply not labelled "Search").
            if (recover != null && recoverEnabled && recoveriesLeft > 0) {
                recoveriesLeft--
                recoveredThisRun = true
                DebugLog.log(DebugLog.Stage.SCREEN, "recovering — asking what to do from this screen")
                recover(steps[index], reason, describeScreen()) { replacement ->
                    if (token != runToken) return@recover
                    if (replacement.isEmpty()) {
                        sequenceRunning = false
                        DebugLog.log(DebugLog.Stage.SCREEN, "recovery found nothing to try")
                        onDone(false, false)
                    } else {
                        DebugLog.log(DebugLog.Stage.SCREEN, "recovery plan: ${replacement.joinToString(", ")}")
                        runStep(replacement, 0, expectedPackage, token, onDone)
                    }
                }
            } else {
                sequenceRunning = false
                onDone(false, false)
            }
        }
        DebugLog.log(DebugLog.Stage.SCREEN, "$position ${steps[index]}")
        when (val step = steps[index]) {
            is ScreenStep.Open -> {
                val target = AppLauncher.resolvePackage(this, step.app)
                if (target != null && target == rootInActiveWindow?.packageName?.toString()) {
                    // Already here. Relaunching would reset the app to its home
                    // screen and throw away the search results the user is
                    // looking at — which is how "search another song from here"
                    // ended up back on the YouTube feed.
                    DebugLog.log(DebugLog.Stage.SCREEN, "$position already in ${step.app} — not relaunching")
                    handler.postDelayed({ runStep(steps, index + 1, target, token, onDone) }, STEP_MS)
                } else {
                    val pkg = AppLauncher.launch(this, step.app)
                    // Give the app a moment to come to the foreground before the next step.
                    handler.postDelayed({ runStep(steps, index + 1, pkg, token, onDone) }, APP_OPEN_MS)
                }
            }
            is ScreenStep.Tap ->
                awaitApp(expectedPackage, step.label, 0) { ok ->
                    if (ok) advance(expectedPackage) else failed("no control matching \"${step.label}\"")
                }
            is ScreenStep.Type ->
                typeWhenReady(step.text, 0) { ok ->
                    if (ok) advance(expectedPackage) else failed("no editable field appeared")
                }
            is ScreenStep.Back ->
                // The real system Back, not a hunt for a control labelled "Back".
                // Chat screens often have no such label, or several.
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    advance(expectedPackage)
                } else {
                    failed("the system refused the back action")
                }
            is ScreenStep.Home ->
                if (performGlobalAction(GLOBAL_ACTION_HOME)) {
                    // Home leaves the app entirely, so stop expecting the old one.
                    handler.postDelayed({ runStep(steps, index + 1, null, token, onDone) }, STEP_SETTLE_MS)
                } else {
                    failed("the system refused the home action")
                }
            is ScreenStep.Pick -> {
                // The one step that looks before it decides. Everything else was
                // committed to a label when the plan was written, before the
                // target screen existed.
                val options = tappableLabels()
                val resolver = onPick
                when {
                    options.isEmpty() -> failed("nothing tappable on screen to choose from")
                    resolver == null -> failed("no way to ask the model which one")
                    else -> {
                        DebugLog.log(
                            DebugLog.Stage.SCREEN,
                            "choosing \"${step.description}\" from ${options.size} on-screen options",
                        )
                        resolver(step.description, options) { choice ->
                            if (token != runToken) return@resolver
                            val label = choice?.let { options.getOrNull(it - 1) }
                            if (label == null) {
                                failed("could not match \"${step.description}\" to anything on screen")
                            } else {
                                DebugLog.log(DebugLog.Stage.SCREEN, "chose \"$label\"")
                                // Re-find by label instead of holding the node: the
                                // model round-trip takes about a second and node
                                // handles go stale.
                                seek(label, 0) { ok ->
                                    if (ok) advance(expectedPackage) else failed("tap on \"$label\" did not register")
                                }
                            }
                        }
                    }
                }
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

    /**
     * A compact description of what is actually on screen right now, for the AI's
     * context. This is what stops it guessing: instead of inventing a label and
     * hoping, it can name something that is really there — and it can tell that
     * the app is already open and it is already in the right place, so it stops
     * replaying steps it has already done.
     *
     * Clickable things are shown in [brackets], text fields as field:"contents".
     * Password fields and anything OTP-shaped are redacted before this leaves the
     * device.
     */
    fun describeScreen(): String {
        val root = userAppRoot()
        if (root != null) {
            val text = renderScreen(root)
            if (text.isNotEmpty()) {
                lastAppPackage = root.packageName?.toString()
                lastAppScreen = text
                lastAppSeenAt = System.currentTimeMillis()
                return text
            }
        }
        // JARVIS's own window is in front, so the user's app cannot be read right
        // now. Report what they were actually looking at rather than describing
        // JARVIS's own UI, which would make the model think it is inside JARVIS.
        val remembered = lastAppScreen
        if (remembered.isNotEmpty()) {
            val secondsAgo = (System.currentTimeMillis() - lastAppSeenAt) / 1000
            return "JARVIS is in front, so the screen cannot be read live. The user was last in " +
                "${lastAppPackage.orEmpty()} (${secondsAgo}s ago) and it looked like this — assume " +
                "it is still there unless the user says otherwise: $remembered"
        }
        lastAppPackage?.let {
            return "JARVIS is in front. The user was last in $it, but its contents cannot be read from here."
        }
        return ""
    }

    /**
     * The front-most window that belongs to some app other than JARVIS. Falls back
     * to scanning all interactive windows, so a session running behind JARVIS can
     * still be described.
     */
    private fun userAppRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() != packageName) return it }
        return try {
            windows
                .asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() != packageName }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The labels of everything tappable on screen, in reading order — the menu a
     * [ScreenStep.Pick] chooses from. Deduplicated, because a list row often
     * exposes the same text on several nested nodes.
     */
    private fun tappableLabels(): List<String> {
        val root = userAppRoot() ?: return emptyList()
        val labels = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < SCREEN_SCAN_NODES && labels.size < PICK_MAX_OPTIONS) {
            val node = queue.removeFirst()
            scanned++
            val raw = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            val tappable = node.isClickable || clickableSelfOrAncestor(node) != null
            if (tappable && raw.isNotEmpty() && raw.length <= SCREEN_LABEL_MAX && node.isVisibleToUser) {
                labels.add(redactSensitive(raw, node.isPassword))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return labels.toList()
    }

    private fun renderScreen(root: AccessibilityNodeInfo): String {
        val app = root.packageName?.toString().orEmpty()
        val items = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < SCREEN_SCAN_NODES && items.size < SCREEN_MAX_ITEMS) {
            val node = queue.removeFirst()
            scanned++
            val raw = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (raw.isNotEmpty() && raw.length <= SCREEN_LABEL_MAX && node.isVisibleToUser) {
                val label = redactSensitive(raw, node.isPassword)
                items.add(
                    when {
                        node.isEditable -> "field:\"$label\""
                        node.isClickable || clickableSelfOrAncestor(node) != null -> "[$label]"
                        else -> label
                    },
                )
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (items.isEmpty()) return ""
        // Deliberately not "foreground app": this same text is reused later to
        // describe the app the user was in, when JARVIS itself is in front.
        return "App: $app. On screen: ${items.joinToString(" ")}"
    }

    /**
     * Screen text goes to a third-party model, so credentials must never travel
     * with it. Password fields are replaced wholesale, and bare 4-8 digit runs
     * (one-time codes) are masked wherever they appear.
     */
    private fun redactSensitive(text: String, isPassword: Boolean): String {
        if (isPassword) return "***"
        return text.replace(OTP_LIKE, "***")
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

    /**
     * Wait for an editable field, then set its text — and if none appears, open
     * one instead of failing.
     *
     * Typing used to assume an earlier step had already opened a text field. That
     * assumption held only by luck: relaunching an app reset it to its home
     * screen, where a "Search" button exists. Once we stopped relaunching an app
     * already in front (correctly — it threw away the user's screen), the search
     * button was no longer there, `<<TAP|Search>>` hit nothing, and every type
     * failed with "no editable field appeared". A step should not depend on a
     * side effect of an unrelated one, so this now recovers on its own.
     */
    private fun typeWhenReady(text: String, tries: Int, onDone: (Boolean) -> Unit) {
        val field = rootInActiveWindow?.let { findEditable(it) }
        if (field != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            onDone(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            return
        }
        // Recovery attempts are spaced out and work through the candidates in
        // turn. One attempt is not enough: the earlier <<TAP|Search>> often
        // "succeeded" on the wrong node, so re-tapping the same one changes
        // nothing — the next candidate has to get a turn.
        val sinceRecovery = tries - FIELD_RECOVERY_AT
        if (sinceRecovery >= 0 && sinceRecovery % RECOVERY_EVERY == 0) {
            val hint = sinceRecovery / RECOVERY_EVERY
            if (hint < TEXT_ENTRY_HINTS.size && openTextEntry(TEXT_ENTRY_HINTS[hint])) {
                handler.postDelayed({ typeWhenReady(text, tries + 1, onDone) }, STEP_SETTLE_MS)
                return
            }
        }
        if (tries < FIELD_WAIT_TRIES) {
            handler.postDelayed({ typeWhenReady(text, tries + 1, onDone) }, STEP_MS)
        } else {
            // Say WHY, not just that it failed. The last trace showed a focused
            // field with the keyboard up and still reported "no editable field",
            // which left nothing to diagnose from and cost a round trip.
            DebugLog.log(DebugLog.Stage.SCREEN, "no field found — ${fieldHunt()}")
            onDone(false)
        }
    }

    /** A one-line account of what the field hunt actually saw. */
    private fun fieldHunt(): String {
        val root = rootInActiveWindow ?: return "no active window at all"
        val wins = try {
            windows.orEmpty()
        } catch (e: Exception) {
            emptyList<AccessibilityWindowInfo>()
        }
        val kinds = wins.joinToString("/") { w ->
            when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
                AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                else -> "w${w.type}"
            }
        }.ifEmpty { "none listed" }
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val focusDesc = focus?.let {
            "focus=${it.className ?: "?"} editable=${it.isEditable} " +
                "setText=${it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SET_TEXT }}"
        } ?: "no input focus"
        return "active=${root.packageName ?: "?"} windows=[$kinds] $focusDesc"
    }

    /**
     * Nothing is typeable, so try to make something typeable: tap whatever most
     * looks like a way into text entry. Apps past their home screen usually still
     * show the query bar or a search affordance somewhere.
     */
    private fun openTextEntry(candidate: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val (node, score) = bestMatch(root, candidate)
        if (node == null || score <= 0) return false
        DebugLog.log(DebugLog.Stage.SCREEN, "no text field yet — tapping \"$candidate\" to open one")
        return tapNode(node)
    }

    /** Press the IME action (search / go / enter) on the focused field. */
    private fun pressImeAction(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val field = rootInActiveWindow?.let { findEditable(it) } ?: return false
        return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** The focused input field, or the first editable field on screen. */
    /**
     * Finds a text field to type into, across every application window.
     *
     * A device trace has Blinkit's search screen open, the field focused with a
     * visible caret and the keyboard up — and typing still failed with "no
     * editable field appeared". Two things were wrong here.
     *
     * It only looked at [rootInActiveWindow]. Once the keyboard is showing, the
     * active window can be the IME rather than the app, and the IME has keys in
     * it, not text fields. So every application window is searched now, and the
     * input-method window is skipped explicitly.
     *
     * And it required `isEditable`, which not every app sets. A node that accepts
     * ACTION_SET_TEXT is a field whatever it calls itself, so that counts too.
     */
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        rootsToSearch(root).forEach { start ->
            start.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (acceptsText(it)) return it }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(start)
            var seen = 0
            while (queue.isNotEmpty() && seen < NODE_SCAN_LIMIT) {
                val node = queue.removeFirst()
                seen++
                if (acceptsText(node)) return node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    /**
     * Every window worth searching for a field, the active one first.
     *
     * `windows` needs FLAG_RETRIEVE_INTERACTIVE_WINDOWS and can be empty; the
     * active root is always included so this degrades to the old behaviour rather
     * than finding nothing.
     */
    private fun rootsToSearch(active: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val roots = mutableListOf(active)
        val others = try {
            windows.orEmpty()
        } catch (e: Exception) {
            emptyList<AccessibilityWindowInfo>()
        }
        for (w in others) {
            // The keyboard has keys, not fields, and searching it wastes the budget.
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            w.root?.let { if (it != active) roots.add(it) }
        }
        return roots
    }

    /** A field, whether or not the app bothered to say so. */
    private fun acceptsText(node: AccessibilityNodeInfo): Boolean =
        node.isEditable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } ||
            node.className?.toString()?.contains("EditText") == true

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
            onDone(tapNode(node))
            return
        }
        if (scrolls < MAX_SCROLLS && scrollForward(root)) {
            handler.postDelayed({ seek(label, scrolls + 1, onDone) }, SCROLL_SETTLE_MS)
            return
        }
        // Nothing confident left: take the best weak match if there is one, but
        // report honestly whether tapping it did anything.
        onDone(node != null && tapNode(node))
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

    /**
     * Returns whether the tap actually happened. This used to return Unit and the
     * caller always reported success — so a tap that silently did nothing was
     * announced as "Playing the Thriller video", and repeating the command
     * repeated the same non-event.
     */
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Prefer a real click on the node; fall back to a tap gesture at its centre.
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            showOutline(bounds)
            return true
        }
        // A node with no on-screen bounds cannot be tapped by gesture — its centre
        // is (0,0), which lands somewhere else entirely.
        if (bounds.isEmpty) return false
        showOutline(bounds)
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
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

    /**
     * Show or hide the translucent "JARVIS is in control" tint over the whole
     * screen. Called by the engine when an on-screen errand begins and ends. Safe
     * to call from any thread and idempotent — a second `true` does not stack a
     * second layer. A safety timer removes it if the engine ever forgets, so a
     * stuck errand cannot leave the screen dimmed for good.
     */
    fun setControlOverlay(active: Boolean) {
        handler.post {
            handler.removeCallbacks(scrimSafety)
            if (active) {
                addScrim()
                handler.postDelayed(scrimSafety, SCRIM_SAFETY_MS)
            } else {
                removeScrim()
            }
        }
    }

    private fun addScrim() {
        if (scrim != null) return
        val view = ScrimView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not touchable and not focusable: the tint must never swallow a tap —
            // JARVIS's own gestures still have to reach the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(view, params)
            scrim = view
        } catch (e: Exception) {
            scrim = null
        }
    }

    private fun removeScrim() {
        scrim?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        scrim = null
    }

    /**
     * Full-screen translucent tint plus a cyan edge frame and a small label, so
     * the screen visibly "belongs to JARVIS" while it acts. Deliberately see-
     * through — the user still needs to watch what it is doing.
     */
    private class ScrimView(context: Context) : View(context) {
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = Color.parseColor("#00D4FF")
        }
        private val frameGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 24f
            color = Color.parseColor("#3300D4FF")
        }
        private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E60A1426")
        }
        private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00D4FF")
            textSize = 34f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // Translucent navy wash — low enough alpha that the app stays readable.
            canvas.drawColor(Color.parseColor("#59060D1C"))
            val edge = RectF(6f, 6f, w - 6f, h - 6f)
            canvas.drawRoundRect(edge, 44f, 44f, frameGlow)
            canvas.drawRoundRect(edge, 44f, 44f, frame)
            // A small pill near the top so the dimming reads as intentional.
            val label = "JARVIS is controlling the screen"
            val tw = pillText.measureText(label)
            val cx = w / 2f
            val top = 70f
            val ph = 82f
            val padX = 44f
            val pill = RectF(cx - tw / 2 - padX, top, cx + tw / 2 + padX, top + ph)
            canvas.drawRoundRect(pill, ph / 2, ph / 2, pillFill)
            canvas.drawRoundRect(pill, ph / 2, ph / 2, frame)
            val baseline = top + ph / 2 - (pillText.descent() + pillText.ascent()) / 2
            canvas.drawText(label, cx, baseline, pillText)
        }
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

        /** Bounds the field hunt per window, so a deep tree cannot stall a step. */
        private const val NODE_SCAN_LIMIT = 600
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

        /** Bounds on the screen description sent to the AI (tokens cost money and latency). */
        private const val SCREEN_SCAN_NODES = 400
        private const val SCREEN_MAX_ITEMS = 45
        private const val SCREEN_LABEL_MAX = 60

        /** Bare 4-8 digit runs — one-time codes — are masked before leaving the device. */
        private val OTP_LIKE = Regex("""\b\d{4,8}\b""")

        /** How many on-screen options a <<PICK>> may choose between. */
        private const val PICK_MAX_OPTIONS = 25

        /**
         * After this many fruitless polls, stop waiting and try to open a text
         * field. Early enough to save the step, late enough that a field which is
         * merely slow to appear is not interrupted.
         */
        private const val FIELD_RECOVERY_AT = 5

        /** Polls between recovery attempts, so each candidate gets time to work. */
        private const val RECOVERY_EVERY = 3

        /** Tried in order when nothing on screen is typeable. */
        private val TEXT_ENTRY_HINTS = listOf("Search", "Search YouTube", "Message", "Type a message")

        /**
         * Asks the model which of the on-screen options matches a description,
         * calling back with a 1-based index, or null when nothing matches. Set by
         * the engine, which owns the AI clients.
         */
        var onPick: ((String, List<String>, (Int?) -> Unit) -> Unit)? = null

        /**
         * Called when a step fails: (failed step, why, what is on screen now) and
         * a callback taking the replacement steps. The engine answers it by asking
         * the model to look at the screen and work out another way.
         */
        var onRecover: ((ScreenStep, String, String, (List<ScreenStep>) -> Unit) -> Unit)? = null

        /** How many times one command may re-plan before giving up. */
        /**
         * How many times a sequence may hand back to the agent loop.
         *
         * Was 2, when recovery meant "re-plan the rest blind and hope". The loop
         * takes ONE step per callback, so this is now the loop's step budget —
         * the engine stops it earlier via AgentLoop.MAX_STEPS, and this is the
         * backstop in case that is ever bypassed.
         */
        private const val MAX_RECOVERIES = 12
        private const val OUTLINE_MS = 1100L

        /**
         * Backstop that removes the control tint if the engine never clears it.
         * Re-armed on every step, so it only fires when the screen genuinely
         * stops changing — long enough that a slow-but-live errand keeps its tint.
         */
        private const val SCRIM_SAFETY_MS = 30_000L
    }
}
