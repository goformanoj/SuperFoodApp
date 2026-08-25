package com.jarvis.os.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The colours of the theme actually in force, for every screen to read.
 *
 * ## Why this exists
 *
 * The user's words were "the themes are just not it", and the reason turned out
 * to be countable rather than a matter of taste: of seven screens, **exactly one
 * was theme-aware.** `HomeScreen` read [LocalPalette]; Settings, Instructions,
 * Chat, Files, Calendar, Speech and Diagnostics all imported the fixed [Cyan]
 * constant directly — thirteen references in Settings alone.
 *
 * So picking Forge (copper) or Nebula (violet) recoloured the orb and the
 * backdrop, and then every other screen in the app stayed cyan. A theme that
 * reaches one screen in seven is not a theme, it is an accent on a hero image,
 * which is exactly what it looked like.
 *
 * Screens now read these instead of the constants. `Color.kt`'s fixed values
 * remain as the *fallback* palette's raw definitions — they are what
 * [JarvisPalette.Arc] is built from — but nothing should import them by name any
 * more.
 *
 * Modelled on `MaterialTheme.colorScheme`: an object of `@Composable` getters, so
 * a screen writes `JarvisTheme.accent` and gets whatever the user chose, with no
 * plumbing through parameters.
 */
object JarvisTheme {

    /** The theme's primary. What was hard-coded as `Cyan` everywhere. */
    val accent: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.accent

    val secondary: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.secondary

    /** The warm counter-colour — gold traces, copper struts, the second arc. */
    val highlight: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.highlight

    val background: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.background

    val surface: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.surface

    /**
     * The veil between the backdrop and a screen's content.
     *
     * **The single most damaging thing in the app before this.** The backdrop is
     * a full-brightness animated scene — beams, gas, a star field — and every
     * screen laid its text directly onto it. On the Forge theme those beams are
     * near-white, so a settings description at 60% grey sat on a background
     * brighter than the text, and the result was unreadable. A screenshot showed
     * a whole paragraph that simply could not be read.
     *
     * A backdrop is scenery. The moment it competes with a sentence it has
     * stopped doing its job, and the fix is not a darker backdrop — it is a veil
     * on the screens that carry content. Home keeps the backdrop at full strength
     * because Home is the backdrop.
     */
    val scrim: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.background.copy(alpha = 0.82f)

    /**
     * A real surface for cards and rows.
     *
     * This was `accent.copy(alpha = 0.07f)` — seven percent, over a moving scene.
     * On a dark backdrop that reads as a faint tint; over a bright one it is
     * nothing at all, which is why the settings cards in a screenshot were
     * invisible and the screen looked like floating text.
     *
     * A card has to be a surface: something the content sits ON. That means real
     * opacity against the theme's own surface colour, with the accent as a tint
     * rather than as the entire fill.
     */
    val card: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.surface.copy(alpha = 0.92f)

    /** A card raised above another card — the editor inside a section. */
    val cardRaised: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.surface.copy(alpha = 0.98f)

    /** The hairline on a card. Enough to find an edge, not enough to box it in. */
    val cardBorder: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.accent.copy(alpha = 0.22f)

    /**
     * Retained so the whole app does not have to change in one commit.
     *
     * Points at [card] now rather than the old 7% wash, so every existing caller
     * gets a surface it can actually read text on without being touched.
     */
    val glass: Color
        @Composable @ReadOnlyComposable get() = card

    /** Retained alias for [cardBorder]. */
    val glassBorder: Color
        @Composable @ReadOnlyComposable get() = cardBorder
}
