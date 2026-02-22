"""Internal server-to-server endpoints (VJ server -> coordinator)."""

from __future__ import annotations

import json
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_session
from app.models.db import DJSession, User
from app.routers.servers import _authenticate_server

router = APIRouter(prefix="/internal", tags=["internal"])


@router.get(
    "/dj-profile/{dj_session_id}",
    summary="Get DJ profile for a session (server-to-server)",
)
async def get_dj_profile_for_session(
    dj_session_id: uuid.UUID,
    server=Depends(_authenticate_server),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Look up DJSession -> User -> DJProfile and return profile data.

    Used by VJ servers to hydrate DJ profile data after a DJ connects.
    Requires server API key authentication.
    """
    stmt = select(DJSession).where(DJSession.id == dj_session_id)
    dj_session = (await session.execute(stmt)).scalar_one_or_none()

    if dj_session is None or dj_session.user_id is None:
        raise HTTPException(status_code=404, detail="No profile for this session")

    stmt = select(User).where(User.id == dj_session.user_id).options(selectinload(User.dj_profile))
    user = (await session.execute(stmt)).scalar_one_or_none()

    if user is None or user.dj_profile is None:
        raise HTTPException(status_code=404, detail="No profile for this session")

    profile = user.dj_profile

    # Parse JSON palette fields
    color_palette = None
    if profile.color_palette:
        try:
            color_palette = json.loads(profile.color_palette)
        except (json.JSONDecodeError, TypeError):
            pass

    block_palette = None
    if profile.block_palette:
        try:
            block_palette = json.loads(profile.block_palette)
        except (json.JSONDecodeError, TypeError):
            pass

    return {
        "dj_name": profile.dj_name,
        "avatar_url": profile.avatar_url,
        "color_palette": color_palette,
        "block_palette": block_palette,
        "slug": profile.slug,
        "bio": profile.bio,
        "genres": profile.genres,
    }
