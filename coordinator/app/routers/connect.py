"""Public connect-code resolution and join endpoints.

GET resolves metadata only (safe/read-only).
POST performs the side-effectful DJ join and returns a short-lived JWT.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import Response
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.models.db import DJSession, Show, VJServer
from app.models.schemas import ConnectCodeResponse, ConnectResolveResponse
from app.services.code_generator import normalise_code
from app.services.jwt_service import create_token
from app.services.metrics import incr as metrics_incr
from app.services.server_service import authenticate_server

logger = logging.getLogger(__name__)

router = APIRouter(tags=["connect"])

_IDEMPOTENCY_TTL_SECONDS = 300
_IDEMPOTENCY_MAX_KEYS = 10_000
_idempotency_cache: dict[str, tuple[float, ConnectCodeResponse]] = {}
_idempotency_locks: dict[str, asyncio.Lock] = {}


def _prune_idempotency_cache(now_ts: float) -> None:
    expired = [k for k, (expires_at, _) in _idempotency_cache.items() if expires_at <= now_ts]
    for key in expired:
        _idempotency_cache.pop(key, None)
        _idempotency_locks.pop(key, None)
    # Bound memory in case of unusually high unique-key traffic bursts.
    if len(_idempotency_cache) > _IDEMPOTENCY_MAX_KEYS:
        overflow = len(_idempotency_cache) - _IDEMPOTENCY_MAX_KEYS
        oldest_keys = sorted(_idempotency_cache.items(), key=lambda item: item[1][0])[:overflow]
        for key, _ in oldest_keys:
            _idempotency_cache.pop(key, None)
            _idempotency_locks.pop(key, None)


# ---------------------------------------------------------------------------
# GET /connect/{code}  --  PUBLIC, rate-limited via middleware
# ---------------------------------------------------------------------------


@router.get(
    "/connect/{code}",
    response_model=ConnectResolveResponse,
    summary="Resolve a connect code (public)",
)
async def resolve_connect_code(
    code: str,
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> ConnectResolveResponse:
    """Resolve a WORD-XXXX connect code to websocket metadata.

    This endpoint is public and rate-limited to 10 requests per IP per minute
    (enforced by ``RateLimitMiddleware``).
    """
    normalised = normalise_code(code)
    request_id = getattr(request.state, "request_id", None)
    metrics_incr("connect.resolve.attempt")

    # Find active show with this code
    stmt = select(Show).where(Show.connect_code == normalised, Show.status == "active")
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        metrics_incr("connect.resolve.not_found")
        raise HTTPException(status_code=404, detail="Connect code not found or expired")

    # Fetch the owning server
    stmt_srv = select(VJServer).where(VJServer.id == show.server_id, VJServer.is_active.is_(True))
    result_srv = await session.execute(stmt_srv)
    server = result_srv.scalar_one_or_none()

    if server is None:
        metrics_incr("connect.resolve.server_offline")
        raise HTTPException(status_code=503, detail="Server registered but currently offline")

    metrics_incr("connect.resolve.success")
    logger.info(
        "Connect code resolved metadata: code=%s show_id=%s",
        normalised,
        show.id,
        extra={
            "request_id": request_id,
            "event": "connect_resolve",
            "path": request.url.path,
            "method": request.method,
        },
    )

    return ConnectResolveResponse(
        websocket_url=server.websocket_url,
        show_name=show.name,
        dj_count=show.current_djs,
        max_djs=show.max_djs,
    )


# ---------------------------------------------------------------------------
# POST /connect/{code}/join  --  PUBLIC, rate-limited via middleware
# ---------------------------------------------------------------------------


@router.post(
    "/connect/{code}/join",
    response_model=ConnectCodeResponse,
    summary="Join a show with a connect code (public)",
)
async def join_connect_code(
    code: str,
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> ConnectCodeResponse:
    """Join a show with a WORD-XXXX code and mint a DJ session token."""
    normalised = normalise_code(code)
    metrics_incr("connect.join.attempt")
    raw_idempotency_key = request.headers.get("Idempotency-Key", "").strip()
    idempotency_key = raw_idempotency_key[:128] if raw_idempotency_key else ""
    client_ip = request.client.host if request.client else "unknown"

    if idempotency_key:
        cache_key = f"{normalised}:{client_ip}:{idempotency_key}"
        now_ts = datetime.now(timezone.utc).timestamp()
        _prune_idempotency_cache(now_ts)
        cached = _idempotency_cache.get(cache_key)
        if cached and cached[0] > now_ts:
            metrics_incr("connect.join.idempotent_hit")
            return cached[1]
        lock = _idempotency_locks.setdefault(cache_key, asyncio.Lock())
        async with lock:
            now_ts = datetime.now(timezone.utc).timestamp()
            _prune_idempotency_cache(now_ts)
            cached = _idempotency_cache.get(cache_key)
            if cached and cached[0] > now_ts:
                metrics_incr("connect.join.idempotent_hit")
                return cached[1]
            try:
                response = await _join_connect_code_inner(
                    normalised=normalised,
                    request=request,
                    session=session,
                    settings=settings,
                    client_ip=client_ip,
                )
                _idempotency_cache[cache_key] = (now_ts + _IDEMPOTENCY_TTL_SECONDS, response)
                return response
            finally:
                if cache_key not in _idempotency_cache:
                    _idempotency_locks.pop(cache_key, None)

    return await _join_connect_code_inner(
        normalised=normalised,
        request=request,
        session=session,
        settings=settings,
        client_ip=client_ip,
    )


async def _join_connect_code_inner(
    *,
    normalised: str,
    request: Request,
    session: AsyncSession,
    settings: Settings,
    client_ip: str,
) -> ConnectCodeResponse:
    """Join implementation shared by regular and idempotent flows."""

    # Find active show with this code
    stmt = select(Show).where(Show.connect_code == normalised, Show.status == "active")
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        metrics_incr("connect.join.not_found")
        raise HTTPException(status_code=404, detail="Connect code not found or expired")

    # Fetch the owning server
    stmt_srv = select(VJServer).where(VJServer.id == show.server_id, VJServer.is_active.is_(True))
    result_srv = await session.execute(stmt_srv)
    server = result_srv.scalar_one_or_none()

    if server is None:
        metrics_incr("connect.join.server_offline")
        raise HTTPException(status_code=503, detail="Server registered but currently offline")

    # Reserve a DJ slot atomically to avoid capacity races under concurrency.
    updated_count = await session.scalar(
        update(Show)
        .where(
            Show.id == show.id,
            Show.status == "active",
            Show.current_djs < Show.max_djs,
        )
        .values(current_djs=Show.current_djs + 1)
        .returning(Show.current_djs)
    )
    if updated_count is None:
        metrics_incr("connect.join.full")
        raise HTTPException(status_code=409, detail="Show is full — maximum DJ limit reached")

    # Try to extract user identity from optional Authorization header
    user_id = None
    auth_header = request.headers.get("authorization", "")
    if auth_header.startswith("Bearer "):
        try:
            from app.services.user_jwt import verify_user_token

            payload = verify_user_token(auth_header[7:], jwt_secret=settings.user_jwt_secret)
            user_id = uuid.UUID(payload.sub)
        except Exception as exc:
            logger.debug("JWT parse failed (anonymous connect): %s", exc)

    # Create a DJ session record
    dj_session_id = uuid.uuid4()
    dj_session = DJSession(
        id=dj_session_id,
        show_id=show.id,
        user_id=user_id,
        dj_name=f"DJ-{normalised}",
        ip_address=client_ip,
    )
    session.add(dj_session)
    await session.commit()

    # Mint JWT
    token = create_token(
        dj_session_id=dj_session_id,
        show_id=show.id,
        server_id=server.id,
        jwt_secret=server.jwt_secret,
        expiry_minutes=settings.jwt_default_expiry_minutes,
    )

    logger.info(
        "Connect code joined: code=%s show_id=%s dj_session=%s",
        normalised,
        show.id,
        dj_session_id,
        extra={
            "request_id": getattr(request.state, "request_id", None),
            "event": "connect_join",
            "path": request.url.path,
            "method": request.method,
        },
    )
    metrics_incr("connect.join.success")

    return ConnectCodeResponse(
        websocket_url=server.websocket_url,
        token=token,
        show_name=show.name,
        dj_count=int(updated_count),
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
    request: Request,
    server: "VJServer" = Depends(authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> Response:
    """Record DJ disconnect and decrement the show's current_djs counter.

    Requires server API-key authentication. The session must belong to a show
    owned by the authenticated server.
    """
    metrics_incr("connect.disconnect.attempt")
    stmt = select(DJSession).where(
        DJSession.id == dj_session_id,
        DJSession.disconnected_at.is_(None),
    )
    dj_session_row = (await session.execute(stmt)).scalar_one_or_none()
    if dj_session_row is None:
        metrics_incr("connect.disconnect.noop")
        return Response(status_code=204)  # Already disconnected or not found — idempotent

    # Verify the session belongs to a show owned by the authenticated server
    show_stmt = select(Show).where(Show.id == dj_session_row.show_id)
    show_row = (await session.execute(show_stmt)).scalar_one_or_none()
    if show_row is None or show_row.server_id != server.id:
        raise HTTPException(status_code=403, detail="Session does not belong to this server")

    dj_session_row.disconnected_at = datetime.now(timezone.utc)

    # Decrement current_djs (floor at 0)
    await session.execute(
        update(Show)
        .where(Show.id == dj_session_row.show_id, Show.current_djs > 0)
        .values(current_djs=Show.current_djs - 1)
    )
    await session.commit()
    metrics_incr("connect.disconnect.success")

    logger.info(
        "DJ disconnected: dj_session=%s show_id=%s",
        dj_session_id,
        dj_session_row.show_id,
        extra={
            "request_id": getattr(request.state, "request_id", None),
            "event": "connect_disconnect",
            "path": request.url.path,
            "method": request.method,
        },
    )
    return Response(status_code=204)
