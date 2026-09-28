package com.jarvis.os.desktop.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class KnowledgeClientTest {

    @Test
    fun webAnswersKeepOnlyRealLinks() {
        val body = """{"answer":" The RBI held the repo rate at 5.5%. ","sources":[
            {"title":"RBI policy","url":"https://rbi.org.in/x"},
            {"title":"bad","url":"javascript:alert(1)"},
            {"url":"https://news.example.com/a"}],"remaining":1000}"""
        val w = KnowledgeClient.parseWeb(body)
        assertEquals("The RBI held the repo rate at 5.5%.", w.answer)
        assertEquals(listOf(
            KnowledgeClient.Source("RBI policy", "https://rbi.org.in/x"),
            KnowledgeClient.Source("https://news.example.com/a", "https://news.example.com/a"),
        ), w.sources)
        assertEquals(0, KnowledgeClient.parseWeb("""{"answer":"x"}""").sources.size)
    }

    @Test
    fun screenshotsShrinkToFitButNeverGrow() {
        assertEquals(1600 to 900, ScreenGrab.fit(1920, 1080, 1600))
        assertEquals(900 to 1600, ScreenGrab.fit(1080, 1920, 1600))
        assertEquals(800 to 600, ScreenGrab.fit(800, 600, 1600))
        assertEquals(1600 to 1, ScreenGrab.fit(8000, 2, 1600))
    }

    @Test
    fun theJpegIsSmallAndReadable() {
        val img = BufferedImage(2560, 1440, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); g.color = java.awt.Color.RED; g.fillRect(100, 100, 800, 400); g.dispose()
        val jpeg = ScreenGrab.jpeg(img)
        assertTrue("was ${jpeg.size} bytes", jpeg.size in 1_000..1_500_000)
        val back = ImageIO.read(jpeg.inputStream())
        assertEquals(1600, back.width)
        assertEquals(900, back.height)
    }
}
