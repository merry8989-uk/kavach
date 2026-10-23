package com.kavach.caller

import com.kavach.caller.domain.CallerVerdict
import com.kavach.caller.domain.CallerVerdict.Level
import com.kavach.caller.domain.model.CallerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording is the feature here, so it is tested rather than eyeballed.
 *
 * Every one of these rules is a claim the app makes about somebody's phone
 * number. The two that matter most:
 *
 * - a warning always carries a **reason**, and the reason attributes the claim
 *   to whoever made it;
 * - nothing is ever called verified that is not, and no name is ever invented.
 */
class CallerVerdictTest {

    private fun identity(
        name: String? = null,
        company: String? = null,
        score: Int = 0,
        reports: Int = 0,
        source: CallerIdentity.Source = CallerIdentity.Source.NONE,
        confidence: Float = 0f,
        tags: List<String> = emptyList(),
    ) = CallerIdentity(
        number = "+919876543210",
        name = name,
        company = company,
        tags = tags,
        spamScore = score,
        reports = reports,
        source = source,
        confidence = confidence,
    )

    // ------------------------------------------------------------ reasons --

    @Test
    fun `a spam warning always carries a reason`() {
        listOf(
            identity(score = 95),
            identity(score = 50),
            identity(reports = 30),
            identity(reports = 1, score = 45),
        ).forEach { id ->
            val v = CallerVerdict.of(id, numberIsBlank = false)
            assertTrue("empty reason for ${v.headline}", v.reason.isNotBlank())
        }
    }

    @Test
    fun `the reason names who is making the claim`() {
        val v = CallerVerdict.of(identity(score = 90, reports = 12), numberIsBlank = false)
        assertTrue(
            "a community claim must be attributed: ${v.reason}",
            v.reason.contains("Reported by 12 people"),
        )
        assertTrue("must not read as a finding", v.reason.contains("community report"))
        assertTrue(v.isCommunityAllegation)
    }

    @Test
    fun `a single report is described as thin evidence`() {
        val v = CallerVerdict.of(identity(score = 45, reports = 1), numberIsBlank = false)
        assertEquals(Level.SUSPECTED, v.level)
        assertTrue("should not overstate one report: ${v.reason}", v.reason.contains("thin evidence"))
    }

    @Test
    fun `a high score with no reports does not claim anyone reported it`() {
        val v = CallerVerdict.of(identity(score = 88), numberIsBlank = false)
        assertEquals(Level.HIGH_RISK, v.level)
        assertTrue(
            "must not invent reporters: ${v.reason}",
            v.reason.contains("No one has reported it"),
        )
    }

    @Test
    fun `a report category is shown when there is one`() {
        val v = CallerVerdict.of(
            identity(score = 80, reports = 6, tags = listOf("Telemarketing")),
            numberIsBlank = false,
        )
        assertTrue("category missing: ${v.reason}", v.reason.contains("telemarketing"))
    }

    // ------------------------------------------------------------- levels --

    @Test
    fun `a saved contact outranks a community report about them`() {
        // Someone in the address book should not be flagged to the user because
        // strangers reported the number.
        val v = CallerVerdict.of(
            identity(name = "Amma", score = 90, reports = 40, source = CallerIdentity.Source.CONTACT),
            numberIsBlank = false,
        )
        assertEquals(Level.CONTACT, v.level)
        assertFalse(v.isWarning)
    }

    @Test
    fun `a listed business is not a warning`() {
        val v = CallerVerdict.of(
            identity(name = "City Clinic", company = "Healthcare", source = CallerIdentity.Source.BUSINESS),
            numberIsBlank = false,
        )
        assertEquals(Level.BUSINESS, v.level)
        assertFalse(v.isWarning)
    }

    @Test
    fun `a tentative community name is not presented as a verified one`() {
        val v = CallerVerdict.of(
            identity(
                name = "Ramesh Kumar",
                source = CallerIdentity.Source.CLOUD,
                confidence = 0.5f,
            ),
            numberIsBlank = false,
        )
        assertEquals(Level.NAMED, v.level)
        assertEquals("Possible name", v.headline)
        assertTrue("must say it is unverified: ${v.reason}", v.reason.contains("not a verified record"))
    }

    @Test
    fun `a confident name is still not called verified`() {
        val v = CallerVerdict.of(
            identity(name = "Ramesh Kumar", source = CallerIdentity.Source.LOCAL_DB, confidence = 0.95f),
            numberIsBlank = false,
        )
        assertEquals("Ramesh Kumar", v.headline)
        assertFalse("nothing here is verified", v.reason.contains("verified"))
    }

    // ------------------------------------------------------------ unknowns --

    @Test
    fun `nothing known says unknown rather than implying the caller is fine`() {
        val v = CallerVerdict.of(identity(), numberIsBlank = false)
        assertEquals(Level.UNKNOWN, v.level)
        assertEquals("Unknown caller", v.headline)
        assertFalse(v.isWarning)
    }

    @Test
    fun `a missing lookup says so rather than showing an empty card`() {
        val v = CallerVerdict.of(null, numberIsBlank = false)
        assertEquals(Level.UNKNOWN, v.level)
        assertTrue(v.reason.isNotBlank())
    }

    @Test
    fun `a withheld number is explained, not treated as suspicious`() {
        val v = CallerVerdict.of(null, numberIsBlank = true)
        assertEquals(Level.RESTRICTED, v.level)
        assertEquals("Private number", v.headline)
        assertFalse("withholding a number is not evidence of spam", v.isWarning)
    }

    // ----------------------------------------------------------- thresholds --

    @Test
    fun `the thresholds are the ones the app advertises`() {
        assertEquals(70, CallerVerdict.HIGH_RISK_SCORE)
        assertEquals(40, CallerVerdict.SUSPECTED_SCORE)
        assertEquals(20, CallerVerdict.HIGH_RISK_REPORTS)
    }

    @Test
    fun `a score just below a threshold does not cross it`() {
        assertEquals(Level.UNKNOWN, CallerVerdict.of(identity(score = 39), false).level)
        assertEquals(Level.SUSPECTED, CallerVerdict.of(identity(score = 40), false).level)
        assertEquals(Level.SUSPECTED, CallerVerdict.of(identity(score = 69), false).level)
        assertEquals(Level.HIGH_RISK, CallerVerdict.of(identity(score = 70), false).level)
    }

    @Test
    fun `enough independent reports warn on their own, without a score`() {
        val v = CallerVerdict.of(identity(reports = 20, score = 0), numberIsBlank = false)
        assertEquals(Level.HIGH_RISK, v.level)
    }
}
