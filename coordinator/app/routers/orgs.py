"""Organization CRUD and management endpoints."""

from __future__ import annotations

import logging
import re
import secrets as _secrets
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.dependencies.auth import get_current_user, require_org_owner
from app.models.db import User
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
from app.services import org_service
from app.services.code_generator import SAFE_CHARS
from app.services.server_service import register_server as svc_register_server

logger = logging.getLogger(__name__)

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
    """Create a new organization with the given name and slug.  The
    authenticated user becomes the owner and first member automatically.
    Returns 409 if the slug is already taken, 400 if the slug is invalid
    or reserved.
    """
    slug = _validate_slug(body.slug)

    if not await org_service.check_slug_available(session, slug):
        raise HTTPException(status_code=409, detail="Slug already taken")

    org = await org_service.create_org(
        session,
        name=body.name,
        slug=slug,
        owner_id=user.id,
        description=body.description,
    )

    await session.commit()
    await session.refresh(org)

    logger.info("Organization created: id=%s slug=%s owner=%s", org.id, org.slug, user.id)

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
    """Resolve an organization by its URL slug.  Requires the authenticated
    user to be a member.  Returns 404 if not found, 403 if not a member.
    """
    org = await org_service.get_org_by_slug(session, slug)

    if org is None:
        raise HTTPException(status_code=404, detail="Organization not found")

    membership = await org_service.get_membership(session, user.id, org.id)
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
    """Get organization details by ID.  Requires the authenticated user to be
    a member.  Returns 404 if not found, 403 if not a member.
    """
    org = await org_service.get_org_by_id(session, org_id)

    if org is None:
        raise HTTPException(status_code=404, detail="Organization not found")

    membership = await org_service.get_membership(session, user.id, org_id)
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
    """Update organization name, description, or avatar.  Only the org owner
    can perform this action.
    """
    org = await org_service.get_org_by_id(session, org_id, active_only=False)

    if org is None:
        raise HTTPException(status_code=404, detail="Organization not found")

    await org_service.update_org(
        session,
        org,
        name=body.name,
        description=body.description,
        avatar_url=body.avatar_url,
    )

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
    """Link an existing VJ server to this organization.  Owner-only.
    Returns 409 if the server is already assigned to a different org.
    """
    server = await org_service.assign_server_to_org(session, body.server_id, org_id)

    if server is None:
        raise HTTPException(status_code=404, detail="Server not found")

    if server.org_id is not None and server.org_id != org_id:
        raise HTTPException(
            status_code=409, detail="Server is already assigned to another organization"
        )

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
    limit: int = 50,
    offset: int = 0,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> list[OrgServerDetailResponse]:
    """List VJ servers belonging to this organization with online status and
    active show counts.  Requires org membership.
    """
    membership = await org_service.get_membership(session, user.id, org_id)
    if membership is None:
        raise HTTPException(status_code=403, detail="Not a member of this organization")

    servers = await org_service.list_org_servers(session, org_id, limit=limit, offset=offset)

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
    """Register a new VJ server under this organization.  Owner-only.
    Generates an API key and JWT secret automatically.  The API key is
    returned **once** and cannot be retrieved again.
    """
    api_key = f"mcav_{_secrets.token_urlsafe(32)}"
    jwt_secret = f"jws_{_secrets.token_urlsafe(32)}"

    server = await svc_register_server(
        name=body.name,
        websocket_url=body.websocket_url,
        api_key=api_key,
        jwt_secret=jwt_secret,
        session=session,
        org_id=org_id,
    )
    await session.commit()
    await session.refresh(server)

    logger.info("Org server registered: id=%s org_id=%s name=%s", server.id, org_id, server.name)

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
    response_model=None,
    summary="Remove a server from this organization",
)
async def remove_org_server(
    org_id: uuid.UUID,
    server_id: uuid.UUID,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> None:
    """Unlink a server from this organization (does not delete the server).
    Owner-only.
    """
    server = await org_service.remove_server_from_org(session, server_id, org_id)

    if server is None:
        raise HTTPException(status_code=404, detail="Server not found in this organization")

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
    """Create an invite code for this organization.  Owner-only.  Optionally
    set ``expires_in_hours`` and ``max_uses`` to limit the invite.
    """
    invite = await org_service.create_invite(
        session,
        org_id=org_id,
        code=_generate_invite_code(),
        created_by=user.id,
        expires_in_hours=body.expires_in_hours,
        max_uses=body.max_uses,
    )

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
    limit: int = 50,
    offset: int = 0,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> list[InviteResponse]:
    """List active invite codes for this organization.  Owner-only."""
    invites = await org_service.list_active_invites(session, org_id, limit=limit, offset=offset)

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
    response_model=None,
    summary="Deactivate an invite code",
)
async def delete_invite(
    org_id: uuid.UUID,
    invite_id: uuid.UUID,
    user: User = Depends(require_org_owner),
    session: AsyncSession = Depends(get_session),
) -> None:
    """Deactivate an invite code so it can no longer be used.  Owner-only."""
    invite = await org_service.deactivate_invite(session, invite_id, org_id)

    if invite is None:
        raise HTTPException(status_code=404, detail="Invite not found")

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
    """Join an organization using an invite code.  Returns 404 if the code is
    invalid, 410 if expired or max uses reached, 409 if already a member.
    """
    invite = await org_service.find_active_invite_by_code(session, body.invite_code)

    if invite is None:
        raise HTTPException(status_code=404, detail="Invalid or expired invite code")

    # Check expiry
    if invite.expires_at is not None and invite.expires_at < datetime.now(timezone.utc):
        raise HTTPException(status_code=410, detail="Invite code has expired")

    # Check max uses
    if invite.max_uses > 0 and invite.use_count >= invite.max_uses:
        raise HTTPException(status_code=410, detail="Invite code has reached its usage limit")

    # Check if already a member
    existing = await org_service.get_membership(session, user.id, invite.org_id)
    if existing is not None:
        raise HTTPException(status_code=409, detail="Already a member of this organization")

    await org_service.join_org_with_invite(session, user_id=user.id, invite=invite)

    await session.commit()

    # Fetch org info for response
    org = await org_service.get_org_for_invite(session, invite.org_id)

    return JoinOrgResponse(
        org_id=org.id,
        org_name=org.name,
        org_slug=org.slug,
        role="member",
    )
