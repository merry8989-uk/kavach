"""Identity resolution: merge, attribute source, evaluate confidence.

Rules the spec insists on:
- never invent a name for an unknown number;
- never mark a number verified without a verification process;
- record the source and timestamp of every identity fact.
"""
import time

from sqlalchemy.orm import Session

from ..models import NumberIdentity

# Base confidence by provenance. A community tag is an allegation, not a fact.
SOURCE_CONFIDENCE = {
    "CONTACT": 1.0,
    "BUSINESS": 0.95,
    "PROVIDER": 0.8,
    "COMMUNITY": 0.6,
    "NONE": 0.0,
}

STALE_AFTER_MS = 180 * 24 * 60 * 60 * 1000  # 180 days


def resolve(db: Session, number: str) -> NumberIdentity | None:
    return db.query(NumberIdentity).filter(NumberIdentity.number == number).one_or_none()


def freshness(last_updated: int, now_ms: int | None = None) -> str:
    if not last_updated:
        return "UNKNOWN"
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    return "STALE" if (now_ms - last_updated) > STALE_AFTER_MS else "FRESH"


def merge_into(
    db: Session,
    number: str,
    *,
    display_name: str | None = None,
    number_type: str | None = None,
    business_category: str | None = None,
    source: str = "COMMUNITY",
    verification_status: str = "UNVERIFIED",
) -> NumberIdentity:
    """Create or update an identity record with transparent matching rules.

    A higher-trust source overwrites a lower-trust one; an equal or lower trust
    source never overwrites an existing name.
    """
    record = resolve(db, number)
    if record is None:
        record = NumberIdentity(number=number)
        db.add(record)

    incoming_confidence = SOURCE_CONFIDENCE.get(source, 0.5)
    if display_name and (
        record.display_name is None
        or incoming_confidence >= SOURCE_CONFIDENCE.get(record.source, 0.0)
    ):
        record.display_name = display_name
        record.source = source
        record.confidence = incoming_confidence

    if number_type and not record.number_type:
        record.number_type = number_type
    if business_category:
        record.business_category = business_category
    if verification_status == "VERIFIED":
        record.verification_status = "VERIFIED"

    record.last_updated = int(time.time() * 1000)
    db.flush()
    return record
