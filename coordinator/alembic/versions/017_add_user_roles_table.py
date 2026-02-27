"""Add user_roles table for multi-role user system.

Replaces the single user_type string with a proper many-to-many
role mapping. Each user can hold multiple roles (dj, server_owner,
vj, developer, beta_tester) from different sources (discord,
coordinator, both).

Revision ID: 017
Revises: 016
Create Date: 2026-02-26
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "017"
down_revision: str = "016"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.create_table(
        "user_roles",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "role",
            sa.Enum("dj", "server_owner", "vj", "developer", "beta_tester", name="roletype"),
            nullable=False,
        ),
        sa.Column(
            "source",
            sa.Enum("discord", "coordinator", "both", name="rolesource"),
            nullable=False,
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("user_id", "role", name="uq_user_roles_user_role"),
    )


def downgrade() -> None:
    op.drop_table("user_roles")
    # Drop the enum types on PostgreSQL (no-op on SQLite)
    bind = op.get_bind()
    if bind.dialect.name == "postgresql":
        sa.Enum(name="roletype").drop(bind)
        sa.Enum(name="rolesource").drop(bind)
