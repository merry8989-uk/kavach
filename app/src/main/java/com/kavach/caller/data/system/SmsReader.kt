package com.kavach.caller.data.system

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads SMS threads (READ_SMS). Returns empty when the permission is missing
 * so the Messages screen degrades gracefully instead of crashing.
 */
@Singleton
class SmsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Sms(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
        val read: Boolean,
    )

    data class Thread(
        val address: String,
        val snippet: String,
        val date: Long,
        val unreadCount: Int,
        val messageCount: Int,
    )

    fun readRecent(limit: Int = 500): List<Sms> {
        val out = mutableListOf<Sms>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readIdx = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                var n = 0
                while (c.moveToNext() && n < limit) {
                    out += Sms(
                        id = c.getLong(idIdx),
                        address = c.getString(addrIdx) ?: "Unknown",
                        body = c.getString(bodyIdx) ?: "",
                        date = c.getLong(dateIdx),
                        type = c.getInt(typeIdx),
                        read = c.getInt(readIdx) == 1,
                    )
                    n++
                }
            }
        }
        return out
    }

    /** Group the flat message list into per-sender threads, newest first. */
    fun threads(limit: Int = 200): List<Thread> =
        readRecent()
            .groupBy { it.address }
            .map { (address, msgs) ->
                val sorted = msgs.sortedByDescending { it.date }
                Thread(
                    address = address,
                    snippet = sorted.first().body,
                    date = sorted.first().date,
                    unreadCount = msgs.count { !it.read && it.type == Telephony.Sms.MESSAGE_TYPE_INBOX },
                    messageCount = msgs.size,
                )
            }
            .sortedByDescending { it.date }
            .take(limit)
}
