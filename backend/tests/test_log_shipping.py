"""Shipping the server's logs to a monitoring sink.

The rules these tests pin are the ones that keep monitoring from becoming a
liability:

- it is **off by default**, and says so when it is on but misconfigured;
- it **never blocks a request**, because a monitoring path that can slow the thing
  it monitors is worse than no monitoring;
- it **drops rather than grows** when the sink is down;
- it **never raises into logging**, because that would surface inside whatever
  code happened to log.
"""
import json
import logging
import time
from unittest.mock import patch

import pytest

from app.config import Settings
from app.logging import JsonFormatter, LogShipHandler, attach_log_shipping


def settings(**over) -> Settings:
    base = dict(
        environment="production",
        log_ship_enabled=True,
        log_ship_url="https://sink.example/v1/logs",
        log_ship_token="a-token",
        log_ship_batch_size=3,
        log_ship_flush_seconds=0.5,
    )
    base.update(over)
    return Settings(**base)


def _record(message="request", **extra) -> logging.LogRecord:
    record = logging.LogRecord(
        name="kavach", level=logging.INFO, pathname="", lineno=0,
        msg=message, args=(), exc_info=None,
    )
    record.category = extra.pop("category", "network")
    for key, value in extra.items():
        setattr(record, key, value)
    return record


class FakeResponse:
    status = 202

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


# ------------------------------------------------------------------- defaults --

def test_shipping_is_off_unless_asked_for():
    handler = attach_log_shipping(settings(log_ship_enabled=False))
    assert handler is None


def test_shipping_with_no_url_does_nothing_and_says_so(caplog):
    """Configured to ship but given nowhere to ship to is said out loud."""
    with caplog.at_level(logging.WARNING):
        handler = attach_log_shipping(settings(log_ship_url=""))
    assert handler is None
    assert any("not configured" in r.getMessage() for r in caplog.records)


def test_shipping_with_no_token_does_nothing():
    assert attach_log_shipping(settings(log_ship_token="")) is None


def test_enabling_it_attaches_a_handler():
    root = logging.getLogger()
    before = len(root.handlers)
    handler = attach_log_shipping(settings())
    try:
        assert handler is not None
        assert len(root.handlers) == before + 1
    finally:
        root.removeHandler(handler)
        handler.close()


# ------------------------------------------------------------------ batching --

def test_records_are_batched_and_sent():
    handler = LogShipHandler("https://sink.example/v1/logs", "tok", batch_size=3, flush_seconds=0.2)
    handler.setFormatter(JsonFormatter(environment="production"))

    sent = []

    def capture(request, timeout=None):
        sent.append(json.loads(request.data))
        return FakeResponse()

    try:
        with patch("app.logging.urllib.request.urlopen", capture):
            for i in range(3):
                handler.emit(_record(f"event {i}"))
            deadline = time.monotonic() + 3
            while handler.shipped < 3 and time.monotonic() < deadline:
                time.sleep(0.05)
    finally:
        handler.close()

    assert handler.shipped == 3
    assert sent, "nothing was sent"
    assert len(sent[0]["events"]) == 3
    assert sent[0]["events"][0]["severity"] == "info"
    assert sent[0]["events"][0]["category"] == "network"


def test_the_batch_carries_the_envelope_the_sink_expects():
    handler = LogShipHandler("https://sink.example/v1/logs", "tok", batch_size=1, flush_seconds=0.1)
    handler.setFormatter(JsonFormatter(environment="production"))

    sent = []

    def capture(request, timeout=None):
        sent.append(json.loads(request.data))
        return FakeResponse()

    try:
        with patch("app.logging.urllib.request.urlopen", capture):
            handler.emit(_record(endpoint="/v1/lookup", status=200, duration_ms=41))
            deadline = time.monotonic() + 3
            while not sent and time.monotonic() < deadline:
                time.sleep(0.05)
    finally:
        handler.close()

    event = sent[0]["events"][0]
    for field in ("timestamp", "severity", "category", "message", "app_id", "environment"):
        assert field in event, f"missing {field}"
    assert event["metadata"]["duration_ms"] == 41


def test_a_server_event_never_carries_a_user_code():
    """The sink refuses anything that does, so the server must not send one."""
    handler = LogShipHandler("https://sink.example/v1/logs", "tok")
    handler.setFormatter(JsonFormatter(environment="production"))
    try:
        payload = json.loads(handler.format(_record()))
        assert "user_code" not in payload
    finally:
        handler.close()


# -------------------------------------------------------------------- failure --

def test_a_dead_sink_does_not_raise_and_is_counted():
    handler = LogShipHandler("https://sink.example/v1/logs", "tok", batch_size=1, flush_seconds=0.1)
    handler.setFormatter(JsonFormatter(environment="production"))

    def explode(request, timeout=None):
        raise OSError("sink is down")

    try:
        with patch("app.logging.urllib.request.urlopen", explode):
            handler.emit(_record())
            deadline = time.monotonic() + 3
            while handler.dropped == 0 and time.monotonic() < deadline:
                time.sleep(0.05)
    finally:
        handler.close()

    assert handler.dropped == 1
    assert handler.shipped == 0


def test_a_rejected_batch_is_counted_as_dropped():
    handler = LogShipHandler("https://sink.example/v1/logs", "tok", batch_size=1, flush_seconds=0.1)
    handler.setFormatter(JsonFormatter(environment="production"))

    class Unauthorised(FakeResponse):
        status = 401

    try:
        with patch("app.logging.urllib.request.urlopen", lambda r, timeout=None: Unauthorised()):
            handler.emit(_record())
            deadline = time.monotonic() + 3
            while handler.dropped == 0 and time.monotonic() < deadline:
                time.sleep(0.05)
    finally:
        handler.close()

    assert handler.dropped == 1


def test_emitting_never_raises_even_with_a_broken_record():
    handler = LogShipHandler("https://sink.example/v1/logs", "tok")
    try:
        # A formatter that blows up must not surface inside the caller's logging.
        class Bad(JsonFormatter):
            def format(self, record):
                raise ValueError("nope")

        handler.setFormatter(Bad(environment="production"))
        handler.emit(_record())  # must not raise
    finally:
        handler.close()


def test_the_queue_is_bounded():
    """A sink that is down must not become unbounded memory in the server."""
    handler = LogShipHandler("https://sink.example/v1/logs", "tok", batch_size=1000, flush_seconds=60)
    handler.setFormatter(JsonFormatter(environment="production"))
    try:
        assert handler._queue.maxsize == 2000
    finally:
        handler.close()
