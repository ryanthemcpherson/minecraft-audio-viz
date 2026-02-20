"""Authentication endpoints: register, login, Discord OAuth, token refresh."""

from __future__ import annotations

import logging
import secrets
import time
from datetime import datetime, timezone
from typing import Any

import jwt as pyjwt
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import HTMLResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.dependencies.auth import get_current_user, require_admin
from app.models.db import RefreshToken as RefreshTokenModel
from app.models.db import User
from app.models.schemas import (
    AuthResponse,
    ChangePasswordRequest,
    DeleteAccountRequest,
    DJProfileResponse,
    ExchangeCodeRequest,
    ForgotPasswordRequest,
    LoginRequest,
    LogoutRequest,
    OAuthAuthorizeResponse,
    OrgSummary,
    RefreshRequest,
    RegisterRequest,
    ResetPasswordRequest,
    SessionInfo,
    UpdateAccountRequest,
    UserProfileResponse,
    UserResponse,
    VerifyEmailRequest,
)
from app.services import auth_service, discord_oauth, google_oauth
from app.services.audit import log_auth_event
from app.services.email import send_password_reset_email, send_verification_email
from app.services.password import hash_password, verify_password

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"])

_OAUTH_STATE_TTL = 600  # seconds
_EXCHANGE_CODE_TTL = 60  # seconds

# In-memory store for desktop OAuth exchange codes: code -> (AuthResponse dict, created_at)
_desktop_exchange_codes: dict[str, tuple[dict[str, Any], float]] = {}


def _cleanup_expired_exchange_codes() -> None:
    """Remove expired desktop OAuth exchange codes to prevent memory leaks."""
    now = time.time()
    expired = [
        code
        for code, (_, created_at) in _desktop_exchange_codes.items()
        if now - created_at > _EXCHANGE_CODE_TTL
    ]
    for code in expired:
        del _desktop_exchange_codes[code]


def _create_oauth_state(
    jwt_secret: str, *, desktop: bool = False, provider: str = "discord"
) -> str:
    """Create a self-validating OAuth state token as a signed JWT."""
    now = int(time.time())
    payload: dict[str, Any] = {
        "nonce": secrets.token_urlsafe(16),
        "iat": now,
        "exp": now + _OAUTH_STATE_TTL,
        "provider": provider,
    }
    if desktop:
        payload["desktop"] = True
    return pyjwt.encode(payload, jwt_secret, algorithm="HS256")


def _validate_oauth_state(state: str, jwt_secret: str) -> dict[str, Any] | None:
    """Validate an OAuth state JWT. Returns decoded payload if valid, None otherwise."""
    try:
        return pyjwt.decode(state, jwt_secret, algorithms=["HS256"])
    except pyjwt.InvalidTokenError:
        return None


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
            is_admin=user.is_admin,
            email_verified=user.email_verified,
        ),
    )


