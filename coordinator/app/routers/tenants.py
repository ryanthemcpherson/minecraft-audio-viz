"""Tenant resolution endpoint for the Cloudflare Worker and client apps."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import Organization, Show, VJServer
from app.models.schemas import (
    TenantOrgInfo,
    TenantResolveResponse,
    TenantServerInfo,
    TenantShowInfo,
)

router = APIRouter(prefix="/tenants", tags=["tenants"])


@router.get(
    "/resolve",
    response_model=TenantResolveResponse,
    summary="Resolve a tenant by subdomain slug",
)
async def resolve_tenant(
    slug: str = Query(..., min_length=1, max_length=63),
    session: AsyncSession = Depends(get_session),
) -> TenantResolveResponse:
    """Public endpoint called by the Cloudflare Worker to resolve a subdomain
    into org info, servers, and active shows."""

    slug = slug.lower().strip()

    org = (
        await session.execute(
            select(Organization).where(
                Organization.slug == slug,
                Organization.is_active.is_(True),
            )
        )
    ).scalar_one_or_none()

    if org is None:
        raise HTTPException(status_code=404, detail=f"Tenant '{slug}' not found")

    # Servers belonging to this org
    servers = (
        (await session.execute(select(VJServer).where(VJServer.org_id == org.id))).scalars().all()
    )

    server_ids = [s.id for s in servers]

    # Active shows across all org servers
    active_shows: list[TenantShowInfo] = []
    if server_ids:
        shows = (
            (
                await session.execute(
                    select(Show).where(
                        Show.server_id.in_(server_ids),
                        Show.status == "active",
                    )
                )
            )
            .scalars()
            .all()
        )

        # Build a server name lookup
        server_name_map = {s.id: s.name for s in servers}

        active_shows = [
            TenantShowInfo(
                id=show.id,
                name=show.name,
                connect_code=show.connect_code,
                current_djs=show.current_djs,
                max_djs=show.max_djs,
                server_name=server_name_map.get(show.server_id, "Unknown"),
            )
            for show in shows
        ]

    server_infos = [
        TenantServerInfo(
            id=s.id,
            name=s.name,
            is_active=s.is_active,
            show_count=sum(1 for show in (s.shows or []) if show.status == "active"),
        )
        for s in servers
    ]

    return TenantResolveResponse(
        org=TenantOrgInfo(
            id=org.id,
            name=org.name,
            slug=org.slug,
            description=org.description,
            avatar_url=org.avatar_url,
        ),
        servers=server_infos,
        active_shows=active_shows,
    )
