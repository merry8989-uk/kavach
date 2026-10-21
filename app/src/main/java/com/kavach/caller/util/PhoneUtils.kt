package com.kavach.caller.util

import android.content.Context
import android.telephony.PhoneNumberUtils
import java.util.Locale

object PhoneUtils {

    /** Digits only, keeping a leading +. Used as the canonical key for lookups. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (plus) "+$digits" else digits
    }

    /**
     * Best-effort E.164 for Indian numbers (default country code 91).
     * The default region is configurable for other markets.
     */
    fun toE164(raw: String, defaultCountryCode: String = "91"): String {
        val n = normalize(raw)
        return when {
            n.startsWith("+") -> n
            n.length == 10 -> "+$defaultCountryCode$n"
            n.length == 11 && n.startsWith("0") -> "+$defaultCountryCode${n.drop(1)}"
            n.startsWith(defaultCountryCode) && n.length == 12 -> "+$n"
            else -> n
        }
    }

    /** Last 10 digits — the part that actually identifies a subscriber in India. */
    fun subscriberKey(raw: String?): String {
        val digits = normalize(raw).trimStart('+')
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    fun isPrivate(raw: String?): Boolean =
        raw.isNullOrBlank() || normalize(raw).isEmpty()

    /** Simple formatting for display: +91 98765 43210. */
    fun pretty(raw: String?): String {
        val e164 = toE164(raw ?: return "")
        val national = e164.removePrefix("+91")
        return if (e164.startsWith("+91") && national.length == 10) {
            "+91 ${national.substring(0, 5)} ${national.substring(5)}"
        } else e164
    }

    fun sameNumber(a: String?, b: String?): Boolean =
        PhoneNumberUtils.compare(a ?: "", b ?: "") || subscriberKey(a) == subscriberKey(b)

    /** T9 lookup key: 2=ABC ... 9=WXYZ. Lets "ama" find "Amit". */
    fun t9(text: String): String = buildString {
        for (c in text.uppercase()) {
            when (c) {
                in "ABC" -> append('2'); in "DEF" -> append('3'); in "GHI" -> append('4')
                in "JKL" -> append('5'); in "MNO" -> append('6'); in "PQRS" -> append('7')
                in "TUV" -> append('8'); in "WXYZ" -> append('9')
                in '0'..'9' -> append(c)
            }
        }
    }

    /**
     * Country/region name for a number's calling code, for the in-call screen.
     * Offline lookup against a small table — never a claim about where the
     * caller physically is.
     */
    private val regionNames = mapOf(
        "1" to "USA / Canada", "7" to "Russia", "20" to "Egypt", "27" to "South Africa",
        "33" to "France", "34" to "Spain", "39" to "Italy", "44" to "United Kingdom",
        "49" to "Germany", "60" to "Malaysia", "61" to "Australia", "62" to "Indonesia",
        "63" to "Philippines", "65" to "Singapore", "66" to "Thailand", "81" to "Japan",
        "82" to "South Korea", "84" to "Vietnam", "86" to "China", "91" to "India",
        "92" to "Pakistan", "94" to "Sri Lanka", "95" to "Myanmar", "977" to "Nepal",
        "880" to "Bangladesh", "971" to "UAE", "966" to "Saudi Arabia", "974" to "Qatar",
        "965" to "Kuwait", "968" to "Oman", "973" to "Bahrain", "234" to "Nigeria",
        "254" to "Kenya", "255" to "Tanzania", "256" to "Uganda", "251" to "Ethiopia",
    )

    fun regionFor(raw: String?): String? {
        val digits = normalize(raw).trimStart('+')
        if (digits.isEmpty()) return null
        // Longest calling code wins.
        for (length in intArrayOf(3, 2, 1)) {
            if (digits.length > length) {
                regionNames[digits.substring(0, length)]?.let { return it }
            }
        }
        return null
    }

    /** mm:ss for call durations. */
    fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    fun appContext(context: Context): Context = context.applicationContext
}
