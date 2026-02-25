"""Add google_id to users table.

Revision ID: 010
Revises: 009
Create Date: 2026-02-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "010"
down_revision: str = "009"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        with op.batch_alter_table("users", recreate="always") as batch_op:
            batch_op.add_column(sa.Column("google_id", sa.String(50), nullable=True))
            batch_op.create_unique_constraint("uq_users_google_id", ["google_id"])
    else:
        op.add_column("users", sa.Column("google_id", sa.String(50), nullable=True))
        op.create_unique_constraint("uq_users_google_id", "users", ["google_id"])


def downgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        with op.batch_alter_table("users", recreate="always") as batch_op:
            batch_op.drop_constraint("uq_users_google_id", type_="unique")
            batch_op.drop_column("google_id")
    else:
        op.drop_constraint("uq_users_google_id", "users", type_="unique")
        op.drop_column("users", "google_id")
