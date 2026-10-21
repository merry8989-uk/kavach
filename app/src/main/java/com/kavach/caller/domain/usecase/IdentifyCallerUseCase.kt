package com.kavach.caller.domain.usecase

import com.kavach.caller.ai.SpamScorer
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.model.CallerIdentity
import com.kavach.caller.util.PhoneUtils
import javax.inject.Inject

/**
 * Resolve who is calling, local-first (F9.2).
 *
 * Never invents a name: an unknown number yields [CallerIdentity.name] == null
 * and `source = NONE`.
 */
class IdentifyCallerUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
    private val spamScorer: SpamScorer,
) {
    suspend operator fun invoke(number: String?): CallerIdentity {
        val e164 = PhoneUtils.toE164(number ?: "")
        val key = PhoneUtils.subscriberKey(number)
        if (key.isEmpty()) {
            return CallerIdentity(number = e164, source = CallerIdentity.Source.NONE)
        }

        val name = contactRepository.nameFor(number ?: "")
        val isContact = name != null
        return CallerIdentity(
            number = e164,
            name = name,
            spamScore = spamScorer.score(number, isContact = isContact),
            source = if (isContact) CallerIdentity.Source.CONTACT else CallerIdentity.Source.LOCAL_DB,
            confidence = if (isContact) 1f else 0f,
        )
    }
}
