package com.jarvis.os.desktop.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlin.coroutines.coroutineContext

class MicException(message: String) : Exception(message)

/**
 * Push-to-talk capture from the default microphone: 16 kHz mono 16-bit, the format
 * Whisper wants. Records until the user stops talking ([Wav.Endpointer]), presses the
 * mic again ([stop]), or the cap is hit. Reports loudness as it goes so the UI can show
 * that JARVIS is actually hearing something.
 *
 * Exactly one owner of the mic at a time — the phone's hardest-won rule — is kept by
 * the caller: [DesktopVoice] never starts a second recording while one is running.
 */
class MicRecorder {
    @Volatile private var stopRequested = false

    fun stop() { stopRequested = true }

    /** Returns WAV bytes, or null if nothing that sounded like speech was heard. */
    suspend fun record(onLevel: (Float) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        stopRequested = false
        val format = AudioFormat(Wav.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) throw MicException("No microphone found.")
        val line = try {
            (AudioSystem.getLine(info) as TargetDataLine).apply { open(format); start() }
        } catch (e: LineUnavailableException) {
            throw MicException("The microphone is busy or blocked (check Windows privacy settings).")
        } catch (e: SecurityException) {
            throw MicException("Windows blocked microphone access for Java — allow it in Privacy & security → Microphone.")
        }
        val chunkMs = 100
        val buf = ByteArray(Wav.SAMPLE_RATE * Wav.BYTES_PER_SAMPLE * chunkMs / 1000)
        val out = ByteArrayOutputStream()
        val endpointer = Wav.Endpointer()
        try {
            while (coroutineContext.isActive && !stopRequested) {
                val n = line.read(buf, 0, buf.size)
                if (n <= 0) continue
                out.write(buf, 0, n)
                val level = Wav.level(buf, n)
                onLevel(level)
                if (endpointer.feed(level, chunkMs)) break
            }
        } finally {
            line.stop(); line.close()
            onLevel(0f)
        }
        // A manual stop after real speech still counts; only "never spoke" is dropped.
        if (!endpointer.heardSpeech) null else Wav.encode(out.toByteArray())
    }
}
