package com.kavach.caller.sms

/**
 * Looks at the links in a message and says what is wrong with them.
 *
 * This is the highest-value part of SMS protection in India: the phishing that
 * actually reaches people is a shortened link, or a domain that imitates a bank
 * just enough to be read once and tapped.
 *
 * Everything here is a **reason**, never a verdict on its own. The classifier
 * weighs the reasons; the UI shows them. Nothing is auto-blocked from a link
 * alone, because a false positive on a real bank message is worse than a missed
 * one the user can still see.
 *
 * No network, no blocklist download. The checks are structural, which means they
 * work offline and on a message the moment it arrives.
 */
object LinkInspector {

    /** Hosts that hide where a link actually goes. */
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "rb.gy", "cutt.ly", "is.gd", "ow.ly",
        "tiny.cc", "rebrand.ly", "bit.do", "mcaf.ee", "su.pr", "adf.ly", "shorte.st",
        "bl.ink", "shorturl.at", "clck.ru", "v.gd", "qr.ae", "x.co", "lnkd.in", "t.ly",
        "short.gy", "s.id", "urlz.fr", "u.to", "chilp.it", "shrtco.de", "1pt.co",
    )

    /**
     * TLDs that are free or near-free to register, and therefore heavily used
     * for throwaway phishing domains.
     */
    private val RISKY_TLDS = setOf(
        "tk", "ml", "ga", "cf", "gq", "top", "xyz", "work", "click", "link",
        "zip", "mov", "country", "kim", "loan", "review", "stream", "download",
        "racing", "win", "bid", "party", "date", "faith", "science", "cricket",
    )

    /** Brand names that are commonly imitated. Never used to clear a link. */
    private val IMPERSONATED_BRANDS = mapOf(
        "hdfc" to "hdfcbank.com",
        "icici" to "icicibank.com",
        "sbi" to "onlinesbi.sbi",
        "axis" to "axisbank.com",
        "kotak" to "kotak.com",
        "paytm" to "paytm.com",
        "phonepe" to "phonepe.com",
        "amazon" to "amazon.in",
        "flipkart" to "flipkart.com",
        "netflix" to "netflix.com",
        "irctc" to "irctc.co.in",
        "uidai" to "uidai.gov.in",
        "aadhaar" to "uidai.gov.in",
        "incometax" to "incometax.gov.in",
        "epfo" to "epfindia.gov.in",
        "jio" to "jio.com",
        "airtel" to "airtel.in",
    )

    /** Words that make a plain-HTTP page a problem rather than just old. */
    private val CREDENTIAL_WORDS = listOf(
        "login", "signin", "sign-in", "verify", "verification", "kyc", "update",
        "secure", "account", "password", "otp", "wallet", "refund", "unblock",
        "reactivate", "confirm", "validate", "billing",
    )

    enum class Risk {
        SHORTENED,
        IP_ADDRESS,
        PUNYCODE,
        RISKY_TLD,
        LOOKALIKE_BRAND,
        INSECURE_LOGIN,
        MANY_SUBDOMAINS,
        EMBEDDED_CREDENTIALS,
        LONG_QUERY,
    }

    data class Finding(val url: String, val risks: Set<Risk>, val host: String) {
        /** Short, user-facing reasons. */
        val explanations: List<String>
            get() = risks.mapNotNull { risk ->
                when (risk) {
                    Risk.SHORTENED -> "the link is shortened, so its real destination is hidden"
                    Risk.IP_ADDRESS -> "the link points at a raw IP address, not a named site"
                    Risk.PUNYCODE -> "the link uses a look-alike alphabet that mimics real letters"
                    Risk.RISKY_TLD -> "the link uses a domain ending commonly used for throwaway scams"
                    Risk.LOOKALIKE_BRAND -> "the link imitates a well-known brand but is not its real domain"
                    Risk.INSECURE_LOGIN -> "the link asks you to sign in over an unencrypted connection"
                    Risk.MANY_SUBDOMAINS -> "the link buries its real domain under many subdomains"
                    Risk.EMBEDDED_CREDENTIALS -> "the link hides a sign-in before the address"
                    Risk.LONG_QUERY -> "the link carries an unusually long tracking query"
                }
            }
    }

    private val URL_REGEX = Regex(
        """(?i)\b(?:https?://|www\.)[^\s<>"']+|\b[a-z0-9][a-z0-9-]{1,61}(?:\.[a-z0-9-]{2,})+/\S*""",
    )

    /** Every link in [body], with whatever is suspicious about each. */
    fun inspect(body: String): List<Finding> {
        if (body.isBlank()) return emptyList()
        return extractUrls(body).map { url ->
            val host = hostOf(url)
            Finding(url = url, risks = risksOf(url, host), host = host)
        }
    }

    /** True when anything in the message looks like it is trying something. */
    fun hasRisk(body: String): Boolean = inspect(body).any { it.risks.isNotEmpty() }

    /** The single worst finding, for a one-line warning. */
    fun worst(body: String): Finding? =
        inspect(body).filter { it.risks.isNotEmpty() }.maxByOrNull { it.risks.size }

    fun extractUrls(body: String): List<String> {
        val found = URL_REGEX.findAll(body).map { it.value.trimEnd('.', ',', ')', ']', ';') }
        return found.filter { it.contains('.') }.map { if (it.startsWith("http")) it else "http://$it" }
            .distinct()
            .toList()
    }

    fun hostOf(url: String): String = runCatching {
        val withoutScheme = url.substringAfter("://", url)
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        authority.substringAfter('@').substringBefore(':').lowercase()
    }.getOrDefault("")

    private fun risksOf(url: String, host: String): Set<Risk> {
        val risks = mutableSetOf<Risk>()
        if (host.isBlank()) return risks

        val registrable = registrableDomain(host)

        if (SHORTENERS.any { registrable == it || host == it }) risks += Risk.SHORTENED

        // A bare IPv4 literal, or an IPv6 in brackets.
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.startsWith("[")) {
            risks += Risk.IP_ADDRESS
        }

        if (host.contains("xn--")) risks += Risk.PUNYCODE
        if (host.any { it.code > 127 }) risks += Risk.PUNYCODE

        val tld = host.substringAfterLast('.', "")
        if (tld in RISKY_TLDS) risks += Risk.RISKY_TLD

        // The brand appears in the host, but the host is not the brand's domain.
        IMPERSONATED_BRANDS.forEach { (brand, official) ->
            if (host.contains(brand) && !host.endsWith(official)) risks += Risk.LOOKALIKE_BRAND
        }

        if (url.startsWith("http://") && CREDENTIAL_WORDS.any { url.lowercase().contains(it) }) {
            risks += Risk.INSECURE_LOGIN
        }

        if (host.count { it == '.' } >= 4) risks += Risk.MANY_SUBDOMAINS

        val authority = url.substringAfter("://", url).substringBefore('/')
        if (authority.contains('@')) risks += Risk.EMBEDDED_CREDENTIALS

        val query = url.substringAfter('?', "")
        if (query.length > 120) risks += Risk.LONG_QUERY

        return risks
    }

    /** The last two labels, which is roughly what a person reads as the site. */
    fun registrableDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return host
        // Handle the common two-part public suffixes we actually see.
        val twoPartSuffixes = setOf("co.in", "net.in", "org.in", "gov.in", "ac.in", "co.uk", "com.au")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in twoPartSuffixes && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }
}
