package com.jarvis.os.desktop.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FolderListTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(parent: File, name: String) = File(parent, name).also { it.mkdirs() }
    private fun file(parent: File, name: String, text: String = "x") = File(parent, name).also { it.writeText(text) }

    @Test
    fun foldersComeFirstThenFilesAlphabetically() {
        val root = tmp.newFolder("proj")
        dir(root, "zeta"); dir(root, "Alpha"); file(root, "b.txt"); file(root, "a.txt", "hello")
        val l = FolderList.list(root)!!
        assertEquals(listOf("Alpha", "zeta", "a.txt", "b.txt"), l.entries.map { it.name })
        assertTrue(l.entries[0].isDir)
        assertEquals(5L, l.entries.first { it.name == "a.txt" }.size)
        assertEquals(4, l.total)
    }

    @Test
    fun secretsAreLeftOutAndOnlyCounted() {
        val root = tmp.newFolder("proj")
        file(root, "README.md"); file(root, ".env"); file(root, "local.properties"); file(root, "server.key"); file(root, "id_rsa")
        val l = FolderList.list(root)!!
        assertEquals(listOf("README.md"), l.entries.map { it.name })
        assertEquals(4, l.hidden)
    }

    @Test
    fun aLongFolderIsCappedButTheTotalIsHonest() {
        val root = tmp.newFolder("big")
        repeat(90) { file(root, "f%03d.txt".format(it)) }
        val l = FolderList.list(root, max = 60)!!
        assertEquals(60, l.entries.size)
        assertEquals(90, l.total)
    }

    @Test
    fun aMissingFolderIsNullNotACrash() {
        assertNull(FolderList.list(File(tmp.root, "nope")))
    }

    @Test
    fun findIgnoresCaseSpacesAndPunctuation() {
        val root = tmp.newFolder("home")
        dir(dir(root, "Pranjal"), "SuperFoodApp")
        assertNotNull(FolderList.find("super food app", listOf(root)).singleOrNull())
        assertNotNull(FolderList.find("superfoodapp", listOf(root)).singleOrNull())
        assertEquals("SuperFoodApp", FolderList.find("Super-Food-App", listOf(root)).single().name)
    }

    @Test
    fun findNeverDescendsIntoNoisyPlaces() {
        val root = tmp.newFolder("home")
        dir(dir(root, "node_modules"), "target")
        dir(dir(root, "AppData"), "target")
        assertTrue(FolderList.find("target", listOf(root)).isEmpty())
    }

    @Test
    fun findStopsAtTheDepthLimitAndPrefersTheNearest() {
        val root = tmp.newFolder("home")
        dir(dir(dir(dir(dir(dir(root, "a"), "b"), "c"), "d"), "e"), "deep")
        assertTrue("six levels down is out of reach", FolderList.find("deep", listOf(root), maxDepth = 4).isEmpty())
        dir(root, "deep")
        val hits = FolderList.find("deep", listOf(root), maxDepth = 6)
        assertEquals(root.path, hits.first().parentFile.path)
    }

    @Test
    fun findGivesUpWhenTimeRunsOut() {
        val root = tmp.newFolder("home")
        dir(dir(root, "a"), "target")
        var t = 0L
        // A clock that jumps a second per look: the deadline passes before the second level is reached.
        val hits = FolderList.find("target", listOf(root), limitMs = 500, clock = { t += 1_000_000_000L; t })
        assertFalse(hits.isNotEmpty())
    }
}
