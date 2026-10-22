package com.kavach.caller.data.diagnostics

import java.security.MessageDigest
import java.util.Locale

/**
 * The privacy gate for logging (standard Part F).
 *
 * "Never log: email, phone, name, exact location, raw file paths, raw search
 * queries, auth tokens, or media file names."
 *
 * Two layers, because trusting call sites is how leaks happen:
 *
 *  1. **Key rules** — a property whose name is on the forbidden list is replaced
 *     with a marker, whatever the caller passed.
 *  2. **Value rules** — even under an innocent key, anything that *looks* like a
 *     phone number, an email address or an absolute path is scrubbed.
 *
 * Redaction lives in one class so the app and the web cannot drift apart.
 */
object Redaction {

    const val REDACTED = "[redacted]"

    /** Property names that must never carry a value. */
    private val FORBIDDEN_KEYS = setOf(
        "email", "e_mail", "phone", "phone_number", "number", "msisdn",
        "name", "display_name", "contact_name", "first_name", "last_name",
        "address", "location", "lat", "long", "latitude", "longitude",
        "path", "file", "file_path", "filename", "file_name", "media_name",
        "query", "search_query", "q",
        "token", "access_token", "refresh_token", "authorization", "password",
        "otp", "code", "secret", "api_key",
        "body", "message", "sms_body", "note", "notes", "comment",
    )

    /**
     * Keys where a leading slash is a route, not a filesystem path. Without
     * this, `api_request.endpoint` would be logged as "[path]" and the event
     * would be useless.
     */
    private val PATH_EXEMPT_KEYS = setOf(
        "endpoint", "route", "screen", "screen_name", "url_path", "previous_screen",
    )

    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val PHONE_LIKE = Regex("""(?:\+?\d[\s-]?){8,}""")
    private val ABSOLUTE_PATH = Regex("""(^|[\s"'])(/[^\s"']{3,}|[A-Za-z]:\\[^\s"']{3,})""")

    fun isForbiddenKey(key: String): Boolean =
        key.lowercase(Locale.US).replace('-', '_') in FORBIDDEN_KEYS

    /**
     * Scrub one property. Returns [REDACTED] for a forbidden key, otherwise the
     * value with anything PII-shaped masked out.
     */
    fun scrub(key: String, value: String): String {
        if (isForbiddenKey(key)) return REDACTED
        return scrubValue(value, allowSlashPaths = key.lowercase(Locale.US) in PATH_EXEMPT_KEYS)
    }

    fun scrubValue(value: String, allowSlashPaths: Boolean = false): String {
        if (value.isEmpty()) return value
        var out = value
        out = EMAIL.replace(out, "[email]")
        out = PHONE_LIKE.replace(out) { match ->
            // Only treat it as a number if it really has enough digits.
            val digits = match.value.count { it.isDigit() }
            if (digits >= 8) "[number]" else match.value
        }
        if (!allowSlashPaths) {
            out = ABSOLUTE_PATH.replace(out) { match ->
                val prefix = match.groupValues[1]
                "$prefix[path]"
            }
        }
        return out
    }

    /** Scrub a whole property map. */
    fun scrubAll(properties: Map<String, String>): Map<String, String> =
        properties.mapValues { (key, value) -> scrub(key, value) }

    /**
     * What we log for a phone number: the last four digits, and nothing else.
     * Kept for local display; the value never carries the full number.
     */
    fun maskNumber(raw: String?): String {
        if (raw.isNullOrBlank()) return "unknown"
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 4) "******" + digits.takeLast(4) else "******"
    }

    /**
     * A stable, non-reversible id for a user. Salted so the same number cannot
     * be re-derived by hashing a phone-number dictionary.
     */
    fun hashUser(raw: String?, salt: String): String? {
        if (raw.isNullOrBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray())
        digest.update(raw.toByteArray())
        return "u_" + digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** For crash reporting: a hash of the stack, never the stack itself. */
    fun hashStack(stack: String?): String {
        if (stack.isNullOrBlank()) return "none"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(stack.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** For a raw search query: its length, not its text. */
    fun queryLength(raw: String?): String = (raw?.length ?: 0).toString()
}
