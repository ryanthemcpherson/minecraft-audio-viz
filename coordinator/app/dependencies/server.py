"""FastAPI dependency for VJ server API-key authentication."""

from __future__ import annotations

from fastapi import Depends, Header, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.models.db import VJServer
from app.services.exceptions import AuthenticationError
from app.services.server_service import authenticate_server as _authenticate_server


async def authenticate_server(
    authorization: str = Header(..., description="Bearer <api_key>"),
    session: AsyncSession = Depends(get_session),
) -> VJServer:
    """FastAPI dependency that verifies the Bearer API key.

    Delegates to the framework-agnostic service function and converts
    ``AuthenticationError`` into a 401 ``HTTPException``.
    """
    try:
        return await _authenticate_server(authorization=authorization, session=session)
    except AuthenticationError as exc:
        raise HTTPException(status_code=401, detail=str(exc)) from exc
