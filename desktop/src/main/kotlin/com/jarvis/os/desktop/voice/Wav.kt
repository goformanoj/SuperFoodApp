package com.jarvis.os.desktop.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The audio helpers the recorder needs, pure so they are unit-tested (WavTest):
 * a PCM-to-WAV wrapper (what the Worker's /transcribe measures and forwards), and the
 * loudness and end-of-speech rules that decide when a push-to-talk clip is finished.
 */
object Wav {

    const val SAMPLE_RATE = 16_000   // what Whisper resamples to anyway — no point sending more
    const val BYTES_PER_SAMPLE = 2   // 16-bit little-endian mono

    /** Wraps raw 16-bit mono PCM in a canonical 44-byte WAV header. */
    fun encode(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1) /* PCM */; putShort(1) /* mono */
            putInt(sampleRate); putInt(sampleRate * BYTES_PER_SAMPLE); putShort(BYTES_PER_SAMPLE.toShort()); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header); write(pcm) }.toByteArray()
    }

    fun seconds(pcmBytes: Int, sampleRate: Int = SAMPLE_RATE): Double = pcmBytes.toDouble() / (sampleRate * BYTES_PER_SAMPLE)

    /** RMS loudness of a 16-bit LE buffer, 0..1. */
    fun level(buf: ByteArray, len: Int = buf.size): Float {
        val n = len / 2
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort() / 32768.0
            sum += s * s
        }
        return sqrt(sum / n).toFloat().coerceIn(0f, 1f)
    }

    /**
     * End-of-speech for push-to-talk: stop once the user has spoken for at least
     * [minSpeechMs] and then been quiet for [silenceMs]. Before any speech, silence
     * never stops the clip (the user may still be about to talk) — only the cap does.
     */
    class Endpointer(
        private val threshold: Float = 0.02f,
        private val minSpeechMs: Int = 400,
        private val silenceMs: Int = 1_300,
        private val maxMs: Int = 30_000,
    ) {
        private var speechMs = 0
        private var quietMs = 0
        private var totalMs = 0
        val heardSpeech: Boolean get() = speechMs >= minSpeechMs

        /** Feed one chunk's loudness and duration; true when the clip should end. */
        fun feed(level: Float, chunkMs: Int): Boolean {
            totalMs += chunkMs
            if (level >= threshold) {
                speechMs += chunkMs
                quietMs = 0
            } else if (heardSpeech) {
                quietMs += chunkMs
            }
            return totalMs >= maxMs || (heardSpeech && quietMs >= silenceMs)
        }
    }
}