def _client_ip(request: Request) -> str | None:
    """Extract client IP from a FastAPI request."""
    return request.client.host if request.client else None


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
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse:
    ip = _client_ip(request)
    ua = request.headers.get("user-agent")

    try:
        reg_result = await auth_service.register_email(
            email=body.email,
            password=body.password,
            display_name=body.display_name,
            session=session,
            jwt_secret=settings.user_jwt_secret,
            expiry_minutes=settings.user_jwt_expiry_minutes,
            refresh_expiry_days=settings.refresh_token_expiry_days,
            user_agent=ua,
            ip_address=ip,
        )
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc))

    await session.commit()

    result = reg_result.auth

    log_auth_event("register", user_id=str(result.user_id), email=body.email, ip_address=ip)

    # Send verification email (best-effort — don't block registration)
    if reg_result.verification_token:
        try:
            await send_verification_email(
                to_email=body.email,
                token=reg_result.verification_token,
                settings=settings,
            )
        except Exception:
            logger.warning("Failed to send verification email to %s", body.email, exc_info=True)

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
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse:
    ip = _client_ip(request)
    ua = request.headers.get("user-agent")

    try:
        result = await auth_service.login_email(
            email=body.email,
            password=body.password,
            session=session,
            jwt_secret=settings.user_jwt_secret,
            expiry_minutes=settings.user_jwt_expiry_minutes,
            refresh_expiry_days=settings.refresh_token_expiry_days,
            max_failed_attempts=settings.max_failed_login_attempts,
            lockout_duration_minutes=settings.lockout_duration_minutes,
            user_agent=ua,
            ip_address=ip,
        )
    except ValueError as exc:
        detail = str(exc)
        if "locked" in detail.lower():
            log_auth_event("login_locked", email=body.email, ip_address=ip)
        else:
            log_auth_event("login_failed", email=body.email, ip_address=ip)
        await session.commit()  # persist failed_login_attempts increment
        raise HTTPException(status_code=401, detail=detail)

    await session.commit()

    log_auth_event("login", user_id=str(result.user_id), email=body.email, ip_address=ip)

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
    response_model=OAuthAuthorizeResponse,
    summary="Get Discord OAuth authorize URL",
)
async def discord_authorize(
    desktop: bool = Query(False, description="Set to true for desktop app deep-link flow"),
    settings: Settings = Depends(get_settings),
) -> OAuthAuthorizeResponse:
    if not settings.discord_client_id:
        raise HTTPException(status_code=501, detail="Discord OAuth not configured")

    state = _create_oauth_state(settings.user_jwt_secret, desktop=desktop, provider="discord")
    url = discord_oauth.get_authorize_url(
        client_id=settings.discord_client_id,
        redirect_uri=settings.discord_redirect_uri,
        state=state,
    )
    return OAuthAuthorizeResponse(authorize_url=url, state=state)


# ---------------------------------------------------------------------------
# GET /auth/discord/callback
# ---------------------------------------------------------------------------


