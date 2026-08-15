"""Show creation, retrieval, and ending endpoints."""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import VJServer
from app.models.schemas import (
    CreateShowRequest,
    CreateShowResponse,
    EndShowResponse,
    ShowDetailResponse,
)
from app.services.exceptions import (
    OwnershipError,
    ShowAlreadyEndedError,
    ShowNotFoundError,
)
from app.services.show_service import (
    create_show as svc_create_show,
    end_show as svc_end_show,
    get_show as svc_get_show,
)
from app.dependencies.server import authenticate_server

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
    server: VJServer = Depends(authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> CreateShowResponse:
    """Create a show for the authenticated server.

    Generates a unique WORD-XXXX connect code automatically.
    """
    try:
        result = await svc_create_show(
            server_id=body.server_id,
            requesting_server_id=server.id,
            name=body.name,
            max_djs=body.max_djs,
            session=session,
        )
    except OwnershipError:
        raise HTTPException(status_code=403, detail="API key does not own this server_id")

    return CreateShowResponse(
        show_id=result.show_id,
        connect_code=result.connect_code,
        name=result.name,
        server_id=result.server_id,
        created_at=result.created_at,
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
    server: VJServer = Depends(authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> EndShowResponse:
    """End a show, setting its status to ``ended`` and clearing the connect
    code so it is no longer resolvable.
    """
    try:
        result = await svc_end_show(
            show_id=show_id,
            requesting_server_id=server.id,
            session=session,
        )
    except ShowNotFoundError:
        raise HTTPException(status_code=404, detail="Show not found")
    except OwnershipError:
        raise HTTPException(status_code=403, detail="API key does not own this show")
    except ShowAlreadyEndedError:
        raise HTTPException(status_code=400, detail="Show already ended")

    return EndShowResponse(
        show_id=result.show_id,
        status=result.status,
        ended_at=result.ended_at,
    )


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
    server: VJServer = Depends(authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> ShowDetailResponse:
    """Return full details for a show owned by the authenticated server."""
    try:
        result = await svc_get_show(
            show_id=show_id,
            requesting_server_id=server.id,
            session=session,
        )
    except ShowNotFoundError:
        raise HTTPException(status_code=404, detail="Show not found")
    except OwnershipError:
        raise HTTPException(status_code=403, detail="API key does not own this show")

    return ShowDetailResponse(
        show_id=result.show_id,
        name=result.name,
        server_id=result.server_id,
        status=result.status,
        connect_code=result.connect_code,
        max_djs=result.max_djs,
        current_djs=result.current_djs,
        created_at=result.created_at,
        ended_at=result.ended_at,
    )
