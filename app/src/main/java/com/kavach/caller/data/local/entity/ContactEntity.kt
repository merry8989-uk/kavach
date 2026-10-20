package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A phone number attached to a contact, with a human label ("mobile", "work"). */
@Serializable
data class PhoneNumber(
    val label: String,
    val number: String,
)

/**
 * Kavach's enriched contact profile. Sits alongside the system contact store:
 * we keep Kavach-only fields (tags, notes, company overrides) that Android's
 * ContactsContract can't hold, and link back via [systemId] when a system
 * contact exists.
 */
@Entity(tableName = "contacts_enriched")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Raw contact id in ContactsContract, or null for a Kavach-only contact. */
    val systemId: Long? = null,
    val displayName: String,
    val photoUri: String? = null,
    val company: String? = null,
    val jobTitle: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val numbers: List<PhoneNumber> = emptyList(),
    /** Crowdsourced / resolved name for this number, if any. */
    val resolvedName: String? = null,
    val isFavourite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
