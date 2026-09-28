package com.jarvis.os.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/**
 * The desktop's half of the phone's `Type.kt`: the same faces under the same names,
 * so the shared drawing code (`JarvisWordmark` imports [Michroma]) compiles here
 * unchanged. The phone reads them through `R.font`; the desktop reads the very same
 * .ttf files, copied from `app/src/main/res/font` onto the classpath at build time.
 *
 * Michroma is the display face (titles, labels, the wordmark) and has one weight;
 * Inter carries prose. Same split, same reasons, as the phone.
 */
private fun bytes(name: String): ByteArray =
    requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("font/$name")) {
        "font/$name missing from the classpath — syncSharedRes should have copied it"
    }.use { it.readBytes() }

val Michroma = FontFamily(Font("michroma", bytes("michroma.ttf"), FontWeight.Normal))

private val interData by lazy { bytes("inter.ttf") }
val Inter = FontFamily(
    Font("inter-400", interData, FontWeight.Normal),
    Font("inter-500", interData, FontWeight.Medium),
    Font("inter-600", interData, FontWeight.SemiBold),
    Font("inter-700", interData, FontWeight.Bold),
)

/** Everything unstyled falls to Inter: Material 3 provides `bodyLarge` as the default text style. */
val DesktopTypography = Typography(
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp),
    titleLarge = TextStyle(fontFamily = Michroma, fontSize = 16.sp, letterSpacing = 0.8.sp),
    titleMedium = TextStyle(fontFamily = Michroma, fontSize = 14.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = Michroma, fontSize = 12.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = Michroma, fontSize = 10.5.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = Michroma, fontSize = 10.sp, letterSpacing = 1.8.sp),
    displayLarge = TextStyle(fontFamily = Michroma, fontSize = 34.sp, letterSpacing = 2.sp),
)
