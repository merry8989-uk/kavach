package com.kavach.caller

import com.kavach.caller.data.update.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerPatchVersion() = assertTrue(UpdateChecker.isNewer("0.3.1", "0.3.0"))

    @Test
    fun newerMinorVersion() = assertTrue(UpdateChecker.isNewer("0.4.0", "0.3.9"))

    @Test
    fun newerMajorVersion() = assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))

    @Test
    fun sameVersionIsNotNewer() = assertFalse(UpdateChecker.isNewer("0.3.0", "0.3.0"))

    @Test
    fun olderVersionIsNotNewer() = assertFalse(UpdateChecker.isNewer("0.2.9", "0.3.0"))

    @Test
    fun toleratesShorterVersionStrings() = assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))

    @Test
    fun blankRemoteIsNotNewer() = assertFalse(UpdateChecker.isNewer("", "0.3.0"))
}
