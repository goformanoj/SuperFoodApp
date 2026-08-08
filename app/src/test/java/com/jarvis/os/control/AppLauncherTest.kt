package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLauncherTest {

    private fun pick(query: String, labels: List<String>): String? =
        AppLauncher.rank(query, labels)?.let { labels[it] }

    @Test
    fun `amazon music does not open amazon the shop or a music app`() {
        // The exact bug from the device trace.
        val installed = listOf("Amazon Shopping", "Music", "Amazon Music", "Chrome")
        assertEquals("Amazon Music", pick("Amazon Music", installed))
    }

    @Test
    fun `plain amazon still opens the shop`() {
        assertEquals("Amazon", pick("Amazon", listOf("Amazon Music", "Amazon")))
    }

    @Test
    fun `plain music opens the music app, not amazon music`() {
        assertEquals("Music", pick("music", listOf("Amazon Music", "Music")))
    }

    @Test
    fun `exact name always wins over a longer variant`() {
        assertEquals("WhatsApp", pick("whatsapp", listOf("WhatsApp Business", "WhatsApp")))
        assertEquals("YouTube", pick("youtube", listOf("YouTube Music", "YouTube")))
    }

    @Test
    fun `covering more spoken words wins`() {
        assertEquals("YouTube Music", pick("youtube music", listOf("YouTube", "YouTube Music")))
    }

    @Test
    fun `a prefix nickname resolves`() {
        assertEquals("Instagram", pick("insta", listOf("Telegram", "Instagram")))
    }

    @Test
    fun `filler words are ignored`() {
        assertEquals("Amazon Music", pick("amazon music app", listOf("Amazon Shopping", "Amazon Music")))
    }

    @Test
    fun `nothing plausible resolves to null, not a random app`() {
        assertNull(pick("spotify", listOf("WhatsApp", "Chrome", "Phone")))
        assertNull(pick("", listOf("WhatsApp")))
    }

    @Test
    fun `case and spacing do not matter`() {
        assertEquals("WhatsApp", pick("  WHATSAPP  ", listOf("Chrome", "WhatsApp")))
    }
}
