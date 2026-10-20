package com.kavach.caller

import com.kavach.caller.util.PhoneUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilsTest {

    @Test
    fun toE164_addsIndianCountryCode() {
        assertEquals("+919876543210", PhoneUtils.toE164("9876543210"))
    }

    @Test
    fun toE164_stripsLeadingZero() {
        assertEquals("+919876543210", PhoneUtils.toE164("09876543210"))
    }

    @Test
    fun toE164_keepsExistingPlusPrefix() {
        assertEquals("+14155552671", PhoneUtils.toE164("+14155552671"))
    }

    @Test
    fun subscriberKey_returnsLastTenDigits() {
        assertEquals("9876543210", PhoneUtils.subscriberKey("+91 98765 43210"))
    }

    @Test
    fun t9_mapsNameToKeypadDigits() {
        assertEquals("2648", PhoneUtils.t9("Amit"))
    }

    @Test
    fun isPrivate_treatsBlankAndNullAsPrivate() {
        assertTrue(PhoneUtils.isPrivate(""))
        assertTrue(PhoneUtils.isPrivate(null))
        assertFalse(PhoneUtils.isPrivate("9876543210"))
    }

    @Test
    fun pretty_formatsIndianNumbers() {
        assertEquals("+91 98765 43210", PhoneUtils.pretty("9876543210"))
    }
}
