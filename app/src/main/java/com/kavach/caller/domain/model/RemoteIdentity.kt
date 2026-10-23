package com.kavach.caller.domain.model

/**
 * A caller identity fetched from the Kavach backend (F9.3).
 *
 * Kept separate from [CallerIdentity] so the UI can tell a server answer apart
 * from a local one, and so a low-confidence community tag is never presented as
 * a certainty.
 */
data class RemoteIdentity(
    val number: String,
    val displayName: String?,
    val numberType: String?,
    val businessCategory: String?,
    /** CONTACT | COMMUNITY | BUSINESS | PROVIDER | NONE */
    val source: String,
    val confidence: Double,
    /** VERIFIED | UNVERIFIED | DISPUTED */
    val verificationStatus: String,
    val spamRisk: Int,
    val reports: Int,
    /** FRESH | RECENT | STALE | UNKNOWN */
    val freshness: String,
    /** Epoch millis of the last update, 0 when unknown. */
    val lastUpdated: Long = 0L,
    /** Why there is no result, when there is none. */
    val unavailableReason: String? = null,
) {
    val hasName: Boolean get() = !displayName.isNullOrBlank()
}
