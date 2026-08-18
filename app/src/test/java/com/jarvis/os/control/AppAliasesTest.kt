package com.jarvis.os.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user wrote `Cloud means Claude` into their instructions, the model still
 * said `<<OPEN|Cloud>>`, and a Realme phone has a real app called "Cloud" — so
 * the executor scored it an exact match and opened it. These pin the rule that
 * makes the substitution deterministic, and the limits that stop it firing on
 * sentences that have nothing to do with apps.
 */
class AppAliasesTest {

    // --- the case that caused this ------------------------------------------

    @Test
    fun `the rule the user actually typed is honoured`() {
        val aliases = AppAliases.parse(listOf("Cloud means Claude"))

        assertEquals("Claude", AppAliases.resolve("Cloud", aliases))
        // The recogniser's casing is anyone's guess, so matching cannot depend on it.
        assertEquals("Claude", AppAliases.resolve("cloud", aliases))
        assertEquals("Claude", AppAliases.resolve("  CLOUD  ", aliases))
    }

    @Test
    fun `it survives the way people actually say it to the app`() {
        val aliases = AppAliases.parse(listOf("Cloud means Claude"))

        assertEquals("Claude", AppAliases.resolve("the cloud app", aliases))
        assertEquals("Claude", AppAliases.resolve("cloud app", aliases))
    }

    @Test
    fun `anything without a rule passes straight through`() {
        val aliases = AppAliases.parse(listOf("Cloud means Claude"))

        assertEquals("YouTube", AppAliases.resolve("YouTube", aliases))
        assertEquals("Cloud", AppAliases.resolve("Cloud", emptyMap()))
    }

    // --- the phrasings people use --------------------------------------------

    @Test
    fun `the three supported phrasings all work`() {
        assertEquals(
            "Claude",
            AppAliases.resolve("cloud", AppAliases.parse(listOf("when I say cloud I mean Claude"))),
        )
        assertEquals(
            "Claude",
            AppAliases.resolve("cloud", AppAliases.parse(listOf("By cloud I mean Claude"))),
        )
        assertEquals(
            "Amazon Music",
            AppAliases.resolve("chow", AppAliases.parse(listOf("chow = Amazon Music"))),
        )
    }

    @Test
    fun `several rules can share one line`() {
        val aliases = AppAliases.parse(listOf("Cloud means Claude. Insta means Instagram."))

        assertEquals("Claude", AppAliases.resolve("cloud", aliases))
        assertEquals("Instagram", AppAliases.resolve("insta", aliases))
    }

    @Test
    fun `a later rule corrects an earlier one`() {
        val aliases = AppAliases.parse(listOf("cloud means Nextcloud", "cloud means Claude"))

        assertEquals("Claude", AppAliases.resolve("cloud", aliases))
    }

    @Test
    fun `learned facts carry the same rules as typed instructions`() {
        // <<REMEMBER>> stores sentences of exactly this shape, so both sources
        // are parsed by the same code rather than only the box the user can see.
        val aliases = AppAliases.parse(
            listOf("Call me sir.", "when I say jao I mean YouTube"),
        )

        assertEquals("YouTube", AppAliases.resolve("jao", aliases))
    }

    // --- what must NOT become a rule ------------------------------------------

    @Test
    fun `ordinary instructions produce no rules at all`() {
        // Every one of these is real text from the app's own suggestion list or
        // the user's screen. A rule mined out of one would redirect an app launch
        // for no reason the user could ever guess at.
        val aliases = AppAliases.parse(
            listOf(
                "Call me sir.",
                "Keep answers to one sentence unless I ask for detail.",
                "I'm in Bangalore — assume IST for times.",
                "Don't read long lists out loud.",
            ),
        )

        assertTrue("no rules should be found: $aliases", aliases.isEmpty())
    }

    @Test
    fun `X is Y is deliberately not a rule`() {
        // The most natural phrasing, and the most common sentence shape in an
        // instructions box that has nothing to do with apps. Supporting it would
        // turn "my name is Manoj" into an app redirect.
        val aliases = AppAliases.parse(listOf("My name is Manoj", "The office wifi is slow"))

        assertTrue("'is' must not mine a rule: $aliases", aliases.isEmpty())
    }

    @Test
    fun `a sentence that merely contains means is not a rule`() {
        val aliases = AppAliases.parse(
            listOf("Being brief means more to me than being thorough, so keep it short"),
        )

        assertTrue("a long clause is not an app name: $aliases", aliases.isEmpty())
    }

    @Test
    fun `a rule pointing a name at itself is dropped`() {
        val aliases = AppAliases.parse(listOf("Claude means Claude"))

        assertTrue(aliases.isEmpty())
    }

    @Test
    fun `empty input is empty output`() {
        assertEquals(emptyMap<String, String>(), AppAliases.parse(emptyList()))
        assertEquals(emptyMap<String, String>(), AppAliases.parse(listOf("", "   ")))
    }
}
