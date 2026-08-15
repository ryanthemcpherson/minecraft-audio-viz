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
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.models.db import VJServer
from app.models.schemas import ConnectCodeResponse, ConnectResolveResponse
from app.services.code_generator import normalise_code
from app.services.connect_service import (
    disconnect_dj as svc_disconnect_dj,
    join_connect_code as svc_join_connect_code,
    resolve_connect_code as svc_resolve_connect_code,
)
from app.services.exceptions import (
    ConnectCodeNotFoundError,
    OwnershipError,
    ServerOfflineError,
    ShowFullError,
)
from app.services.metrics import incr as metrics_incr
from app.dependencies.server import authenticate_server

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
    request_id = getattr(request.state, "request_id", None)
    metrics_incr("connect.resolve.attempt")

    try:
        result = await svc_resolve_connect_code(code=code, session=session)
    except ConnectCodeNotFoundError:
        metrics_incr("connect.resolve.not_found")
        raise HTTPException(status_code=404, detail="Connect code not found or expired")
    except ServerOfflineError:
        metrics_incr("connect.resolve.server_offline")
        raise HTTPException(status_code=503, detail="Server registered but currently offline")

    metrics_incr("connect.resolve.success")
    logger.info(
        "Connect code resolved metadata: code=%s",
        normalise_code(code),
        extra={
            "request_id": request_id,
            "event": "connect_resolve",
            "path": request.url.path,
            "method": request.method,
        },
    )

    return ConnectResolveResponse(
        websocket_url=result.websocket_url,
        show_name=result.show_name,
        dj_count=result.dj_count,
        max_djs=result.max_djs,
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

    try:
        result = await svc_join_connect_code(
            code=normalised,
            session=session,
            jwt_secret_setting=settings.user_jwt_secret,
            jwt_expiry_minutes=settings.jwt_default_expiry_minutes,
            client_ip=client_ip,
            user_id=user_id,
        )
    except ConnectCodeNotFoundError:
        metrics_incr("connect.join.not_found")
        raise HTTPException(status_code=404, detail="Connect code not found or expired")
    except ServerOfflineError:
        metrics_incr("connect.join.server_offline")
        raise HTTPException(status_code=503, detail="Server registered but currently offline")
    except ShowFullError:
        metrics_incr("connect.join.full")
        raise HTTPException(status_code=409, detail="Show is full — maximum DJ limit reached")

    logger.info(
        "Connect code joined: code=%s dj_session=%s",
        normalised,
        result.dj_session_id,
        extra={
            "request_id": getattr(request.state, "request_id", None),
            "event": "connect_join",
            "path": request.url.path,
            "method": request.method,
        },
    )
    metrics_incr("connect.join.success")

    return ConnectCodeResponse(
        websocket_url=result.websocket_url,
        token=result.token,
        show_name=result.show_name,
        dj_count=result.dj_count,
        dj_session_id=result.dj_session_id,
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

    try:
        disconnected = await svc_disconnect_dj(
            dj_session_id=dj_session_id,
            server_id=server.id,
            session=session,
        )
    except OwnershipError:
        raise HTTPException(status_code=403, detail="Session does not belong to this server")

    if not disconnected:
        metrics_incr("connect.disconnect.noop")
        return Response(status_code=204)

    metrics_incr("connect.disconnect.success")

    logger.info(
        "DJ disconnected: dj_session=%s",
        dj_session_id,
        extra={
            "request_id": getattr(request.state, "request_id", None),
            "event": "connect_disconnect",
            "path": request.url.path,
            "method": request.method,
        },
    )
    return Response(status_code=204)
