"""Add desktop_exchange_codes table.

Moves desktop OAuth exchange codes from in-memory dict to the database
so they survive server restarts.

Revision ID: 013
Revises: 012
Create Date: 2026-02-22
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "013"
down_revision: str = "012"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.create_table(
        "desktop_exchange_codes",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("code", sa.String(64), nullable=False, unique=True),
        sa.Column("user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("payload", sa.String(4000), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
    )
    op.create_index("ix_desktop_exchange_codes_code", "desktop_exchange_codes", ["code"])


def downgrade() -> None:
    op.drop_index("ix_desktop_exchange_codes_code", table_name="desktop_exchange_codes")
    op.drop_table("desktop_exchange_codes")
