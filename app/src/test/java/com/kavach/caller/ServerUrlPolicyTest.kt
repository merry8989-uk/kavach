package com.kavach.caller

import com.kavach.caller.data.remote.ServerUrlPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plain-http URL to a public host would put the bearer token on the wire in
 * clear. This is the check that stops it, and it is the kind of check that fails
 * invisibly — so it is tested rather than assumed.
 */
class ServerUrlPolicyTest {

    private fun ok(url: String) = ServerUrlPolicy.check(url) is ServerUrlPolicy.Verdict.Ok

    @Test
    fun `https to anything is allowed`() {
        assertTrue(ok("https://kavach.example.com"))
        assertTrue(ok("https://192.168.1.5:8000"))
        assertTrue(ok("https://example.com/api"))
    }

    @Test
    fun `http to loopback is allowed`() {
        assertTrue(ok("http://localhost:8000"))
        assertTrue(ok("http://127.0.0.1:8000"))
        assertTrue(ok("http://127.1.2.3:8000"))
    }

    @Test
    fun `http to a private address is allowed`() {
        assertTrue(ok("http://192.168.1.5:8000"))
        assertTrue(ok("http://10.0.0.5:8000"))
        assertTrue(ok("http://172.16.4.4:8000"))
        assertTrue(ok("http://172.31.255.254:8000"))
    }

    @Test
    fun `http to a public address is refused`() {
        assertFalse(ok("http://example.com"))
        assertFalse(ok("http://8.8.8.8"))
        assertFalse(ok("http://kavach.example.com:8000"))
    }

    @Test
    fun `a hostname that merely starts with a private prefix is refused`() {
        // This is the bug the parse-first approach exists to prevent.
        assertFalse(ok("http://10.evil.com"))
        assertFalse(ok("http://192.168.1.5.evil.com"))
        assertFalse(ok("http://127.0.0.1.evil.com"))
    }

    @Test
    fun `172 outside the private range is refused`() {
        assertFalse(ok("http://172.15.0.1"))   // just below
        assertFalse(ok("http://172.32.0.1"))   // just above
    }

    @Test
    fun `a missing scheme is refused with a reason`() {
        val verdict = ServerUrlPolicy.check("kavach.example.com")
        assertTrue(verdict is ServerUrlPolicy.Verdict.Rejected)
    }

    @Test
    fun `a blank url is refused`() {
        assertTrue(ServerUrlPolicy.check("") is ServerUrlPolicy.Verdict.Rejected)
        assertTrue(ServerUrlPolicy.check("   ") is ServerUrlPolicy.Verdict.Rejected)
    }

    @Test
    fun `an octet out of range is not treated as an address`() {
        assertFalse(ServerUrlPolicy.isPrivateOrLoopback("192.168.1.300"))
        assertFalse(ServerUrlPolicy.isPrivateOrLoopback("10.0.0.256"))
    }

    @Test
    fun `a hostname is not treated as an address`() {
        assertFalse(ServerUrlPolicy.isPrivateOrLoopback("example.com"))
        assertFalse(ServerUrlPolicy.isPrivateOrLoopback("kavach"))
    }

    @Test
    fun `the host is parsed, not sliced out of the string`() {
        assertTrue(ServerUrlPolicy.hostOf("http://192.168.1.5:8000/path") == "192.168.1.5")
        assertTrue(ServerUrlPolicy.hostOf("https://Example.COM/x") == "example.com")
    }
}
