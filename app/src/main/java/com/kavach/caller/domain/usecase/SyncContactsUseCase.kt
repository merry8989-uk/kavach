package com.kavach.caller.domain.usecase

import com.kavach.caller.data.repo.ContactRepository
import javax.inject.Inject

/** Import device contacts into Kavach, preserving Kavach-only fields (F2.1). */
class SyncContactsUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    /** @return how many contacts were imported. */
    suspend operator fun invoke(): Int = contactRepository.syncFromSystem()
}
