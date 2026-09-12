package com.jarvis.os.files

import java.util.Calendar
import java.util.TimeZone

/**
 * How the Files screen groups and summarises artifacts — pure, so the date
 * bucketing, human sizes and the storage summary are unit-tested and only the
 * layout is left for CI. Buckets are LOCAL-day based (a file made after midnight
 * is "Today"), with a fixed zone injected in tests.
 */
object FileFormat {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    enum class Bucket(val label: String) { TODAY("Today"), WEEK("This week"), EARLIER("Earlier") }

    /** Which date group a file belongs to, relative to [now]. */
    fun bucket(createdMillis: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Bucket {
        val days = -dayDifference(createdMillis, now, zone) // files are in the past → 0..N ago
        return when {
            days <= 0 -> Bucket.TODAY
            days <= 6 -> Bucket.WEEK
            else -> Bucket.EARLIER
        }
    }

    /** Whole local days from [ref]'s day to [target]'s day (negative when target is earlier). */
    fun dayDifference(target: Long, ref: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val diff = floorDay(target, zone) - floorDay(ref, zone)
        return Math.floorDiv(diff, DAY_MS).toInt()
    }

    /** "820 B", "34 KB", "1.2 MB" — a size a person reads at a glance. */
    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
        else -> {
            val mb = bytes.toDouble() / (1024 * 1024)
            "%.1f MB".format(java.util.Locale.US, mb)
        }
    }

    /** One item for the summary line: its display kind and its size. */
    data class Item(val kindLabel: String, val sizeBytes: Long)

    /** "2 PDFs · 1 note · 58 KB" — counts per kind, then the total on device. */
    fun summary(items: List<Item>): String {
        if (items.isEmpty()) return "Nothing stored yet"
        val counts = LinkedHashMap<String, Int>()
        var total = 0L
        for (it in items) {
            counts[it.kindLabel] = (counts[it.kindLabel] ?: 0) + 1
            total += it.sizeBytes
        }
        val parts = counts.entries.map { (label, n) -> "$n $label" + if (n > 1) "s" else "" }
        return parts.joinToString(" · ") + " · " + humanSize(total)
    }

    private fun floorDay(ms: Long, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
