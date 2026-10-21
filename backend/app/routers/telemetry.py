"""Telemetry intake and the Notion push.

`POST /v1/telemetry/events` is how a hundred installs all reach one dashboard:
each app sends its schema events here, the server validates and stores them, and
a separate push drains them into the operator's Notion database.

Invalid events are counted and skipped rather than failing the batch — one bad
build must not block every other phone.
"""
import json
import time

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from .. import notion_sink, telemetry_schema
from ..config import Settings, get_settings
from ..db import get_db
from ..models import TelemetryEvent
from ..schemas import NotionPushResult, TelemetryAck, TelemetryBatch
from ..security import rate_limit, require_token

router = APIRouter(tags=["telemetry"])

MAX_REASONS = 5


@router.post("/v1/telemetry/events", response_model=TelemetryAck, status_code=202)
def ingest(
    payload: TelemetryBatch,
    db: Session = Depends(get_db),
    _token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
) -> TelemetryAck:
    accepted = 0
    rejected = 0
    reasons: list[str] = []
    now = int(time.time())

    for raw in payload.events:
        problems = telemetry_schema.validate(raw)
        if problems:
            rejected += 1
            if len(reasons) < MAX_REASONS:
                reasons.append("; ".join(problems)[:200])
            continue

        properties = {
            key: str(value)
            for key, value in raw.items()
            if key not in telemetry_schema.ENVELOPE_REQUIRED
            and key not in telemetry_schema.ENVELOPE_OPTIONAL
        }
        db.add(
            TelemetryEvent(
                received_at=now,
                user_code=payload.user_code[:16],
                event_name=str(raw.get("event_name"))[:64],
                schema_version=str(raw.get("schema_version"))[:16],
                event_timestamp=str(raw.get("timestamp"))[:32],
                session_id=str(raw.get("session_id"))[:64],
                attempt_id=(str(raw["attempt_id"])[:64] if raw.get("attempt_id") else None),
                level=str(raw.get("log_level"))[:8],
                app_version=str(raw.get("app_version"))[:32],
                os=str(raw.get("os"))[:64],
                device_model=str(raw.get("device_model"))[:64],
                platform=str(raw.get("platform"))[:16],
                network_type=(str(raw["network_type"])[:16] if raw.get("network_type") else None),
                screen_or_route=str(raw.get("screen_or_route"))[:64],
                release_channel=(
                    str(raw["release_channel"])[:16] if raw.get("release_channel") else None
                ),
                properties_json=json.dumps(properties)[:4000],
            ),
        )
        accepted += 1

    db.commit()
    return TelemetryAck(accepted=accepted, rejected=rejected, reasons=reasons)


@router.post("/v1/admin/notion/push", response_model=NotionPushResult)
def push_to_notion(
    limit: int = Query(default=40, ge=1, le=200),
    db: Session = Depends(get_db),
    _token: str = Depends(require_token),
    settings: Settings = Depends(get_settings),
) -> NotionPushResult:
    pushed, failed, remaining, detail = notion_sink.push_pending(db, limit=limit)
    return NotionPushResult(pushed=pushed, failed=failed, remaining=remaining, detail=detail)


@router.post("/v1/admin/telemetry/purge")
def purge(
    keep_days: int = Query(default=30, ge=1, le=365),
    db: Session = Depends(get_db),
    _token: str = Depends(require_token),
) -> dict:
    return {"deleted": notion_sink.purge_old(db, keep_days=keep_days)}
