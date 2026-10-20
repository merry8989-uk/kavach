package com.kavach.caller.util

import android.content.Context
import android.telephony.PhoneNumberUtils

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

    fun appContext(context: Context): Context = context.applicationContext
}
