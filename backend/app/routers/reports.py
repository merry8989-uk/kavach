"""Spam reports and identity corrections."""
import time

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import AuditLog, Correction, NumberIdentity, Report
from ..normalizer import is_valid_e164, to_e164
from ..schemas import CorrectionRequest, CorrectionResponse, ReportRequest, ReportResponse
from ..scoring import ReputationInput, compute_reputation, detect_patterns
from ..security import actor_hash, rate_limit, require_token
from ..services.identity import resolve

router = APIRouter(tags=["reports"])

# One report per reporter per number per window. Prevents a single user from
# dominating a number's reputation.
DEDUPE_WINDOW_MS = 30 * 24 * 60 * 60 * 1000


@router.post("/v1/reports", response_model=ReportResponse, status_code=202)
def create_report(
    payload: ReportRequest,
    db: Session = Depends(get_db),
    token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
) -> ReportResponse:
    e164 = to_e164(payload.number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    reporter = actor_hash(token)
    now = int(time.time() * 1000)

    duplicate = (
        db.query(Report)
        .filter(
            Report.number == e164,
            Report.reporter_hash == reporter,
            Report.created_at > now - DEDUPE_WINDOW_MS,
        )
        .first()
    )
    if duplicate is not None:
        record = resolve(db, e164)
        return ReportResponse(
            accepted=True,
            reports=record.reports if record else 0,
            detail="already reported within the deduplication window",
        )

    db.add(
        Report(
            number=e164,
            reporter_hash=reporter,
            category=payload.category,
            note=payload.note,
            created_at=now,
        )
    )
    db.flush()

    total = db.query(Report).filter(Report.number == e164).count()
    distinct = (
        db.query(Report.reporter_hash).filter(Report.number == e164).distinct().count()
    )

    record = resolve(db, e164)
    if record is None:
        record = NumberIdentity(number=e164)
        db.add(record)

    record.report_count = total
    record.distinct_reporters = distinct
    record.spam_risk = compute_reputation(
        ReputationInput(
            distinct_reporters=distinct,
            total_reports=total,
            recent_reports=total,
            pattern_flags=detect_patterns(e164),
        )
    ).score
    record.last_updated = now

    db.add(AuditLog(actor_hash=reporter, action="report", subject=e164, created_at=now))
    db.flush()

    return ReportResponse(accepted=True, reports=total)


@router.post("/v1/identity-corrections", response_model=CorrectionResponse, status_code=202)
def create_correction(
    payload: CorrectionRequest,
    db: Session = Depends(get_db),
    token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
) -> CorrectionResponse:
    e164 = to_e164(payload.number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    now = int(time.time() * 1000)
    actor = actor_hash(token)
    db.add(
        Correction(
            number=e164,
            suggested_name=payload.suggestedName,
            evidence=payload.evidence,
            status="PENDING",
            created_at=now,
            actor_hash=actor,
        )
    )
    db.add(
        AuditLog(
            actor_hash=actor,
            action="correction",
            subject=e164,
            created_at=now,
        )
    )
    db.flush()
    # Corrections are moderated; they never auto-apply to the identity record.
    return CorrectionResponse(accepted=True, status="PENDING")
