package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A speed-dial assignment (F1.6). Slots 2-9; slot 1 is reserved for voicemail
 * by convention, like a stock dialer.
 */
@Entity(tableName = "speed_dial")
data class SpeedDialEntity(
    @PrimaryKey val slot: Int,
    val number: String,
    val label: String,
)
