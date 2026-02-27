"""Add DB constraints for show DJ count invariants.

Revision ID: 016
Revises: 015
Create Date: 2026-02-24
"""

from __future__ import annotations

from alembic import op

revision: str = "016"
down_revision: str = "015"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    with op.batch_alter_table("shows") as batch_op:
        batch_op.create_check_constraint("ck_shows_max_djs_ge_1", "max_djs >= 1")
        batch_op.create_check_constraint("ck_shows_current_djs_ge_0", "current_djs >= 0")
        batch_op.create_check_constraint(
            "ck_shows_current_djs_le_max_djs", "current_djs <= max_djs"
        )


def downgrade() -> None:
    with op.batch_alter_table("shows") as batch_op:
        batch_op.drop_constraint("ck_shows_current_djs_le_max_djs", type_="check")
        batch_op.drop_constraint("ck_shows_current_djs_ge_0", type_="check")
        batch_op.drop_constraint("ck_shows_max_djs_ge_1", type_="check")
