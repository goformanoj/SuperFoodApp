package com.jarvis.os.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalPalette
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The JARVIS mark: the same badge as the phone's app icon (a dark disc, glowing rings, a "J"), in the active
 * theme's colour. The artwork is blue; [LogoTint] shifts its hue to the theme's accent while leaving the white "J"
 * and the dark metal alone, so every theme gets the same logo rather than a different one.
 */
object LogoTint {
    /** The hue, in degrees, of the glow in the source artwork. */
    const val SOURCE_HUE = 207f

    /** Hue of an ARGB colour in degrees, 0..360. */
    fun hueOf(argb: Int): Float {
        val hsb = java.awt.Color.RGBtoHSB((argb shr 16) and 255, (argb shr 8) and 255, argb and 255, null)
        return hsb[0] * 360f
    }

    /** [argb] with its hue moved by [delta] degrees. Alpha, saturation and brightness are untouched, so greys stay grey. */
    fun shiftHue(argb: Int, delta: Float): Int {
        val a = argb ushr 24
        val hsb = java.awt.Color.RGBtoHSB((argb shr 16) and 255, (argb shr 8) and 255, argb and 255, null)
        val h = (((hsb[0] * 360f + delta) % 360f + 360f) % 360f) / 360f
        return (a shl 24) or (java.awt.Color.HSBtoRGB(h, hsb[1], hsb[2]) and 0xFFFFFF)
    }

    /** How far to turn the artwork's hue to reach [accent]'s. */
    fun deltaFor(accent: Int): Float = hueOf(accent) - SOURCE_HUE

    fun tinted(source: BufferedImage, delta: Float): BufferedImage {
        val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) out.setRGB(x, y, shiftHue(source.getRGB(x, y), delta))
        return out
    }
}

private val source: BufferedImage? by lazy {
    runCatching { Thread.currentThread().contextClassLoader.getResourceAsStream("logo/jarvis_logo.png")?.use { ImageIO.read(it) } }.getOrNull()
}

private val cache = HashMap<JarvisPalette, ImageBitmap?>()

/** The logo in [palette]'s colour, built once per theme. Null only if the artwork is missing from the build. */
fun logoFor(palette: JarvisPalette): ImageBitmap? = synchronized(cache) {
    cache.getOrPut(palette) {
        source?.let { LogoTint.tinted(it, LogoTint.deltaFor(palette.accent.toArgb())).toComposeImageBitmap() }
    }
}

@Composable
fun JarvisLogo(size: Dp, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val bmp = remember(palette) { logoFor(palette) } ?: return
    Image(bmp, contentDescription = "JARVIS", modifier = modifier.size(size))
}

/** The same mark as a [Painter], for the window and tray icons. */
class LogoPainter(palette: JarvisPalette) : Painter() {
    private val bmp = logoFor(palette)
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        bmp?.let { drawImage(it, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
    }
}
