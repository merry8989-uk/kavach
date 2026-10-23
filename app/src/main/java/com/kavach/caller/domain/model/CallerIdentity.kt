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
    /**
     * When the record was last updated, epoch millis; 0 when unknown.
     *
     * The review asked for this by name — *"Data kab update hua"* — and it is the
     * difference between a result and a claim about the present. A name from
     * eighteen months ago and one from this morning should not look alike.
     */
    val lastUpdated: Long = 0L,
    /** FRESH | RECENT | STALE | UNKNOWN — the server's own judgement. */
    val freshness: String = "UNKNOWN",
    /**
     * Why there is no result, when there is none. Null when the lookup worked.
     *
     * An empty card and an unavailable lookup look identical otherwise, and one
     * of them is a bug.
     */
    val unavailableReason: String? = null,
) {
    /** True when we have nothing, and the server told us why. */
    val isUnavailable: Boolean get() = unavailableReason != null

    enum class Source { CONTACT, LOCAL_DB, CLOUD, BUSINESS, NONE }

    val isKnown: Boolean get() = name != null

    /**
     * True when the name came from the community rather than a verified or
     * personal source, so the UI can say "possible" instead of stating it.
     */
    val isTentative: Boolean
        get() = name != null && source == Source.CLOUD && confidence < 0.8f
}
