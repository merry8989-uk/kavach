"""ORM models. Mirrors docs/API_CONTRACT.md."""
from sqlalchemy import BigInteger, Boolean, Float, ForeignKey, Index, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


class NumberIdentity(Base):
    """A caller identity record. One row per normalised number."""

    __tablename__ = "number_identities"

    id: Mapped[int] = mapped_column(primary_key=True)
    number: Mapped[str] = mapped_column(String(24), unique=True, index=True)
    display_name: Mapped[str | None] = mapped_column(String(200), nullable=True)
    number_type: Mapped[str | None] = mapped_column(String(20), nullable=True)
    business_category: Mapped[str | None] = mapped_column(String(60), nullable=True)

    # CONTACT | COMMUNITY | BUSINESS | PROVIDER | NONE
    source: Mapped[str] = mapped_column(String(20), default="COMMUNITY")
    confidence: Mapped[float] = mapped_column(Float, default=0.0)
    # VERIFIED | UNVERIFIED | DISPUTED
    verification_status: Mapped[str] = mapped_column(String(20), default="UNVERIFIED")

    spam_risk: Mapped[int] = mapped_column(Integer, default=0)
    report_count: Mapped[int] = mapped_column(Integer, default=0)
    distinct_reporters: Mapped[int] = mapped_column(Integer, default=0)
    last_updated: Mapped[int] = mapped_column(BigInteger, default=0)


class Business(Base):
    __tablename__ = "businesses"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(200), index=True)
    category: Mapped[str | None] = mapped_column(String(60), nullable=True)
    number: Mapped[str | None] = mapped_column(String(24), index=True, nullable=True)
    verified: Mapped[bool] = mapped_column(Boolean, default=False)
    source: Mapped[str] = mapped_column(String(20), default="BUSINESS")


class Report(Base):
    """A spam report. Deduplicated per reporter per number per window."""

    __tablename__ = "reports"

    id: Mapped[int] = mapped_column(primary_key=True)
    number: Mapped[str] = mapped_column(String(24), index=True)
    # Hash of the authenticated reporter — never a raw identity.
    reporter_hash: Mapped[str] = mapped_column(String(64), index=True)
    category: Mapped[str] = mapped_column(String(40))
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[int] = mapped_column(BigInteger)

    __table_args__ = (
        Index("ix_reports_number_reporter", "number", "reporter_hash"),
    )


class Correction(Base):
    """A user-submitted identity correction, held for moderation."""

    __tablename__ = "identity_corrections"

    id: Mapped[int] = mapped_column(primary_key=True)
    number: Mapped[str] = mapped_column(String(24), index=True)
    # Who submitted it. Without this a correction could not be attributed to
    # anyone, so the right-to-erasure path had no way to remove one — it tried
    # to guess by "numbers this actor reported", which is not the same thing and
    # did not work either (the reports were deleted first).
    actor_hash: Mapped[str | None] = mapped_column(String(64), index=True, nullable=True)
    suggested_name: Mapped[str] = mapped_column(String(200))
    evidence: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="PENDING")
    created_at: Mapped[int] = mapped_column(BigInteger)


class AuditLog(Base):
    """Every identity write and report is attributable."""

    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(primary_key=True)
    actor_hash: Mapped[str] = mapped_column(String(64), index=True)
    action: Mapped[str] = mapped_column(String(40))
    subject: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[int] = mapped_column(BigInteger)


class ConsentRecord(Base):
    """Explicit opt-in records (e.g. contributing contact data)."""

    __tablename__ = "consent_records"

    id: Mapped[int] = mapped_column(primary_key=True)
    actor_hash: Mapped[str] = mapped_column(String(64), index=True)
    scope: Mapped[str] = mapped_column(String(40))
    granted: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[int] = mapped_column(BigInteger)


# ---------------------------------------------------------------------------
# Consent-based caller ID (docs/CALLER-ID-PLAN.md).
#
# These tables are the source of truth for the three lawful sources of a name:
#   1. self_declared — the number's owner opted in;
#   2. business      — a verified public listing;
#   3. community     — spam labels, which are opinions about a number, never a
#                      claim about a person's identity.
#
# Phone numbers appear here ONLY as `phone_hash` (HMAC_SHA256 + pepper).
# `NumberIdentity` above remains the fast public projection the lookup endpoint
# reads, so the Android client keeps a stable contract.
# ---------------------------------------------------------------------------


class User(Base):
    """A verified account. Identified by a hashed number, never a raw one."""

    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)  # uuid4
    phone_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    phone_country: Mapped[str] = mapped_column(String(2), default="IN")
    is_verified: Mapped[bool] = mapped_column(Boolean, default=False)
    # Grows with account age and good behaviour; weights their spam reports.
    trust_score: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[int] = mapped_column(BigInteger)
    last_seen_at: Mapped[int] = mapped_column(BigInteger)


class Profile(Base):
    """What the owner wants shown, and what they consent to."""

    __tablename__ = "profiles"

    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id"), primary_key=True,
    )
    display_name: Mapped[str | None] = mapped_column(String(120), nullable=True)
    # Consent to publish the name publicly. Default OFF, always.
    publish_name: Mapped[bool] = mapped_column(Boolean, default=False)
    allow_spam_reports: Mapped[bool] = mapped_column(Boolean, default=True)
    updated_at: Mapped[int] = mapped_column(BigInteger, default=0)


