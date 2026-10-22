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

    /**
     * Bump on any breaking change and announce it.
     *
     * 1.1.0 adds the two severity levels the taxonomy asks for and this project
     * was missing (trace, fatal), a **category** on every event, a
     * **correlation id** so one action can be followed across the app and the
     * server, and an **environment**. It is additive: an event written against
     * 1.0.0 still validates.
     */
    const val VERSION = "1.1.0"

    // ---------------- fixed vocabularies ----------------

    /**
     * Six levels, not four.
     *
     * The two that were missing are the two that matter at the edges: `trace` is
     * the step-by-step detail you only want when something is wrong, and `fatal`
     * is the failure that ends the process — without it a crash and a caught
     * exception looked the same in the log.
     */
    val LOG_LEVELS = setOf("trace", "debug", "info", "warn", "error", "fatal")

    /**
     * What kind of log this is, as opposed to how severe it is.
     *
     * The two are independent: a failed login is an `error` *and* a
     * `security` event. Without this field a log is a flat list sorted by
     * severity, and "show me everything that touched the database" is a text
     * search rather than a filter.
     */
    val CATEGORIES = setOf(
        "application",   // what the app is doing: screens, sessions, features
        "error",         // crashes, ANRs, failed operations
        "security",      // auth failures, permission denials, anything suspicious
        "performance",   // timings, memory, slow work
        "activity",      // what the user did: taps, searches, navigation
        "infrastructure",// the process itself: start, stop, availability
        "network",       // requests, responses, latency, rate limits
        "database",      // queries, migrations, transactions
        "audit",         // who changed what, when, and on which resource
        "analytics",     // counts and adoption, kept apart from raw events
        "background",    // scheduled work, retries, jobs
        "trace",         // step-by-step detail, for when something is wrong
    )

    /** Where this build is running. Separates a developer's noise from a user's. */
    val ENVIRONMENTS = setOf("development", "staging", "production")
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
        // Added in 1.1.0.
        "category", "request_id", "environment",
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
            // One row per session at most, instead of one row per feature on a
            // first run — which a real export showed as twelve near-identical
            // lines crowding out everything else.
            name = "feature_snapshot",
            required = setOf("total", "enabled_count"),
            optional = setOf("disabled"),
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

        // ---- the families the taxonomy asks for that Kavach had none of ----

        EventSpec(
            name = "security_event",
            category = "security",
            required = setOf("kind", "outcome"),
            optional = setOf("detail", "source"),
            enums = mapOf(
                "kind" to setOf(
                    "auth_failed", "permission_denied", "permission_revoked",
                    "signature_mismatch", "tampered_file", "suspicious_input",
                ),
                "outcome" to setOf("blocked", "allowed", "recorded"),
            ),
        ),
        EventSpec(
            name = "background_job",
            category = "background",
            required = setOf("job", "outcome"),
            optional = setOf("duration_ms", "attempt", "reason"),
            enums = mapOf("outcome" to setOf("success", "failed", "skipped", "retry")),
        ),
        EventSpec(
            name = "audit_event",
            category = "audit",
            // Who did what, when, to which resource. On the app the "who" is an
            // install, because Kavach has no admin surface — but the shape is the
            // same, so app and server records can sit side by side.
            required = setOf("action", "resource"),
            optional = setOf("resource_id", "before", "after"),
        ),
        EventSpec(
            name = "perf_sample",
            category = "performance",
            required = setOf("metric", "value"),
            optional = setOf("unit", "screen"),
            enums = mapOf("unit" to setOf("ms", "bytes", "count", "percent")),
        ),
        EventSpec(
            name = "db_query",
            category = "database",
            required = setOf("operation", "outcome"),
            optional = setOf("table", "duration_ms", "rows"),
            enums = mapOf("outcome" to setOf("success", "failed", "slow")),
        ),
        EventSpec(
            name = "analytics_event",
            category = "analytics",
            // Counts and adoption, kept apart from raw activity on purpose: these
            // are the numbers a weekly review reads, and mixing them into the
            // event stream is how a dashboard becomes unreadable.
            required = setOf("metric", "value"),
            optional = setOf("dimension", "window"),
        ),
        EventSpec(
            name = "trace_step",
            category = "trace",
            // Only emitted in a development build, and only when tracing is on.
            required = setOf("step"),
            optional = setOf("detail", "elapsed_ms"),
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
    /**
     * Which kind of log this is, as opposed to how severe. Carried on the
     * envelope too, so a reader does not need the catalogue to filter by it.
     */
    val category: String = "application",
) {
    val allowed: Set<String> get() = required + optional
}
