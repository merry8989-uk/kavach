"""Structured logging for the server.

The app has had a logging standard since v0.18.0 and the server has had nothing:
`print` and uvicorn's default text, which cannot be filtered, counted or joined
to anything. That is half a logging story, and the taxonomy this was built
against asks for twelve categories — most of which only exist server-side
(infrastructure, network, database, audit, background work).

So the server emits **the same envelope the app does**:

    timestamp · severity · category · message · app_id · environment
    · request_id · sanitized metadata

`request_id` is the field that makes the two halves one story. A client sends
`X-Request-Id` (the app's `LogEvent.requestId`), the server reuses it if present
and mints one if not, every log line inside that request carries it, and it comes
back in the response header. One user action can then be followed from the tap
that caused it to the query that answered it.

Nothing sensitive is ever formatted in: headers are allow-listed rather than
blocked, because a deny-list of header names is a list somebody has to remember
to update.
"""
from __future__ import annotations

import json
import logging
import time
import uuid
from contextvars import ContextVar
from typing import Any

from .config import Settings, get_settings

# ---------------------------------------------------------------- correlation --

_request_id: ContextVar[str | None] = ContextVar("kavach_request_id", default=None)

# Header names worth having in a log. An allow-list, not a deny-list: the set of
# headers that might carry a secret is unbounded, and the set worth logging is
# not. `authorization` is deliberately absent.
LOGGED_HEADERS = ("x-request-id", "user-agent", "content-type", "accept")

APP_ID = "kavach-server"

# The same twelve categories the app uses, so a filter means the same thing on
# both sides of the wire.
CATEGORIES = {
    "application",
    "error",
    "security",
    "performance",
    "activity",
    "infrastructure",
    "network",
    "database",
    "audit",
    "analytics",
    "background",
    "trace",
}


def current_request_id() -> str | None:
    return _request_id.get()


def set_request_id(value: str | None) -> None:
    _request_id.set(value)


def new_request_id() -> str:
    return "req_" + uuid.uuid4().hex[:12]


# ------------------------------------------------------------------- records --

# Attributes `logging` puts on every record. Anything else in `record.__dict__`
# was passed as `extra=` and belongs in the metadata block.
_RESERVED = {
    "name", "msg", "args", "levelname", "levelno", "pathname", "filename",
    "module", "exc_info", "exc_text", "stack_info", "lineno", "funcName",
    "created", "msecs", "relativeCreated", "thread", "threadName",
    "processName", "process", "taskName", "message", "asctime",
}


class JsonFormatter(logging.Formatter):
    """One JSON object per line, in the envelope the app already uses."""

    def __init__(self, environment: str) -> None:
        super().__init__()
        self.environment = environment

    def format(self, record: logging.LogRecord) -> str:
        # The six levels the taxonomy asks for. Python has a fifth, CRITICAL,
        # which maps onto FATAL rather than inventing a seventh name for it.
        severity = {
            "CRITICAL": "fatal",
            "ERROR": "error",
            "WARNING": "warn",
            "INFO": "info",
            "DEBUG": "debug",
            "NOTSET": "trace",
        }.get(record.levelname, "info")

        category = getattr(record, "category", "application")
        if category not in CATEGORIES:
            # A typo should be visible, not silently accepted as a category that
            # no filter will ever match.
            category = "application"

        out: dict[str, Any] = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(record.created)),
            "severity": severity,
            "category": category,
            "message": record.getMessage(),
            "app_id": APP_ID,
            "environment": self.environment,
            "logger": record.name,
        }

        request_id = getattr(record, "request_id", None) or current_request_id()
        if request_id:
            out["request_id"] = request_id

        metadata = {
            key: value
            for key, value in record.__dict__.items()
            if key not in _RESERVED and key not in ("category", "request_id")
        }
        if metadata:
            out["metadata"] = metadata

        if record.exc_info:
            # The type and message only. A traceback can carry local variable
            # values, and a phone number in a traceback is a phone number in a log.
            exc_type, exc_value = record.exc_info[0], record.exc_info[1]
            out["exception"] = {
                "type": getattr(exc_type, "__name__", str(exc_type)),
                "detail": str(exc_value)[:200],
            }

        return json.dumps(out, separators=(",", ":"), default=str)


