package com.jarvis.os.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class LogoTintTest {

    private fun rgb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun aGreyDoesNotChangeWhateverTheShift() {
        // The "J" is white and the badge is dark metal: they must stay exactly as they are in every theme.
        for (c in listOf(rgb(255, 255, 255, 255), rgb(255, 20, 20, 20), rgb(255, 128, 128, 128))) {
            assertEquals(c, LogoTint.shiftHue(c, 140f))
        }
    }

    @Test
    fun alphaSurvivesTheShift() {
        assertEquals(77, LogoTint.shiftHue(rgb(77, 30, 120, 255), 100f) ushr 24)
        assertEquals(0, LogoTint.shiftHue(rgb(0, 30, 120, 255), 100f) ushr 24)
    }

    @Test
    fun shiftingMovesTheHueByTheRequestedAmountAndWraps() {
        val blue = rgb(255, 0, 128, 255)
        val h0 = LogoTint.hueOf(blue)
        assertEquals((h0 + 90f) % 360f, LogoTint.hueOf(LogoTint.shiftHue(blue, 90f)), 2f)
        assertEquals((h0 + 200f) % 360f, LogoTint.hueOf(LogoTint.shiftHue(blue, 200f)), 2f)
        assertEquals(h0, LogoTint.hueOf(LogoTint.shiftHue(blue, 360f)), 2f)
    }

    @Test
    fun theDeltaLandsTheSourceGlowOnTheThemesAccent() {
        val forge = rgb(255, 0xF0, 0xA4, 0x4B)
        val delta = LogoTint.deltaFor(forge)
        val glow = rgb(255, 0, 140, 255)
        assertEquals(LogoTint.hueOf(forge), LogoTint.hueOf(LogoTint.shiftHue(glow, delta)), 14f)
    }

    @Test
    fun aWholeImageIsTintedPixelByPixel() {
        val img = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, rgb(255, 0, 128, 255)); setRGB(1, 0, rgb(255, 255, 255, 255))
        }
        val out = LogoTint.tinted(img, 120f)
        assertTrue("the coloured pixel moved", out.getRGB(0, 0) != img.getRGB(0, 0))
        assertEquals("the white pixel did not", img.getRGB(1, 0), out.getRGB(1, 0))
    }
}
