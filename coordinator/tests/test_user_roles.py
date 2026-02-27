"""Tests for the UserRole model and user_roles table.

Validates multi-role assignment and unique constraint enforcement.
"""

from __future__ import annotations

import uuid

import pytest
from app.models.db import RoleSource, RoleType, User, UserRole
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload


@pytest.mark.asyncio
async def test_user_can_have_multiple_roles(db_session: AsyncSession) -> None:
    """A user should be able to hold DJ and SERVER_OWNER roles simultaneously."""
    user = User(
        id=uuid.uuid4(),
        display_name="Multi-role User",
        email="multi@example.com",
    )
    db_session.add(user)
    await db_session.flush()

    role_dj = UserRole(
        user_id=user.id,
        role=RoleType.DJ,
        source=RoleSource.DISCORD,
    )
    role_owner = UserRole(
        user_id=user.id,
        role=RoleType.SERVER_OWNER,
        source=RoleSource.COORDINATOR,
    )
    db_session.add_all([role_dj, role_owner])
    await db_session.flush()

    # Re-query with eager load to verify both roles persisted
    result = await db_session.execute(
        select(User).where(User.id == user.id).options(selectinload(User.roles))
    )
    fetched_user = result.scalar_one()
    role_names = {r.role for r in fetched_user.roles}
    assert role_names == {RoleType.DJ, RoleType.SERVER_OWNER}


@pytest.mark.asyncio
async def test_duplicate_role_rejected(db_session: AsyncSession) -> None:
    """Adding the same role twice for a user should raise IntegrityError."""
    user = User(
        id=uuid.uuid4(),
        display_name="Dup Role User",
        email="dup@example.com",
    )
    db_session.add(user)
    await db_session.flush()

    role1 = UserRole(
        user_id=user.id,
        role=RoleType.DJ,
        source=RoleSource.DISCORD,
    )
    db_session.add(role1)
    await db_session.flush()

    role2 = UserRole(
        user_id=user.id,
        role=RoleType.DJ,
        source=RoleSource.COORDINATOR,
    )
    db_session.add(role2)
    with pytest.raises(IntegrityError):
        await db_session.flush()
