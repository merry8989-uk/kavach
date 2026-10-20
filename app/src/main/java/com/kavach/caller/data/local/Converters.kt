package com.kavach.caller.data.local

import androidx.room.TypeConverter
import com.kavach.caller.data.local.entity.PhoneNumber
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room type converters: lists are persisted as JSON blobs. */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromStringList(value: List<String>?): String = json.encodeToString(value ?: emptyList())

    @TypeConverter
    fun toStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList() else json.decodeFromString(value)

    @TypeConverter
    fun fromPhoneList(value: List<PhoneNumber>?): String =
        json.encodeToString(value ?: emptyList())

    @TypeConverter
    fun toPhoneList(value: String?): List<PhoneNumber> =
        if (value.isNullOrBlank()) emptyList() else json.decodeFromString(value)
}
