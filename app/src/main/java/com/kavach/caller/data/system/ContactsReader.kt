package com.kavach.caller.data.system

import android.content.Context
import android.provider.ContactsContract
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.local.entity.PhoneNumber
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device contact store and maps it into Kavach's enriched model.
 * Requires READ_CONTACTS. Returns an empty list (never throws) when the
 * permission is missing, so the UI can degrade gracefully.
 */
@Singleton
class ContactsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun readAll(): List<ContactEntity> {
        val out = mutableListOf<ContactEntity>()
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)
                val hasPhoneIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                while (c.moveToNext()) {
                    if (c.getInt(hasPhoneIdx) == 0) continue
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx) ?: continue
                    val photo = c.getString(photoIdx)
                    val numbers = readNumbers(id)
                    if (numbers.isEmpty()) continue
                    val org = readOrg(id)
                    out += ContactEntity(
                        systemId = id,
                        displayName = name,
                        photoUri = photo,
                        company = org,
                        numbers = numbers,
                    )
                }
            }
        }
        return out
    }

    private fun readNumbers(contactId: Long): List<PhoneNumber> {
        val result = mutableListOf<PhoneNumber>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        runCatching {
            context.contentResolver.query(uri, projection, selection, arrayOf(contactId.toString()), null)?.use { c ->
                val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (c.moveToNext()) {
                    val number = c.getString(numIdx) ?: continue
                    val type = c.getInt(typeIdx)
                    val label = c.getString(labelIdx)
                        ?: ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, null).toString()
                    result += PhoneNumber(label = label, number = number)
                }
            }
        }
        return result.distinctBy { it.number }
    }

    private fun readOrg(contactId: Long): String? {
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
            "${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
        runCatching {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return null
    }
}
