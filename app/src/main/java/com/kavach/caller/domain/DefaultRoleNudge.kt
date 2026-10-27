package com.kavach.caller.domain

/**
 * When to ask the user to make Kavach the default dialer and caller-ID app.
 *
 * The rules are here rather than in the screen or the worker because three
 * places need them — Settings, the app-open prompt, and the periodic
 * notification — and three copies of "is it time yet" is how they end up
 * disagreeing.
 *
 * ## The rules
 *
 * - **Never ask someone who has already set it.** The button is hidden and no
 *   nudge is scheduled; being asked for something you already did is the fastest
 *   way to make a prompt feel like nagging.
 * - **Never ask before the user has been asked once.** Kavach asks during setup.
 *   This is the reminder for someone who said "later", not a cold approach.
 * - **Two days between asks.** Not one: the first day after a "later" is when the
 *   user is most likely to still be deciding, and a prompt then reads as
 *   pressure.
 * - **Two days is a minimum, not a schedule.** If the phone was off for a week,
 *   the next open is the moment — there is no reason to wait for a window.
 */
object DefaultRoleNudge {

    /** How long "later" means. */
    const val LATER_DAYS = 2

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Whether to show the "set as default" prompt now.
     *
     * @param isDefault the app already holds every role it needs
     * @param scheduledAt when the user last said "later", or 0 if they never did
     * @param now the current time
     */
    fun shouldAsk(isDefault: Boolean, scheduledAt: Long, now: Long): Boolean {
        // Already done. Asking again is the thing that makes people turn
        // notifications off.
        if (isDefault) return false
        // Never said "later", so this is not the reminder's job. Setup asks once.
        if (scheduledAt <= 0L) return false
        return now >= scheduledAt
    }

    /** When "later" should come back round. */
    fun nextAskAt(now: Long): Long = now + LATER_DAYS * DAY_MS

    /**
     * Whether the Settings row should be shown at all.
     *
     * Same condition as the prompt: if it is already the default there is nothing
     * to offer, and a row that says "you have already done this" is a row that
     * wastes a tap.
     */
    fun shouldShowRow(isDefault: Boolean): Boolean = !isDefault
}
