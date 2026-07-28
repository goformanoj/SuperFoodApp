package com.jarvis.os.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePreferenceTest {

    private fun score(
        language: String = "en",
        country: String = "GB",
        name: String = "en-gb-x-gbb#male_1-local",
        quality: Int = 400,
        networkRequired: Boolean = false,
    ) = VoicePreference.score(language, country, name, quality, networkRequired)

    @Test
    fun `non-english voices are rejected outright`() {
        assertEquals(VoicePreference.REJECT, score(language = "hi", country = "IN"))
        assertEquals(VoicePreference.REJECT, score(language = "fr", country = "FR"))
    }

    @Test
    fun `british beats american beats everything else`() {
        val gb = score(country = "GB")
        val us = score(country = "US")
        val india = score(country = "IN")

        assertTrue(gb > us)
        assertTrue(us > india)
    }

    @Test
    fun `higher quality wins, all else equal`() {
        assertTrue(score(quality = 500) > score(quality = 300))
    }

    @Test
    fun `a local voice is preferred over one needing the network`() {
        assertTrue(score(networkRequired = false) > score(networkRequired = true))
    }

    @Test
    fun `a much better network voice can still win over a poor local one`() {
        val goodNetwork = score(quality = 500, networkRequired = true)
        val poorLocal = score(quality = 100, networkRequired = false)

        assertTrue(goodNetwork > poorLocal)
    }

    // --- the trap ---------------------------------------------------------

    @Test
    fun `female is not mistaken for male`() {
        // "female" contains "male", so a naive contains() check picks female
        // voices about half the time.
        assertFalse(VoicePreference.isMale("en-gb-x-gba#female_1-local"))
        assertFalse(VoicePreference.isMale("en-us-x-tpf#female_2-network"))
    }

    @Test
    fun `male voice ids are recognised`() {
        assertTrue(VoicePreference.isMale("en-gb-x-gbb#male_1-local"))
        assertTrue(VoicePreference.isMale("en-us-x-iom#male_3-local"))
    }

    @Test
    fun `a male voice outranks a female one of equal quality and locale`() {
        val male = score(name = "en-gb-x-gbb#male_1-local")
        val female = score(name = "en-gb-x-gba#female_1-local")

        assertTrue(male > female)
    }

    @Test
    fun `an unlabelled voice is not rejected, just unranked for gender`() {
        assertTrue(score(name = "en-gb-x-rjs-local") > VoicePreference.REJECT)
    }

    @Test
    fun `locale case does not matter`() {
        assertEquals(score(country = "GB"), score(country = "gb"))
        assertEquals(score(language = "en"), score(language = "EN"))
    }

    @Test
    fun `the delivery is a little lower and slower than default`() {
        assertTrue("pitch should be below the 1.0 default", VoicePreference.PITCH < 1.0f)
        assertTrue("but not comically deep", VoicePreference.PITCH > 0.7f)
        assertTrue("rate should be near natural", VoicePreference.SPEECH_RATE in 0.85f..1.05f)
    }
}
