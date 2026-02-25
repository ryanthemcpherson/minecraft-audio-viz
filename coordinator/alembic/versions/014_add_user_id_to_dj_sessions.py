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
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        with op.batch_alter_table("dj_sessions", recreate="always") as batch_op:
            batch_op.add_column(sa.Column("user_id", sa.Uuid(), nullable=True))
            batch_op.create_foreign_key(
                "fk_dj_sessions_user_id_users",
                "users",
                ["user_id"],
                ["id"],
            )
    else:
        op.add_column(
            "dj_sessions",
            sa.Column("user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=True),
        )


def downgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        with op.batch_alter_table("dj_sessions", recreate="always") as batch_op:
            batch_op.drop_constraint("fk_dj_sessions_user_id_users", type_="foreignkey")
            batch_op.drop_column("user_id")
    else:
        op.drop_column("dj_sessions", "user_id")
