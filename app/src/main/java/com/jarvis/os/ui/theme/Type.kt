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

/**
 * The app's voice.
 *
 * **Michroma carries everything structural** — the wordmark, every screen title,
 * every section heading, every tab, every button label, every settings row title.
 * *"i want that the font you used to write JARVIS in the centre of the orb to be
 * the font you follow throughout the app"*. That is what makes an app sound like
 * one product rather than a set of screens, and it is now true of every piece of
 * chrome a user reads.
 *
 * **Inter carries running prose, and that is deliberate rather than a shortcut.**
 * Michroma has exactly one weight and is extremely wide — a paragraph set in it
 * wraps two or three times where Inter wraps once, and a device screenshot
 * already showed "Open JARVIS with a gesture" broken across three lines as a
 * *title*. Setting descriptions and chat messages in it would make the app harder
 * to read while looking more branded, which is the wrong trade for the parts
 * where the words matter most. Pairing a display face with a text face is not a
 * compromise; it is what every commercial app does.
 *
 * **Every role the app uses is defined here.** `bodySmall` is the single
 * most-used style in the codebase — twenty-eight call sites — and it was not in
 * this table at all, so it fell through to Material's default and rendered in
 * **Roboto**. So did `titleSmall`. A third of the text in the app was in a font
 * nobody chose, which is a large part of why it read as assembled. Anything
 * missing from a `Typography` does not inherit; it defaults.
 */
val JarvisTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 34.sp, letterSpacing = 2.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 26.sp, letterSpacing = 1.5.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = 1.2.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 24.sp, letterSpacing = 1.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 21.sp, letterSpacing = 0.9.sp,
    ),
    // 18, down from 19: this carries every screen title, and "Custom instructions"
    // in Michroma at 19sp filled the full width of a 360dp phone edge to edge.
    headlineSmall = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 18.sp, letterSpacing = 0.6.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 0.8.sp,
    ),
    // Row and card titles. Michroma now, where it used to be Inter — this is the
    // style on every settings row and every drawer item, so it is most of what
    // makes the chrome speak in one voice.
    titleMedium = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.4.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, letterSpacing = 0.4.sp,
    ),
    // ── Prose. Inter, for the reasons in the doc above. ──────────────────────
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 16.sp, letterSpacing = 0.2.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 14.sp, letterSpacing = 0.2.sp,
        lineHeight = 20.sp,
    ),
    // The most-used style in the app, and until now not defined at all.
    bodySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight(400), fontSize = 13.sp, letterSpacing = 0.1.sp,
        lineHeight = 19.sp,
    ),
    // ── Labels. All Michroma: buttons, tabs, section headings. ───────────────
    labelLarge = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 1.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 10.5.sp, letterSpacing = 1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Michroma, fontWeight = FontWeight.Normal, fontSize = 10.sp, letterSpacing = 1.8.sp,
    ),
)
