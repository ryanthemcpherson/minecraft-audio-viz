"""Public connect-code resolution endpoint.

This is the main endpoint DJ clients hit: they provide a WORD-XXXX code and
receive back the VJ server's WebSocket URL along with a short-lived JWT.
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.models.db import DJSession, Show, VJServer
from app.models.schemas import ConnectCodeResponse
from app.services.code_generator import normalise_code
from app.services.jwt_service import create_token

logger = logging.getLogger(__name__)

router = APIRouter(tags=["connect"])


# ---------------------------------------------------------------------------
# GET /connect/{code}  --  PUBLIC, rate-limited via middleware
# ---------------------------------------------------------------------------


@router.get(
    "/connect/{code}",
    response_model=ConnectCodeResponse,
    summary="Resolve a connect code (public)",
)
async def resolve_connect_code(
    code: str,
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> ConnectCodeResponse:
    """Resolve a WORD-XXXX connect code to a WebSocket URL and JWT.

    This endpoint is public and rate-limited to 10 requests per IP per minute
    (enforced by ``RateLimitMiddleware``).
    """
    normalised = normalise_code(code)

    # Find active show with this code
    stmt = select(Show).where(Show.connect_code == normalised, Show.status == "active")
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        raise HTTPException(status_code=404, detail="Connect code not found or expired")

    # Fetch the owning server
    stmt_srv = select(VJServer).where(VJServer.id == show.server_id, VJServer.is_active.is_(True))
    result_srv = await session.execute(stmt_srv)
    server = result_srv.scalar_one_or_none()

    if server is None:
        raise HTTPException(status_code=503, detail="Server registered but currently offline")

    # Atomically increment current DJ count only if below max_djs
    result_update = await session.execute(
        update(Show)
        .where(Show.id == show.id, Show.current_djs < Show.max_djs)
        .values(current_djs=Show.current_djs + 1)
    )
    if result_update.rowcount == 0:
        raise HTTPException(status_code=409, detail="Show is full — maximum DJ limit reached")

    # Create a DJ session record
    dj_session_id = uuid.uuid4()
    client_ip = request.client.host if request.client else "unknown"
    dj_session = DJSession(
        id=dj_session_id,
        show_id=show.id,
        dj_name=f"DJ-{normalised}",
        ip_address=client_ip,
    )
    session.add(dj_session)

    await session.commit()

    # Refresh to get the updated count
    await session.refresh(show)

    # Mint JWT
    token = create_token(
        dj_session_id=dj_session_id,
        show_id=show.id,
        server_id=server.id,
        jwt_secret=server.jwt_secret,
        expiry_minutes=settings.jwt_default_expiry_minutes,
    )

    logger.info(
        "Connect code resolved: code=%s show_id=%s dj_session=%s",
        normalised,
        show.id,
        dj_session_id,
    )

    return ConnectCodeResponse(
        websocket_url=server.websocket_url,
        token=token,
        show_name=show.name,
        dj_count=show.current_djs,
    )
