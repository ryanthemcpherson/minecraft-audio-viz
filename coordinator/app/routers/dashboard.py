"""Dashboard summary endpoint — returns role-specific data for the logged-in user."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import (
    DJSession,
    Organization,
    OrgInvite,
    OrgMember,
    Show,
    User,
    VJServer,
)
from app.models.schemas import (
    DJDashboardData,
    DJDashboardSection,
    GenericDashboard,
    OrgDashboardSummary,
    RecentShowSummary,
    ServerOwnerChecklist,
    ServerOwnerDashboard,
    TeamMemberDashboard,
    UnifiedDashboard,
)

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


def _org_summaries(user: User) -> list[OrgDashboardSummary]:
    """Build org summary list from eagerly-loaded relationships."""
    summaries: list[OrgDashboardSummary] = []
    for mem in user.org_memberships:
        org = mem.organization
        if org is None:
            continue
        active_shows = 0
        for srv in org.servers:
            active_shows += sum(1 for s in srv.shows if s.status == "active")
        summaries.append(
            OrgDashboardSummary(
                id=org.id,
                name=org.name,
                slug=org.slug,
                role=mem.role,
                server_count=len(org.servers),
                member_count=len(org.members),
                active_show_count=active_shows,
            )
        )
    return summaries


def _recent_shows(user: User, *, active_only: bool = False) -> list[RecentShowSummary]:
    """Collect shows from user's orgs, optionally filtering to active only."""
    shows: list[RecentShowSummary] = []
    for mem in user.org_memberships:
        if mem.organization is None:
            continue
        for srv in mem.organization.servers:
            for show in srv.shows:
                if active_only and show.status != "active":
                    continue
                shows.append(
                    RecentShowSummary(
                        id=show.id,
                        name=show.name,
                        server_name=srv.name,
                        connect_code=show.connect_code,
                        status=show.status,
                        current_djs=show.current_djs,
                        created_at=show.created_at,
                    )
                )
    shows.sort(key=lambda s: s.created_at, reverse=True)
    return shows[:10]


