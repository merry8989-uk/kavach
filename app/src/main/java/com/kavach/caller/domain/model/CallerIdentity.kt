package com.kavach.caller.domain.model

/**
 * A caller identity result (F9). Domain-level: no Android, no Room, no network
 * types leak in here.
 *
 * `source` records where the fact came from, and `confidence` how much it is
 * trusted — a prefix or circle is never treated as proof of identity.
 */
data class CallerIdentity(
    val number: String,
    val name: String? = null,
    val company: String? = null,
    val tags: List<String> = emptyList(),
    val spamScore: Int = 0,
    val reports: Int = 0,
    val source: Source = Source.NONE,
    val confidence: Float = 0f,
) {
    enum class Source { CONTACT, LOCAL_DB, CLOUD, NONE }

    val isKnown: Boolean get() = name != null
}
