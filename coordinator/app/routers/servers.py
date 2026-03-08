"""VJ server registration and heartbeat endpoints.

Servers self-register with POST /servers/register (no user auth required).
"""

from __future__ import annotations

import logging
import secrets as _secrets
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import User, VJServer
from app.models.schemas import (
    HeartbeatResponse,
    RegisterServerRequest,
    RegisterServerResponse,
)
from app.services.server_service import (
    authenticate_server,
    compute_key_prefix,
    update_heartbeat,
)
from app.services.server_service import (
    register_server as svc_register_server,
)

logger = logging.getLogger(__name__)

# Backwards-compatible aliases so existing imports from other routers still work.
_authenticate_server = authenticate_server
_compute_key_prefix = compute_key_prefix

router = APIRouter(tags=["servers"])


# ---------------------------------------------------------------------------
# POST /servers/register
# ---------------------------------------------------------------------------


@router.post(
    "/servers/register",
    response_model=RegisterServerResponse,
    status_code=201,
    summary="Register a new VJ server",
)
async def register_server_endpoint(
    body: RegisterServerRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> RegisterServerResponse:
    """Register a VJ server. Returns ``server_id`` and a ``jwt_secret`` that
    the server should store for verifying coordinator-minted JWTs.

    The ``api_key`` provided by the caller is bcrypt-hashed before storage
    and **never** returned again.
    """
    # Generate a per-server JWT secret
    jwt_secret = f"jws_{_secrets.token_urlsafe(32)}"

    server = await svc_register_server(
        name=body.name,
        websocket_url=body.websocket_url,
        api_key=body.api_key,
        jwt_secret=jwt_secret,
        session=session,
    )
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
    server: VJServer = Depends(authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> HeartbeatResponse:
    """Update ``last_heartbeat`` for the authenticated server.

    The path ``server_id`` must match the server identified by the API key.
    """
    if server.id != server_id:
        raise HTTPException(status_code=403, detail="Server ID mismatch")

    now = await update_heartbeat(server_id=server_id, session=session)
    await session.commit()

    logger.info("Heartbeat: server_id=%s", server_id)

    return HeartbeatResponse(server_id=server_id, last_heartbeat=now)
