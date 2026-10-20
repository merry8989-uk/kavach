package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    /** Absolute path to the (encrypted) audio file. */
    val filePath: String,
    val timestamp: Long,
    val durationSec: Long = 0,
    val sizeBytes: Long = 0,
    val encrypted: Boolean = true,
)
