"""Add social link fields to dj_profiles.

Revision ID: 007
Revises: 006
Create Date: 2026-02-12
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "007"
down_revision: str = "006"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column("dj_profiles", sa.Column("soundcloud_url", sa.String(500), nullable=True))
    op.add_column("dj_profiles", sa.Column("spotify_url", sa.String(500), nullable=True))
    op.add_column("dj_profiles", sa.Column("website_url", sa.String(500), nullable=True))


def downgrade() -> None:
    op.drop_column("dj_profiles", "website_url")
    op.drop_column("dj_profiles", "spotify_url")
    op.drop_column("dj_profiles", "soundcloud_url")
