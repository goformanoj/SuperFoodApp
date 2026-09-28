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
