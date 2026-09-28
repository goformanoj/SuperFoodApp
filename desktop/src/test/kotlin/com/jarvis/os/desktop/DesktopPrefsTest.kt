package com.jarvis.os.desktop

import com.jarvis.os.ui.theme.BackdropStyle
import com.jarvis.os.ui.theme.JarvisPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopPrefsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun noFileMeansTheDefaultThemeAndItsOwnWorld() {
        val a = DesktopPrefs(tmp.root.resolve("p.properties")).load()
        assertEquals(JarvisPalette.Default, a.palette)
        assertEquals(BackdropStyle.defaultFor(JarvisPalette.Default.orbStyle), a.backdrop)
    }

    @Test
    fun roundTripsThemeAndBackdrop() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        val chosen = BackdropStyle.entries.last()
        prefs.save(DesktopPrefs.Appearance(JarvisPalette.Forge, chosen.id))
        val back = prefs.load()
        assertEquals(JarvisPalette.Forge, back.palette)
        assertEquals(chosen, back.backdrop)
    }

    @Test
    fun blankBackdropFollowsTheTheme() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.save(DesktopPrefs.Appearance(JarvisPalette.Orbit, ""))
        assertEquals(BackdropStyle.defaultFor(JarvisPalette.Orbit.orbStyle), prefs.load().backdrop)
    }

    @Test
    fun unknownIdsFallBackInsteadOfCrashing() {
        val f = tmp.root.resolve("p.properties").apply { writeText("theme=gone\nbackdrop=also-gone\n") }
        val a = DesktopPrefs(f).load()
        assertEquals(JarvisPalette.Default, a.palette)
        assertEquals(BackdropStyle.defaultFor(JarvisPalette.Default.orbStyle), a.backdrop)
    }
}
