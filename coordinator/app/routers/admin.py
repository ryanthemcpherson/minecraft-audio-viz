"""Admin endpoints for site-wide management."""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_session
from app.dependencies.auth import require_admin
from app.models.db import (
    DJProfile,
    Organization,
    Show,
    User,
    VJServer,
)
from app.models.schemas import (
    AdminOrgRow,
    AdminServerRow,
    AdminShowRow,
    AdminStats,
    AdminUpdateUserRequest,
    AdminUserRow,
)

router = APIRouter(prefix="/admin", tags=["admin"])


# ---------------------------------------------------------------------------
# GET /admin/stats
# ---------------------------------------------------------------------------


@router.get("/stats", response_model=AdminStats, summary="Site-wide statistics")
async def admin_stats(
    _admin: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> AdminStats:
    """Return aggregate statistics: total users, orgs, servers, shows, and
    new users in the last 30 days.  Admin-only.
    """
    now = datetime.now(timezone.utc)
    thirty_days_ago = now - timedelta(days=30)

    total_users = (await session.execute(select(func.count(User.id)))).scalar_one()
    total_orgs = (await session.execute(select(func.count(Organization.id)))).scalar_one()
    total_servers = (await session.execute(select(func.count(VJServer.id)))).scalar_one()
    total_shows = (await session.execute(select(func.count(Show.id)))).scalar_one()
    active_shows = (
        await session.execute(select(func.count(Show.id)).where(Show.status == "active"))
    ).scalar_one()
    users_last_30 = (
        await session.execute(select(func.count(User.id)).where(User.created_at >= thirty_days_ago))
    ).scalar_one()
    dj_profiles = (await session.execute(select(func.count(DJProfile.id)))).scalar_one()

    return AdminStats(
        total_users=total_users,
        total_organizations=total_orgs,
        total_servers=total_servers,
        total_shows=total_shows,
        active_shows=active_shows,
        users_last_30_days=users_last_30,
        dj_profiles=dj_profiles,
    )


# ---------------------------------------------------------------------------
# GET /admin/users
# ---------------------------------------------------------------------------


@router.get("/users", response_model=list[AdminUserRow], summary="List all users")
async def list_users(
    _admin: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    search: str | None = Query(None),
) -> list[AdminUserRow]:
    """List all users with optional search by display name, email, or Discord
    username.  Supports pagination.  Admin-only.
    """
    stmt = (
        select(User)
        .options(selectinload(User.org_memberships), selectinload(User.dj_profile))
        .order_by(User.created_at.desc())
        .limit(limit)
        .offset(offset)
    )

    if search:
        # Escape SQL wildcard characters in user input
        escaped = search.replace("%", r"\%").replace("_", r"\_")
        pattern = f"%{escaped}%"
        stmt = stmt.where(
            User.display_name.ilike(pattern)
            | User.email.ilike(pattern)
            | User.discord_username.ilike(pattern)
        )

    result = await session.execute(stmt)
    users = result.scalars().all()

    return [
        AdminUserRow(
            id=u.id,
            display_name=u.display_name,
            email=u.email,
            discord_username=u.discord_username,
            avatar_url=u.avatar_url,
            is_active=u.is_active,
            is_admin=u.is_admin,
            user_type=u.user_type,
            created_at=u.created_at,
            last_login_at=u.last_login_at,
            org_count=len(u.org_memberships),
            has_dj_profile=u.dj_profile is not None,
        )
        for u in users
    ]


# ---------------------------------------------------------------------------
# PATCH /admin/users/{user_id}
# ---------------------------------------------------------------------------


@router.patch(
    "/users/{user_id}",
    response_model=AdminUserRow,
    summary="Update user (activate/deactivate/admin)",
)
async def update_user(
    user_id: uuid.UUID,
    body: AdminUpdateUserRequest,
    admin: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> AdminUserRow:
    """Update a user's active/admin status.  Admin-only.  Prevents admins
    from removing their own admin access or deactivating themselves.
    """
    stmt = (
        select(User)
        .where(User.id == user_id)
        .options(selectinload(User.org_memberships), selectinload(User.dj_profile))
    )
    result = await session.execute(stmt)
    user = result.scalar_one_or_none()

    if user is None:
        raise HTTPException(status_code=404, detail="User not found")

    # Prevent admin from removing their own admin access or deactivating themselves
    if user.id == admin.id:
        if body.is_admin is False:
            raise HTTPException(status_code=400, detail="Cannot remove your own admin access")
        if body.is_active is False:
            raise HTTPException(status_code=400, detail="Cannot deactivate your own account")

    if body.is_active is not None:
        user.is_active = body.is_active
    if body.is_admin is not None:
        user.is_admin = body.is_admin

    await session.commit()

    return AdminUserRow(
        id=user.id,
        display_name=user.display_name,
        email=user.email,
        discord_username=user.discord_username,
        avatar_url=user.avatar_url,
        is_active=user.is_active,
        is_admin=user.is_admin,
        user_type=user.user_type,
        created_at=user.created_at,
        last_login_at=user.last_login_at,
        org_count=len(user.org_memberships),
        has_dj_profile=user.dj_profile is not None,
    )


# ---------------------------------------------------------------------------
# GET /admin/organizations
# ---------------------------------------------------------------------------


@router.get(
    "/organizations",
    response_model=list[AdminOrgRow],
    summary="List all organizations",
)
async def list_organizations(
    _admin: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
) -> list[AdminOrgRow]:
    """List all organizations with owner, member count, and server count.
    Supports pagination.  Admin-only.
    """
    stmt = (
        select(Organization)
        .options(
            selectinload(Organization.owner),
            selectinload(Organization.members),
            selectinload(Organization.servers),
        )
        .order_by(Organization.created_at.desc())
        .limit(limit)
        .offset(offset)
    )
    result = await session.execute(stmt)
    orgs = result.scalars().all()

    return [
        AdminOrgRow(
            id=o.id,
            name=o.name,
            slug=o.slug,
            owner_name=o.owner.display_name if o.owner else "Unknown",
            member_count=len(o.members),
            server_count=len(o.servers),
            is_active=o.is_active,
            created_at=o.created_at,
        )
        for o in orgs
    ]


# ---------------------------------------------------------------------------
# GET /admin/servers
# ---------------------------------------------------------------------------


@router.get(
    "/servers",
    response_model=list[AdminServerRow],
    summary="List all servers",
)
async def list_servers(
    _admin: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
) -> list[AdminServerRow]:
    """List all VJ servers with org association, heartbeat, and active show
    count.  Supports pagination.  Admin-only.
    """
    stmt = (
        select(VJServer)
        .options(
            selectinload(VJServer.organization),
            selectinload(VJServer.shows),
        )
        .order_by(VJServer.created_at.desc())
        .limit(limit)
        .offset(offset)
    )
    result = await session.execute(stmt)
    servers = result.scalars().all()

    return [
        AdminServerRow(
            id=s.id,
            name=s.name,
            websocket_url=s.websocket_url,
            org_name=s.organization.name if s.organization else None,
            is_active=s.is_active,
            last_heartbeat=s.last_heartbeat,
            active_show_count=sum(1 for sh in s.shows if sh.status == "active"),
            created_at=s.created_at,
        )
        for s in servers
    ]


# ---------------------------------------------------------------------------
# GET /admin/shows
# ---------------------------------------------------------------------------


@router.get(
    "/shows",
    response_model=list[AdminShowRow],
    summary="List all shows",
)
async def list_shows(
    _admin: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    status: str | None = Query(None),
) -> list[AdminShowRow]:
    """List all shows with optional status filter (``active``, ``ended``).
    Supports pagination.  Admin-only.
    """
    stmt = (
        select(Show)
        .options(selectinload(Show.server))
        .order_by(Show.created_at.desc())
        .limit(limit)
        .offset(offset)
    )

    if status:
        stmt = stmt.where(Show.status == status)

    result = await session.execute(stmt)
    shows = result.scalars().all()

    return [
        AdminShowRow(
            id=sh.id,
            name=sh.name,
            server_name=sh.server.name if sh.server else "Unknown",
            connect_code=sh.connect_code,
            status=sh.status,
            current_djs=sh.current_djs,
            max_djs=sh.max_djs,
            created_at=sh.created_at,
            ended_at=sh.ended_at,
        )
        for sh in shows
    ]
