package com.kavach.caller

import com.kavach.caller.ui.theme.MotionMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The motion setting exists to control real animation, so the two lists that
 * describe it — one in the data layer, one in the UI — must not drift apart.
 *
 * If they did, the picker would offer a value the repository silently rewrites to
 * "system", and the user would tap "Reduced motion" and get the opposite.
 */
class MotionModeTest {

    @Test
    fun `the data layer and the ui agree on the vocabulary`() {
        // Mirrors SettingsRepository's private MOTION_MODES.
        val dataLayer = setOf("system", "full", "reduced")
        assertEquals(dataLayer, MotionMode.VALUES)
    }

    @Test
    fun `every value parses`() {
        assertEquals(MotionMode.SYSTEM, MotionMode.from("system"))
        assertEquals(MotionMode.FULL, MotionMode.from("full"))
        assertEquals(MotionMode.REDUCED, MotionMode.from("reduced"))
    }

    @Test
    fun `an unknown value falls back to system rather than to a guess`() {
        assertEquals(MotionMode.SYSTEM, MotionMode.from("wobbly"))
        assertEquals(MotionMode.SYSTEM, MotionMode.from(null))
        assertEquals(MotionMode.SYSTEM, MotionMode.from(""))
    }

    @Test
    fun `the parse is case-insensitive`() {
        assertEquals(MotionMode.REDUCED, MotionMode.from("REDUCED"))
    }
}
