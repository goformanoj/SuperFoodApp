package com.jarvis.os.desktop.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {

    @Test
    fun headerDescribes16kMono16bitAndTheDataSize() {
        val pcm = ByteArray(32_000) // one second
        val w = ByteBuffer.wrap(Wav.encode(pcm)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(Wav.encode(pcm).copyOfRange(0, 4)))
        assertEquals("WAVE", String(Wav.encode(pcm).copyOfRange(8, 12)))
        assertEquals(36 + 32_000, w.getInt(4))
        assertEquals(1, w.getShort(20).toInt())      // PCM
        assertEquals(1, w.getShort(22).toInt())      // mono
        assertEquals(16_000, w.getInt(24))           // sample rate
        assertEquals(32_000, w.getInt(28))           // byte rate — what the Worker divides by
        assertEquals(16, w.getShort(34).toInt())     // bits
        assertEquals(32_000, w.getInt(40))           // data size
        assertEquals(44 + 32_000, Wav.encode(pcm).size)
        assertEquals(1.0, Wav.seconds(pcm.size), 1e-9)
    }

    private fun tone(amplitude: Short, samples: Int): ByteArray {
        val b = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { i -> b.putShort(if (i % 2 == 0) amplitude else (-amplitude).toShort()) }
        return b.array()
    }

    @Test
    fun levelIsZeroForSilenceAndScalesWithAmplitude() {
        assertEquals(0f, Wav.level(ByteArray(3200)), 1e-6f)
        assertEquals(0.5f, Wav.level(tone(16384, 1600)), 0.001f)
        assertTrue(Wav.level(tone(3000, 1600)) < Wav.level(tone(12000, 1600)))
    }

    @Test
    fun silenceBeforeSpeechNeverEndsTheClip() {
        val e = Wav.Endpointer()
        repeat(40) { assertFalse(e.feed(0f, 100)) } // 4 s of waiting to speak
    }

    @Test
    fun speechThenAPauseEndsTheClip() {
        val e = Wav.Endpointer(silenceMs = 1_000)
        repeat(8) { assertFalse(e.feed(0.2f, 100)) }  // 0.8 s speaking
        repeat(9) { assertFalse(e.feed(0.001f, 100)) } // 0.9 s quiet: not yet
        assertTrue(e.feed(0.001f, 100))                // 1.0 s quiet: done
    }

    @Test
    fun aShortBlipIsNotSpeech() {
        val e = Wav.Endpointer(minSpeechMs = 400, silenceMs = 500)
        e.feed(0.3f, 100) // a click, 0.1 s
        repeat(20) { assertFalse(e.feed(0f, 100)) }
        assertFalse(e.heardSpeech)
    }

    @Test
    fun theCapAlwaysEndsIt() {
        val e = Wav.Endpointer(maxMs = 1_000)
        repeat(9) { assertFalse(e.feed(0.5f, 100)) }
        assertTrue(e.feed(0.5f, 100))
    }
}
