"""Business directory search, and claiming a listing."""
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from ..db import get_db
from ..hashing import phone_hash
from ..models import Business
from ..normalizer import is_valid_e164, to_e164
from ..schemas import (
    BusinessClaimRequest,
    BusinessClaimResponse,
    BusinessResult,
    BusinessSearchResponse,
)
from ..security import actor_hash, rate_limit, require_app_token
from ..services import moderation
from ..services.names import upsert_number_by_hash

router = APIRouter(tags=["businesses"])


@router.get("/v1/businesses/search", response_model=BusinessSearchResponse)
def search(
    q: str = Query(..., min_length=1, max_length=120),
    category: str | None = Query(default=None, max_length=60),
    page: int = Query(default=1, ge=1),
    pageSize: int = Query(default=20, ge=1, le=100),
    db: Session = Depends(get_db),
    _token: str = Depends(require_app_token),
    _rl: None = Depends(rate_limit),
) -> BusinessSearchResponse:
    query = db.query(Business).filter(Business.name.ilike(f"%{q}%"))
    if category:
        query = query.filter(Business.category == category)
    total = query.count()
    rows = (
        query.order_by(Business.name)
        .offset((page - 1) * pageSize)
        .limit(pageSize)
        .all()
    )
    return BusinessSearchResponse(
        page=page,
        pageSize=pageSize,
        total=total,
        results=[
            BusinessResult(
                name=b.name,
                category=b.category,
                verified=b.verified,
                source=b.source,
            )
            for b in rows
        ],
    )


@router.post("/v1/businesses/claim", response_model=BusinessClaimResponse, status_code=202)
def claim(
    payload: BusinessClaimRequest,
    db: Session = Depends(get_db),
    token: str = Depends(require_app_token),
    _rl: None = Depends(rate_limit),
) -> BusinessClaimResponse:
    """A business says a listing is theirs.

    This endpoint exists because `Business.verified` did not have one. The column
    had been in the schema since the first migration and **nothing ever set it**,
    so the directory could show a listing as a business and no listing could ever
    become a verified one. A field that cannot become true is a field that lies.

    The claim goes to the moderation queue rather than publishing itself. That is
    the point: a business is a claim about a *person's phone number*, and the only
    thing standing between a real clinic and someone impersonating one is a person
    reading the evidence.
    """
    e164 = to_e164(payload.number)
    if not is_valid_e164(e164):
        raise HTTPException(status_code=422, detail="invalid phone number")

    number = upsert_number_by_hash(db, phone_hash(e164))

    item, created = moderation.file_business_claim(
        db,
        number_id=number.id,
        appellant_hash=actor_hash(token),
        name=payload.business_name,
        category=payload.category,
        evidence=payload.evidence,
    )
    db.commit()

    return BusinessClaimResponse(
        accepted=True,
        claim_id=item.id,
        detail=(
            "Claim received. A person reviews the evidence before the listing is "
            "marked verified — nothing changes until then."
            if created
            else "You already have an open claim for this number. It was updated."
        ),
    )
