package com.kavach.caller.domain.usecase

import com.kavach.caller.data.repo.ContactDraft
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.util.PhoneUtils
import javax.inject.Inject

/**
 * Validate and persist a contact.
 *
 * Validation lives here rather than in the editor UI so it is unit-testable and
 * cannot be bypassed by another caller.
 */
class SaveContactUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    sealed interface Result {
        data class Saved(val id: Long) : Result
        data object NeedsNameOrNumber : Result
        data class InvalidNumber(val number: String) : Result
    }

    /** Shortest subscriber key we accept. Anything less is a typo, not a number. */
    private val minDigits = 6

    suspend operator fun invoke(draft: ContactDraft): Result {
        val numbers = draft.numbers.filter { it.number.isNotBlank() }
        if (draft.name.isBlank() && numbers.isEmpty()) return Result.NeedsNameOrNumber

        val invalid = numbers.firstOrNull { PhoneUtils.subscriberKey(it.number).length < minDigits }
        if (invalid != null) return Result.InvalidNumber(invalid.number)

        return Result.Saved(contactRepository.saveDraft(draft))
    }
}