class Number(Base):
    """A number we know about. Keyed by hash; the raw value is not stored."""

    __tablename__ = "numbers"

    id: Mapped[int] = mapped_column(primary_key=True)
    phone_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    country: Mapped[str] = mapped_column(String(2), default="IN")
    carrier: Mapped[str | None] = mapped_column(String(60), nullable=True)
    telecom_circle: Mapped[str | None] = mapped_column(String(60), nullable=True)
    # mobile | landline | voip | unknown
    line_type: Mapped[str | None] = mapped_column(String(20), nullable=True)
    first_seen_at: Mapped[int] = mapped_column(BigInteger, default=0)


class NameEntry(Base):
    """A candidate name for a number, with its lawful source and confidence."""

    __tablename__ = "name_entries"

    id: Mapped[int] = mapped_column(primary_key=True)
    number_id: Mapped[int] = mapped_column(ForeignKey("numbers.id"), index=True)
    name: Mapped[str] = mapped_column(String(200))
    # self_declared | business | community
    source: Mapped[str] = mapped_column(String(20), default="community")
    confidence: Mapped[int] = mapped_column(Integer, default=0)  # 0..100
    contributor_user: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("users.id"), nullable=True,
    )
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[int] = mapped_column(BigInteger, default=0)

    __table_args__ = (
        Index("ix_name_entries_number_active", "number_id", "is_active"),
    )


class SpamReport(Base):
    """A community opinion about a number. One per user, number and category."""

    __tablename__ = "spam_reports"

    id: Mapped[int] = mapped_column(primary_key=True)
    number_id: Mapped[int] = mapped_column(ForeignKey("numbers.id"), index=True)
    reporter_user: Mapped[str] = mapped_column(String(36), index=True)
    # fraud | telemarketing | robocall | other
    category: Mapped[str] = mapped_column(String(40))
    comment: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Derived from the reporter's trust score, so new accounts count for less.
    weight: Mapped[int] = mapped_column(Integer, default=10)
    created_at: Mapped[int] = mapped_column(BigInteger)

    __table_args__ = (
        Index(
            "ix_spam_reports_unique",
            "number_id", "reporter_user", "category",
            unique=True,
        ),
    )


class SpamScore(Base):
    """The aggregated, explainable score for a number."""

    __tablename__ = "spam_scores"

    number_id: Mapped[int] = mapped_column(
        ForeignKey("numbers.id"), primary_key=True,
    )
    score: Mapped[int] = mapped_column(Integer, default=0)
    report_count: Mapped[int] = mapped_column(Integer, default=0)
    distinct_reporters: Mapped[int] = mapped_column(Integer, default=0)
    last_report_at: Mapped[int] = mapped_column(BigInteger, default=0)
    updated_at: Mapped[int] = mapped_column(BigInteger, default=0)


class ConsentEvent(Base):
    """Append-only audit trail of every consent decision (DPDP requirement)."""

    __tablename__ = "consent_events"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), index=True)
    # publish_name_on | publish_name_off | profile_updated | delete_account | ...
    action: Mapped[str] = mapped_column(String(40))
    detail: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[int] = mapped_column(BigInteger)


class ModerationItem(Base):
    """Something a human should look at before it is published."""

    __tablename__ = "moderation_queue"

    id: Mapped[int] = mapped_column(primary_key=True)
    number_id: Mapped[int | None] = mapped_column(
        ForeignKey("numbers.id"), nullable=True, index=True,
    )
    # flagged_by_threshold | user_appeal | business_claim | burst_reports
    reason: Mapped[str] = mapped_column(String(40))
    # pending | approved | rejected
    status: Mapped[str] = mapped_column(String(20), default="pending")
    created_at: Mapped[int] = mapped_column(BigInteger, default=0)


class TelemetryEvent(Base):
    """One diagnostics event received from an install.

    This is what makes the dashboard work at scale: every phone sends the same
    schema to *this* server, so the operator owns one database instead of asking
    each user to create their own Notion integration.

    The `user_code` is the app's random per-install pseudonym (KV-XXX-XXX), not
    an account or a device id.
    """

    __tablename__ = "telemetry_events"

    id: Mapped[int] = mapped_column(primary_key=True)
    received_at: Mapped[int] = mapped_column(BigInteger, index=True)
    user_code: Mapped[str] = mapped_column(String(16), index=True)
    event_name: Mapped[str] = mapped_column(String(64), index=True)
    schema_version: Mapped[str] = mapped_column(String(16))
    event_timestamp: Mapped[str] = mapped_column(String(32))
    session_id: Mapped[str] = mapped_column(String(64), index=True)
    attempt_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    level: Mapped[str] = mapped_column(String(8), index=True)
    app_version: Mapped[str] = mapped_column(String(32))
    os: Mapped[str] = mapped_column(String(64))
    device_model: Mapped[str] = mapped_column(String(64))
    platform: Mapped[str] = mapped_column(String(16), default="android")
    network_type: Mapped[str | None] = mapped_column(String(16), nullable=True)
    screen_or_route: Mapped[str] = mapped_column(String(64))
    release_channel: Mapped[str | None] = mapped_column(String(16), nullable=True)
    properties_json: Mapped[str] = mapped_column(Text, default="{}")

    pushed_to_notion: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    notion_error: Mapped[str | None] = mapped_column(String(200), nullable=True)
