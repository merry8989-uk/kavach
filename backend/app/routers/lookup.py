"""Caller lookup and number reputation."""
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..db import get_db
from ..models import NumberIdentity
from ..normalizer import is_valid_e164, to_e164
from ..scoring import ReputationInput, compute_reputation, detect_patterns
from ..schemas import LookupResponse, ReputationResponse
from ..security import rate_limit, require_token
from ..services.identity import freshness, resolve

router = APIRouter(tags=["caller-id"])


@router.get("/v1/lookup", response_model=LookupResponse)
def lookup(
    number: str = Query(..., min_length=3, max_length=24),
    db: Session = Depends(get_db),
    _token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
    settings: Settings = Depends(get_settings),
) -> LookupResponse:
    e164 = to_e164(number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    record = resolve(db, e164)
    if record is None or not record.display_name:
        # Explicit no-result. We never invent a name for an unknown number.
        return LookupResponse(
            number=e164,
            displayName=None,
            source="NONE",
            confidence=0.0,
            verificationStatus="UNVERIFIED",
            spamRisk=0,
            reports=0,
            lastUpdated=0,
            freshness="UNKNOWN",
            unavailableReason="NO_RELIABLE_SOURCE",
        )

    return LookupResponse(
        number=record.number,
        displayName=record.display_name,
        numberType=record.number_type,
        businessCategory=record.business_category,
        source=record.source,
        confidence=record.confidence,
        verificationStatus=record.verification_status,
        spamRisk=record.spam_risk,
        reports=record.report_count,
        lastUpdated=record.last_updated,
        freshness=freshness(record.last_updated),
    )


@router.get("/v1/number-reputation", response_model=ReputationResponse)
def reputation(
    number: str = Query(..., min_length=3, max_length=24),
    db: Session = Depends(get_db),
    _token: str = Depends(require_token),
    _rl: None = Depends(rate_limit),
    settings: Settings = Depends(get_settings),
) -> ReputationResponse:
    e164 = to_e164(number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    record = resolve(db, e164)
    verified_business = bool(record and record.business_category and record.verification_status == "VERIFIED")
    result = compute_reputation(
        ReputationInput(
            distinct_reporters=record.distinct_reporters if record else 0,
            total_reports=record.report_count if record else 0,
            recent_reports=record.report_count if record else 0,
            verified_business=verified_business,
            pattern_flags=detect_patterns(e164),
        ),
        rule_version=settings.scoring_rule_version,
    )
    return ReputationResponse(
        number=e164,
        score=result.score,
        bands=result.bands,
        explain=result.explain,
        ruleVersion=result.rule_version,
    )