def configure_logging(settings: Settings | None = None) -> None:
    """Send everything through the JSON formatter, once."""
    settings = settings or get_settings()
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter(environment=settings.environment))

    root = logging.getLogger()
    # Replace rather than add, so a second call cannot double every line.
    root.handlers = [handler]
    root.setLevel(logging.DEBUG if not settings.is_production else logging.INFO)

    # uvicorn's own loggers propagate to root once they are not configured
    # separately, so its access lines arrive in the same shape as ours.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(name)
        logger.handlers = []
        logger.propagate = True


def log(category: str, message: str, level: str = "info", **metadata: Any) -> None:
    """Write one structured line.

    `category` is the kind of log, `level` is how severe — the two are separate
    on purpose, because a failed login is an error *and* a security event.
    """
    logger = logging.getLogger("kavach")
    logger.log(
        {
            "trace": logging.DEBUG,
            "debug": logging.DEBUG,
            "info": logging.INFO,
            "warn": logging.WARNING,
            "error": logging.ERROR,
            "fatal": logging.CRITICAL,
        }.get(level, logging.INFO),
        message,
        extra={"category": category, **metadata},
    )


# ----------------------------------------------------------------- middleware --

async def request_logging_middleware(request, call_next):
    """One `network` line per request, plus the correlation id on the way out.

    Three things this does that a plain access log does not:

    - **Reuses the client's id** when it sends one, so an action the app logged
      and the request it caused carry the same `request_id`. That is what makes
      the app's log and the server's log one story instead of two.
    - **Records latency**, which is the performance category and the number that
      actually explains a slow app.
    - **Classifies by outcome**: a 401 or 403 is a `security` event, a 5xx is an
      `error`, everything else is `network`. A flat access log makes those look
      identical.
    """
    incoming = request.headers.get("x-request-id") or request.headers.get("X-Request-Id")
    request_id = incoming or new_request_id()
    set_request_id(request_id)

    started = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception as exc:  # noqa: BLE001 — logged, then re-raised
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        log(
            "error",
            "request failed",
            level="error",
            method=request.method,
            endpoint=_route_of(request),
            duration_ms=elapsed_ms,
            exception=type(exc).__name__,
        )
        set_request_id(None)
        raise

    elapsed_ms = int((time.perf_counter() - started) * 1000)

    if response.status_code in (401, 403):
        log(
            "security",
            "request refused",
            level="warn",
            method=request.method,
            endpoint=_route_of(request),
            status=response.status_code,
            duration_ms=elapsed_ms,
        )
    elif response.status_code >= 500:
        log(
            "error",
            "request failed",
            level="error",
            method=request.method,
            endpoint=_route_of(request),
            status=response.status_code,
            duration_ms=elapsed_ms,
        )
    else:
        log(
            "network",
            "request",
            method=request.method,
            endpoint=_route_of(request),
            status=response.status_code,
            duration_ms=elapsed_ms,
            headers=_safe_headers(request),
        )

    response.headers["X-Request-Id"] = request_id
    set_request_id(None)
    return response


def _route_of(request) -> str:
    """The route *pattern*, not the filled-in path.

    `/v1/lookup` groups; `/v1/lookup?number=%2B9198...` is one row per number,
    which is both a privacy problem and a useless aggregate.
    """
    route = request.scope.get("route")
    return getattr(route, "path", request.url.path)


def _safe_headers(request) -> dict[str, str]:
    """Only the headers on the allow-list, and only if present."""
    return {
        name: value[:120]
        for name in LOGGED_HEADERS
        if (value := request.headers.get(name))
    }
