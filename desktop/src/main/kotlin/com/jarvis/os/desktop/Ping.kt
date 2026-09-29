package com.jarvis.os.desktop

import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.UsageStats
import com.jarvis.os.data.ChatTurn
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * `./gradlew :desktop:ping` — a one-shot health check of the desktop's server path,
 * the desktop twin of the phone's Diagnostics ping: the same tiny "Reply with
 * exactly: OK" probe (a few tokens, never the full assistant prompt), sent through
 * the real [ProxyClient] with a real anonymous identity.
 *
 * Prints only safe facts — configured or not, the reply, plan and allowance — never
 * the secret or a token.
 */
fun main(args: Array<String>) {
    println("Worker configured: ${ProxyClient.isConfigured()} (Firebase key present: ${Identity.isConfigured()})")
    if (!ProxyClient.isConfigured()) {
        println("Missing PROXY_SECRET or FIREBASE_WEB_API_KEY in ~/.gradle/gradle.properties.")
        exitProcess(2)
    }
    // Gradle splits --args on spaces: rejoin them, or a request only keeps its first word.
    val joined = args.joinToString(" ")
    // `--search|<query>`: the live web through /search. `--files|<query>`: Windows Search
    // (paths are counted, not printed). `--doc|<path>`: reads a document locally and prints
    // what it found: pages, characters, the first passage for a sample question.
    when {
        joined.startsWith("--search|") -> exitProcess(if (runBlocking { probe {
            val w = com.jarvis.os.desktop.knowledge.KnowledgeClient.webSearch(joined.substringAfter("|"), "Current date/time: ${java.time.LocalDateTime.now()}.")
            println("Answer: ${w.answer}")
            w.sources.forEach { println("  source: ${it.title} — ${it.url}") }
        } }) 0 else 1)
        joined.startsWith("--files|") -> exitProcess(if (runBlocking { probe {
            val q = com.jarvis.os.desktop.knowledge.FileSearch.query(joined.substringAfter("|")) ?: error("nothing to search for")
            val found = com.jarvis.os.desktop.knowledge.FileSearch.run(q)
            println("Windows Search: ${found.size} file(s); kinds: ${found.groupingBy { it.name.substringAfterLast('.', "?").lowercase() }.eachCount()}")
        } }) 0 else 1)
        // This laptop's account id (the Firebase uid, not a secret: it's what PRO_UIDS lists).
        // Read from the token's payload; the token itself is never printed.
        joined.startsWith("--whoami") -> exitProcess(if (runBlocking { probe {
            val payload = Identity.token().split('.').getOrNull(1) ?: error("no token")
            val json = org.json.JSONObject(String(java.util.Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))))
            println("uid: ${json.optString("user_id")}  plan: ${Identity.plan()}  signed in with Google: ${json.optString("email").isNotBlank()}")
        } }) 0 else 1)
        // A synthetic error dialog drawn here, never the user's real screen.
        joined.startsWith("--vision-test") -> exitProcess(if (runBlocking { probe {
            val img = java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply {
                color = java.awt.Color(30, 30, 30); fillRect(0, 0, 1280, 720)
                color = java.awt.Color(245, 245, 245); fillRect(340, 220, 600, 240)
                color = java.awt.Color(200, 30, 30); font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 22); drawString("Copy File", 370, 265)
                color = java.awt.Color.BLACK; font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 18)
                drawString("Error 0x80070070: There is not enough space on the disk.", 370, 320)
                drawString("You need an additional 2.4 GB to copy these files.", 370, 350)
                dispose()
            }
            val answer = com.jarvis.os.desktop.knowledge.KnowledgeClient.askAboutImage(
                com.jarvis.os.desktop.knowledge.ScreenGrab.jpeg(img), "What's this error and how do I fix it?")
            println("Vision: $answer")
        } }) 0 else 1)
        // A real fetch of YouTube's own search page (Phase 4 tool play_youtube) — no AI
        // model involved, so this costs nothing against the daily allowance.
        joined.startsWith("--youtube-test|") -> exitProcess(if (runBlocking { probe {
            val q = joined.substringAfter("|")
            val v = com.jarvis.os.desktop.knowledge.YouTubeSearch.firstVideo(q) ?: error("no video result")
            println("video: ${v.title} -> ${com.jarvis.os.desktop.knowledge.YouTubeSearch.videoUrl(v.id)}")
            val p = com.jarvis.os.desktop.knowledge.YouTubeSearch.firstPlaylist(q) ?: error("no playlist result")
            println("playlist: ${p.title} startVideo=${p.startVideoId} -> ${com.jarvis.os.desktop.knowledge.YouTubeSearch.playlistUrl(p.id, p.startVideoId)}")
        } }) 0 else 1)
        joined.startsWith("--doc|") -> exitProcess(if (runBlocking { probe {
            val ex = com.jarvis.os.desktop.knowledge.DocText.extract(java.io.File(joined.substringAfter("|")))
            println("${ex.name}: ${ex.pages.size} ${ex.unit}(s), ${ex.chars} chars, ${com.jarvis.os.desktop.knowledge.DocText.chunks(ex.pages).size} chunks")
        } }) 0 else 1)
        // A real push + pull against the LIVE Worker (Phase 7), on a throwaway in-memory
        // brain — never touches the user's real brain.db. Proves the deployed endpoint,
        // auth and D1 table actually work, not just the pure logic the unit tests cover.
        joined.startsWith("--sync-test") -> exitProcess(if (runBlocking { probe {
            val brain = com.jarvis.os.desktop.brain.Brain.inMemory()
            val client = com.jarvis.os.desktop.sync.SyncClient(brain)
            val t = brain.addTask("Sync smoke test " + System.currentTimeMillis())
            println("  pushing 1 pending edit...")
            val pushed = client.pushOnce()
            println("  pushed=$pushed, outbox now empty=${brain.pendingSync().isEmpty()}")
            // A second, independent brain simulates "the other device": it starts with
            // nothing, pulls from the same account, and should see the task just pushed.
            val other = com.jarvis.os.desktop.brain.Brain.inMemory()
            val otherClient = com.jarvis.os.desktop.sync.SyncClient(other)
            val pulled = otherClient.pullOnce()
            val seen = other.task(t.id)
            println("  pulled=$pulled, other device sees it: ${seen?.title}")
            if (seen?.title != t.title) error("round trip did not match: sent \"${t.title}\", other device has \"${seen?.title}\"")
            println("  OK: the live Worker's sync round trip works.")
        } }) 0 else 1)
    }
    // `:desktop:ping --args="--agent|<request>"` runs the REAL agent against the live Worker,
    // on a throwaway in-memory brain with a host that opens nothing — the user's data and
    // screen are never touched. Prints every step and the answer. Steps that need approval
    // are declined automatically (and reported), so a test can never delete or share anything.
    // Web and file SEARCHES are real (read-only); `--agent-doc|<path>|<request>` first attaches a document.
    if (joined.startsWith("--agent|") || joined.startsWith("--agent-doc|")) {
        val docPath = if (joined.startsWith("--agent-doc|")) joined.substringAfter("|").substringBefore("|") else null
        val request = joined.substringAfter("|").let { if (docPath != null) it.substringAfter("|") else it }
        val ok = runBlocking {
            val brain = com.jarvis.os.desktop.brain.Brain.inMemory()
            val host = object : com.jarvis.os.desktop.agent.ToolBox.Host {
                override fun openUrl(url: String): Boolean { println("  (would open $url)"); return true }
                override fun openApp(name: String): String? { println("  (would open app $name)"); return name }
                override fun clipboardText(): String? = null
                override fun openFile(path: String): Boolean { println("  (would open a file)"); return true }
                override suspend fun webSearch(query: String) = com.jarvis.os.desktop.knowledge.KnowledgeClient.webSearch(query)
                override suspend fun searchFiles(q: com.jarvis.os.desktop.knowledge.FileSearch.Query) = com.jarvis.os.desktop.knowledge.FileSearch.run(q)
            }
            val conv = brain.createConversation("test").id
            val attached = docPath?.let { listOf(com.jarvis.os.desktop.knowledge.Library.import(brain, java.io.File(it), conv)) }.orEmpty()
            val tools = com.jarvis.os.desktop.agent.ToolBox(brain, host)
            val zone = java.time.ZoneId.systemDefault()
            val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm").format(java.util.Date()) +
                " (" + zone.id + ", UTC" + java.time.ZonedDateTime.now(zone).offset.id + ")"
            try {
                val answer = com.jarvis.os.desktop.agent.AgentLoop(
                    tools,
                    step = { m, c, t -> com.jarvis.os.desktop.agent.AgentClient.step(m, c, t) },
                    approve = { d -> println("  [approval requested → declined in test] ${d.description}"); false },
                    onStep = { call, r -> println("  step: ${call.name}(${call.arguments}) → ${if (r.ok) "OK" else "FAILED"}: ${r.summary}") },
                ).run(listOf(ChatTurn(ChatTurn.USER, request)), DesktopTurn.context(now, "", attached.map { "${it.name} (${it.pages} ${it.unit}s)" }, java.time.LocalDate.now()), conv)
                println("Answer: $answer")
                println("Brain now: ${brain.openTasks().size} task(s) ${brain.openTasks().map { it.title + (it.dueAt?.let { d -> " @ " + java.time.Instant.ofEpochMilli(d).atZone(zone).toLocalDateTime() } ?: "") }}, " +
                    "${brain.upcomingReminders().size} reminder(s) ${brain.upcomingReminders().map { it.text + " @ " + java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDateTime() }}")
                true
            } catch (e: Exception) {
                println("FAILED: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }
        exitProcess(if (ok) 0 else 1)
    }
    // `:desktop:ping --args="some.wav"` checks the voice path instead: the file goes
    // through the real TranscribeClient to the live /transcribe, and the text is printed.
    args.firstOrNull()?.let { path ->
        val ok = runBlocking {
            try {
                val text = com.jarvis.os.desktop.voice.TranscribeClient.transcribe(java.io.File(path).readBytes())
                println("Heard: \"$text\"")
                UsageStats.today()?.let { println("Tokens left today: ${UsageStats.format(it.remaining)}") }
                true
            } catch (e: Exception) {
                println("FAILED: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }
        exitProcess(if (ok) 0 else 1)
    }
    val ok = runBlocking {
        try {
            val reply = ProxyClient.generate(
                listOf(ChatTurn(ChatTurn.USER, "Reply with just: OK")),
                context = "",
                systemOverride = "Reply with exactly: OK",
            )
            println("Reply: $reply")
            println("Plan: ${Identity.plan()}")
            UsageStats.today()?.let { println("Tokens left today: ${UsageStats.format(it.remaining)}") }
            true
        } catch (e: Exception) {
            println("FAILED: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }
    exitProcess(if (ok) 0 else 1)
}

private suspend fun probe(block: suspend () -> Unit): Boolean = try {
    block(); true
} catch (e: Exception) {
    println("FAILED: ${e.message ?: e.javaClass.simpleName}"); false
}
