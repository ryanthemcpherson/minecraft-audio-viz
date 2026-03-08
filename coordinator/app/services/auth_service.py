"""Authentication orchestration: register, login, Discord callback, token refresh."""

from __future__ import annotations

import hashlib
import hmac
import json as _json
import logging
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import delete as sa_delete
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import (
    DesktopExchangeCode,
    EmailVerificationToken,
    PasswordResetToken,
    RefreshToken,
    User,
    UserRole,
)
from app.services.password import hash_password, verify_password
from app.services.user_jwt import create_refresh_token_value, create_user_token

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class AuthResult:
    """Returned by login / register flows."""

    access_token: str
    refresh_token: str
    user_id: uuid.UUID
    display_name: str
    expires_in: int  # seconds


def _hash_refresh_token(value: str, *, secret: str) -> str:
    """HMAC-SHA256 hash of a refresh token for storage.

    Uses the JWT secret as the HMAC key so that token hashes are bound to the
    deployment's secret material — a leaked DB alone is not enough to forge a
    valid hash.

    NOTE: This is a breaking change. Existing refresh tokens stored with plain
    SHA-256 hashes will no longer match and will be effectively invalidated.
    This is acceptable for a security improvement.
    """
    return hmac.new(secret.encode(), value.encode(), hashlib.sha256).hexdigest()


