"""Server-side Notion sink.

Why this exists: the app can push straight to a user's own Notion database, but
that only works for someone who is willing to create a Notion integration and
paste a token. At a hundred installs that is nobody.

So the real path is: every phone sends its events to **this** server, and the
server writes them into the operator's Notion database using a token that lives
only in the server environment. Users never see a token; the operator owns the
dashboard.

The token is read from `NOTION_TOKEN` and the destination from
`NOTION_DATA_SOURCE_ID`. With either missing the sink is a no-op that says so,
rather than failing silently.
"""
import json
import time

import httpx
from sqlalchemy.orm import Session

from .config import get_settings
from .models import TelemetryEvent

NOTION_VERSION = "2025-09-03"
NOTION_PAGES_URL = "https://api.notion.com/v1/pages"

# Notion's API creates one page per request and rate-limits; keep each run modest.
DEFAULT_BATCH = 40


def is_configured() -> bool:
    settings = get_settings()
    return bool(getattr(settings, "notion_token", "")) and bool(
        getattr(settings, "notion_data_source_id", ""),
    )


def _row_for(event: TelemetryEvent) -> dict:
    """Map a stored event onto the Notion database's properties."""
    try:
        properties = json.loads(event.properties_json or "{}")
    except json.JSONDecodeError:
        properties = {}

    details = "  ".join(f"{k}={v}" for k, v in properties.items())
    title = event.event_name
    if details:
        title = f"{event.event_name} · {details[:120]}"

    return {
        "parent": {"type": "data_source_id", "data_source_id": get_settings().notion_data_source_id},
        "properties": {
            "Log": {"title": [{"text": {"content": title[:1900]}}]},
            "Timestamp": {"date": {"start": event.event_timestamp}},
            "Event Name": {"select": {"name": event.event_name}},
            "Level": {"select": {"name": event.level}},
            "Platform": {"select": {"name": event.platform or "android"}},
            "Release": {"select": {"name": event.release_channel or "production"}},
            "Device": {"select": {"name": "mobile"}},
            "App Version": {"rich_text": [{"text": {"content": event.app_version[:1900]}}]},
            "OS": {"rich_text": [{"text": {"content": event.os[:1900]}}]},
            "Network": {"rich_text": [{"text": {"content": (event.network_type or "unknown")[:1900]}}]},
            "Route": {"rich_text": [{"text": {"content": event.screen_or_route[:1900]}}]},
            "Session": {"rich_text": [{"text": {"content": event.session_id[:1900]}}]},
            "Attempt": {"rich_text": [{"text": {"content": (event.attempt_id or "")[:1900]}}]},
            "User Code": {"rich_text": [{"text": {"content": event.user_code[:1900]}}]},
            "Details": {"rich_text": [{"text": {"content": details[:1900]}}]},
            "Schema": {"rich_text": [{"text": {"content": event.schema_version[:1900]}}]},
        },
    }


def push_pending(db: Session, limit: int = DEFAULT_BATCH) -> tuple[int, int, int, str | None]:
    """Push unsent rows. Returns (pushed, failed, remaining, detail)."""
    settings = get_settings()
    if not is_configured():
        remaining = db.query(TelemetryEvent).filter(TelemetryEvent.pushed_to_notion.is_(False)).count()
        return 0, 0, remaining, "Notion is not configured on this server."

    pending = (
        db.query(TelemetryEvent)
        .filter(TelemetryEvent.pushed_to_notion.is_(False))
        .order_by(TelemetryEvent.received_at.asc())
        .limit(limit)
        .all()
    )
    if not pending:
        return 0, 0, 0, None

    pushed = failed = 0
    detail: str | None = None

    with httpx.Client(timeout=20.0) as client:
        for event in pending:
            try:
                response = client.post(
                    NOTION_PAGES_URL,
                    headers={
                        "Authorization": f"Bearer {settings.notion_token}",
                        "Notion-Version": NOTION_VERSION,
                        "Content-Type": "application/json",
                    },
                    json=_row_for(event),
                )
            except httpx.HTTPError as exc:
                detail = f"{exc.__class__.__name__}"
                break

            if response.is_success:
                event.pushed_to_notion = True
                event.notion_error = None
                pushed += 1
            elif 400 <= response.status_code < 500:
                # The row is wrong; retrying will not fix it. Mark it so the
                # queue can drain instead of jamming on one bad event.
                event.pushed_to_notion = True
                event.notion_error = f"rejected {response.status_code}"
                failed += 1
            else:
                event.notion_error = f"server {response.status_code}"
                detail = f"Notion returned {response.status_code}"
                break

    db.commit()
    remaining = db.query(TelemetryEvent).filter(TelemetryEvent.pushed_to_notion.is_(False)).count()
    return pushed, failed, remaining, detail


def purge_old(db: Session, keep_days: int = 30) -> int:
    """Enforce the retention policy on stored telemetry."""
    cutoff = int(time.time()) - keep_days * 24 * 60 * 60
    deleted = (
        db.query(TelemetryEvent)
        .filter(TelemetryEvent.received_at < cutoff)
        .delete(synchronize_session=False)
    )
    db.commit()
    return int(deleted or 0)
