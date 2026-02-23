"""Add nonce column to desktop_exchange_codes for polling-based OAuth.

The DJ client polls for auth completion using the nonce as a poll token,
replacing the unreliable deep-link redirect flow.

Revision ID: 015
Revises: 014
Create Date: 2026-02-23
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "015"
down_revision: str = "014"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column(
        "desktop_exchange_codes",
        sa.Column("nonce", sa.String(64), nullable=True),
    )
    op.create_index(
        "ix_desktop_exchange_codes_nonce",
        "desktop_exchange_codes",
        ["nonce"],
    )


def downgrade() -> None:
    op.drop_index("ix_desktop_exchange_codes_nonce", table_name="desktop_exchange_codes")
    op.drop_column("desktop_exchange_codes", "nonce")
