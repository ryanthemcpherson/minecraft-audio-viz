"""DJ profile CRUD endpoints."""

from __future__ import annotations

import json
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
    SlugCheckResponse,
    UpdateDJProfileRequest,
)

router = APIRouter(prefix="/dj", tags=["dj-profiles"])


def _parse_color_palette(raw: str | None) -> list[str] | None:
    """Deserialize color_palette from JSON string to list."""
    if raw is None:
        return None
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, list):
            return parsed
    except (json.JSONDecodeError, TypeError):
        pass
    return None


def _serialize_color_palette(colors: list[str] | None) -> str | None:
    """Serialize color_palette list to JSON string for storage."""
    if colors is None:
        return None
    return json.dumps(colors)


def _profile_response(profile: DJProfile) -> DJProfileResponse:
    return DJProfileResponse(
        id=profile.id,
        user_id=profile.user_id,
        dj_name=profile.dj_name,
        bio=profile.bio,
        genres=profile.genres,
        avatar_url=profile.avatar_url,
        banner_url=profile.banner_url,
        color_palette=_parse_color_palette(profile.color_palette),
        slug=profile.slug,
        soundcloud_url=profile.soundcloud_url,
        spotify_url=profile.spotify_url,
        website_url=profile.website_url,
        is_public=profile.is_public,
        created_at=profile.created_at,
    )


async def _check_slug_unique(
    session: AsyncSession, slug: str, exclude_profile_id: uuid.UUID | None = None
) -> bool:
    """Return True if the slug is available."""
    query = select(DJProfile).where(DJProfile.slug == slug)
    if exclude_profile_id is not None:
        query = query.where(DJProfile.id != exclude_profile_id)
    result = (await session.execute(query)).scalar_one_or_none()
    return result is None


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

    if body.slug is not None:
        if not await _check_slug_unique(session, body.slug):
            raise HTTPException(status_code=409, detail="Slug already taken")

    profile = DJProfile(
        id=uuid.uuid4(),
        user_id=user.id,
        dj_name=body.dj_name,
        bio=body.bio,
        genres=body.genres,
        slug=body.slug,
        color_palette=_serialize_color_palette(body.color_palette),
        soundcloud_url=body.soundcloud_url or None,
        spotify_url=body.spotify_url or None,
        website_url=body.website_url or None,
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
    if body.slug is not None:
        if not await _check_slug_unique(session, body.slug, exclude_profile_id=profile.id):
            raise HTTPException(status_code=409, detail="Slug already taken")
        profile.slug = body.slug
    if body.color_palette is not None:
        profile.color_palette = _serialize_color_palette(body.color_palette)
    if body.avatar_url is not None:
        profile.avatar_url = body.avatar_url
    if body.banner_url is not None:
        profile.banner_url = body.banner_url
    if body.is_public is not None:
        profile.is_public = body.is_public
    if body.soundcloud_url is not None:
        profile.soundcloud_url = body.soundcloud_url or None
    if body.spotify_url is not None:
        profile.spotify_url = body.spotify_url or None
    if body.website_url is not None:
        profile.website_url = body.website_url or None

    await session.commit()
    await session.refresh(profile)
    return _profile_response(profile)


@router.get(
    "/slug-check/{slug}",
    response_model=SlugCheckResponse,
    summary="Check if a DJ slug is available",
)
async def check_slug(
    slug: str,
    session: AsyncSession = Depends(get_session),
) -> SlugCheckResponse:
    available = await _check_slug_unique(session, slug)
    return SlugCheckResponse(available=available)


@router.get(
    "/by-slug/{slug}",
    response_model=DJProfileResponse,
    summary="Get public DJ profile by slug",
)
async def get_profile_by_slug(
    slug: str,
    session: AsyncSession = Depends(get_session),
) -> DJProfileResponse:
    profile = (
        await session.execute(
            select(DJProfile).where(DJProfile.slug == slug, DJProfile.is_public.is_(True))
        )
    ).scalar_one_or_none()

    if profile is None:
        raise HTTPException(status_code=404, detail="DJ profile not found")

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
