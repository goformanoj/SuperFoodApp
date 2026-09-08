package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PackClient.parse] turns the Worker's served pack JSON into an [com.jarvis.os.control.AppPack].
 * org.json → Robolectric, same as the other client parser tests; the network and disk
 * cache are device-only. A malformed pack must yield null rather than throw — a bad
 * server response can never break the executor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackClientParseTest {

    private val good = """
        {"version":1,"package":"com.grofers.customerapp","match":"grofers","name":"Blinkit",
         "controls":{"search":["Search for atta, dal, coke and more","Search for products"],
                     "cart":["My Cart","Cart"],"add":["ADD"]}}
    """.trimIndent()

    @Test
    fun `a valid pack parses into labels`() {
        val pack = PackClient.parse(good, fetchedAt = 42L)!!
        assertEquals("com.grofers.customerapp", pack.pkg)
        assertEquals(1, pack.version)
        assertEquals(42L, pack.fetchedAt)
        assertEquals(listOf("Search for atta, dal, coke and more", "Search for products"), pack.labelsFor("search"))
        assertEquals(listOf("ADD"), pack.labelsFor("add"))
        assertEquals(emptyList<String>(), pack.labelsFor("checkout"))
    }

    @Test
    fun `a pack with no package is rejected`() {
        assertNull(PackClient.parse("""{"version":1,"controls":{"search":["x"]}}""", 0L))
    }

    @Test
    fun `blank labels are dropped and empty controls are omitted`() {
        val pack = PackClient.parse(
            """{"package":"com.x","controls":{"search":["","  ","Real"],"cart":[]}}""",
            0L,
        )!!
        assertEquals(listOf("Real"), pack.labelsFor("search"))
        assertEquals(emptyList<String>(), pack.labelsFor("cart"))
    }

    @Test
    fun `malformed json yields null, not a throw`() {
        assertNull(PackClient.parse("not json", 0L))
        assertNull(PackClient.parse("", 0L))
    }

    @Test
    fun `a non-object controls field is treated as empty, and the pack still parses`() {
        // A wrong-shaped controls value must not crash — the pack parses with no
        // labels, which the executor reads as "nothing extra to try".
        val pack = PackClient.parse("""{"package":"com.x","controls":"nope"}""", 0L)!!
        assertEquals(emptyList<String>(), pack.labelsFor("search"))
    }
}
