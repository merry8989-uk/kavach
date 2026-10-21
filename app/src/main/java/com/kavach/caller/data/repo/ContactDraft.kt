package com.kavach.caller.data.repo

import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.local.entity.PhoneNumber

/** Editable form model for creating or updating a contact. */
data class ContactDraft(
    val id: Long = 0,
    val systemId: Long? = null,
    val name: String = "",
    val numbers: List<PhoneNumber> = listOf(PhoneNumber("Mobile", "")),
    val company: String = "",
    val jobTitle: String = "",
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val isFavourite: Boolean = false,
    val ringtoneUri: String? = null,
) {
    companion object {
        fun from(contact: ContactEntity) = ContactDraft(
            id = contact.id,
            systemId = contact.systemId,
            name = contact.displayName,
            numbers = contact.numbers.ifEmpty { listOf(PhoneNumber("Mobile", "")) },
            company = contact.company.orEmpty(),
            jobTitle = contact.jobTitle.orEmpty(),
            notes = contact.notes.orEmpty(),
            tags = contact.tags,
            isFavourite = contact.isFavourite,
            ringtoneUri = contact.ringtoneUri,
        )
    }
}
