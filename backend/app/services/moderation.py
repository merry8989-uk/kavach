"""Reading the moderation queue, and deciding what to do with it.

The queue existed and nobody read it. `services/spam.py` flagged numbers for
burst reporting and for crossing a threshold, and every one of those flags went
into a table that had no reader — so the abuse signal the spec asks for was
detected and then discarded, and a number wrongly labelled spam stayed labelled.

Two things make this a workflow rather than a log:

1. **A reader** — `pending()` returns the queue in the order a person should work
   it: oldest first, because a wrong label left standing for a week is worse than
   one left for an hour.
2. **A decision that does something** — approving an appeal does not just change a
   status. It clears the number's score and its aggregate counts, so the next
   lookup actually returns a different answer. A decision with no effect is a
   comment.
"""
import time

from sqlalchemy.orm import Session

from ..models import Business, ModerationItem, Number, SpamScore

# The reasons the queue accepts. Anything else is a typo, not a new category.
REASONS = {"flagged_by_threshold", "user_appeal", "business_claim", "burst_reports"}
DECISIONS = {"approved", "rejected"}


def file_appeal(
    db: Session,
    number_id: int,
    appellant_hash: str,
    detail: str | None,
) -> tuple[ModerationItem, bool]:
    """Record that someone says a number has been labelled wrongly.

    Returns `(item, created)`. A second pending appeal from the same source about
    the same number updates the statement rather than filing a duplicate — one
    person should not be able to bury the queue.
    """
    existing = (
        db.query(ModerationItem)
        .filter(
            ModerationItem.number_id == number_id,
            ModerationItem.appellant_hash == appellant_hash,
            ModerationItem.status == "pending",
        )
        .one_or_none()
    )
    if existing is not None:
        if detail:
            existing.detail = detail
        return existing, False

    item = ModerationItem(
        number_id=number_id,
        reason="user_appeal",
        status="pending",
        detail=detail,
        appellant_hash=appellant_hash,
        created_at=int(time.time()),
    )
    db.add(item)
    db.flush()
    return item, True


def file_business_claim(
    db: Session,
    number_id: int,
    appellant_hash: str,
    name: str,
    category: str | None,
    evidence: str | None,
) -> tuple[ModerationItem, bool]:
    """Record that a business says a listing is theirs.

    Same dedupe rule as an appeal: one source cannot bury the queue with the same
    claim. Returns `(item, created)`.
    """
    existing = (
        db.query(ModerationItem)
        .filter(
            ModerationItem.number_id == number_id,
            ModerationItem.appellant_hash == appellant_hash,
            ModerationItem.reason == "business_claim",
            ModerationItem.status == "pending",
        )
        .one_or_none()
    )
    if existing is not None:
        existing.claim_name = name.strip()[:200]
        existing.claim_category = (category or "").strip()[:60] or None
        if evidence:
            existing.detail = evidence.strip()[:1000]
        return existing, False

    item = ModerationItem(
        number_id=number_id,
        reason="business_claim",
        status="pending",
        detail=(evidence or "").strip()[:1000] or None,
        claim_name=name.strip()[:200],
        claim_category=(category or "").strip()[:60] or None,
        appellant_hash=appellant_hash,
        created_at=int(time.time()),
    )
    db.add(item)
    db.flush()
    return item, True


def pending(db: Session, limit: int = 50, offset: int = 0) -> list[ModerationItem]:
    """The queue, oldest first — the order it should be worked in."""
    return (
        db.query(ModerationItem)
        .filter(ModerationItem.status == "pending")
        .order_by(ModerationItem.created_at.asc())
        .limit(limit)
        .offset(offset)
        .all()
    )


def pending_count(db: Session) -> int:
    return db.query(ModerationItem).filter(ModerationItem.status == "pending").count()


def decide(
    db: Session,
    item: ModerationItem,
    decision: str,
    note: str | None,
) -> None:
    """Resolve an item, and make the resolution mean something.

    An approved appeal clears the number's score and counts. That is the entire
    point: without it the queue would record that a mistake was acknowledged and
    leave the mistake in place.
    """
    if decision not in DECISIONS:
        raise ValueError(f"unknown decision {decision!r}")

    item.status = decision
    item.resolved_at = int(time.time())
    item.resolution_note = note

    # A business claim is the one reason whose approval changes what the app
    # *shows*, rather than what it blocks. Approving it publishes the name.
    if decision == "approved" and item.reason == "business_claim" and item.number_id is not None:
        _approve_business_claim(db, item)

    if decision == "approved" and item.reason == "user_appeal" and item.number_id is not None:
        score = db.query(SpamScore).filter(SpamScore.number_id == item.number_id).one_or_none()
        if score is not None:
            # Zeroed rather than deleted, so the record of there having been a
            # score at all survives and the change is auditable.
            score.score = 0
            score.report_count = 0
            score.distinct_reporters = 0
            score.updated_at = int(time.time())

        # Any other pending flags on the same number are moot now.
        others = (
            db.query(ModerationItem)
            .filter(
                ModerationItem.number_id == item.number_id,
                ModerationItem.status == "pending",
                ModerationItem.id != item.id,
            )
            .all()
        )
        for other in others:
            other.status = "approved"
            other.resolved_at = int(time.time())
            other.resolution_note = "closed by an approved appeal on the same number"


def _approve_business_claim(db: Session, item: ModerationItem) -> None:
    """Publish the claimed name, and mark the directory listing verified.

    This is the step that was missing. `Business.verified` had existed since the
    first schema with nothing ever setting it — so a listing could be shown as a
    business and could never become a verified one. A claim that is approved now
    does three things, and all three are needed for the claim to have meant
    anything:

    1. the number is published under the claimed name, with `business` provenance
       and a confidence that puts it above community suggestions;
    2. the directory row is marked verified;
    3. any pending community name for the same number is left alone — it will lose
       to the business entry on provenance, which is the correct outcome and does
       not need a destructive step here.
    """
    from . import names

    name = (item.claim_name or "").strip()
    if not name:
        # Nothing to publish. Refusing here rather than publishing an empty name
        # is the difference between a no-op and a wrong record.
        return

    number = db.query(Number).filter(Number.id == item.number_id).one_or_none()
    if number is None:
        return

    names.add_business_name_by_hash(db, number.phone_hash, name, verified=True)

    business = (
        db.query(Business)
        .filter(Business.name.ilike(name))
        .one_or_none()
    )
    if business is None:
        business = Business(name=name, category=item.claim_category, source="BUSINESS")
        db.add(business)
    business.category = item.claim_category or business.category
    business.verified = True


def find_number(db: Session, phone_hash: str) -> Number | None:
    return db.query(Number).filter(Number.phone_hash == phone_hash).one_or_none()
