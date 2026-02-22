"""Public connect-code resolution endpoint.

This is the main endpoint DJ clients hit: they provide a WORD-XXXX code and
receive back the VJ server's WebSocket URL along with a short-lived JWT.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import Response
from sqlalchemy import func, select, update
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

    # Compute active DJ count from DJSession table (avoids counter drift
    # when DJs crash or drop without explicit disconnect).
    active_count = await session.scalar(
        select(func.count())
        .select_from(DJSession)
        .where(
            DJSession.show_id == show.id,
            DJSession.disconnected_at.is_(None),
        )
    )
    if active_count >= show.max_djs:
        raise HTTPException(status_code=409, detail="Show is full — maximum DJ limit reached")

    # Try to extract user identity from optional Authorization header
    user_id = None
    auth_header = request.headers.get("authorization", "")
    if auth_header.startswith("Bearer "):
        try:
            from app.services.user_jwt import verify_user_token

            payload = verify_user_token(auth_header[7:], jwt_secret=settings.user_jwt_secret)
            user_id = payload.sub
        except Exception:
            pass  # Anonymous is fine — don't fail the connect flow

    # Create a DJ session record
    dj_session_id = uuid.uuid4()
    client_ip = request.client.host if request.client else "unknown"
    dj_session = DJSession(
        id=dj_session_id,
        show_id=show.id,
        user_id=user_id,
        dj_name=f"DJ-{normalised}",
        ip_address=client_ip,
    )
    session.add(dj_session)

    # Sync the cached counter to match reality (new session included: +1)
    await session.execute(
        update(Show).where(Show.id == show.id).values(current_djs=active_count + 1)
    )

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
        dj_session_id=str(dj_session_id),
    )


# ---------------------------------------------------------------------------
# POST /disconnect/{dj_session_id}  --  called by DJ client on graceful disconnect
# ---------------------------------------------------------------------------


@router.post(
    "/disconnect/{dj_session_id}",
    status_code=204,
    response_class=Response,
    summary="Notify coordinator that a DJ has disconnected",
)
async def disconnect_dj(
    dj_session_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
) -> Response:
    """Record DJ disconnect and decrement the show's current_djs counter."""
    stmt = select(DJSession).where(
        DJSession.id == dj_session_id,
        DJSession.disconnected_at.is_(None),
    )
    dj_session_row = (await session.execute(stmt)).scalar_one_or_none()
    if dj_session_row is None:
        return Response(status_code=204)  # Already disconnected or not found — idempotent

    dj_session_row.disconnected_at = datetime.now(timezone.utc)

    # Decrement current_djs (floor at 0)
    await session.execute(
        update(Show)
        .where(Show.id == dj_session_row.show_id, Show.current_djs > 0)
        .values(current_djs=Show.current_djs - 1)
    )
    await session.commit()

    logger.info("DJ disconnected: dj_session=%s show_id=%s", dj_session_id, dj_session_row.show_id)
    return Response(status_code=204)
