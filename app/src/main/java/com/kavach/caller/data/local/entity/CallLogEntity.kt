package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_log")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val name: String? = null,
    /** Matches android.provider.CallLog.Calls type constants. */
    val type: Int,
    val durationSec: Long = 0,
    val timestamp: Long,
    val simSlot: Int = -1,
    val spamScore: Int = 0,
    val aiAnswered: Boolean = false,
    val recordingId: Long? = null,
    val notes: String? = null,
)
