"""Initial schema: vj_servers, shows, dj_sessions.

Revision ID: 001
Revises:
Create Date: 2026-02-07
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "001"
down_revision: str | None = None
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    # -- vj_servers ------------------------------------------------------------
    op.create_table(
        "vj_servers",
        sa.Column("id", sa.Uuid, primary_key=True),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("websocket_url", sa.String(500), nullable=False),
        sa.Column("api_key_hash", sa.String(128), nullable=False),
        sa.Column("jwt_secret", sa.String(128), nullable=False),
        sa.Column("last_heartbeat", sa.DateTime(timezone=True), nullable=True),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.text("true")),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
    )

    # -- shows -----------------------------------------------------------------
    op.create_table(
        "shows",
        sa.Column("id", sa.Uuid, primary_key=True),
        sa.Column(
            "server_id",
            sa.Uuid,
            sa.ForeignKey("vj_servers.id"),
            nullable=False,
        ),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("connect_code", sa.String(10), unique=True, nullable=True),
        sa.Column("status", sa.String(20), nullable=False, server_default=sa.text("'active'")),
        sa.Column("max_djs", sa.Integer, nullable=False, server_default=sa.text("8")),
        sa.Column("current_djs", sa.Integer, nullable=False, server_default=sa.text("0")),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=True),
    )

    # -- dj_sessions -----------------------------------------------------------
    op.create_table(
        "dj_sessions",
        sa.Column("id", sa.Uuid, primary_key=True),
        sa.Column(
            "show_id",
            sa.Uuid,
            sa.ForeignKey("shows.id"),
            nullable=False,
        ),
        sa.Column("dj_name", sa.String(100), nullable=False),
        sa.Column(
            "connected_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
        sa.Column("disconnected_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("ip_address", sa.String(45), nullable=False, server_default=sa.text("''")),
    )


def downgrade() -> None:
    op.drop_table("dj_sessions")
    op.drop_table("shows")
    op.drop_table("vj_servers")
