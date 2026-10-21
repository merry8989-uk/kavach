"""Privacy disclosure, export and deletion."""
import time

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import AuditLog, ConsentRecord, Correction, Report
from ..schemas import DeletionResponse, PrivacyResponse
from ..security import actor_hash, require_token

router = APIRouter(tags=["account"])

DATA_FLOWS = [
    "Caller lookup sends a phone number to the server to resolve an identity.",
    "Spam reports send a number and a category, attributed to a hashed actor id — never a raw identity.",
    "Contacts, SMS bodies and recordings are never uploaded by the client.",
    "Call audio is uploaded only for transcription, only when the user has enabled cloud AI, and is capped in size.",
]


@router.get("/v1/account/privacy", response_model=PrivacyResponse)
def privacy(_token: str = Depends(require_token)) -> PrivacyResponse:
    return PrivacyResponse(
        dataFlows=DATA_FLOWS,
        retention="Reports are retained for 24 months, then aggregated and anonymised.",
        exportsAvailable=True,
        deletionAvailable=True,
    )


@router.delete("/v1/account", response_model=DeletionResponse)
def delete_account(
    db: Session = Depends(get_db),
    token: str = Depends(require_token),
) -> DeletionResponse:
    """Delete everything attributable to the caller.

    Numbers other users reported are not deleted (they are not this actor's
    data), but this actor's reports, corrections and consent records are.
    """
    actor = actor_hash(token)
    reports = db.query(Report).filter(Report.reporter_hash == actor).delete()
    corrections = db.query(Correction).filter(Correction.number.in_(
        db.query(Report.number).filter(Report.reporter_hash == actor)
    )).delete(synchronize_session=False)
    consents = db.query(ConsentRecord).filter(ConsentRecord.actor_hash == actor).delete()
    db.add(AuditLog(actor_hash=actor, action="account_deleted", subject=None,
                    created_at=int(time.time() * 1000)))
    db.flush()
    return DeletionResponse(
        deleted=True,
        detail=f"removed {reports} report(s), {corrections} correction(s), {consents} consent record(s)",
    )
