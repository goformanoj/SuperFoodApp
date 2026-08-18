package com.jarvis.os.ui.theme

import androidx.compose.ui.graphics.Color
import com.jarvis.os.control.BubbleColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the one duplication in the theme system.
 *
 * The floating orb ([BubbleColors]) holds its own plain-int copy of every
 * palette, because it is drawn by a `View` on a `Canvas` and — more importantly —
 * because anything under `control/` that imports Compose breaks the off-device
 * gate in `scripts/jvmcheck`, which can only compile the non-UI sources BECAUSE
 * none of them reaches into `ui/`.
 *
 * A copy is only safe while something checks it. This test lives under `ui/` on
 * purpose: `jvmcheck` skips this directory, so it runs in CI, where Compose
 * actually exists and the real palette can be read.
 *
 * If this fails, a colour was changed in one place and not the other. Change the
 * bubble to match the palette — the palette is the source.
 */
class JarvisPaletteBubbleTest {

    private fun rgb(c: Color): Triple<Int, Int, Int> = Triple(
        (c.red * 255f + 0.5f).toInt(),
        (c.green * 255f + 0.5f).toInt(),
        (c.blue * 255f + 0.5f).toInt(),
    )

    private fun rgb(argb: Int): Triple<Int, Int, Int> = Triple(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
    )

    @Test
    fun `the bubble covers exactly the themes the palette has, in the same order`() {
        assertEquals(
            JarvisPalette.entries.map { it.id },
            BubbleColors.IDS,
        )
    }

    @Test
    fun `every bubble colour still equals the palette it was copied from`() {
        JarvisPalette.entries.forEach { palette ->
            val bubble = BubbleColors.forTheme(palette.id)
            assertEquals("${palette.id} accent", rgb(palette.accent), rgb(bubble.accent))
            assertEquals("${palette.id} secondary", rgb(palette.secondary), rgb(bubble.secondary))
            assertEquals("${palette.id} highlight", rgb(palette.highlight), rgb(bubble.highlight))
            assertEquals("${palette.id} background", rgb(palette.background), rgb(bubble.background))
        }
    }

    @Test
    fun `an unknown stored theme falls back rather than throwing`() {
        // This is read on a draw path for a window that sits over other apps. A
        // preferences string nobody could have typed is not worth a crash there.
        assertEquals(
            BubbleColors.forTheme(JarvisPalette.Default.id),
            BubbleColors.forTheme("a theme that was removed"),
        )
    }
}
