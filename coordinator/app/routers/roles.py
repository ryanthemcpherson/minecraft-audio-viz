"""User role CRUD endpoints."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import RoleSource, RoleType, User, UserRole
from app.models.schemas import UpdateRolesRequest, UserRoleResponse, UserRolesResponse
from app.services.discord_bot_notifier import notify_role_change

router = APIRouter(prefix="/users", tags=["roles"])


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


async def _get_user_roles(session: AsyncSession, user_id) -> list[UserRole]:
    """Fetch all roles for a user."""
    result = await session.execute(select(UserRole).where(UserRole.user_id == user_id))
    return list(result.scalars().all())


@router.get(
    "/me/roles",
    response_model=UserRolesResponse,
    summary="Get current user's roles",
)
async def get_my_roles(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserRolesResponse:
    """Return all roles assigned to the authenticated user, including the
    role source (coordinator, discord, or both).
    """
    roles = await _get_user_roles(session, user.id)
    return _roles_response(user.id, roles)


@router.put(
    "/me/roles",
    response_model=UserRolesResponse,
    summary="Add roles to current user (union — never removes existing)",
)
async def update_my_roles(
    body: UpdateRolesRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> UserRolesResponse:
    """Add roles to the authenticated user (union merge -- never removes
    existing roles).  Notifies the Discord community bot if the user has
    a linked Discord account.
    """
    # Get existing roles for this user
    existing_roles = await _get_user_roles(session, user.id)
    existing_role_types = {r.role for r in existing_roles}

    # Add only roles that don't already exist (union)
    for role_type in body.roles:
        if role_type not in existing_role_types:
            new_role = UserRole(
                user_id=user.id,
                role=role_type,
                source=RoleSource.COORDINATOR,
            )
            session.add(new_role)

    await session.commit()

    # Re-fetch to return the full updated list
    roles = await _get_user_roles(session, user.id)

    # Notify community bot (fire-and-forget)
    if user.discord_id:
        await notify_role_change(
            settings=settings,
            discord_id=user.discord_id,
            user_id=user.id,
            roles=[r.role.value for r in roles],
        )

    return _roles_response(user.id, roles)


@router.delete(
    "/me/roles/{role}",
    response_model=UserRolesResponse,
    summary="Remove a specific role from the current user",
)
async def delete_my_role(
    role: RoleType,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> UserRolesResponse:
    """Remove a specific role from the authenticated user.  Returns 404 if
    the role is not currently assigned.
    """
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

    # Re-fetch remaining roles
    roles = await _get_user_roles(session, user.id)

    # Notify community bot (fire-and-forget)
    if user.discord_id:
        await notify_role_change(
            settings=settings,
            discord_id=user.discord_id,
            user_id=user.id,
            roles=[r.role.value for r in roles],
        )

    return _roles_response(user.id, roles)
