package com.kavach.caller

import com.kavach.caller.recording.RecordingCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spec is explicit about recording:
 *
 *   "Do not claim two-way recording works unless it has been verified on the
 *    target device."
 *
 * These tests hold that line. The verdict is a pure companion function for
 * exactly this reason: the rule cannot be quietly broken by a device-specific
 * branch later without a test going red.
 */
class RecordingCapabilityTest {

    private fun verdict(hasMic: Boolean, hasDialer: Boolean) =
        RecordingCapability.verdict(hasMic = hasMic, hasDialerRole = hasDialer)

    @Test
    fun `without the microphone permission nothing can be recorded`() {
        val result = verdict(hasMic = false, hasDialer = true)
        assertEquals(RecordingCapability.Capture.NONE, result.capture)
        assertTrue(result.headline.contains("not available", ignoreCase = true))
    }

    @Test
    fun `two-way capture is never promised, even with the dialer role`() {
        // The dialer role does not prove the OEM allows uplink capture, so the
        // verdict must stay microphone-only in both cases.
        val withRole = verdict(hasMic = true, hasDialer = true)
        val withoutRole = verdict(hasMic = true, hasDialer = false)

        assertFalse(withRole.capture == RecordingCapability.Capture.TWO_WAY)
        assertFalse(withoutRole.capture == RecordingCapability.Capture.TWO_WAY)
        assertEquals(RecordingCapability.Capture.MICROPHONE, withRole.capture)
        assertEquals(RecordingCapability.Capture.MICROPHONE, withoutRole.capture)
    }

    @Test
    fun `the headline says only your side is captured`() {
        val result = verdict(hasMic = true, hasDialer = false)
        assertTrue(result.headline.contains("your side", ignoreCase = true))
        assertTrue(result.explanation.contains("does not let an ordinary app", ignoreCase = true))
    }

    @Test
    fun `holding the dialer role changes the wording but not the promise`() {
        val withRole = verdict(hasMic = true, hasDialer = true)
        assertTrue(withRole.explanation.contains("default-dialer role"))
        assertTrue(withRole.explanation.contains("actually worked"))
        assertEquals(RecordingCapability.Capture.MICROPHONE, withRole.capture)
    }

    @Test
    fun `without the role the explanation says so`() {
        assertTrue(verdict(hasMic = true, hasDialer = false).explanation.contains("does not have it"))
    }

    @Test
    fun `the verdict is stable for the same inputs`() {
        assertEquals(verdict(true, false), verdict(true, false))
    }
}
