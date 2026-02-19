"""DJ profile CRUD endpoints."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import DJProfile, User
from app.models.schemas import (
    CreateDJProfileRequest,
    DJProfileResponse,
    UpdateDJProfileRequest,
)

router = APIRouter(prefix="/dj", tags=["dj-profiles"])


def _profile_response(profile: DJProfile) -> DJProfileResponse:
    return DJProfileResponse(
        id=profile.id,
        user_id=profile.user_id,
        dj_name=profile.dj_name,
        bio=profile.bio,
        genres=profile.genres,
        avatar_url=profile.avatar_url,
        is_public=profile.is_public,
        created_at=profile.created_at,
    )


@router.post(
    "/profile",
    response_model=DJProfileResponse,
    status_code=201,
    summary="Create DJ profile",
)
async def create_profile(
    body: CreateDJProfileRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DJProfileResponse:
    existing = (
        await session.execute(select(DJProfile).where(DJProfile.user_id == user.id))
    ).scalar_one_or_none()

    if existing is not None:
        raise HTTPException(status_code=409, detail="DJ profile already exists")

    profile = DJProfile(
        id=uuid.uuid4(),
        user_id=user.id,
        dj_name=body.dj_name,
        bio=body.bio,
        genres=body.genres,
    )
    session.add(profile)
    await session.commit()
    await session.refresh(profile)
    return _profile_response(profile)


@router.get(
    "/profile",
    response_model=DJProfileResponse,
    summary="Get own DJ profile",
)
async def get_own_profile(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DJProfileResponse:
    profile = (
        await session.execute(select(DJProfile).where(DJProfile.user_id == user.id))
    ).scalar_one_or_none()

    if profile is None:
        raise HTTPException(status_code=404, detail="DJ profile not found")

    return _profile_response(profile)


@router.put(
    "/profile",
    response_model=DJProfileResponse,
    summary="Update own DJ profile",
)
async def update_profile(
    body: UpdateDJProfileRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DJProfileResponse:
    profile = (
        await session.execute(select(DJProfile).where(DJProfile.user_id == user.id))
    ).scalar_one_or_none()

    if profile is None:
        raise HTTPException(status_code=404, detail="DJ profile not found")

    if body.dj_name is not None:
        profile.dj_name = body.dj_name
    if body.bio is not None:
        profile.bio = body.bio
    if body.genres is not None:
        profile.genres = body.genres

    await session.commit()
    await session.refresh(profile)
    return _profile_response(profile)


@router.get(
    "/{user_id}",
    response_model=DJProfileResponse,
    summary="Get public DJ profile",
)
async def get_public_profile(
    user_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
) -> DJProfileResponse:
    profile = (
        await session.execute(
            select(DJProfile).where(DJProfile.user_id == user_id, DJProfile.is_public.is_(True))
        )
    ).scalar_one_or_none()

    if profile is None:
        raise HTTPException(status_code=404, detail="DJ profile not found")

    return _profile_response(profile)
