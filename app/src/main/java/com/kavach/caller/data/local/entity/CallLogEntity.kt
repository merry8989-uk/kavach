package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Unique on (number, timestamp, type) so re-syncing the system call log is
 * idempotent. Before this, every sync inserted a fresh copy of every call
 * because the primary key is auto-generated.
 */
@Entity(
    tableName = "call_log",
    indices = [Index(value = ["number", "timestamp", "type"], unique = true)],
)
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
