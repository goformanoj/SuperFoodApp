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

// Orbitron — headings / display. Inter — body / UI text.
val Orbitron = FontFamily(orbitron(400), orbitron(500), orbitron(600), orbitron(700), orbitron(900))
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

val JarvisTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Orbitron, fontWeight = FontWeight(700), fontSize = 44.sp, letterSpacing = 2.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Orbitron, fontWeight = FontWeight(700), fontSize = 32.sp, letterSpacing = 1.5.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Orbitron, fontWeight = FontWeight(600), fontSize = 22.sp, letterSpacing = 1.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Orbitron, fontWeight = FontWeight(600), fontSize = 18.sp, letterSpacing = 1.sp,
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
        fontFamily = Orbitron, fontWeight = FontWeight(600), fontSize = 14.sp, letterSpacing = 1.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 12.sp, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Orbitron, fontWeight = FontWeight(500), fontSize = 11.sp, letterSpacing = 2.sp,
    ),
)
