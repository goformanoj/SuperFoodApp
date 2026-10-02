package com.jarvis.os.desktop

import com.jarvis.os.desktop.WindowChrome.Geometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the draggable strip, the window controls and the resize edges are — for the frameless window
 * (see [WindowChrome]). No window needed: it's just geometry. 1600x950 px, 125% scaling:
 * edge 7 px, corner 17 px, title strip 40 px, controls 172 px.
 */
class WindowChromeTest {

    private fun hit(x: Int, y: Int, maximized: Boolean = false) =
        Geometry.hitTest(x, y, width = 1600, height = 950, edge = 7, corner = 17, titleHeight = 40, controlsWidth = 172, maximized = maximized)

    @Test
    fun theTitleStripDragsTheWindow() {
        assertEquals(Geometry.HTCAPTION, hit(400, 20))
        assertEquals(Geometry.HTCAPTION, hit(1427, 39))   // right up to the controls, last row of the strip
    }

    @Test
    fun theWindowControlsStayClickable() {
        // Not caption: a click here must reach the buttons, not start a drag.
        assertEquals(Geometry.HTCLIENT, hit(1428, 20))
        assertEquals(Geometry.HTCLIENT, hit(1590, 20))
    }

    @Test
    fun belowTheStripIsOrdinaryContent() {
        assertEquals(Geometry.HTCLIENT, hit(400, 40))
        assertEquals(Geometry.HTCLIENT, hit(800, 475))
    }

    @Test
    fun theFourEdgesResize() {
        assertEquals(Geometry.HTLEFT, hit(3, 475))
        assertEquals(Geometry.HTRIGHT, hit(1596, 475))
        assertEquals(Geometry.HTTOP, hit(800, 3))
        assertEquals(Geometry.HTBOTTOM, hit(800, 946))
    }

    @Test
    fun theCornersResizeDiagonally() {
        assertEquals(Geometry.HTTOPLEFT, hit(3, 3))
        assertEquals(Geometry.HTTOPRIGHT, hit(1596, 3))
        assertEquals(Geometry.HTBOTTOMLEFT, hit(3, 946))
        assertEquals(Geometry.HTBOTTOMRIGHT, hit(1596, 946))
        // A little way along an edge still counts as the corner (corners are bigger targets than edges).
        assertEquals(Geometry.HTTOPLEFT, hit(12, 3))
        assertEquals(Geometry.HTTOPLEFT, hit(3, 12))
    }

    @Test
    fun theEdgesWinOverTheStripAtTheVeryTop() {
        // The top 7 px is the resize edge, even though it is also inside the 40 px strip.
        assertEquals(Geometry.HTTOP, hit(400, 2))
        assertEquals(Geometry.HTCAPTION, hit(400, 8))
    }

    @Test
    fun aMaximisedWindowCannotBeResizedByItsEdges() {
        assertEquals(Geometry.HTCLIENT, hit(3, 475, maximized = true))
        assertEquals(Geometry.HTCLIENT, hit(1596, 946, maximized = true))
        // …but its strip still drags (that is how you pull it back out of maximised).
        assertEquals(Geometry.HTCAPTION, hit(400, 3, maximized = true))
    }

    @Test
    fun pointsOutsideTheWindowAreNotClaimed() {
        assertEquals(Geometry.HTCLIENT, hit(-1, 20))
        assertEquals(Geometry.HTCLIENT, hit(1600, 20))
        assertEquals(Geometry.HTCLIENT, hit(400, 950))
    }
}
