"""Authentication orchestration: register, login, Discord callback, token refresh."""

from __future__ import annotations

import hashlib
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import delete, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import RefreshToken, User
from app.services.password import hash_password, verify_password
from app.services.user_jwt import create_refresh_token_value, create_user_token


@dataclass(frozen=True)
class AuthResult:
    """Returned by login / register flows."""

    access_token: str
    refresh_token: str
    user_id: uuid.UUID
    display_name: str
    expires_in: int  # seconds


def _hash_refresh_token(value: str) -> str:
    """SHA-256 hash of a refresh token for storage (fast lookup + not stored in clear)."""
    return hashlib.sha256(value.encode()).hexdigest()


async def _issue_tokens(
    *,
    user: User,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
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
        token_hash=_hash_refresh_token(refresh_value),
        expires_at=datetime.now(timezone.utc) + timedelta(days=refresh_expiry_days),
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
# Email / password
# ---------------------------------------------------------------------------


async def register_email(
    *,
    email: str,
    password: str,
    display_name: str,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
) -> AuthResult:
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
        last_login_at=datetime.now(timezone.utc),
    )
    session.add(user)
    await session.flush()

    return await _issue_tokens(
        user=user,
        session=session,
        jwt_secret=jwt_secret,
        expiry_minutes=expiry_minutes,
        refresh_expiry_days=refresh_expiry_days,
    )


async def login_email(
    *,
    email: str,
    password: str,
    session: AsyncSession,
    jwt_secret: str,
    expiry_minutes: int,
    refresh_expiry_days: int,
) -> AuthResult:
    """Authenticate with email / password."""
    stmt = select(User).where(User.email == email.lower(), User.is_active.is_(True))
    user = (await session.execute(stmt)).scalar_one_or_none()

    if user is None or user.password_hash is None:
        raise ValueError("Invalid email or password")

    if not verify_password(password, user.password_hash):
        raise ValueError("Invalid email or password")

    user.last_login_at = datetime.now(timezone.utc)

    return await _issue_tokens(
        user=user,
        session=session,
        jwt_secret=jwt_secret,
        expiry_minutes=expiry_minutes,
        refresh_expiry_days=refresh_expiry_days,
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
) -> AuthResult:
    """Find-or-create a user from a Discord profile, then issue tokens."""
    stmt = select(User).where(User.discord_id == discord_id)
    user = (await session.execute(stmt)).scalar_one_or_none()

    if user is None:
        # Check if a user with this email already exists (link Discord to existing account)
        if discord_email:
            existing_by_email = (
                await session.execute(
                    select(User).where(User.email == discord_email.lower().strip())
                )
            ).scalar_one_or_none()
            if existing_by_email is not None:
                # Link Discord ID to the existing account
                existing_by_email.discord_id = discord_id
                existing_by_email.discord_username = discord_username
                existing_by_email.last_login_at = datetime.now(timezone.utc)
                if discord_avatar:
                    existing_by_email.avatar_url = (
                        f"https://cdn.discordapp.com/avatars/{discord_id}/{discord_avatar}.png"
                    )
                user = existing_by_email
                await session.flush()

                return await _issue_tokens(
                    user=user,
                    session=session,
                    jwt_secret=jwt_secret,
                    expiry_minutes=expiry_minutes,
                    refresh_expiry_days=refresh_expiry_days,
                )

        # New user — create
        avatar_url = (
            f"https://cdn.discordapp.com/avatars/{discord_id}/{discord_avatar}.png"
            if discord_avatar
            else None
        )
        user = User(
            id=uuid.uuid4(),
            discord_id=discord_id,
            discord_username=discord_username,
            email=discord_email.lower().strip() if discord_email else None,
            display_name=discord_username,
            avatar_url=avatar_url,
            last_login_at=datetime.now(timezone.utc),
        )
        session.add(user)
        await session.flush()
    else:
        # Existing user — update profile fields
        user.discord_username = discord_username
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
) -> AuthResult:
    """Validate a refresh token and issue a new access + refresh pair.

    The old refresh token is revoked (rotation).
    """
    token_hash = _hash_refresh_token(refresh_token_value)

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
    )


async def revoke_refresh_token(
    *,
    refresh_token_value: str,
    session: AsyncSession,
) -> None:
    """Revoke a refresh token (logout)."""
    token_hash = _hash_refresh_token(refresh_token_value)
    stmt = select(RefreshToken).where(RefreshToken.token_hash == token_hash)
    row = (await session.execute(stmt)).scalar_one_or_none()
    if row is not None:
        row.revoked = True


async def cleanup_expired_tokens(session: AsyncSession) -> int:
    """Delete refresh tokens that are revoked or expired. Returns count deleted."""
    now = datetime.now(timezone.utc)
    stmt = delete(RefreshToken).where(
        or_(
            RefreshToken.revoked.is_(True),
            RefreshToken.expires_at < now,
        )
    )
    result = await session.execute(stmt)
    return result.rowcount
