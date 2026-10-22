"""Telemetry intake: the server side of the logging standard.

The point of these tests is the promise made to a hundred installs: every phone
sends the same schema, the server keeps what it can validate, and it never
jams on one bad build.
"""
import time

from app import telemetry_schema


def _event(name: str = "session_start", **extra) -> dict:
    base = {
        "event_name": name,
        "schema_version": "1.0.0",
        "timestamp": "2026-10-21T18:21:43Z",
        "session_id": "sess_abc123",
        "platform": "android",
        "app_version": "0.21.0 (40)",
        "os": "Android 14 (API 34)",
        "device_model": "TECNO LH8n",
        "screen_or_route": "dialer",
        "log_level": "info",
    }
    base.update(extra)
    return base


def test_a_valid_batch_is_stored(client, db):
    response = client.post(
        "/v1/telemetry/events",
        json={
            "user_code": "KV-4F9-K2Q",
            "events": [
                _event("session_start", start_type="cold"),
                _event("screen_view", screen_name="dialer", duration_ms="1241"),
            ],
        },
    )
    assert response.status_code == 202
    body = response.json()
    assert body["accepted"] == 2
    assert body["rejected"] == 0

    from app.models import TelemetryEvent

    rows = db.query(TelemetryEvent).all()
    assert len(rows) == 2
    assert {row.user_code for row in rows} == {"KV-4F9-K2Q"}


def test_an_event_without_its_outcome_is_rejected(client):
    # The standard's core rule, enforced at the edge as well as in the app.
    bad = _event("access_fix_result", action_taken="request_role", state_after="granted")
    response = client.post(
        "/v1/telemetry/events",
        json={"user_code": "KV-4F9-K2Q", "events": [bad]},
    )
    assert response.status_code == 202
    body = response.json()
    assert body["accepted"] == 0
    assert body["rejected"] == 1
    assert any("outcome" in reason for reason in body["reasons"])


def test_an_unknown_event_is_rejected(client):
    response = client.post(
        "/v1/telemetry/events",
        json={"user_code": "KV-4F9-K2Q", "events": [_event("something_invented")]},
    )
    body = response.json()
    assert body["rejected"] == 1
    assert any("unknown event_name" in reason for reason in body["reasons"])


def test_one_bad_event_does_not_block_the_rest(client):
    response = client.post(
        "/v1/telemetry/events",
        json={
            "user_code": "KV-4F9-K2Q",
            "events": [
                _event("something_invented"),
                _event("session_start", start_type="cold"),
                _event("screen_view", screen_name="contacts"),
            ],
        },
    )
    body = response.json()
    assert body["accepted"] == 2
    assert body["rejected"] == 1


def test_a_value_outside_an_enum_is_rejected(client):
    response = client.post(
        "/v1/telemetry/events",
        json={
            "user_code": "KV-4F9-K2Q",
            "events": [_event("session_start", start_type="lukewarm")],
        },
    )
    body = response.json()
    assert body["rejected"] == 1
    assert any("enum" in reason for reason in body["reasons"])


def test_an_empty_batch_is_refused(client):
    response = client.post(
        "/v1/telemetry/events",
        json={"user_code": "KV-4F9-K2Q", "events": []},
    )
    assert response.status_code == 422


def test_the_endpoint_needs_a_token(client):
    response = client.post(
        "/v1/telemetry/events",
        json={"user_code": "KV-4F9-K2Q", "events": [_event("session_start", start_type="cold")]},
        headers={"Authorization": "Bearer wrong"},
    )
    # The shared token guard answers 403 for a wrong bearer.
    assert response.status_code in (401, 403)


def test_the_notion_push_is_an_honest_no_op_when_unconfigured(client):
    client.post(
        "/v1/telemetry/events",
        json={
            "user_code": "KV-4F9-K2Q",
            "events": [_event("session_start", start_type="cold")],
        },
    )
    response = client.post("/v1/admin/notion/push")
    assert response.status_code == 200
    body = response.json()
    assert body["pushed"] == 0
    # It says why rather than pretending to have worked.
    assert "not configured" in (body["detail"] or "").lower()
    assert body["remaining"] == 1


def test_purge_removes_old_rows(client, db):
    from app.models import TelemetryEvent

    client.post(
        "/v1/telemetry/events",
        json={
            "user_code": "KV-4F9-K2Q",
            "events": [_event("session_start", start_type="cold")],
        },
    )
    row = db.query(TelemetryEvent).first()
    row.received_at = int(time.time()) - 40 * 24 * 60 * 60
    db.commit()

    response = client.post("/v1/admin/telemetry/purge?keep_days=30")
    assert response.json()["deleted"] == 1


def test_the_server_schema_matches_the_documented_event_names():
    # A guard against the two catalogues drifting apart.
    expected = {
        "session_start", "screen_view", "permission_state_resolved",
        "access_fix_requested", "access_fix_result", "permission_loop",
        "data_query_result", "api_request", "crash", "anr", "error_state_shown",
        "performance_marker", "feature_toggle", "feature_snapshot",
        "call_screened", "call_state",
        "sms_sent", "auto_reply", "update_check", "identity_lookup",
        "rage_tap", "dead_click", "back_button_spam", "drop_off",
        "time_to_first_value", "scroll_depth",
    }
    assert set(telemetry_schema.CATALOGUE) == expected
