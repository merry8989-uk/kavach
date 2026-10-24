package com.kavach.caller.data.repo

import android.content.Context
import com.kavach.caller.data.calendar.CalendarGateway
import com.kavach.caller.data.local.dao.ReminderDao
import com.kavach.caller.data.local.entity.ReminderEntity
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminders about a person, local or in the device calendar.
 *
 * The two destinations are not variations on one thing, and the difference is
 * kept visible all the way to the screen:
 *
 * - **local** — Kavach notifies. Always available, no account, nothing leaves the
 *   phone.
 * - **calendar** — an event is written through `CalendarContract`. If a Google
 *   account is synced, Google Calendar is one of the calendars offered and the
 *   event syncs to it. Kavach holds no Google credentials and calls no Google
 *   API — see [CalendarGateway].
 *
 * A calendar reminder is still stored here as well, so it shows up in Kavach's
 * own list and can take its event with it when deleted.
 */
@Singleton
class ReminderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ReminderDao,
    private val calendar: CalendarGateway,
) {

    sealed interface Outcome {
        data class Saved(val id: Long, val inCalendar: Boolean, val calendarName: String?) : Outcome
        /** Saved locally, but the calendar could not take it — and why. */
        data class SavedLocalOnly(val id: Long, val reason: String) : Outcome
    }

    suspend fun pending(): List<ReminderEntity> = withContext(Dispatchers.IO) {
        runCatching { dao.pending() }.getOrDefault(emptyList())
    }

    suspend fun forNumber(number: String): List<ReminderEntity> = withContext(Dispatchers.IO) {
        runCatching { dao.forNumber(PhoneUtils.toE164(number)) }.getOrDefault(emptyList())
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        runCatching { dao.pendingCount() }.getOrDefault(0)
    }

    /**
     * Save a reminder.
     *
     * When the calendar is asked for and cannot be used, the reminder is still
     * saved locally and the **reason** comes back with it. Dropping it silently
     * would be the worst outcome: the user would believe a reminder existed
     * somewhere and find out otherwise at the wrong moment.
     */
    suspend fun add(
        number: String?,
        title: String,
        note: String?,
        dueAt: Long,
        toCalendar: Boolean,
        calendarId: Long? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        val key = number?.takeIf { it.isNotBlank() }?.let { PhoneUtils.toE164(it) }

        if (!toCalendar) {
            val id = dao.insert(
                ReminderEntity(
                    contactKey = key, title = title, note = note, dueAt = dueAt,
                    destination = ReminderEntity.DESTINATION_LOCAL,
                ),
            )
            return@withContext Outcome.Saved(id, inCalendar = false, calendarName = null)
        }

        when (val result = calendar.addEvent(title, note, dueAt, calendarId = calendarId)) {
            is CalendarGateway.Result.Added -> {
                val id = dao.insert(
                    ReminderEntity(
                        contactKey = key, title = title, note = note, dueAt = dueAt,
                        destination = ReminderEntity.DESTINATION_CALENDAR,
                        calendarEventId = result.eventId,
                    ),
                )
                Outcome.Saved(id, inCalendar = true, calendarName = result.calendarName)
            }
            CalendarGateway.Result.NoPermission ->
                saveLocalAndExplain(key, title, note, dueAt, "Kavach does not have permission to use the calendar.")
            CalendarGateway.Result.NoWritableCalendar ->
                saveLocalAndExplain(key, title, note, dueAt, "No calendar on this device can be written to. Add an account, or keep it in Kavach.")
            is CalendarGateway.Result.Failed ->
                saveLocalAndExplain(key, title, note, dueAt, result.reason)
        }
    }

    private suspend fun saveLocalAndExplain(
        key: String?, title: String, note: String?, dueAt: Long, reason: String,
    ): Outcome {
        val id = dao.insert(
            ReminderEntity(
                contactKey = key, title = title, note = note, dueAt = dueAt,
                destination = ReminderEntity.DESTINATION_LOCAL,
            ),
        )
        return Outcome.SavedLocalOnly(id, reason)
    }

    /** Delete the reminder, and its calendar event when it made one. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val reminder = dao.byId(id)
        reminder?.calendarEventId?.let { calendar.removeEvent(it) }
        dao.delete(id)
    }

    suspend fun markDone(id: Long) = withContext(Dispatchers.IO) {
        dao.markDone(id, System.currentTimeMillis())
    }

    /** Fired by the worker; marks them so the same one does not fire twice. */
    suspend fun due(now: Long = System.currentTimeMillis()): List<ReminderEntity> =
        withContext(Dispatchers.IO) {
            runCatching { dao.due(now) }.getOrDefault(emptyList())
        }

    suspend fun markNotified(id: Long) = withContext(Dispatchers.IO) {
        dao.markNotified(id, System.currentTimeMillis())
    }
}
