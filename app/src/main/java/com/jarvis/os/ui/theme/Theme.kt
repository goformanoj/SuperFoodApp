package com.jarvis.os.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Applies [palette] to the whole app.
 *
 * The colour scheme is derived rather than fixed, so switching a theme moves the
 * background and surfaces too — not only the accent. Both composition locals are
 * provided here, which means a screen gets the current theme by being inside the
 * app, with nothing to remember to pass down.
 */
@Composable
fun JarvisTheme(
    palette: JarvisPalette = JarvisPalette.Default,
    content: @Composable () -> Unit,
) {
    val scheme = remember(palette) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color(0xFF00131A),
            secondary = palette.secondary,
            onSecondary = Color(0xFFEAF2FF),
            tertiary = palette.highlight,
            background = palette.background,
            onBackground = TextPrimary,
            surface = palette.surface,
            onSurface = TextPrimary,
            surfaceVariant = palette.surface,
            onSurfaceVariant = TextSecondary,
            error = ErrorRed,
            onError = Color(0xFF1A0000),
            outline = GlassBorder,
        )
    }

    CompositionLocalProvider(
        LocalAccent provides palette.accent,
        LocalPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = JarvisTypography,
            content = content,
        )
    }
}
