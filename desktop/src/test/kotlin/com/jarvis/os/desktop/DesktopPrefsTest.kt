package com.jarvis.os.desktop

import com.jarvis.os.ui.theme.JarvisPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Rectangle

class DesktopPrefsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun noFileMeansTheDefaultThemeAndItsOwnWorld() {
        val a = DesktopPrefs(tmp.root.resolve("p.properties")).load()
        assertEquals(JarvisPalette.Default, a.palette)
        assertEquals(DesktopWorld.ownFor(JarvisPalette.Default), a.desktopWorld)
    }

    @Test
    fun roundTripsThemeAndBackdrop() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        val chosen = DesktopWorld.entries.last()
        prefs.save(DesktopPrefs.Appearance(JarvisPalette.Forge, chosen.id))
        val back = prefs.load()
        assertEquals(JarvisPalette.Forge, back.palette)
        assertEquals(chosen, back.desktopWorld)
    }

    @Test
    fun blankBackdropFollowsTheTheme() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.save(DesktopPrefs.Appearance(JarvisPalette.Orbit, ""))
        assertEquals(DesktopWorld.Orbital, prefs.load().desktopWorld)
    }

    @Test
    fun everyThemeBringsItsOwnLaptopWorldAndANamedChoiceWins() {
        assertEquals(DesktopWorld.Deck, DesktopPrefs.Appearance(JarvisPalette.Stark, "").desktopWorld)
        assertEquals(DesktopWorld.ReactorHall, DesktopPrefs.Appearance(JarvisPalette.Arc, "").desktopWorld)
        assertEquals(DesktopWorld.Foundry, DesktopPrefs.Appearance(JarvisPalette.Forge, "").desktopWorld)
        assertEquals(DesktopWorld.Skyline, DesktopPrefs.Appearance(JarvisPalette.Holo, "").desktopWorld)
        // Picked on purpose under another theme.
        assertEquals(DesktopWorld.Orbital, DesktopPrefs.Appearance(JarvisPalette.Forge, DesktopWorld.Orbital.id).desktopWorld)
    }

    @Test
    fun anUnrecognisedStoredWorldFallsBackToTheThemesOwnNeverABlankScreen() {
        // The ids of the phone's old backdrops (still in a prefs file written by an earlier build), and junk.
        assertEquals(DesktopWorld.Deck, DesktopPrefs.Appearance(JarvisPalette.Stark, "canyon").desktopWorld)
        assertEquals(DesktopWorld.ReactorHall, DesktopPrefs.Appearance(JarvisPalette.Arc, "no-such-world").desktopWorld)
    }

    @Test
    fun worldIdsAreUniqueAndRoundTrip() {
        val ids = DesktopWorld.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        DesktopWorld.entries.forEach { assertEquals(it, DesktopWorld.fromId(it.id)) }
    }

    @Test
    fun everyThemeHasADistinctOwnWorld() {
        val own = JarvisPalette.entries.map { DesktopWorld.ownFor(it) }
        assertEquals("two themes sharing a world would be recolours of each other", own.size, own.toSet().size)
    }

    @Test
    fun theStarkThemeSurvivesARoundTrip() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.save(DesktopPrefs.Appearance(JarvisPalette.Stark, DesktopWorld.Foundry.id))
        assertEquals(DesktopPrefs.Appearance(JarvisPalette.Stark, DesktopWorld.Foundry.id), prefs.load())
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
    fun aPositionOffEveryConnectedScreenIsDiscardedNotRestored() {
        // e.g. the window was last closed on a second monitor that's since been unplugged —
        // reopening at that saved spot would put almost the whole window off the real desktop,
        // leaving just the title bar visible (the bug this guards against).
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.saveGeometry(DesktopPrefs.Geometry(1280f, 760f, 5000f, 5000f, false))
        val g = prefs.loadGeometry { listOf(Rectangle(0, 0, 1920, 1080)) }
        assertNull(g.x)
        assertNull(g.y)
    }

    @Test
    fun aPositionStillWithinAKnownScreenIsKept() {
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.saveGeometry(DesktopPrefs.Geometry(1280f, 760f, 100f, 80f, false))
        val g = prefs.loadGeometry { listOf(Rectangle(0, 0, 1920, 1080)) }
        assertEquals(100f, g.x)
        assertEquals(80f, g.y)
    }

    @Test
    fun aPositionOnASecondMonitorToTheLeftIsStillKept() {
        // Multi-monitor setups legitimately have negative x — this must not be mistaken for "off-screen".
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.saveGeometry(DesktopPrefs.Geometry(1280f, 760f, -1800f, 50f, false))
        val screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(-1920, 0, 1920, 1080))
        val g = prefs.loadGeometry { screens }
        assertEquals(-1800f, g.x)
        assertEquals(50f, g.y)
    }

    @Test
    fun whenScreensCannotBeDeterminedTheSavedPositionIsTrusted() {
        // e.g. a headless CI runner — no screens to check against, so don't fight the saved value.
        val prefs = DesktopPrefs(tmp.root.resolve("p.properties"))
        prefs.saveGeometry(DesktopPrefs.Geometry(1280f, 760f, 40f, 30f, false))
        val g = prefs.loadGeometry { emptyList() }
        assertEquals(40f, g.x)
        assertEquals(30f, g.y)
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
        assertEquals(DesktopWorld.ownFor(JarvisPalette.Default), a.desktopWorld)
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
