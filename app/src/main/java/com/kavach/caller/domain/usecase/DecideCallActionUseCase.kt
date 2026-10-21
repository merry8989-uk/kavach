package com.kavach.caller.domain.usecase

import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.data.repo.ContactRepository
import javax.inject.Inject

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
) {
    suspend operator fun invoke(number: String?): BlockRepository.Decision {
        val isContact = !number.isNullOrBlank() && contactRepository.nameFor(number) != null
        return blockRepository.decide(number, isContact)
    }
}
