package com.jarvis.os.desktop.ui.stark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarkGeometryTest {

    @Test
    fun floorRowsBunchTowardTheHorizon() {
        val rows = StarkGeometry.floorRows(0f, 10)
        val gaps = rows.zipWithNext { a, b -> b - a }
        assertTrue("rows must be in order", gaps.all { it > 0f })
        assertTrue("gaps must widen toward the viewer", gaps.zipWithNext().all { (a, b) -> b > a })
        assertTrue(rows.all { it in 0f..1f })
    }

    @Test
    fun theFloorWrapsWithoutAJump() {
        // Phase 1 is phase 0 shifted by one row: the same set of lines, so the loop has no seam.
        val a = StarkGeometry.floorRows(0f, 8)
        val b = StarkGeometry.floorRows(1f, 8)
        assertEquals(a, b)
        // And a fractional phase moves every row toward the viewer.
        val c = StarkGeometry.floorRows(0.5f, 8)
        assertTrue(a.zip(c).all { (x, y) -> y > x })
    }

    @Test
    fun cellsFadeOutWithDistance() {
        assertEquals(1f, StarkGeometry.fade(0f, 100f), 0f)
        assertEquals(0f, StarkGeometry.fade(100f, 100f), 0f)
        assertEquals(0f, StarkGeometry.fade(500f, 100f), 0f)
        assertTrue(StarkGeometry.fade(30f, 100f) > StarkGeometry.fade(60f, 100f))
        assertEquals(0f, StarkGeometry.fade(10f, 0f), 0f)
    }

    @Test
    fun dataColumnsStayInRangeAndNeverGoFlat() {
        for (i in 0 until 24) for (step in 0 until 200) {
            val h = StarkGeometry.barHeight(i, step * 0.1f)
            assertTrue("bar $i at step $step = $h", h in 0.18f..1.0f)
        }
    }

    @Test
    fun hashIsStableAndSpreadOut() {
        assertEquals(StarkGeometry.hash01(3, 4, 5), StarkGeometry.hash01(3, 4, 5), 0f)
        val sample = (0 until 400).map { StarkGeometry.hash01(it, it * 7, 1) }
        assertTrue(sample.all { it in 0f..1f })
        assertTrue("hash should not be clumped", sample.count { it < 0.5f } in 120..280)
    }
}
