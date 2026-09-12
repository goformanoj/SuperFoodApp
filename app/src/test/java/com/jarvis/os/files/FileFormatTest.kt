package com.jarvis.os.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Files grouping/summary — pure, so the buckets and sizes are pinned off-device. */
class FileFormatTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_757_419_200_000L // 2026-09-09T12:00:00Z
    private val DAY = 24L * 60 * 60 * 1000

    @Test
    fun `files bucket by local date`() {
        assertEquals(FileFormat.Bucket.TODAY, FileFormat.bucket(now - 60_000, now, utc))
        assertEquals(FileFormat.Bucket.TODAY, FileFormat.bucket(now - 11 * 60 * 60 * 1000, now, utc)) // 1am today
        assertEquals(FileFormat.Bucket.WEEK, FileFormat.bucket(now - DAY, now, utc))
        assertEquals(FileFormat.Bucket.WEEK, FileFormat.bucket(now - 6 * DAY, now, utc))
        assertEquals(FileFormat.Bucket.EARLIER, FileFormat.bucket(now - 7 * DAY, now, utc))
    }

    @Test
    fun `human size steps from bytes to MB`() {
        assertEquals("500 B", FileFormat.humanSize(500))
        assertEquals("1 KB", FileFormat.humanSize(1024))
        assertEquals("34 KB", FileFormat.humanSize(34_000))
        assertEquals("2.0 MB", FileFormat.humanSize(2L * 1024 * 1024))
    }

    @Test
    fun `summary counts each kind and totals the size`() {
        val s = FileFormat.summary(
            listOf(
                FileFormat.Item("PDF", 34_000),
                FileFormat.Item("PDF", 22_000),
                FileFormat.Item("note", 2_000),
            ),
        )
        assertTrue(s, s.contains("2 PDFs"))
        assertTrue(s, s.contains("1 note"))
        assertTrue(s, s.endsWith("KB"))
    }

    @Test
    fun `empty summary says so`() {
        assertEquals("Nothing stored yet", FileFormat.summary(emptyList()))
    }
}
