package com.mhmh2.bujirun.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * A finished workout as an event in the phone's calendar - the Google account's own calendar
 * where there is one, so it syncs to Google Calendar (and from there to anything that reads it)
 * with no sign-in of our own: the calendar provider does the syncing.
 */
object CalendarExport {
    val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun hasPermission(context: Context) = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun exists(context: Context, start: Long, title: String): Boolean = runCatching {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.DTSTART} = ? AND ${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(start.toString(), title), null,
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** The calendar to write to: a Google account's primary one first, else any writable one. */
    private fun calendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
        )
        val where = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        data class Cal(val id: Long, val google: Boolean, val primary: Boolean)
        val found = mutableListOf<Cal>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, where, null, null)?.use { c ->
            while (c.moveToNext()) {
                val google = c.getString(1) == "com.google"
                // IS_PRIMARY is newer and not always filled in; the account's own calendar is
                // also the one whose owner is the account.
                val primary = c.getInt(2) == 1 || (c.getString(3) != null && c.getString(3) == c.getString(4))
                found += Cal(c.getLong(0), google, primary)
            }
        }
        return found.sortedWith(compareByDescending<Cal> { it.google && it.primary }.thenByDescending { it.google }).firstOrNull()?.id
    }

    /**
     * Writes the event; returns false if there's no calendar to write to. Never twice: the
     * same workout can be written from several places (on save, on app open, the result
     * screen's switch and button), so an event already there with its title and start counts.
     */
    @Synchronized
    fun write(context: Context, w: WorkoutEntity, title: String, notes: String): Boolean {
        if (exists(context, w.startedAt, title)) return true
        val calendar = calendarId(context) ?: return false
        val end = w.startedAt + maxOf(w.durationMs, w.endedAt - w.startedAt)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, notes)
            put(CalendarContract.Events.DTSTART, w.startedAt)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            // A record of what was done, not something to keep time free for.
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
        }
        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
    }
}
