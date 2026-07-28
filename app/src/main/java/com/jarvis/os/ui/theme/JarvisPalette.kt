package com.jarvis.os.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A selectable look. Only the accent moves for now — the full re-skin waits on
 * the designs, but the choice, the persistence and the plumbing are real, so
 * adding a design later is a data change rather than a rewrite.
 */
enum class JarvisPalette(
    val id: String,
    val displayName: String,
    val blurb: String,
    val accent: Color,
    val secondary: Color,
) {
    Arc("arc", "Arc Reactor", "Cyan on matte black. The original.", Cyan, ElectricBlue),
    Ember("ember", "Ember", "Warm amber, like a workshop at night.", Color(0xFFFFB020), Color(0xFFFF6B35)),
    Signal("signal", "Signal", "Cold green, terminal-flavoured.", Color(0xFF2EE6A6), Color(0xFF13B981)),
    Violet("violet", "Violet", "Deep indigo with a synthetic edge.", Color(0xFF9B7BFF), Color(0xFF5B3DF5)),
    ;

    companion object {
        val Default = Arc

        fun fromId(id: String): JarvisPalette = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * The accent in force. Screens read this instead of the fixed [Cyan] so a theme
 * change takes effect everywhere that has been migrated to it.
 */
val LocalAccent = compositionLocalOf { JarvisPalette.Default.accent }
