package com.kavach.caller.domain

import com.kavach.caller.data.local.entity.BlockAction

/**
 * What a reputation score is allowed to do on its own.
 *
 * The review was specific about this, and it is the one place in the app where a
 * wrong default kills a real call:
 *
 * > *"Important: sirf reputation score ke basis par genuine calls automatically
 * > block na hon; threshold aur action user control kare."*
 *
 * Three rules follow from that sentence, and each is a decision rather than a
 * detail:
 *
 * 1. **Off by default.** `autoBlockScore = 0` means the score never acts. Rules
 *    block; a score does not. A score is other people's opinion plus pattern
 *    rules — not evidence.
 * 2. **A saved contact is never acted on.** If someone is in the address book,
 *    strangers reporting their number is not a reason to silence their call. The
 *    user knows them; the crowd does not.
 * 3. **The gentlest action is the default.** Silence rather than block: the call
 *    still arrives, is still logged, and still shows a verdict — it just does not
 *    ring. Reversible in the way blocking is not.
 *
 * Pure, so all three can be tested rather than assumed.
 */
object ScorePolicy {

    /** The user's choices, mirroring the settings vocabulary. */
    enum class Mode { OFF, SILENCE, BLOCK, WARN }

    data class Decision(
        val action: BlockAction,
        /**
         * True when the score, rather than a rule, produced this. The screening
         * log records which, so a wrongly-silenced call can be traced to the
         * setting that did it.
         */
        val fromScore: Boolean,
        /** Why, in a sentence, for the log and for the in-call verdict. */
        val reason: String?,
    ) {
        companion object {
            val ALLOW = Decision(BlockAction.ALLOW, fromScore = false, reason = null)
        }
    }

    fun modeOf(value: String?): Mode = when (value?.lowercase()) {
        "silence" -> Mode.SILENCE
        "block" -> Mode.BLOCK
        "warn" -> Mode.WARN
        else -> Mode.OFF
    }

    /**
     * Decide from the score alone.
     *
     * @param threshold 0-100; 0 means the user has turned this off.
     * @param mode the action the user chose.
     * @param score the number's reputation score.
     * @param isSavedContact whether the number is in the user's contacts.
     */
    fun decide(
        threshold: Int,
        mode: Mode,
        score: Int,
        isSavedContact: Boolean,
    ): Decision {
        // Rule 1: off means off. Not "off unless the score is really high".
        if (threshold <= 0 || mode == Mode.OFF) return Decision.ALLOW

        // Rule 2: the user's own contact list outranks the crowd.
        if (isSavedContact) {
            return Decision(
                action = BlockAction.ALLOW,
                fromScore = false,
                reason = "In your contacts, so the score was not acted on.",
            )
        }

        if (score < threshold) return Decision.ALLOW

        val action = when (mode) {
            Mode.BLOCK -> BlockAction.BLOCK
            Mode.SILENCE -> BlockAction.SILENCE
            // "Warn" is not a screening action — the call is allowed and the
            // verdict banner does the warning. Saying so here keeps the mapping in
            // one place instead of leaving a silent gap in the `when`.
            Mode.WARN -> BlockAction.ALLOW
            Mode.OFF -> BlockAction.ALLOW
        }

        return Decision(
            action = action,
            fromScore = action != BlockAction.ALLOW,
            reason = "Scored $score, at or above your threshold of $threshold.",
        )
    }

    /** How the choice reads in Settings. */
    fun label(mode: Mode): String = when (mode) {
        Mode.OFF -> "Never"
        Mode.SILENCE -> "Silence the ring"
        Mode.BLOCK -> "Reject the call"
        Mode.WARN -> "Show a warning only"
    }
}
