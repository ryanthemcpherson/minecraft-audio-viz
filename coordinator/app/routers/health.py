"""Health-check endpoint for load balancers and monitoring."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import Show, VJServer
from app.models.schemas import HealthResponse

logger = logging.getLogger(__name__)

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse, summary="Health check")
async def health_check(
    session: AsyncSession = Depends(get_session),
) -> HealthResponse | JSONResponse:
    """Return service health status along with aggregate counts of active
    servers and shows.
    """
    try:
        # Active servers
        srv_stmt = select(func.count()).select_from(VJServer).where(VJServer.is_active.is_(True))
        srv_result = await session.execute(srv_stmt)
        active_servers = srv_result.scalar_one()

        # Active shows
        show_stmt = select(func.count()).select_from(Show).where(Show.status == "active")
        show_result = await session.execute(show_stmt)
        active_shows = show_result.scalar_one()
    except Exception:
        logger.exception("Health check: database query failed")
        return JSONResponse(
            status_code=503,
            content={"status": "degraded", "error": "database_unavailable"},
        )

    return HealthResponse(
        status="ok",
        version="0.1.0",
        active_shows=active_shows,
        active_servers=active_servers,
    )
