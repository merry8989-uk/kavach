package com.kavach.caller

import com.kavach.caller.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defaults are the feature here, so they are pinned rather than assumed.
 *
 * "Collect every log, with no limit" is not a setting a user has to find — it is
 * what the app does when they change nothing. These tests exist so that stays
 * true: a future edit that turns sampling back on, or gives the log an expiry
 * nobody asked for, fails here rather than silently in someone's pocket.
 */
class DiagnosticsDefaultsTest {

    private val defaults = AppSettings()

    @Test
    fun `sampling is off, so every eligible event is kept`() {
        // The specification asks for "100% of eligible events". Off is that.
        assertFalse(
            "sampling must ship off — turning it on is the user's call",
            defaults.diagnosticsSamplingEnabled,
        )
    }

    @Test
    fun `retention is zero, which means nothing is ever deleted`() {
        assertEquals(
            "the log must not expire on its own",
            0,
            defaults.diagnosticsRetentionDays,
        )
    }

    @Test
    fun `diagnostics still ships off until the user opts in`() {
        // Separate from the caps: collection itself is opt-in, and stays so.
        assertFalse(defaults.diagnosticsEnabled)
    }

    @Test
    fun `nothing that limits collection is on by default`() {
        // One assertion over the whole set, so a new limit added later has to be
        // considered here rather than slipping in.
        val limitingDefaults = buildMap {
            put("sampling", defaults.diagnosticsSamplingEnabled)
            put("retention", defaults.diagnosticsRetentionDays > 0)
        }
        val on = limitingDefaults.filterValues { it }.keys
        assertTrue("these should all be off by default: $on", on.isEmpty())
    }
}
