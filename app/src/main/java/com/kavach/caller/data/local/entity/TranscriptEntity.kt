package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The AI assistant's output for one call: transcript + structured brief. */
@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val timestamp: Long,
    val transcript: String,
    val summary: String? = null,
    /** e.g. DELIVERY, APPOINTMENT, EMERGENCY, SALES, SPAM, PERSONAL, UNKNOWN */
    val intent: String = "UNKNOWN",
    /** POSITIVE / NEUTRAL / NEGATIVE */
    val sentiment: String = "NEUTRAL",
    /** 0..100 risk of spam */
    val risk: Int = 0,
    val recordingId: Long? = null,
)
