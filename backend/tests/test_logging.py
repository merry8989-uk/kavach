"""Server-side structured logging.

The app has had a logging standard since v0.18.0 and the server had nothing —
which is half a logging story. These tests pin the envelope, the twelve
categories, the six severities, and the one field that makes the two halves one
story: the request id that the app sends and the server reuses.
"""
import json
import logging

import pytest

from app.logging import (
    APP_ID,
    CATEGORIES,
    JsonFormatter,
    new_request_id,
    request_logging_middleware,
    set_request_id,
)


def _record(**extra) -> logging.LogRecord:
    record = logging.LogRecord(
        name="kavach", level=logging.INFO, pathname="", lineno=0,
        msg="something happened", args=(), exc_info=None,
    )
    for key, value in extra.items():
        setattr(record, key, value)
    return record


def _formatted(**extra) -> dict:
    set_request_id(None)
    return json.loads(JsonFormatter(environment="production").format(_record(**extra)))


# ------------------------------------------------------------------ envelope --

def test_the_envelope_has_the_fields_the_taxonomy_asks_for():
    out = _formatted(category="network")
    for field in ("timestamp", "severity", "category", "message", "app_id", "environment"):
        assert field in out, f"missing {field}"
    assert out["app_id"] == APP_ID
    assert out["environment"] == "production"


def test_one_line_is_one_json_object():
    line = JsonFormatter(environment="production").format(_record())
    assert line.count("\n") == 0
    json.loads(line)


def test_metadata_is_carried_separately():
    out = _formatted(category="network", endpoint="/v1/lookup", status=200)
    assert out["metadata"]["endpoint"] == "/v1/lookup"
    assert out["metadata"]["status"] == 200


# ----------------------------------------------------------------- severity --

@pytest.mark.parametrize(
    "levelname,expected",
    [
        ("CRITICAL", "fatal"),
        ("ERROR", "error"),
        ("WARNING", "warn"),
        ("INFO", "info"),
        ("DEBUG", "debug"),
    ],
)
def test_python_levels_map_onto_the_six_the_taxonomy_uses(levelname, expected):
    record = _record(category="application")
    record.levelname = levelname
    set_request_id(None)
    out = json.loads(JsonFormatter(environment="production").format(record))
    assert out["severity"] == expected


# ----------------------------------------------------------------- category --

def test_every_category_the_app_uses_is_accepted():
    for category in CATEGORIES:
        assert _formatted(category=category)["category"] == category


def test_an_unknown_category_falls_back_rather_than_passing_through():
    """A typo should not create a category no filter will ever match."""
    assert _formatted(category="not_a_category")["category"] == "application"


def test_the_server_and_the_app_agree_on_the_categories():
    """A filter has to mean the same thing on both sides of the wire."""
    assert CATEGORIES == {
        "application", "error", "security", "performance", "activity",
        "infrastructure", "network", "database", "audit", "analytics",
        "background", "trace",
    }


# -------------------------------------------------------------- correlation --

def test_a_request_id_is_on_the_line_when_one_is_set():
    set_request_id("req_abc123")
    try:
        out = json.loads(JsonFormatter(environment="production").format(_record()))
        assert out["request_id"] == "req_abc123"
    finally:
        set_request_id(None)


def test_a_record_can_override_the_ambient_request_id():
    set_request_id("req_ambient")
    try:
        out = _formatted(request_id="req_explicit")
        assert out["request_id"] == "req_explicit"
    finally:
        set_request_id(None)


def test_no_request_id_is_invented_when_there_is_none():
    """A startup line has no request, and a made-up id would join nothing."""
    assert "request_id" not in _formatted()


def test_generated_ids_look_like_the_apps():
    assert new_request_id().startswith("req_")
    assert len(new_request_id()) == 16


# ----------------------------------------------------------------- exception --

def test_an_exception_records_its_type_and_not_a_traceback():
    """A traceback can carry local variable values, and a phone number in a
    traceback is a phone number in a log."""
    try:
        raise ValueError("number +919876543210 was not found")
    except ValueError:
        import sys

        record = logging.LogRecord(
            name="kavach", level=logging.ERROR, pathname="", lineno=0,
            msg="failed", args=(), exc_info=sys.exc_info(),
        )
    set_request_id(None)
    out = json.loads(JsonFormatter(environment="production").format(record))
    assert out["exception"]["type"] == "ValueError"
    assert "Traceback" not in json.dumps(out)
