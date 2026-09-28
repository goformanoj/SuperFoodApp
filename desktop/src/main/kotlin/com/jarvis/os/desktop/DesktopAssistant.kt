package com.jarvis.os.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jarvis.os.ai.GroqClient
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
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
    private val toolBox = ToolBox(brain, WindowsHost())

    /** An irreversible step waiting for the user's click (Rule 6 — never by prompt). */
    class Approval(val description: String, internal val answer: kotlinx.coroutines.CompletableDeferred<Boolean>)
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
        val line = toolBox.undo(card.undo) ?: return
        brain.updateMessage(messageId, card.copy(undone = true, summary = card.summary + " — undone ($line)").encode())
        activeId?.let { turns = loadTurns(it) }
        refreshTasks(); refreshMemories()
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
    }

    /** Renames a conversation. A blank name is ignored (the old title stays). */
    fun rename(id: String, title: String) {
        val clean = DesktopTurn.cleanTitle(title) ?: return
        brain.renameConversation(id, clean)
        refreshConversations()
    }

    /** Returns true if the message was accepted (so the input can be cleared). */
    fun send(input: String, spoken: Boolean = false): Boolean {
        val message = input.trim()
        if (message.isEmpty() || thinking) return false
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
        activeId = id
        turns = loadTurns(id)
        refreshConversations()
        thinkingIn = id
        scope.launch {
            try {
                val history = modelHistory(id).takeLast(DesktopTurn.MAX_CONTEXT_TURNS)
                // Exact local time and zone: the agent turns "tomorrow at 5" into a real time.
                val zone = java.time.ZoneId.systemDefault()
                val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date()) +
                    " (" + zone.id + ", UTC" + java.time.ZonedDateTime.now(zone).offset.id.replace("Z", "+00:00") + ")"
                val context = DesktopTurn.context(now, formatMemory("", facts))
                val started = System.currentTimeMillis()
                val raw = if (ProxyClient.isConfigured()) {
                    // The agent: the model may call tools; each step lands in the chat as a card.
                    AgentLoop(
                        toolBox,
                        step = { m, c, t -> AgentClient.step(m, c, t) },
                        approve = { description ->
                            val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
                            pendingApproval = Approval(description, answer)
                            answer.await()
                        },
                        onStep = { _, result ->
                            brain.addMessage(id, ActionCard.ROLE, ActionCard(result.summary, result.ok, result.undo).encode())
                            if (activeId == id) turns = loadTurns(id)
                            refreshTasks(); refreshMemories(); upcomingReminders = brain.upcomingReminders()
                        },
                    ).run(history, context, sourceConversation = id)
                } else {
                    withContext(Dispatchers.IO) { GroqClient.generate(history, context) }
                }
                lastLatencyMs = System.currentTimeMillis() - started
                val result = DesktopTurn.process(raw)
                applyMemory(result.memory, sourceConversation = id)
                brain.addMessage(id, ChatTurn.ASSISTANT, result.text.ifBlank { "…" })
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
