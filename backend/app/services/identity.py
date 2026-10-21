"""Identity resolution across both stores.

Order of resolution:

1. **The consent store** (`numbers` + `name_entries` + `spam_scores`). This is
   the source of truth. A name is returned only when `services.names` says it is
   publishable — self-declared or verified business.
2. **The legacy projection** (`number_identities`), which older rows and the
   seeded tests still use. Kept so the public contract does not break.

The raw number is never written by this module: it is hashed on the way in and
only the hash reaches the consent tables.
"""
import time
from dataclasses import dataclass, field

from sqlalchemy.orm import Session

from ..hashing import phone_hash
from ..models import Number, NumberIdentity, SpamScore
from . import names

STALE_AFTER_SECONDS = 180 * 24 * 60 * 60


@dataclass
class ResolvedIdentity:
    """The public answer about a number. Safe to serialise."""

    display_name: str | None = None
    source: str = "NONE"
    # The plan's provenance vocabulary: self_declared | business | contact |
    # provider | community | None. Only the first two are ever produced by the
    # consent store — see services.names.best_publishable_name.
    name_source: str | None = None
    confidence: float = 0.0
    verification_status: str = "UNVERIFIED"
    number_type: str | None = None
    business_category: str | None = None
    carrier: str | None = None
    telecom_circle: str | None = None
    spam_risk: int = 0
    reports: int = 0
    distinct_reporters: int = 0
    spam_label: str = "clean"
    last_updated_ms: int = 0
    unavailable_reason: str | None = None
    explain: list[str] = field(default_factory=list)


def resolve(db: Session, e164: str) -> ResolvedIdentity | None:
    """Everything we lawfully know about a number, or None if we know nothing."""
    number = db.query(Number).filter(Number.phone_hash == phone_hash(e164)).one_or_none()
    if number is not None:
        return _from_consent_store(db, number)

    # Fall back to the legacy projection so seeded and pre-existing rows still
    # resolve. New writes never go here.
    legacy = db.query(NumberIdentity).filter(NumberIdentity.number == e164).one_or_none()
    if legacy is None:
        return None
    return ResolvedIdentity(
        display_name=legacy.display_name,
        source=legacy.source,
        name_source=_legacy_source_tag(legacy.source) if legacy.display_name else None,
        confidence=legacy.confidence,
        verification_status=legacy.verification_status,
        number_type=legacy.number_type,
        business_category=legacy.business_category,
        spam_risk=legacy.spam_risk,
        reports=legacy.report_count,
        distinct_reporters=legacy.distinct_reporters,
        spam_label=_label(legacy.spam_risk),
        last_updated_ms=legacy.last_updated,
    )


def _from_consent_store(db: Session, number: Number) -> ResolvedIdentity:
    publishable = names.best_publishable_name(db, number.id)
    score = db.query(SpamScore).filter(SpamScore.number_id == number.id).one_or_none()

    if publishable is not None:
        name, source, confidence = publishable
        # `source` is already the plan's vocabulary (self_declared | business).
        name_source = source
        source_tag = "BUSINESS" if source == "business" else "CONTACT"
        verification = "VERIFIED"
    else:
        name, name_source, source_tag, confidence, verification = None, None, "NONE", 0.0, "UNVERIFIED"

    risk = score.score if score else 0
    return ResolvedIdentity(
        display_name=name,
        source=source_tag,
        name_source=name_source,
        confidence=confidence / 100.0,
        verification_status=verification,
        carrier=number.carrier,
        telecom_circle=number.telecom_circle,
        number_type=number.line_type,
        spam_risk=risk,
        reports=score.report_count if score else 0,
        distinct_reporters=score.distinct_reporters if score else 0,
        spam_label=_label(risk),
        last_updated_ms=(score.updated_at * 1000) if score else 0,
        unavailable_reason=None if name else "NO_RELIABLE_SOURCE",
    )


def _legacy_source_tag(source: str) -> str | None:
    """Map the legacy projection's tags onto the plan's vocabulary."""
    return {
        "BUSINESS": "business",
        "CONTACT": "contact",
        "PROVIDER": "provider",
        "COMMUNITY": "community",
    }.get((source or "").upper())


def _label(score: int) -> str:
    if score >= 70:
        return "spam"
    if score >= 40:
        return "likely_spam"
    return "clean"


def freshness(last_updated: int, now_ms: int | None = None) -> str:
    """`last_updated` is milliseconds, matching the existing contract."""
    if not last_updated:
        return "UNKNOWN"
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    return "STALE" if (now_ms - last_updated) > STALE_AFTER_SECONDS * 1000 else "FRESH"


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
    """Legacy projection writer, retained for the seeded business flow."""
    record = db.query(NumberIdentity).filter(NumberIdentity.number == number).one_or_none()
    if record is None:
        record = NumberIdentity(number=number)
        db.add(record)

    from .names import SOURCE_CONFIDENCE as _CONF  # local import: avoid a cycle

    incoming = _CONF.get("business" if source == "BUSINESS" else source.lower(), 50) / 100.0
    if display_name and (
        record.display_name is None or incoming >= (record.confidence or 0.0)
    ):
        record.display_name = display_name
        record.source = source
        record.confidence = incoming

    if number_type and not record.number_type:
        record.number_type = number_type
    if business_category:
        record.business_category = business_category
    if verification_status == "VERIFIED":
        record.verification_status = "VERIFIED"

    record.last_updated = int(time.time() * 1000)
    db.flush()
    return record
