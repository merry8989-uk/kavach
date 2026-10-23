package com.kavach.caller.domain.usecase

import com.kavach.caller.ai.SpamScorer
import com.kavach.caller.data.remote.CallerLookupDataSource
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.model.CallerIdentity
import com.kavach.caller.util.PhoneUtils
import javax.inject.Inject

/**
 * Resolve who is calling (F9.2 + F9.3).
 *
 * Order of trust, highest first:
 *  1. the user's own contacts;
 *  2. the Kavach server, if one is configured and the answer is confident enough;
 *  3. local heuristics (spam score) with no name.
 *
 * It **never invents a name**: an unknown number yields `name == null`. A
 * low-confidence community tag is returned with a low `confidence` so the UI can
 * present it as a possibility rather than a fact.
 *
 * This is not on the call-screening path — screening must stay fast and offline.
 */
class IdentifyCallerUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
    private val spamScorer: SpamScorer,
    private val remoteLookup: CallerLookupDataSource,
) {
    suspend operator fun invoke(number: String?): CallerIdentity {
        val e164 = PhoneUtils.toE164(number ?: "")
        val key = PhoneUtils.subscriberKey(number)
        if (key.isEmpty()) {
            return CallerIdentity(number = e164, source = CallerIdentity.Source.NONE)
        }

        // 1. Local contacts win.
        val localName = contactRepository.nameFor(number ?: "")
        if (localName != null) {
            return CallerIdentity(
                number = e164,
                name = localName,
                spamScore = 0,
                source = CallerIdentity.Source.CONTACT,
                confidence = 1f,
            )
        }

        // 2. The server, when there is one and it is confident enough to name.
        val remote = remoteLookup.lookup(number ?: "")
        if (remote?.hasName == true && remote.confidence >= MIN_CONFIDENCE) {
            return CallerIdentity(
                number = e164,
                name = remote.displayName,
                company = remote.businessCategory,
                spamScore = remote.spamRisk,
                reports = remote.reports,
                source = if (remote.source == SOURCE_BUSINESS) {
                    CallerIdentity.Source.BUSINESS
                } else {
                    CallerIdentity.Source.CLOUD
                },
                confidence = remote.confidence.toFloat(),
                lastUpdated = remote.lastUpdated,
                freshness = remote.freshness,
            )
        }

        // 3. Nothing trustworthy about the name — keep the spam signal only.
        // Take the *higher* of the local heuristic and the server score: a server
        // record that simply has no spam signal (0) must not erase a local one.
        val localScore = spamScorer.score(number, isContact = false)
        val remoteScore = remote?.spamRisk ?: 0
        return CallerIdentity(
            number = e164,
            name = null,
            spamScore = maxOf(localScore, remoteScore),
            reports = remote?.reports ?: 0,
            source = CallerIdentity.Source.LOCAL_DB,
            lastUpdated = remote?.lastUpdated ?: 0L,
            freshness = remote?.freshness ?: "UNKNOWN",
            // Carried through so the screen can tell "we found nothing" apart
            // from "the lookup could not run". Those look identical otherwise,
            // and one of them is a bug.
            unavailableReason = remote?.unavailableReason,
        )
    }

    private companion object {
        /** Below this, a community tag is treated as too weak to show as a name. */
        const val MIN_CONFIDENCE = 0.5

        const val SOURCE_BUSINESS = "BUSINESS"
    }
}
