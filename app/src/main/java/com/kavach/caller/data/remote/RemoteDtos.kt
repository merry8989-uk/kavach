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
