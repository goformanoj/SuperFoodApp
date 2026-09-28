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
        val p = Properties()
        if (file.exists()) runCatching { file.inputStream().use { p.load(it) } }
        return Appearance(
            palette = JarvisPalette.fromId(p.getProperty(KEY_THEME).orEmpty()),
            backdropId = p.getProperty(KEY_BACKDROP).orEmpty(),
        )
    }

    fun save(a: Appearance) {
        val p = Properties()
        p.setProperty(KEY_THEME, a.palette.id)
        p.setProperty(KEY_BACKDROP, a.backdropId)
        runCatching { file.outputStream().use { p.store(it, "JARVIS desktop appearance") } }
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_BACKDROP = "backdrop"
    }
}
