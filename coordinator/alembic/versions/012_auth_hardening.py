"""Auth hardening: email verification, account lockout, session metadata.

Revision ID: 012
Revises: 011
Create Date: 2026-02-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "012"
down_revision: str = "011"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    # --- Users: email verification + account lockout ---
    op.add_column(
        "users",
        sa.Column("email_verified", sa.Boolean(), server_default=sa.text("false"), nullable=False),
    )
    op.add_column(
        "users",
        sa.Column(
            "failed_login_attempts", sa.Integer(), server_default=sa.text("0"), nullable=False
        ),
    )
    op.add_column("users", sa.Column("locked_until", sa.DateTime(timezone=True), nullable=True))

    # --- Email verification tokens ---
    op.create_table(
        "email_verification_tokens",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("token_hash", sa.String(128), nullable=False, unique=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
    )

    # --- Refresh tokens: session metadata ---
    op.add_column("refresh_tokens", sa.Column("user_agent", sa.String(500), nullable=True))
    op.add_column("refresh_tokens", sa.Column("ip_address", sa.String(45), nullable=True))
    op.add_column(
        "refresh_tokens", sa.Column("last_used_at", sa.DateTime(timezone=True), nullable=True)
    )


def downgrade() -> None:
    op.drop_column("refresh_tokens", "last_used_at")
    op.drop_column("refresh_tokens", "ip_address")
    op.drop_column("refresh_tokens", "user_agent")
    op.drop_table("email_verification_tokens")
    op.drop_column("users", "locked_until")
    op.drop_column("users", "failed_login_attempts")
    op.drop_column("users", "email_verified")
