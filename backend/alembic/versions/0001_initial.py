"""initial schema

Revision ID: 0001_initial
Revises:
Create Date: 2026-01-01
"""
import sqlalchemy as sa
from alembic import op

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "number_identities",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number", sa.String(length=24), nullable=False),
        sa.Column("display_name", sa.String(length=200), nullable=True),
        sa.Column("number_type", sa.String(length=20), nullable=True),
        sa.Column("business_category", sa.String(length=60), nullable=True),
        sa.Column("source", sa.String(length=20), nullable=False),
        sa.Column("confidence", sa.Float(), nullable=False),
        sa.Column("verification_status", sa.String(length=20), nullable=False),
        sa.Column("spam_risk", sa.Integer(), nullable=False),
        sa.Column("report_count", sa.Integer(), nullable=False),
        sa.Column("distinct_reporters", sa.Integer(), nullable=False),
        sa.Column("last_updated", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_number_identities_number", "number_identities", ["number"], unique=True)

    op.create_table(
        "businesses",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("category", sa.String(length=60), nullable=True),
        sa.Column("number", sa.String(length=24), nullable=True),
        sa.Column("verified", sa.Boolean(), nullable=False),
        sa.Column("source", sa.String(length=20), nullable=False),
    )
    op.create_index("ix_businesses_name", "businesses", ["name"])
    op.create_index("ix_businesses_number", "businesses", ["number"])

    op.create_table(
        "reports",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number", sa.String(length=24), nullable=False),
        sa.Column("reporter_hash", sa.String(length=64), nullable=False),
        sa.Column("category", sa.String(length=40), nullable=False),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_reports_number", "reports", ["number"])
    op.create_index("ix_reports_reporter_hash", "reports", ["reporter_hash"])
    op.create_index("ix_reports_number_reporter", "reports", ["number", "reporter_hash"])

    op.create_table(
        "identity_corrections",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number", sa.String(length=24), nullable=False),
        sa.Column("suggested_name", sa.String(length=200), nullable=False),
        sa.Column("evidence", sa.Text(), nullable=True),
        sa.Column("status", sa.String(length=20), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_identity_corrections_number", "identity_corrections", ["number"])

    op.create_table(
        "audit_log",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("actor_hash", sa.String(length=64), nullable=False),
        sa.Column("action", sa.String(length=40), nullable=False),
        sa.Column("subject", sa.String(length=64), nullable=True),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_audit_log_actor_hash", "audit_log", ["actor_hash"])

    op.create_table(
        "consent_records",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("actor_hash", sa.String(length=64), nullable=False),
        sa.Column("scope", sa.String(length=40), nullable=False),
        sa.Column("granted", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_consent_records_actor_hash", "consent_records", ["actor_hash"])


def downgrade() -> None:
    op.drop_table("consent_records")
    op.drop_table("audit_log")
    op.drop_table("identity_corrections")
    op.drop_table("reports")
    op.drop_table("businesses")
    op.drop_table("number_identities")
