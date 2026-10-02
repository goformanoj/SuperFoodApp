package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.ui.components.HudOrb
import com.jarvis.os.ui.components.OrbPreview
import com.jarvis.os.ui.theme.DesktopTypography
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.Michroma
import com.jarvis.os.voice.OrbState

/**
 * The desktop's design tokens. Neutrals are fixed; everything that carries the
 * theme's colour reads the active [JarvisPalette] — so choosing Forge turns the
 * whole app gold, exactly as it does on the phone.
 */
object J {
    val Hairline = Color(0x14FFFFFF)
    val Border = Color(0x1FFFFFFF)

    val Text = Color(0xFFE6F1FF)
    val TextBody = Color(0xFFDCE7F5)
    val TextMuted = Color(0xFFB4C0D0)
    val TextDim = Color(0xFF8A97AB)
    val TextFaint = Color(0xFF6E7C92)

    val Green = Color(0xFF2EE6A6)
    val Red = Color(0xFFFF4D4D)

    val Mono = FontFamily.Monospace
    val Display = Michroma

    /** The theme's primary — cyan for Arc, gold for Forge, and so on. */
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val Secondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.secondary
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background

    /**
     * True for the Holo theme: its panels are a thin layer of tinted glass over the world, not solid cards,
     * so the city shows through everything. Every other theme keeps the denser glass below.
     */
    val translucent: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current == JarvisPalette.Holo

    /** Glass over the live world: the backdrop shows through, text still reads. */
    val Glass: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background.copy(alpha = if (translucent) 0.40f else 0.72f)
    val Card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface.copy(alpha = if (translucent) 0.44f else 0.88f)
    val CardBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent.copy(alpha = if (translucent) 0.45f else 0.20f)
    val Veil: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background.copy(alpha = if (translucent) 0.58f else 0.80f)
}

@Composable
fun DesktopTheme(palette: JarvisPalette, content: @Composable () -> Unit) {
    val scheme = remember(palette) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.background,
            secondary = palette.secondary,
            tertiary = palette.highlight,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surface,
            onBackground = J.Text,
            onSurface = J.Text,
            onSurfaceVariant = J.TextMuted,
            outline = J.Border,
            error = J.Red,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = DesktopTypography, content = content)
    }
}

/** The live orb — the phone's own 3D [HudOrb]. Keep at most one or two on screen. */
@Composable
fun LiveOrb(size: Dp, state: OrbState, modifier: Modifier = Modifier) {
    HudOrb(modifier = modifier, orb = state, size = size, showLabel = false)
}

/**
 * A still orb for small places (message avatars): the same geometry, drawn once.
 * A live orb per message would be a 60fps Canvas each — the phone's theme picker
 * made that mistake once.
 */
@Composable
fun StillOrb(size: Dp, modifier: Modifier = Modifier) {
    OrbPreview(palette = LocalPalette.current, size = size, modifier = modifier, animated = false)
}

/** A click target that shows the hand cursor, as a desktop control should. */
fun Modifier.clicky(onClick: () -> Unit): Modifier =
    pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)

/** A small rounded label: status chips, badges. */
@Composable
fun Pill(text: String, fg: Color = J.TextMuted, bg: Color = Color(0x14FFFFFF), dot: Color? = null, mono: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Box(Modifier.size(6.dp))
        }
        Text(text, color = fg, fontSize = if (mono) 11.sp else 12.sp, fontFamily = if (mono) J.Mono else null)
    }
}

/** Hover hint for icon buttons and not-yet-built controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Hint(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/** Section label in the HUD style: the display face, tracked wide. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = J.TextFaint) {
    Text(text.uppercase(), modifier = modifier, color = color, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.8.sp)
}