async def _issue_tokens(
    *,
    user: User,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> AuthResult:
    """Create an access + refresh token pair for *user*."""
    access_token = create_user_token(
        user_id=user.id,
        jwt_secret=jwt_secret,
        expiry_minutes=expiry_minutes,
    )

    refresh_value = create_refresh_token_value()
    refresh_row = RefreshToken(
        id=uuid.uuid4(),
        user_id=user.id,
        token_hash=_hash_refresh_token(refresh_value, secret=jwt_secret),
        expires_at=datetime.now(timezone.utc) + timedelta(days=refresh_expiry_days),
        user_agent=user_agent[:500] if user_agent else None,
        ip_address=ip_address[:45] if ip_address else None,
    )
    session.add(refresh_row)
    await session.flush()

    return AuthResult(
        access_token=access_token,
        refresh_token=refresh_value,
        user_id=user.id,
        display_name=user.display_name,
        expires_in=expiry_minutes * 60,
    )


# ---------------------------------------------------------------------------
# User lookups
# ---------------------------------------------------------------------------


async def get_user_by_id(session: AsyncSession, user_id: uuid.UUID) -> User | None:
    """Fetch a user by primary key."""
    return (await session.execute(select(User).where(User.id == user_id))).scalar_one_or_none()


async def get_user_by_id_strict(session: AsyncSession, user_id: uuid.UUID) -> User:
    """Fetch a user by primary key, raising if not found."""
    return (await session.execute(select(User).where(User.id == user_id))).scalar_one()


async def get_user_by_discord_id(session: AsyncSession, discord_id: str) -> User | None:
    """Fetch a user by Discord ID."""
    return (
        await session.execute(select(User).where(User.discord_id == discord_id))
    ).scalar_one_or_none()


async def get_user_roles(session: AsyncSession, user_id: uuid.UUID) -> list[UserRole]:
    """Fetch all roles for a user."""
    result = await session.execute(select(UserRole).where(UserRole.user_id == user_id))
    return list(result.scalars().all())


# ---------------------------------------------------------------------------
# Desktop exchange codes
# ---------------------------------------------------------------------------


async def cleanup_expired_exchange_codes(session: AsyncSession) -> None:
    """Remove expired desktop OAuth exchange codes from the database."""
    now = datetime.now(timezone.utc)
    await session.execute(
        sa_delete(DesktopExchangeCode).where(DesktopExchangeCode.expires_at < now)
    )


async def find_exchange_code(
    session: AsyncSession, *, code: str | None = None, nonce: str | None = None
) -> DesktopExchangeCode | None:
    """Find an unused exchange code by code value or by nonce (poll token)."""
    if code is not None:
        stmt = select(DesktopExchangeCode).where(
            DesktopExchangeCode.code == code,
            DesktopExchangeCode.used.is_(False),
        )
    elif nonce is not None:
        stmt = select(DesktopExchangeCode).where(
            DesktopExchangeCode.nonce == nonce,
            DesktopExchangeCode.used.is_(False),
        )
    else:
        return None
    return (await session.execute(stmt)).scalar_one_or_none()


async def store_exchange_code(
    session: AsyncSession,
    *,
    code: str,
    user_id: uuid.UUID,
    nonce: str | None,
    payload: dict,
    ttl_seconds: int = 60,
) -> DesktopExchangeCode:
    """Store a desktop OAuth exchange code in the database."""
    row = DesktopExchangeCode(
        code=code,
        user_id=user_id,
        nonce=nonce,
        payload=_json.dumps(payload),
        expires_at=datetime.now(timezone.utc) + timedelta(seconds=ttl_seconds),
    )
    session.add(row)
    return row


# ---------------------------------------------------------------------------
# Session management
# ---------------------------------------------------------------------------


async def list_active_sessions(
    session: AsyncSession,
    user_id: uuid.UUID,
    *,
    limit: int = 50,
    offset: int = 0,
) -> list[RefreshToken]:
    """List active (non-revoked, non-expired) refresh tokens for a user."""
    now = datetime.now(timezone.utc)
    stmt = (
        select(RefreshToken)
        .where(
            RefreshToken.user_id == user_id,
            RefreshToken.revoked.is_(False),
            RefreshToken.expires_at > now,
        )
        .order_by(RefreshToken.created_at.desc())
        .offset(offset)
        .limit(limit)
    )
    return list((await session.execute(stmt)).scalars().all())


async def revoke_session_by_id(
    session: AsyncSession,
    session_id: uuid.UUID,
    user_id: uuid.UUID,
) -> RefreshToken | None:
    """Revoke a specific session (refresh token) by ID.

    Returns the token row if found and revoked, None otherwise.
    """
    stmt = select(RefreshToken).where(
        RefreshToken.id == session_id,
        RefreshToken.user_id == user_id,
        RefreshToken.revoked.is_(False),
    )
    token_row = (await session.execute(stmt)).scalar_one_or_none()
    if token_row is not None:
        token_row.revoked = True
    return token_row


async def revoke_all_user_tokens(session: AsyncSession, user_id: uuid.UUID) -> int:
    """Revoke all active refresh tokens for a user. Returns the count revoked."""
    stmt = select(RefreshToken).where(
        RefreshToken.user_id == user_id,
        RefreshToken.revoked.is_(False),
    )
    active_tokens = (await session.execute(stmt)).scalars().all()
    for t in active_tokens:
        t.revoked = True
    return len(active_tokens)


async def find_and_revoke_refresh_token(
    session: AsyncSession,
    *,
    refresh_token_value: str,
    jwt_secret: str,
    user_id: uuid.UUID,
) -> RefreshToken | None:
    """Find a refresh token by value and user, then revoke it.

    Returns the token row if found and revoked, None otherwise.
    """
    token_hash = _hash_refresh_token(refresh_token_value, secret=jwt_secret)
    stmt = select(RefreshToken).where(
        RefreshToken.token_hash == token_hash,
        RefreshToken.user_id == user_id,
    )
    token_row = (await session.execute(stmt)).scalar_one_or_none()
    if token_row is not None:
        token_row.revoked = True
    return token_row


# ---------------------------------------------------------------------------
# Email / password
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class RegisterResult:
    """Returned by register flow — includes auth + optional verification token."""

    auth: AuthResult
    verification_token: str | None = None


async def register_email(
    *,
    email: str,
    password: str,
    display_name: str,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> RegisterResult:
    """Register a new user with email / password."""
    stmt = select(User).where(User.email == email.lower())
    existing = (await session.execute(stmt)).scalar_one_or_none()
    if existing is not None:
        raise ValueError("Email already registered")

    user = User(
        id=uuid.uuid4(),
        email=email.lower().strip(),
        password_hash=hash_password(password),
        display_name=display_name,
        email_verified=False,
        last_login_at=datetime.now(timezone.utc),
    )
    session.add(user)
    await session.flush()

    # Create email verification token
    verification_token = await create_email_verification(
        user_id=user.id,
        session=session,
    )

    auth = await _issue_tokens(
        user=user,
        session=session,
        jwt_secret=jwt_secret,
        expiry_minutes=expiry_minutes,
        refresh_expiry_days=refresh_expiry_days,
        user_agent=user_agent,
        ip_address=ip_address,
    )

    return RegisterResult(auth=auth, verification_token=verification_token)


async def login_email(
    *,
    email: str,
    password: str,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
    max_failed_attempts: int = 5,
    lockout_duration_minutes: int = 15,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> AuthResult:
    """Authenticate with email / password."""
    stmt = select(User).where(User.email == email.lower(), User.is_active.is_(True))
    user = (await session.execute(stmt)).scalar_one_or_none()

    if user is None or user.password_hash is None:
        raise ValueError("Invalid email or password")

    # Check account lockout
    now = datetime.now(timezone.utc)
    if user.locked_until is not None:
        locked = (
            user.locked_until
            if user.locked_until.tzinfo
            else user.locked_until.replace(tzinfo=timezone.utc)
        )
        if locked > now:
            raise ValueError("Account temporarily locked. Try again later.")
        # Lockout expired — reset
        user.locked_until = None
        user.failed_login_attempts = 0

    if not verify_password(password, user.password_hash):
        user.failed_login_attempts = (user.failed_login_attempts or 0) + 1
        if user.failed_login_attempts >= max_failed_attempts:
            user.locked_until = now + timedelta(minutes=lockout_duration_minutes)
        raise ValueError("Invalid email or password")

    # Successful login — reset lockout counters
    user.failed_login_attempts = 0
    user.locked_until = None
    user.last_login_at = now

    return await _issue_tokens(
        user=user,
        session=session,
        jwt_secret=jwt_secret,
        expiry_minutes=expiry_minutes,
        refresh_expiry_days=refresh_expiry_days,
        user_agent=user_agent,
        ip_address=ip_address,
    )


# ---------------------------------------------------------------------------
# Discord OAuth
# ---------------------------------------------------------------------------


async def login_discord(
    *,
    discord_id: str,
    discord_username: str,
    discord_email: str | None,
    discord_avatar: str | None,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> AuthResult:
    """Find-or-create a user from a Discord profile, then issue tokens."""
    stmt = select(User).where(User.discord_id == discord_id)
    user = (await session.execute(stmt)).scalar_one_or_none()

    if user is None:
        # Check if a user with this email already exists
        email_conflict = False
        if discord_email:
            existing_by_email = (
                await session.execute(
                    select(User).where(User.email == discord_email.lower().strip())
                )
            ).scalar_one_or_none()
            if existing_by_email is not None:
                # Existing account already has Discord linked — this shouldn't happen
                # (would have been found by discord_id lookup above). Don't auto-merge;
                # create the new Discord account without the conflicting email.
                logger.warning(
                    "Discord OAuth email conflict: discord_id=%s email=%s matches "
                    "existing user_id=%s. Creating separate account without email.",
                    discord_id,
                    discord_email,
                    existing_by_email.id,
                )
                email_conflict = True

        # New user — create (email verified by Discord)
        avatar_url = (
            f"https://cdn.discordapp.com/avatars/{discord_id}/{discord_avatar}.png"
            if discord_avatar
            else None
        )
        user = User(
            id=uuid.uuid4(),
            discord_id=discord_id,
            discord_username=discord_username,
            email=discord_email.lower().strip() if discord_email and not email_conflict else None,
            display_name=discord_username,
            avatar_url=avatar_url,
            email_verified=True,
            last_login_at=datetime.now(timezone.utc),
        )
        session.add(user)
        await session.flush()
    else:
        # Existing user — update profile fields
        user.discord_username = discord_username
        user.email_verified = True
        user.last_login_at = datetime.now(timezone.utc)
        if discord_avatar:
            user.avatar_url = (
                f"https://cdn.discordapp.com/avatars/{discord_id}/{discord_avatar}.png"
            )

    return await _issue_tokens(
        user=user,
        session=session,
        jwt_secret=jwt_secret,
        expiry_minutes=expiry_minutes,
        refresh_expiry_days=refresh_expiry_days,
        user_agent=user_agent,
        ip_address=ip_address,
    )


# ---------------------------------------------------------------------------
# Google OAuth
# ---------------------------------------------------------------------------


async def login_google(
    *,
    google_id: str,
    google_email: str | None,
    google_name: str | None,
    google_picture: str | None,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> AuthResult:
    """Find-or-create a user from a Google profile, then issue tokens."""
    stmt = select(User).where(User.google_id == google_id)
    user = (await session.execute(stmt)).scalar_one_or_none()

    if user is None:
        # Check if a user with this email already exists
        email_conflict = False
        if google_email:
            existing_by_email = (
                await session.execute(
                    select(User).where(User.email == google_email.lower().strip())
                )
            ).scalar_one_or_none()
            if existing_by_email is not None:
                # Don't auto-merge — create the new Google account without the
                # conflicting email to prevent account takeover.
                logger.warning(
                    "Google OAuth email conflict: google_id=%s email=%s matches "
                    "existing user_id=%s. Creating separate account without email.",
                    google_id,
                    google_email,
                    existing_by_email.id,
                )
                email_conflict = True

        # New user — create (email verified by Google)
        user = User(
            id=uuid.uuid4(),
            google_id=google_id,
            email=google_email.lower().strip() if google_email and not email_conflict else None,
            display_name=google_name or google_email or "User",
            avatar_url=google_picture,
            email_verified=True,
            last_login_at=datetime.now(timezone.utc),
        )
        session.add(user)
        await session.flush()
    else:
        # Existing user — update profile fields
        user.email_verified = True
        user.last_login_at = datetime.now(timezone.utc)
        if google_name:
            user.display_name = google_name
        if google_picture:
            user.avatar_url = google_picture

    return await _issue_tokens(
        user=user,
        session=session,
        jwt_secret=jwt_secret,
        expiry_minutes=expiry_minutes,
        refresh_expiry_days=refresh_expiry_days,
        user_agent=user_agent,
        ip_address=ip_address,
    )


# ---------------------------------------------------------------------------
# Token refresh (rotation)
# ---------------------------------------------------------------------------


async def refresh_access_token(
    *,
    refresh_token_value: str,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> AuthResult:
    """Validate a refresh token and issue a new access + refresh pair.

    The old refresh token is revoked (rotation).
    """
    token_hash = _hash_refresh_token(refresh_token_value, secret=jwt_secret)

    stmt = select(RefreshToken).where(
        RefreshToken.token_hash == token_hash,
        RefreshToken.revoked.is_(False),
    )
    row = (await session.execute(stmt)).scalar_one_or_none()

    if row is None:
        raise ValueError("Invalid or revoked refresh token")

    # Compare in a timezone-safe way (SQLite returns naive datetimes)
    now = datetime.now(timezone.utc)
    expires = (
        row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    )
    if expires < now:
        row.revoked = True
        raise ValueError("Refresh token expired")

    # Revoke old token
    row.revoked = True

    # Load user
    user_stmt = select(User).where(User.id == row.user_id, User.is_active.is_(True))
    user = (await session.execute(user_stmt)).scalar_one_or_none()
    if user is None:
        raise ValueError("User not found or inactive")

    return await _issue_tokens(
        user=user,
        session=session,
        jwt_secret=jwt_secret,
        expiry_minutes=expiry_minutes,
        refresh_expiry_days=refresh_expiry_days,
        user_agent=user_agent,
        ip_address=ip_address,
    )


async def revoke_refresh_token(
    *,
    refresh_token_value: str,
    session: AsyncSession,
    jwt_secret: str,
) -> None:
    """Revoke a refresh token (logout)."""
    token_hash = _hash_refresh_token(refresh_token_value, secret=jwt_secret)
    stmt = select(RefreshToken).where(RefreshToken.token_hash == token_hash)
    row = (await session.execute(stmt)).scalar_one_or_none()
    if row is not None:
        row.revoked = True


async def cleanup_expired_tokens(session: AsyncSession) -> int:
    """Delete refresh tokens that are revoked or expired. Returns count deleted."""
    now = datetime.now(timezone.utc)
    stmt = sa_delete(RefreshToken).where(
        or_(
            RefreshToken.revoked.is_(True),
            RefreshToken.expires_at < now,
        )
    )
    result = await session.execute(stmt)
    return result.rowcount


# ---------------------------------------------------------------------------
# Password reset
# ---------------------------------------------------------------------------


async def request_password_reset(
    *,
    email: str,
    session: AsyncSession,
    expiry_minutes: int,
) -> str | None:
    """Create a password reset token for the user.

    Returns the raw token string, or None if no matching user found.
    Silently returns None for OAuth-only accounts to prevent enumeration.
    """
    stmt = select(User).where(User.email == email.lower(), User.is_active.is_(True))
    user = (await session.execute(stmt)).scalar_one_or_none()

    if user is None or user.password_hash is None:
        return None

    # Invalidate any prior unused tokens for this user
    prior_stmt = select(PasswordResetToken).where(
        PasswordResetToken.user_id == user.id,
        PasswordResetToken.used.is_(False),
    )
    prior_tokens = (await session.execute(prior_stmt)).scalars().all()
    for t in prior_tokens:
        t.used = True

    # Create new token
    raw_token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(raw_token.encode()).hexdigest()

    reset_token = PasswordResetToken(
        id=uuid.uuid4(),
        user_id=user.id,
        token_hash=token_hash,
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=expiry_minutes),
    )
    session.add(reset_token)
    await session.flush()

    return raw_token


async def reset_password(
    *,
    token_value: str,
    new_password: str,
    session: AsyncSession,
) -> None:
    """Reset a user's password using a valid reset token.

    Raises ValueError if the token is invalid, expired, or already used.
    """
    token_hash = hashlib.sha256(token_value.encode()).hexdigest()

    stmt = select(PasswordResetToken).where(
        PasswordResetToken.token_hash == token_hash,
        PasswordResetToken.used.is_(False),
    )
    row = (await session.execute(stmt)).scalar_one_or_none()

    if row is None:
        raise ValueError("Invalid or already used reset token")

    # Check expiry (handle naive datetimes from SQLite in tests)
    now = datetime.now(timezone.utc)
    expires = (
        row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    )
    if expires < now:
        row.used = True
        raise ValueError("Reset token has expired")

    # Mark token as used
    row.used = True

    # Update password
    user_stmt = select(User).where(User.id == row.user_id, User.is_active.is_(True))
    user = (await session.execute(user_stmt)).scalar_one_or_none()
    if user is None:
        raise ValueError("User not found or inactive")

    from app.services.password import hash_password

    user.password_hash = hash_password(new_password)

    # Revoke all refresh tokens (force re-login everywhere)
    revoke_stmt = select(RefreshToken).where(
        RefreshToken.user_id == user.id,
        RefreshToken.revoked.is_(False),
    )
    active_tokens = (await session.execute(revoke_stmt)).scalars().all()
    for t in active_tokens:
        t.revoked = True


# ---------------------------------------------------------------------------
# Email verification
# ---------------------------------------------------------------------------


async def create_email_verification(
    *,
    user_id: uuid.UUID,
    session: AsyncSession,
    expiry_minutes: int = 1440,
) -> str:
    """Create an email verification token. Returns the raw token string.

    Default expiry: 24 hours (1440 minutes).
    """
    # Invalidate prior unused tokens for this user
    prior_stmt = select(EmailVerificationToken).where(
        EmailVerificationToken.user_id == user_id,
        EmailVerificationToken.used.is_(False),
    )
    prior_tokens = (await session.execute(prior_stmt)).scalars().all()
    for t in prior_tokens:
        t.used = True

    raw_token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(raw_token.encode()).hexdigest()

    token_row = EmailVerificationToken(
        id=uuid.uuid4(),
        user_id=user_id,
        token_hash=token_hash,
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=expiry_minutes),
    )
    session.add(token_row)
    await session.flush()

    return raw_token


async def verify_email(
    *,
    token_value: str,
    session: AsyncSession,
) -> None:
    """Verify a user's email using a valid verification token.

    Raises ValueError if the token is invalid, expired, or already used.
    """
    token_hash = hashlib.sha256(token_value.encode()).hexdigest()

    stmt = select(EmailVerificationToken).where(
        EmailVerificationToken.token_hash == token_hash,
        EmailVerificationToken.used.is_(False),
    )
    row = (await session.execute(stmt)).scalar_one_or_none()

    if row is None:
        raise ValueError("Invalid or already used verification token")

    # Check expiry (handle naive datetimes from SQLite in tests)
    now = datetime.now(timezone.utc)
    expires = (
        row.expires_at if row.expires_at.tzinfo else row.expires_at.replace(tzinfo=timezone.utc)
    )
    if expires < now:
        row.used = True
        raise ValueError("Verification token has expired")

    # Mark token as used
    row.used = True

    # Set user email as verified
    user_stmt = select(User).where(User.id == row.user_id, User.is_active.is_(True))
    user = (await session.execute(user_stmt)).scalar_one_or_none()
    if user is None:
        raise ValueError("User not found or inactive")

    user.email_verified = True
