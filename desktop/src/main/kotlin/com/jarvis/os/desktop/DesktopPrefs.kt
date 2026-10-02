package com.jarvis.os.desktop

import com.jarvis.os.ui.theme.JarvisPalette
import java.io.File
import java.util.Properties

/**
 * The desktop's appearance choices — the same two the phone offers: a theme (which
 * decides the orb) and a world behind it. Stored by the same permanent ids the phone
 * uses ([JarvisPalette.id], plus the laptop's [DesktopWorld.id]), in a properties file under [AppDirs].
 *
 * An empty backdrop id means "the theme's own world", exactly as on the phone, so a
 * theme change carries its world with it until the user picks one on purpose.
 */
class DesktopPrefs(private val file: File = AppDirs.file("prefs.properties")) {

    data class Appearance(val palette: JarvisPalette, val backdropId: String) {
        /**
         * The world behind everything. A stored id that names one of the laptop's worlds wins; a blank id, or
         * one nothing recognises (the phone's old backdrop ids from an earlier build), falls back to the theme's
         * own — never a blank screen.
         */
        val desktopWorld: DesktopWorld get() = DesktopWorld.fromId(backdropId) ?: DesktopWorld.ownFor(palette)
    }

    fun load(): Appearance {
        val p = props()
        return Appearance(
            palette = JarvisPalette.fromId(p.getProperty(KEY_THEME).orEmpty()),
            backdropId = p.getProperty(KEY_BACKDROP).orEmpty(),
        )
    }

    /** Saves the appearance WITHOUT disturbing other keys (the window geometry lives here too). */
    fun save(a: Appearance) = edit {
        it.setProperty(KEY_THEME, a.palette.id)
        it.setProperty(KEY_BACKDROP, a.backdropId)
    }

    /** Where the window was left, in dp. [x]/[y] null = let the OS centre it. */
    data class Geometry(val width: Float, val height: Float, val x: Float?, val y: Float?, val maximized: Boolean) {
        companion object {
            val DEFAULT = Geometry(1280f, 760f, null, null, false)
        }
    }

    fun loadGeometry(screens: () -> List<java.awt.Rectangle> = ::connectedScreenBounds): Geometry {
        val p = props()
        val w = p.getProperty(KEY_W)?.toFloatOrNull()
        val h = p.getProperty(KEY_H)?.toFloatOrNull()
        if (w == null || h == null) return Geometry.DEFAULT
        var x = p.getProperty(KEY_X)?.toFloatOrNull()
        var y = p.getProperty(KEY_Y)?.toFloatOrNull()
        // A saved position goes stale when a monitor is unplugged or Windows reassigns the
        // display layout: reopening there puts almost the whole window off the visible desktop
        // and only the title bar (the one sliver still on-screen) shows. Trust the saved spot
        // only if it still lands within the screens that are actually connected right now.
        if (x != null && y != null && !fitsKnownScreens(x, y, screens())) { x = null; y = null }
        return Geometry(
            // Never restore something unusably small (the window's own minimum is 980×640 px).
            width = w.coerceAtLeast(900f),
            height = h.coerceAtLeast(600f),
            x = x,
            y = y,
            maximized = p.getProperty(KEY_MAX) == "true",
        )
    }

    fun saveGeometry(g: Geometry) = edit {
        it.setProperty(KEY_W, g.width.toString())
        it.setProperty(KEY_H, g.height.toString())
        if (g.x != null && g.y != null) {
            it.setProperty(KEY_X, g.x.toString()); it.setProperty(KEY_Y, g.y.toString())
        } else {
            it.remove(KEY_X); it.remove(KEY_Y)
        }
        it.setProperty(KEY_MAX, g.maximized.toString())
    }

    /**
     * Where the user left the Quick bar and how big (dp): [height] is its size with an answer
     * showing (with none it is just the input row). [x]/[y] null = the default spot, top-centre.
     */
    data class QuickGeometry(val width: Float, val height: Float, val x: Float?, val y: Float?) {
        companion object {
            val DEFAULT = QuickGeometry(720f, 440f, null, null)
            const val MIN_W = 460f
            const val MAX_W = 1600f
            const val MIN_H = 220f
            const val MAX_H = 1200f
        }
    }

