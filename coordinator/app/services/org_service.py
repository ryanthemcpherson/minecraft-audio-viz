"""Organization CRUD service layer.

Extracted from ``app.routers.orgs`` to decouple DB queries from routing.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.db import Organization, OrgInvite, OrgMember, VJServer


async def get_org_by_slug(
    session: AsyncSession, slug: str, *, active_only: bool = True
) -> Organization | None:
    """Fetch an organization by its URL slug."""
    stmt = select(Organization).where(Organization.slug == slug.lower().strip())
    if active_only:
        stmt = stmt.where(Organization.is_active.is_(True))
    return (await session.execute(stmt)).scalar_one_or_none()


async def get_org_by_id(
    session: AsyncSession, org_id: uuid.UUID, *, active_only: bool = True
) -> Organization | None:
    """Fetch an organization by ID."""
    stmt = select(Organization).where(Organization.id == org_id)
    if active_only:
        stmt = stmt.where(Organization.is_active.is_(True))
    return (await session.execute(stmt)).scalar_one_or_none()


async def get_membership(
    session: AsyncSession, user_id: uuid.UUID, org_id: uuid.UUID
) -> OrgMember | None:
    """Check whether a user is a member of an org."""
    stmt = select(OrgMember).where(OrgMember.user_id == user_id, OrgMember.org_id == org_id)
    return (await session.execute(stmt)).scalar_one_or_none()


async def check_slug_available(session: AsyncSession, slug: str) -> bool:
    """Return True if the slug is not taken by any existing org."""
    existing = await get_org_by_slug(session, slug, active_only=False)
    return existing is None


async def create_org(
    session: AsyncSession,
    *,
    name: str,
    slug: str,
    owner_id: uuid.UUID,
    description: str | None = None,
) -> Organization:
    """Create a new organization and add the owner as a member."""
    org = Organization(
        id=uuid.uuid4(),
        name=name,
        slug=slug,
        owner_id=owner_id,
        description=description,
    )
    session.add(org)

    membership = OrgMember(
        id=uuid.uuid4(),
        user_id=owner_id,
        org_id=org.id,
        role="owner",
    )
    session.add(membership)

    await session.flush()
    return org


async def update_org(
    session: AsyncSession,
    org: Organization,
    *,
    name: str | None = None,
    description: str | None = None,
    avatar_url: str | None = None,
) -> Organization:
    """Update mutable fields on an organization."""
    if name is not None:
        org.name = name
    if description is not None:
        org.description = description
    if avatar_url is not None:
        org.avatar_url = avatar_url
    await session.flush()
    return org


async def assign_server_to_org(
    session: AsyncSession, server_id: uuid.UUID, org_id: uuid.UUID
) -> VJServer | None:
    """Assign a server to an org. Returns the server or None if not found."""
    server = (
        await session.execute(select(VJServer).where(VJServer.id == server_id))
    ).scalar_one_or_none()
    if server is not None:
        server.org_id = org_id
    return server


async def list_org_servers(
    session: AsyncSession,
    org_id: uuid.UUID,
    *,
    limit: int = 50,
    offset: int = 0,
) -> list[VJServer]:
    """List VJ servers belonging to an org, eagerly loading shows."""
    stmt = (
        select(VJServer)
        .where(VJServer.org_id == org_id)
        .options(selectinload(VJServer.shows))
        .limit(limit)
        .offset(offset)
    )
    return list((await session.execute(stmt)).scalars().all())


async def remove_server_from_org(
    session: AsyncSession, server_id: uuid.UUID, org_id: uuid.UUID
) -> VJServer | None:
    """Unlink a server from an org. Returns the server or None if not found."""
    server = (
        await session.execute(
            select(VJServer).where(VJServer.id == server_id, VJServer.org_id == org_id)
        )
    ).scalar_one_or_none()
    if server is not None:
        server.org_id = None
    return server


async def create_invite(
    session: AsyncSession,
    *,
    org_id: uuid.UUID,
    code: str,
    created_by: uuid.UUID,
    expires_in_hours: int | None = None,
    max_uses: int = 0,
) -> OrgInvite:
    """Create an invite code for an organization."""
    expires_at = None
    if expires_in_hours is not None:
        expires_at = datetime.now(timezone.utc) + timedelta(hours=expires_in_hours)

    invite = OrgInvite(
        id=uuid.uuid4(),
        org_id=org_id,
        code=code,
        created_by=created_by,
        expires_at=expires_at,
        max_uses=max_uses,
    )
    session.add(invite)
    await session.flush()
    return invite


async def list_active_invites(
    session: AsyncSession,
    org_id: uuid.UUID,
    *,
    limit: int = 50,
    offset: int = 0,
) -> list[OrgInvite]:
    """List active invite codes for an organization."""
    stmt = (
        select(OrgInvite)
        .where(OrgInvite.org_id == org_id, OrgInvite.is_active.is_(True))
        .limit(limit)
        .offset(offset)
    )
    return list((await session.execute(stmt)).scalars().all())


async def deactivate_invite(
    session: AsyncSession, invite_id: uuid.UUID, org_id: uuid.UUID
) -> OrgInvite | None:
    """Deactivate an invite. Returns the invite or None if not found."""
    invite = (
        await session.execute(
            select(OrgInvite).where(OrgInvite.id == invite_id, OrgInvite.org_id == org_id)
        )
    ).scalar_one_or_none()
    if invite is not None:
        invite.is_active = False
    return invite


async def find_active_invite_by_code(session: AsyncSession, code: str) -> OrgInvite | None:
    """Find an active invite by its code (case-insensitive)."""
    stmt = select(OrgInvite).where(
        OrgInvite.code == code.upper(),
        OrgInvite.is_active.is_(True),
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def join_org_with_invite(
    session: AsyncSession,
    *,
    user_id: uuid.UUID,
    invite: OrgInvite,
) -> OrgMember:
    """Create a membership and increment the invite's use count.

    Caller is responsible for checking expiry, max_uses, and duplicate membership
    before calling this function.
    """
    membership = OrgMember(
        id=uuid.uuid4(),
        user_id=user_id,
        org_id=invite.org_id,
        role="member",
    )
    session.add(membership)

    # Atomically increment use count
    await session.execute(
        update(OrgInvite).where(OrgInvite.id == invite.id).values(use_count=OrgInvite.use_count + 1)
    )

    await session.flush()
    return membership


async def get_org_for_invite(session: AsyncSession, org_id: uuid.UUID) -> Organization | None:
    """Fetch the org associated with an invite."""
    return (
        await session.execute(select(Organization).where(Organization.id == org_id))
    ).scalar_one()
