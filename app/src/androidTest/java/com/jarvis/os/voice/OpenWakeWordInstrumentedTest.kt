package com.jarvis.os.voice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android runtime (emulator in CI). This is the ONLY automated
 * place the openWakeWord TFLite models actually load against the app's real
 * `tensorflow-lite` AAR — i.e. where the on-device `CONV_2D ... BytesRequired
 * overflowed` load crash reproduces. If the runtime can't prepare the graph, this
 * goes red in CI instead of on the user's phone.
 */
@RunWith(AndroidJUnit4::class)
class OpenWakeWordInstrumentedTest {

    private fun newDetector() = OpenWakeWord(ApplicationProvider.getApplicationContext())

    @Test
    fun models_load_on_the_real_android_runtime() {
        val oww = newDetector()
        try {
            assertTrue(
                "openWakeWord models failed to load on the Android runtime — this is the " +
                    "CONV_2D overflow that has been failing on-device.",
                oww.available,
            )
        } finally {
            oww.close()
        }
    }

    @Test
    fun silence_does_not_falsely_wake() {
        val oww = newDetector()
        assumeTrue("models unavailable — already covered by the load test", oww.available)
        try {
            var maxScore = 0f
            repeat(50) { maxScore = maxOf(maxScore, oww.process(ShortArray(OpenWakeWord.CHUNK))) }
            assertTrue("silence should score near zero, was $maxScore", maxScore < 0.1f)
        } finally {
            oww.close()
        }
    }
}
