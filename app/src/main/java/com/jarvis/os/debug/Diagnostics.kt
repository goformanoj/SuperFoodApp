package com.jarvis.os.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.os.ai.Brain
import com.jarvis.os.control.ScreenControlService
import com.jarvis.os.data.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One self-check result. [ok] false means this is why something isn't working. */
data class Check(val name: String, val ok: Boolean, val detail: String)

/**
 * Answers "why isn't it working?" without a debugger. Everything here is
 * readable on the phone and exportable as text, so a failure report contains
 * facts rather than "it didn't work".
 */
object Diagnostics {

    /** Instant checks — no network, safe to run on the main thread. */
    fun run(context: Context): List<Check> = listOf(
        permission(context, Manifest.permission.RECORD_AUDIO, "Microphone"),
        permission(context, Manifest.permission.READ_CALENDAR, "Calendar (read)"),
        permission(context, Manifest.permission.WRITE_CALENDAR, "Calendar (write)"),
        speechCheck(context),
        accessibilityCheck(),
        brainCheck(),
        deviceInfo(),
    )

    /**
     * A real round-trip to the AI provider. This is the check that proves the key
     * works and the network is reachable — the one thing that cannot be verified
     * from the build environment.
     */
    suspend fun pingBrain(): Check = withContext(Dispatchers.IO) {
        if (!Brain.hasKey()) return@withContext Check("AI round-trip", false, "No API key compiled in")
        val started = System.currentTimeMillis()
        try {
            val reply = Brain.generate(listOf(ChatTurn(ChatTurn.USER, "Reply with just: OK")), "")
            val ms = System.currentTimeMillis() - started
            Check("AI round-trip", true, "${Brain.providerName()} replied in ${ms}ms: ${reply.take(40)}")
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - started
            Check("AI round-trip", false, "Failed after ${ms}ms: ${DebugLog.redact(e.message ?: e.javaClass.simpleName)}")
        }
    }

    private fun permission(context: Context, permission: String, label: String): Check {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return Check(label, granted, if (granted) "Granted" else "Not granted — tap to allow in app settings")
    }

    private fun speechCheck(context: Context): Check {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        return Check(
            "Speech recognition",
            available,
            if (available) "Available" else "No recognition service — install/enable Google app",
        )
    }

    private fun accessibilityCheck(): Check {
        val running = ScreenControlService.isRunning()
        return Check(
            "Screen control",
            running,
            if (running) {
                "Accessibility service connected"
            } else {
                "Off — Settings > Accessibility > Downloaded apps > JARVIS Screen Control"
            },
        )
    }

    private fun brainCheck(): Check {
        val has = Brain.hasKey()
        return Check(
            "AI key",
            has,
            if (has) "Provider: ${Brain.providerName()}" else "No key — set GROQ_API_KEY as a GitHub secret and rebuild",
        )
    }

    private fun deviceInfo(): Check =
        Check("Device", true, "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
}
