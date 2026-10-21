"""The three lawful sources of a name, and the rules that gate them.

Hard rule, enforced here and nowhere else:

    A name is publishable ONLY if its source is `self_declared` or `business`.

A community submission is stored so a human can look at it, but it is **never**
returned as somebody's name. That single rule is what keeps this product
defensible, so it lives in one function rather than being re-checked at each
call site.

Timestamps in this layer are epoch **seconds**.
"""
import time

from sqlalchemy import func
from sqlalchemy.orm import Session

from ..hashing import phone_hash
from ..models import NameEntry, Number

# Only these may ever be shown as a name.
PUBLISHABLE_SOURCES = frozenset({"self_declared", "business"})

# Confidence we attach by provenance (0..100).
SOURCE_CONFIDENCE = {"self_declared": 100, "business": 95, "community": 40}


def upsert_number_by_hash(db: Session, digest: str, country: str = "IN") -> Number:
    """Get or create the row for a number, from its hash.

    This is the variant the account flows use: a verified user's own number is
    reachable through `user.phone_hash`, so no raw number is ever needed — and
    therefore never stored — to publish or withdraw their own name.
    """
    row = db.query(Number).filter(Number.phone_hash == digest).one_or_none()
    if row is None:
        row = Number(phone_hash=digest, country=country, first_seen_at=int(time.time()))
        db.add(row)
        db.flush()
    return row


def upsert_number(db: Session, e164: str, country: str = "IN") -> Number:
    """Get or create the row for a number we hold transiently (admin flows)."""
    return upsert_number_by_hash(db, phone_hash(e164), country)


def set_self_declared_name_by_hash(
    db: Session, digest: str, name: str, contributor_user: str,
) -> NameEntry:
    """The owner declared this name. Supersedes any previous self-declaration."""
    number = upsert_number_by_hash(db, digest)
    _deactivate(db, number.id, source="self_declared")

    entry = NameEntry(
        number_id=number.id,
        name=name.strip()[:200],
        source="self_declared",
        confidence=SOURCE_CONFIDENCE["self_declared"],
        contributor_user=contributor_user,
        is_active=True,
        created_at=int(time.time()),
    )
    db.add(entry)
    db.flush()
    return entry


def set_self_declared_name(
    db: Session, e164: str, name: str, contributor_user: str,
) -> NameEntry:
    """Admin/business variant that takes a raw number transiently."""
    return set_self_declared_name_by_hash(db, phone_hash(e164), name, contributor_user)


def clear_self_declared_name_by_hash(db: Session, digest: str) -> int:
    """Opt-out. After this the number returns no name at all."""
    number = upsert_number_by_hash(db, digest)
    return _deactivate(db, number.id, source="self_declared")


def clear_self_declared_name(db: Session, e164: str) -> int:
    return clear_self_declared_name_by_hash(db, phone_hash(e164))


def add_business_name(
    db: Session, e164: str, name: str, verified: bool = False,
) -> NameEntry:
    """A public listing. Unverified claims stay out of the publishable set."""
    number = upsert_number(db, e164)
    _deactivate(db, number.id, source="business")
    entry = NameEntry(
        number_id=number.id,
        name=name.strip()[:200],
        source="business" if verified else "community",
        confidence=SOURCE_CONFIDENCE["business"] if verified else SOURCE_CONFIDENCE["community"],
        is_active=True,
        created_at=int(time.time()),
    )
    db.add(entry)
    db.flush()
    return entry


def add_community_name(
    db: Session, e164: str, name: str, contributor_user: str,
) -> NameEntry:
    """A crowd-sourced suggestion. Recorded for moderation; never published."""
    number = upsert_number(db, e164)
    entry = NameEntry(
        number_id=number.id,
        name=name.strip()[:200],
        source="community",
        confidence=SOURCE_CONFIDENCE["community"],
        contributor_user=contributor_user,
        is_active=True,
        created_at=int(time.time()),
    )
    db.add(entry)
    db.flush()
    return entry


def best_publishable_name(db: Session, number_id: int) -> tuple[str, str, int] | None:
    """The best name we are allowed to show, or None.

    Returns `(name, source, confidence)`. Anything whose source is not
    self_declared or business is filtered out here — including a community
    submission, no matter how many people made it.
    """
    entries = (
        db.query(NameEntry)
        .filter(
            NameEntry.number_id == number_id,
            NameEntry.is_active.is_(True),
            NameEntry.source.in_(tuple(PUBLISHABLE_SOURCES)),
        )
        .all()
    )
    if not entries:
        return None
    best = max(entries, key=lambda e: (e.confidence, e.created_at))
    return best.name, best.source, best.confidence


def is_whitelisted(db: Session, number_id: int) -> bool:
    """Verified business or an active self-declared name — trusted over rumours."""
    return (
        db.query(NameEntry)
        .filter(
            NameEntry.number_id == number_id,
            NameEntry.is_active.is_(True),
            NameEntry.source.in_(tuple(PUBLISHABLE_SOURCES)),
        )
        .count()
        > 0
    )


def pending_community_suggestions(db: Session, number_id: int) -> int:
    """How many community suggestions are waiting for a human. Never published."""
    return (
        db.query(func.count(NameEntry.id))
        .filter(
            NameEntry.number_id == number_id,
            NameEntry.is_active.is_(True),
            NameEntry.source == "community",
        )
        .scalar()
        or 0
    )


def _deactivate(db: Session, number_id: int, source: str) -> int:
    rows = (
        db.query(NameEntry)
        .filter(
            NameEntry.number_id == number_id,
            NameEntry.source == source,
            NameEntry.is_active.is_(True),
        )
        .all()
    )
    for row in rows:
        row.is_active = False
    db.flush()
    return len(rows)
