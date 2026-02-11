"""Authentication endpoints: register, login, Discord OAuth, token refresh."""

from __future__ import annotations

import logging
import secrets
import time
from typing import Dict

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import User
from app.models.schemas import (
    AuthResponse,
    DiscordAuthorizeResponse,
    DJProfileResponse,
    LoginRequest,
    LogoutRequest,
    OrgSummary,
    RefreshRequest,
    RegisterRequest,
    UserProfileResponse,
    UserResponse,
)
from app.services import auth_service, discord_oauth

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"])

# In-memory OAuth state store: state_token -> expiry_timestamp
# States expire after 10 minutes. Cleaned up lazily on new state creation.
_OAUTH_STATE_TTL = 600  # seconds
_oauth_states: Dict[str, float] = {}


def _store_oauth_state(state: str) -> None:
    """Store an OAuth state token and clean up expired entries."""
    now = time.time()
    # Lazy cleanup of expired states
    expired = [k for k, v in _oauth_states.items() if v < now]
    for k in expired:
        _oauth_states.pop(k, None)
    _oauth_states[state] = now + _OAUTH_STATE_TTL


def _validate_oauth_state(state: str) -> bool:
    """Validate and consume an OAuth state token. Returns True if valid."""
    expiry = _oauth_states.pop(state, None)
    if expiry is None:
        return False
    return time.time() < expiry


def _auth_response(result: auth_service.AuthResult, user: User) -> AuthResponse:
    """Build a standard ``AuthResponse`` from an ``AuthResult`` + ``User``."""
    return AuthResponse(
        access_token=result.access_token,
        refresh_token=result.refresh_token,
        expires_in=result.expires_in,
        user=UserResponse(
            id=user.id,
            display_name=user.display_name,
            email=user.email,
            discord_username=user.discord_username,
            avatar_url=user.avatar_url,
            onboarding_completed=user.onboarding_completed_at is not None,
        ),
    )


# ---------------------------------------------------------------------------
# POST /auth/register
# ---------------------------------------------------------------------------


@router.post(
    "/register",
    response_model=AuthResponse,
    status_code=201,
    summary="Register with email and password",
)
async def register(
    body: RegisterRequest,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse:
    try:
        result = await auth_service.register_email(
            email=body.email,
            password=body.password,
            display_name=body.display_name,
            session=session,
            jwt_secret=settings.user_jwt_secret,
            expiry_minutes=settings.user_jwt_expiry_minutes,
            refresh_expiry_days=settings.refresh_token_expiry_days,
        )
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc))

    await session.commit()

    # Re-fetch user for response
    from sqlalchemy import select

    from app.models.db import User as UserModel

    user = (
        await session.execute(select(UserModel).where(UserModel.id == result.user_id))
    ).scalar_one()

    return _auth_response(result, user)


# ---------------------------------------------------------------------------
# POST /auth/login
# ---------------------------------------------------------------------------


