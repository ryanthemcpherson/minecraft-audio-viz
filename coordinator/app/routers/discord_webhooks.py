"""Discord webhook endpoints for community bot role sync."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.models.db import RoleSource, RoleType, User, UserRole
from app.models.schemas import (
    DiscordRoleSyncRequest,
    UserRoleResponse,
    UserRolesResponse,
)

_logger = logging.getLogger(__name__)

router = APIRouter(prefix="/webhooks/discord", tags=["discord-webhooks"])


# ---------------------------------------------------------------------------
# Auth dependency
# ---------------------------------------------------------------------------


async def _verify_secret(
    x_webhook_secret: str | None = Header(None),
    settings: Settings = Depends(get_settings),
) -> None:
    """Verify X-Webhook-Secret header matches the configured secret."""
    if not settings.discord_webhook_secret:
        raise HTTPException(status_code=503, detail="Webhook secret not configured")
    if not x_webhook_secret or x_webhook_secret != settings.discord_webhook_secret:
        raise HTTPException(status_code=401, detail="Invalid or missing webhook secret")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_by_discord_id(session: AsyncSession, discord_id: str) -> User:
    """Look up a user by discord_id or raise 404."""
    result = await session.execute(select(User).where(User.discord_id == discord_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=404, detail="User not found for discord_id")
    return user


async def _get_user_roles(session: AsyncSession, user_id) -> list[UserRole]:
    """Fetch all roles for a user."""
    result = await session.execute(select(UserRole).where(UserRole.user_id == user_id))
    return list(result.scalars().all())


def _roles_response(user_id, roles: list[UserRole]) -> UserRolesResponse:
    """Build a UserRolesResponse from a list of UserRole ORM objects."""
    return UserRolesResponse(
        user_id=user_id,
        roles=[
            UserRoleResponse(
                role=r.role,
                source=r.source,
                created_at=r.created_at,
            )
            for r in roles
        ],
    )


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.post(
    "/role-sync",
    response_model=UserRolesResponse,
    summary="Bot pushes role changes from Discord (union merge)",
)
async def role_sync(
    body: DiscordRoleSyncRequest,
    _secret: None = Depends(_verify_secret),
    session: AsyncSession = Depends(get_session),
) -> UserRolesResponse:
    user = await _get_user_by_discord_id(session, body.discord_id)

    existing_roles = await _get_user_roles(session, user.id)
    existing_map: dict[RoleType, UserRole] = {r.role: r for r in existing_roles}

    for role_type in body.roles:
        if role_type in existing_map:
            # If existing role was from COORDINATOR, upgrade source to BOTH
            existing_role = existing_map[role_type]
            if existing_role.source == RoleSource.COORDINATOR:
                existing_role.source = RoleSource.BOTH
        else:
            new_role = UserRole(
                user_id=user.id,
                role=role_type,
                source=RoleSource.DISCORD,
            )
            session.add(new_role)

    await session.commit()

    roles = await _get_user_roles(session, user.id)
    return _roles_response(user.id, roles)


@router.delete(
    "/role-sync/{discord_id}/{role}",
    response_model=UserRolesResponse,
    summary="Bot notifies of explicit role removal",
)
async def delete_role(
    discord_id: str,
    role: RoleType,
    _secret: None = Depends(_verify_secret),
    session: AsyncSession = Depends(get_session),
) -> UserRolesResponse:
    user = await _get_user_by_discord_id(session, discord_id)

    result = await session.execute(
        select(UserRole).where(
            UserRole.user_id == user.id,
            UserRole.role == role,
        )
    )
    user_role = result.scalar_one_or_none()
    if user_role is None:
        raise HTTPException(status_code=404, detail="Role not assigned to user")

    await session.delete(user_role)
    await session.commit()

    roles = await _get_user_roles(session, user.id)
    return _roles_response(user.id, roles)


@router.get(
    "/users/{discord_id}",
    summary="Lookup user by Discord ID",
)
async def get_user_by_discord_id(
    discord_id: str,
    _secret: None = Depends(_verify_secret),
    session: AsyncSession = Depends(get_session),
) -> dict:
    user = await _get_user_by_discord_id(session, discord_id)
    roles = await _get_user_roles(session, user.id)

    return {
        "user_id": str(user.id),
        "discord_id": user.discord_id,
        "discord_username": user.discord_username,
        "roles": [
            {
                "role": r.role.value,
                "source": r.source.value,
                "created_at": r.created_at.isoformat(),
            }
            for r in roles
        ],
    }
