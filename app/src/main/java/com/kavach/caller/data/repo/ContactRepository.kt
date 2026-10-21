package com.kavach.caller.data.repo

import com.kavach.caller.data.local.dao.ContactDao
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.system.ContactsReader
import com.kavach.caller.data.system.ContactsWriter
import com.kavach.caller.util.PhoneUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(
    private val dao: ContactDao,
    private val systemReader: ContactsReader,
    private val systemWriter: ContactsWriter,
) {
    fun observeAll(): Flow<List<ContactEntity>> = dao.observeAll()

    suspend fun search(q: String): List<ContactEntity> = dao.search(q)

    /** T9 name search for the dialer (input is digits). */
    suspend fun searchByT9(digits: String): List<ContactEntity> {
        if (digits.isBlank()) return emptyList()
        return dao.searchT9(PhoneUtils.t9(digits))
    }

    /** Pull the device contacts into Kavach, preserving Kavach-only fields. */
    suspend fun syncFromSystem(): Int {
        val system = systemReader.readAll()
        var n = 0
        for (c in system) {
            val existing = c.systemId?.let { dao.findBySystemId(it) }
            val merged = c.copy(
                id = existing?.id ?: 0,
                tags = existing?.tags ?: emptyList(),
                notes = existing?.notes,
                jobTitle = existing?.jobTitle,
                isFavourite = existing?.isFavourite ?: false,
                t9Key = PhoneUtils.t9(c.displayName),
            )
            dao.upsert(merged)
            n++
        }
        return n
    }

    /** Always keep the T9 key in sync with the name on write. */
    suspend fun save(contact: ContactEntity): Long =
        dao.upsert(contact.copy(t9Key = PhoneUtils.t9(contact.displayName)))

    /** Toggle the favourite flag without going through the full editor. */
    suspend fun toggleFavourite(contact: ContactEntity) =
        dao.upsert(contact.copy(isFavourite = !contact.isFavourite))

    suspend fun delete(contact: ContactEntity) = dao.delete(contact)

    /**
     * Create or update a contact from the editor form. Writes to the device
     * store first (so the contact appears in the phone's own contacts app),
     * then mirrors it into Kavach's enriched table.
     */
    suspend fun saveDraft(draft: ContactDraft): Long {
        val numbers = draft.numbers.filter { it.number.isNotBlank() }
        val name = draft.name.trim().ifBlank { numbers.firstOrNull()?.number ?: "Unknown" }
        val company = draft.company.trim().ifBlank { null }
        val jobTitle = draft.jobTitle.trim().ifBlank { null }
        val notes = draft.notes.trim().ifBlank { null }

        val systemId = if (draft.systemId == null) {
            systemWriter.insert(name, numbers, company, jobTitle, notes)
        } else {
            systemWriter.update(draft.systemId, name, company, jobTitle, notes)
            draft.systemId
        }

        // Per-contact ringtone lives on the device contact, not in Kavach.
        if (systemId != null) {
            systemWriter.setCustomRingtone(systemId, draft.ringtoneUri)
        }

        val entity = ContactEntity(
            id = draft.id,
            systemId = systemId,
            displayName = name,
            company = company,
            jobTitle = jobTitle,
            notes = notes,
            tags = draft.tags,
            numbers = numbers,
            isFavourite = draft.isFavourite,
            t9Key = PhoneUtils.t9(name),
            ringtoneUri = draft.ringtoneUri,
        )
        return dao.upsert(entity)
    }

    /**
     * Remove a contact. If it is linked to a device contact, that is deleted
     * too — otherwise the next sync would just bring it back.
     */
    suspend fun deleteContact(contact: ContactEntity) {
        contact.systemId?.let { systemWriter.delete(it) }
        dao.delete(contact)
    }

    /**
     * Resolve a display name for an incoming number, or null if unknown.
     * Single indexed-ish SQL query — safe to call on the call-screening
     * hot path.
     */
    suspend fun nameFor(number: String): String? {
        val key = PhoneUtils.subscriberKey(number)
        if (key.isEmpty()) return null
        return dao.findByNumberKey(key)?.displayName
    }
}
