package com.jarvis.os.desktop

import com.jarvis.os.desktop.SingleInstance.Handoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theFirstCopyOwnsItAndASecondOneDoesNot() {
        SingleInstance(tmp.root).use { first ->
            assertTrue(first.claim { })
            // A second launch in the same user folder must be told "someone is already running".
            SingleInstance(tmp.root).use { second -> assertFalse(second.claim { }) }
        }
    }

    @Test
    fun aLaterLaunchAsksTheRunningCopyToShowItself() {
        val shown = CountDownLatch(1)
        SingleInstance(tmp.root).use { first ->
            assertTrue(first.claim { shown.countDown() })
            assertTrue(SingleInstance.signal(tmp.root, show = true))
            assertTrue("the running copy never got the request", shown.await(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aBackgroundLaunchDoesNotPopTheWindowUp() {
        // "Start with Windows" while JARVIS is already running: answered, but nothing visible happens.
        val shown = CountDownLatch(1)
        SingleInstance(tmp.root).use { first ->
            first.claim { shown.countDown() }
            assertTrue(SingleInstance.signal(tmp.root, show = false))
            assertFalse(shown.await(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun nobodyRunningMeansNobodyToSignal() {
        assertFalse(SingleInstance.signal(tmp.root, show = true, attempts = 2, waitMs = 10))
    }

    @Test
    fun closingReleasesItSoTheNextLaunchCanStart() {
        // The OS lock is what makes a crash harmless: once the first copy is gone, the next one is first.
        val first = SingleInstance(tmp.root)
        assertTrue(first.claim { })
        first.close()
        SingleInstance(tmp.root).use { assertTrue(it.claim { }) }
    }

    @Test
    fun aLeftOverPortFileFromADeadCopyIsNotTrusted() {
        tmp.root.resolve("instance.port").writeText("1")   // nothing listens there
        SingleInstance(tmp.root).use { first ->
            assertTrue(first.claim { })
            val port = tmp.root.resolve("instance.port").readText().trim().toInt()
            assertTrue("the port file should now be this copy's own", port > 1)
        }
    }

    @Test
    fun requestsAreParsedStrictly() {
        assertEquals(Handoff.SHOW, Handoff.parse("show"))
        assertEquals(Handoff.SHOW, Handoff.parse("  SHOW \n"))
        assertEquals(Handoff.QUIET, Handoff.parse("quiet"))
        assertNull(Handoff.parse("quit"))     // nothing a stranger sends can do more than show or not show
        assertNull(Handoff.parse(""))
        assertNull(Handoff.parse(null))
    }
}
