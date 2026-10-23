"""let a business claim a listing

Revision ID: 0007_business_claims
Revises: 0006_moderation_decisions
Create Date: 2026-03-12

`Business.verified` has existed since the first schema and **nothing ever set it
to true**. The directory could show a listing as a business; no listing could
ever become a verified one, because there was no way to claim it and no approval
that had any effect.

These two columns are what a claim carries. Kept separate from `detail` because
an approved claim applies the name exactly, and parsing a free-text note back out
is how that goes quietly wrong.
"""
import sqlalchemy as sa
from alembic import op

revision = "0007_business_claims"
down_revision = "0006_moderation_decisions"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("moderation_queue", sa.Column("claim_name", sa.String(200), nullable=True))
    op.add_column("moderation_queue", sa.Column("claim_category", sa.String(60), nullable=True))


def downgrade() -> None:
    op.drop_column("moderation_queue", "claim_category")
    op.drop_column("moderation_queue", "claim_name")
