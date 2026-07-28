package com.jarvis.os.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Reads events from the real device calendar. */
object CalendarReader {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WINDOW_DAYS = 7
    private const val MATCH_WINDOW_MS = 45L * 60 * 1000 // ±45 min when matching by time

    /**
     * Upcoming events (next [WINDOW_DAYS] days) as "Title — yyyy-MM-dd HH:mm".
     * Returns null if calendar read permission is missing.
     */
    fun upcomingEvents(context: Context): List<String>? {
        if (!hasReadPermission(context)) return null
        val start = System.currentTimeMillis()
        val end = start + WINDOW_DAYS * DAY_MS
        val out = mutableListOf<String>()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        queryInstances(
            context, start, end,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
        ) { c ->
            val raw = c.getString(0)
            val title = if (raw.isNullOrBlank()) "(untitled)" else raw
            out.add("$title — ${format.format(Date(c.getLong(1)))}")
        }
        return out
    }


    /** One real calendar event, for the UI. */
    data class Event(val title: String, val startMillis: Long, val allDay: Boolean) {
        /** "14:30", or "All day". */
        fun timeLabel(): String =
            if (allDay) "All day" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startMillis))
    }

    /**
     * The real events between now and the end of tomorrow, soonest first.
     * Returns null when calendar permission has not been granted — which the UI
     * shows differently from "you have nothing on", because they mean different
     * things and the old hardcoded list could say neither.
     */
    fun agenda(context: Context, days: Int = 2): List<Event>? {
        if (!hasReadPermission(context)) return null
        val start = System.currentTimeMillis()
        val end = start + days * DAY_MS
        val out = mutableListOf<Event>()
        queryInstances(
            context, start, end,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
            ),
        ) { c ->
            val raw = c.getString(0)
            out.add(
                Event(
                    title = if (raw.isNullOrBlank()) "(untitled)" else raw,
                    startMillis = c.getLong(1),
                    allDay = c.getInt(2) != 0,
                ),
            )
        }
        return out.sortedBy { it.startMillis }
    }

    /** Event IDs whose title matches on [date] (optionally near [time]), for deletion. */
    fun findMatchingEventIds(context: Context, title: String, date: String, time: String): List<Long> {
        if (!hasReadPermission(context)) return emptyList()
        val dayStart = CalendarActions.parseStart(date, "00:00") ?: return emptyList()
        val dayEnd = dayStart + DAY_MS
        val target = CalendarActions.parseStart(date, time)
        val ids = mutableListOf<Long>()
        queryInstances(
            context, dayStart, dayEnd,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
            ),
        ) { c ->
            val id = c.getLong(0)
            val rowTitle = c.getString(1) ?: ""
            val begin = c.getLong(2)
            if (titleMatches(rowTitle, title) && (target == null || abs(begin - target) <= MATCH_WINDOW_MS)) {
                ids.add(id)
            }
        }
        return ids.distinct()
    }

    private fun titleMatches(row: String, wanted: String): Boolean {
        val a = row.trim()
        val b = wanted.trim()
        if (b.isBlank()) return false
        return a.equals(b, ignoreCase = true) ||
            a.contains(b, ignoreCase = true) ||
            b.contains(a, ignoreCase = true)
    }

    private fun hasReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun queryInstances(
        context: Context,
        start: Long,
        end: Long,
        projection: Array<String>,
        onRow: (Cursor) -> Unit,
    ) {
        try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, start)
            ContentUris.appendId(builder, end)
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) onRow(c)
            }
        } catch (e: Exception) {
            // Ignore — treat as no events.
        }
    }
}
