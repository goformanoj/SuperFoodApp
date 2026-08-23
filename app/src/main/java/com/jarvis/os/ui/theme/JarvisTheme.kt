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
     * The glass fill on cards and rows.
     *
     * Tinted with the theme's accent rather than the flat 8% white it used to be.
     * That neutral is why every card looked identical in all seven themes: the
     * accent changed, and the surface the accent sat on did not, so the screen
     * read as the same grey app with a different highlight colour.
     */
    val glass: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.accent.copy(alpha = 0.07f)

    /** The hairline on that glass. Enough to find an edge, not enough to box it in. */
    val glassBorder: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.accent.copy(alpha = 0.20f)
}