@router.get(
    "/summary",
    response_model=ServerOwnerDashboard | TeamMemberDashboard | DJDashboardData | GenericDashboard,
    summary="Role-specific dashboard data for the current user",
)
async def dashboard_summary(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> ServerOwnerDashboard | TeamMemberDashboard | DJDashboardData | GenericDashboard:
    # Re-fetch user with deep relationship loading to avoid sync lazy-load errors.
    stmt = (
        select(User)
        .where(User.id == user.id)
        .options(
            selectinload(User.org_memberships)
            .selectinload(OrgMember.organization)
            .selectinload(Organization.servers)
            .selectinload(VJServer.shows),
            selectinload(User.org_memberships)
            .selectinload(OrgMember.organization)
            .selectinload(Organization.members),
            selectinload(User.dj_profile),
        )
    )
    result = await session.execute(stmt)
    user = result.scalar_one()

    orgs = _org_summaries(user)

    if user.user_type == "server_owner":
        # Check if any org has invites
        org_ids = [m.org_id for m in user.org_memberships]
        invite_count = 0
        if org_ids:
            result = await session.execute(
                select(func.count()).select_from(OrgInvite).where(OrgInvite.org_id.in_(org_ids))
            )
            invite_count = result.scalar_one()

        has_server = any(o.server_count > 0 for o in orgs)
        has_show = any(o.active_show_count > 0 for o in orgs)

        return ServerOwnerDashboard(
            checklist=ServerOwnerChecklist(
                org_created=len(orgs) > 0,
                server_registered=has_server,
                invite_created=invite_count > 0,
                show_started=has_show,
            ),
            organizations=orgs,
            recent_shows=_recent_shows(user),
        )

    if user.user_type == "team_member":
        return TeamMemberDashboard(
            organizations=orgs,
            active_shows=_recent_shows(user, active_only=True),
        )

    if user.user_type == "dj":
        dj = user.dj_profile
        session_count = 0
        recent: list[RecentShowSummary] = []

        if dj:
            result = await session.execute(
                select(func.count()).select_from(DJSession).where(DJSession.dj_name == dj.dj_name)
            )
            session_count = result.scalar_one()

            # Recent sessions: join DJSession -> Show -> VJServer
            stmt = (
                select(DJSession, Show, VJServer)
                .join(Show, DJSession.show_id == Show.id)
                .join(VJServer, Show.server_id == VJServer.id)
                .where(DJSession.dj_name == dj.dj_name)
                .order_by(DJSession.connected_at.desc())
                .limit(10)
            )
            rows = await session.execute(stmt)
            for dj_sess, show, srv in rows:
                recent.append(
                    RecentShowSummary(
                        id=show.id,
                        name=show.name,
                        server_name=srv.name,
                        connect_code=show.connect_code,
                        status=show.status,
                        current_djs=show.current_djs,
                        created_at=dj_sess.connected_at,
                    )
                )

        return DJDashboardData(
            dj_name=dj.dj_name if dj else "Unknown",
            bio=dj.bio if dj else None,
            genres=dj.genres if dj else None,
            session_count=session_count,
            recent_sessions=recent,
        )

    # Generic / skipped
    return GenericDashboard(organizations=orgs)


@router.get(
    "/unified",
    response_model=UnifiedDashboard,
    summary="Unified dashboard data combining all user capabilities",
)
async def unified_dashboard(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UnifiedDashboard:
    # Eager-load relationships (same as dashboard_summary)
    stmt = (
        select(User)
        .where(User.id == user.id)
        .options(
            selectinload(User.org_memberships)
            .selectinload(OrgMember.organization)
            .selectinload(Organization.servers)
            .selectinload(VJServer.shows),
            selectinload(User.org_memberships)
            .selectinload(OrgMember.organization)
            .selectinload(Organization.members),
            selectinload(User.dj_profile),
        )
    )
    result = await session.execute(stmt)
    user = result.scalar_one()

    orgs = _org_summaries(user)
    recent_shows = _recent_shows(user)
    has_orgs = len(orgs) > 0

    # Checklist: build if user has orgs or user_type is server_owner
    checklist = None
    if has_orgs or user.user_type == "server_owner":
        org_ids = [m.org_id for m in user.org_memberships]
        invite_count = 0
        if org_ids:
            result = await session.execute(
                select(func.count()).select_from(OrgInvite).where(OrgInvite.org_id.in_(org_ids))
            )
            invite_count = result.scalar_one()

        has_server = any(o.server_count > 0 for o in orgs)
        has_show = any(o.active_show_count > 0 for o in orgs)

        checklist = ServerOwnerChecklist(
            org_created=has_orgs,
            server_registered=has_server,
            invite_created=invite_count > 0,
            show_started=has_show,
        )

    # DJ section
    dj_section = None
    dj = user.dj_profile
    has_dj_profile = dj is not None

    if dj:
        result = await session.execute(
            select(func.count()).select_from(DJSession).where(DJSession.dj_name == dj.dj_name)
        )
        session_count = result.scalar_one()

        dj_recent: list[RecentShowSummary] = []
        rows = await session.execute(
            select(DJSession, Show, VJServer)
            .join(Show, DJSession.show_id == Show.id)
            .join(VJServer, Show.server_id == VJServer.id)
            .where(DJSession.dj_name == dj.dj_name)
            .order_by(DJSession.connected_at.desc())
            .limit(10)
        )
        for dj_sess, show, srv in rows:
            dj_recent.append(
                RecentShowSummary(
                    id=show.id,
                    name=show.name,
                    server_name=srv.name,
                    connect_code=show.connect_code,
                    status=show.status,
                    current_djs=show.current_djs,
                    created_at=dj_sess.connected_at,
                )
            )

        dj_section = DJDashboardSection(
            dj_name=dj.dj_name,
            bio=dj.bio,
            genres=dj.genres,
            slug=dj.slug,
            soundcloud_url=dj.soundcloud_url,
            spotify_url=dj.spotify_url,
            website_url=dj.website_url,
            session_count=session_count,
            recent_sessions=dj_recent,
        )

    return UnifiedDashboard(
        user_type=user.user_type,
        checklist=checklist,
        organizations=orgs,
        recent_shows=recent_shows,
        dj=dj_section,
        has_dj_profile=has_dj_profile,
        has_orgs=has_orgs,
    )
