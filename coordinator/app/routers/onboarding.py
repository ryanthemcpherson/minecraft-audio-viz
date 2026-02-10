"""Onboarding endpoints: complete and skip onboarding flow."""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import User
from app.models.schemas import CompleteOnboardingRequest, UserResponse

router = APIRouter(prefix="/onboarding", tags=["onboarding"])


def _user_response(user: User) -> UserResponse:
    return UserResponse(
        id=user.id,
        display_name=user.display_name,
        email=user.email,
        discord_username=user.discord_username,
        avatar_url=user.avatar_url,
        onboarding_completed=user.onboarding_completed_at is not None,
    )


@router.post(
    "/complete",
    response_model=UserResponse,
    summary="Complete onboarding with a chosen user type",
)
async def complete_onboarding(
    body: CompleteOnboardingRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserResponse:
    user.user_type = body.user_type
    user.onboarding_completed_at = datetime.now(timezone.utc)
    await session.commit()
    await session.refresh(user)
    return _user_response(user)


@router.post(
    "/skip",
    response_model=UserResponse,
    summary="Skip onboarding",
)
async def skip_onboarding(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserResponse:
    user.onboarding_completed_at = datetime.now(timezone.utc)
    await session.commit()
    await session.refresh(user)
    return _user_response(user)


@router.post(
    "/reset",
    response_model=UserResponse,
    summary="Reset onboarding status so user can redo the flow",
)
async def reset_onboarding(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserResponse:
    user.onboarding_completed_at = None
    user.user_type = None
    await session.commit()
    await session.refresh(user)
    return _user_response(user)
