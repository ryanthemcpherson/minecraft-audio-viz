"""Show creation, retrieval, and ending endpoints."""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import DJSession, Show, VJServer
from app.models.schemas import (
    CreateShowRequest,
    CreateShowResponse,
    EndShowResponse,
    ShowDetailResponse,
)
from app.routers.servers import _authenticate_server
from app.services.code_generator import generate_unique_code

logger = logging.getLogger(__name__)

router = APIRouter(tags=["shows"])


# ---------------------------------------------------------------------------
# POST /shows
# ---------------------------------------------------------------------------


@router.post(
    "/shows",
    response_model=CreateShowResponse,
    status_code=201,
    summary="Create a new show",
)
async def create_show(
    body: CreateShowRequest,
    server: VJServer = Depends(_authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> CreateShowResponse:
    """Create a show for the authenticated server.

    Generates a unique WORD-XXXX connect code automatically.
    """
    if server.id != body.server_id:
        raise HTTPException(status_code=403, detail="API key does not own this server_id")

    connect_code = await generate_unique_code(session)

    show = Show(
        id=uuid.uuid4(),
        server_id=body.server_id,
        name=body.name,
        connect_code=connect_code,
        max_djs=body.max_djs,
    )
    session.add(show)
    await session.commit()
    await session.refresh(show)

    logger.info("Show created: id=%s name=%s code=%s", show.id, show.name, connect_code)

    return CreateShowResponse(
        show_id=show.id,
        connect_code=show.connect_code,  # type: ignore[arg-type]
        name=show.name,
        server_id=show.server_id,
        created_at=show.created_at,
    )


# ---------------------------------------------------------------------------
# DELETE /shows/{show_id}
# ---------------------------------------------------------------------------


@router.delete(
    "/shows/{show_id}",
    response_model=EndShowResponse,
    summary="End an active show",
)
async def end_show(
    show_id: uuid.UUID,
    server: VJServer = Depends(_authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> EndShowResponse:
    """End a show, setting its status to ``ended`` and clearing the connect
    code so it is no longer resolvable.
    """
    stmt = select(Show).where(Show.id == show_id)
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        raise HTTPException(status_code=404, detail="Show not found")

    if show.server_id != server.id:
        raise HTTPException(status_code=403, detail="API key does not own this show")

    if show.status == "ended":
        raise HTTPException(status_code=400, detail="Show already ended")

    now = datetime.now(timezone.utc)
    stmt_update = (
        update(Show)
        .where(Show.id == show_id)
        .values(status="ended", ended_at=now, connect_code=None, current_djs=0)
    )
    await session.execute(stmt_update)

    # Disconnect all active DJ sessions in this show
    await session.execute(
        update(DJSession)
        .where(DJSession.show_id == show_id, DJSession.disconnected_at.is_(None))
        .values(disconnected_at=now)
    )
    await session.commit()

    logger.info("Show ended: id=%s", show_id)

    return EndShowResponse(show_id=show_id, status="ended", ended_at=now)


# ---------------------------------------------------------------------------
# GET /shows/{show_id}
# ---------------------------------------------------------------------------


@router.get(
    "/shows/{show_id}",
    response_model=ShowDetailResponse,
    summary="Get show details",
)
async def get_show(
    show_id: uuid.UUID,
    server: VJServer = Depends(_authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> ShowDetailResponse:
    """Return full details for a show owned by the authenticated server."""
    stmt = select(Show).where(Show.id == show_id)
    result = await session.execute(stmt)
    show = result.scalar_one_or_none()

    if show is None:
        raise HTTPException(status_code=404, detail="Show not found")

    if show.server_id != server.id:
        raise HTTPException(status_code=403, detail="API key does not own this show")

    return ShowDetailResponse(
        show_id=show.id,
        name=show.name,
        server_id=show.server_id,
        status=show.status,
        connect_code=show.connect_code,
        max_djs=show.max_djs,
        current_djs=show.current_djs,
        created_at=show.created_at,
        ended_at=show.ended_at,
    )


# ---------------------------------------------------------------------------
# POST /shows/disconnect/{session_id}
# ---------------------------------------------------------------------------


@router.post(
    "/shows/disconnect/{session_id}",
    status_code=204,
    response_class=Response,
    summary="Disconnect a DJ session (idempotent)",
)
async def disconnect_dj(
    session_id: uuid.UUID,
    server: VJServer = Depends(_authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> Response:
    """Mark a DJ session as disconnected and decrement the show's DJ count.

    This endpoint is **idempotent**: disconnecting an already-disconnected or
    non-existent session returns 204 without error.
    """
    stmt = select(DJSession).where(DJSession.id == session_id)
    result = await session.execute(stmt)
    dj_session = result.scalar_one_or_none()

    if dj_session is None or dj_session.disconnected_at is not None:
        return Response(status_code=204)

    # Verify server ownership
    show_stmt = select(Show).where(Show.id == dj_session.show_id)
    show_result = await session.execute(show_stmt)
    show = show_result.scalar_one_or_none()

    if show is None or show.server_id != server.id:
        return Response(status_code=204)

    now = datetime.now(timezone.utc)

    # Mark session disconnected
    await session.execute(
        update(DJSession)
        .where(DJSession.id == session_id, DJSession.disconnected_at.is_(None))
        .values(disconnected_at=now)
    )

    # Decrement current_djs (floor at 0)
    await session.execute(
        update(Show)
        .where(Show.id == show.id, Show.current_djs > 0)
        .values(current_djs=Show.current_djs - 1)
    )

    await session.commit()

    logger.info("DJ disconnected: session_id=%s show_id=%s", session_id, show.id)

    return Response(status_code=204)
