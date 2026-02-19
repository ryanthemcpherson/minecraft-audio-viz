"""FastAPI dependency for Host-header based tenant resolution."""

from __future__ import annotations

from fastapi import Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import Organization


def extract_subdomain(request: Request) -> str | None:
    """Extract the subdomain from the Host header.

    Returns ``None`` for the bare domain (``mcav.live``) or reserved subdomains.
    """
    host = request.headers.get("host", "")
    # Strip port if present
    host = host.split(":")[0]

    # Expected: {slug}.mcav.live or localhost variants
    if host.endswith(".mcav.live"):
        subdomain = host.removesuffix(".mcav.live")
        if subdomain in ("www", "api", ""):
            return None
        return subdomain

    return None


async def resolve_tenant(
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> Organization | None:
    """Resolve the current tenant from the Host header.

    Returns ``None`` if the request is for the main site (no subdomain).
    Raises 404 if a subdomain is present but doesn't match any active org.
    """
    subdomain = extract_subdomain(request)
    if subdomain is None:
        return None

    stmt = select(Organization).where(
        Organization.slug == subdomain,
        Organization.is_active.is_(True),
    )
    result = await session.execute(stmt)
    org = result.scalar_one_or_none()

    if org is None:
        raise HTTPException(status_code=404, detail=f"Tenant '{subdomain}' not found")

    return org
