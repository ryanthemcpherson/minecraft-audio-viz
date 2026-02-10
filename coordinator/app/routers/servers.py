"""VJ server registration and heartbeat endpoints."""

from __future__ import annotations

import secrets as _secrets
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import VJServer
from app.models.schemas import (
    HeartbeatResponse,
    RegisterServerRequest,
    RegisterServerResponse,
)
from app.services.password import hash_password, verify_password

router = APIRouter(tags=["servers"])


async def _authenticate_server(
    authorization: str = Header(..., description="Bearer <api_key>"),
    session: AsyncSession = Depends(get_session),
) -> VJServer:
    """Dependency that verifies the Bearer API key and returns the server row."""
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid Authorization header format")

    api_key = authorization[len("Bearer ") :]
    stmt = select(VJServer).where(VJServer.is_active.is_(True))
    result = await session.execute(stmt)
    servers = result.scalars().all()

    for server in servers:
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
    session: AsyncSession = Depends(get_session),
) -> RegisterServerResponse:
    """Register a VJ server. Returns ``server_id`` and a ``jwt_secret`` that
    the server should store for verifying coordinator-minted JWTs.

    The ``api_key`` provided by the caller is bcrypt-hashed before storage
    and **never** returned again.
    """
    # Hash the caller-supplied API key
    api_key_hash = hash_password(body.api_key)

    # Generate a per-server JWT secret
    jwt_secret = f"jws_{_secrets.token_urlsafe(32)}"

    server = VJServer(
        id=uuid.uuid4(),
        name=body.name,
        websocket_url=body.websocket_url,
        api_key_hash=api_key_hash,
        jwt_secret=jwt_secret,
    )
    session.add(server)
    await session.commit()
    await session.refresh(server)

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

    return HeartbeatResponse(server_id=server_id, last_heartbeat=now)
