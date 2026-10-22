package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A number the user looked up (F3, reverse number search).
 *
 * Deliberately thin. It stores the number and when it was last looked up, and
 * nothing about *why* — a search history that quietly accumulates context is a
 * search history that becomes a profile of the person using it.
 *
 * `number` is the primary key, so looking the same number up twice moves it to
 * the top rather than adding a row. A history that fills with one repeated lookup
 * is a history nobody can use.
 */
@Entity(
    tableName = "search_history",
    indices = [Index(value = ["lookedUpAt"])],
)
data class SearchHistoryEntity(
    @PrimaryKey val number: String,
    /** The name we knew at the time, if any. Refreshed on each lookup. */
    val name: String? = null,
    /** 0-100, as the caller-identity resolver scored it. Refreshed on each lookup. */
    val spamScore: Int = 0,
    val lookedUpAt: Long,
)
