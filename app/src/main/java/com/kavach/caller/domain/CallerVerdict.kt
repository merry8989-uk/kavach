package com.kavach.caller.domain

import com.kavach.caller.domain.model.CallerIdentity

/**
 * What to tell the user about a caller, and why.
 *
 * The review put this first, and was specific about the second half:
 *
 * > *"Spam warning ke saath reason bhi ho, jaise 'Multiple users reported this
 * > number.'"*
 * > *"Naam verify na ho to clearly 'Unknown caller' likhe — naam invent na kare."*
 *
 * A bare score is not a warning. "Risk 78" tells someone nothing they can act on
 * — it does not say who says so, how many of them, or what they claimed. A score
 * with no reason is worse than no score, because it looks like evidence.
 *
 * Pure and tested, because the wording is the feature. The rules are simple
 * enough to state in a sentence each, and every one of them is a claim the app is
 * making about a person's phone number.
 *
 * **What this never does:** invent a name, call something verified that is not,
 * or present an allegation as a finding. Community reports are attributed to the
 * community, in the same sentence.
 */
object CallerVerdict {

    enum class Level {
        /** In the user's own contacts. */
        CONTACT,

        /** A verified business listing. */
        BUSINESS,

        /** A name from a source we can point at, but not verified. */
        NAMED,

        /** Nothing is known. Stated plainly rather than guessed. */
        UNKNOWN,

        /** Reported by others, or a high score. Worth caution, not certainty. */
        SUSPECTED,

        /** Many independent reports. */
        HIGH_RISK,

        /** The network withheld the number. */
        RESTRICTED,
    }

    data class Verdict(
        val level: Level,
        /** One short line, e.g. "Suspected spam". */
        val headline: String,
        /**
         * The reason, in the user's terms. This is the part that makes the
         * headline checkable, so it is never empty.
         */
        val reason: String,
        /** True when the app should be visibly cautious rather than neutral. */
        val isWarning: Boolean,
        /** True when the identity rests on community reports rather than a record. */
        val isCommunityAllegation: Boolean,
    )

    /**
     * Above this, a number is treated as high risk rather than merely suspected.
     *
     * Named rather than inlined so the settings screen and the scorer can point
     * at the same number, and so the threshold is visible in one place.
     */
    const val HIGH_RISK_SCORE = 70

    /** Above this, a number is worth a caution. */
    const val SUSPECTED_SCORE = 40

    /** How many distinct reporters turn a caution into a warning on their own. */
    const val HIGH_RISK_REPORTS = 20

    fun of(identity: CallerIdentity?, numberIsBlank: Boolean): Verdict {
        if (numberIsBlank) {
            return Verdict(
                level = Level.RESTRICTED,
                headline = "Private number",
                reason = "The network did not pass on the number, so there is nothing to look up.",
                isWarning = false,
                isCommunityAllegation = false,
            )
        }

        if (identity == null) {
            // No lookup has run or it found nothing. Say that, rather than
            // letting an empty card imply the caller is fine.
            return Verdict(
                level = Level.UNKNOWN,
                headline = "Unknown caller",
                reason = "Not in your contacts, and no other record was found for this number.",
                isWarning = false,
                isCommunityAllegation = false,
            )
        }

        // A contact the user saved outranks every other signal. If someone is in
        // the address book, a community report about them is not a reason to warn
        // the user about their own contact.
        if (identity.source == CallerIdentity.Source.CONTACT) {
            return Verdict(
                level = Level.CONTACT,
                headline = identity.name ?: "Saved contact",
                reason = "Saved in your contacts.",
                isWarning = false,
                isCommunityAllegation = false,
            )
        }

        val score = identity.spamScore
        val reports = identity.reports

        if (score >= HIGH_RISK_SCORE || reports >= HIGH_RISK_REPORTS) {
            return Verdict(
                level = Level.HIGH_RISK,
                headline = "High-risk caller",
                reason = reportReason(reports, identity),
                isWarning = true,
                isCommunityAllegation = true,
            )
        }

        if (score >= SUSPECTED_SCORE || reports > 0) {
            return Verdict(
                level = Level.SUSPECTED,
                headline = "Suspected spam",
                reason = reportReason(reports, identity),
                isWarning = true,
                isCommunityAllegation = true,
            )
        }

        if (identity.source == CallerIdentity.Source.BUSINESS) {
            return Verdict(
                level = Level.BUSINESS,
                headline = identity.name ?: "Business",
                reason = identity.company?.let { "A listed business — $it." }
                    ?: "A listed business, from the business directory.",
                isWarning = false,
                isCommunityAllegation = false,
            )
        }

        if (identity.name != null) {
            // The name is real but the provenance is not verified, and the two
            // must not look alike. `isTentative` is the resolver's own judgement.
            val from = when (identity.source) {
                CallerIdentity.Source.LOCAL_DB -> "a name on file for this number"
                CallerIdentity.Source.CLOUD -> "a name suggested by the community"
                else -> "a name we hold for this number"
            }
            return Verdict(
                level = Level.NAMED,
                headline = if (identity.isTentative) "Possible name" else identity.name,
                reason = if (identity.isTentative) {
                    "This is $from, not a verified record. Treat it as a hint."
                } else {
                    "Kavach has $from."
                },
                isWarning = false,
                isCommunityAllegation = identity.source == CallerIdentity.Source.CLOUD,
            )
        }

        return Verdict(
            level = Level.UNKNOWN,
            headline = "Unknown caller",
            reason = "No name is known for this number. That is not a warning — it is just unknown.",
            isWarning = false,
            isCommunityAllegation = false,
        )
    }

    /**
     * The report sentence.
     *
     * Deliberately phrased as an allegation with a source. "Reported by 12
     * people" is a fact about the reports; "spam" would be a fact about the
     * caller, and the app does not know that.
     */
    private fun reportReason(reports: Int, identity: CallerIdentity): String {
        val category = identity.tags.firstOrNull()?.let { " as ${it.lowercase()}" } ?: ""
        return when {
            reports >= 2 ->
                "Reported by $reports people$category. This is a community report, not a verified finding."
            reports == 1 ->
                "Reported once$category. One report is thin evidence — treat it as a hint."
            else ->
                // A high score with no reports means the score came from pattern
                // rules rather than people, and saying otherwise would be a lie.
                "Kavach's rules score this number as risky. No one has reported it yet."
        }
    }
}
