package com.jarvis.os.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.jarvis.os.ai.GroqClient
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.UsageClient
import com.jarvis.os.ai.UsageStats
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.data.formatMemory
import com.jarvis.os.debug.DebugLog
import com.jarvis.os.desktop.brain.Brain
import com.jarvis.os.desktop.brain.LegacyImport
import com.jarvis.os.desktop.agent.ActionCard
import com.jarvis.os.desktop.agent.AgentClient
import com.jarvis.os.desktop.agent.AgentLoop
import com.jarvis.os.desktop.agent.ToolBox
import com.jarvis.os.desktop.agent.WindowsHost
import com.jarvis.os.desktop.knowledge.KnowledgeClient
import com.jarvis.os.desktop.knowledge.Library
import com.jarvis.os.desktop.knowledge.ScreenGrab
import com.jarvis.os.desktop.voice.MicRecorder
import com.jarvis.os.desktop.voice.Speaker
import com.jarvis.os.desktop.voice.TranscribeClient
import com.jarvis.os.desktop.voice.WakeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The desktop's assistant: conversations as Compose state, each turn sent through the
 * SAME Worker client the phone uses, the reply applied via the pure [DesktopTurn].
 * The Worker is preferred (it holds the model key and meters the account); a direct
 * Groq key is the dev-only fallback, as on the phone.
 *
 * `activeId == null` is a fresh, not-yet-saved chat: it becomes a conversation on its
 * first message, so empty chats never litter the sidebar.
 */
