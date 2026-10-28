package com.kavach.caller.domain.usecase

import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.data.repo.ContactRepository
import javax.inject.Inject
import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.domain.ScorePolicy
import com.kavach.caller.domain.QuietHours

/**
 * Decide what to do with an incoming call.
 *
 * Orchestrates two repositories — contact resolution and block-rule evaluation
 * — which is exactly the kind of cross-source logic that belongs in a use case
 * rather than in a service or a ViewModel.
 */
class DecideCallActionUseCase @Inject constructor(
    private val blockRepository: BlockRepository,
    private val contactRepository: ContactRepository,
    private val identifyCaller: IdentifyCallerUseCase,
    private val settings: SettingsRepository,
) {
    /**
     * Decide what to do with an incoming call.
     *
     * **Rules first, always.** A rule is something the user wrote down; the score
     * is other people's opinion plus pattern rules. When the two disagree the
     * explicit instruction wins, and that ordering is the whole reason this is a
     * single function rather than two independent checks.
     *
     * The score is consulted only when no rule matched, and only if the user has
     * turned it on — see [ScorePolicy]. With the shipped defaults it never acts.
     */
    suspend operator fun invoke(number: String?): BlockRepository.Decision {
        val isContact = !number.isNullOrBlank() && contactRepository.nameFor(number) != null

        val byRule = blockRepository.decide(number, isContact)
        if (byRule.rule != null) return byRule

        val snapshot = settings.snapshot

        // **Quiet hours, before the score.**
        //
        // Checked after the explicit rules — a rule the user wrote is a stronger
        // statement than a schedule — and before the score, because "it is 3am" is
        // a better reason to stay quiet than "somebody reported this number".
        //
        // Favourites, repeat callers and emergency numbers are exempted inside
        // QuietHours.decide, so a night-time emergency still rings.
        if (snapshot.quietHoursEnabled) {
            val window = QuietHours.Window(
                startHour = snapshot.quietHoursStartHour,
                startMinute = snapshot.quietHoursStartMinute,
                endHour = snapshot.quietHoursEndHour,
                endMinute = snapshot.quietHoursEndMinute,
                days = snapshot.quietHoursDays,
            )
            val action = QuietHours.decide(
                window = window,
                now = java.util.Calendar.getInstance(),
                number = number.orEmpty(),
                isFavourite = number != null && runCatching {
                    contactRepository.findByNumber(number)?.isFavourite == true
                }.getOrDefault(false),
                isRepeat = false,
                action = when (snapshot.quietHoursAction.uppercase()) {
                    "REJECT" -> QuietHours.Action.REJECT
                    "RING" -> QuietHours.Action.RING
                    else -> QuietHours.Action.SILENCE
                },
            )
            if (action != QuietHours.Action.RING) {
                return BlockRepository.Decision(
                    action = when (action) {
                        QuietHours.Action.REJECT -> BlockAction.BLOCK
                        else -> BlockAction.SILENCE
                    },
                    // Null rule, like the score path: the log can tell a schedule
                    // from a rule the user wrote.
                    rule = null,
                )
            }
        }

        if (snapshot.autoBlockScore <= 0) return byRule

        // Only now, and only for a real number: an unknown lookup should not
        // become a reason to act.
        if (number.isNullOrBlank()) return byRule

        val score = runCatching { identifyCaller(number).spamScore }.getOrDefault(0)
        val decision = ScorePolicy.decide(
            threshold = snapshot.autoBlockScore,
            mode = ScorePolicy.modeOf(snapshot.autoBlockAction),
            score = score,
            isSavedContact = isContact,
        )

        return if (decision.action == BlockAction.ALLOW) {
            byRule
        } else {
            // No rule produced this, so `rule` stays null and the log can tell the
            // two apart. A wrongly-silenced call has to be traceable to whatever
            // did it.
            BlockRepository.Decision(decision.action, null)
        }
    }
}
