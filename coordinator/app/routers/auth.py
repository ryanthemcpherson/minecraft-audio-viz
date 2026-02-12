"""Authentication endpoints: register, login, Discord OAuth, token refresh."""

from __future__ import annotations

import logging
import secrets
import time

import jwt as pyjwt
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import User
from app.models.schemas import (
    AuthResponse,
    ChangePasswordRequest,
    DiscordAuthorizeResponse,
    DJProfileResponse,
    LoginRequest,
    LogoutRequest,
    OrgSummary,
    RefreshRequest,
    RegisterRequest,
    UpdateAccountRequest,
    UserProfileResponse,
    UserResponse,
)
from app.services import auth_service, discord_oauth
from app.services.password import hash_password, verify_password

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"])

_OAUTH_STATE_TTL = 600  # seconds


def _create_oauth_state(jwt_secret: str) -> str:
    """Create a self-validating OAuth state token as a signed JWT."""
    now = int(time.time())
    payload = {
        "nonce": secrets.token_urlsafe(16),
        "iat": now,
        "exp": now + _OAUTH_STATE_TTL,
    }
    return pyjwt.encode(payload, jwt_secret, algorithm="HS256")


def _validate_oauth_state(state: str, jwt_secret: str) -> bool:
    """Validate an OAuth state JWT. Returns True if valid and not expired."""
    try:
        pyjwt.decode(state, jwt_secret, algorithms=["HS256"])
        return True
    except pyjwt.InvalidTokenError:
        return False


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

    state = _create_oauth_state(settings.user_jwt_secret)
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

    if not state or not _validate_oauth_state(state, settings.user_jwt_secret):
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


def _build_user_profile_response(user: User) -> UserProfileResponse:
    """Build a ``UserProfileResponse`` from a fully-loaded ``User``."""
    orgs = [
        OrgSummary(
            id=m.organization.id,
            name=m.organization.name,
            slug=m.organization.slug,
            role=m.role,
        )
        for m in user.org_memberships
        if m.organization is not None
    ]

    dj_profile = None
    if user.dj_profile is not None:
        import json

        color_palette = None
        if user.dj_profile.color_palette:
            try:
                parsed = json.loads(user.dj_profile.color_palette)
                if isinstance(parsed, list):
                    color_palette = parsed
            except (json.JSONDecodeError, TypeError):
                pass

        dj_profile = DJProfileResponse(
            id=user.dj_profile.id,
            user_id=user.dj_profile.user_id,
            dj_name=user.dj_profile.dj_name,
            bio=user.dj_profile.bio,
            genres=user.dj_profile.genres,
            avatar_url=user.dj_profile.avatar_url,
            banner_url=user.dj_profile.banner_url,
            color_palette=color_palette,
            slug=user.dj_profile.slug,
            soundcloud_url=user.dj_profile.soundcloud_url,
            spotify_url=user.dj_profile.spotify_url,
            website_url=user.dj_profile.website_url,
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


@router.get(
    "/me",
    response_model=UserProfileResponse,
    summary="Get current user profile",
)
async def me(
    user: User = Depends(get_current_user),
) -> UserProfileResponse:
    return _build_user_profile_response(user)


# ---------------------------------------------------------------------------
# PATCH /auth/me
# ---------------------------------------------------------------------------


@router.patch(
    "/me",
    response_model=UserProfileResponse,
    summary="Update current user account",
)
async def update_me(
    body: UpdateAccountRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserProfileResponse:
    if body.display_name is not None:
        user.display_name = body.display_name

    await session.commit()
    return _build_user_profile_response(user)


# ---------------------------------------------------------------------------
# POST /auth/change-password
# ---------------------------------------------------------------------------


@router.post(
    "/change-password",
    response_model=UserProfileResponse,
    summary="Change password for the current user",
)
async def change_password(
    body: ChangePasswordRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> UserProfileResponse:
    if user.password_hash is None:
        raise HTTPException(
            status_code=400,
            detail="Account uses Discord login — set a password via email registration first",
        )

    if not verify_password(body.current_password, user.password_hash):
        raise HTTPException(status_code=400, detail="Current password is incorrect")

    user.password_hash = hash_password(body.new_password)
    await session.commit()
    return _build_user_profile_response(user)


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


# ---------------------------------------------------------------------------
# POST /auth/admin/cleanup-tokens
# ---------------------------------------------------------------------------


@router.post(
    "/admin/cleanup-tokens",
    summary="Delete expired and revoked refresh tokens",
)
async def cleanup_tokens(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> dict:
    deleted = await auth_service.cleanup_expired_tokens(session)
    await session.commit()
    return {"deleted": deleted}