class DesktopAssistant(
    private val scope: CoroutineScope,
    /** The brain (AGENT_PLAN §3). Tests pass an in-memory one. */
    val brain: Brain = openBrain(),
) {
    var conversations by mutableStateOf(brain.conversations())
        private set
    var activeId by mutableStateOf(conversations.firstOrNull()?.id)
        private set
    /** The open conversation's messages, as the chat shows them (incl. agent step cards). */
    var turns by mutableStateOf(loadTurns(activeId))
        private set
    /** Everything remembered, typed and sourced. */
    var memories by mutableStateOf(brain.memories())
        private set
    /** Open tasks (the Tasks screen and Today read these). */
    var openTasks by mutableStateOf(brain.openTasks())
        private set

    /** The remembered facts as plain text — what rides on each request as context. */
    val facts: List<String> get() = memories.map { it.text }
    /** The conversation a reply is being written for, or null when idle. */
    var thinkingIn by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var usage by mutableStateOf<UsageStats.Daily?>(null)
        private set
    /** How long the last reply took, end to end (Worker round trip + model). Real, for the HUD. */
    var lastLatencyMs by mutableStateOf<Long?>(null)
        private set

    var messageCount by mutableStateOf(brain.messageCount())
        private set

    val active: Brain.Conversation? get() = conversations.firstOrNull { it.id == activeId }
    val thinking: Boolean get() = thinkingIn != null
    val thinkingHere: Boolean get() = thinkingIn != null && thinkingIn == activeId

    val configured: Boolean get() = ProxyClient.isConfigured() || GroqClient.hasKey()
    val plan: String get() = if (ProxyClient.isConfigured()) account.plan else "dev"

    // ── Voice (Phase 2) ──────────────────────────────────────────────────────
    enum class Voice { Idle, Listening, Transcribing, Speaking }

    private val recorder = MicRecorder()
    val speaker = Speaker()
    var voice by mutableStateOf(Voice.Idle)
        private set
    /** Mic loudness 0..1 while listening — shown so the user can SEE they're being heard. */
    var micLevel by mutableStateOf(0f)
        private set
    /** Speak every reply aloud, not only answers to spoken questions. */
    var speakAllReplies by mutableStateOf(false)

    // ── Wake word (Phase 2.2) ────────────────────────────────────────────────
    private val wake = WakeListener(scope)
    /** The user's choice. Off by default: an always-open mic is theirs to turn on. */
    var wakeWordOn by mutableStateOf(false)
    /** True while the background listener actually holds the mic. */
    var wakeListening by mutableStateOf(false)
        private set

    /**
     * Keeps the wake listener in step with everything else: armed only when the user
     * wants it AND nothing else needs the mic or the speaker — not while recording, not
     * while transcribing or thinking, and not while JARVIS talks (its own voice saying
     * "Jarvis" must not wake it). Called whenever any of those change.
     */
    fun syncWake() {
        val shouldListen = wakeWordOn && voice == Voice.Idle && !thinking && ProxyClient.isConfigured()
        if (shouldListen && !wake.armed) {
            wake.arm(
                onWake = { scope.launch { wakeListening = false; toggleMic() } },
                onError = { msg -> scope.launch { wakeListening = false; wakeWordOn = false; error = msg } },
            )
            wakeListening = true
        } else if (!shouldListen && wake.armed) {
            wake.disarm()
            wakeListening = false
        }
    }

    fun shutdownVoice() {
        wake.shutdown()
        speaker.shutdown()
    }

    /**
     * The mic button. Idle → listen; listening → finish now; speaking → cut JARVIS off
     * and listen (the phone's barge-in, as a click). One owner of the mic, always.
     */
    fun toggleMic() {
        when (voice) {
            Voice.Listening -> { recorder.stop(); return }
            Voice.Transcribing -> return
            Voice.Speaking -> speaker.stop()
            Voice.Idle -> Unit
        }
        if (thinking || !ProxyClient.isConfigured()) {
            if (!ProxyClient.isConfigured()) error = "Voice needs the JARVIS server — see Settings."
            return
        }
        error = null
        // The recorder must be the ONLY mic owner: release the wake listener first.
        wake.disarm()
        wakeListening = false
        voice = Voice.Listening
        scope.launch {
            try {
                val wav = recorder.record { micLevel = it }
                if (wav == null) {
                    error = "I didn't catch anything — click the mic and speak."
                    return@launch
                }
                voice = Voice.Transcribing
                val text = TranscribeClient.transcribe(wav)
                if (text.isBlank()) {
                    error = "I heard sound but no words — try again."
                    return@launch
                }
                voice = Voice.Idle
                send(text, spoken = true)
            } catch (e: Exception) {
                DebugLog.log(DebugLog.Stage.ERROR, "desktop voice failed: ${e.javaClass.simpleName}")
                error = e.message ?: "Voice failed — try again."
            } finally {
                if (voice == Voice.Listening || voice == Voice.Transcribing) voice = Voice.Idle
                micLevel = 0f
            }
        }
    }

    /** Speaks a line that isn't a reply (a reminder). */
    fun speakText(text: String) = speakReply(text)

    private fun speakReply(text: String) {
        if (!speaker.available) return
        scope.launch {
            voice = Voice.Speaking
            try { speaker.speak(text) } finally { if (voice == Voice.Speaking) voice = Voice.Idle }
        }
    }

    fun newChat() {
        activeId = null
        turns = emptyList()
        error = null
    }

    fun select(id: String) {
        activeId = id
        turns = loadTurns(id)
        error = null
    }

    fun delete(id: String) {
        if (thinkingIn == id) return
        brain.deleteConversation(id)
        if (activeId == id) newChat()
        refreshConversations()
    }

    fun setPinned(id: String, pinned: Boolean) { brain.setPinned(id, pinned); refreshConversations() }
    fun setArchived(id: String, archived: Boolean) {
        brain.setArchived(id, archived)
        if (archived && activeId == id) newChat()
        refreshConversations()
    }

    // ── Projects ─────────────────────────────────────────────────────────────
    var projects by mutableStateOf(brain.projects())
        private set

    fun createProject(name: String): Brain.Project? =
        runCatching { brain.createProject(name) }.getOrNull()?.also { projects = brain.projects() }

    fun moveToProject(conversationId: String, projectId: String?) { brain.moveToProject(conversationId, projectId); refreshConversations() }
    fun deleteProject(id: String) { brain.deleteProject(id); projects = brain.projects(); refreshConversations() }

    // ── Tasks ────────────────────────────────────────────────────────────────
    var doneTasks by mutableStateOf(brain.doneTasks(20))
        private set

    fun addTask(title: String, dueAt: Long? = null, sourceConversation: String? = null): Brain.Task? =
        runCatching { brain.addTask(title, dueAt = dueAt, sourceConversation = sourceConversation) }.getOrNull()
            ?.also { brain.log("task", "Added task: ${it.title}"); refreshTasks() }

    fun setTaskDone(id: String, done: Boolean) {
        brain.setTaskDone(id, done)
        brain.task(id)?.let { brain.log("task", (if (done) "Completed: " else "Reopened: ") + it.title) }
        refreshTasks()
    }

    fun deleteTask(id: String) { brain.deleteTask(id); refreshTasks() }

    /** Open tasks due before the end of today (including overdue). */
    fun tasksDueToday(): List<Brain.Task> = brain.tasksDueBy(endOfToday())

    // ── Search + activity ────────────────────────────────────────────────────
    fun search(query: String): List<Brain.SearchHit> = brain.search(query)
    fun activity(): List<Brain.Activity> = brain.activity()

    /** Archived chats — folded away in the sidebar, never deleted. */
    var archived by mutableStateOf(brain.conversations(includeArchived = true).filter { it.archived })
        private set

    fun renameProject(id: String, name: String) { brain.renameProject(id, name); projects = brain.projects() }

    private fun refreshConversations() {
        conversations = brain.conversations()
        archived = brain.conversations(includeArchived = true).filter { it.archived }
        messageCount = brain.messageCount()
    }

    private fun refreshTasks() {
        openTasks = brain.openTasks()
        doneTasks = brain.doneTasks(20)
    }

    private fun refreshMemories() { memories = brain.memories() }
    fun refreshReminders() { upcomingReminders = brain.upcomingReminders() }

    private fun loadTurns(id: String?): List<Brain.Message> = if (id == null) emptyList() else brain.messages(id)

    /** What the MODEL sees: only the words — step cards are for the user, the tools report to the model in-turn. */
    private fun modelHistory(id: String): List<ChatTurn> =
        brain.messages(id).filter { it.role == ChatTurn.USER || it.role == ChatTurn.ASSISTANT }.map { ChatTurn(it.role, it.content) }

    // ── The agent (Phase 4) ─────────────────────────────────────────────────
    // ── Google Calendar + Gmail (Phase 6) ───────────────────────────────────
    private val googleAccount = com.jarvis.os.desktop.google.GoogleAccount()
    private val googleApis = com.jarvis.os.desktop.google.GoogleApis(googleAccount)
    /** The connected Google account's email, or null. */
    var googleEmail by mutableStateOf(runCatching { googleAccount.email ?: if (googleAccount.connected) "connected" else null }.getOrNull())
        private set
    var googleBusy by mutableStateOf(false)
        private set
    var googleError by mutableStateOf<String?>(null)
        private set

    /**
     * Settings → Connect: Google's consent page for Calendar + Gmail. The same Google login
     * also links this laptop's JARVIS account if it's still a guest (one sign-in, not two).
     */
    fun connectGoogle() {
        if (googleBusy) return
        googleBusy = true
        googleError = null
        scope.launch {
            try {
                val body = googleAccount.connect()
                googleEmail = googleAccount.email ?: "connected"
                brain.log("google", "Connected Google Calendar and Gmail" + (googleAccount.email?.let { " ($it)" } ?: ""))
                if (!account.isSignedIn) GoogleSignIn.idTokenFrom(body)?.let { runCatching { account = Identity.linkGoogle(it) } }
            } catch (e: Exception) {
                googleError = e.message ?: "Connecting Google failed — try again."
            } finally {
                googleBusy = false
            }
        }
    }

    fun disconnectGoogle() {
        scope.launch {
            runCatching { googleAccount.disconnect() }
            googleEmail = null
            brain.log("google", "Disconnected Google Calendar and Gmail")
        }
    }

    // ── Permissions (off by default — the user's own laptop, the user's own call) ──────

    /**
     * Laptop-file access: search_files, open_file, and reading a NEW document by path.
     * OFF by default — JARVIS starts with no reach onto the laptop's files at all; a file
     * the user attaches themselves (the paperclip, drag-and-drop) always works regardless,
     * since that is the user choosing to share it, not JARVIS reaching for it.
     */
    var filesAllowed by mutableStateOf(false)

    private val toolBox = ToolBox(
        brain, WindowsHost(context = { "Current date/time: ${nowLine()}." }, captureScreen = { captureScreenJpeg() }),
        google = googleApis,
        filesAllowed = { filesAllowed },
    )

    /** A step waiting for the user's click (Rule 6: never by prompt): what it does, and what approving means. */
    class Approval(val description: String, val note: String, internal val answer: kotlinx.coroutines.CompletableDeferred<Boolean>)
    var pendingApproval by mutableStateOf<Approval?>(null)
        private set

    fun resolveApproval(approved: Boolean) {
        pendingApproval?.answer?.complete(approved)
        pendingApproval = null
    }

    /** Undo button on a step card. */
    fun undoAction(messageId: String) {
        val msg = turns.firstOrNull { it.id == messageId } ?: return
        val card = ActionCard.decode(msg.content) ?: return
        if (card.undone || card.undo == null) return
        // Some undos live in Google (an event, a draft), so this can take a network round trip.
        scope.launch {
            val line = toolBox.undoAsync(card.undo) ?: return@launch
            brain.updateMessage(messageId, card.copy(undone = true, summary = card.summary + " — undone ($line)").encode())
            activeId?.let { turns = loadTurns(it) }
            refreshTasks(); refreshMemories(); refreshRoutines(); refreshReminders()
        }
    }

    // ── Knowledge (Phase 5) ─────────────────────────────────────────────────

    /** The app window, as far as a screenshot needs it: out of the way for the moment of the shot. */
    interface WindowControl {
        val visible: Boolean
        fun hide()
        fun show()
    }
    var windowControl: WindowControl? = null

    /** Every document JARVIS has been given (the Files screen). */
    var documents by mutableStateOf(brain.documents())
        private set
    /** Files waiting in the composer, sent with the next message. */
    var pendingDocs by mutableStateOf<List<Brain.Document>>(emptyList())
        private set
    /** How many files are still being read in. */
    var importing by mutableStateOf(0)
        private set
    /** A screenshot waiting in the composer (JPEG, memory only), and its preview. */
    var pendingShot by mutableStateOf<ByteArray?>(null)
        private set
    var pendingShotPreview by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set
    var capturing by mutableStateOf(false)
        private set

    /** Reads files in (locally) and puts them in the composer. Unreadable ones say why. */
    fun attach(files: List<java.io.File>) {
        files.filter { it.isFile }.forEach { file ->
            importing++
            scope.launch {
                try {
                    val d = Library.import(brain, file, conversationId = null)
                    if (pendingDocs.none { it.id == d.id }) pendingDocs = pendingDocs + d
                    documents = brain.documents()
                } catch (e: IllegalArgumentException) {
                    error = e.message
                } catch (e: Exception) {
                    DebugLog.log(DebugLog.Stage.ERROR, "attach failed: ${e.javaClass.simpleName}")
                    error = "I couldn't read “${file.name}”."
                } finally {
                    importing--
                }
            }
        }
    }

    fun removePending(id: String) { pendingDocs = pendingDocs.filterNot { it.id == id } }

    fun deleteDocument(id: String) {
        brain.document(id)?.let { brain.log("document", "Removed document: ${it.name}") }
        brain.deleteDocument(id)
        pendingDocs = pendingDocs.filterNot { it.id == id }
        documents = brain.documents()
    }

    /** Opens a chat about one document, ready for a question. */
    fun askAbout(d: Brain.Document) {
        newChat()
        pendingDocs = listOf(d)
    }

    /** The composer's screen button: one shot of the screen the user is on, shown before it's sent. */
    fun captureForQuestion() {
        if (capturing) return
        capturing = true
        scope.launch {
            try {
                val jpeg = captureScreenJpeg()
                pendingShot = jpeg
                pendingShotPreview = withContext(Dispatchers.IO) { org.jetbrains.skia.Image.makeFromEncoded(jpeg).toComposeImageBitmap() }
            } catch (e: Exception) {
                DebugLog.log(DebugLog.Stage.ERROR, "screenshot failed: ${e.javaClass.simpleName}")
                error = "I couldn't take a screenshot."
            } finally {
                capturing = false
            }
        }
    }

    fun discardShot() { pendingShot = null; pendingShotPreview = null }

    /** Hides JARVIS for a moment so the shot shows what's behind it, then brings it back. */
    private suspend fun captureScreenJpeg(): ByteArray {
        val w = windowControl
        val wasVisible = w?.visible == true
        if (wasVisible) { w!!.hide(); kotlinx.coroutines.delay(450) }
        try {
            return withContext(Dispatchers.IO) { ScreenGrab.jpeg(ScreenGrab.capture()) }
        } finally {
            if (wasVisible) w!!.show()
        }
    }

    // ── Routines (Phase 6, S9) ──────────────────────────────────────────────

    var routines by mutableStateOf(brain.routines())
        private set
    fun refreshRoutines() { routines = brain.routines() }

    /** What a routine produced, for the notification (and speech) the app shows. */
    class RoutineRun(val routine: Brain.Routine, val text: String, val late: Boolean, val ok: Boolean, val conversationId: String)

    /**
     * Runs every routine whose time has come, one after another. Waits while the user is
     * mid-turn (the next 15-second tick picks it up). A run missed by hours (laptop off) is
     * skipped and logged, never fired hours late; a run a little late says so.
     */
    suspend fun runDueRoutines(): List<RoutineRun> {
        if (thinking || !ProxyClient.isConfigured()) return emptyList()
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val out = mutableListOf<RoutineRun>()
        for (r in brain.dueRoutines(now)) {
            when (com.jarvis.os.desktop.brain.Schedule.due(r.nextRun, now)) {
                com.jarvis.os.desktop.brain.Schedule.Due.SKIP -> {
                    brain.markRoutine(r.id, null, r.schedule.next(now, zone))
                    brain.log("routine", "Skipped “${r.name}”: it came due while JARVIS was off")
                }
                com.jarvis.os.desktop.brain.Schedule.Due.RUN -> out += runRoutine(r, late = false)
                com.jarvis.os.desktop.brain.Schedule.Due.RUN_LATE -> out += runRoutine(r, late = true)
            }
        }
        refreshRoutines()
        return out
    }

    /** The Scheduled screen's "Run now": runs it and opens the chat it lands in. */
    fun runRoutineNow(id: String, onDone: (RoutineRun) -> Unit = {}) {
        val r = brain.routine(id) ?: return
        if (thinking) return
        scope.launch {
            val run = runRoutine(r, late = false, manual = true)
            select(run.conversationId)
            onDone(run)
        }
    }

    fun setRoutineEnabled(id: String, on: Boolean) {
        val r = brain.routine(id) ?: return
        // Turning one back on starts from the next future slot, never a backlog.
        brain.setRoutineEnabled(id, on, if (on) r.schedule.next(System.currentTimeMillis(), java.time.ZoneId.systemDefault()) else null)
        refreshRoutines()
    }
    fun setRoutineSpeak(id: String, on: Boolean) { brain.setRoutineSpeak(id, on); refreshRoutines() }
    fun deleteRoutine(id: String) {
        brain.routine(id)?.let { brain.log("routine", "Routine removed: ${it.name}") }
        brain.setRoutineDeleted(id, true)
        refreshRoutines()
    }
    fun deleteReminder(id: String) { brain.deleteReminder(id); brain.log("reminder", "Reminder cancelled"); refreshReminders() }

    /**
     * One routine run: its own conversation ("Morning brief · Tue 29 Sep"), the agent with
     * every tool, and NO approvals: nobody is watching, so anything irreversible or that
     * shares something is declined in code and the model is told so.
     */
    private suspend fun runRoutine(r: Brain.Routine, late: Boolean, manual: Boolean = false): RoutineRun {
        val zone = java.time.ZoneId.systemDefault()
        val started = System.currentTimeMillis()
        val day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        val conv = brain.createConversation("${r.name} · $day")
        brain.addMessage(conv.id, ChatTurn.USER, "${DesktopTurn.ROUTINE_MARK} ${r.name}\n${r.instruction}")
        thinkingIn = conv.id
        refreshConversations()
        val (text, ok) = try {
            val context = DesktopTurn.context(nowLine(), formatMemory("", facts), today = java.time.LocalDate.now(), google = googleEmail) + "\n\n" +
                DesktopTurn.routineNote(r.name, manual)
            val raw = AgentLoop(
                toolBox,
                step = { m, c, t -> AgentClient.step(m, c, t) },
                approve = { ask -> brain.log("routine", "Declined in “${r.name}” (nobody to approve): ${ask.description}"); false },
                onStep = { _, result -> brain.addMessage(conv.id, ActionCard.ROLE, ActionCard(result.summary, result.ok, result.undo).encode()) },
            ).run(listOf(ChatTurn(ChatTurn.USER, r.instruction)), context, sourceConversation = conv.id)
            DesktopTurn.process(raw).text.ifBlank { "…" } to true
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "routine failed: ${e.javaClass.simpleName}")
            "I couldn't run “${r.name}”: ${e.message ?: "something went wrong"}" to false
        } finally {
            thinkingIn = null
        }
        brain.addMessage(conv.id, ChatTurn.ASSISTANT, text)
        if (!manual) brain.markRoutine(r.id, started, r.schedule.next(started, zone))
        brain.log("routine", (if (ok) "Ran “" else "Failed “") + r.name + "”" + if (late) " (late)" else "")
        if (activeId == conv.id) turns = loadTurns(conv.id)
        refreshConversations(); refreshTasks(); refreshMemories(); refreshReminders(); refreshRoutines()
        usage = UsageStats.today()
        return RoutineRun(r, text, late, ok, conv.id)
    }

    fun dueReminders(): List<Brain.Reminder> = brain.dueReminders()
    fun markReminderDelivered(r: Brain.Reminder) {
        brain.markDelivered(r.id)
        brain.log("reminder", "Reminded: ${r.text}")
    }
    var upcomingReminders by mutableStateOf(brain.upcomingReminders())
        private set

    // ── Account ──────────────────────────────────────────────────────────────
    var account by mutableStateOf(Identity.account())
        private set
    var signingIn by mutableStateOf(false)
        private set
    var signInError by mutableStateOf<String?>(null)
        private set

    /** Opens the browser for Google sign-in and links this laptop to that account. */
    fun signInWithGoogle() {
        if (signingIn) return
        signingIn = true
        signInError = null
        scope.launch {
            try {
                val googleToken = GoogleSignIn.signIn()
                account = Identity.linkGoogle(googleToken)
                usage = null
                refreshPlan()
            } catch (e: Exception) {
                DebugLog.log(DebugLog.Stage.ERROR, "desktop sign-in failed: ${e.javaClass.simpleName}")
                signInError = e.message ?: "Sign-in failed — try again."
            } finally {
                signingIn = false
            }
        }
    }

    fun signOut() {
        Identity.signOut()
        account = Identity.account()
        usage = null
        refreshPlan()
    }

    /**
     * Asks the Worker for this account's plan and today's allowance (no tokens spent — see UsageClient), so the
     * sidebar is right straight after launch or sign-in instead of after the first reply. A failed call changes nothing.
     */
    fun refreshPlan() {
        scope.launch {
            // At launch the network, or the sign-in token, may not be ready yet: try again a few times, widening the gap,
            // so a Pro account is never left reading "Free" just because the first call lost a race.
            for (waitMs in REFRESH_RETRY_MS) {
                if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                if (UsageClient.refresh() != null) {
                    account = Identity.account()
                    usage = UsageStats.today()
                    return@launch
                }
            }
        }
    }

    // ── Sync (Phase 7, AGENT_PLAN §7) ────────────────────────────────────────

    /**
     * The user's switch — OFF by default (AGENT_PLAN §8 decision 4: opt-in, not automatic).
     * Only meaningful once signed in with Google: an anonymous account is a fresh identity
     * on every install, so there is no "other device" to share with until both devices are
     * signed in as the SAME account. [syncAvailable] is what Settings actually gates on.
     */
    var syncOn by mutableStateOf(false)
    val syncAvailable: Boolean get() = syncOn && account.isSignedIn && ProxyClient.isConfigured()

    var syncing by mutableStateOf(false)
        private set
    var lastSyncedAt by mutableStateOf<Long?>(null)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set

    private val syncClient = com.jarvis.os.desktop.sync.SyncClient(brain)

    /**
     * One push-then-pull round. Called from a slow periodic loop (Main.kt) — sync is
     * eventually-consistent by design, not a live channel, so once a minute is plenty and
     * keeps this off the critical path of everything else the laptop is doing. A network
     * hiccup is logged and swallowed, exactly like the reminders loop: one failed round
     * must never crash the loop that would otherwise fix itself next time.
     */
    suspend fun syncNow() {
        if (!syncAvailable || syncing) return
        syncing = true
        try {
            if (syncClient.syncOnce()) {
                refreshConversations(); refreshTasks(); refreshMemories(); refreshRoutines(); refreshReminders()
                documents = brain.documents()
            }
            lastSyncedAt = System.currentTimeMillis()
            syncError = null
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "sync failed: ${e.javaClass.simpleName}")
            syncError = e.message ?: "Sync failed — will try again."
        } finally {
            syncing = false
        }
    }

    /** Renames a conversation. A blank name is ignored (the old title stays). */
    fun rename(id: String, title: String) {
        val clean = DesktopTurn.cleanTitle(title) ?: return
        brain.renameConversation(id, clean)
        refreshConversations()
    }

    // ── Quick bar (Phase 6) ─────────────────────────────────────────────────

    /** The user's switch (Settings); on by default. */
    var quickBarOn by mutableStateOf(true)
    /** The key combination Windows gave the Quick bar, or null (off, or every choice was taken). */
    var quickKey by mutableStateOf<String?>(null)

    /** The Quick bar's current conversation (follow-ups continue it) and its latest answer. */
    private var quickConversationId: String? = null
    var quickAnswer by mutableStateOf<String?>(null)
        private set
    /** The error of the last quick question, if it failed. */
    var quickError by mutableStateOf<String?>(null)
        private set
    val quickThinking: Boolean get() = thinkingIn != null && thinkingIn == quickConversationId

    /** A fresh Quick bar session (the bar was just opened). */
    fun quickReset() { quickConversationId = null; quickAnswer = null; quickError = null }

    /** Asks from the Quick bar: a new chat the first time, then follow-ups in the same one. */
    fun quickAsk(text: String): Boolean {
        val conv = quickConversationId
        if (conv != null && brain.conversation(conv) != null) select(conv) else newChat()
        quickError = null
        val ok = send(text, extraContext = QUICK_NOTE)
        if (ok) quickConversationId = activeId
        return ok
    }

    /** Returns true if the message was accepted (so the input can be cleared). */
    fun send(input: String, spoken: Boolean = false, extraContext: String? = null): Boolean {
        val docs = pendingDocs
        val shot = pendingShot
        // A file or a screenshot with no words is still a question.
        val typed = input.trim().ifEmpty {
            when {
                shot != null -> "What's on my screen?"
                docs.size == 1 -> "Summarise ${docs[0].name}."
                docs.isNotEmpty() -> "Summarise these documents."
                else -> ""
            }
        }
        if (typed.isEmpty() || thinking || importing > 0) return false
        // What's attached rides on the message itself, so the chat (and the model) can see it.
        val message = (docs.map { "${DesktopTurn.DOC_MARK} ${it.name}" } + listOfNotNull(if (shot != null) DesktopTurn.SHOT_MARK + " Screenshot of my screen" else null) + typed)
            .joinToString("\n")
        // A new question cuts off whatever JARVIS was still saying.
        if (voice == Voice.Speaking) speaker.stop()
        if (!configured) {
            error = "Not connected to the JARVIS server — see Settings."
            return false
        }
        error = null
        val userTurn = ChatTurn(ChatTurn.USER, message)
        // A fresh chat becomes a conversation on its first message, titled from it.
        val id = activeId ?: brain.createConversation(DesktopTurn.titleFor(listOf(userTurn))).id
        brain.addMessage(id, ChatTurn.USER, message)
        docs.forEach { brain.attachDocument(id, it.id) }
        pendingDocs = emptyList()
        discardShot()
        activeId = id
        turns = loadTurns(id)
        refreshConversations()
        thinkingIn = id
        scope.launch {
            try {
                val history = modelHistory(id).takeLast(DesktopTurn.MAX_CONTEXT_TURNS)
                // Exact local time and zone: the agent turns "tomorrow at 5" into a real time.
                val attached = brain.attachedDocuments(id).map { "${it.name} (${it.pages} ${it.unit}${if (it.pages == 1) "" else "s"})" }
                val context = DesktopTurn.context(nowLine(), formatMemory("", facts), attached, java.time.LocalDate.now(), googleEmail) +
                    (extraContext?.let { "\n\n$it" } ?: "")
                val started = System.currentTimeMillis()
                val raw = if (shot != null && ProxyClient.isConfigured()) {
                    // The user took this screenshot and pressed send: that IS the approval.
                    // It goes to the vision model once, with the question, and isn't kept.
                    val answer = KnowledgeClient.askAboutImage(shot, typed, "Current date/time: ${nowLine()}.")
                    brain.log("screen", "Looked at the screen")
                    brain.addMessage(id, ActionCard.ROLE, ActionCard("Looked at your screen (the screenshot wasn't kept)", true, null).encode())
                    answer.ifBlank { "I couldn't make out anything on that screenshot." }
                } else if (ProxyClient.isConfigured()) {
                    // The agent: the model may call tools; each step lands in the chat as a card.
                    AgentLoop(
                        toolBox,
                        step = { m, c, t -> AgentClient.step(m, c, t) },
                        approve = { ask ->
                            val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
                            pendingApproval = Approval(ask.description, ask.note, answer)
                            answer.await()
                        },
                        onStep = { _, result ->
                            brain.addMessage(id, ActionCard.ROLE, ActionCard(result.summary, result.ok, result.undo).encode())
                            if (activeId == id) turns = loadTurns(id)
                            refreshTasks(); refreshMemories(); upcomingReminders = brain.upcomingReminders()
                            documents = brain.documents(); refreshRoutines()
                        },
                    ).run(history, context, sourceConversation = id)
                } else {
                    withContext(Dispatchers.IO) { GroqClient.generate(history, context) }
                }
                lastLatencyMs = System.currentTimeMillis() - started
                val result = DesktopTurn.process(raw)
                applyMemory(result.memory, sourceConversation = id)
                brain.addMessage(id, ChatTurn.ASSISTANT, result.text.ifBlank { "…" })
                if (id == quickConversationId) quickAnswer = result.text
                // Only redraw the chat if the user is still looking at it.
                if (activeId == id) turns = loadTurns(id)
                refreshConversations()
                usage = UsageStats.today()
                // The Worker reports the plan on every reply (the owner's email → pro).
                account = Identity.account()
                // Asked out loud → answered out loud (or always, if the user chose that).
                if (spoken || speakAllReplies) speakReply(result.text)
            } catch (e: Exception) {
                DebugLog.log(DebugLog.Stage.ERROR, "desktop turn failed: ${e.javaClass.simpleName}")
                // ProxyException already carries a human, speakable sentence.
                error = e.message ?: "Something went wrong — try again."
                if (id == quickConversationId) quickError = error
            } finally {
                thinkingIn = null
                pendingApproval?.let { it.answer.complete(false); pendingApproval = null }
            }
        }
        return true
    }

    /** Forgets one memory (the Memory screen's ✕). */
    fun forgetMemory(id: String) {
        memories.firstOrNull { it.id == id }?.let { brain.log("memory", "Forgot: ${it.text}") }
        brain.deleteMemory(id)
        refreshMemories()
    }

    fun forgetEverything() {
        memories.forEach { brain.deleteMemory(it.id) }
        brain.log("memory", "Forgot everything")
        refreshMemories()
    }

    /** REMEMBER/FORGET from a reply, into typed, sourced memory — the phone's rules (DesktopTurn). */
    private fun applyMemory(actions: List<com.jarvis.os.data.MemoryAction>, sourceConversation: String) {
        if (actions.isEmpty()) return
        for (a in actions) {
            when (a) {
                is com.jarvis.os.data.MemoryAction.Remember ->
                    brain.remember(a.fact.take(com.jarvis.os.data.MemoryActions.MAX_FACT), sourceConversation = sourceConversation)
                        ?.let { brain.log("memory", "Remembered: ${it.text}") }
                is com.jarvis.os.data.MemoryAction.Forget ->
                    brain.forget(a.about).takeIf { it > 0 }?.let { brain.log("memory", "Forgot $it memory item(s) about “${a.about}”") }
            }
        }
        refreshMemories()
    }

    companion object {
        /** Gaps before each try at asking the Worker for the plan: now, then 4 s, 15 s, 60 s. */
        private val REFRESH_RETRY_MS = listOf(0L, 4_000L, 15_000L, 60_000L)

        /** Rides on Quick bar questions: the answer lands in a small box and is often pasted somewhere. */
        const val QUICK_NOTE = "The user asked from the Quick bar (a small box over whatever they are doing). Keep the answer short. " +
            "When they ask you to rewrite, translate, fix or draft text, reply with ONLY the finished text, no preamble, so it can be copied straight into place. " +
            "\"What I copied\" / \"this\" means the clipboard: use read_clipboard."

        /** "Monday 28 September 2026, 17:05 (Asia/Kolkata, UTC+05:30)": exact, so "tomorrow at 5" becomes a real time. */
        fun nowLine(): String {
            val zone = java.time.ZoneId.systemDefault()
            return SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date()) +
                " (" + zone.id + ", UTC" + java.time.ZonedDateTime.now(zone).offset.id.replace("Z", "+00:00") + ")"
        }

        /** Opens the brain and, the first time, moves the old chat.json into it. */
        fun openBrain(): Brain {
            val b = Brain.open(AppDirs.file("brain.db"))
            runCatching { LegacyImport.run(b, AppDirs.file("chat.json")) }
                .onFailure { DebugLog.log(DebugLog.Stage.ERROR, "legacy import failed: ${it.javaClass.simpleName}") }
            return b
        }

        fun endOfToday(): Long = java.time.LocalDate.now().plusDays(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
