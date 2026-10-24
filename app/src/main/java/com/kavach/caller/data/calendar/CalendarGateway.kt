package com.kavach.caller.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writing a reminder into the device's calendar.
 *
 * **What this is, precisely.** It writes through `CalendarContract` to the
 * calendar provider Android already has. If the user has a Google account synced,
 * Google Calendar is one of the calendars that provider exposes, and the event
 * appears there — and on every other device signed into that account.
 *
 * **What this is not.** Kavach does not call the Google Calendar API, holds no
 * Google credentials, and has no account of its own. That matters for two
 * reasons: it is why there is nothing to sign into here, and it is why the app
 * can offer this without the calendar becoming somewhere data leaves the phone
 * without the user choosing it.
 *
 * Every method returns a result rather than throwing, because "there is no
 * writable calendar" is a normal state on a fresh device and has to be
 * reportable, not a crash.
 */
@Singleton
class CalendarGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Result {
        data class Added(val eventId: Long, val calendarName: String) : Result
        data object NoPermission : Result
        data object NoWritableCalendar : Result
        data class Failed(val reason: String) : Result
    }

    data class CalendarOption(
        val id: Long,
        val displayName: String,
        val accountName: String?,
        /** True when this is the account's default calendar. */
        val isPrimary: Boolean,
    )

    /**
     * True when a calendar can actually be written to.
     *
     * Not the same as "the permission is granted": a device with no account
     * synced has the permission and nowhere to write, and offering the option
     * then would be offering something that cannot happen.
     */
    fun canWrite(): Boolean =
        hasPermission() && runCatching { writableCalendars().isNotEmpty() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Every calendar the user could write to, with the primary one first. */
    fun writableCalendars(): List<CalendarOption> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        val found = mutableListOf<CalendarOption>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, selection, args,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    found += CalendarOption(
                        id = cursor.getLong(0),
                        displayName = cursor.getString(1) ?: "Calendar",
                        accountName = cursor.getString(2),
                        isPrimary = cursor.getInt(3) == 1,
                    )
                }
            }
        }
        return found
    }

    /**
     * Insert a reminder as an all-day or timed event.
     *
     * @param calendarId which calendar, or null for the primary one.
     */
    fun addEvent(
        title: String,
        note: String?,
        startMillis: Long,
        durationMinutes: Int = 30,
        calendarId: Long? = null,
    ): Result {
        if (!hasPermission()) return Result.NoPermission

        val target = calendarId
            ?: writableCalendars().firstOrNull()?.id
            ?: return Result.NoWritableCalendar

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, target)
            put(CalendarContract.Events.TITLE, title)
            note?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, startMillis + durationMinutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            // A reminder about a call, not a meeting to be invited to.
            put(CalendarContract.Events.HAS_ALARM, 1)
            put(CalendarContract.Events.EVENT_LOCATION, null as String?)
        }

        return runCatching {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return Result.Failed("the calendar provider refused the event")
            val id = ContentUris.parseId(uri)
            val name = writableCalendars().firstOrNull { it.id == target }?.displayName ?: "your calendar"
            Result.Added(eventId = id, calendarName = name)
        }.getOrElse { Result.Failed(it.message ?: "could not write to the calendar") }
    }

    /**
     * Remove an event this app inserted.
     *
     * Called when the user deletes the reminder. Without it the reminder vanishes
     * from Kavach and stays in the calendar, which reads as the app ignoring what
     * it was told.
     */
    fun removeEvent(eventId: Long): Boolean {
        if (!hasPermission()) return false
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    /** A calendar entry for a number, named the way a person would name it. */
    fun defaultTitle(name: String?, number: String): String =
        "Call ${name?.takeIf { it.isNotBlank() } ?: PhoneUtils.pretty(number)}"
}
