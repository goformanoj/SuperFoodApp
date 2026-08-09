package com.jarvis.os.voice

import android.content.Context
import com.jarvis.os.debug.DebugLog
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device "hey jarvis" wake word, using the openWakeWord models via TensorFlow
 * Lite. No key, no account, no network — the three small models are bundled in
 * assets. The heavy [android.speech.SpeechRecognizer] is not involved, so nothing
 * is transcribed while this listens; it only ever emits a score for one phrase.
 *
 * The pipeline is a direct port of openWakeWord's streaming inference, fed one
 * 80 ms (1280-sample) chunk at a time:
 *   1. melspectrogram model over the last 1760 samples (1280 + 3 hops of look-back)
 *      → 8 mel frames of 32 bins, transformed by x/10 + 2 (openWakeWord's default);
 *   2. embedding model over the last 76 mel frames → one 96-d embedding;
 *   3. wake-word model over the last 16 embeddings → a score in [0, 1].
 * Consecutive embeddings are therefore 8 mel frames apart, matching openWakeWord's
 * window 76 / step 8. Audio is fed as raw int16 values cast to float (NOT
 * normalised to ±1) — that is the scale the models were trained on.
 *
 * Cannot crash the caller: if the models are missing or TFLite fails, [available]
 * is false and the caller falls back to the in-app wake gate.
 */
class OpenWakeWord(context: Context) {

    private var melspec: Interpreter? = null
    private var embedding: Interpreter? = null
    private var wakeword: Interpreter? = null

    // Streaming buffers.
    private val raw = FloatArray(MEL_INPUT_SAMPLES) // last 1760 samples, left-padded with 0
    private val melBuffer = ArrayDeque<FloatArray>() // each entry = 32 mel bins
    private val featBuffer = ArrayDeque<FloatArray>() // each entry = 96-d embedding

    // Reused I/O tensors, so a per-chunk call allocates nothing.
    private val melIn = arrayOf(raw)
    private val melOut = Array(1) { Array(1) { Array(FRAMES_PER_CHUNK) { FloatArray(MEL_BINS) } } }
    private val embIn = Array(1) { Array(EMBED_WINDOW) { Array(MEL_BINS) { FloatArray(1) } } }
    private val embOut = Array(1) { Array(1) { Array(1) { FloatArray(EMBED_DIM) } } }
    private val wwIn = Array(1) { Array(PREDICT_WINDOW) { FloatArray(EMBED_DIM) } }
    private val wwOut = Array(1) { FloatArray(1) }

    val available: Boolean get() = melspec != null && embedding != null && wakeword != null

    /**
     * The exact reason the last load failed ("<exception class>: <message>"), or null
     * if it loaded. Surfaced so CI/Diagnostics show the real cause (e.g. the CONV_2D
     * overflow vs a missing native lib) instead of just "unavailable".
     */
    @Volatile
    var lastLoadError: String? = null
        private set

    init {
        try {
            // The melspectrogram model has a dynamic audio-length input; resize it to
            // our fixed chunk. strict=true is REQUIRED here — without it TFLite 2.16
            // leaves the downstream tensor shapes symbolic and a CONV_2D overflows
            // its byte count at prepare time ("BytesRequired ... overflowed"). This
            // matches openWakeWord's own resize_tensor_input(..., strict=True).
            melspec = interpreter(context, "openwakeword/melspectrogram.tflite", intArrayOf(1, MEL_INPUT_SAMPLES))
            embedding = interpreter(context, "openwakeword/embedding_model.tflite")
            wakeword = interpreter(context, "openwakeword/hey_jarvis_v0.1.tflite")
            lastLoadError = null
            DebugLog.log(DebugLog.Stage.HEARD, "openWakeWord loaded — \"hey jarvis\" ready")
        } catch (e: Throwable) {
            // Any failure (missing asset, unsupported ABI, OOM) → stay unavailable.
            lastLoadError = "${e.javaClass.name}: ${e.message ?: "(no message)"}"
            DebugLog.log(DebugLog.Stage.ERROR, "openWakeWord unavailable: $lastLoadError")
            close()
        }
    }

    /**
     * Feed exactly [CHUNK] (1280) int16 audio samples and get the current wake-word
     * score in [0, 1]. Returns 0 until enough audio has been seen to warm up, or if
     * the engine is unavailable. Not thread-safe — call from one audio thread.
     */
    fun process(chunk: ShortArray): Float {
        val mel = melspec ?: return 0f
        val emb = embedding ?: return 0f
        val ww = wakeword ?: return 0f
        if (chunk.size != CHUNK) return 0f

        // Slide the raw window: drop the oldest 1280, append the new 1280.
        System.arraycopy(raw, CHUNK, raw, 0, MEL_INPUT_SAMPLES - CHUNK)
        val base = MEL_INPUT_SAMPLES - CHUNK
        for (i in 0 until CHUNK) raw[base + i] = chunk[i].toFloat()

        // 1) melspectrogram → 8 new frames, transformed and appended.
        mel.run(melIn, melOut)
        val frames = melOut[0][0]
        for (f in 0 until FRAMES_PER_CHUNK) {
            val row = FloatArray(MEL_BINS)
            val src = frames[f]
            for (b in 0 until MEL_BINS) row[b] = src[b] / 10f + 2f
            melBuffer.addLast(row)
        }
        while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()

        // 2) one embedding from the most recent 76 mel frames.
        if (melBuffer.size >= EMBED_WINDOW) {
            val start = melBuffer.size - EMBED_WINDOW
            var idx = 0
            val it = melBuffer.iterator()
            var pos = 0
            while (it.hasNext()) {
                val row = it.next()
                if (pos >= start) {
                    val dst = embIn[0][idx]
                    for (b in 0 until MEL_BINS) dst[b][0] = row[b]
                    idx++
                }
                pos++
            }
            emb.run(embIn, embOut)
            featBuffer.addLast(embOut[0][0][0].copyOf())
            while (featBuffer.size > FEAT_BUFFER_MAX) featBuffer.removeFirst()
        }

        // 3) score from the most recent 16 embeddings.
        if (featBuffer.size >= PREDICT_WINDOW) {
            val start = featBuffer.size - PREDICT_WINDOW
            var idx = 0
            var pos = 0
            val it = featBuffer.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (pos >= start) {
                    System.arraycopy(e, 0, wwIn[0][idx], 0, EMBED_DIM)
                    idx++
                }
                pos++
            }
            ww.run(wwIn, wwOut)
            return wwOut[0][0]
        }
        return 0f
    }

    /** Clear streaming state (e.g. after a detection) so old audio can't re-fire. */
    fun reset() {
        raw.fill(0f)
        melBuffer.clear()
        featBuffer.clear()
    }

    fun close() {
        try { melspec?.close() } catch (e: Exception) {}
        try { embedding?.close() } catch (e: Exception) {}
        try { wakeword?.close() } catch (e: Exception) {}
        melspec = null
        embedding = null
        wakeword = null
    }

    private fun interpreter(context: Context, assetPath: String, inputShape: IntArray? = null): Interpreter {
        val options = Interpreter.Options().apply {
            numThreads = 1
            // The XNNPACK delegate failed to apply on these models on-device; the
            // plain CPU kernels are plenty fast for one wake phrase and avoid it.
            setUseXNNPACK(false)
        }
        return Interpreter(loadModel(context, assetPath), options).apply {
            if (inputShape != null) resizeInput(0, inputShape, true) // strict — see init
            allocateTensors()
        }
    }

    private fun loadModel(context: Context, assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    companion object {
        /** Samples per process() call — 80 ms at 16 kHz, openWakeWord's step. */
        const val CHUNK = 1280

        /** Detection fires above this score. Tunable; 0.5 is openWakeWord's default. */
        const val DETECT_THRESHOLD = 0.5f

        // Model/pipeline constants, all fixed by the openWakeWord models.
        private const val MEL_INPUT_SAMPLES = 1760 // 1280 + 3*160 look-back
        private const val FRAMES_PER_CHUNK = 8
        private const val MEL_BINS = 32
        private const val EMBED_WINDOW = 76
        private const val EMBED_DIM = 96
        private const val PREDICT_WINDOW = 16
        private const val MEL_BUFFER_MAX = 970
        private const val FEAT_BUFFER_MAX = 120
    }
}
