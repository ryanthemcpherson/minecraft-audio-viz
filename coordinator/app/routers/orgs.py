"""Organization CRUD and management endpoints."""

from __future__ import annotations

import re
import secrets as _secrets
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.dependencies.auth import get_current_user, require_org_owner
from app.models.db import Organization, OrgInvite, OrgMember, User, VJServer
from app.models.schemas import (
    AssignServerRequest,
    AssignServerResponse,
    CreateInviteRequest,
    CreateOrgRequest,
    CreateOrgResponse,
    InviteResponse,
    JoinOrgRequest,
    JoinOrgResponse,
    OrgDetailResponse,
    OrgServerDetailResponse,
    RegisterOrgServerRequest,
    RegisterOrgServerResponse,
    UpdateOrgRequest,
)
from app.services.code_generator import SAFE_CHARS
from app.services.password import hash_password

router = APIRouter(prefix="/orgs", tags=["organizations"])

RESERVED_SLUGS = frozenset(
    {
        "www",
        "api",
        "admin",
        "app",
        "dashboard",
        "mail",
        "ftp",
        "cdn",
        "docs",
        "help",
        "support",
        "status",
        "blog",
        "static",
        "assets",
        "auth",
        "login",
        "register",
        "signup",
        "account",
        "settings",
        "mcav",
        "minecraft",
        "test",
        "staging",
        "dev",
        "prod",
    }
)

SLUG_PATTERN = re.compile(r"^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$")


def _validate_slug(slug: str) -> str:
    """Validate and normalise an org slug."""
    slug = slug.lower().strip()
    if not SLUG_PATTERN.match(slug):
        raise HTTPException(
            status_code=400,
            detail="Slug must be 3-63 lowercase alphanumeric characters or hyphens, "
            "and cannot start or end with a hyphen",
        )
    if slug in RESERVED_SLUGS:
        raise HTTPException(status_code=400, detail=f"'{slug}' is a reserved name")
    return slug


# ---------------------------------------------------------------------------
# POST /orgs
# ---------------------------------------------------------------------------