@router.get(
    "/discord/callback",
    response_model=None,
    summary="Discord OAuth callback",
)
async def discord_callback(
    request: Request,
    code: str = Query(...),
    state: str = Query(""),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse | HTMLResponse:
    if not settings.discord_client_id or not settings.discord_client_secret:
        raise HTTPException(status_code=501, detail="Discord OAuth not configured")

    state_payload = _validate_oauth_state(state, settings.user_jwt_secret)
    if not state_payload:
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

    ip = _client_ip(request)
    ua = request.headers.get("user-agent")

    result = await auth_service.login_discord(
        discord_id=discord_user.id,
        discord_username=discord_user.username,
        discord_email=discord_user.email,
        discord_avatar=discord_user.avatar,
        session=session,
        jwt_secret=settings.user_jwt_secret,
        expiry_minutes=settings.user_jwt_expiry_minutes,
        refresh_expiry_days=settings.refresh_token_expiry_days,
        user_agent=ua,
        ip_address=ip,
    )

    await session.commit()

    log_auth_event("login", user_id=str(result.user_id), ip_address=ip, detail="discord")

    from sqlalchemy import select

    from app.models.db import User as UserModel

    user = (
        await session.execute(select(UserModel).where(UserModel.id == result.user_id))
    ).scalar_one()

    auth_resp = _auth_response(result, user)

    # Desktop deep-link flow: store auth response behind a one-time exchange code
    # and redirect to the desktop app via custom URL scheme.
    if state_payload.get("desktop"):
        _cleanup_expired_exchange_codes()
        exchange_code = secrets.token_urlsafe(32)
        _desktop_exchange_codes[exchange_code] = (
            auth_resp.model_dump(mode="json"),
            time.time(),
        )
        scheme = settings.desktop_deep_link_scheme
        redirect_url = f"{scheme}://auth/callback?exchange_code={exchange_code}"
        html = (
            f"<html><head><meta http-equiv='refresh' content='0;url={redirect_url}'>"
            f"</head><body><p>Redirecting to MCAV DJ Client...</p>"
            f"<p><a href='{redirect_url}'>Click here if not redirected</a></p>"
            f"</body></html>"
        )
        return HTMLResponse(content=html)

    return auth_resp


# ---------------------------------------------------------------------------
# GET /auth/google
# ---------------------------------------------------------------------------


@router.get(
    "/google",
    response_model=OAuthAuthorizeResponse,
    summary="Get Google OAuth authorize URL",
)
async def google_authorize(
    desktop: bool = Query(False, description="Set to true for desktop app deep-link flow"),
    settings: Settings = Depends(get_settings),
) -> OAuthAuthorizeResponse:
    if not settings.google_client_id:
        raise HTTPException(status_code=501, detail="Google OAuth not configured")

    state = _create_oauth_state(settings.user_jwt_secret, desktop=desktop, provider="google")
    url = google_oauth.get_authorize_url(
        client_id=settings.google_client_id,
        redirect_uri=settings.google_redirect_uri,
        state=state,
    )
    return OAuthAuthorizeResponse(authorize_url=url, state=state)


# ---------------------------------------------------------------------------
# GET /auth/google/callback
# ---------------------------------------------------------------------------


@router.get(
    "/google/callback",
    response_model=None,
    summary="Google OAuth callback",
)
async def google_callback(
    request: Request,
    code: str = Query(...),
    state: str = Query(""),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse | HTMLResponse:
    if not settings.google_client_id or not settings.google_client_secret:
        raise HTTPException(status_code=501, detail="Google OAuth not configured")

    state_payload = _validate_oauth_state(state, settings.user_jwt_secret)
    if not state_payload:
        raise HTTPException(status_code=400, detail="Invalid or expired OAuth state")

    try:
        access_token = await google_oauth.exchange_code(
            code=code,
            client_id=settings.google_client_id,
            client_secret=settings.google_client_secret,
            redirect_uri=settings.google_redirect_uri,
        )
        google_user = await google_oauth.get_google_user(access_token)
    except Exception:
        logger.exception("Google OAuth exchange failed")
        raise HTTPException(status_code=400, detail="Google OAuth exchange failed")

    ip = _client_ip(request)
    ua = request.headers.get("user-agent")

    result = await auth_service.login_google(
        google_id=google_user.id,
        google_email=google_user.email,
        google_name=google_user.name,
        google_picture=google_user.picture,
        session=session,
        jwt_secret=settings.user_jwt_secret,
        expiry_minutes=settings.user_jwt_expiry_minutes,
        refresh_expiry_days=settings.refresh_token_expiry_days,
        user_agent=ua,
        ip_address=ip,
    )

    await session.commit()

    log_auth_event("login", user_id=str(result.user_id), ip_address=ip, detail="google")

    from sqlalchemy import select

    from app.models.db import User as UserModel

    user = (
        await session.execute(select(UserModel).where(UserModel.id == result.user_id))
    ).scalar_one()

    auth_resp = _auth_response(result, user)

    # Desktop deep-link flow
    if state_payload.get("desktop"):
        _cleanup_expired_exchange_codes()
        exchange_code = secrets.token_urlsafe(32)
        _desktop_exchange_codes[exchange_code] = (
            auth_resp.model_dump(mode="json"),
            time.time(),
        )
        scheme = settings.desktop_deep_link_scheme
        redirect_url = f"{scheme}://auth/callback?exchange_code={exchange_code}"
        html = (
            f"<html><head><meta http-equiv='refresh' content='0;url={redirect_url}'>"
            f"</head><body><p>Redirecting to MCAV DJ Client...</p>"
            f"<p><a href='{redirect_url}'>Click here if not redirected</a></p>"
            f"</body></html>"
        )
        return HTMLResponse(content=html)

    return auth_resp


# ---------------------------------------------------------------------------
# POST /auth/exchange
# ---------------------------------------------------------------------------


@router.post(
    "/exchange",
    response_model=AuthResponse,
    summary="Exchange a desktop OAuth code for tokens",
)
async def exchange_desktop_code(
    body: ExchangeCodeRequest,
) -> AuthResponse:
    """Exchange a one-time code (from the desktop deep-link callback) for auth tokens."""
    _cleanup_expired_exchange_codes()
    entry = _desktop_exchange_codes.pop(body.exchange_code, None)
    if entry is None:
        raise HTTPException(status_code=400, detail="Invalid or already used exchange code")

    data, created_at = entry
    if time.time() - created_at > _EXCHANGE_CODE_TTL:
        raise HTTPException(status_code=400, detail="Exchange code expired")

    return AuthResponse(**data)


# ---------------------------------------------------------------------------
# POST /auth/forgot-password
# ---------------------------------------------------------------------------


@router.post(
    "/forgot-password",
    summary="Request a password reset email",
)
async def forgot_password(
    body: ForgotPasswordRequest,
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> dict:
    ip = _client_ip(request)

    token = await auth_service.request_password_reset(
        email=body.email,
        session=session,
        expiry_minutes=settings.password_reset_expiry_minutes,
    )

    log_auth_event("password_reset_request", email=body.email, ip_address=ip)

    if token:
        try:
            await send_password_reset_email(
                to_email=body.email,
                reset_token=token,
                settings=settings,
            )
        except RuntimeError:
            await session.rollback()
            raise HTTPException(status_code=503, detail="Email service not configured")
        except Exception:
            logger.exception("Failed to send password reset email")
            await session.rollback()
            raise HTTPException(status_code=503, detail="Failed to send email")

    await session.commit()
    # Always return success to prevent email enumeration
    return {"message": "If an account with that email exists, a reset link has been sent."}


# ---------------------------------------------------------------------------
# POST /auth/reset-password
# ---------------------------------------------------------------------------


@router.post(
    "/reset-password",
    summary="Reset password with token",
)
async def reset_password_endpoint(
    body: ResetPasswordRequest,
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> dict:
    ip = _client_ip(request)

    try:
        await auth_service.reset_password(
            token_value=body.token,
            new_password=body.new_password,
            session=session,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    await session.commit()
    log_auth_event("password_reset_complete", ip_address=ip)
    return {"message": "Password has been reset. You can now log in with your new password."}


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
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthResponse:
    ip = _client_ip(request)
    ua = request.headers.get("user-agent")

    try:
        result = await auth_service.refresh_access_token(
            refresh_token_value=body.refresh_token,
            session=session,
            jwt_secret=settings.user_jwt_secret,
            expiry_minutes=settings.user_jwt_expiry_minutes,
            refresh_expiry_days=settings.refresh_token_expiry_days,
            user_agent=ua,
            ip_address=ip,
        )
    except ValueError as exc:
        raise HTTPException(status_code=401, detail=str(exc))

    await session.commit()

    log_auth_event("token_refreshed", user_id=str(result.user_id), ip_address=ip)

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

        block_palette = None
        if user.dj_profile.block_palette:
            try:
                parsed = json.loads(user.dj_profile.block_palette)
                if isinstance(parsed, list):
                    block_palette = parsed
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
            block_palette=block_palette,
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
        is_admin=user.is_admin,
        email_verified=user.email_verified,
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
    request: Request,
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

    log_auth_event("password_change", user_id=str(user.id), ip_address=_client_ip(request))

    return _build_user_profile_response(user)


# ---------------------------------------------------------------------------
# DELETE /auth/account
# ---------------------------------------------------------------------------


@router.delete(
    "/account",
    status_code=204,
    response_class=Response,
    summary="Delete (deactivate) current user account",
)
async def delete_account(
    body: DeleteAccountRequest,
    request: Request,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Response:
    # Email-auth users must confirm with their password
    if user.password_hash is not None:
        if not verify_password(body.password, user.password_hash):
            raise HTTPException(status_code=400, detail="Incorrect password")
    else:
        # OAuth-only users: password field is ignored but required by schema
        pass

    # Soft-delete: deactivate user
    user.is_active = False

    # Revoke all refresh tokens
    from sqlalchemy import select as sa_select

    from app.models.db import RefreshToken as RT

    active_tokens = (
        (await session.execute(sa_select(RT).where(RT.user_id == user.id, RT.revoked.is_(False))))
        .scalars()
        .all()
    )
    for t in active_tokens:
        t.revoked = True

    await session.commit()

    log_auth_event("account_deleted", user_id=str(user.id), ip_address=_client_ip(request))

    return Response(status_code=204)


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
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> Response:
    await auth_service.revoke_refresh_token(
        refresh_token_value=body.refresh_token,
        session=session,
    )
    await session.commit()

    log_auth_event("logout", ip_address=_client_ip(request))

    return Response(status_code=204)


# ---------------------------------------------------------------------------
# POST /auth/verify-email
# ---------------------------------------------------------------------------


@router.post(
    "/verify-email",
    summary="Verify email with token",
)
async def verify_email_endpoint(
    body: VerifyEmailRequest,
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> dict:
    try:
        await auth_service.verify_email(
            token_value=body.token,
            session=session,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    await session.commit()

    log_auth_event("email_verified", ip_address=_client_ip(request))

    return {"message": "Email verified successfully."}


# ---------------------------------------------------------------------------
# POST /auth/resend-verification
# ---------------------------------------------------------------------------


@router.post(
    "/resend-verification",
    summary="Resend email verification link",
)
async def resend_verification(
    request: Request,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> dict:
    if user.email_verified:
        return {"message": "Email is already verified."}

    if not user.email:
        raise HTTPException(status_code=400, detail="No email address on this account")

    token = await auth_service.create_email_verification(
        user_id=user.id,
        session=session,
    )

    try:
        await send_verification_email(
            to_email=user.email,
            token=token,
            settings=settings,
        )
    except RuntimeError:
        await session.rollback()
        raise HTTPException(status_code=503, detail="Email service not configured")
    except Exception:
        logger.exception("Failed to send verification email")
        await session.rollback()
        raise HTTPException(status_code=503, detail="Failed to send email")

    await session.commit()
    return {"message": "Verification email sent."}


# ---------------------------------------------------------------------------
# GET /auth/sessions
# ---------------------------------------------------------------------------


@router.get(
    "/sessions",
    response_model=list[SessionInfo],
    summary="List active sessions for the current user",
)
async def list_sessions(
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> list[SessionInfo]:
    from sqlalchemy import select as sa_select

    stmt = sa_select(RefreshTokenModel).where(
        RefreshTokenModel.user_id == user.id,
        RefreshTokenModel.revoked.is_(False),
    )
    rows = (await session.execute(stmt)).scalars().all()

    now = datetime.now(timezone.utc)
    result = []
    for row in rows:
        expires = (
            row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
        )
        if expires < now:
            continue  # skip expired

        result.append(
            SessionInfo(
                id=row.id,
                user_agent=row.user_agent,
                ip_address=row.ip_address,
                created_at=row.created_at,
                last_used_at=row.last_used_at,
                is_current=False,  # Can't determine from access token alone
            )
        )

    return result


# ---------------------------------------------------------------------------
# DELETE /auth/sessions/{session_id}
# ---------------------------------------------------------------------------


@router.delete(
    "/sessions/{session_id}",
    status_code=204,
    response_class=Response,
    summary="Revoke a specific session",
)
async def revoke_session(
    session_id: str,
    request: Request,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Response:
    import uuid as _uuid

    from sqlalchemy import select as sa_select

    try:
        sid = _uuid.UUID(session_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid session ID")

    stmt = sa_select(RefreshTokenModel).where(
        RefreshTokenModel.id == sid,
        RefreshTokenModel.user_id == user.id,
        RefreshTokenModel.revoked.is_(False),
    )
    token_row = (await session.execute(stmt)).scalar_one_or_none()
    if token_row is None:
        raise HTTPException(status_code=404, detail="Session not found")

    token_row.revoked = True
    await session.commit()

    log_auth_event(
        "session_revoked",
        user_id=str(user.id),
        ip_address=_client_ip(request),
        detail=str(sid),
    )

    return Response(status_code=204)


# ---------------------------------------------------------------------------
# POST /auth/admin/cleanup-tokens
# ---------------------------------------------------------------------------


@router.post(
    "/admin/cleanup-tokens",
    summary="Delete expired and revoked refresh tokens",
)
async def cleanup_tokens(
    _admin: User = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> dict:
    deleted = await auth_service.cleanup_expired_tokens(session)
    await session.commit()
    return {"deleted": deleted}
