package com.jarvis.os.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * From a device trace, the reply that caused this:
 *
 * ```
 * **UI testing (sometimes called "up testing" by mistake)** checks that…
 *   * **Espresso** – Write concise, readable tests…
 * ```
 *
 * Android's text-to-speech says "asterisk" for every one of those. The user's
 * words: "the model starts reading asterisk out loud which ruins it".
 */
class SpokenTextTest {

    // --- the reply that caused this ------------------------------------------

    @Test
    fun `no asterisk survives the real trace reply`() {
        val reply = """
            Sure thing!

            **UI testing (sometimes called "up testing" by mistake)** checks that the app's
            screens, buttons, and user flows work the way users expect.

            **How to test Android apps**

            1. **Unit tests** – Test individual classes with JUnit.
            2. **Instrumentation/UI tests** – Run on a real device.
               * **Espresso** – simulate clicks, typing, scrolling.
        """.trimIndent()

        val out = SpokenText.plain(reply)

        assertFalse("an asterisk would be read aloud: $out", out.contains("*"))
        assertFalse("a backtick would be read aloud: $out", out.contains("`"))
        // The words themselves must all survive.
        listOf("UI testing", "Espresso", "Unit tests", "Instrumentation").forEach {
            assertEquals("lost \"$it\"", true, out.contains(it))
        }
    }

    @Test
    fun `numbers in a list are kept`() {
        // A spoken list that counts is easier to follow, not harder — only the
        // bullet glyphs go.
        val out = SpokenText.plain("1. First thing\n2. Second thing")

        assertEquals("1. First thing\n2. Second thing", out)
    }

    // --- each kind of formatting ---------------------------------------------

    @Test
    fun `bold and italic keep their words`() {
        assertEquals("really important", SpokenText.plain("**really** *important*"))
        assertEquals("really important", SpokenText.plain("__really__ _important_"))
    }

    @Test
    fun `headings lose the hashes and keep the heading`() {
        assertEquals("How to test", SpokenText.plain("## How to test"))
    }

    @Test
    fun `bullets lose the glyph and keep the item`() {
        assertEquals("Espresso\nUI Automator", SpokenText.plain("* Espresso\n- UI Automator"))
    }

    @Test
    fun `inline code keeps the code and drops the backticks`() {
        assertEquals("Run gradlew test now", SpokenText.plain("Run `gradlew test` now"))
    }

    @Test
    fun `a link is spoken as its label, never its URL`() {
        // Hearing a URL read character by character is worse than not hearing it.
        assertEquals(
            "See the docs for details",
            SpokenText.plain("See [the docs](https://example.com/a/b?c=d) for details"),
        )
    }

    @Test
    fun `a code fence disappears but its contents stay`() {
        assertEquals("gradlew test", SpokenText.plain("```sh\ngradlew test\n```"))
    }

    // --- what must NOT be touched ---------------------------------------------

    @Test
    fun `ordinary prose is returned unchanged`() {
        val plain = "The back end is the part that runs on servers, handling data storage."

        assertEquals(plain, SpokenText.plain(plain))
    }

    @Test
    fun `an underscore inside a word is left alone`() {
        // snake_case is a word, not italics. Removing the underscore changes what
        // was said, which is worse than reading one stray symbol.
        assertEquals("the file is user_prefs today", SpokenText.plain("the file is user_prefs today"))
    }

    @Test
    fun `paragraph breaks survive`() {
        // Speaker pauses at them, and that pause is the difference between a list
        // and a run-on sentence.
        val out = SpokenText.plain("First point.\n\nSecond point.")

        assertEquals("First point.\n\nSecond point.", out)
    }

    @Test
    fun `blank input is returned untouched`() {
        assertEquals("", SpokenText.plain(""))
        assertEquals("   ", SpokenText.plain("   "))
    }

    @Test
    fun `an unbalanced marker is still removed`() {
        // The model does emit these. Left in, it is the exact bug being fixed.
        assertFalse(SpokenText.plain("**Almost bold").contains("*"))
    }
}
