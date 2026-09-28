package com.jarvis.os.desktop.voice

import com.jarvis.os.debug.DebugLog
import com.jarvis.os.voice.SpokenText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.Base64

/**
 * JARVIS's voice on Windows: the built-in speech engine (System.Speech — free, offline,
 * no key), driven through ONE long-lived PowerShell process so a reply starts in a
 * fraction of a second instead of paying PowerShell's start-up every time.
 *
 * Each line in is a base64 UTF-8 utterance (no quoting or newline hazards); the process
 * speaks it synchronously and answers "DONE", which is how the orb knows speaking has
 * ended. [stop] kills the process — the only instant, reliable way to cut a sentence
 * off — and the next [speak] starts a fresh one.
 *
 * Text goes through the phone's [SpokenText.plain] first, so markdown, emoji and
 * markers are never read aloud ("less than less than tap pipe…").
 */
class Speaker(private val voice: String = "Microsoft David Desktop") {
    private val lock = Mutex()
    @Volatile private var proc: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null

    val available: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Speaks [text] and returns when it has finished (or was stopped). */
    suspend fun speak(text: String) {
        if (!available) return
        val plain = SpokenText.plain(text).trim()
        if (plain.isEmpty()) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val p = ensure()
                    input!!.apply { write(Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))); newLine(); flush() }
                    // Blocks until DONE, or returns null when stop() killed the process.
                    while (true) {
                        val line = output!!.readLine() ?: break
                        if (line.trim().endsWith("DONE")) break
                    }
                    if (!p.isAlive) reset()
                } catch (e: Exception) {
                    DebugLog.log(DebugLog.Stage.ERROR, "speaker failed: ${e.javaClass.simpleName}")
                    reset()
                }
            }
        }
    }

    /** Cuts JARVIS off mid-sentence. */
    fun stop() {
        proc?.destroyForcibly()
    }

    fun shutdown() {
        stop(); reset()
    }

    private fun ensure(): Process {
        proc?.let { if (it.isAlive) return it }
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}ProgressPreference = 'SilentlyContinue'
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            try { ${'$'}s.SelectVoice('$voice') } catch { }
            ${'$'}s.Rate = 1
            while ((${'$'}l = [Console]::In.ReadLine()) -ne ${'$'}null) {
              try { ${'$'}s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${'$'}l))) } catch { }
              [Console]::Out.WriteLine('DONE'); [Console]::Out.Flush()
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        // stderr is DISCARDED, not merged: PowerShell writes a CLIXML progress blob there
        // ("Preparing modules for first use") with no trailing newline, and merged into
        // stdout it glued itself onto the first DONE — the speaker would wait forever.
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        proc = p
        input = p.outputStream.bufferedWriter(Charsets.US_ASCII)
        output = p.inputStream.bufferedReader(Charsets.UTF_8)
        return p
    }

    private fun reset() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        proc = null; input = null; output = null
    }
}
