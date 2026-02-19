"""Add is_admin flag to users table.

Revision ID: 008
Revises: 007
Create Date: 2026-02-17
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "008"
down_revision: str = "007"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column(
        "users",
        sa.Column("is_admin", sa.Boolean(), nullable=False, server_default="false"),
    )
    # Set the papa_johns_official Discord account as the initial site admin
    op.execute("UPDATE users SET is_admin = true WHERE discord_username = 'papa_johns_official'")


def downgrade() -> None:
    op.drop_column("users", "is_admin")
