package com.kavach.caller.domain

/**
 * Which missed calls are worth returning.
 *
 * A missed-call list sorted by time is not useful. Someone who called three times
 * this morning from a saved number is a different situation from a single missed
 * call from a number nobody has ever seen, and a list that shows them in the same
 * order makes the user do the ranking themselves every time.
 *
 * Pure, and deliberately so: no Android, no clock, no IO. Every signal below is
 * something a person would actually use, and each is testable in isolation. The
 * alternative — a "smart" ranking nobody can explain — is worse than sorting by
 * time, because a wrong priority is indistinguishable from a right one.
 *
 * **What this is not:** a claim about who is important. It ranks calls, not
 * people, and it says why in [Reason] so the user can disagree with it.
 */
object MissedCallPriority {

    /** Why a call was ranked where it was. Shown to the user, not hidden. */
    enum class Reason {
        /** A saved contact, and they called more than once. */
        CONTACT_REPEATED,

        /** A saved contact. */
        CONTACT,

        /** Not saved, but called more than once. */
        REPEATED,

        /** Called once, unknown, and not flagged. */
        UNKNOWN,
    }

    data class Ranked(
        val number: String,
        val name: String?,
        val missedCount: Int,
        val lastMissedAt: Long,
        val spamScore: Int,
        val reason: Reason,
        val score: Int,
    )

    /**
     * Calls from a number scored at or above this are **excluded**, not ranked
     * low. A number that has been reported by many people is not an opportunity
     * to call back, and putting it at the bottom of a list invites exactly that.
     */
    const val EXCLUDE_AT_OR_ABOVE = 70

    /** More than this many calls in the window is a pattern worth surfacing. */
    private const val REPEATED_THRESHOLD = 2

    /**
     * Rank the missed calls in [missed].
     *
     * @param missed only the missed entries; the caller filters, so this stays
     *   free of any assumption about the call-log's `type` encoding.
     * @param savedNumbers normalised numbers that are in the user's contacts.
     */
    fun rank(
        missed: List<MissedCall>,
        savedNumbers: Set<String>,
    ): List<Ranked> {
        val grouped = missed.groupBy { it.number }

        return grouped.mapNotNull { (number, calls) ->
            val latest = calls.maxOf { it.timestamp }
            val spam = calls.maxOf { it.spamScore }
            if (spam >= EXCLUDE_AT_OR_ABOVE) return@mapNotNull null

            val count = calls.size
            val isSaved = number in savedNumbers
            val repeated = count >= REPEATED_THRESHOLD

            val reason = when {
                isSaved && repeated -> Reason.CONTACT_REPEATED
                isSaved -> Reason.CONTACT
                repeated -> Reason.REPEATED
                else -> Reason.UNKNOWN
            }

            Ranked(
                number = number,
                name = calls.firstNotNullOfOrNull { it.name },
                missedCount = count,
                lastMissedAt = latest,
                spamScore = spam,
                reason = reason,
                score = scoreOf(reason, count, spam),
            )
        }.sortedWith(
            // Highest score first; ties broken by the most recent call, because
            // between two equally important calls the newer one is the one still
            // worth acting on.
            compareByDescending<Ranked> { it.score }.thenByDescending { it.lastMissedAt },
        )
    }

    private fun scoreOf(reason: Reason, count: Int, spamScore: Int): Int {
        val base = when (reason) {
            Reason.CONTACT_REPEATED -> 80
            Reason.CONTACT -> 55
            Reason.REPEATED -> 40
            Reason.UNKNOWN -> 15
        }
        // Each extra call adds a little, capped, so a nuisance caller cannot
        // outrank a saved contact simply by calling more often.
        val volume = minOf(count - 1, 4) * 5
        // A non-zero score is a caution, never a boost.
        val caution = -spamScore / 4
        return (base + volume + caution).coerceIn(0, 100)
    }

    /** The minimum a ranking needs. Kept separate so this stays testable. */
    data class MissedCall(
        val number: String,
        val name: String? = null,
        val timestamp: Long,
        val spamScore: Int = 0,
    )
}
