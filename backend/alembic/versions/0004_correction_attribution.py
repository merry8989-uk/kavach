"""attribute identity corrections to the actor who submitted them

Revision ID: 0004_correction_attribution
Revises: 0003_telemetry
Create Date: 2026-02-03

Without this column a correction could not be traced to anyone, so the
right-to-erasure path had no way to delete one. It tried to approximate it by
"numbers this actor reported", which is a different thing — and the lookup ran
after those reports were already deleted, so it silently removed nothing.

Nullable, because rows written before this migration have no known author.
"""
import sqlalchemy as sa
from alembic import op

revision = "0004_correction_attribution"
down_revision = "0003_telemetry"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "identity_corrections",
        sa.Column("actor_hash", sa.String(64), nullable=True),
    )
    op.create_index(
        "ix_identity_corrections_actor_hash",
        "identity_corrections",
        ["actor_hash"],
    )


def downgrade() -> None:
    op.drop_index("ix_identity_corrections_actor_hash", table_name="identity_corrections")
    op.drop_column("identity_corrections", "actor_hash")
