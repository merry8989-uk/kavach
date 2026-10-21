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
    /** FRESH | STALE | UNKNOWN */
    val freshness: String,
) {
    val hasName: Boolean get() = !displayName.isNullOrBlank()
}
