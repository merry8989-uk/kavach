package com.kavach.caller

import com.kavach.caller.ai.SpamScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamScorerTest {

    private val scorer = SpamScorer()

    @Test
    fun contactsAreNeverSpam() {
        assertEquals(0, scorer.score("9876543210", reports = 500, isContact = true))
    }

    @Test
    fun repeatedDigitsScoreHigh() {
        assertTrue(scorer.score("9999999999") >= 20)
    }

    @Test
    fun sequentialDigitsScoreHigh() {
        assertTrue(scorer.score("9876543210") >= 20)
    }

    @Test
    fun crowdReportsRaiseScore() {
        val low = scorer.score("9812345678", reports = 0)
        val high = scorer.score("9812345678", reports = 100)
        assertTrue(high > low)
    }
}
