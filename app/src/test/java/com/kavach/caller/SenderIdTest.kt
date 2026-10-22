package com.kavach.caller

import com.kavach.caller.sms.SenderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sender IDs are the strongest signal in an Indian inbox, and the easiest thing
 * to get confidently wrong. A wrong brand label would tell someone a scam is
 * their bank.
 */
class SenderIdTest {

    @Test
    fun `a prefixed commercial header is parsed`() {
        val parsed = SenderId.parse("VM-HDFCBK")
        assertEquals(SenderId.Kind.COMMERCIAL, parsed.kind)
        assertEquals("VM", parsed.operatorPrefix)
        assertEquals("HDFCBK", parsed.header)
        assertEquals("HDFC Bank", parsed.knownBrand)
    }

    @Test
    fun `a bare header is parsed without a prefix`() {
        val parsed = SenderId.parse("AMZNIN")
        assertEquals(SenderId.Kind.COMMERCIAL, parsed.kind)
        assertNull(parsed.operatorPrefix)
        assertEquals("Amazon", parsed.knownBrand)
    }

    @Test
    fun `an unknown header is reported without inventing a brand`() {
        val parsed = SenderId.parse("AD-ZZZZZZ")
        assertEquals(SenderId.Kind.COMMERCIAL, parsed.kind)
        assertNull("a brand was invented for an unknown header", parsed.knownBrand)
    }

    @Test
    fun `indian mobile numbers are phone numbers, not headers`() {
        listOf("+919876543210", "9876543210", "919876543210").forEach { number ->
            assertEquals("$number", SenderId.Kind.PHONE, SenderId.parse(number).kind)
        }
    }

    @Test
    fun `short codes are recognised`() {
        listOf("56161", "56767", "1900").forEach { code ->
            assertEquals("$code", SenderId.Kind.SHORTCODE, SenderId.parse(code).kind)
        }
    }

    @Test
    fun `an email gateway is recognised`() {
        assertEquals(SenderId.Kind.EMAIL, SenderId.parse("alerts@bank.example").kind)
    }

    @Test
    fun `display name prefers the contact name`() {
        assertEquals("Amma", SenderId.displayName("+919876543210", "Amma"))
    }

    @Test
    fun `display name shows a known brand and its header`() {
        assertEquals("HDFC Bank (HDFCBK)", SenderId.displayName("VM-HDFCBK"))
    }

    @Test
    fun `display name never invents a brand for an unknown header`() {
        assertEquals("ZZZZZZ", SenderId.displayName("AD-ZZZZZZ"))
    }

    @Test
    fun `a bank message is not classified as coming from a person`() {
        assertTrue(SenderId.looksCommercial("VM-HDFCBK"))
        assertTrue(SenderId.looksCommercial("56161"))
        assertEquals(false, SenderId.looksCommercial("+919876543210"))
    }

    @Test
    fun `garbage does not crash or pretend to be a header`() {
        listOf("", "   ", "?", "----").forEach { input ->
            assertEquals("'$input'", SenderId.Kind.UNKNOWN, SenderId.parse(input).kind)
        }
    }
}
