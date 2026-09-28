package com.jarvis.os.desktop.voice

import com.jarvis.os.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Listens for "Jarvis" in the background. Holds the microphone ONLY while armed, and
 * releases it the instant it hears the wake word — before telling anyone — so the
 * push-to-talk recorder that follows is the sole owner of the mic (the phone's
 * hardest-won rule: one mic owner, always).
 *
 * Nothing leaves the laptop while listening: detection runs locally on
 * [WakeWordDetector]; only the command recorded AFTER the wake word is transcribed.
 */
class WakeListener(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var detector: WakeWordDetector? = null
    @Volatile private var line: TargetDataLine? = null

    val armed: Boolean get() = job?.isActive == true

    /**
     * Starts listening; [onWake] runs once, after the mic has been released. Returns
     * false (with [onError] told why) if the models or the mic are unavailable.
     */
    fun arm(onWake: () -> Unit, onError: (String) -> Unit) {
        if (armed) return
        job = scope.launch(Dispatchers.IO) {
            val det = try {
                detector ?: WakeWordDetector.load().also { detector = it }
            } catch (e: Throwable) {
                DebugLog.log(DebugLog.Stage.ERROR, "wake word unavailable: ${e.javaClass.simpleName}")
                onError("Wake word couldn't start on this PC.")
                return@launch
            }
            val format = AudioFormat(16_000f, 16, 1, true, false)
            val l = try {
                (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine)
                    .apply { open(format); start() }
            } catch (e: Exception) {
                onError("The microphone isn't available for the wake word.")
                return@launch
            }
            line = l
            det.reset()
            val bytes = ByteArray(WakeWordDetector.CHUNK * 2)
            val samples = ShortArray(WakeWordDetector.CHUNK)
            var heard = false
            try {
                while (isActive) {
                    var read = 0
                    while (read < bytes.size && isActive) {
                        val n = l.read(bytes, read, bytes.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < bytes.size) continue
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                    if (det.process(samples) >= WakeWordDetector.THRESHOLD) {
                        heard = true
                        break
                    }
                }
            } finally {
                runCatching { l.stop(); l.close() }
                line = null
                det.reset()
            }
            if (heard) onWake()
        }
    }

    /** Stops listening and releases the mic. */
    fun disarm() {
        job?.cancel()
        job = null
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
    }

    fun shutdown() {
        disarm()
        detector?.close()
        detector = null
    }
}
