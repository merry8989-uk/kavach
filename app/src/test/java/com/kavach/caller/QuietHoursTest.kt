package com.kavach.caller

import com.kavach.caller.domain.QuietHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Quiet hours.
 *
 * Every case here is a way to wake somebody up at 3am. The window that crosses
 * midnight is the one that is almost always written wrong, and the exceptions are
 * the ones that make the feature safe to leave on.
 */
class QuietHoursTest {

    private fun at(hour: Int, minute: Int = 0, day: Int = Calendar.MONDAY): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private val overnight = QuietHours.Window(22, 0, 7, 0)
    private val daytime = QuietHours.Window(13, 0, 15, 0)

    // ------------------------------------------------------- the ordinary case --

    @Test
    fun `a simple window contains the hours inside it`() {
        assertTrue(QuietHours.isInside(daytime, at(13, 30)))
        assertTrue(QuietHours.isInside(daytime, at(14, 59)))
    }

    @Test
    fun `a simple window excludes the hours outside it`() {
        assertFalse(QuietHours.isInside(daytime, at(12, 59)))
        assertFalse(QuietHours.isInside(daytime, at(15, 0)))
    }

    @Test
    fun `the start minute is inside and the end minute is not`() {
        // A call at 15:00 sharp is an afternoon call, not a quiet one.
        assertTrue(QuietHours.isInside(daytime, at(13, 0)))
        assertFalse(QuietHours.isInside(daytime, at(15, 0)))
    }

    // --------------------------------------------------- the midnight crossing --

    @Test
    fun `an overnight window contains the late evening`() {
        assertTrue(QuietHours.isInside(overnight, at(22, 30)))
        assertTrue(QuietHours.isInside(overnight, at(23, 59)))
    }

    @Test
    fun `an overnight window contains the small hours`() {
        assertTrue(QuietHours.isInside(overnight, at(0, 30)))
        assertTrue(QuietHours.isInside(overnight, at(3, 0)))
        assertTrue(QuietHours.isInside(overnight, at(6, 59)))
    }

    @Test
    fun `an overnight window excludes the day`() {
        // The failure this prevents: `hour in 22..7` is false for every hour, so
        // quiet hours silently does nothing.
        assertFalse(QuietHours.isInside(overnight, at(7, 0)))
        assertFalse(QuietHours.isInside(overnight, at(12, 0)))
        assertFalse(QuietHours.isInside(overnight, at(21, 59)))
    }

    @Test
    fun `crossing midnight is detected`() {
        assertTrue(overnight.crossesMidnight)
        assertFalse(daytime.crossesMidnight)
    }

    // ------------------------------------------------------------- day of week --

    @Test
    fun `a window with no days applies every day`() {
        assertTrue(QuietHours.isInside(overnight, at(23, 0, Calendar.SATURDAY)))
    }

    @Test
    fun `a window restricted to a day only applies that day`() {
        val mondayOnly = overnight.copy(days = setOf(Calendar.MONDAY))
        assertTrue(QuietHours.isInside(mondayOnly, at(23, 0, Calendar.MONDAY)))
        assertFalse(QuietHours.isInside(mondayOnly, at(23, 0, Calendar.TUESDAY)))
    }

    @Test
    fun `the day that decides is the day the call arrives`() {
        // "Weeknights only" must not silence Saturday morning because Friday
        // night's window spilled over. Saturday 2am is Saturday, and the user did
        // not ask for Saturday to be quiet.
        val weeknights = overnight.copy(
            days = setOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY,
            ),
        )
        assertTrue(QuietHours.isInside(weeknights, at(2, 0, Calendar.SATURDAY)).not())
        assertTrue(QuietHours.isInside(weeknights, at(2, 0, Calendar.FRIDAY)))
    }

    // ------------------------------------------------------------- the decision --

    @Test
    fun `no window means always ring`() {
        assertEquals(
            QuietHours.Action.RING,
            QuietHours.decide(null, at(3, 0), "+919876543210", false, false),
        )
    }

    @Test
    fun `outside the window it rings`() {
        assertEquals(
            QuietHours.Action.RING,
            QuietHours.decide(overnight, at(12, 0), "+919876543210", false, false),
        )
    }

    @Test
    fun `inside the window it applies the chosen action`() {
        assertEquals(
            QuietHours.Action.SILENCE,
            QuietHours.decide(overnight, at(2, 0), "+919876543210", false, false),
        )
        assertEquals(
            QuietHours.Action.REJECT,
            QuietHours.decide(overnight, at(2, 0), "+919876543210", false, false, action = QuietHours.Action.REJECT),
        )
    }

    @Test
    fun `a favourite always rings`() {
        assertEquals(
            QuietHours.Action.RING,
            QuietHours.decide(overnight, at(2, 0), "+919876543210", isFavourite = true, isRepeat = false),
        )
    }

    @Test
    fun `a repeat call always rings`() {
        // The classic emergency signal. Silencing it is how quiet hours becomes
        // dangerous.
        assertEquals(
            QuietHours.Action.RING,
            QuietHours.decide(overnight, at(2, 0), "+919876543210", isFavourite = false, isRepeat = true),
        )
    }

    @Test
    fun `an emergency number always rings`() {
        assertEquals(
            QuietHours.Action.RING,
            QuietHours.decide(
                overnight, at(2, 0), "112", false, false,
                emergencyNumbers = setOf("112", "108"),
            ),
        )
    }

    @Test
    fun `an ordinary stranger is silenced`() {
        assertEquals(
            QuietHours.Action.SILENCE,
            QuietHours.decide(
                overnight, at(2, 0), "+919999999999", false, false,
                emergencyNumbers = setOf("112"),
            ),
        )
    }
}
