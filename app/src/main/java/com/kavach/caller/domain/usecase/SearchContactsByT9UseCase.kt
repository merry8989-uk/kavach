package com.kavach.caller.domain.usecase

import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.repo.ContactRepository
import javax.inject.Inject

/**
 * T9 name search for the dialer (F1.7): typing 2648 should find "Amit".
 * Prefix match against the precomputed `t9Key`, so it stays a single query.
 */
class SearchContactsByT9UseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(digits: String): List<ContactEntity> {
        if (digits.isBlank() || !digits.all { it.isDigit() }) return emptyList()
        return contactRepository.searchByT9(digits)
    }
}
