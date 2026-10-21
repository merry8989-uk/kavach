package com.kavach.caller.data.remote

import kotlinx.serialization.Serializable

/**
 * Wire models for the Kavach backend. Field names mirror
 * `docs/API_CONTRACT.md` and `backend/app/schemas.py` exactly — if you change one
 * side, change both.
 */

@Serializable
data class LookupDto(
    val number: String,
    val displayName: String? = null,
    val numberType: String? = null,
    val businessCategory: String? = null,
    val source: String = "NONE",
    val confidence: Double = 0.0,
    val verificationStatus: String = "UNVERIFIED",
    val spamRisk: Int = 0,
    val reports: Int = 0,
    val lastUpdated: Long = 0,
    val freshness: String = "UNKNOWN",
    val unavailableReason: String? = null,
)

@Serializable
data class ReputationDto(
    val number: String,
    val score: Int = 0,
    val bands: List<String> = emptyList(),
    val explain: List<String> = emptyList(),
    val ruleVersion: String = "",
)

@Serializable
data class BusinessResultDto(
    val name: String,
    val category: String? = null,
    val verified: Boolean = false,
    val source: String = "BUSINESS",
)

@Serializable
data class BusinessSearchDto(
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0,
    val results: List<BusinessResultDto> = emptyList(),
)

@Serializable
data class ReportRequestDto(
    val number: String,
    val category: String,
    val note: String? = null,
)

@Serializable
data class ReportResponseDto(
    val accepted: Boolean = false,
    val reports: Int = 0,
    val detail: String? = null,
)

@Serializable
data class CorrectionRequestDto(
    val number: String,
    val suggestedName: String,
    val evidence: String? = null,
)

@Serializable
data class CorrectionResponseDto(
    val accepted: Boolean = false,
    val status: String = "PENDING",
)

// ---------------------------------------------------------------------------
// Consent-based caller ID (docs/CALLER-ID-PLAN.md).
// ---------------------------------------------------------------------------

@Serializable
data class OtpRequestBodyDto(val phone: String, val country: String = "IN")

@Serializable
data class OtpRequestResponseDto(
    val request_id: String,
    val expires_in: Int = 300,
    /** Present only when the server runs with OTP_DEBUG_RETURN (local dev). */
    val debug_code: String? = null,
)

@Serializable
data class OtpVerifyBodyDto(val request_id: String, val otp: String)

@Serializable
data class TokenDto(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Int = 3600,
    val is_new: Boolean = false,
    val user_id: String = "",
)

@Serializable
data class ProfileUpdateDto(
    val display_name: String? = null,
    val publish_name: Boolean = false,
    val allow_spam_reports: Boolean? = null,
)

@Serializable
data class ProfileDto(
    val user_id: String = "",
    val phone_masked: String? = null,
    val display_name: String? = null,
    val publish_name: Boolean = false,
    val allow_spam_reports: Boolean = true,
    val is_verified: Boolean = false,
    val trust_score: Int = 0,
    val created_at: Long = 0,
)

@Serializable
data class ConsentEventDto(
    val action: String,
    val detail: String? = null,
    val created_at: Long = 0,
)

@Serializable
data class ContributeDto(
    val number: String,
    val name: String,
    val evidence: String? = null,
)
