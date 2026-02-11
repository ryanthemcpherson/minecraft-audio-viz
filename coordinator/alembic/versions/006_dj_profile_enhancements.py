"""Add banner_url, color_palette, slug to dj_profiles.

Revision ID: 006
Revises: 005
Create Date: 2026-02-11
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "006"
down_revision: str = "005"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column("dj_profiles", sa.Column("banner_url", sa.String(500), nullable=True))
    op.add_column("dj_profiles", sa.Column("color_palette", sa.String(500), nullable=True))
    op.add_column("dj_profiles", sa.Column("slug", sa.String(60), nullable=True))
    op.create_index("ix_dj_profiles_slug", "dj_profiles", ["slug"], unique=True)


def downgrade() -> None:
    op.drop_index("ix_dj_profiles_slug", table_name="dj_profiles")
    op.drop_column("dj_profiles", "slug")
    op.drop_column("dj_profiles", "color_palette")
    op.drop_column("dj_profiles", "banner_url")
