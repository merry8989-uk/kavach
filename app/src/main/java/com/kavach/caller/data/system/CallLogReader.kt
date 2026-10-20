package com.kavach.caller.data.system

import android.content.Context
import android.provider.CallLog
import com.kavach.caller.data.local.entity.CallLogEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the system call log (READ_CALL_LOG). Empty list if permission missing. */
@Singleton
class CallLogReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun readRecent(limit: Int = 500): List<CallLogEntity> {
        val out = mutableListOf<CallLogEntity>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null, null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                var count = 0
                while (c.moveToNext() && count < limit) {
                    out += CallLogEntity(
                        number = c.getString(numIdx) ?: "",
                        name = c.getString(nameIdx),
                        type = c.getInt(typeIdx),
                        durationSec = c.getLong(durIdx),
                        timestamp = c.getLong(dateIdx),
                    )
                    count++
                }
            }
        }
        return out
    }
}
