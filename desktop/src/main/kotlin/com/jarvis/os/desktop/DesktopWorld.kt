package com.jarvis.os.desktop

import com.jarvis.os.ui.theme.JarvisPalette

/**
 * The worlds drawn by the laptop app itself — large-screen scenes built for a 1280-pixel window,
 * as opposed to the phone's ten backdrops (which are still offered, below these). Every theme has
 * exactly one as its own, and any of them can be picked under any theme.
 *
 * The ids are written to the prefs file, so they are permanent: rename a [displayName] freely,
 * never an [id].
 *
 * Pure (no Compose), so the theme→world rule is unit-tested.
 */
enum class DesktopWorld(val id: String, val displayName: String, val blurb: String) {
    Deck("deck", "Command Deck", "Range rings, a drifting floor and live data columns."),
    ReactorHall("reactor", "Reactor Hall", "Conduits radiating from the core, with pulses racing outward."),
    Foundry("foundry", "Foundry", "Embers rising off a molten grate under heat haze."),
    DeepSpace("deepspace", "Deep Space", "A spiral galaxy turning slowly in drifting violet gas."),
    Orbital("orbital", "Orbital", "A planet's lit edge, satellites on their tracks, a station in the dark."),
    Skyline("skyline", "Skyline", "A city at night under a heavy sky, its lights mirrored in the river."),
    ;

    companion object {
        /**
         * The world a theme brings with it. A `when` over every theme with no `else`, so adding a
         * sixth theme fails to compile instead of silently landing on some other theme's scene.
         */
        fun ownFor(palette: JarvisPalette): DesktopWorld = when (palette) {
            JarvisPalette.Arc -> ReactorHall
            JarvisPalette.Forge -> Foundry
            JarvisPalette.Nebula -> DeepSpace
            JarvisPalette.Orbit -> Orbital
            JarvisPalette.Stark -> Deck
            JarvisPalette.Holo -> Skyline
        }

        fun fromId(id: String?): DesktopWorld? = entries.firstOrNull { it.id == id }
    }
}
