package com.jarvis.os.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Pure-JVM guard for the Kotlin melspectrogram: it reads the shipped weights blob
 * straight off disk (no Android runtime needed) and checks the layout and the math.
 * The full numeric fidelity vs the original TFLite model is verified in Python
 * (`scripts/owwtest/run.py`, same weights) and end-to-end on the emulator; this test
 * exists so a wrong blob size or a broken frame loop fails fast in CI.
 */
class MelSpectrogramTest {

    private fun weights(): ByteBuffer {
        // Unit tests run with the module dir as CWD; fall back to the repo-root path.
        val candidates = listOf(
            "src/main/assets/openwakeword/melspec_weights.bin",
            "app/src/main/assets/openwakeword/melspec_weights.bin",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("melspec_weights.bin not found (cwd=${File(".").absolutePath})")
        return ByteBuffer.wrap(file.readBytes())
    }

    @Test
    fun `weights blob has the expected float count`() {
        val floats = weights().remaining() / 4
        assertEquals(MelSpectrogram.WEIGHTS_FLOATS.toLong(), floats.toLong())
    }

    @Test
    fun `a 1760-sample window yields 8 frames of 32 finite bins`() {
        val mel = MelSpectrogram(weights())
        // A 400 Hz tone at 16 kHz, int16 scale (not normalised) — the models' scale.
        val raw = FloatArray(MelSpectrogram.MEL_INPUT_SAMPLES) {
            (3000.0 * Math.sin(2.0 * Math.PI * 400.0 * it / 16000.0)).toFloat()
        }
        val out = mel.process(raw)
        assertEquals(MelSpectrogram.FRAMES, out.size)
        for (row in out) {
            assertEquals(MelSpectrogram.MEL_BINS, row.size)
            for (v in row) assertTrue("mel value must be finite, was $v", v.isFinite())
        }
    }

    @Test
    fun `power-to-db floor holds — no value is more than 80 below the block max`() {
        val mel = MelSpectrogram(weights())
        val raw = FloatArray(MelSpectrogram.MEL_INPUT_SAMPLES) {
            (5000.0 * Math.sin(2.0 * Math.PI * 220.0 * it / 16000.0)).toFloat()
        }
        val out = mel.process(raw)
        val max = out.maxOf { row -> row.max() }
        val min = out.minOf { row -> row.min() }
        // librosa top_db = 80: nothing sits below max - 80 (small epsilon for floats).
        assertTrue("min $min below max $max - 80", min >= max - 80f - 1e-3f)
        // A real tone must have some spread — not every bin pinned to the floor.
        assertTrue("tone produced a flat spectrum (max=$max min=$min)", max - min > 1f)
    }
}
