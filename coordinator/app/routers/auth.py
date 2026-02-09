"""Authentication endpoints: register, login, Discord OAuth, token refresh."""

from __future__ import annotations

import secrets

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.dependencies.auth import get_current_user
from app.models.db import User
from app.models.schemas import (
    AuthResponse,
    DiscordAuthorizeResponse,
    LoginRequest,
    LogoutRequest,
    OrgSummary,
    RefreshRequest,
    RegisterRequest,
    UserProfileResponse,
    UserResponse,
)
from app.services import auth_service, discord_oauth

router = APIRouter(prefix="/auth", tags=["auth"])


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
    url = discord_oauth.get_authorize_url(
        client_id=settings.discord_client_id,
        redirect_uri=settings.discord_redirect_uri,
        state=state,
    )
    return DiscordAuthorizeResponse(authorize_url=url)


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

    try:
        access_token = await discord_oauth.exchange_code(
            code=code,
            client_id=settings.discord_client_id,
            client_secret=settings.discord_client_secret,
            redirect_uri=settings.discord_redirect_uri,
        )
        discord_user = await discord_oauth.get_discord_user(access_token)
    except Exception:
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
    return UserProfileResponse(
        id=user.id,
        display_name=user.display_name,
        email=user.email,
        discord_username=user.discord_username,
        avatar_url=user.avatar_url,
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
