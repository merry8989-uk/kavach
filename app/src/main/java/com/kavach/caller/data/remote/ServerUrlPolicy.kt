package com.kavach.caller.data.remote

/**
 * Whether a server URL is safe to send a token to.
 *
 * Plain HTTP puts the bearer token on the wire in clear, so `http://` is allowed
 * only for a loopback or private address — where the traffic never leaves the
 * network the phone is already on. Anything public needs `https://`.
 *
 * Pure, so the rule can be tested rather than assumed. This is the kind of check
 * that is easy to write, easy to get subtly wrong (`10.0.0.1` is private;
 * `100.0.0.1` and `10.evil.com` are not), and invisible when it fails.
 */
object ServerUrlPolicy {

    sealed interface Verdict {
        data object Ok : Verdict

        /** The URL is unusable, with the reason a user can act on. */
        data class Rejected(val reason: String) : Verdict
    }

    fun check(rawUrl: String): Verdict {
        val url = rawUrl.trim()
        if (url.isBlank()) return Verdict.Rejected("Enter a server URL first.")

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Verdict.Rejected("The URL must start with http:// or https://")
        }

        if (url.startsWith("https://")) return Verdict.Ok

        val host = hostOf(url)
            ?: return Verdict.Rejected("That does not look like a URL. Try https://host:port")

        return if (isPrivateOrLoopback(host)) {
            Verdict.Ok
        } else {
            Verdict.Rejected(
                "Use https:// for a public server. Plain http:// would send your " +
                    "token in clear — it is allowed only for a local address.",
            )
        }
    }

    /** The bare host, without scheme, port or path. Null when there is not one. */
    fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Loopback or RFC 1918 private, decided on the *parsed host* — never on a
     * string prefix.
     *
     * `10.evil.com` starts with `10.` and is not private. Parsing first is what
     * makes that distinction, and it is why this is a function rather than a
     * `startsWith` chain inline.
     */
    fun isPrivateOrLoopback(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "[::1]") return true

        val parts = host.split(".")
        if (parts.size != 4) return false

        // Every octet must be a real number, or this is a hostname that merely
        // looks like an address.
        val octets = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }

        return when {
            octets[0] == 127 -> true                       // loopback
            octets[0] == 10 -> true                        // 10.0.0.0/8
            octets[0] == 192 && octets[1] == 168 -> true   // 192.168.0.0/16
            octets[0] == 172 && octets[1] in 16..31 -> true // 172.16.0.0/12
            else -> false
        }
    }
}
