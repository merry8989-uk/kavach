package com.kavach.caller

import com.kavach.caller.domain.MissedCallPriority
import com.kavach.caller.domain.MissedCallPriority.MissedCall
import com.kavach.caller.domain.MissedCallPriority.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking has to be explainable, because a wrong priority is
 * indistinguishable from a right one unless the reasoning is visible.
 */
class MissedCallPriorityTest {

    private fun call(number: String, at: Long, name: String? = null, spam: Int = 0) =
        MissedCall(number = number, name = name, timestamp = at, spamScore = spam)

    @Test
    fun `a saved contact who called twice outranks everything else`() {
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500001", 1_000),
                call("+919876500002", 2_000),
                call("+919876500002", 2_500),
                call("+919876500003", 3_000),
                call("+919876500003", 3_100),
                call("+919876500003", 3_200),
            ),
            savedNumbers = setOf("+919876500002"),
        )

        assertEquals("+919876500002", ranked.first().number)
        assertEquals(Reason.CONTACT_REPEATED, ranked.first().reason)
    }

    @Test
    fun `a nuisance caller cannot outrank a saved contact by calling more`() {
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500010", 1_000),
                // Twenty calls from a stranger.
                *((0 until 20).map { call("+919876500011", 2_000L + it) }.toTypedArray()),
            ),
            savedNumbers = setOf("+919876500010"),
        )

        assertEquals("+919876500010", ranked.first().number)
    }

    @Test
    fun `volume can never lift a stranger past a saved contact`() {
        // The first version sorted on score alone: a stranger calling 20 times
        // reached 60 while a saved contact sat at 55, so the stranger won. Tiers
        // now decide the order and the score only breaks ties inside a tier.
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500015", 1_000),
                *((0 until 50).map { call("+919876500016", 2_000L + it) }.toTypedArray()),
            ),
            savedNumbers = setOf("+919876500015"),
        )

        assertEquals("+919876500015", ranked.first().number)
        assertEquals(Reason.CONTACT, ranked.first().reason)
        assertEquals(Reason.REPEATED, ranked[1].reason)
    }

    @Test
    fun `a number reported by many people is excluded, not ranked low`() {
        // Putting it at the bottom of a list invites calling it back.
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500020", 1_000, spam = 85),
                call("+919876500021", 2_000),
            ),
            savedNumbers = emptySet(),
        )

        assertEquals(1, ranked.size)
        assertEquals("+919876500021", ranked.first().number)
    }

    @Test
    fun `a mild spam score lowers the rank without excluding`() {
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500030", 1_000, spam = 40),
                call("+919876500031", 2_000, spam = 0),
            ),
            savedNumbers = emptySet(),
        )

        assertEquals("+919876500031", ranked.first().number)
        assertTrue("both should still be present", ranked.size == 2)
    }

    @Test
    fun `calls are grouped by number, and the count is carried`() {
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500040", 1_000),
                call("+919876500040", 1_500),
                call("+919876500040", 2_000),
            ),
            savedNumbers = emptySet(),
        )

        assertEquals(1, ranked.size)
        assertEquals(3, ranked.first().missedCount)
        assertEquals(2_000, ranked.first().lastMissedAt)
    }

    @Test
    fun `ties are broken by the most recent call`() {
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500050", 1_000),
                call("+919876500051", 9_000),
            ),
            savedNumbers = emptySet(),
        )

        assertEquals("+919876500051", ranked.first().number)
    }

    @Test
    fun `the name is taken from whichever entry carries one`() {
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500060", 1_000, name = null),
                call("+919876500060", 1_500, name = "Asha"),
            ),
            savedNumbers = setOf("+919876500060"),
        )

        assertEquals("Asha", ranked.first().name)
    }

    @Test
    fun `every score stays inside 0 to 100`() {
        val ranked = MissedCallPriority.rank(
            missed = (0 until 30).map { call("+919876500070", 1_000L + it) },
            savedNumbers = setOf("+919876500070"),
        )

        ranked.forEach {
            assertTrue("${it.score} out of range", it.score in 0..100)
        }
    }

    @Test
    fun `an empty list ranks to nothing rather than throwing`() {
        assertTrue(MissedCallPriority.rank(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun `every ranked call carries a reason a person can read`() {
        val ranked = MissedCallPriority.rank(
            missed = listOf(
                call("+919876500080", 1_000),
                call("+919876500081", 2_000, name = "Ravi"),
            ),
            savedNumbers = setOf("+919876500081"),
        )

        ranked.forEach {
            assertTrue(
                "no reason given for ${it.number}",
                it.reason in Reason.entries,
            )
        }
    }
}
