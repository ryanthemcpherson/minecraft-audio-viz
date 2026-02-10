"""FastAPI dependencies for user authentication."""

from __future__ import annotations

import uuid

from fastapi import Depends, Header, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.config import Settings, get_settings
from app.database import get_session
from app.models.db import OrgMember, User
from app.services.user_jwt import verify_user_token


async def get_current_user(
    authorization: str = Header(..., description="Bearer <access_token>"),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> User:
    """Dependency that extracts and verifies a user-session JWT, returning the ``User``."""
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid Authorization header format")

    token = authorization[len("Bearer ") :]
    try:
        payload = verify_user_token(token, jwt_secret=settings.user_jwt_secret)
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid or expired token")

    stmt = (
        select(User)
        .where(User.id == uuid.UUID(payload.sub), User.is_active.is_(True))
        .options(
            selectinload(User.org_memberships).selectinload(OrgMember.organization),
            selectinload(User.dj_profile),
        )
    )
    result = await session.execute(stmt)
    user = result.scalar_one_or_none()

    if user is None:
        raise HTTPException(status_code=401, detail="User not found or inactive")

    return user


async def get_current_user_optional(
    authorization: str | None = Header(None),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> User | None:
    """Like ``get_current_user`` but returns ``None`` instead of 401 when no token is present."""
    if authorization is None:
        return None
    try:
        return await get_current_user(authorization, session, settings)
    except HTTPException:
        return None


async def require_org_owner(
    org_id: uuid.UUID,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> User:
    """Dependency that verifies the current user is an owner of the given org."""
    stmt = select(OrgMember).where(
        OrgMember.user_id == user.id,
        OrgMember.org_id == org_id,
        OrgMember.role == "owner",
    )
    result = await session.execute(stmt)
    membership = result.scalar_one_or_none()

    if membership is None:
        raise HTTPException(status_code=403, detail="Not an owner of this organization")

    return user
