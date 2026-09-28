package com.jarvis.os.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "JARVIS Night" — the desktop design direction (Claude Design canvas
 * https://claude.ai/artifact/NS51vSipMPM78D18tygCWL). Colours are the phone's
 * palette (app/.../ui/theme/Color.kt) extended with the canvas's surface steps.
 */
object J {
    val Ground = Color(0xFF050B18)
    val Sidebar = Color(0xFF070F1F)
    val Rail = Color(0xFF060D1C)
    val Surface = Color(0xFF0A1426)
    val Raised = Color(0xFF0E1A30)
    val Hairline = Color(0x12FFFFFF)
    val Border = Color(0x1AFFFFFF)

    val Cyan = Color(0xFF00D4FF)
    val CyanSoft = Color(0xFF7FE8FF)
    val Blue = Color(0xFF0066FF)
    val OnCyan = Color(0xFF03101F)

    val Text = Color(0xFFE6F1FF)
    val TextBody = Color(0xFFDCE7F5)
    val TextMuted = Color(0xFFA9B6C8)
    val TextDim = Color(0xFF8A97AB)
    val TextFaint = Color(0xFF6E7C92)

    val Green = Color(0xFF2EE6A6)
    val Amber = Color(0xFFFF9F1C)
    val AmberSoft = Color(0xFFFFC266)
    val Red = Color(0xFFFF4D4D)

    val Mono = FontFamily.Monospace

    val colors = darkColorScheme(
        primary = Cyan,
        onPrimary = OnCyan,
        secondary = Blue,
        background = Ground,
        surface = Surface,
        surfaceVariant = Raised,
        onBackground = Text,
        onSurface = Text,
        onSurfaceVariant = TextMuted,
        outline = Border,
        error = Red,
    )
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = J.colors, content = content)

/** The brand orb: a radial cyan→blue sphere with a glow; [active] quickens its pulse. */
@Composable
fun Orb(size: Dp, active: Boolean = false, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition()
    val glow by t.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (active) 520 else 2200), RepeatMode.Reverse),
    )
    Box(
        modifier
            .size(size)
            .shadow(elevation = size * 0.45f * glow, shape = CircleShape, ambientColor = J.Cyan, spotColor = J.Cyan)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    0f to Color(0xFFBFF4FF),
                    0.30f to J.Cyan.copy(alpha = glow),
                    0.72f to J.Blue,
                    1f to Color(0xFF002878),
                ),
            ),
    )
}

/** A small rounded label: status chips, badges. */
@Composable
fun Pill(text: String, fg: Color = J.TextMuted, bg: Color = Color(0x0DFFFFFF), dot: Color? = null, mono: Boolean = false) {
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

/** Section label in the canvas's mono caps style ("TODAY", "RECENT"). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, color = J.TextFaint, fontSize = 11.sp, fontFamily = J.Mono, letterSpacing = 1.sp)
}
