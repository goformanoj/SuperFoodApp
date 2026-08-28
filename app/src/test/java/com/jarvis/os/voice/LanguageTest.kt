package com.jarvis.os.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract of the two-language preference.
 *
 * The interpretation itself is the model's and the recogniser's, neither of which
 * runs here — so what is pinned is everything AROUND them: that a selection can
 * never produce an illegal pair, that a stored tag survives a round trip, that an
 * unknown tag from an old build falls through instead of crashing, and that the
 * prompt line says the one thing that makes the feature work (reply in the
 * language you were spoken to).
 */
class LanguageTest {

    // ---- the enum ----------------------------------------------------------

    @Test
    fun `there are exactly the five chosen languages`() {
        assertEquals(
            listOf("English", "Hindi", "Arabic", "French", "German"),
            Language.entries.map { it.label },
        )
    }

    @Test
    fun `a tag resolves to its language regardless of region`() {
        assertEquals(Language.English, Language.fromTag("en"))
        assertEquals(Language.English, Language.fromTag("en-GB"))
        assertEquals(Language.English, Language.fromTag("en-US"))
        assertEquals(Language.Hindi, Language.fromTag("hi-IN"))
        assertEquals(Language.Arabic, Language.fromTag("ar-EG"))
        assertEquals(Language.German, Language.fromTag("de"))
    }

    @Test
    fun `an unknown or blank tag is not one of ours`() {
        assertNull(Language.fromTag("es-ES"))
        assertNull(Language.fromTag("zh"))
        assertNull(Language.fromTag(""))
        assertNull(Language.fromTag(null))
    }

    @Test
    fun `every language carries its own name in its own script`() {
        // The settings list shows the endonym so a speaker recognises their own;
        // an empty one would defeat the entire point of listing it.
        Language.entries.forEach {
            assertTrue("${it.label} has no endonym", it.endonym.isNotBlank())
            assertTrue("${it.label} tag is not region-qualified", it.tag.contains('-'))
        }
    }

    // ---- selection can never produce an illegal pair -----------------------

    @Test
    fun `no selection at all falls back to the default`() {
        assertEquals(LanguagePrefs.DEFAULT, LanguagePrefs.of(emptyList()))
        assertEquals(Language.English, LanguagePrefs.of(emptyList()).primary)
        assertNull(LanguagePrefs.of(emptyList()).secondary)
    }

    @Test
    fun `one language is a monolingual preference`() {
        val p = LanguagePrefs.of(listOf(Language.Hindi))
        assertEquals(Language.Hindi, p.primary)
        assertNull(p.secondary)
        assertFalse(p.isBilingual)
        assertEquals(listOf(Language.Hindi), p.understood)
    }

    @Test
    fun `more than two languages is capped at two, in priority order`() {
        val p = LanguagePrefs.of(
            listOf(Language.French, Language.German, Language.Arabic, Language.Hindi),
        )
        assertEquals(Language.French, p.primary)
        assertEquals(Language.German, p.secondary)
        assertEquals(2, p.understood.size)
    }

    @Test
    fun `a repeated language never becomes an illegal same-same pair`() {
        val p = LanguagePrefs.of(listOf(Language.Arabic, Language.Arabic))
        assertEquals(Language.Arabic, p.primary)
        assertNull(p.secondary)
    }

    @Test
    fun `understood is always primary-first, non-empty and at most two`() {
        listOf(
            LanguagePrefs.of(emptyList()),
            LanguagePrefs.of(listOf(Language.German)),
            LanguagePrefs.of(listOf(Language.German, Language.English)),
        ).forEach { p ->
            assertTrue(p.understood.isNotEmpty())
            assertTrue(p.understood.size <= 2)
            assertEquals(p.primary, p.understood.first())
        }
    }

    // ---- storage round trip ------------------------------------------------

    @Test
    fun `a preference survives a store-and-load round trip`() {
        listOf(
            LanguagePrefs.of(listOf(Language.Hindi, Language.English)),
            LanguagePrefs.of(listOf(Language.French)),
            LanguagePrefs.DEFAULT,
        ).forEach { p ->
            assertEquals(p, LanguagePrefs.parse(p.serialize()))
        }
    }

    @Test
    fun `parsing tolerates blank and unknown stored values`() {
        assertEquals(LanguagePrefs.DEFAULT, LanguagePrefs.parse(null))
        assertEquals(LanguagePrefs.DEFAULT, LanguagePrefs.parse(""))
        assertEquals(LanguagePrefs.DEFAULT, LanguagePrefs.parse("es-ES,zh-CN"))
        // A known primary with an unknown secondary keeps the known one only.
        assertEquals(
            LanguagePrefs.of(listOf(Language.Hindi)),
            LanguagePrefs.parse("hi-IN,xx-YY"),
        )
    }

    @Test
    fun `the stored form is the ordered tags`() {
        assertEquals("hi-IN,fr-FR", LanguagePrefs.of(listOf(Language.Hindi, Language.French)).serialize())
        assertEquals("en-US", LanguagePrefs.DEFAULT.serialize())
    }

    // ---- the prompt line ---------------------------------------------------

    @Test
    fun `a monolingual prompt pins the one language for both understanding and reply`() {
        val line = languagePromptLine(LanguagePrefs.of(listOf(Language.German)))
        assertTrue(line.contains("German"))
        assertTrue("must fix the reply language", line.contains("reply in German"))
    }

    @Test
    fun `a bilingual prompt names both and replies in the language last used`() {
        val line = languagePromptLine(LanguagePrefs.of(listOf(Language.English, Language.Hindi)))
        assertTrue(line.contains("English"))
        assertTrue(line.contains("Hindi"))
        // The load-bearing instruction: answer in the language the user used.
        assertTrue(line.lowercase().contains("used"))
    }

    // ---- which language to SPEAK a reply in, from its script ---------------

    @Test
    fun `a Devanagari reply is spoken in Hindi when Hindi is chosen`() {
        val prefs = LanguagePrefs.of(listOf(Language.English, Language.Hindi))
        assertEquals(Language.Hindi, spokenLanguageFor("नमस्ते, कैसे हैं आप", prefs))
    }

    @Test
    fun `an Arabic reply is spoken in Arabic when Arabic is chosen`() {
        val prefs = LanguagePrefs.of(listOf(Language.English, Language.Arabic))
        assertEquals(Language.Arabic, spokenLanguageFor("مرحبا كيف حالك", prefs))
    }

    @Test
    fun `a Latin reply falls back to the primary, never a wrong script`() {
        val prefs = LanguagePrefs.of(listOf(Language.French, Language.English))
        assertEquals(Language.French, spokenLanguageFor("Bonjour, comment ca va", prefs))
    }

    @Test
    fun `a script the user did not choose is not spoken in that language`() {
        // The model should not produce it, but if a stray Devanagari glyph appears
        // and the user never chose Hindi, the primary voice speaks — not a Hindi
        // voice the user never asked for.
        val prefs = LanguagePrefs.of(listOf(Language.English, Language.German))
        assertEquals(Language.English, spokenLanguageFor("Hello नमस्ते", prefs))
    }

    @Test
    fun `an empty reply is spoken in the primary`() {
        assertEquals(Language.English, spokenLanguageFor("", LanguagePrefs.DEFAULT))
    }
}
