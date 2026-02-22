"""Add user_id FK to dj_sessions table.

Links DJ sessions to coordinator user accounts so we can look up
the DJ's profile (avatar, colors, blocks) when they connect.
Nullable because anonymous DJs without coordinator accounts can still connect.

Revision ID: 014
Revises: 013
Create Date: 2026-02-22
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "014"
down_revision: str = "013"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column(
        "dj_sessions",
        sa.Column("user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("dj_sessions", "user_id")
