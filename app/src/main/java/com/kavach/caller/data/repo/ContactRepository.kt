package com.kavach.caller.data.repo

import com.kavach.caller.data.local.dao.ContactDao
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.system.ContactsReader
import com.kavach.caller.util.PhoneUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(
    private val dao: ContactDao,
    private val systemReader: ContactsReader,
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

    suspend fun delete(contact: ContactEntity) = dao.delete(contact)

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
