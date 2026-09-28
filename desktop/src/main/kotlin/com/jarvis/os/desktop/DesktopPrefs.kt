package com.jarvis.os.desktop

import com.jarvis.os.ui.theme.BackdropStyle
import com.jarvis.os.ui.theme.JarvisPalette
import java.io.File
import java.util.Properties

/**
 * The desktop's appearance choices — the same two the phone offers: a theme (which
 * decides the orb) and a world behind it. Stored by the same permanent ids the phone
 * uses ([JarvisPalette.id], [BackdropStyle.id]), in a properties file under [AppDirs].
 *
 * An empty backdrop id means "the theme's own world", exactly as on the phone, so a
 * theme change carries its world with it until the user picks one on purpose.
 */
class DesktopPrefs(private val file: File = AppDirs.file("prefs.properties")) {

    data class Appearance(val palette: JarvisPalette, val backdropId: String) {
        val backdrop: BackdropStyle get() = BackdropStyle.resolve(backdropId, palette.orbStyle)
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

    fun loadGeometry(): Geometry {
        val p = props()
        val w = p.getProperty(KEY_W)?.toFloatOrNull()
        val h = p.getProperty(KEY_H)?.toFloatOrNull()
        if (w == null || h == null) return Geometry.DEFAULT
        return Geometry(
            // Never restore something unusably small (the window's own minimum is 980×640 px).
            width = w.coerceAtLeast(900f),
            height = h.coerceAtLeast(600f),
            x = p.getProperty(KEY_X)?.toFloatOrNull(),
            y = p.getProperty(KEY_Y)?.toFloatOrNull(),
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
        private const val KEY_W = "window.width"
        private const val KEY_H = "window.height"
        private const val KEY_X = "window.x"
        private const val KEY_Y = "window.y"
        private const val KEY_MAX = "window.maximized"
        private const val KEY_Q_W = "quickbar.width"
        private const val KEY_Q_H = "quickbar.height"
        private const val KEY_Q_X = "quickbar.x"
        private const val KEY_Q_Y = "quickbar.y"
    }
}
