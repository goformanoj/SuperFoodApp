package com.jarvis.os.voice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs on a real Android runtime (emulator in CI) — the ONLY automated place the
 * openWakeWord TFLite models load against the app's real `tensorflow-lite` AAR
 * (where the on-device `CONV_2D ... BytesRequired overflowed` crash reproduces) AND
 * where detection can be exercised end-to-end on a bundled recording.
 *
 * The fixture `assets/hey_jarvis.pcm` is one of the user's real "Hey Jarvis"
 * recordings, decoded to 16 kHz mono little-endian int16 PCM.
 */
@RunWith(AndroidJUnit4::class)
class OpenWakeWordInstrumentedTest {

    private fun newDetector() = OpenWakeWord(ApplicationProvider.getApplicationContext())

    @Test
    fun models_load_on_the_real_android_runtime() {
        val oww = newDetector()
        try {
            assertTrue(
                "openWakeWord models failed to load on the Android runtime. " +
                    "Actual cause: ${oww.lastLoadError}",
                oww.available,
            )
        } finally {
            oww.close()
        }
    }

    @Test
    fun detects_a_real_hey_jarvis_recording() {
        val oww = newDetector()
        assumeTrue("models unavailable — already covered by the load test", oww.available)
        try {
            val pcm = readPcmAsset("hey_jarvis.pcm")
            var peak = 0f
            var i = 0
            while (i + OpenWakeWord.CHUNK <= pcm.size) {
                peak = maxOf(peak, oww.process(pcm.copyOfRange(i, i + OpenWakeWord.CHUNK)))
                i += OpenWakeWord.CHUNK
            }
            assertTrue(
                "a real 'Hey Jarvis' recording should cross DETECT_THRESHOLD " +
                    "(${OpenWakeWord.DETECT_THRESHOLD}); peak was $peak",
                peak >= OpenWakeWord.DETECT_THRESHOLD,
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

    /** Reads a raw 16 kHz mono little-endian int16 PCM asset from the test APK. */
    private fun readPcmAsset(name: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).use { it.readBytes() }
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }
}
