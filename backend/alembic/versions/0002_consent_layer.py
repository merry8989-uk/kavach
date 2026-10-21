"""consent-based caller ID: users, profiles, numbers, name_entries,
spam_reports, spam_scores, consent_events, moderation_queue

Revision ID: 0002_consent_layer
Revises: 0001_initial
Create Date: 2026-02-01

Phone numbers appear in these tables ONLY as `phone_hash`
(HMAC_SHA256 with a server pepper). The raw number is never persisted here.
"""
import sqlalchemy as sa
from alembic import op

revision = "0002_consent_layer"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("phone_hash", sa.String(length=64), nullable=False),
        sa.Column("phone_country", sa.String(length=2), nullable=False),
        sa.Column("is_verified", sa.Boolean(), nullable=False),
        sa.Column("trust_score", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
        sa.Column("last_seen_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_users_phone_hash", "users", ["phone_hash"], unique=True)

    op.create_table(
        "profiles",
        sa.Column("user_id", sa.String(length=36), sa.ForeignKey("users.id"), primary_key=True),
        sa.Column("display_name", sa.String(length=120), nullable=True),
        sa.Column("publish_name", sa.Boolean(), nullable=False),
        sa.Column("allow_spam_reports", sa.Boolean(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
    )

    op.create_table(
        "numbers",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("phone_hash", sa.String(length=64), nullable=False),
        sa.Column("country", sa.String(length=2), nullable=False),
        sa.Column("carrier", sa.String(length=60), nullable=True),
        sa.Column("telecom_circle", sa.String(length=60), nullable=True),
        sa.Column("line_type", sa.String(length=20), nullable=True),
        sa.Column("first_seen_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_numbers_phone_hash", "numbers", ["phone_hash"], unique=True)

    op.create_table(
        "name_entries",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number_id", sa.Integer(), sa.ForeignKey("numbers.id"), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("source", sa.String(length=20), nullable=False),
        sa.Column("confidence", sa.Integer(), nullable=False),
        sa.Column("contributor_user", sa.String(length=36), sa.ForeignKey("users.id"), nullable=True),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_name_entries_number_id", "name_entries", ["number_id"])
    op.create_index("ix_name_entries_number_active", "name_entries", ["number_id", "is_active"])

    op.create_table(
        "spam_reports",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number_id", sa.Integer(), sa.ForeignKey("numbers.id"), nullable=False),
        sa.Column("reporter_user", sa.String(length=36), nullable=False),
        sa.Column("category", sa.String(length=40), nullable=False),
        sa.Column("comment", sa.Text(), nullable=True),
        sa.Column("weight", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_spam_reports_number_id", "spam_reports", ["number_id"])
    op.create_index("ix_spam_reports_reporter_user", "spam_reports", ["reporter_user"])
    # One report per user, number and category.
    op.create_index(
        "ix_spam_reports_unique",
        "spam_reports",
        ["number_id", "reporter_user", "category"],
        unique=True,
    )

    op.create_table(
        "spam_scores",
        sa.Column("number_id", sa.Integer(), sa.ForeignKey("numbers.id"), primary_key=True),
        sa.Column("score", sa.Integer(), nullable=False),
        sa.Column("report_count", sa.Integer(), nullable=False),
        sa.Column("distinct_reporters", sa.Integer(), nullable=False),
        sa.Column("last_report_at", sa.BigInteger(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
    )

    op.create_table(
        "consent_events",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.String(length=36), nullable=False),
        sa.Column("action", sa.String(length=40), nullable=False),
        sa.Column("detail", sa.Text(), nullable=True),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_consent_events_user_id", "consent_events", ["user_id"])

    op.create_table(
        "moderation_queue",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number_id", sa.Integer(), sa.ForeignKey("numbers.id"), nullable=True),
        sa.Column("reason", sa.String(length=40), nullable=False),
        sa.Column("status", sa.String(length=20), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_moderation_queue_number_id", "moderation_queue", ["number_id"])


def downgrade() -> None:
    op.drop_table("moderation_queue")
    op.drop_table("consent_events")
    op.drop_table("spam_scores")
    op.drop_index("ix_spam_reports_unique", table_name="spam_reports")
    op.drop_table("spam_reports")
    op.drop_table("name_entries")
    op.drop_table("numbers")
    op.drop_table("profiles")
    op.drop_table("users")
