package com.jarvis.os.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background catalogue.
 *
 * Small rules, all of them the kind that break silently: an id typo resets
 * everyone who chose that background, a duplicate id makes two entries fight over
 * the same preference, and a theme with no default lands its users on a blank
 * screen. None of that shows up until someone reports "my background changed by
 * itself", by which point the cause is a string.
 */
class BackdropStyleTest {

    @Test
    fun `ten backgrounds, as promised`() {
        assertEquals(10, BackdropStyle.entries.size)
    }

    @Test
    fun `ids are unique and storage-safe`() {
        // These are written to SharedPreferences, so they are permanent. Lower
        // case and no spaces is not fussiness — it is what stops a rename that
        // "looks the same" from silently resetting every user who picked it.
        val ids = BackdropStyle.entries.map { it.id }
        assertEquals("two backgrounds share an id", ids.size, ids.toSet().size)
        ids.forEach {
            assertTrue("'$it' is not a safe preference id", it.matches(Regex("[a-z]+")))
        }
    }

    @Test
    fun `every background is named and described`() {
        BackdropStyle.entries.forEach {
            assertTrue("${it.name} has no display name", it.displayName.isNotBlank())
            assertTrue("${it.name} has no blurb", it.blurb.isNotBlank())
            assertTrue("${it.name}'s blurb is a label, not a description", it.blurb.length > 25)
        }
        val names = BackdropStyle.entries.map { it.displayName }
        assertEquals("two backgrounds share a name", names.size, names.toSet().size)
    }

    @Test
    fun `every theme brings a background with it`() {
        OrbStyle.entries.forEach { style ->
            assertNotNull("$style has no default background", BackdropStyle.defaultFor(style))
        }
    }

    @Test
    fun `no two themes arrive on the same background`() {
        // Each theme was designed around its own world. Two sharing a default
        // would put us back where the themes started — "they all look almost the
        // same" — for anyone who never opens the picker.
        val defaults = OrbStyle.entries.map { BackdropStyle.defaultFor(it) }
        assertEquals("two themes share a default background", defaults.size, defaults.toSet().size)
    }

    @Test
    fun `an empty choice follows the theme`() {
        // "when u select a theme u get the default background which comes with
        // it" — which only keeps working if the stored value means "follow",
        // rather than naming whichever background was showing at the time.
        OrbStyle.entries.forEach { style ->
            assertEquals(BackdropStyle.defaultFor(style), BackdropStyle.resolve("", style))
            assertEquals(BackdropStyle.defaultFor(style), BackdropStyle.resolve(null, style))
        }
    }

    @Test
    fun `an unknown stored id falls back instead of blanking the screen`() {
        // An id outlives the build that wrote it: someone who picked a background
        // that is later removed still has its id on disk. The only acceptable
        // answer is the theme's own.
        assertEquals(
            BackdropStyle.defaultFor(OrbStyle.Nebula),
            BackdropStyle.resolve("a-background-that-was-deleted", OrbStyle.Nebula),
        )
    }

    @Test
    fun `a stored choice overrides the theme`() {
        // The other half of the contract, and the half that makes the picker
        // worth having at all.
        assertEquals(
            BackdropStyle.DeepReef,
            BackdropStyle.resolve(BackdropStyle.DeepReef.id, OrbStyle.Reactor),
        )
        assertEquals(
            BackdropStyle.Blueprint,
            BackdropStyle.resolve(BackdropStyle.Blueprint.id, OrbStyle.Orbit),
        )
    }

    @Test
    fun `every background is reachable through resolve`() {
        BackdropStyle.entries.forEach {
            assertEquals(it, BackdropStyle.resolve(it.id, OrbStyle.Reactor))
        }
    }
}