    fun loadQuick(): QuickGeometry {
        val p = props()
        val d = QuickGeometry.DEFAULT
        return QuickGeometry(
            width = (p.getProperty(KEY_Q_W)?.toFloatOrNull() ?: d.width).coerceIn(QuickGeometry.MIN_W, QuickGeometry.MAX_W),
            height = (p.getProperty(KEY_Q_H)?.toFloatOrNull() ?: d.height).coerceIn(QuickGeometry.MIN_H, QuickGeometry.MAX_H),
            x = p.getProperty(KEY_Q_X)?.toFloatOrNull(),
            y = p.getProperty(KEY_Q_Y)?.toFloatOrNull(),
        )
    }

    fun saveQuick(g: QuickGeometry) = edit {
        it.setProperty(KEY_Q_W, g.width.toString())
        it.setProperty(KEY_Q_H, g.height.toString())
        if (g.x != null && g.y != null) {
            it.setProperty(KEY_Q_X, g.x.toString()); it.setProperty(KEY_Q_Y, g.y.toString())
        }
    }

    /** How dark the see-through Holo tint is, 0 (clear) .. 1 (dense). Default is the middle. */
    fun glassLevel(): Float = (props().getProperty(KEY_GLASS)?.toFloatOrNull() ?: DEFAULT_GLASS).coerceIn(0f, 1f)

    fun setGlassLevel(v: Float) = edit { it.setProperty(KEY_GLASS, v.coerceIn(0f, 1f).toString()) }

    /** A simple on/off preference (e.g. the wake word). */
    fun flag(key: String, default: Boolean = false): Boolean =
        props().getProperty(key)?.let { it == "true" } ?: default

    fun setFlag(key: String, value: Boolean) = edit { it.setProperty(key, value.toString()) }

    private fun props(): Properties = Properties().apply {
        if (file.exists()) runCatching { file.inputStream().use { load(it) } }
    }

    private fun edit(change: (Properties) -> Unit) {
        val p = props()
        change(p)
        runCatching { file.outputStream().use { p.store(it, "JARVIS desktop preferences") } }
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_BACKDROP = "backdrop"
        private const val KEY_GLASS = "glass.level"
        const val DEFAULT_GLASS = 0.5f
        private const val KEY_W = "window.width"
        private const val KEY_H = "window.height"
        private const val KEY_X = "window.x"
        private const val KEY_Y = "window.y"
        private const val KEY_MAX = "window.maximized"
        private const val KEY_Q_W = "quickbar.width"
        private const val KEY_Q_H = "quickbar.height"
        private const val KEY_Q_X = "quickbar.x"
        private const val KEY_Q_Y = "quickbar.y"

        /** Generous slop (dp vs. physical px on a scaled display aren't the same units; this is a sanity check, not a pixel-perfect one). */
        private const val SCREEN_FIT_MARGIN = 150f

        /** True if [x],[y] lands within (or near) the combined area of [screens] — empty means "couldn't tell" (e.g. headless), which trusts the saved spot rather than fighting it. */
        private fun fitsKnownScreens(x: Float, y: Float, screens: List<java.awt.Rectangle>): Boolean {
            if (screens.isEmpty()) return true
            val left = screens.minOf { it.x } - SCREEN_FIT_MARGIN
            val top = screens.minOf { it.y } - SCREEN_FIT_MARGIN
            val right = screens.maxOf { it.x + it.width } + SCREEN_FIT_MARGIN
            val bottom = screens.maxOf { it.y + it.height } + SCREEN_FIT_MARGIN
            return x in left..right && y in top..bottom
        }

        private fun connectedScreenBounds(): List<java.awt.Rectangle> = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        }.getOrDefault(emptyList())
    }
}
