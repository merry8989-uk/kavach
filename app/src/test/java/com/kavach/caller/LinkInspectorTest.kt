package com.kavach.caller

import com.kavach.caller.sms.LinkInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link checks are structural, so they work offline and on the message the
 * moment it lands. The risk here is a false positive on a real message, so the
 * safe cases are tested as carefully as the dangerous ones.
 */
class LinkInspectorTest {

    @Test
    fun `a shortened link is flagged because its destination is hidden`() {
        val finding = LinkInspector.inspect("Claim now http://bit.ly/xyz123").single()
        assertTrue(finding.risks.contains(LinkInspector.Risk.SHORTENED))
    }

    @Test
    fun `a raw ip address is flagged`() {
        val finding = LinkInspector.inspect("Login at http://192.168.4.7/verify").single()
        assertTrue(finding.risks.contains(LinkInspector.Risk.IP_ADDRESS))
    }

    @Test
    fun `a throwaway tld is flagged`() {
        val finding = LinkInspector.inspect("http://secure-hdfc.tk/login").single()
        assertTrue(finding.risks.contains(LinkInspector.Risk.RISKY_TLD))
    }

    @Test
    fun `a bank look-alike is flagged`() {
        val finding = LinkInspector.inspect("http://hdfcbank-kyc-update.com/verify").single()
        assertTrue(finding.risks.contains(LinkInspector.Risk.LOOKALIKE_BRAND))
    }

    @Test
    fun `the real bank domain is not flagged as a look-alike`() {
        val finding = LinkInspector.inspect("https://www.hdfcbank.com/personal").single()
        assertFalse(
            "the genuine domain was flagged: ${finding.risks}",
            finding.risks.contains(LinkInspector.Risk.LOOKALIKE_BRAND),
        )
    }

    @Test
    fun `a plain http login page is flagged`() {
        val finding = LinkInspector.inspect("http://example.com/login").single()
        assertTrue(finding.risks.contains(LinkInspector.Risk.INSECURE_LOGIN))
    }

    @Test
    fun `https is not flagged for being insecure`() {
        val finding = LinkInspector.inspect("https://example.com/login").single()
        assertFalse(finding.risks.contains(LinkInspector.Risk.INSECURE_LOGIN))
    }

    @Test
    fun `punycode is flagged`() {
        val finding = LinkInspector.inspect("http://xn--hdfc-9na.com/").single()
        assertTrue(finding.risks.contains(LinkInspector.Risk.PUNYCODE))
    }

    @Test
    fun `credentials embedded in the address are flagged`() {
        val finding = LinkInspector.inspect("http://hdfcbank.com@evil.example/").single()
        assertTrue(finding.risks.contains(LinkInspector.Risk.EMBEDDED_CREDENTIALS))
    }

    @Test
    fun `a deeply nested host is flagged`() {
        val finding = LinkInspector.inspect("http://a.b.c.d.example.com/").single()
        assertTrue(finding.risks.contains(LinkInspector.Risk.MANY_SUBDOMAINS))
    }

    @Test
    fun `an ordinary https link raises nothing`() {
        val findings = LinkInspector.inspect("Your order shipped: https://www.flipkart.com/orders")
        assertTrue(findings.all { it.risks.isEmpty() })
        assertFalse(LinkInspector.hasRisk("Your order shipped: https://www.flipkart.com/orders"))
    }

    @Test
    fun `a message with no link raises nothing`() {
        assertTrue(LinkInspector.inspect("See you at 7").isEmpty())
        assertFalse(LinkInspector.hasRisk("See you at 7"))
    }

    @Test
    fun `the registrable domain handles two-part indian suffixes`() {
        assertEquals("hdfcbank.com", LinkInspector.registrableDomain("www.hdfcbank.com"))
        assertEquals("irctc.co.in", LinkInspector.registrableDomain("www.irctc.co.in"))
        assertEquals("example.com", LinkInspector.registrableDomain("a.b.example.com"))
    }

    @Test
    fun `the worst finding is the one with the most reasons`() {
        val worst = LinkInspector.worst("http://bit.ly/x and http://192.168.1.1/login/verify")
        assertTrue(worst != null)
        assertTrue(worst!!.risks.size >= 2)
    }

    @Test
    fun `trailing punctuation is not swallowed into the link`() {
        val urls = LinkInspector.extractUrls("Go to https://example.com/page.")
        assertEquals(listOf("https://example.com/page"), urls)
    }
}
