package com.jarvis.os.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.jarvis.os.debug.DebugLog

/**
 * Listens for "Hey Jarvis" **while JARVIS is speaking**, so the user can cut him
 * off with their voice instead of a tap.
 *
 * ## Why the wake word and not a loudness detector
 *
 * The obvious design is a voice-activity detector: hear a person, stop talking.
 * It cannot work on a phone. The speaker is a few centimetres from the
 * microphone, so JARVIS's own voice arrives 20-30 dB above the user's, and
 * platform echo cancellation is no help — it references the *communication*
 * downlink, while TTS here plays on the music stream, so
 * `AcousticEchoCanceler` will happily report itself enabled and cancel nothing.
 * A loudness detector in that position does not detect barge-in; it detects
 * JARVIS, every single time, and the result is a loop that speaks, hears itself,
 * stops, listens to silence and goes to sleep. That is worse than having no
 * barge-in at all, and it could not be discovered until it was on a device.
 *
 * The wake word has none of that problem. **JARVIS never says "Hey Jarvis", so
 * his own voice cannot trigger it** — with or without working echo cancellation.
 * The detector is the one already shipped and already verified two ways in CI.
 *
 * ## Why not [VoiceController]
 *
 * `SpeechRecognizer` mutes `STREAM_MUSIC` to suppress its earcon, and TTS plays
 * on `STREAM_MUSIC`. Opening it during speech would silence the very sentence
 * the user is trying to interrupt.
 *
 * ## One microphone owner, still
 *
 * This does not run alongside the recogniser. It is [MicOwner.BARGE_IN], and
 * `AssistantEngine.applyMicOwner` starts it only when [WorkSession] names it the
 * owner — the same rule, extended, rather than a second holder outside it.
 *
 * Driven from the main thread; reads audio on its own.
 *
 * @see com.jarvis.os.control.HotwordService for the same loop in its original form
 */
class BargeInListener(private val context: Context) {

    /** The user said the wake word over JARVIS. Delivered on the main thread. */
    var onDetected: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())

    /**
     * Held for the object's life and [OpenWakeWord.reset] between arms, rather
     * than built per utterance. Loading it is real TFLite work, and paying that
     * at the start of every reply would put a stutter on every single answer.
     */
    @Volatile private var detector: OpenWakeWord? = null

    /** True once we know the models cannot be loaded, so we stop trying. */
    @Volatile private var unavailable = false

    @Volatile private var running = false
    private var thread: Thread? = null

    /**
     * Open the microphone and start listening for the wake word.
     *
     * Idempotent: [com.jarvis.os.assistant.AssistantEngine.applyMicOwner] runs
     * on plenty of triggers besides speech starting, and every one of them would
     * otherwise open a second recorder.
     */
    fun start() {
        if (thread != null || unavailable) return
        running = true
        // The model load happens on this thread, not the caller's: it is
        // hundreds of milliseconds of work and the caller is the main thread
        // partway through starting to speak.
        thread = Thread { loop() }.apply { priority = Thread.NORM_PRIORITY; start() }
    }

    /**
     * Release the microphone.
     *
     * Returns true if it was actually recording, which the caller needs in order
     * to know whether the speech recogniser is about to race the audio HAL for
     * the input — see `MIC_HANDOFF_MS`.
     */
    fun stop(): Boolean {
        val t = thread ?: return false
        running = false
        // Joining is what makes the return value trustworthy: once this returns,
        // the recorder has been released, because releasing it is the last thing
        // the loop does. The caller uses that to decide whether the speech
        // recogniser needs a gap before it opens the input.
        //
        // One read is CHUNK samples at 16 kHz — 80 ms — so the loop cannot be
        // much more than that from noticing, well inside any ANR budget.
        try { t.join(JOIN_MS) } catch (e: InterruptedException) {}
        thread = null
        return true
    }

    /** Stop and release the models. */
    fun close() {
        stop()
        detector?.close()
        detector = null
    }

    private fun loop() {
        val detect = detector ?: OpenWakeWord(context).also { detector = it }
        // Loading the models on first use takes long enough that a short reply
        // can finish inside it. Without this check the thread would go on to
        // open the microphone AFTER stop() had already reported it released —
        // seizing the input at the exact moment the speech recogniser wants it.
        if (!running) return
        if (!detect.available) {
            // No models, or TFLite unsupported on this device. Tapping the orb
            // remains, so this stands down quietly rather than failing the turn.
            DebugLog.log(DebugLog.Stage.ERROR, "barge-in: wake word unavailable — tap to interrupt still works")
            unavailable = true
            return
        }
        // Old audio must not carry across an utterance boundary, or the tail of
        // the previous turn could fire the moment this one starts.
        detect.reset()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val recorder = try {
            AudioRecord(
                // VOICE_RECOGNITION, matching HotwordService — it is what
                // openWakeWord was trained against. VOICE_COMMUNICATION would
                // bring its own echo cancellation, but it also changes the audio
                // route and gain characteristics, which puts the models
                // off-distribution for a benefit this stream does not get anyway.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING,
                maxOf(minBuf, OpenWakeWord.CHUNK * 8),
            )
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "barge-in: mic init failed: ${e.message}")
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            return
        }

        // Same again: constructing the AudioRecord is not instant either.
        if (!running) {
            recorder.release()
            return
        }

        val buf = ShortArray(OpenWakeWord.CHUNK)
        try {
            recorder.startRecording()
            DebugLog.log(DebugLog.Stage.HEARD, "barge-in armed — say “Hey Jarvis” to cut in")
            while (running) {
                var off = 0
                while (off < buf.size) {
                    val n = recorder.read(buf, off, buf.size - off)
                    if (n <= 0) { off = -1; break }
                    off += n
                }
                if (off != buf.size) continue
                if (detect.process(buf) >= OpenWakeWord.DETECT_THRESHOLD) {
                    DebugLog.log(DebugLog.Stage.HEARD, "barge-in — heard “Hey Jarvis” over the reply")
                    detect.reset()
                    main.post { onDetected() }
                    // `break`, NOT `running = false`. Clearing the flag here
                    // would make the stop() that follows report "nothing was
                    // recording", and the caller would then open the speech
                    // recogniser with no hand-off gap — while this thread is
                    // still inside the release below. Leaving the flag set means
                    // stop() joins, which both reports honestly and guarantees
                    // the recorder is gone before anything else asks for the mic.
                    break
                }
            }
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "barge-in loop error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            // Released here rather than in stop(), so the recorder is only ever
            // touched by the thread that owns it.
            try { recorder.stop() } catch (e: Exception) {}
            recorder.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val JOIN_MS = 300L
    }
}
