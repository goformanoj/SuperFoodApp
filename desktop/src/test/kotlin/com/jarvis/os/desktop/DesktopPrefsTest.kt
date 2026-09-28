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
    fun geometryRoundTripsAndDoesNotClobberTheTheme() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.save(DesktopPrefs.Appearance(JarvisPalette.Nebula, ""))
        val g = DesktopPrefs.Geometry(1400f, 820f, 40f, 30f, maximized = true)
        prefs.saveGeometry(g)
        assertEquals(g, prefs.loadGeometry())
        assertEquals(JarvisPalette.Nebula, prefs.load().palette)
    }

    @Test
    fun noGeometryMeansTheDefaultCentredWindow() {
        assertEquals(DesktopPrefs.Geometry.DEFAULT, DesktopPrefs(tmp.root.resolve("p.properties")).loadGeometry())
    }

    @Test
    fun aTinySavedWindowIsGrownToUsable() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.saveGeometry(DesktopPrefs.Geometry(100f, 50f, null, null, false))
        val g = prefs.loadGeometry()
        assertEquals(900f, g.width)
        assertEquals(600f, g.height)
    }

    @Test
    fun flagsDefaultOffAndRoundTrip() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        assertEquals(false, prefs.flag("voice.wakeword"))
        prefs.setFlag("voice.wakeword", true)
        assertEquals(true, prefs.flag("voice.wakeword"))
        assertEquals(JarvisPalette.Default, prefs.load().palette) // other keys untouched
    }

    @Test
    fun unknownIdsFallBackInsteadOfCrashing() {
        val f = tmp.root.resolve("p.properties").apply { writeText("theme=gone\nbackdrop=also-gone\n") }
        val a = DesktopPrefs(f).load()
        assertEquals(JarvisPalette.Default, a.palette)
        assertEquals(BackdropStyle.defaultFor(JarvisPalette.Default.orbStyle), a.backdrop)
    }
    @Test
    fun theQuickBarReopensWhereAndHowBigItWasLeftWithinSaneBounds() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        assertEquals(DesktopPrefs.QuickGeometry.DEFAULT, prefs.loadQuick())
        prefs.saveQuick(DesktopPrefs.QuickGeometry(900f, 500f, 120f, 80f))
        assertEquals(DesktopPrefs.QuickGeometry(900f, 500f, 120f, 80f), prefs.loadQuick())
        // A corrupt or absurd size is clamped, never restored unusable.
        prefs.saveQuick(DesktopPrefs.QuickGeometry(5f, 99999f, 1f, 1f))
        assertEquals(DesktopPrefs.QuickGeometry.MIN_W, prefs.loadQuick().width)
        assertEquals(DesktopPrefs.QuickGeometry.MAX_H, prefs.loadQuick().height)
    }
}
