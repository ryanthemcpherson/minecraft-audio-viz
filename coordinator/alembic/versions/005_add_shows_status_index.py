"""Add index on shows.status for faster active show queries.

Revision ID: 005
Revises: 004
Create Date: 2026-02-11
"""

from __future__ import annotations

from alembic import op

# revision identifiers, used by Alembic.
revision: str = "005"
down_revision: str = "004"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.create_index("ix_shows_status", "shows", ["status"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_shows_status", table_name="shows")
