"""give the moderation queue a reader and a decision

Revision ID: 0006_moderation_decisions
Revises: 0005_app_installs
Create Date: 2026-02-05

`moderation_queue` was written to and never read. These columns are what turn it
from a log into a workflow: what the person said, who said it, and what was
decided.
"""
import sqlalchemy as sa
from alembic import op

revision = "0006_moderation_decisions"
down_revision = "0005_app_installs"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("moderation_queue", sa.Column("detail", sa.Text(), nullable=True))
    op.add_column("moderation_queue", sa.Column("appellant_hash", sa.String(64), nullable=True))
    op.add_column("moderation_queue", sa.Column("resolved_at", sa.BigInteger(), nullable=True))
    op.add_column("moderation_queue", sa.Column("resolution_note", sa.Text(), nullable=True))
    op.create_index("ix_moderation_queue_appellant", "moderation_queue", ["appellant_hash"])
    # The queue is read as "pending items, oldest first", so index that.
    op.create_index("ix_moderation_queue_status", "moderation_queue", ["status", "created_at"])


def downgrade() -> None:
    op.drop_index("ix_moderation_queue_status", table_name="moderation_queue")
    op.drop_index("ix_moderation_queue_appellant", table_name="moderation_queue")
    op.drop_column("moderation_queue", "resolution_note")
    op.drop_column("moderation_queue", "resolved_at")
    op.drop_column("moderation_queue", "appellant_hash")
    op.drop_column("moderation_queue", "detail")
