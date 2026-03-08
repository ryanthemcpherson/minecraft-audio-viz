"""Authentication endpoints: register, login, Discord OAuth, token refresh."""

from __future__ import annotations

import json as _json
import logging
import secrets
import time
import uuid as _uuid
from datetime import datetime, timezone
from typing import Any

import jwt as pyjwt
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import HTMLResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.database import get_session
from app.dependencies.auth import get_current_user, require_admin
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
from app.services.discord_bot_notifier import notify_role_change
from app.services.email import send_password_reset_email, send_verification_email
from app.services.password import hash_password, verify_password

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"])

_OAUTH_STATE_TTL = 600  # seconds
_EXCHANGE_CODE_TTL = 60  # seconds


def _create_oauth_state(
    jwt_secret: str,
    *,
    desktop: bool = False,
    provider: str = "discord",
    redirect_uri: str = "",
) -> str:
    """Create a self-validating OAuth state token as a signed JWT."""
    now = int(time.time())
    payload: dict[str, Any] = {
        "nonce": secrets.token_urlsafe(16),
        "iat": now,
        "exp": now + _OAUTH_STATE_TTL,
        "provider": provider,
        "redirect_uri": redirect_uri,
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
    """Create a new account with email and password.  Returns access and
    refresh tokens.  Sends a verification email (best-effort).  Returns 409
    if the email is already registered.
    """
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
    user = await auth_service.get_user_by_id_strict(session, result.user_id)

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
    """Authenticate with email and password.  Returns access and refresh
    tokens.  Returns 401 on invalid credentials or if the account is locked
    after too many failed attempts.
    """
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

    user = await auth_service.get_user_by_id_strict(session, result.user_id)

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
    """Return a Discord OAuth2 authorize URL and CSRF state token.  When
    ``desktop=true``, also returns a ``poll_token`` for the desktop polling flow.
    Returns 501 if Discord OAuth is not configured.
    """
    if not settings.discord_client_id:
        raise HTTPException(status_code=501, detail="Discord OAuth not configured")

    state = _create_oauth_state(
        settings.user_jwt_secret,
        desktop=desktop,
        provider="discord",
        redirect_uri=settings.discord_redirect_uri,
    )
    url = discord_oauth.get_authorize_url(
        client_id=settings.discord_client_id,
        redirect_uri=settings.discord_redirect_uri,
        state=state,
    )
    if desktop:
        state_payload = pyjwt.decode(state, settings.user_jwt_secret, algorithms=["HS256"])
        return OAuthAuthorizeResponse(
            authorize_url=url, state=state, poll_token=state_payload["nonce"]
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
    """Handle the Discord OAuth2 redirect.  Exchanges the authorization code
    for tokens, creates or updates the user, and returns an ``AuthResponse``.
    For desktop flows, stores an exchange code and returns an HTML success page.
    """
    if not settings.discord_client_id or not settings.discord_client_secret:
        raise HTTPException(status_code=501, detail="Discord OAuth not configured")

    state_payload = _validate_oauth_state(state, settings.user_jwt_secret)
    if not state_payload:
        raise HTTPException(status_code=400, detail="Invalid or expired OAuth state")

    # Verify redirect_uri matches to prevent authorization code injection
    state_redirect = state_payload.get("redirect_uri", "")
    if state_redirect != settings.discord_redirect_uri:
        raise HTTPException(status_code=400, detail="OAuth redirect_uri mismatch")

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

    # Check if this Discord ID is already linked (to detect first-time link)
    existing_discord_user = await auth_service.get_user_by_discord_id(session, discord_user.id)
    is_new_discord_link = existing_discord_user is None

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

    user = await auth_service.get_user_by_id_strict(session, result.user_id)

    # Notify community bot on first Discord link
    if is_new_discord_link and user.discord_id:
        role_rows = await auth_service.get_user_roles(session, user.id)
        await notify_role_change(
            settings=settings,
            discord_id=user.discord_id,
            user_id=user.id,
            roles=[r.role.value for r in role_rows],
        )

    auth_resp = _auth_response(result, user)

    # Desktop deep-link flow: store auth response behind a one-time exchange code.
    # The client can either catch the deep link OR poll for completion via nonce.
    if state_payload.get("desktop"):
        await auth_service.cleanup_expired_exchange_codes(session)
        exchange_code = secrets.token_urlsafe(32)
        nonce = state_payload.get("nonce")
        await auth_service.store_exchange_code(
            session,
            code=exchange_code,
            user_id=user.id,
            nonce=nonce,
            payload=auth_resp.model_dump(mode="json"),
            ttl_seconds=_EXCHANGE_CODE_TTL,
        )
        await session.commit()
        html = (
            "<html><head>"
            "<style>body{background:#08090d;color:#f5f5f5;font-family:'Inter',system-ui,sans-serif;"
            "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
            ".card{text-align:center;padding:2rem;max-width:400px}"
            "h2{margin:0 0 .5rem;font-size:1.4rem}"
            "p{color:#a1a1aa;margin:.5rem 0;line-height:1.5}"
            ".check{font-size:2.5rem;margin-bottom:.5rem}</style>"
            "</head><body><div class='card'>"
            "<div class='check'>&#10003;</div>"
            "<h2>Login successful</h2>"
            "<p>You can close this tab and return to the MCAV DJ Client.</p>"
            "</div></body></html>"
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
    """Return a Google OAuth2 authorize URL and CSRF state token.  When
    ``desktop=true``, also returns a ``poll_token`` for the desktop polling flow.
    Returns 501 if Google OAuth is not configured.
    """
    if not settings.google_client_id:
        raise HTTPException(status_code=501, detail="Google OAuth not configured")

    state = _create_oauth_state(
        settings.user_jwt_secret,
        desktop=desktop,
        provider="google",
        redirect_uri=settings.google_redirect_uri,
    )
    url = google_oauth.get_authorize_url(
        client_id=settings.google_client_id,
        redirect_uri=settings.google_redirect_uri,
        state=state,
    )
    if desktop:
        state_payload = pyjwt.decode(state, settings.user_jwt_secret, algorithms=["HS256"])
        return OAuthAuthorizeResponse(
            authorize_url=url, state=state, poll_token=state_payload["nonce"]
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
    """Handle the Google OAuth2 redirect.  Exchanges the authorization code
    for tokens, creates or updates the user, and returns an ``AuthResponse``.
    For desktop flows, stores an exchange code and returns an HTML success page.
    """
    if not settings.google_client_id or not settings.google_client_secret:
        raise HTTPException(status_code=501, detail="Google OAuth not configured")

    state_payload = _validate_oauth_state(state, settings.user_jwt_secret)
    if not state_payload:
        raise HTTPException(status_code=400, detail="Invalid or expired OAuth state")

    # Verify redirect_uri matches to prevent authorization code injection
    state_redirect = state_payload.get("redirect_uri", "")
    if state_redirect != settings.google_redirect_uri:
        raise HTTPException(status_code=400, detail="OAuth redirect_uri mismatch")

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

    user = await auth_service.get_user_by_id_strict(session, result.user_id)

    auth_resp = _auth_response(result, user)

    # Desktop deep-link flow
    if state_payload.get("desktop"):
        await auth_service.cleanup_expired_exchange_codes(session)
        exchange_code = secrets.token_urlsafe(32)
        nonce = state_payload.get("nonce")
        await auth_service.store_exchange_code(
            session,
            code=exchange_code,
            user_id=user.id,
            nonce=nonce,
            payload=auth_resp.model_dump(mode="json"),
            ttl_seconds=_EXCHANGE_CODE_TTL,
        )
        await session.commit()
        html = (
            "<html><head>"
            "<style>body{background:#08090d;color:#f5f5f5;font-family:'Inter',system-ui,sans-serif;"
            "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
            ".card{text-align:center;padding:2rem;max-width:400px}"
            "h2{margin:0 0 .5rem;font-size:1.4rem}"
            "p{color:#a1a1aa;margin:.5rem 0;line-height:1.5}"
            ".check{font-size:2.5rem;margin-bottom:.5rem}</style>"
            "</head><body><div class='card'>"
            "<div class='check'>&#10003;</div>"
            "<h2>Login successful</h2>"
            "<p>You can close this tab and return to the MCAV DJ Client.</p>"
            "</div></body></html>"
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
    session: AsyncSession = Depends(get_session),
) -> AuthResponse:
    """Exchange a one-time code (from the desktop deep-link callback) for auth tokens."""
    await auth_service.cleanup_expired_exchange_codes(session)

    row = await auth_service.find_exchange_code(session, code=body.exchange_code)

    if row is None:
        raise HTTPException(status_code=400, detail="Invalid or already used exchange code")

    now = datetime.now(timezone.utc)
    expires = (
        row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    )
    if expires < now:
        row.used = True
        await session.commit()
        raise HTTPException(status_code=400, detail="Exchange code expired")

    # Mark as used (one-time)
    row.used = True
    await session.commit()

    data = _json.loads(row.payload)
    return AuthResponse(**data)


# ---------------------------------------------------------------------------
# GET /auth/desktop-poll/{poll_token}
# ---------------------------------------------------------------------------


@router.get(
    "/desktop-poll/{poll_token}",
    summary="Poll for desktop OAuth completion",
)
async def desktop_poll(
    poll_token: str,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Poll for desktop OAuth completion. Returns exchange_code when ready."""
    row = await auth_service.find_exchange_code(session, nonce=poll_token)
    if row is None:
        return {"status": "pending"}
    now = datetime.now(timezone.utc)
    expires = (
        row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    )
    if expires < now:
        return {"status": "expired"}
    return {"status": "complete", "exchange_code": row.code}


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
    """Request a password-reset email.  Always returns success to prevent
    email enumeration, even if no account matches the provided email.
    """
    ip = _client_ip(request)

    token = await auth_service.request_password_reset(
        email=body.email,
        session=session,
        expiry_minutes=settings.password_reset_expiry_minutes,
    )

    log_auth_event("password_reset_request", email=body.email, ip_address=ip)

    # Commit the token first, then send email as best-effort.
    # This prevents dirty session state if the email send raises an
    # unexpected exception, and avoids locking users out of password reset.
    await session.commit()

    if token:
        try:
            await send_password_reset_email(
                to_email=body.email,
                reset_token=token,
                settings=settings,
            )
        except RuntimeError:
            logger.warning("Email service not configured for password reset")
        except Exception:
            logger.exception("Failed to send password reset email")
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
    """Reset a user's password using a one-time token from the reset email.
    Returns 400 if the token is invalid or expired.
    """
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
    """Exchange a valid refresh token for a new access/refresh token pair.
    The old refresh token is rotated.  Returns 401 if the token is invalid,
    expired, or revoked.
    """
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

    user = await auth_service.get_user_by_id_strict(session, result.user_id)

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
        color_palette = None
        if user.dj_profile.color_palette:
            try:
                parsed = _json.loads(user.dj_profile.color_palette)
                if isinstance(parsed, list):
                    color_palette = parsed
            except (_json.JSONDecodeError, TypeError):
                pass

        block_palette = None
        if user.dj_profile.block_palette:
            try:
                parsed = _json.loads(user.dj_profile.block_palette)
                if isinstance(parsed, list):
                    block_palette = parsed
            except (_json.JSONDecodeError, TypeError):
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
    """Return the authenticated user's full profile, including organizations
    and DJ profile if present.  Requires a valid access token.
    """
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
    """Update the authenticated user's account fields (currently display_name).
    Only provided fields are updated; omitted fields are left unchanged.
    """
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
    """Change the authenticated user's password.  Requires the current password
    for verification.  Returns 400 if the account uses OAuth-only login or if
    the current password is incorrect.
    """
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
    """Soft-delete the authenticated user's account (deactivate) and revoke
    all refresh tokens.  Email-auth users must confirm with their password.
    """
    # Email-auth users must confirm with their password
    if user.password_hash is not None:
        if not verify_password(body.password, user.password_hash):
            raise HTTPException(status_code=400, detail="Incorrect password")
    else:
        # OAuth-only users must explicitly confirm deletion
        if not getattr(body, "confirm_delete", False):
            raise HTTPException(status_code=400, detail="OAuth users must set confirm_delete=true")

    # Soft-delete: deactivate user
    user.is_active = False

    # Revoke all refresh tokens
    await auth_service.revoke_all_user_tokens(session, user.id)

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
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> Response:
    """Revoke a specific refresh token, ending that session.  The token must
    belong to the authenticated user.  Returns 404 if not found.
    """
    token_row = await auth_service.find_and_revoke_refresh_token(
        session,
        refresh_token_value=body.refresh_token,
        jwt_secret=settings.user_jwt_secret,
        user_id=user.id,
    )
    if token_row is None:
        raise HTTPException(status_code=404, detail="Refresh token not found for this user")

    await session.commit()

    log_auth_event("logout", user_id=str(user.id), ip_address=_client_ip(request))

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
    """Verify a user's email address using the one-time token sent via email.
    Returns 400 if the token is invalid or expired.
    """
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
    """Resend the email verification link for the authenticated user.  Returns
    a no-op message if the email is already verified.  Returns 503 if the
    email service is unavailable.
    """
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
    limit: int = Query(50, ge=1, le=100, description="Max sessions to return"),
    offset: int = Query(0, ge=0, description="Number of sessions to skip"),
) -> list[SessionInfo]:
    """List active (non-revoked, non-expired) sessions for the authenticated
    user, ordered by most recent first.  Supports pagination via ``limit``
    and ``offset``.
    """
    rows = await auth_service.list_active_sessions(session, user.id, limit=limit, offset=offset)

    result = []
    for row in rows:
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
    """Revoke a specific session by ID, invalidating its refresh token.
    The session must belong to the authenticated user.
    """
    try:
        sid = _uuid.UUID(session_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid session ID")

    token_row = await auth_service.revoke_session_by_id(session, sid, user.id)
    if token_row is None:
        raise HTTPException(status_code=404, detail="Session not found")

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
    """Admin-only: delete expired and revoked refresh tokens from the database.
    Returns the count of deleted rows.
    """
    deleted = await auth_service.cleanup_expired_tokens(session)
    await session.commit()
    return {"deleted": deleted}
