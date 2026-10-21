package com.kavach.caller.data.diagnostics

/**
 * The event schema every log line must satisfy.
 *
 * Modelled on the shared logging standard (ProGallery Logging Checklist & Event
 * Schema v1.0), which states the rule this codebase was breaking:
 *
 *   "a log must capture intent, outcome and context together, not intent alone."
 *
 * The old Kavach log wrote free-text lines like
 * `Screening  BLOCK ******3210 rule=SPAM` — it recorded that something happened,
 * never whether it worked. Everything now goes through [EventSpec], so an event
 * with a missing outcome is rejected rather than quietly written.
 *
 * Naming follows `object_action` in snake_case, properties are snake_case, and
 * enums are fixed lists — never free text.
 */
object EventSchema {

    /** Bump on any breaking change and announce it. */
    const val VERSION = "1.0.0"

    // ---------------- fixed vocabularies ----------------

    val LOG_LEVELS = setOf("debug", "info", "warn", "error")
    val PLATFORMS = setOf("android")
    val NETWORK_TYPES = setOf("wifi", "cellular", "ethernet", "offline", "unknown")
    val RELEASE_CHANNELS = setOf("debug", "beta", "production")

    /** The outcome vocabulary. "no_op" is the one that exposes silent failures. */
    val OUTCOMES = setOf("success", "no_op", "failed")

    /** Permission and role states. */
    val ACCESS_STATES = setOf(
        "granted", "partial", "denied", "permanently_denied", "not_applicable",
    )

    /**
     * A session is created once per process, so every session_start Kavach can
     * observe is a cold start. "warm" stays in the vocabulary for the day the
     * app can distinguish an activity re-entry without a new process.
     */
    val START_TYPES = setOf("cold", "warm")

    // ---------------- envelope (Part B) ----------------

    /** Fields every event carries. */
    val ENVELOPE_REQUIRED = setOf(
        "event_name", "schema_version", "timestamp", "session_id",
        "platform", "app_version", "os", "device_model", "screen_or_route", "log_level",
    )

    val ENVELOPE_OPTIONAL = setOf(
        "attempt_id", "user_id", "user_code", "network_type", "release_channel",
    )

    // ---------------- catalogue (Part C, adapted to Kavach) ----------------

    /**
     * Kavach's own event catalogue.
     *
     * The standard's media-access events (`permission_state_resolved`,
     * `access_fix_requested`, `access_fix_result`, `media_query_result`) map
     * directly onto this app: Kavach has exactly the same "user taps a fix, did
     * it actually work?" loop for permissions and for the dialer/screening roles.
     * `media_query_result` becomes [DATA_QUERY_RESULT] because Kavach reads the
     * contact and call-log providers rather than MediaStore.
     */
    val CATALOGUE: Map<String, EventSpec> = listOf(
        // ---- lifecycle ----
        EventSpec(
            name = "session_start",
            required = setOf("start_type"),
            // cold_start_ms / warm_start_ms were declared but never measured, so
            // a real export showed them missing every time. Removed rather than
            // left as a promise the app does not keep. Startup cost is reported
            // properly by performance_marker(flow=app_start).
            enums = mapOf("start_type" to START_TYPES),
        ),
        EventSpec(
            name = "screen_view",
            required = setOf("screen_name"),
            optional = setOf("previous_screen", "duration_ms"),
        ),

        // ---- permissions and roles: the intent -> outcome pair ----
        EventSpec(
            name = "permission_state_resolved",
            required = setOf("permission", "state"),
            optional = setOf("item_count"),
            enums = mapOf("state" to ACCESS_STATES),
        ),
        EventSpec(
            name = "access_fix_requested",
            required = setOf("permission", "state_before"),
            enums = mapOf("state_before" to ACCESS_STATES),
        ),
        EventSpec(
            name = "access_fix_result",
            required = setOf("action_taken", "state_after", "outcome"),
            optional = setOf("error_code", "duration_ms"),
            enums = mapOf(
                "state_after" to ACCESS_STATES,
                "outcome" to OUTCOMES,
            ),
        ),
        EventSpec(
            name = "permission_loop",
            required = setOf("permission", "request_count"),
        ),

        // ---- data reads ----
        EventSpec(
            name = "data_query_result",
            required = setOf("source", "item_count"),
            optional = setOf("query_ms", "error_code"),
        ),

        // ---- network ----
        EventSpec(
            name = "api_request",
            required = setOf("endpoint", "method", "status_code", "latency_ms"),
            optional = setOf("retry_count"),
        ),

        // ---- failures ----
        EventSpec(
            name = "crash",
            required = setOf("fatal", "exception_type", "stack_trace_hash"),
            optional = setOf("last_screen", "last_action"),
        ),
        EventSpec(
            name = "anr",
            required = setOf("duration_ms"),
            optional = setOf("last_screen"),
        ),
        EventSpec(
            name = "error_state_shown",
            required = setOf("error_code", "screen"),
            optional = setOf("message_key"),
        ),

        // ---- performance ----
        EventSpec(
            name = "performance_marker",
            required = setOf("flow", "duration_ms"),
            optional = setOf("frame_drops"),
        ),

        // ---- Kavach domain ----
        EventSpec(
            name = "feature_toggle",
            required = setOf("feature", "enabled"),
            optional = setOf("status"),
        ),
        EventSpec(
            name = "call_screened",
            required = setOf("action", "outcome"),
            optional = setOf("has_rule", "number_type"),
        ),
        EventSpec(
            name = "call_state",
            required = setOf("direction", "phase"),
            optional = setOf("has_sim", "has_name"),
        ),
        EventSpec(
            name = "sms_sent",
            required = setOf("outcome"),
            optional = setOf("parts", "error_code"),
        ),
        EventSpec(
            name = "auto_reply",
            required = setOf("outcome"),
            optional = setOf("error_code"),
        ),
        EventSpec(
            name = "update_check",
            required = setOf("outcome", "current_version"),
            optional = setOf("available_version", "error_code"),
        ),
        EventSpec(
            name = "identity_lookup",
            required = setOf("outcome"),
            optional = setOf("source", "has_name", "confidence_band"),
        ),

        // ---- user-friendliness signals (Part E) ----
        EventSpec(
            name = "rage_tap",
            required = setOf("element", "tap_count"),
            optional = setOf("window_ms"),
        ),
        EventSpec(
            name = "dead_click",
            required = setOf("element"),
            optional = setOf("screen"),
        ),
        EventSpec(
            name = "back_button_spam",
            required = setOf("press_count"),
            optional = setOf("window_ms"),
        ),
        EventSpec(
            name = "drop_off",
            required = setOf("step"),
            optional = setOf("last_event"),
        ),
        EventSpec(
            name = "time_to_first_value",
            required = setOf("duration_ms"),
            optional = setOf("first_action"),
        ),
        EventSpec(
            name = "scroll_depth",
            required = setOf("percent"),
            optional = setOf("list_name"),
        ),
    ).associateBy { it.name }

    fun specFor(eventName: String): EventSpec? = CATALOGUE[eventName]

    fun isKnown(eventName: String): Boolean = CATALOGUE.containsKey(eventName)
}

/** One event's shape: what must be present, what may be, and which fields are enums. */
data class EventSpec(
    val name: String,
    val required: Set<String>,
    val optional: Set<String> = emptySet(),
    val enums: Map<String, Set<String>> = emptyMap(),
) {
    val allowed: Set<String> get() = required + optional
}