@router.post(
    "",
    response_model=CreateOrgResponse,
    status_code=201,
    summary="Create a new organization",
)
async def create_org(
    body: CreateOrgRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> CreateOrgResponse:
    slug = _validate_slug(body.slug)

    # Check uniqueness
    existing = (
        await session.execute(select(Organization).where(Organization.slug == slug))
    ).scalar_one_or_none()
    if existing is not None:
        raise HTTPException(status_code=409, detail="Slug already taken")

    org = Organization(
        id=uuid.uuid4(),
        name=body.name,
        slug=slug,
        owner_id=user.id,
        description=body.description,
    )
    session.add(org)

    # Auto-create owner membership
    membership = OrgMember(
        id=uuid.uuid4(),
        user_id=user.id,
        org_id=org.id,
        role="owner",
    )
    session.add(membership)

    await session.commit()
    await session.refresh(org)

    return CreateOrgResponse(
        id=org.id,
        name=org.name,
        slug=org.slug,
        owner_id=org.owner_id,
        created_at=org.created_at,
    )


# ---------------------------------------------------------------------------
# GET /orgs/by-slug/{slug}
# ---------------------------------------------------------------------------


@router.get(
    "/by-slug/{slug}",
    response_model=OrgDetailResponse,
    summary="Resolve organization by slug",
)
async def get_org_by_slug(
    slug: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> OrgDetailResponse:
    org = (
        await session.execute(
            select(Organization).where(
                Organization.slug == slug.lower().strip(),
                Organization.is_active.is_(True),
            )
        )
    ).scalar_one_or_none()

    if org is None:
        raise HTTPException(status_code=404, detail="Organization not found")

    membership = (
        await session.execute(
            select(OrgMember).where(OrgMember.user_id == user.id, OrgMember.org_id == org.id)
        )
    ).scalar_one_or_none()

    if membership is None:
        raise HTTPException(status_code=403, detail="Not a member of this organization")

    return OrgDetailResponse(
        id=org.id,
        name=org.name,
        slug=org.slug,
        description=org.description,
        avatar_url=org.avatar_url,
        owner_id=org.owner_id,
        created_at=org.created_at,
    )


# ---------------------------------------------------------------------------
# GET /orgs/{org_id}
# ---------------------------------------------------------------------------


@router.get(
    "/{org_id}",
    response_model=OrgDetailResponse,
    summary="Get organization details",
)
async def get_org(
    org_id: uuid.UUID,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> OrgDetailResponse:
    org = (
        await session.execute(
            select(Organization).where(Organization.id == org_id, Organization.is_active.is_(True))
        )
    ).scalar_one_or_none()

    if org is None:
        raise HTTPException(status_code=404, detail="Organization not found")

    # Check membership
    membership = (
        await session.execute(
            select(OrgMember).where(OrgMember.user_id == user.id, OrgMember.org_id == org_id)
        )
    ).scalar_one_or_none()

    if membership is None:
        raise HTTPException(status_code=403, detail="Not a member of this organization")

    return OrgDetailResponse(
        id=org.id,
        name=org.name,
        slug=org.slug,
        description=org.description,
        avatar_url=org.avatar_url,
        owner_id=org.owner_id,
        created_at=org.created_at,
    )


# ---------------------------------------------------------------------------
# PUT /orgs/{org_id}
# ---------------------------------------------------------------------------


@router.put(
    "/{org_id}",
    response_model=OrgDetailResponse,
    summary="Update organization",
)
async def update_org(
    org_id: uuid.UUID,
    body: UpdateOrgRequest,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> OrgDetailResponse:
    org = (
        await session.execute(select(Organization).where(Organization.id == org_id))
    ).scalar_one_or_none()

    if org is None:
        raise HTTPException(status_code=404, detail="Organization not found")

    if body.name is not None:
        org.name = body.name
    if body.description is not None:
        org.description = body.description
    if body.avatar_url is not None:
        org.avatar_url = body.avatar_url

    await session.commit()
    await session.refresh(org)

    return OrgDetailResponse(
        id=org.id,
        name=org.name,
        slug=org.slug,
        description=org.description,
        avatar_url=org.avatar_url,
        owner_id=org.owner_id,
        created_at=org.created_at,
    )


# ---------------------------------------------------------------------------
# POST /orgs/{org_id}/servers
# ---------------------------------------------------------------------------


@router.post(
    "/{org_id}/servers",
    response_model=AssignServerResponse,
    summary="Link a VJ server to this organization",
)
async def assign_server(
    org_id: uuid.UUID,
    body: AssignServerRequest,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> AssignServerResponse:
    server = (
        await session.execute(select(VJServer).where(VJServer.id == body.server_id))
    ).scalar_one_or_none()

    if server is None:
        raise HTTPException(status_code=404, detail="Server not found")

    if server.org_id is not None and server.org_id != org_id:
        raise HTTPException(
            status_code=409, detail="Server is already assigned to another organization"
        )

    server.org_id = org_id
    await session.commit()

    return AssignServerResponse(server_id=server.id, org_id=org_id)


# ---------------------------------------------------------------------------
# GET /orgs/{org_id}/servers
# ---------------------------------------------------------------------------

HEARTBEAT_ONLINE_THRESHOLD = timedelta(minutes=5)


@router.get(
    "/{org_id}/servers",
    response_model=list[OrgServerDetailResponse],
    summary="List servers belonging to this organization",
)
async def list_org_servers(
    org_id: uuid.UUID,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> list[OrgServerDetailResponse]:
    # Check membership
    membership = (
        await session.execute(
            select(OrgMember).where(OrgMember.user_id == user.id, OrgMember.org_id == org_id)
        )
    ).scalar_one_or_none()

    if membership is None:
        raise HTTPException(status_code=403, detail="Not a member of this organization")

    servers = (
        (await session.execute(select(VJServer).where(VJServer.org_id == org_id))).scalars().all()
    )

    now = datetime.now(timezone.utc)
    results: list[OrgServerDetailResponse] = []
    for s in servers:
        is_online = (
            s.last_heartbeat is not None and (now - s.last_heartbeat) < HEARTBEAT_ONLINE_THRESHOLD
        )
        active_show_count = sum(1 for show in s.shows if show.status == "active")
        results.append(
            OrgServerDetailResponse(
                id=s.id,
                name=s.name,
                websocket_url=s.websocket_url,
                is_active=s.is_active,
                is_online=is_online,
                last_heartbeat=s.last_heartbeat,
                active_show_count=active_show_count,
                created_at=s.created_at,
            )
        )

    return results


# ---------------------------------------------------------------------------
# POST /orgs/{org_id}/servers/register
# ---------------------------------------------------------------------------


@router.post(
    "/{org_id}/servers/register",
    response_model=RegisterOrgServerResponse,
    status_code=201,
    summary="Register a new server for this organization",
)
async def register_org_server(
    org_id: uuid.UUID,
    body: RegisterOrgServerRequest,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> RegisterOrgServerResponse:
    api_key = f"mcav_{_secrets.token_urlsafe(32)}"
    api_key_hash = hash_password(api_key)
    jwt_secret = f"jws_{_secrets.token_urlsafe(32)}"

    server = VJServer(
        id=uuid.uuid4(),
        name=body.name,
        websocket_url=body.websocket_url,
        api_key_hash=api_key_hash,
        jwt_secret=jwt_secret,
        org_id=org_id,
    )
    session.add(server)
    await session.commit()
    await session.refresh(server)

    return RegisterOrgServerResponse(
        server_id=server.id,
        name=server.name,
        websocket_url=server.websocket_url,
        api_key=api_key,
        jwt_secret=jwt_secret,
    )


# ---------------------------------------------------------------------------
# DELETE /orgs/{org_id}/servers/{server_id}
# ---------------------------------------------------------------------------


@router.delete(
    "/{org_id}/servers/{server_id}",
    status_code=204,
    summary="Remove a server from this organization",
)
async def remove_org_server(
    org_id: uuid.UUID,
    server_id: uuid.UUID,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> None:
    server = (
        await session.execute(
            select(VJServer).where(VJServer.id == server_id, VJServer.org_id == org_id)
        )
    ).scalar_one_or_none()

    if server is None:
        raise HTTPException(status_code=404, detail="Server not found in this organization")

    server.org_id = None
    await session.commit()


# ---------------------------------------------------------------------------
# POST /orgs/{org_id}/invites
# ---------------------------------------------------------------------------


def _generate_invite_code() -> str:
    """Generate an 8-character invite code from safe characters."""
    import secrets

    return "".join(secrets.choice(SAFE_CHARS) for _ in range(8))


@router.post(
    "/{org_id}/invites",
    response_model=InviteResponse,
    status_code=201,
    summary="Create an invite code for this organization",
)
async def create_invite(
    org_id: uuid.UUID,
    body: CreateInviteRequest = CreateInviteRequest(),
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> InviteResponse:
    expires_at = None
    if body.expires_in_hours is not None:
        expires_at = datetime.now(timezone.utc) + timedelta(hours=body.expires_in_hours)

    invite = OrgInvite(
        id=uuid.uuid4(),
        org_id=org_id,
        code=_generate_invite_code(),
        created_by=user.id,
        expires_at=expires_at,
        max_uses=body.max_uses,
    )
    session.add(invite)
    await session.commit()
    await session.refresh(invite)

    return InviteResponse(
        id=invite.id,
        org_id=invite.org_id,
        code=invite.code,
        max_uses=invite.max_uses,
        use_count=invite.use_count,
        is_active=invite.is_active,
        expires_at=invite.expires_at,
        created_at=invite.created_at,
    )


# ---------------------------------------------------------------------------
# GET /orgs/{org_id}/invites
# ---------------------------------------------------------------------------


@router.get(
    "/{org_id}/invites",
    response_model=list[InviteResponse],
    summary="List active invite codes",
)
async def list_invites(
    org_id: uuid.UUID,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> list[InviteResponse]:
    invites = (
        (
            await session.execute(
                select(OrgInvite).where(OrgInvite.org_id == org_id, OrgInvite.is_active.is_(True))
            )
        )
        .scalars()
        .all()
    )

    return [
        InviteResponse(
            id=inv.id,
            org_id=inv.org_id,
            code=inv.code,
            max_uses=inv.max_uses,
            use_count=inv.use_count,
            is_active=inv.is_active,
            expires_at=inv.expires_at,
            created_at=inv.created_at,
        )
        for inv in invites
    ]


# ---------------------------------------------------------------------------
# DELETE /orgs/{org_id}/invites/{invite_id}
# ---------------------------------------------------------------------------


@router.delete(
    "/{org_id}/invites/{invite_id}",
    status_code=204,
    summary="Deactivate an invite code",
)
async def delete_invite(
    org_id: uuid.UUID,
    invite_id: uuid.UUID,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> None:
    invite = (
        await session.execute(
            select(OrgInvite).where(OrgInvite.id == invite_id, OrgInvite.org_id == org_id)
        )
    ).scalar_one_or_none()

    if invite is None:
        raise HTTPException(status_code=404, detail="Invite not found")

    invite.is_active = False
    await session.commit()


# ---------------------------------------------------------------------------
# POST /orgs/join
# ---------------------------------------------------------------------------


@router.post(
    "/join",
    response_model=JoinOrgResponse,
    summary="Join an organization via invite code",
)
async def join_org(
    body: JoinOrgRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> JoinOrgResponse:
    invite = (
        await session.execute(
            select(OrgInvite).where(
                OrgInvite.code == body.invite_code.upper(),
                OrgInvite.is_active.is_(True),
            )
        )
    ).scalar_one_or_none()

    if invite is None:
        raise HTTPException(status_code=404, detail="Invalid or expired invite code")

    # Check expiry
    if invite.expires_at is not None and invite.expires_at < datetime.now(timezone.utc):
        raise HTTPException(status_code=410, detail="Invite code has expired")

    # Check max uses
    if invite.max_uses > 0 and invite.use_count >= invite.max_uses:
        raise HTTPException(status_code=410, detail="Invite code has reached its usage limit")

    # Check if already a member
    existing = (
        await session.execute(
            select(OrgMember).where(OrgMember.user_id == user.id, OrgMember.org_id == invite.org_id)
        )
    ).scalar_one_or_none()

    if existing is not None:
        raise HTTPException(status_code=409, detail="Already a member of this organization")

    # Create membership
    membership = OrgMember(
        id=uuid.uuid4(),
        user_id=user.id,
        org_id=invite.org_id,
        role="member",
    )
    session.add(membership)

    # Increment use count
    invite.use_count += 1

    await session.commit()

    # Fetch org info for response
    org = (
        await session.execute(select(Organization).where(Organization.id == invite.org_id))
    ).scalar_one()

    return JoinOrgResponse(
        org_id=org.id,
        org_name=org.name,
        org_slug=org.slug,
        role="member",
    )
