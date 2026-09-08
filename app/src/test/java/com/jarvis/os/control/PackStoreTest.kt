package com.jarvis.os.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PackStore] is the in-memory bridge from a fetched pack to the executor. Pure and
 * off-device: the fetch is elsewhere, what matters here is that a held pack answers
 * the right labels and that a stale one is flagged for refresh.
 */
class PackStoreTest {

    private val now = 1_725_000_000_000L

    private fun pack(pkg: String, fetchedAt: Long, controls: Map<String, List<String>>) =
        AppPack(pkg, version = 1, controls = controls, fetchedAt = fetchedAt)

    @Before fun setUp() = PackStore.clear()
    @After fun tearDown() = PackStore.clear()

    @Test
    fun `a held pack answers its labels, keyed case-insensitively`() {
        PackStore.put(pack("com.grofers.customerapp", now, mapOf("search" to listOf("Search for atta, dal, coke and more"))))

        assertEquals(
            listOf("Search for atta, dal, coke and more"),
            PackStore.candidatesFor("COM.Grofers.CustomerApp", "search"),
        )
    }

    @Test
    fun `an unknown app or intent yields nothing`() {
        PackStore.put(pack("com.grofers.customerapp", now, mapOf("search" to listOf("x"))))

        assertTrue(PackStore.candidatesFor("com.whatsapp", "search").isEmpty())
        assertTrue(PackStore.candidatesFor("com.grofers.customerapp", "cart").isEmpty())
        assertTrue(PackStore.candidatesFor(null, "search").isEmpty())
    }

    @Test
    fun `missing or expired pack is stale, a fresh one is not`() {
        assertTrue("nothing held ⇒ stale", PackStore.isStale("com.grofers.customerapp", now))

        PackStore.put(pack("com.grofers.customerapp", now, mapOf("search" to listOf("x"))))
        assertFalse(PackStore.isStale("com.grofers.customerapp", now))
        assertFalse(PackStore.isStale("com.grofers.customerapp", now + PackStore.TTL_MS - 1))
        assertTrue(PackStore.isStale("com.grofers.customerapp", now + PackStore.TTL_MS + 1))
    }
}
