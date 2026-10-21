"""Community spam flagging: submission, anti-abuse and aggregation.

A spam label is a **community opinion about a number**, never a claim about a
person. The anti-abuse rules from the plan (section 6) are enforced here:

- one report per (number, user, category);
- new/low-trust accounts carry reduced weight;
- a per-user daily cap;
- the score is recomputed from capped, decayed, weighted signals.

Timestamps are epoch **seconds**.
"""
import time

from sqlalchemy import func
from sqlalchemy.orm import Session

from ..config import get_settings
from ..models import Number, SpamReport, SpamScore
from ..scoring import (
    ReportSignal,
    compute_consent_spam_score,
    reporter_weight_for_trust,
)
from . import names

VALID_CATEGORIES = frozenset({"fraud", "telemarketing", "robocall", "other"})

# Anti-abuse caps.
DAILY_REPORT_LIMIT = 20
# Bursts from many accounts about one number get a human's attention.
BURST_DISTINCT_REPORTERS = 25


class ReportRejected(Exception):
    """A report that must not be stored. The message is safe to show a user."""

    def __init__(self, detail: str, status_code: int = 409) -> None:
        super().__init__(detail)
        self.detail = detail
        self.status_code = status_code


def submit_report(
    db: Session,
    e164: str,
    reporter_user: str,
    category: str,
    comment: str | None,
    trust_score: int = 0,
) -> tuple[bool, int, str]:
    """Store a report and refresh the number's score.

    Returns `(created, report_count, detail)`. `created` is False when the user
    had already reported this number in this category.
    """
    normalised = (category or "").strip().lower()
    if normalised not in VALID_CATEGORIES:
        raise ReportRejected(
            f"category must be one of: {', '.join(sorted(VALID_CATEGORIES))}",
            status_code=422,
        )

    number = names.upsert_number(db, e164)

    existing = (
        db.query(SpamReport)
        .filter(
            SpamReport.number_id == number.id,
            SpamReport.reporter_user == reporter_user,
            SpamReport.category == normalised,
        )
        .one_or_none()
    )
    if existing is not None:
        count = _report_count(db, number.id)
        return False, count, "you have already reported this number in this category"

    now = int(time.time())
    since = now - 86_400
    today = (
        db.query(func.count(SpamReport.id))
        .filter(
            SpamReport.reporter_user == reporter_user,
            SpamReport.created_at >= since,
        )
        .scalar()
        or 0
    )
    if today >= DAILY_REPORT_LIMIT:
        raise ReportRejected("daily report limit reached, please try again tomorrow", status_code=429)

    db.add(
        SpamReport(
            number_id=number.id,
            reporter_user=reporter_user,
            category=normalised,
            comment=(comment or "").strip()[:500] or None,
            weight=reporter_weight_for_trust(trust_score),
            created_at=now,
        )
    )
    db.flush()

    recompute_score(db, number.id)
    return True, _report_count(db, number.id), "report recorded, thank you"


def recompute_score(db: Session, number_id: int):
    """Rebuild the aggregate from scratch — never incrementally drift."""
    rows = db.query(SpamReport).filter(SpamReport.number_id == number_id).all()
    now = int(time.time())
    settings = get_settings()

    signals = [
        ReportSignal(
            category=row.category,
            weight=row.weight,
            age_days=max(0.0, (now - row.created_at) / 86_400.0),
        )
        for row in rows
    ]
    distinct = len({row.reporter_user for row in rows})
    whitelisted = names.is_whitelisted(db, number_id)

    result = compute_consent_spam_score(
        signals=signals,
        distinct_reporters=distinct,
        whitelisted=whitelisted,
        half_life_days=settings.spam_report_half_life_days,
        likely_threshold=settings.spam_likely_threshold,
        spam_threshold=settings.spam_threshold,
        rule_version=settings.scoring_rule_version,
    )

    score = db.query(SpamScore).filter(SpamScore.number_id == number_id).one_or_none()
    if score is None:
        score = SpamScore(number_id=number_id)
        db.add(score)
    score.score = result.score
    score.report_count = len(rows)
    score.distinct_reporters = distinct
    score.last_report_at = max((row.created_at for row in rows), default=0)
    score.updated_at = now
    db.flush()

    if distinct >= BURST_DISTINCT_REPORTERS:
        _flag_for_moderation(db, number_id, "burst_reports")

    return result


def report_summary(db: Session, number_id: int) -> dict:
    """What the UI may show: counts and categories, never who reported."""
    rows = db.query(SpamReport).filter(SpamReport.number_id == number_id).all()
    categories: dict[str, int] = {}
    for row in rows:
        categories[row.category] = categories.get(row.category, 0) + 1

    score = db.query(SpamScore).filter(SpamScore.number_id == number_id).one_or_none()
    return {
        "count": len(rows),
        "distinct_reporters": len({row.reporter_user for row in rows}),
        "top_categories": [
            {"category": name, "count": count}
            for name, count in sorted(categories.items(), key=lambda kv: kv[1], reverse=True)
        ],
        "score": score.score if score else 0,
        "last_report_at": score.last_report_at if score else 0,
    }


def _report_count(db: Session, number_id: int) -> int:
    return (
        db.query(func.count(SpamReport.id))
        .filter(SpamReport.number_id == number_id)
        .scalar()
        or 0
    )


def _flag_for_moderation(db: Session, number_id: int, reason: str) -> None:
    from ..models import ModerationItem  # local import avoids a cycle at module load

    already = (
        db.query(ModerationItem)
        .filter(
            ModerationItem.number_id == number_id,
            ModerationItem.reason == reason,
            ModerationItem.status == "pending",
        )
        .one_or_none()
    )
    if already is None:
        db.add(
            ModerationItem(
                number_id=number_id,
                reason=reason,
                status="pending",
                created_at=int(time.time()),
            )
        )
        db.flush()
