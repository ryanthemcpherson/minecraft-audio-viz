"""Add onboarding fields to users, create org_invites and dj_profiles tables.

Revision ID: 003
Revises: 002
Create Date: 2026-02-09
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "003"
down_revision: str = "002"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    # -- Add onboarding columns to users ---------------------------------------
    op.add_column(
        "users",
        sa.Column("onboarding_completed_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "users",
        sa.Column("user_type", sa.String(20), nullable=True),
    )

    # Backfill existing users so they skip onboarding
    op.execute("UPDATE users SET onboarding_completed_at = created_at")

    # -- org_invites -----------------------------------------------------------
    op.create_table(
        "org_invites",
        sa.Column("id", sa.Uuid, primary_key=True),
        sa.Column("org_id", sa.Uuid, sa.ForeignKey("organizations.id"), nullable=False),
        sa.Column("code", sa.String(8), unique=True, nullable=False),
        sa.Column("created_by", sa.Uuid, sa.ForeignKey("users.id"), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("max_uses", sa.Integer, nullable=False, server_default=sa.text("0")),
        sa.Column("use_count", sa.Integer, nullable=False, server_default=sa.text("0")),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.text("true")),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
    )
    op.create_index("ix_org_invites_code", "org_invites", ["code"], unique=True)

    # -- dj_profiles -----------------------------------------------------------
    op.create_table(
        "dj_profiles",
        sa.Column("id", sa.Uuid, primary_key=True),
        sa.Column("user_id", sa.Uuid, sa.ForeignKey("users.id"), unique=True, nullable=False),
        sa.Column("dj_name", sa.String(100), nullable=False),
        sa.Column("bio", sa.String(500), nullable=True),
        sa.Column("genres", sa.String(500), nullable=True),
        sa.Column("avatar_url", sa.String(500), nullable=True),
        sa.Column("is_public", sa.Boolean, nullable=False, server_default=sa.text("true")),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
    )
    op.create_index("ix_dj_profiles_user_id", "dj_profiles", ["user_id"], unique=True)


def downgrade() -> None:
    op.drop_table("dj_profiles")
    op.drop_table("org_invites")
    op.drop_column("users", "user_type")
    op.drop_column("users", "onboarding_completed_at")