@router.post(
    "/login",
    response_model=AuthResponse,
    summary="Login with email and password",
)
async def login(
    body: LoginRequest,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse:
    try:
        result = await auth_service.login_email(
            email=body.email,
            password=body.password,
            session=session,
            jwt_secret=settings.user_jwt_secret,
            expiry_minutes=settings.user_jwt_expiry_minutes,
            refresh_expiry_days=settings.refresh_token_expiry_days,
        )
    except ValueError as exc:
        raise HTTPException(status_code=401, detail=str(exc))

    await session.commit()

    from sqlalchemy import select

    from app.models.db import User as UserModel

    user = (
        await session.execute(select(UserModel).where(UserModel.id == result.user_id))
    ).scalar_one()

    return _auth_response(result, user)


# ---------------------------------------------------------------------------
# GET /auth/discord
# ---------------------------------------------------------------------------


@router.get(
    "/discord",
    response_model=DiscordAuthorizeResponse,
    summary="Get Discord OAuth authorize URL",
)
async def discord_authorize(
    settings: Settings = Depends(get_settings),
) -> DiscordAuthorizeResponse:
    if not settings.discord_client_id:
        raise HTTPException(status_code=501, detail="Discord OAuth not configured")

    state = secrets.token_urlsafe(32)
    _store_oauth_state(state)
    url = discord_oauth.get_authorize_url(
        client_id=settings.discord_client_id,
        redirect_uri=settings.discord_redirect_uri,
        state=state,
    )
    return DiscordAuthorizeResponse(authorize_url=url, state=state)


# ---------------------------------------------------------------------------
# GET /auth/discord/callback
# ---------------------------------------------------------------------------


@router.get(
    "/discord/callback",
    response_model=AuthResponse,
    summary="Discord OAuth callback",
)
async def discord_callback(
    code: str = Query(...),
    state: str = Query(""),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse:
    if not settings.discord_client_id or not settings.discord_client_secret:
        raise HTTPException(status_code=501, detail="Discord OAuth not configured")

    if not state or not _validate_oauth_state(state):
        raise HTTPException(status_code=400, detail="Invalid or expired OAuth state")

    try:
        access_token = await discord_oauth.exchange_code(
            code=code,
            client_id=settings.discord_client_id,
            client_secret=settings.discord_client_secret,
            redirect_uri=settings.discord_redirect_uri,
        )
        discord_user = await discord_oauth.get_discord_user(access_token)
    except Exception:
        logger.exception("Discord OAuth exchange failed")
        raise HTTPException(status_code=400, detail="Discord OAuth exchange failed")

    result = await auth_service.login_discord(
        discord_id=discord_user.id,
        discord_username=discord_user.username,
        discord_email=discord_user.email,
        discord_avatar=discord_user.avatar,
        session=session,
        jwt_secret=settings.user_jwt_secret,
        expiry_minutes=settings.user_jwt_expiry_minutes,
        refresh_expiry_days=settings.refresh_token_expiry_days,
    )

    await session.commit()

    from sqlalchemy import select

    from app.models.db import User as UserModel

    user = (
        await session.execute(select(UserModel).where(UserModel.id == result.user_id))
    ).scalar_one()

    return _auth_response(result, user)


# ---------------------------------------------------------------------------
# POST /auth/refresh
# ---------------------------------------------------------------------------


@router.post(
    "/refresh",
    response_model=AuthResponse,
    summary="Refresh access token",
)
async def refresh(
    body: RefreshRequest,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse:
    try:
        result = await auth_service.refresh_access_token(
            refresh_token_value=body.refresh_token,
            session=session,
            jwt_secret=settings.user_jwt_secret,
            expiry_minutes=settings.user_jwt_expiry_minutes,
            refresh_expiry_days=settings.refresh_token_expiry_days,
        )
    except ValueError as exc:
        raise HTTPException(status_code=401, detail=str(exc))

    await session.commit()

    from sqlalchemy import select

    from app.models.db import User as UserModel

    user = (
        await session.execute(select(UserModel).where(UserModel.id == result.user_id))
    ).scalar_one()

    return _auth_response(result, user)


# ---------------------------------------------------------------------------
# GET /auth/me
# ---------------------------------------------------------------------------


@router.get(
    "/me",
    response_model=UserProfileResponse,
    summary="Get current user profile",
)
async def me(
    user: User = Depends(get_current_user),
) -> UserProfileResponse:
    orgs = [
        OrgSummary(
            id=m.organization.id,
            name=m.organization.name,
            slug=m.organization.slug,
            role=m.role,
        )
        for m in user.org_memberships
    ]

    dj_profile = None
    if user.dj_profile is not None:
        dj_profile = DJProfileResponse(
            id=user.dj_profile.id,
            user_id=user.dj_profile.user_id,
            dj_name=user.dj_profile.dj_name,
            bio=user.dj_profile.bio,
            genres=user.dj_profile.genres,
            avatar_url=user.dj_profile.avatar_url,
            is_public=user.dj_profile.is_public,
            created_at=user.dj_profile.created_at,
        )

    return UserProfileResponse(
        id=user.id,
        display_name=user.display_name,
        email=user.email,
        discord_username=user.discord_username,
        avatar_url=user.avatar_url,
        onboarding_completed=user.onboarding_completed_at is not None,
        user_type=user.user_type,
        dj_profile=dj_profile,
        organizations=orgs,
    )


# ---------------------------------------------------------------------------
# POST /auth/logout
# ---------------------------------------------------------------------------


@router.post(
    "/logout",
    status_code=204,
    response_class=Response,
    summary="Revoke refresh token",
)
async def logout(
    body: LogoutRequest,
    session: AsyncSession = Depends(get_session),
) -> Response:
    await auth_service.revoke_refresh_token(
        refresh_token_value=body.refresh_token,
        session=session,
    )
    await session.commit()
    return Response(status_code=204)
