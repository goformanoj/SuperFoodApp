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
    // `:desktop:ping --args="--agent|<request>"` runs the REAL agent against the live Worker,
    // on a throwaway in-memory brain with a host that opens nothing — the user's data and
    // screen are never touched. Prints every step and the answer. Irreversible steps are
    // declined automatically (and reported), so a test can never delete anything.
    if (args.firstOrNull()?.startsWith("--agent|") == true) {
        // Gradle splits --args on spaces: rejoin them, or the agent only hears the first word.
        val request = args.joinToString(" ").substringAfter("--agent|")
        val ok = runBlocking {
            val brain = com.jarvis.os.desktop.brain.Brain.inMemory()
            val host = object : com.jarvis.os.desktop.agent.ToolBox.Host {
                override fun openUrl(url: String): Boolean { println("  (would open $url)"); return true }
                override fun openApp(name: String): String? { println("  (would open app $name)"); return name }
                override fun clipboardText(): String? = null
            }
            val tools = com.jarvis.os.desktop.agent.ToolBox(brain, host)
            val zone = java.time.ZoneId.systemDefault()
            val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm").format(java.util.Date()) +
                " (" + zone.id + ", UTC" + java.time.ZonedDateTime.now(zone).offset.id + ")"
            try {
                val answer = com.jarvis.os.desktop.agent.AgentLoop(
                    tools,
                    step = { m, c, t -> com.jarvis.os.desktop.agent.AgentClient.step(m, c, t) },
                    approve = { d -> println("  [approval requested → declined in test] $d"); false },
                    onStep = { call, r -> println("  step: ${call.name}(${call.arguments}) → ${if (r.ok) "OK" else "FAILED"}: ${r.summary}") },
                ).run(listOf(ChatTurn(ChatTurn.USER, request)), DesktopTurn.context(now, ""), "test")
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
