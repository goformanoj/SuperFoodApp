package com.jarvis.os.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Adds an event to the device calendar. If calendar write permission is granted
 * and a writable calendar exists, it inserts directly (silent). Otherwise it
 * opens the calendar app's "new event" screen pre-filled, so it works without
 * permission too.
 */
object CalendarWriter {

    fun addEvent(context: Context, title: String, startMillis: Long, durationMin: Int): Boolean {
        val endMillis = startMillis + durationMin.coerceAtLeast(1) * 60_000L

        if (hasPermission(context)) {
            val calendarId = writableCalendarId(context)
            if (calendarId != null) {
                val inserted = try {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.DTSTART, startMillis)
                        put(CalendarContract.Events.DTEND, endMillis)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.CALENDAR_ID, calendarId)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    }
                    context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
                } catch (e: Exception) {
                    false
                }
                if (inserted) return true
            }
        }
        return openInsertScreen(context, title, startMillis, endMillis)
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun writableCalendarId(context: Context): Long? {
        return try {
            val projection = arrayOf(CalendarContract.Calendars._ID)
            val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= " +
                "${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun openInsertScreen(
        context: Context,
        title: String,
        startMillis: Long,
        endMillis: Long,
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
