"""Show creation, retrieval, and ending endpoints."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import Show, VJServer
from app.models.schemas import (
    CreateShowRequest,
    CreateShowResponse,
    EndShowResponse,
    ShowDetailResponse,
)
from app.routers.servers import _authenticate_server
from app.services.code_generator import generate_unique_code

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
        .values(status="ended", ended_at=now, connect_code=None)
    )
    await session.execute(stmt_update)
    await session.commit()

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
