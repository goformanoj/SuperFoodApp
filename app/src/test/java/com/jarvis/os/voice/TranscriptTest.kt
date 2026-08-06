package com.jarvis.os.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    @Test
    fun `surfaces the near-homophone the user actually said`() {
        // The exact failure from the trace: "peak" heard as "pic".
        val words = Transcript.alternativeWords(
            listOf(
                "play my playlist called pic",
                "play my playlist called peak",
                "play my playlist called pick",
            ),
        )
        assertEquals(listOf("peak", "pick"), words)
    }

    @Test
    fun `hint quotes the alternatives`() {
        val hint = Transcript.disambiguationHint(
            listOf("play my playlist called pic", "play my playlist called peak"),
        )
        assertEquals("\"peak\"", hint)
    }

    @Test
    fun `a single guess offers nothing`() {
        assertEquals(emptyList<String>(), Transcript.alternativeWords(listOf("open whatsapp")))
        assertNull(Transcript.disambiguationHint(listOf("open whatsapp")))
    }

    @Test
    fun `no alternatives when the guesses agree after trimming`() {
        assertNull(
            Transcript.disambiguationHint(
                listOf("set an alarm", "set an alarm", "  set an alarm  "),
            ),
        )
    }

    @Test
    fun `empty input is handled`() {
        assertEquals(emptyList<String>(), Transcript.alternativeWords(emptyList()))
        assertNull(Transcript.disambiguationHint(emptyList()))
    }

    @Test
    fun `ignores a wholesale different sentence of another length`() {
        // A lower-ranked guess with a different word count is a different guess,
        // not a swapped word — it should not leak in as a "suggestion".
        val words = Transcript.alternativeWords(
            listOf(
                "call mom",
                "call moms and dad",
            ),
        )
        assertEquals(emptyList<String>(), words)
    }

    @Test
    fun `ignores when more than two words differ`() {
        val words = Transcript.alternativeWords(
            listOf(
                "turn on the kitchen light",
                "burn off my chicken tonight",
            ),
        )
        assertEquals(emptyList<String>(), words)
    }

    @Test
    fun `handles a single misheard word utterance`() {
        val words = Transcript.alternativeWords(listOf("pic", "peak", "pick"))
        assertEquals(listOf("peak", "pick"), words)
    }

    @Test
    fun `common homophones are recognised as confusable`() {
        assertEquals(listOf("two"), Transcript.alternativeWords(listOf("send it to him", "send it two him")))
        assertEquals(listOf("their"), Transcript.alternativeWords(listOf("open there app", "open their app")))
    }

    @Test
    fun `punctuation on an alternative does not leak into the suggestion`() {
        val words = Transcript.alternativeWords(listOf("play peak", "play peak,"))
        // The only difference is trailing punctuation on the same word, so after
        // trimming there is nothing meaningfully different to offer.
        assertEquals(emptyList<String>(), words)
    }

    @Test
    fun `caps the number of suggestions`() {
        val hypotheses = buildList {
            add("aa bb cc dd ee")
            add("zz bb cc dd ee")
            add("aa yy cc dd ee")
            add("aa bb xx dd ee")
            add("aa bb cc ww ee")
            add("aa bb cc dd vv")
        }
        val words = Transcript.alternativeWords(hypotheses)
        assertTrue("expected at most 4 suggestions, got ${words.size}", words.size <= 4)
    }
}
