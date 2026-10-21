"""Business directory search."""
from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Business
from ..schemas import BusinessResult, BusinessSearchResponse
from ..security import rate_limit, require_token

router = APIRouter(tags=["businesses"])


@router.get("/v1/businesses/search", response_model=BusinessSearchResponse)
def search(
    q: str = Query(..., min_length=1, max_length=120),
    category: str | None = Query(default=None, max_length=60),
    page: int = Query(default=1, ge=1),
    pageSize: int = Query(default=20, ge=1, le=100),
    db: Session = Depends(get_db),
    _token: str = Depends(require_token),
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
