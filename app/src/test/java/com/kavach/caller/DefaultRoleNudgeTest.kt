package com.kavach.caller

import com.kavach.caller.domain.DefaultRoleNudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app is allowed to ask to be made the default dialer.
 *
 * Three places use these rules — Settings, the app-open prompt and the daily
 * worker — so they are tested once here rather than trusted three times. The
 * failure mode is not a crash: it is a prompt that appears when it should not,
 * which teaches people to dismiss prompts without reading them.
 */
class DefaultRoleNudgeTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------ already done --

    @Test
    fun `someone who is already the default is never asked`() {
        // The setting says the app is the default, so there is nothing to remind.
        assertFalse(DefaultRoleNudge.shouldAsk(isDefault = true, scheduledAt = now - day, now = now))
    }

    @Test
    fun `being the default wins over a due reminder`() {
        // A user can grant the role before the reminder fires. The reminder must
        // not then appear for something already done.
        assertFalse(
            DefaultRoleNudge.shouldAsk(
                isDefault = true,
                scheduledAt = now - 10 * day,
                now = now,
            ),
        )
    }

    @Test
    fun `the settings row is hidden once it is the default`() {
        assertFalse(DefaultRoleNudge.shouldShowRow(isDefault = true))
        assertTrue(DefaultRoleNudge.shouldShowRow(isDefault = false))
    }

    // ---------------------------------------------------------- never scheduled --

    @Test
    fun `someone who was never asked is not nudged`() {
        // Setup asks once. This is the reminder for a "later", not a cold approach.
        assertFalse(DefaultRoleNudge.shouldAsk(isDefault = false, scheduledAt = 0L, now = now))
    }

    @Test
    fun `a negative timestamp is treated as unscheduled`() {
        assertFalse(DefaultRoleNudge.shouldAsk(isDefault = false, scheduledAt = -1L, now = now))
    }

    // -------------------------------------------------------------- the timing --

    @Test
    fun `later means two days, not one`() {
        val next = DefaultRoleNudge.nextAskAt(now)
        assertEquals(now + 2 * day, next)
    }

    @Test
    fun `one day after choosing later is too soon`() {
        assertFalse(
            DefaultRoleNudge.shouldAsk(
                isDefault = false,
                scheduledAt = DefaultRoleNudge.nextAskAt(now),
                now = now + day,
            ),
        )
    }

    @Test
    fun `exactly two days later it is due`() {
        assertTrue(
            DefaultRoleNudge.shouldAsk(
                isDefault = false,
                scheduledAt = DefaultRoleNudge.nextAskAt(now),
                now = now + 2 * day,
            ),
        )
    }

    @Test
    fun `a reminder missed by a week is still due`() {
        // Two days is a minimum, not a window. A phone that was off should be
        // asked on the next open rather than waiting for a schedule to line up.
        assertTrue(
            DefaultRoleNudge.shouldAsk(
                isDefault = false,
                scheduledAt = now - 7 * day,
                now = now,
            ),
        )
    }

    @Test
    fun `the interval is a constant, not a magic number in three places`() {
        assertEquals(2, DefaultRoleNudge.LATER_DAYS)
    }
}
