package com.jarvis.os.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Which geometry the orb draws. Each theme in the user's designs has its own
 * centrepiece, not just its own colours — a hexagonal crystal lattice does not
 * become a filigree disc by recolouring it.
 */
enum class OrbStyle { Reactor, Filigree, Nebula, Orbit }

/**
 * A selectable look: a full colour set plus the shape of the orb.
 *
 * **Four, down from seven.** Lattice, Prism and Core were removed on 2026-08-19
 * at the user's request — "make every theme unique, right now all look almost the
 * same" — and they were right about the cause. Lattice shared `wireGlobe` with
 * Arc, and Prism and Core shared `nodeShell` with each other, so three of the
 * seven were recolours of a neighbour rather than designs of their own. Cutting
 * them leaves four that each own a distinct centrepiece and a distinct world:
 *
 * | Theme  | Orb            | Backdrop signature      |
 * |--------|----------------|-------------------------|
 * | Arc    | reactor rings  | wireframe globe         |
 * | Forge  | filigree core  | forge floor + warm haze |
 * | Nebula | sweeping rings | nebula clouds           |
 * | Orbit  | ringed world   | planet limb from orbit  |
 *
 * The orb geometry is built in three dimensions per [OrbStyle]; this carries the
 * colours it is drawn in.
 */
enum class JarvisPalette(
    val id: String,
    val displayName: String,
    val blurb: String,
    val accent: Color,
    val secondary: Color,
    /** The warm counter-colour: gold traces, copper struts, the second energy arc. */
    val highlight: Color,
    /** The metal the JARVIS wordmark is cut from — pale cyan in the two blue
     * designs, rose gold or gold in the warm ones. Chosen per theme rather than
     * derived from the accent, because it is the brightest thing on screen.
     */
    val wordmark: Color,
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
        wordmark = Color(0xFFA8ECF7),
        background = Color(0xFF04101E),
        surface = Color(0xFF0A1A2E),
        orbStyle = OrbStyle.Reactor,
    ),
    Forge(
        id = "forge",
        displayName = "Forge",
        blurb = "Ornate copper filigree round a molten core.",
        accent = Color(0xFFF0A44B),
        secondary = Color(0xFFC2410C),
        highlight = Color(0xFFFFD79A),
        wordmark = Color(0xFFFFDCA8),
        background = Color(0xFF120A07),
        surface = Color(0xFF20120C),
        orbStyle = OrbStyle.Filigree,
    ),
    Nebula(
        id = "nebula",
        displayName = "Nebula",
        blurb = "Spiral starfield, violet and copper.",
        accent = Color(0xFFC084FC),
        secondary = Color(0xFFE0845C),
        highlight = Color(0xFF7DD3FC),
        wordmark = Color(0xFFEBD8EC),
        background = Color(0xFF0F0518),
        surface = Color(0xFF1C0C2B),
        orbStyle = OrbStyle.Nebula,
    ),
    Orbit(
        id = "orbit",
        displayName = "Orbit",
        blurb = "A lit world under wide orbital rings, over a planet horizon.",
        // Sampled from the reference: the sphere's rim runs cyan and its far side
        // falls to violet, which is why the accent and secondary are a cyan/violet
        // pair rather than the cyan/blue every other blue theme uses. That pair is
        // also what the greeting gradient is cut from.
        accent = Color(0xFF29D6FF),
        secondary = Color(0xFF7A5CFF),
        highlight = Color(0xFFB98CFF),
        wordmark = Color(0xFFCDE9FF),
        // Darker than any other theme on purpose: the design is mostly empty space
        // with one bright object in it, and a lighter ground would flatten the glow.
        background = Color(0xFF03070F),
        surface = Color(0xFF091426),
        orbStyle = OrbStyle.Orbit,
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
