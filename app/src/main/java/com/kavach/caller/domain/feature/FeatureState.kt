package com.kavach.caller.domain.feature

/**
 * The four states a module can be in. The spec insists on distinguishing these
 * rather than collapsing everything into a single boolean.
 */
enum class FeatureStatus {
    /** On, and the device supports it. */
    ENABLED,

    /** Turned off by the user. */
    DISABLED,

    /** Cannot run on this device right now — see [FeatureState.reason]. */
    UNAVAILABLE,

    /** On, but running with reduced capability — see [FeatureState.reason]. */
    DEGRADED,
}

/** Everything the UI needs to explain a module's current situation. */
data class FeatureState(
    val id: FeatureId,
    val enabled: Boolean,
    val status: FeatureStatus,
    /** Plain-language explanation, present for UNAVAILABLE and DEGRADED. */
    val reason: String? = null,
    val missingPermissions: List<String> = emptyList(),
    val missingRole: String? = null,
    val missingDependencies: List<FeatureId> = emptyList(),
) {
    val isUsable: Boolean get() = status == FeatureStatus.ENABLED || status == FeatureStatus.DEGRADED
}
