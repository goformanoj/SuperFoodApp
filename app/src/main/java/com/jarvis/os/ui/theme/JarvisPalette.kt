package com.jarvis.os.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Which geometry the orb draws. Each theme in the user's designs has its own
 * centrepiece, not just its own colours — a hexagonal crystal lattice does not
 * become a filigree disc by recolouring it.
 */
enum class OrbStyle { Reactor, Lattice, Prism, Filigree, Machine, Nebula }

/**
 * A selectable look: a full colour set plus the shape of the orb.
 *
 * Six, drawn from the designs the user supplied. Every one of them animates —
 * the brief was "moving objects like the ring we had before", so each style has
 * its own motion, and the palette carries the colours that motion is drawn in
 * rather than every screen reaching for the fixed [Cyan].
 */
enum class JarvisPalette(
    val id: String,
    val displayName: String,
    val blurb: String,
    val accent: Color,
    val secondary: Color,
    /** The warm counter-colour: gold traces, copper struts, the second energy arc. */
    val highlight: Color,
    val background: Color,
    val surface: Color,
    val orbStyle: OrbStyle,
) {
    Arc(
        id = "arc",
        displayName = "Arc Reactor",
        blurb = "Cyan and gold energy arcs on deep navy.",
        accent = Color(0xFF22E0F5),
        secondary = Color(0xFF0A84FF),
        highlight = Color(0xFFF5B944),
        background = Color(0xFF04101E),
        surface = Color(0xFF0A1A2E),
        orbStyle = OrbStyle.Reactor,
    ),
    Lattice(
        id = "lattice",
        displayName = "Lattice",
        blurb = "Crystal hexagon, gold circuit traces.",
        accent = Color(0xFF7DF0FF),
        secondary = Color(0xFF1B9BD8),
        highlight = Color(0xFFE0A93A),
        background = Color(0xFF06182B),
        surface = Color(0xFF0B2439),
        orbStyle = OrbStyle.Lattice,
    ),
    Prism(
        id = "prism",
        displayName = "Prism",
        blurb = "Faceted gem in violet and rose gold.",
        accent = Color(0xFFB98CF0),
        secondary = Color(0xFF8B5CF6),
        highlight = Color(0xFFE8A87C),
        background = Color(0xFF160A20),
        surface = Color(0xFF241333),
        orbStyle = OrbStyle.Prism,
    ),
    Forge(
        id = "forge",
        displayName = "Forge",
        blurb = "Ornate copper filigree round a molten core.",
        accent = Color(0xFFF0A44B),
        secondary = Color(0xFFC2410C),
        highlight = Color(0xFFFFD79A),
        background = Color(0xFF120A07),
        surface = Color(0xFF20120C),
        orbStyle = OrbStyle.Filigree,
    ),
    Core(
        id = "core",
        displayName = "Core",
        blurb = "Violet crystal around a turning copper machine.",
        accent = Color(0xFFA855F7),
        secondary = Color(0xFFD08B5B),
        highlight = Color(0xFFE9C0A0),
        background = Color(0xFF150A22),
        surface = Color(0xFF231338),
        orbStyle = OrbStyle.Machine,
    ),
    Nebula(
        id = "nebula",
        displayName = "Nebula",
        blurb = "Spiral starfield, violet and copper.",
        accent = Color(0xFFC084FC),
        secondary = Color(0xFFE0845C),
        highlight = Color(0xFF7DD3FC),
        background = Color(0xFF0F0518),
        surface = Color(0xFF1C0C2B),
        orbStyle = OrbStyle.Nebula,
    ),
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

/** The whole palette in force, for the places that need more than the accent. */
val LocalPalette = compositionLocalOf { JarvisPalette.Default }
