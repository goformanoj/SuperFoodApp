package com.jarvis.os.ui.components

import com.jarvis.os.ui.theme.OrbStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No theme's orb may leave its frame.
 *
 * This test exists because one did. The Orbit orb shipped with its widest ring
 * cut off at both edges — "the orbit theme's orb is getting cut" — and nothing in
 * the spec looked wrong: the ring is 1.55 orb radii, the orb is drawn at 0.86 of
 * the half-frame, and 1.55 x 0.86 = 1.33 is only obviously too big once someone
 * writes it down. Perspective then made it worse, because the near side of a
 * tilted ring projects larger, and the tilts precess, so the worst case happens
 * at a moment no still frame of the spec would show.
 *
 * A number that is only checkable by looking at a phone is a number that ships
 * wrong. This checks it in milliseconds instead.
 */
class OrbFitTest {

    @Test
    fun `no theme draws outside its frame`() {
        OrbStyle.entries.forEach { style ->
            val reach = extentFor(style) * fitFor(style)
            assertTrue(
                "$style reaches $reach of the half-frame — anything over 1.0 is cut off " +
                    "(extent ${extentFor(style)}, drawn at ${fitFor(style)})",
                reach <= 1.0f,
            )
        }
    }

    @Test
    fun `the fix did not quietly shrink every orb`() {
        // fitFor could satisfy the test above by making everything tiny. The
        // point is that a design which already fits keeps its full size, and only
        // one that would genuinely clip is pulled in. As the four specs stand,
        // none should be shrunk at all.
        OrbStyle.entries.forEach { style ->
            assertEquals(
                "$style is being shrunk to fit — retune its rings instead of " +
                    "accepting a smaller orb",
                PREFERRED_FILL,
                fitFor(style),
                1e-4f,
            )
        }
    }

    @Test
    fun `every orb still uses most of the room it has`() {
        // The other failure direction: a theme so conservative it floats in the
        // middle of a large empty frame. Anything under about two thirds of the
        // half-frame reads as an orb that failed to load.
        OrbStyle.entries.forEach { style ->
            val reach = extentFor(style) * fitFor(style)
            assertTrue("$style only reaches $reach of its frame — it will look lost", reach > 0.66f)
        }
    }

    @Test
    fun `Orbit is the specific case this exists for`() {
        // Pinned as a regression: the shipped value was 1.55, which does not fit.
        val widest = specFor(OrbStyle.Orbit).rings.maxOf { it.radius }
        assertTrue(
            "Orbit's widest ring is back above the width that shipped clipped ($widest)",
            widest < 1.40f,
        )
        assertTrue(
            "Orbit should still have an orbit outside the body — that is the design",
            widest > 1.0f,
        )
    }

    @Test
    fun `the extent measured is the extent that gets drawn`() {
        // The renderer precesses tilts by exactly these swings. If the two ever
        // drift apart, the fit check sweeps a range the renderer does not use and
        // a ring clips at a phase nothing looked at.
        assertEquals(0.45f, PRECESS_SWING_X, 1e-6f)
        assertEquals(0.55f, PRECESS_SWING_Y, 1e-6f)
        assertEquals(3.4f, CAMERA_DISTANCE, 1e-6f)
        assertEquals(2.55f, FOCAL, 1e-6f)
    }

    @Test
    fun `a wider ring always reaches further`() {
        // Sanity on the measurement itself: monotonic in radius, or it is not
        // measuring what it claims to.
        val small = Orb3D.ringExtent(0.5f, 0.3f, 0.1f, 0.45f, 0.55f, CAMERA_DISTANCE, FOCAL)
        val large = Orb3D.ringExtent(1.5f, 0.3f, 0.1f, 0.45f, 0.55f, CAMERA_DISTANCE, FOCAL)
        assertTrue("extent is not monotonic in radius", large > small)
    }

    @Test
    fun `extent is scale-invariant, which is why it can be measured once`() {
        // Camera, focal length and ring all scale together, so the answer in orb
        // radii is the same at any pixel size — which is what lets one cached
        // number serve a 92dp preview and a 280dp home orb alike.
        val unit = Orb3D.ringExtent(1.2f, 0.3f, -0.1f, 0.45f, 0.55f, CAMERA_DISTANCE, FOCAL)
        val big = Orb3D.ringExtent(1.2f * 40f, 0.3f, -0.1f, 0.45f, 0.55f, CAMERA_DISTANCE * 40f, FOCAL * 40f)
        assertEquals("extent should not depend on the pixel size", unit, big / 40f, 1e-3f)
    }

    @Test
    fun `perspective is accounted for, not ignored`() {
        // A ring tilted toward the camera must measure LARGER than the same ring
        // seen flat on. If these came out equal, the measurement would have
        // silently dropped the perspective divide — which is most of what made
        // Orbit overflow in the first place.
        val flat = Orb3D.ringExtent(1.2f, 0f, 0f, 0f, 0f, CAMERA_DISTANCE, FOCAL)
        val tilted = Orb3D.ringExtent(1.2f, 0.7f, 0.6f, 0f, 0f, CAMERA_DISTANCE, FOCAL)
        assertTrue("a tilted ring should reach further than a flat one", tilted > flat)
    }
}
