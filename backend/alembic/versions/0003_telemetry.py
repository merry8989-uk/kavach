"""telemetry_events: server-side store for app diagnostics events

Revision ID: 0003_telemetry
Revises: 0002_consent_layer
Create Date: 2026-02-02

This is what lets one operator own the dashboard for every install, instead of
each user creating their own Notion integration.
"""
import sqlalchemy as sa
from alembic import op

revision = "0003_telemetry"
down_revision = "0002_consent_layer"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "telemetry_events",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("received_at", sa.BigInteger(), nullable=False),
        sa.Column("user_code", sa.String(16), nullable=False),
        sa.Column("event_name", sa.String(64), nullable=False),
        sa.Column("schema_version", sa.String(16), nullable=False),
        sa.Column("event_timestamp", sa.String(32), nullable=False),
        sa.Column("session_id", sa.String(64), nullable=False),
        sa.Column("attempt_id", sa.String(64), nullable=True),
        sa.Column("level", sa.String(8), nullable=False),
        sa.Column("app_version", sa.String(32), nullable=False),
        sa.Column("os", sa.String(64), nullable=False),
        sa.Column("device_model", sa.String(64), nullable=False),
        sa.Column("platform", sa.String(16), nullable=False, server_default="android"),
        sa.Column("network_type", sa.String(16), nullable=True),
        sa.Column("screen_or_route", sa.String(64), nullable=False),
        sa.Column("release_channel", sa.String(16), nullable=True),
        sa.Column("properties_json", sa.Text(), nullable=False, server_default="{}"),
        sa.Column("pushed_to_notion", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("notion_error", sa.String(200), nullable=True),
    )
    op.create_index("ix_telemetry_received_at", "telemetry_events", ["received_at"])
    op.create_index("ix_telemetry_user_code", "telemetry_events", ["user_code"])
    op.create_index("ix_telemetry_event_name", "telemetry_events", ["event_name"])
    op.create_index("ix_telemetry_session", "telemetry_events", ["session_id"])
    op.create_index("ix_telemetry_level", "telemetry_events", ["level"])
    op.create_index("ix_telemetry_pushed", "telemetry_events", ["pushed_to_notion"])


def downgrade() -> None:
    op.drop_index("ix_telemetry_pushed", table_name="telemetry_events")
    op.drop_index("ix_telemetry_level", table_name="telemetry_events")
    op.drop_index("ix_telemetry_session", table_name="telemetry_events")
    op.drop_index("ix_telemetry_event_name", table_name="telemetry_events")
    op.drop_index("ix_telemetry_user_code", table_name="telemetry_events")
    op.drop_index("ix_telemetry_received_at", table_name="telemetry_events")
    op.drop_table("telemetry_events")
