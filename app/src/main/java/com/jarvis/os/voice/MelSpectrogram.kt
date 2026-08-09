package com.jarvis.os.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * openWakeWord's melspectrogram, recomputed in pure Kotlin.
 *
 * WHY NOT THE TFLITE MODEL. `melspectrogram.tflite` has a dynamic-length audio
 * input, and every TensorFlow-Lite / LiteRT runtime shipped as an Android AAR
 * (tensorflow-lite 2.16.1 / 2.17.0, LiteRT 1.0.0 / 2.1.0) overflows a CONV_2D's
 * byte count while allocating tensors for it once the input is resized
 * ("BytesRequired number of elements overflowed. Node number 3 (CONV_2D) failed to
 * prepare") — a shape-inference bug in those runtimes, caught on the CI emulator
 * with no device. The other two models (embedding, wake word) have fixed shapes and
 * load fine, so only this step moves to Kotlin.
 *
 * WHAT IT COMPUTES. The exact librosa power-to-db mel spectrogram the model does,
 * using the model's OWN weights (extracted by `scripts/owwtest/extract_melspec_weights.py`
 * into `assets/openwakeword/melspec_weights.bin`): frame the audio (window 512, hop
 * 160, valid), take a 512-tap DFT via the cos/sin bases into [FREQ_BINS] bins,
 * power = re^2 + im^2, project onto the [FREQ_BINS x MEL_BINS] mel matrix, then
 * `10*log10(max(., 1e-10))` clamped to `max(., globalMax - 80)`. The conv bias is
 * all zeros so it is omitted. Reproducing this in numpy matched the model to ~1.5e-5
 * and gave identical end-to-end detection (0.998 / 0.000) before it was ported.
 *
 * A [MEL_INPUT_SAMPLES]-sample window yields [FRAMES] mel frames of [MEL_BINS] bins.
 */
class MelSpectrogram(weights: ByteBuffer) {

    // cos[bin][tap], sin[bin][tap] flattened row-major; mel[bin][melBin] flattened.
    private val cos = FloatArray(FREQ_BINS * WIN)
    private val sin = FloatArray(FREQ_BINS * WIN)
    private val mel = FloatArray(FREQ_BINS * MEL_BINS)

    init {
        val f = weights.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        f.get(cos)
        f.get(sin)
        f.get(mel)
    }

    /**
     * [raw] must hold exactly [MEL_INPUT_SAMPLES] samples (int16 values as float, the
     * scale the models were trained on). Returns [FRAMES] rows of [MEL_BINS] — the
     * same tensor the TFLite melspectrogram produced, so the caller's `x/10 + 2`
     * transform and the downstream models are unchanged.
     */
    fun process(raw: FloatArray): Array<FloatArray> {
        val out = Array(FRAMES) { FloatArray(MEL_BINS) }
        val power = FloatArray(FREQ_BINS)
        var globalMax = Float.NEGATIVE_INFINITY

        for (frame in 0 until FRAMES) {
            val offset = frame * HOP
            // 512-tap DFT via the cos/sin bases → power spectrum.
            for (bin in 0 until FREQ_BINS) {
                val base = bin * WIN
                var re = 0f
                var im = 0f
                for (t in 0 until WIN) {
                    val s = raw[offset + t]
                    re += s * cos[base + t]
                    im += s * sin[base + t]
                }
                power[bin] = re * re + im * im
            }
            // Project onto the mel filterbank, then power-to-db (log10 part).
            val row = out[frame]
            for (m in 0 until MEL_BINS) {
                var acc = 0f
                for (bin in 0 until FREQ_BINS) acc += power[bin] * mel[bin * MEL_BINS + m]
                val db = 10f * log10(maxOf(acc, AMIN))
                row[m] = db
                if (db > globalMax) globalMax = db
            }
        }

        // power-to-db floor: clamp to globalMax - TOP_DB across the whole block.
        val floor = globalMax - TOP_DB
        for (frame in 0 until FRAMES) {
            val row = out[frame]
            for (m in 0 until MEL_BINS) if (row[m] < floor) row[m] = floor
        }
        return out
    }

    private fun log10(x: Float): Float = (Math.log10(x.toDouble())).toFloat()

    companion object {
        const val MEL_INPUT_SAMPLES = 1760 // 1280 chunk + 3*160 look-back
        const val MEL_BINS = 32
        const val FRAMES = 8 // (1760 - 512) / 160 + 1

        private const val WIN = 512 // DFT / frame length
        private const val HOP = 160 // 10 ms at 16 kHz
        private const val FREQ_BINS = 257 // WIN / 2 + 1
        private const val AMIN = 1e-10f // librosa amin (log floor)
        private const val TOP_DB = 80f // librosa top_db (dynamic-range clamp)

        /** float32 count of the weights blob: cos + sin + mel. */
        const val WEIGHTS_FLOATS = FREQ_BINS * WIN * 2 + FREQ_BINS * MEL_BINS
    }
}
