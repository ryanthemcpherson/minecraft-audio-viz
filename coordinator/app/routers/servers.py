"""VJ server registration and heartbeat endpoints."""

from __future__ import annotations

import hashlib
import logging
import secrets as _secrets
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import User, VJServer
from app.models.schemas import (
    HeartbeatResponse,
    RegisterServerRequest,
    RegisterServerResponse,
)
from app.services.password import hash_password, verify_password

logger = logging.getLogger(__name__)


def _compute_key_prefix(raw_key: str) -> str:
    """Return the first 16 hex chars of a SHA-256 hash of *raw_key*."""
    return hashlib.sha256(raw_key.encode()).hexdigest()[:16]


router = APIRouter(tags=["servers"])


async def _authenticate_server(
    authorization: str = Header(..., description="Bearer <api_key>"),
    session: AsyncSession = Depends(get_session),
) -> VJServer:
    """Dependency that verifies the Bearer API key and returns the server row."""
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid Authorization header format")

    api_key = authorization[len("Bearer ") :]
    prefix = _compute_key_prefix(api_key)

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


# ---------------------------------------------------------------------------
# POST /servers/register
# ---------------------------------------------------------------------------


@router.post(
    "/servers/register",
    response_model=RegisterServerResponse,
    status_code=201,
    summary="Register a new VJ server",
)
async def register_server(
    body: RegisterServerRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> RegisterServerResponse:
    """Register a VJ server. Returns ``server_id`` and a ``jwt_secret`` that
    the server should store for verifying coordinator-minted JWTs.

    The ``api_key`` provided by the caller is bcrypt-hashed before storage
    and **never** returned again.
    """
    # Hash the caller-supplied API key and store a prefix for fast lookup
    api_key_hash = hash_password(body.api_key)
    key_prefix = _compute_key_prefix(body.api_key)

    # Generate a per-server JWT secret
    jwt_secret = f"jws_{_secrets.token_urlsafe(32)}"

    server = VJServer(
        id=uuid.uuid4(),
        name=body.name,
        websocket_url=body.websocket_url,
        api_key_hash=api_key_hash,
        key_prefix=key_prefix,
        jwt_secret=jwt_secret,
    )
    session.add(server)
    await session.commit()
    await session.refresh(server)

    logger.info("Server registered: id=%s name=%s", server.id, server.name)

    return RegisterServerResponse(
        server_id=server.id,
        jwt_secret=jwt_secret,
        name=server.name,
    )


# ---------------------------------------------------------------------------
# PUT /servers/{id}/heartbeat
# ---------------------------------------------------------------------------


@router.put(
    "/servers/{server_id}/heartbeat",
    response_model=HeartbeatResponse,
    summary="Update server heartbeat",
)
async def heartbeat(
    server_id: uuid.UUID,
    server: VJServer = Depends(_authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> HeartbeatResponse:
    """Update ``last_heartbeat`` for the authenticated server.

    The path ``server_id`` must match the server identified by the API key.
    """
    if server.id != server_id:
        raise HTTPException(status_code=403, detail="Server ID mismatch")

    now = datetime.now(timezone.utc)
    stmt = update(VJServer).where(VJServer.id == server_id).values(last_heartbeat=now)
    await session.execute(stmt)
    await session.commit()

    logger.info("Heartbeat: server_id=%s", server_id)

    return HeartbeatResponse(server_id=server_id, last_heartbeat=now)
