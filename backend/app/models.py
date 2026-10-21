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
