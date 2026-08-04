@file:OptIn(ExperimentalTextApi::class)

package com.jarvis.os.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jarvis.os.R

// Orbitron and Inter ship as variable fonts (single .ttf each); each weight is
// selected via the font's `wght` variation axis.
private fun orbitron(weight: Int) = Font(
    resId = R.font.orbitron,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun inter(weight: Int) = Font(
    resId = R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/**
 * Michroma — the display face, chosen to match the lettering on the JARVIS badge.
 *
 * The badge's own lettering is not a real typeface: it is an AI render, and its
 * "A" has no crossbar, which essentially no text font does because it would
 * collide with Λ. So an exact match cannot be bought or downloaded. Michroma is
 * the closest real face on the traits that actually carry the look — squared
 * geometry, flat terminals, a straight-legged R, and wide proportions.
 *
 * It is a STATIC font with one weight. Asking it for 600 or 700 would make the
 * renderer synthesise a faux-bold, which smears a face this geometric, so every
 * style below stays at Normal and earns its hierarchy from size, tracking and
 * colour instead.
 *
 * Michroma is much wider than Orbitron, so the sizes here are smaller than the
 * ones they replace. Each was checked against the longest real string it has to
 * carry ("Custom instructions" at headlineSmall) on a 320dp screen.
 */
private val michroma = Font(resId = R.font.michroma, weight = FontWeight.Normal)

val Michroma = FontFamily(michroma)
val Orbitron = FontFamily(orbitron(400), orbitron(500), orbitron(600), orbitron(700), orbitron(900))
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

val JarvisTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 34.sp, letterSpacing = 2.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 26.sp, letterSpacing = 1.5.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 19.sp, letterSpacing = 0.8.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 0.8.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 16.sp, letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 14.sp, letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 1.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 12.sp, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 10.sp, letterSpacing = 1.8.sp,
    ),
)
