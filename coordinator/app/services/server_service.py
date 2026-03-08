"""Server authentication and utility functions.

Extracted from ``app.routers.servers`` to decouple DB queries from routing.
"""

from __future__ import annotations

import hashlib
import logging
import uuid
from datetime import datetime, timezone

from fastapi import Depends, Header, HTTPException
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import VJServer
from app.services.password import hash_password, verify_password

logger = logging.getLogger(__name__)


def compute_key_prefix(raw_key: str) -> str:
    """Return the first 16 hex chars of a SHA-256 hash of *raw_key*."""
    return hashlib.sha256(raw_key.encode()).hexdigest()[:16]


async def authenticate_server(
    authorization: str = Header(..., description="Bearer <api_key>"),
    session: AsyncSession = Depends(get_session),
) -> VJServer:
    """Dependency that verifies the Bearer API key and returns the server row."""
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid Authorization header format")

    api_key = authorization[len("Bearer ") :]
    prefix = compute_key_prefix(api_key)

    # Filter by key_prefix first so we only bcrypt-verify the matching row(s)
    stmt = select(VJServer).where(
        VJServer.is_active.is_(True),
        VJServer.key_prefix == prefix,
    )
    result = await session.execute(stmt)
    candidates = result.scalars().all()

    for server in candidates:
        if verify_password(api_key, server.api_key_hash):
            return server

    # Fallback for legacy servers without key_prefix
    stmt_legacy = select(VJServer).where(
        VJServer.is_active.is_(True),
        VJServer.key_prefix.is_(None),
    )
    result_legacy = await session.execute(stmt_legacy)
    for server in result_legacy.scalars().all():
        if verify_password(api_key, server.api_key_hash):
            return server

    raise HTTPException(status_code=401, detail="Invalid API key")


async def register_server(
    *,
    name: str,
    websocket_url: str,
    api_key: str,
    jwt_secret: str,
    session: AsyncSession,
    org_id: uuid.UUID | None = None,
) -> VJServer:
    """Create a new VJ server record in the database.

    The ``api_key`` is bcrypt-hashed before storage and never returned again.
    """
    api_key_hash = hash_password(api_key)
    key_prefix = compute_key_prefix(api_key)

    server = VJServer(
        id=uuid.uuid4(),
        name=name,
        websocket_url=websocket_url,
        api_key_hash=api_key_hash,
        key_prefix=key_prefix,
        jwt_secret=jwt_secret,
        org_id=org_id,
    )
    session.add(server)
    await session.flush()
    return server


async def update_heartbeat(
    *,
    server_id: uuid.UUID,
    session: AsyncSession,
) -> datetime:
    """Update ``last_heartbeat`` for a server. Returns the timestamp."""
    now = datetime.now(timezone.utc)
    stmt = update(VJServer).where(VJServer.id == server_id).values(last_heartbeat=now)
    await session.execute(stmt)
    return now
