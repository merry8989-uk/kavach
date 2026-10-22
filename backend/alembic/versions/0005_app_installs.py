"""per-install app tokens

Revision ID: 0005_app_installs
Revises: 0004_correction_attribution
Create Date: 2026-02-04

The app shipped one shared bearer token. An APK is trivially decompilable, so
that token was public in practice — and because it was shared, revoking it would
have broken every user at once, which means it was never revoked.

This table holds one row per install, with only the token's hash, so an install
can be cut off on its own.
"""
import sqlalchemy as sa
from alembic import op

revision = "0005_app_installs"
down_revision = "0004_correction_attribution"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "app_installs",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("install_id", sa.String(64), nullable=False),
        sa.Column("token_hash", sa.String(64), nullable=False),
        sa.Column("platform", sa.String(16), nullable=False, server_default="android"),
        sa.Column("app_version", sa.String(32), nullable=True),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
        sa.Column("last_seen_at", sa.BigInteger(), nullable=False),
        sa.Column("revoked", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("registration_ip_hash", sa.String(64), nullable=True),
    )
    op.create_index("ix_app_installs_install_id", "app_installs", ["install_id"], unique=True)
    op.create_index("ix_app_installs_token_hash", "app_installs", ["token_hash"])
    op.create_index("ix_app_installs_revoked", "app_installs", ["revoked"])


def downgrade() -> None:
    op.drop_index("ix_app_installs_revoked", table_name="app_installs")
    op.drop_index("ix_app_installs_token_hash", table_name="app_installs")
    op.drop_index("ix_app_installs_install_id", table_name="app_installs")
    op.drop_table("app_installs")
