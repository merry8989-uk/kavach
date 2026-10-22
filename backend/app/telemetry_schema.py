"""Server-side mirror of the app's event schema.

Kept deliberately in step with the Android `EventSchema`. The server validates
every incoming event against this before storing it, so a malformed or
newly-invented event is rejected at the edge rather than polluting the table.

If you add an event on the app side, add it here too — `tests/test_telemetry.py`
asserts the two catalogues agree on the event names it is given.
"""

SCHEMA_VERSION = "1.0.0"

ENVELOPE_REQUIRED = {
    "event_name", "schema_version", "timestamp", "session_id",
    "platform", "app_version", "os", "device_model", "screen_or_route", "log_level",
}
ENVELOPE_OPTIONAL = {
    "attempt_id", "user_id", "user_code", "network_type", "release_channel",
}

LOG_LEVELS = {"debug", "info", "warn", "error"}
OUTCOMES = {"success", "no_op", "failed"}
ACCESS_STATES = {"granted", "partial", "denied", "permanently_denied", "not_applicable"}

# event_name -> (required properties, allowed properties, enum constraints)
CATALOGUE: dict[str, tuple[set[str], set[str], dict[str, set[str]]]] = {
    "session_start": ({"start_type"}, set(), {"start_type": {"cold", "warm"}}),
    "screen_view": ({"screen_name"}, {"previous_screen", "duration_ms"}, {}),
    "permission_state_resolved": ({"permission", "state"}, {"item_count"}, {"state": ACCESS_STATES}),
    "access_fix_requested": ({"permission", "state_before"}, set(), {"state_before": ACCESS_STATES}),
    "access_fix_result": (
        {"action_taken", "state_after", "outcome"},
        {"error_code", "duration_ms"},
        {"state_after": ACCESS_STATES, "outcome": OUTCOMES},
    ),
    "permission_loop": ({"permission", "request_count"}, set(), {}),
    "data_query_result": ({"source", "item_count"}, {"query_ms", "error_code"}, {}),
    "api_request": (
        {"endpoint", "method", "status_code", "latency_ms"}, {"retry_count"}, {},
    ),
    "crash": ({"fatal", "exception_type", "stack_trace_hash"}, {"last_screen", "last_action"}, {}),
    "anr": ({"duration_ms"}, {"last_screen"}, {}),
    "error_state_shown": ({"error_code", "screen"}, {"message_key"}, {}),
    "performance_marker": ({"flow", "duration_ms"}, {"frame_drops"}, {}),
    "feature_toggle": ({"feature", "enabled"}, {"status"}, {}),
    "feature_snapshot": ({"total", "enabled_count"}, {"disabled"}, {}),
    "call_screened": ({"action", "outcome"}, {"has_rule", "number_type"}, {"outcome": OUTCOMES}),
    "call_state": ({"direction", "phase"}, {"has_sim", "has_name"}, {}),
    "sms_sent": ({"outcome"}, {"parts", "error_code"}, {"outcome": OUTCOMES}),
    "auto_reply": ({"outcome"}, {"error_code"}, {"outcome": OUTCOMES}),
    "update_check": ({"outcome", "current_version"}, {"available_version", "error_code"}, {"outcome": OUTCOMES}),
    "identity_lookup": ({"outcome"}, {"source", "has_name", "confidence_band"}, {"outcome": OUTCOMES}),
    "rage_tap": ({"element", "tap_count"}, {"window_ms"}, {}),
    "dead_click": ({"element"}, {"screen"}, {}),
    "back_button_spam": ({"press_count"}, {"window_ms"}, {}),
    "drop_off": ({"step"}, {"last_event"}, {}),
    "time_to_first_value": ({"duration_ms"}, {"first_action"}, {}),
    "scroll_depth": ({"percent"}, {"list_name"}, {}),
}


def validate(event: dict) -> list[str]:
    """Return a list of problems; empty means the event is acceptable."""
    problems: list[str] = []

    for key in ENVELOPE_REQUIRED:
        if not event.get(key):
            problems.append(f"missing envelope field '{key}'")

    name = event.get("event_name")
    if not name:
        return problems

    spec = CATALOGUE.get(name)
    if spec is None:
        problems.append(f"unknown event_name '{name}'")
        return problems

    required, optional, enums = spec
    allowed = ENVELOPE_REQUIRED | ENVELOPE_OPTIONAL | required | optional

    for key in required:
        if not event.get(key):
            problems.append(f"'{name}' missing required '{key}'")

    for key in event:
        if key not in allowed:
            problems.append(f"'{name}' has unexpected property '{key}'")

    for key, permitted in enums.items():
        value = event.get(key)
        if value is not None and value not in permitted:
            problems.append(f"'{name}' has {key}='{value}' outside the enum")

    if event.get("log_level") and event["log_level"] not in LOG_LEVELS:
        problems.append(f"log_level '{event['log_level']}' is not valid")

    return problems
