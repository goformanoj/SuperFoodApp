package com.jarvis.os.desktop.voice

import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The laptop's "hey jarvis" against REAL audio — the repo's own recordings, the same
 * bar as scripts/owwtest/run.py for the phone: a real utterance crosses the threshold,
 * silence stays below 0.1.
 */
class WakeWordDetectorTest {

    companion object {
        private lateinit var detector: WakeWordDetector

        @BeforeClass @JvmStatic fun load() { detector = WakeWordDetector.load() }
        @AfterClass @JvmStatic fun close() { detector.close() }

        /** Repo-relative, whether Gradle runs from the root or from desktop/. */
        private fun fixture(name: String): ShortArray {
            val f = listOf(File("scripts/owwtest/fixtures/$name"), File("../scripts/owwtest/fixtures/$name")).first { it.exists() }
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(b.remaining()).also { b.get(it) }
        }

        private fun peak(pcm: ShortArray): Float {
            detector.reset()
            var best = 0f
            for (i in 0 until pcm.size / WakeWordDetector.CHUNK) {
                val chunk = pcm.copyOfRange(i * WakeWordDetector.CHUNK, (i + 1) * WakeWordDetector.CHUNK)
                best = maxOf(best, detector.process(chunk))
            }
            return best
        }
    }

    @Test
    fun aRealHeyJarvisCrossesTheThreshold() {
        val score = peak(fixture("hey_jarvis.pcm"))
        println("hey_jarvis.pcm peak = $score")
        assertTrue("peak $score should be ≥ ${WakeWordDetector.THRESHOLD}", score >= WakeWordDetector.THRESHOLD)
    }

    @Test
    fun longSilenceNeverFires() {
        // Four copies of the 1 s silence fixture: long enough to fully warm the pipeline,
        // so this really scores silence (a 1 s clip alone never reaches a prediction).
        val one = fixture("silence.pcm")
        val four = ShortArray(one.size * 4) { one[it % one.size] }
        val score = peak(four)
        println("silence x4 peak = $score")
        assertTrue("silence peak $score should be < 0.1", score < 0.1f)
    }
}
