package com.kavach.caller.ui.screens.dialer

/** Where a dialer search result came from. */
enum class MatchSource { CONTACT, RECENT, BOTH }

/**
 * One row in the dialer's search results.
 *
 * A number can be both a saved contact and a recent caller. When it is, the row
 * is built from the **recent call** — its timestamp is what the user is
 * actually looking for — while the name still comes from the contact, because a
 * saved name beats whatever the call log recorded.
 */
data class DialerMatch(
    val number: String,
    val name: String?,
    val source: MatchSource,
    val lastCallAt: Long? = null,
    val lastCallType: Int? = null,
    val contactId: Long? = null,
) {
    val displayName: String get() = name ?: number
}
