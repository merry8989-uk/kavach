package com.kavach.caller

import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.domain.ScorePolicy
import com.kavach.caller.domain.ScorePolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This is the one place in the app where a wrong default kills a real call, so
 * every rule the review asked for is pinned here.
 */
class ScorePolicyTest {

    @Test
    fun `off by default means the score never acts`() {
        // threshold 0 is the shipped default.
        listOf(1, 50, 99, 100).forEach { score ->
            val d = ScorePolicy.decide(threshold = 0, mode = Mode.SILENCE, score = score, isSavedContact = false)
            assertEquals("score $score should not act", BlockAction.ALLOW, d.action)
            assertFalse(d.fromScore)
        }
    }

    @Test
    fun `a saved contact is never acted on, however high the score`() {
        val d = ScorePolicy.decide(threshold = 50, mode = Mode.BLOCK, score = 100, isSavedContact = true)
        assertEquals(BlockAction.ALLOW, d.action)
        assertFalse(d.fromScore)
        assertTrue("the reason should say why", d.reason!!.contains("contacts"))
    }

    @Test
    fun `the score only acts at or above the chosen threshold`() {
        assertEquals(
            BlockAction.ALLOW,
            ScorePolicy.decide(50, Mode.BLOCK, score = 49, isSavedContact = false).action,
        )
        assertEquals(
            BlockAction.BLOCK,
            ScorePolicy.decide(50, Mode.BLOCK, score = 50, isSavedContact = false).action,
        )
    }

    @Test
    fun `silence is gentler than block and both are honoured`() {
        assertEquals(BlockAction.SILENCE, ScorePolicy.decide(60, Mode.SILENCE, 80, false).action)
        assertEquals(BlockAction.BLOCK, ScorePolicy.decide(60, Mode.BLOCK, 80, false).action)
    }

    @Test
    fun `warn allows the call and leaves the warning to the verdict`() {
        val d = ScorePolicy.decide(60, Mode.WARN, 90, isSavedContact = false)
        assertEquals("warn must not reject a call", BlockAction.ALLOW, d.action)
        assertFalse("nothing was enforced, so it did not come from the score", d.fromScore)
    }

    @Test
    fun `a decision from the score says so, and why`() {
        val d = ScorePolicy.decide(55, Mode.SILENCE, 72, isSavedContact = false)
        assertTrue(d.fromScore)
        assertTrue("the log needs the number: ${d.reason}", d.reason!!.contains("72"))
        assertTrue(d.reason!!.contains("55"))
    }

    @Test
    fun `a plain allow carries no reason`() {
        val d = ScorePolicy.decide(0, Mode.OFF, 90, isSavedContact = false)
        assertNull(d.reason)
    }

    @Test
    fun `an unknown mode string is treated as off, not as a guess`() {
        assertEquals(Mode.OFF, ScorePolicy.modeOf("nonsense"))
        assertEquals(Mode.OFF, ScorePolicy.modeOf(null))
        assertEquals(Mode.OFF, ScorePolicy.modeOf(""))
        assertEquals(Mode.SILENCE, ScorePolicy.modeOf("silence"))
        assertEquals(Mode.BLOCK, ScorePolicy.modeOf("BLOCK"))
    }

    @Test
    fun `a negative threshold is off, not a very strict setting`() {
        assertEquals(
            BlockAction.ALLOW,
            ScorePolicy.decide(threshold = -5, mode = Mode.BLOCK, score = 100, isSavedContact = false).action,
        )
    }

    @Test
    fun `every mode has a label a person can read`() {
        Mode.entries.forEach { mode ->
            assertTrue("no label for $mode", ScorePolicy.label(mode).isNotBlank())
        }
    }
}
