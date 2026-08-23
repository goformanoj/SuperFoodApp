package com.jarvis.os.ui.theme

/**
 * The world behind the orb, chosen separately from the theme.
 *
 * Every theme still arrives with the backdrop it was designed around — pick Arc
 * and you get its blueprint, pick Nebula and you get deep sky — and that stays
 * true until the user says otherwise. What is new is that they *can* say
 * otherwise: ten scenes, any of them under any theme, because the orb and the
 * world behind it are separate pieces of taste and there is no reason a
 * blueprint globe should be the only thing an Arc user is allowed to sit in.
 *
 * **Compose-free, like [OrbStyle], and for the same reason**: the catalogue, the
 * defaults and the id round-trip are logic, and logic in this project is tested
 * off-device rather than looked at on a phone. Only the drawing is in Compose.
 *
 * The ids are written to preferences, so they are permanent. Renaming an entry
 * is free; renaming an `id` silently resets everyone who chose it.
 */
enum class BackdropStyle(
    val id: String,
    val displayName: String,
    val blurb: String,
) {
    // ── The four that ship with a theme ──────────────────────────────────────

    /** Arc's own: a wireframe globe under instrument brackets. */
    Blueprint(
        id = "blueprint",
        displayName = "Blueprint",
        blurb = "A wireframe world under instrument brackets. Drafting-table light.",
    ),

    /** Forge's own: warm haze over a lit floor, no wire anywhere. */
    ForgeFloor(
        id = "forge",
        displayName = "Forge Floor",
        blurb = "Heat haze over a lit workshop floor. Nothing here is drawn in wire.",
    ),

    /** Nebula's own: gas and a circuit floor. */
    DeepSky(
        id = "deepsky",
        displayName = "Deep Sky",
        blurb = "Star-forming gas over a circuit plain, far from any sun.",
    ),

    /** Orbit's own: a planet limb across the bottom of the frame. */
    LowOrbit(
        id = "loworbit",
        displayName = "Low Orbit",
        blurb = "The lit edge of a world, seen from just above the atmosphere.",
    ),

    // ── Six more, belonging to no theme ──────────────────────────────────────

    /**
     * Columns of falling light. The only backdrop with a strong vertical read,
     * which is what makes it feel like a system running rather than a place.
     */
    DataRain(
        id = "datarain",
        displayName = "Data Rain",
        blurb = "Columns of falling light, each running at its own rate. A machine thinking out loud.",
    ),

    /**
     * Receding ridgelines in haze. The one scene with real distance in it —
     * five silhouettes, each paler and higher than the last.
     */
    Canyon(
        id = "canyon",
        displayName = "Canyon",
        blurb = "Ridgelines receding into haze, each one paler than the last.",
    ),

    /** Curtains of light standing on a dark horizon, rippling along their length. */
    AuroraVeil(
        id = "aurora",
        displayName = "Aurora",
        blurb = "Curtains of light standing on a dark horizon, rippling end to end.",
    ),

    /**
     * A vast dark slab with light spilling around it. Deliberately the stillest
     * of the ten — everything else moves, and one that refuses to is a choice.
     */
    Monolith(
        id = "monolith",
        displayName = "Monolith",
        blurb = "A slab standing in front of the light. The still one.",
    ),

    /** Underwater: caustics from a surface above, motes rising through them. */
    DeepReef(
        id = "reef",
        displayName = "Deep Reef",
        blurb = "Light bending down through water, with everything drifting upward through it.",
    ),

    /** Dunes under a low sun, with grain moving across them. */
    Dune(
        id = "dune",
        displayName = "Dune",
        blurb = "Sand ridges under a low sun, with the wind visible across them.",
    ),

    ;

    companion object {

        /**
         * The backdrop a theme brings with it.
         *
         * Selecting a theme resets the backdrop to this, which is the behaviour
         * asked for: *"when u select a theme u get the default background which
         * comes with it, but you can also change the background if you wish"*.
         * Every theme must name one — a `when` over [OrbStyle] rather than a map
         * with a fallback, so adding a fifth theme fails to compile instead of
         * silently landing everyone on Blueprint.
         */
        fun defaultFor(style: OrbStyle): BackdropStyle = when (style) {
            OrbStyle.Reactor -> Blueprint
            OrbStyle.Filigree -> ForgeFloor
            OrbStyle.Nebula -> DeepSky
            OrbStyle.Orbit -> LowOrbit
        }

        /**
         * The stored choice, or the theme's own when there is no stored choice —
         * and also when the stored one is unrecognised.
         *
         * That second case is the one worth having a function for: an id survives
         * an uninstall, so a preference written by a build where "prism" existed
         * is still on the device after it is removed. Falling back to the theme's
         * default is the only answer that cannot leave someone on a blank screen.
         */
        fun resolve(id: String?, style: OrbStyle): BackdropStyle =
            entries.firstOrNull { it.id == id } ?: defaultFor(style)
    }
}
