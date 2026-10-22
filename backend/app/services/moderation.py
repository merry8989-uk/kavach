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

from ..models import ModerationItem, Number, SpamScore

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

    if decision == "approved" and item.number_id is not None:
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


def find_number(db: Session, phone_hash: str) -> Number | None:
    return db.query(Number).filter(Number.phone_hash == phone_hash).one_or_none()
