"""Privacy disclosure, export and deletion."""
import time

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import AuditLog, ConsentRecord, Correction, Report, User
from ..schemas import DeletionResponse, PrivacyResponse
from ..security import require_app_token, require_user

router = APIRouter(tags=["account"])

DATA_FLOWS = [
    "Caller lookup sends a phone number to the server to resolve an identity.",
    "Spam reports send a number and a category, attributed to a hashed actor id — never a raw identity.",
    "Contacts, SMS bodies and recordings are never uploaded by the client.",
    "Call audio is uploaded only for transcription, only when the user has enabled cloud AI, and is capped in size.",
]


@router.get("/v1/account/privacy", response_model=PrivacyResponse)
def privacy(_install: str = Depends(require_app_token)) -> PrivacyResponse:
    return PrivacyResponse(
        dataFlows=DATA_FLOWS,
        retention="Reports are retained for 24 months, then aggregated and anonymised.",
        exportsAvailable=True,
        deletionAvailable=True,
    )


@router.delete("/v1/account", response_model=DeletionResponse)
def delete_account(
    db: Session = Depends(get_db),
    user: User = Depends(require_user),
) -> DeletionResponse:
    """Delete everything attributable to the signed-in user.

    Scoped to the **user**, not to the app. Before this, the endpoint used the
    shared service token as the actor — and because that token was identical on
    every device, `actor_hash(token)` was one value for everybody. A single
    user's deletion request therefore deleted **every app user's reports at
    once**. That is the kind of bug that is invisible until someone checks, and
    it is why the app token and the user token are now separate things.

    What is removed: the profile, the published name, consent records, the user's
    own corrections, and reports attributed to the user's own identity.

    What is not: reports attributed to an *install* rather than an account. Those
    are deliberately anonymous — tying one back to a person at deletion time
    would create exactly the linkage the design avoids, and the user never had a
    way to see them as theirs.
    """
    # Scoped to the signed-in user, not to the app. The whole point of separating
    # the two tokens is that the app token proves *what* is calling and the user
    # token proves *who*.
    actor = user.phone_hash

    # Order matters, and it used to be wrong. The corrections lookup was a
    # subquery over the reporter's own reports — but the reports were deleted on
    # the line above, so the subquery always matched nothing and **no correction
    # was ever deleted**. It was also the wrong attribution: a correction is
    # submitted by someone, not owned by whoever reported the number.
    #
    # Corrections are now attributable (`Correction.actor_hash`) and are removed
    # by that, independently of the reports.
    corrections = db.query(Correction).filter(Correction.actor_hash == actor).delete(
        synchronize_session=False,
    )
    reports = db.query(Report).filter(Report.reporter_hash == actor).delete()
    consents = db.query(ConsentRecord).filter(ConsentRecord.actor_hash == actor).delete()
    db.add(AuditLog(actor_hash=actor, action="account_deleted", subject=None,
                    created_at=int(time.time() * 1000)))
    db.flush()
    return DeletionResponse(
        deleted=True,
        detail=f"removed {reports} report(s), {corrections} correction(s), {consents} consent record(s)",
    )
