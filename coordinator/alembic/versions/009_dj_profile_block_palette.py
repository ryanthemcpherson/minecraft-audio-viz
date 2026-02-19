"""Add block_palette to dj_profiles.

Revision ID: 009
Revises: 008
Create Date: 2026-02-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "009"
down_revision: str = "008"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column("dj_profiles", sa.Column("block_palette", sa.String(500), nullable=True))


def downgrade() -> None:
    op.drop_column("dj_profiles", "block_palette")
