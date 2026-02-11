"""Onboarding endpoints: complete and skip onboarding flow."""

from __future__ import annotations

import os
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import DJProfile, Organization, OrgInvite, OrgMember, User, VJServer
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


@router.post(
    "/reset-full",
    response_model=UserResponse,
    summary="Full account reset: wipe orgs, memberships, DJ profile, and onboarding state",
)
async def reset_full(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserResponse:
    """Nuclear reset for testing: deletes all orgs owned by the user (and their
    invites/memberships/server assignments), removes memberships in other orgs,
    deletes DJ profile, and resets onboarding state back to fresh.

    Only available in development/testing environments.
    """
    env = os.environ.get("MCAV_ENV", "development").lower()
    if env in ("production", "prod", "staging"):
        raise HTTPException(
            status_code=403,
            detail="Full reset is disabled in production environments",
        )

    # 1. Find orgs owned by this user
    owned_org_ids = (
        (await session.execute(select(Organization.id).where(Organization.owner_id == user.id)))
        .scalars()
        .all()
    )

    if owned_org_ids:
        # Unassign servers from owned orgs (set org_id = NULL, keep server)
        await session.execute(
            update(VJServer).where(VJServer.org_id.in_(owned_org_ids)).values(org_id=None)
        )
        # Delete invites for owned orgs
        await session.execute(delete(OrgInvite).where(OrgInvite.org_id.in_(owned_org_ids)))
        # Delete all memberships in owned orgs (including other users)
        await session.execute(delete(OrgMember).where(OrgMember.org_id.in_(owned_org_ids)))
        # Delete the owned orgs themselves
        await session.execute(delete(Organization).where(Organization.id.in_(owned_org_ids)))

    # 2. Remove user's remaining memberships (orgs they joined but don't own)
    await session.execute(delete(OrgMember).where(OrgMember.user_id == user.id))

    # 3. Delete DJ profile if exists
    await session.execute(delete(DJProfile).where(DJProfile.user_id == user.id))

    # 4. Reset onboarding state
    user.onboarding_completed_at = None
    user.user_type = None

    await session.commit()
    await session.refresh(user)
    return _user_response(user)
