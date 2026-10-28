package com.kavach.caller.domain

import java.util.Calendar

/**
 * Quiet hours — when Kavach should not ring.
 *
 * ## Why this is a pure object
 *
 * The rule has more edge cases than it looks, and every one of them is a bug that
 * wakes somebody up at 3am:
 *
 * - **A window that crosses midnight.** 22:00 to 07:00 is the normal case and the
 *   naive `hour in start..end` test gets it exactly wrong.
 * - **The minute it starts and the minute it ends.** A window of 22:00–07:00
 *   includes 22:00 and excludes 07:00, because a call at 07:00 sharp is a morning
 *   call.
 * - **Days of the week.** "Weeknights only" must not silence a Saturday morning
 *   because Friday night's window spilled into it. The day that decides is the day
 *   the *call arrives*, not the day the window began.
 * - **Favourites and repeat callers.** Silencing a stranger at 3am is the point.
 *   Silencing your family is a different thing, and the second call from the same
 *   number within a few minutes is almost never a robocall.
 *
 * So the rule is testable in isolation, and [com.kavach.caller.domain.QuietHours]
 * is the only place it lives.
 */
object QuietHours {

    /** Days, in [Calendar] numbering: Sunday is 1. */
    data class Window(
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
        /** Empty means every day. */
        val days: Set<Int> = emptySet(),
    ) {
        val crossesMidnight: Boolean
            get() = endHour * 60 + endMinute <= startHour * 60 + startMinute

        /** Minutes since midnight, for comparison. */
        val startMinutes: Int get() = startHour * 60 + startMinute
        val endMinutes: Int get() = endHour * 60 + endMinute
    }

    /**
     * What to do with a call that arrives during quiet hours.
     *
     * Not a boolean, because "silence everything" is not the only sensible answer
     * and the difference is what makes the feature usable rather than turned off.
     */
    enum class Action {
        /** Ring normally. */
        RING,

        /** Ring, but make no sound. The call still appears and can be answered. */
        SILENCE,

        /** Decline without ringing. */
        REJECT,
    }

    /**
     * Whether [now] falls inside [window].
     *
     * `now` is passed in rather than read, so this is testable without waiting for
     * a particular hour.
     */
    fun isInside(window: Window, now: Calendar): Boolean {
        val day = now.get(Calendar.DAY_OF_WEEK)
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // Which window applies depends on whether we are in the evening part or
        // the morning part of a window that spans midnight.
        return if (window.crossesMidnight) {
            val inside = minutes >= window.startMinutes || minutes < window.endMinutes
            // The day check uses the day the **call arrives**, not the day the
            // window began — that is what a person means by "Sunday morning", and
            // it stops a Friday-night window silencing a Saturday morning the user
            // did not ask for.
            inside && (window.days.isEmpty() || day in window.days)
        } else {
            val inside = minutes >= window.startMinutes && minutes < window.endMinutes
            inside && (window.days.isEmpty() || day in window.days)
        }
    }

    /**
     * The decision for one call.
     *
     * @param window the quiet-hours window, or null when the feature is off
     * @param isFavourite a contact the user starred
     * @param isRepeat a second call from the same number inside [REPEAT_MINUTES]
     * @param emergencyNumbers numbers that always ring
     */
    fun decide(
        window: Window?,
        now: Calendar,
        number: String,
        isFavourite: Boolean,
        isRepeat: Boolean,
        emergencyNumbers: Set<String> = emptySet(),
        action: Action = Action.SILENCE,
    ): Action {
        if (window == null) return Action.RING
        if (!isInside(window, now)) return Action.RING

        // These three are the exceptions, and each is deliberate:
        //
        // - a favourite is someone the user chose to always hear from;
        // - a repeat call is the classic emergency signal, and silencing it is how
        //   quiet hours becomes dangerous;
        // - an emergency number is never silenced, whatever the settings say.
        if (isFavourite) return Action.RING
        if (isRepeat) return Action.RING
        if (number in emergencyNumbers) return Action.RING

        return action
    }

    /** Two calls from one number inside this window count as a repeat. */
    const val REPEAT_MINUTES = 5
}
