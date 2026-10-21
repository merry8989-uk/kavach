"""Accounts, profiles and the consent audit trail (plan sections 4.1, 4.5, 5).

DPDP requirements this file is responsible for:
- consent is explicit and defaults to OFF;
- every consent decision is appended to `consent_events` (auditable);
- deletion is real: the profile is removed, the self-declared name is
  deactivated so the number stops resolving to a name, and only anonymous
  aggregate report counts survive.

Timestamps are epoch **seconds**.
"""
import time
import uuid

from sqlalchemy.orm import Session

from ..models import ConsentEvent, Profile, User
from . import names

# Actions written to the append-only audit trail.
ACTION_PUBLISH_ON = "publish_name_on"
ACTION_PUBLISH_OFF = "publish_name_off"
ACTION_PROFILE_UPDATED = "profile_updated"
ACTION_DELETE_ACCOUNT = "delete_account"


def get_or_create_user(db: Session, phone_hash_value: str, country: str = "IN") -> tuple[User, bool]:
    """Return `(user, created)`. The caller decides what to do with `created`."""
    user = db.query(User).filter(User.phone_hash == phone_hash_value).one_or_none()
    if user is not None:
        user.last_seen_at = int(time.time())
        db.flush()
        return user, False

    now = int(time.time())
    user = User(
        id=str(uuid.uuid4()),
        phone_hash=phone_hash_value,
        phone_country=country,
        is_verified=True,          # only ever created after a verified OTP
        trust_score=0,
        created_at=now,
        last_seen_at=now,
    )
    db.add(user)
    db.flush()
    return user, True


def get_profile(db: Session, user_id: str) -> Profile:
    profile = db.query(Profile).filter(Profile.user_id == user_id).one_or_none()
    if profile is None:
        profile = Profile(
            user_id=user_id,
            display_name=None,
            publish_name=False,          # consent defaults to OFF
            allow_spam_reports=True,
            updated_at=int(time.time()),
        )
        db.add(profile)
        db.flush()
    return profile


def set_profile(
    db: Session,
    user: User,
    display_name: str | None,
    publish_name: bool,
    allow_spam_reports: bool | None = None,
) -> Profile:
    """Update the profile and record exactly what the user consented to.

    The number is addressed through `user.phone_hash`, so the raw number is
    never needed here — and therefore never stored.
    """
    profile = get_profile(db, user.id)
    was_publishing = profile.publish_name

    profile.display_name = (display_name or "").strip()[:120] or None
    profile.publish_name = bool(publish_name)
    if allow_spam_reports is not None:
        profile.allow_spam_reports = bool(allow_spam_reports)
    profile.updated_at = int(time.time())

    # The name only exists in the identity tables while consent is on.
    if profile.publish_name and profile.display_name:
        names.set_self_declared_name_by_hash(
            db, user.phone_hash, profile.display_name, user.id,
        )
    else:
        names.clear_self_declared_name_by_hash(db, user.phone_hash)

    if profile.publish_name != was_publishing:
        record_event(
            db, user.id,
            ACTION_PUBLISH_ON if profile.publish_name else ACTION_PUBLISH_OFF,
            detail="name published" if profile.publish_name else "name withdrawn",
        )
    else:
        record_event(db, user.id, ACTION_PROFILE_UPDATED)

    db.flush()
    return profile


def record_event(db: Session, user_id: str, action: str, detail: str | None = None) -> ConsentEvent:
    event = ConsentEvent(
        user_id=user_id,
        action=action,
        detail=detail,
        created_at=int(time.time()),
    )
    db.add(event)
    db.flush()
    return event


def delete_account(db: Session, user: User) -> None:
    """Right to erasure. Irreversible, and recorded."""
    # 1. The number must stop resolving to this person's name.
    names.clear_self_declared_name_by_hash(db, user.phone_hash)

    # 2. Remove the profile outright.
    db.query(Profile).filter(Profile.user_id == user.id).delete()

    # 3. Audit before the row disappears, so the trail survives.
    record_event(db, user.id, ACTION_DELETE_ACCOUNT, detail="user requested erasure")

    # 4. Drop the user. Spam reports keep only their anonymous aggregate counts,
    #    which contain no personal data.
    db.query(User).filter(User.id == user.id).delete()
    db.flush()


def consent_history(db: Session, user_id: str, limit: int = 50) -> list[ConsentEvent]:
    return (
        db.query(ConsentEvent)
        .filter(ConsentEvent.user_id == user_id)
        .order_by(ConsentEvent.created_at.desc())
        .limit(limit)
        .all()
    )
