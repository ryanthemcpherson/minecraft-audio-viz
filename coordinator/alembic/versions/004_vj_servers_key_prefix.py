"""Add key_prefix column to vj_servers for fast API key lookup.

Revision ID: 004
Revises: 003
Create Date: 2026-02-11
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "004"
down_revision: str = "003"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column("vj_servers", sa.Column("key_prefix", sa.String(16), nullable=True))
    op.create_index("ix_vj_servers_key_prefix", "vj_servers", ["key_prefix"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_vj_servers_key_prefix", table_name="vj_servers")
    op.drop_column("vj_servers", "key_prefix")
