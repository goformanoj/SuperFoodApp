package com.jarvis.os.desktop.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.jarvis.os.voice.MelSpectrogram
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * "Hey Jarvis" on the laptop: the phone's openWakeWord pipeline (app/.../voice/
 * OpenWakeWord.kt), step for step, with ONNX Runtime in place of TFLite (which has no
 * desktop runtime). Per 80 ms chunk of 16 kHz int16 audio:
 *   1. mel-spectrogram over the last 1760 samples → 8 frames (the phone's own pure-Kotlin
 *      [MelSpectrogram] and weights), each transformed x/10 + 2;
 *   2. the embedding model over the last 76 frames → one 96-d embedding;
 *   3. the wake-word model over the last 16 embeddings → a score in [0, 1].
 *
 * Checked against the repo's real recordings (scripts/owwtest/fixtures) in
 * WakeWordDetectorTest — the same bar as the phone's Python check: a real "hey jarvis"
 * crosses [THRESHOLD], silence stays near zero.
 *
 * Not thread-safe: one audio thread calls [process].
 */
class WakeWordDetector private constructor(
    private val env: OrtEnvironment,
    private val embedding: OrtSession,
    private val wakeword: OrtSession,
    private val mel: MelSpectrogram,
) : AutoCloseable {

    private val raw = FloatArray(MEL_INPUT_SAMPLES)
    private val melBuffer = ArrayDeque<FloatArray>()
    private val featBuffer = ArrayDeque<FloatArray>()
    private val embName = embedding.inputNames.first()
    private val wwName = wakeword.inputNames.first()

    /** Feed exactly [CHUNK] samples; returns the current score (0 while warming up). */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "chunk must be $CHUNK samples" }
        System.arraycopy(raw, CHUNK, raw, 0, MEL_INPUT_SAMPLES - CHUNK)
        val base = MEL_INPUT_SAMPLES - CHUNK
        for (i in 0 until CHUNK) raw[base + i] = chunk[i].toFloat()

        val frames = mel.process(raw)
        for (f in 0 until FRAMES_PER_CHUNK) {
            melBuffer.addLast(FloatArray(MEL_BINS) { b -> frames[f][b] / 10f + 2f })
        }
        while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()

        if (melBuffer.size >= EMBED_WINDOW) {
            val input = FloatBuffer.allocate(EMBED_WINDOW * MEL_BINS)
            for (i in melBuffer.size - EMBED_WINDOW until melBuffer.size) input.put(melBuffer[i])
            input.rewind()
            OnnxTensor.createTensor(env, input, longArrayOf(1, EMBED_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { t ->
                embedding.run(mapOf(embName to t)).use { out ->
                    featBuffer.addLast(flatten(out[0].value).copyOf(EMBED_DIM))
                }
            }
            while (featBuffer.size > FEAT_BUFFER_MAX) featBuffer.removeFirst()
        }

        if (featBuffer.size >= PREDICT_WINDOW) {
            val input = FloatBuffer.allocate(PREDICT_WINDOW * EMBED_DIM)
            for (i in featBuffer.size - PREDICT_WINDOW until featBuffer.size) input.put(featBuffer[i])
            input.rewind()
            OnnxTensor.createTensor(env, input, longArrayOf(1, PREDICT_WINDOW.toLong(), EMBED_DIM.toLong())).use { t ->
                wakeword.run(mapOf(wwName to t)).use { out -> return flatten(out[0].value)[0] }
            }
        }
        return 0f
    }

    /** Forget the stream (after a detection) so the same audio can't fire twice. */
    fun reset() {
        raw.fill(0f)
        melBuffer.clear()
        featBuffer.clear()
    }

    override fun close() {
        runCatching { embedding.close() }
        runCatching { wakeword.close() }
    }

    /** ONNX outputs arrive as nested float arrays of varying rank; take them flat. */
    private fun flatten(v: Any?): FloatArray = when (v) {
        is FloatArray -> v
        is Array<*> -> v.flatMap { flatten(it).asList() }.toFloatArray()
        else -> floatArrayOf()
    }

    companion object {
        const val CHUNK = 1280               // 80 ms at 16 kHz — openWakeWord's step
        const val THRESHOLD = 0.5f           // the phone's DETECT_THRESHOLD
        private const val MEL_INPUT_SAMPLES = 1760
        private const val FRAMES_PER_CHUNK = 8
        private const val MEL_BINS = 32
        private const val EMBED_WINDOW = 76
        private const val EMBED_DIM = 96
        private const val PREDICT_WINDOW = 16
        private const val MEL_BUFFER_MAX = 970
        private const val FEAT_BUFFER_MAX = 120

        /** Loads the models from the classpath; null (with the reason logged) if unavailable. */
        fun load(): WakeWordDetector {
            fun bytes(path: String): ByteArray =
                requireNotNull(WakeWordDetector::class.java.classLoader.getResourceAsStream(path)) { "$path missing" }
                    .use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) } // tiny models; stay off the other cores
            return WakeWordDetector(
                env,
                env.createSession(bytes("openwakeword/embedding_model.onnx"), opts),
                env.createSession(bytes("openwakeword/hey_jarvis_v0.1.onnx"), opts),
                MelSpectrogram(ByteBuffer.wrap(bytes("openwakeword/melspec_weights.bin"))),
            )
        }
    }
}
