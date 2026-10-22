package com.kavach.caller

import com.kavach.caller.data.diagnostics.SamplingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sampling was quietly eating the log.
 *
 * A real export held sixteen `screen_view` events across ten sessions, and four
 * sessions had none — a fixed 1-in-4 rate was discarding three quarters of the
 * only event that shows what a user did. These tests hold the replacement:
 * keep everything while the app is quiet, sample only when it is genuinely
 * busy.
 */
class SamplingPolicyTest {

    @Test
    fun `a quiet app keeps every event`() {
        // Kavach's real rate is about six events per session — far below the
        // threshold, so nothing should ever be dropped here.
        listOf(0.0, 1.0, 6.0, 30.0, 119.0, 120.0).forEach { rate ->
            (0L..7L).forEach { tick ->
                assertTrue(
                    "dropped an event at $rate/min with tick $tick",
                    SamplingPolicy.keep(ratePerMinute = rate, sampleTick = tick),
                )
            }
        }
    }

    @Test
    fun `a chatty app samples roughly one in four`() {
        val kept = (0L until 100L).count {
            SamplingPolicy.keep(ratePerMinute = 1_000.0, sampleTick = it)
        }
        assertEquals(25, kept)
    }

    @Test
    fun `a chatty app still keeps something`() {
        val kept = (0L until 100L).count {
            SamplingPolicy.keep(ratePerMinute = 5_000.0, sampleTick = it)
        }
        assertTrue("sampling discarded everything", kept > 0)
    }

    @Test
    fun `the threshold is exclusive, so the boundary keeps`() {
        assertTrue(SamplingPolicy.keep(SamplingPolicy.CHATTY_EVENTS_PER_MINUTE, 1L))
        assertFalse(SamplingPolicy.keep(SamplingPolicy.CHATTY_EVENTS_PER_MINUTE + 1, 1L))
    }

    @Test
    fun `the rate is derived from the window correctly`() {
        // The window is one minute, so the count is the rate.
        assertEquals(0.0, SamplingPolicy.ratePerMinute(0), 0.001)
        assertEquals(60.0, SamplingPolicy.ratePerMinute(60), 0.001)
        assertEquals(600.0, SamplingPolicy.ratePerMinute(600), 0.001)
    }

    @Test
    fun `the sample rate is the documented one`() {
        assertEquals(4L, SamplingPolicy.INFO_SAMPLE_RATE)
    }
}
