"""Appeals, and the moderation queue that reads them.

Two audiences in one router, deliberately:

- `POST /v1/appeals` is the **app**. Any install can say "this number is not
  spam". It needs the app token and is rate-limited.
- `GET /v1/admin/moderation` and `POST /v1/admin/moderation/{id}` are the
  **operator**. They need the service token, which is not the same thing and is
  not in the APK.

Before this, `moderation_queue` had a writer and no reader. Burst reporting was
detected and thrown away, and there was no way for anyone to say a number had
been labelled wrongly. The decision endpoint is the part that makes the queue
mean something: approving an appeal clears the number's score, so the next lookup
returns a different answer rather than a different status.
"""
import time

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from ..db import get_db
from ..logging import log
from ..schemas import (
    AppealRequest,
    AppealResponse,
    ModerationDecisionBody,
    ModerationDecisionResponse,
    ModerationItemView,
    ModerationQueueResponse,
)
from ..security import actor_hash, bearer_token, rate_limit, require_admin_token, require_app_token
from ..services import moderation
from ..services.names import upsert_number_by_hash
from ..hashing import phone_hash
from ..normalizer import is_valid_e164, to_e164

router = APIRouter(tags=["appeals"])


@router.post("/v1/appeals", response_model=AppealResponse, status_code=202)
def create_appeal(
    payload: AppealRequest,
    db: Session = Depends(get_db),
    install: str = Depends(require_app_token),
    _rl: None = Depends(rate_limit),
) -> AppealResponse:
    """File an appeal. The app can do this; it cannot decide one."""
    e164 = to_e164(payload.number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    number = upsert_number_by_hash(db, phone_hash(e164))

    item, created = moderation.file_appeal(
        db,
        number_id=number.id,
        appellant_hash=actor_hash(install),
        detail=(payload.statement or "").strip()[:1000] or None,
    )
    db.commit()

    return AppealResponse(
        accepted=True,
        appeal_id=item.id,
        detail=(
            "Appeal recorded. Nothing changes immediately — a person reviews it, "
            "and an approved appeal clears the number's score."
            if created
            else "You already have an open appeal for this number. Your note was updated."
        ),
    )


# ------------------------------------------------------------------ operator --

def _require_operator(token: str = Depends(bearer_token)) -> str:
    """The service token. This is what the APK must never contain."""
    return token


@router.get("/v1/admin/moderation", response_model=ModerationQueueResponse)
def list_moderation(
    db: Session = Depends(get_db),
    _operator: str = Depends(require_admin_token),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
) -> ModerationQueueResponse:
    """The pending queue, oldest first — the order it should be worked in.

    A wrong label left standing for a week is worse than one left for an hour, so
    the ordering is the point rather than a detail.
    """
    from ..models import Number

    items = moderation.pending(db, limit=limit, offset=offset)
    now = int(time.time())

    views = []
    for item in items:
        number = None
        if item.number_id is not None:
            row = db.query(Number).filter(Number.id == item.number_id).one_or_none()
            # The raw number is not stored, only its hash — so the queue shows a
            # short digest rather than pretending it can show the number.
            number = row.phone_hash[:12] if row else None
        views.append(
            ModerationItemView(
                id=item.id,
                number=number,
                reason=item.reason,
                detail=item.detail,
                claim_name=item.claim_name,
                claim_category=item.claim_category,
                age_seconds=max(0, now - (item.created_at or now)),
                created_at=item.created_at or 0,
            )
        )

    return ModerationQueueResponse(items=views, pending_total=moderation.pending_count(db))


@router.post(
    "/v1/admin/moderation/{item_id}",
    response_model=ModerationDecisionResponse,
)
def decide_moderation(
    item_id: int,
    payload: ModerationDecisionBody,
    db: Session = Depends(get_db),
    _operator: str = Depends(require_admin_token),
) -> ModerationDecisionResponse:
    """Resolve an item, and make the resolution mean something.

    Approving an appeal zeroes the number's score and counts, so the next lookup
    returns a different answer. Without that step the queue would record that a
    mistake was acknowledged and leave the mistake in place.
    """
    from ..models import ModerationItem

    item = db.query(ModerationItem).filter(ModerationItem.id == item_id).one_or_none()
    if item is None:
        raise HTTPException(status_code=404, detail="no such moderation item")
    if item.status != "pending":
        raise HTTPException(status_code=409, detail=f"already {item.status}")

    try:
        moderation.decide(db, item, payload.decision, payload.note)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    db.commit()

    # The audit category: who changed what, when, and on which resource. The
    # database has an AuditLog table for identity writes; this is the same
    # question asked of the log stream, so an operator can see it without a
    # query.
    log(
        "audit",
        "moderation decision",
        actor="operator",
        action=payload.decision,
        resource="moderation_item",
        resource_id=item.id,
        number_id=item.number_id,
        note=(payload.note or "")[:120],
    )

    detail = (
        "Approved. The number's score and counts were cleared."
        if payload.decision == "approved"
        else "Rejected. The number keeps its score."
    )
    return ModerationDecisionResponse(id=item.id, status=item.status, detail=detail)
