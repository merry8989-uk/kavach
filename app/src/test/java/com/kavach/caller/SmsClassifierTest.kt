package com.kavach.caller

import com.kavach.caller.sms.SmsClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real Indian message shapes, real failures.
 *
 * The two that matter most:
 *  - a genuine bank message must never be called spam;
 *  - an OTP body must never be exposed in a preview or a log.
 */
class SmsClassifierTest {

    private fun classify(body: String, address: String? = null, isContact: Boolean = false) =
        SmsClassifier.classify(body = body, address = address, isContact = isContact)

    // ---------------- OTP ----------------

    @Test
    fun `a real bank OTP is detected`() {
        val result = classify(
            "482913 is your OTP for HDFC Bank NetBanking. Do not share it with anyone. " +
                "Valid for 10 minutes.",
            address = "VM-HDFCBK",
        )
        assertEquals(SmsClassifier.Category.OTP, result.category)
        assertEquals("482913", result.otpCode)
        assertEquals(10, result.otpValidMinutes)
    }

    @Test
    fun `an OTP without a code is not treated as one`() {
        // A scam that merely says "OTP" must not hide behind the OTP label.
        val result = classify(
            "Your OTP has expired. Click http://bit.ly/x to request a new one now.",
            address = "AD-ZZZZZZ",
        )
        assertFalse(result.category == SmsClassifier.Category.OTP)
    }

    @Test
    fun `a bare number with no OTP wording is not an OTP`() {
        val result = classify("Meeting moved to 4829 today", address = "+919876543210")
        assertFalse(result.category == SmsClassifier.Category.OTP)
    }

    @Test
    fun `the OTP preview never contains the code`() {
        val result = classify(
            "482913 is your OTP. Do not share it with anyone. Valid for 10 minutes.",
            address = "VM-HDFCBK",
        )
        val preview = SmsClassifier.safePreview("482913 is your OTP", result.category)
        assertFalse("the code leaked into the preview", preview.contains("482913"))
        assertTrue(preview.contains("One-time password"))
    }

    @Test
    fun `an OTP is never given a spam score`() {
        val result = classify(
            "123456 is your OTP for login. Do not share. Valid for 5 minutes.",
            address = "VM-HDFCBK",
        )
        assertEquals(0, result.spamScore)
    }

    // ---------------- transactional ----------------

    @Test
    fun `a genuine debit alert is transactional, not spam`() {
        val result = classify(
            "Rs.2,500.00 debited from A/c XX1234 on 21-10-26 to VPA amazon@okaxis. " +
                "Avl Bal Rs.12,340.50.",
            address = "VM-HDFCBK",
        )
        assertEquals(SmsClassifier.Category.TRANSACTIONAL, result.category)
        assertFalse(result.isSpam)
    }

    @Test
    fun `a delivery update is transactional`() {
        val result = classify(
            "Your order has been dispatched. Out for delivery today. Track at " +
                "https://www.flipkart.com/orders",
            address = "AD-FLIPKRT",
        )
        assertEquals(SmsClassifier.Category.TRANSACTIONAL, result.category)
    }

    @Test
    fun `a real bank message with a threat word is not called spam`() {
        // "KYC" and "blocked" appear in genuine bank messages constantly. Without
        // a link, this must not reach the spam threshold.
        val result = classify(
            "Dear Customer, your KYC is due for update. Visit your branch to avoid " +
                "your account being blocked.",
            address = "VM-HDFCBK",
        )
        assertFalse("a real bank message was called spam: ${result.reasons}", result.isSpam)
    }

    // ---------------- promotional ----------------

    @Test
    fun `a promotional message is promotional, not spam`() {
        val result = classify(
            "MEGA SALE! Flat 60% off on all items. Shop now at https://www.myntra.com/sale. " +
                "T&C apply. Unsubscribe to opt out.",
            address = "AD-MYNTRA",
        )
        assertEquals(SmsClassifier.Category.PROMOTIONAL, result.category)
    }

    // ---------------- spam ----------------

    @Test
    fun `the KYC phishing message is caught`() {
        val result = classify(
            "URGENT: Your account will be blocked today. Complete KYC immediately at " +
                "http://hdfcbank-kyc-update.tk/verify",
            address = "AD-ZZZZZZ",
        )
        assertTrue("missed: ${result.reasons}", result.isSpam)
        assertTrue(result.reasons.any { it.contains("blocked") })
    }

    @Test
    fun `the lottery message is caught`() {
        val result = classify(
            "Congratulations! You have won Rs 25 lakh in the KBC lucky draw. " +
                "Claim your prize now: http://bit.ly/claim",
            address = "AD-ZZZZZZ",
        )
        assertTrue("missed: ${result.reasons}", result.isSpam)
    }

    @Test
    fun `the work-from-home scam is caught`() {
        val result = classify(
            "Earn from home! Part time job, daily income Rs 2000. No documents needed. " +
                "WhatsApp now.",
            address = "AD-ZZZZZZ",
        )
        assertTrue("missed: ${result.reasons}", result.isSpam)
    }

    @Test
    fun `an ordinary message from a saved contact is personal`() {
        val result = classify("See you at 7", address = "+919876543210", isContact = true)
        assertEquals(SmsClassifier.Category.PERSONAL, result.category)
        assertFalse(result.isSpam)
    }

    @Test
    fun `a saved contact is never spam on text alone`() {
        val result = classify(
            "Congratulations! You won the office lottery, come collect your prize",
            address = "+919876543210",
            isContact = true,
        )
        assertFalse("a contact was called spam: ${result.reasons}", result.isSpam)
    }

    @Test
    fun `an unknown number with a risky link scores but is not automatically spam`() {
        val result = classify("Check this out http://bit.ly/x", address = "+919876543210")
        assertTrue(result.hasLinkRisk)
        // One shortened link alone should not cross the line.
        assertFalse("a single shortened link was enough to call it spam", result.isSpam)
    }

    // ---------------- safety ----------------

    @Test
    fun `an empty body does not crash`() {
        val result = classify("")
        assertEquals(SmsClassifier.Category.UNKNOWN, result.category)
        assertNull(result.otpCode)
    }

    @Test
    fun `every result carries its reasons so a verdict can be explained`() {
        val result = classify(
            "You have won a prize! Claim at http://192.168.1.1/claim",
            address = "AD-ZZZZZZ",
        )
        assertTrue(result.reasons.isNotEmpty())
        assertNotNull(result.links)
    }

    @Test
    fun `the safe preview leaves an ordinary message alone`() {
        val body = "Your order is arriving today"
        assertEquals(body, SmsClassifier.safePreview(body, SmsClassifier.Category.TRANSACTIONAL))
    }
}
