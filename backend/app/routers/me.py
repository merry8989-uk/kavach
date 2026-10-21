"""The signed-in user's own data: profile, consent, reports, erasure.

Everything here acts on **the caller's own record**, so it requires a user token
from `/v1/auth/otp/verify` rather than the shared service token.

DPDP rights implemented in this file:
- access (`GET /v1/me`, `GET /v1/me/consent`)
- correction (`PUT /v1/me/profile`)
- erasure (`DELETE /v1/me`)
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..db import get_db
from ..hashing import phone_hash
from ..models import Number, User
from ..normalizer import is_valid_e164, to_e164
from ..schemas import (
    ConsentEventOut,
    ContributeBody,
    MeResponse,
    NumberReportsResponse,
    ProfileBody,
    ReportRequest,
    ReportResponse,
)
from ..security import rate_limit, require_token, require_user
from ..services import consent, names, spam

router = APIRouter(tags=["me"])


def _me(user: User, profile) -> MeResponse:
    return MeResponse(
        user_id=user.id,
        phone_masked=None,          # not retained server-side; see MeResponse
        display_name=profile.display_name,
        publish_name=profile.publish_name,
        allow_spam_reports=profile.allow_spam_reports,
        is_verified=user.is_verified,
        trust_score=user.trust_score,
        created_at=user.created_at,
    )


@router.get("/v1/me", response_model=MeResponse)
def read_me(
    user: User = Depends(require_user),
    db: Session = Depends(get_db),
) -> MeResponse:
    return _me(user, consent.get_profile(db, user.id))


@router.put("/v1/me/profile", response_model=MeResponse)
def update_profile(
    payload: ProfileBody,
    user: User = Depends(require_user),
    db: Session = Depends(get_db),
) -> MeResponse:
    """Set the display name and the consent to publish it.

    `publish_name` defaults to false everywhere. Turning it off withdraws the
    name immediately — the number then resolves to no name at all.
    """
    if payload.publish_name and not (payload.display_name or "").strip():
        raise HTTPException(
            status_code=422,
            detail="a display name is required before publishing",
        )

    profile = consent.set_profile(
        db=db,
        user=user,
        display_name=payload.display_name,
        publish_name=payload.publish_name,
        allow_spam_reports=payload.allow_spam_reports,
    )
    db.commit()
    return _me(user, profile)


@router.get("/v1/me/consent", response_model=list[ConsentEventOut])
def read_consent_history(
    user: User = Depends(require_user),
    db: Session = Depends(get_db),
) -> list[ConsentEventOut]:
    """The append-only audit trail of everything this user agreed to."""
    return [
        ConsentEventOut(action=e.action, detail=e.detail, created_at=e.created_at)
        for e in consent.consent_history(db, user.id)
    ]


@router.delete("/v1/me")
def delete_me(
    user: User = Depends(require_user),
    db: Session = Depends(get_db),
) -> dict:
    """Erase the account. The number stops resolving to a name."""
    consent.delete_account(db, user)
    db.commit()
    return {"deleted": True, "detail": "account erased and name withdrawn"}


@router.post("/v1/me/reports", response_model=ReportResponse, status_code=202)
def report_as_user(
    payload: ReportRequest,
    user: User = Depends(require_user),
    db: Session = Depends(get_db),
    _rl: None = Depends(rate_limit),
) -> ReportResponse:
    """Submit a spam report as a verified user (weighted by trust)."""
    e164 = to_e164(payload.number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    try:
        created, count, detail = spam.submit_report(
            db=db,
            e164=e164,
            reporter_user=user.id,
            category=payload.category,
            comment=payload.note,
            trust_score=user.trust_score,
        )
    except spam.ReportRejected as exc:
        raise HTTPException(status_code=exc.status_code, detail=exc.detail) from exc

    db.commit()
    return ReportResponse(accepted=created, reports=count, detail=detail)


@router.post("/v1/me/contributions", status_code=202)
def contribute_name(
    payload: ContributeBody,
    user: User = Depends(require_user),
    db: Session = Depends(get_db),
    _rl: None = Depends(rate_limit),
) -> dict:
    """Suggest a name for a number (crowd-sourced).

    Stored as a **community** entry and held for moderation. It is never
    published as somebody's name — only a self-declaration or a verified
    business listing can be.
    """
    e164 = to_e164(payload.number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    names.add_community_name(db, e164, payload.name, user.id)
    db.commit()
    return {
        "accepted": True,
        "status": "PENDING_REVIEW",
        "detail": (
            "Thanks — suggestions are reviewed before they can be shown, and a "
            "number's own owner is the only source that can name it."
        ),
    }


@router.get("/v1/numbers/{number}/reports", response_model=NumberReportsResponse)
def number_reports(
    number: str,
    db: Session = Depends(get_db),
    _token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
) -> NumberReportsResponse:
    """Aggregate report counts for a number. Never reveals who reported."""
    e164 = to_e164(number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    row = db.query(Number).filter(Number.phone_hash == phone_hash(e164)).one_or_none()
    if row is None:
        return NumberReportsResponse(
            count=0, distinct_reporters=0, top_categories=[],
            score=0, label="clean", last_report_at=0,
        )

    summary = spam.report_summary(db, row.id)
    from ..services.identity import _label  # single source for the band names

    return NumberReportsResponse(
        count=summary["count"],
        distinct_reporters=summary["distinct_reporters"],
        top_categories=summary["top_categories"],
        score=summary["score"],
        label=_label(summary["score"]),
        last_report_at=summary["last_report_at"],
    )
