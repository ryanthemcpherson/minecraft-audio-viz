"""Migrate existing user_type data to user_roles table.

Copies user_type values into user_roles for users that have a
directly-mappable role (dj, server_owner, vj). Types "team_member"
and "generic" are skipped as they have no direct role equivalent.

The user_type column is NOT dropped — it stays for backwards
compatibility.

Revision ID: 018
Revises: 017
Create Date: 2026-02-26
"""

from __future__ import annotations

import uuid

import sqlalchemy as sa
from alembic import op

revision: str = "018"
down_revision: str = "017"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    conn = op.get_bind()

    type_to_role = {
        "dj": "dj",
        "server_owner": "server_owner",
        "vj": "vj",
    }

    users = conn.execute(
        sa.text("SELECT id, user_type FROM users WHERE user_type IS NOT NULL")
    ).fetchall()

    for user_id, user_type in users:
        role = type_to_role.get(user_type)
        if role:
            conn.execute(
                sa.text(
                    "INSERT INTO user_roles (id, user_id, role, source, created_at) "
                    "VALUES (:id, :user_id, :role, 'coordinator', NOW()) "
                    "ON CONFLICT (user_id, role) DO NOTHING"
                ),
                {"id": str(uuid.uuid4()), "user_id": str(user_id), "role": role},
            )


def downgrade() -> None:
    # Data migration — downgrade is a no-op (user_type column still exists)
    pass
