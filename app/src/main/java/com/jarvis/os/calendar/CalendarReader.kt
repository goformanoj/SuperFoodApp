package com.jarvis.os.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Reads events from the real device calendar. */
object CalendarReader {

    /**
     * Today's events as "Title at HH:MM" strings, ordered by start time.
     * Returns null if calendar read permission is missing (so callers can tell
     * "no access" apart from "nothing scheduled").
     */
    fun todaysEvents(context: Context): List<String>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startOfDayMillis())
            ContentUris.appendId(builder, endOfDayMillis())
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
            )
            val events = mutableListOf<String>()
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                while (c.moveToNext()) {
                    val raw = c.getString(0)
                    val title = if (raw.isNullOrBlank()) "(untitled)" else raw
                    val begin = c.getLong(1)
                    events.add("$title at ${timeFormat.format(Date(begin))}")
                }
            }
            events
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun startOfDayMillis(): Long = dayBoundary(0, 0, 0, 0)
    private fun endOfDayMillis(): Long = dayBoundary(23, 59, 59, 999)

    private fun dayBoundary(hour: Int, min: Int, sec: Int, ms: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, min)
        cal.set(Calendar.SECOND, sec)
        cal.set(Calendar.MILLISECOND, ms)
        return cal.timeInMillis
    }
}
