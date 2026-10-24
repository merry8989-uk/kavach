package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How a call from one person should look.
 *
 * Keyed by [contactKey] rather than by a contact row id, and that choice matters:
 * a card should survive the contact being edited, re-synced or deleted and
 * re-added, and it should be settable for a number that is not a contact at all.
 * The key is the E.164 number, which is stable across all of those.
 *
 * One card per number, hence the unique index.
 */
@Entity(
    tableName = "calling_cards",
    indices = [Index(value = ["contactKey"], unique = true)],
)
data class CallingCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The E.164 number this card belongs to. */
    val contactKey: String,
    /**
     * The background image, as a persisted content URI.
     *
     * Persisted, not the raw picker URI: the picker hands back a grant that
     * expires with the process, and a card that turns into a grey rectangle the
     * next morning is worse than no card.
     */
    val backgroundUri: String? = null,
    /**
     * What to show above the name. Null uses the default for the call direction.
     */
    val headline: String? = null,
    /** A line under the name — a greeting, a note to self, anything. */
    val subtitle: String? = null,
    /** ARGB, or null to follow the theme. */
    val accentColor: Int? = null,
    /**
     * Darken the image so the name stays readable over it.
     *
     * On by default, because the failure mode of a photo background is white
     * text on a bright sky and a name nobody can read.
     */
    val dimBackground: Boolean = true,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)
