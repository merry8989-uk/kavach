package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reminder attached to a person, a number, or nothing in particular.
 *
 * Two destinations, and the difference is worth being precise about:
 *
 * - **Local** — Kavach notifies, and the reminder lives only here. Always works,
 *   needs no account, and is the default.
 * - **Device calendar** — an event is inserted through `CalendarContract` into a
 *   calendar the user picks. If they have a Google account synced, Google
 *   Calendar is one of those calendars and the event appears there. Kavach does
 *   not talk to Google's API and holds no Google credentials; it writes to the
 *   calendar provider, which is the supported way for an app to do this.
 *
 * [calendarEventId] records what was inserted so a deleted reminder can take its
 * event with it, rather than leaving orphans in someone's calendar.
 */
@Entity(
    tableName = "reminders",
    indices = [
        Index(value = ["dueAt"]),
        Index(value = ["contactKey"]),
    ],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The number this is about, when it is about one. */
    val contactKey: String? = null,
    /** Shown on the reminder and on the calendar event. */
    val title: String,
    val note: String? = null,
    val dueAt: Long,
    /** Where it went: "local" or "calendar". */
    val destination: String = DESTINATION_LOCAL,
    /**
     * The inserted calendar event id, when there is one.
     *
     * Kept so deleting the reminder can delete the event too. Without it a user
     * who removes a reminder finds it still sitting in their calendar a week
     * later, which reads as the app ignoring them.
     */
    val calendarEventId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * When the notification was fired. Null until then.
     *
     * This is what stops the reminder worker firing the same reminder every time
     * it runs — the difference between a reminder and an alarm.
     */
    val notifiedAt: Long? = null,
    /** Set when the user marks it done, or when the notification is dismissed. */
    val doneAt: Long? = null,
) {
    companion object {
        const val DESTINATION_LOCAL = "local"
        const val DESTINATION_CALENDAR = "calendar"
    }
}
