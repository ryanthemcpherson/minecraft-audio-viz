"""Organization CRUD and management endpoints."""

from __future__ import annotations

import re
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.dependencies.auth import get_current_user, require_org_owner
from app.models.db import OrgMember, Organization, User, VJServer
from app.models.schemas import (
    AssignServerRequest,
    AssignServerResponse,
    CreateOrgRequest,
    CreateOrgResponse,
    OrgDetailResponse,
    OrgServerResponse,
    UpdateOrgRequest,
)

router = APIRouter(prefix="/orgs", tags=["organizations"])

RESERVED_SLUGS = frozenset({
    "www", "api", "admin", "app", "dashboard", "mail", "ftp", "cdn",
    "docs", "help", "support", "status", "blog", "static", "assets",
    "auth", "login", "register", "signup", "account", "settings",
    "mcav", "minecraft", "test", "staging", "dev", "prod",
})

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
        await session.execute(
            select(Organization).where(Organization.slug == slug)
        )
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
            select(Organization).where(
                Organization.id == org_id, Organization.is_active.is_(True)
            )
        )
    ).scalar_one_or_none()

    if org is None:
        raise HTTPException(status_code=404, detail="Organization not found")

    # Check membership
    membership = (
        await session.execute(
            select(OrgMember).where(
                OrgMember.user_id == user.id, OrgMember.org_id == org_id
            )
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
        await session.execute(
            select(Organization).where(Organization.id == org_id)
        )
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
        await session.execute(
            select(VJServer).where(VJServer.id == body.server_id)
        )
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


@router.get(
    "/{org_id}/servers",
    response_model=list[OrgServerResponse],
    summary="List servers belonging to this organization",
)
async def list_org_servers(
    org_id: uuid.UUID,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> list[OrgServerResponse]:
    # Check membership
    membership = (
        await session.execute(
            select(OrgMember).where(
                OrgMember.user_id == user.id, OrgMember.org_id == org_id
            )
        )
    ).scalar_one_or_none()

    if membership is None:
        raise HTTPException(status_code=403, detail="Not a member of this organization")

    servers = (
        await session.execute(
            select(VJServer).where(VJServer.org_id == org_id)
        )
    ).scalars().all()

    return [
        OrgServerResponse(
            id=s.id,
            name=s.name,
            websocket_url=s.websocket_url,
            is_active=s.is_active,
        )
        for s in servers
    ]
