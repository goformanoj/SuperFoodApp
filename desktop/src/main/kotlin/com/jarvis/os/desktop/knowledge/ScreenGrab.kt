package com.jarvis.os.desktop.knowledge

import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * "What's this error on my screen?" (AGENT_PLAN §5, S6). One screenshot of the screen the
 * mouse is on, only when the user asked for it (a button, or an approved agent step),
 * shrunk to a JPEG the vision model can read. It is held in memory for that one question
 * and never written to disk.
 */
object ScreenGrab {

    /** The screen the pointer is on (where the user is looking), or the primary one. */
    fun capture(): BufferedImage {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val device = MouseInfo.getPointerInfo()?.device ?: env.defaultScreenDevice
        val bounds: Rectangle = device.defaultConfiguration.bounds
        return Robot(device).createScreenCapture(bounds)
    }

    /** Longest side at most [maxSide] px, JPEG at [quality] — a few hundred KB for a 1080p screen. */
    fun jpeg(img: BufferedImage, maxSide: Int = 1600, quality: Float = 0.82f): ByteArray {
        val (w, h) = fit(img.width, img.height, maxSide)
        val rgb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(img, 0, 0, w, h, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
            writer.write(null, IIOImage(rgb, null, null), p)
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** Scales (w, h) down so the longer side is at most [maxSide]; never up. Pure; tested. */
    fun fit(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= maxSide) return w to h
        val s = maxSide.toDouble() / longest
        return maxOf(1, (w * s).toInt()) to maxOf(1, (h * s).toInt())
    }
}
