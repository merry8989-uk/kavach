package com.kavach.caller.data.system

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.kavach.caller.data.local.entity.PhoneNumber
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes to the device contact store (WRITE_CONTACTS): create, update and
 * delete. So a contact added in Kavach also shows up in the phone's own
 * contacts app, and vice versa.
 *
 * All operations fail soft — a missing permission or a read-only account
 * returns null/false rather than crashing, and Kavach still keeps its copy.
 */
@Singleton
class ContactsWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver

    /** Create a new device contact. Returns the aggregated contact id, or null. */
    fun insert(
        name: String,
        numbers: List<PhoneNumber>,
        company: String?,
        jobTitle: String?,
        notes: String?,
    ): Long? = runCatching {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build()
        )
        numbers.forEach { number ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number.number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phoneType(number.label))
                    .build()
            )
        }
        if (!company.isNullOrBlank() || !jobTitle.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, jobTitle)
                    .build()
            )
        }
        if (!notes.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, notes)
                    .build()
            )
        }
        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull() ?: return null
        contactIdForRaw(rawId)
    }.getOrElse {
        Log.w(TAG, "insert failed: ${it.message}")
        null
    }

    /** Update name / company / job title / notes on an existing device contact. */
    fun update(
        contactId: Long,
        name: String,
        company: String?,
        jobTitle: String?,
        notes: String?,
    ): Boolean = runCatching {
        val rawIds = rawIdsFor(contactId)
        if (rawIds.isEmpty()) return false
        rawIds.forEach { rawId ->
            if (name.isNotBlank()) {
                upsert(
                    rawId,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    ContentValues().apply {
                        put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    },
                )
            }
            if (!company.isNullOrBlank() || !jobTitle.isNullOrBlank()) {
                upsert(
                    rawId,
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                    ContentValues().apply {
                        put(ContactsContract.CommonDataKinds.Organization.COMPANY, company)
                        put(ContactsContract.CommonDataKinds.Organization.TITLE, jobTitle)
                    },
                )
            }
            if (!notes.isNullOrBlank()) {
                upsert(
                    rawId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                    ContentValues().apply {
                        put(ContactsContract.CommonDataKinds.Note.NOTE, notes)
                    },
                )
            }
        }
        true
    }.getOrElse {
        Log.w(TAG, "update failed: ${it.message}")
        false
    }

    /** Delete a device contact (all its raw contacts). */
    fun delete(contactId: Long): Boolean = runCatching {
        resolver.delete(
            ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
        ) > 0
    }.getOrElse {
        Log.w(TAG, "delete failed: ${it.message}")
        false
    }

    private fun upsert(rawId: Long, mime: String, values: ContentValues) {
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(rawId.toString(), mime)
        val updated = resolver.update(ContactsContract.Data.CONTENT_URI, values, selection, args)
        if (updated == 0) {
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            values.put(ContactsContract.Data.MIMETYPE, mime)
            runCatching { resolver.insert(ContactsContract.Data.CONTENT_URI, values) }
        }
    }

    private fun rawIdsFor(contactId: Long): List<Long> {
        val out = mutableListOf<Long>()
        runCatching {
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { cursor -> while (cursor.moveToNext()) out.add(cursor.getLong(0)) }
        }
        return out
    }

    private fun contactIdForRaw(rawId: Long): Long? = runCatching {
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawId.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    private fun phoneType(label: String): Int = when (label.trim().lowercase()) {
        "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
        "work" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
        "mobile", "cell" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        "main" -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
        else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
    }

    private companion object {
        const val TAG = "KavachContacts"
    }
}
