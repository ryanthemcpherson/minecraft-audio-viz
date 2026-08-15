"""Unit tests for app.services.auth_service.

Tests the core authentication orchestration: registration, login, token
refresh/revocation, password reset, email verification, and session management.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import jwt as pyjwt
import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import RefreshToken, User
from app.services.auth_service import (
    _hash_refresh_token,
    cleanup_expired_tokens,
    find_and_revoke_refresh_token,
    get_user_by_id,
    list_active_sessions,
    login_discord,
    login_email,
    login_google,
    refresh_access_token,
    register_email,
    request_password_reset,
    reset_password,
    revoke_all_user_tokens,
    revoke_refresh_token,
    revoke_session_by_id,
    create_email_verification,
    verify_email,
)
from app.services.password import hash_password
from app.services.user_jwt import verify_user_token

JWT_SECRET = "test-jwt-secret-for-auth-service-unit-tests-32+"  # nosec B105


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _create_user(
    session: AsyncSession,
    *,
    email: str = "alice@example.com",
    password: str = "Testpass123",
    display_name: str = "Alice",
    discord_id: str | None = None,
    google_id: str | None = None,
    is_active: bool = True,
) -> User:
    """Insert a user directly for testing."""
    user = User(
        id=uuid.uuid4(),
        email=email,
        password_hash=hash_password(password),
        display_name=display_name,
        discord_id=discord_id,
        google_id=google_id,
        email_verified=False,
        is_active=is_active,
        last_login_at=datetime.now(timezone.utc),
    )
    session.add(user)
    await session.flush()
    return user


# ---------------------------------------------------------------------------
# _hash_refresh_token
# ---------------------------------------------------------------------------


class TestHashRefreshToken:
    def test_deterministic_for_same_inputs(self) -> None:
        h1 = _hash_refresh_token("token-value", secret="secret")
        h2 = _hash_refresh_token("token-value", secret="secret")
        assert h1 == h2

    def test_different_for_different_tokens(self) -> None:
        h1 = _hash_refresh_token("token-a", secret="secret")
        h2 = _hash_refresh_token("token-b", secret="secret")
        assert h1 != h2

    def test_different_for_different_secrets(self) -> None:
        h1 = _hash_refresh_token("token-value", secret="secret-1")
        h2 = _hash_refresh_token("token-value", secret="secret-2")
        assert h1 != h2

    def test_returns_hex_string(self) -> None:
        h = _hash_refresh_token("tok", secret="sec")
        assert isinstance(h, str)
        assert len(h) == 64  # SHA-256 hex digest


# ---------------------------------------------------------------------------
# register_email
# ---------------------------------------------------------------------------


class TestRegisterEmail:
    async def test_registers_new_user(self, db_session: AsyncSession) -> None:
        result = await register_email(
            email="new@example.com",
            password="Testpass123",
            display_name="New User",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        assert result.auth.display_name == "New User"
        assert result.auth.access_token
        assert result.auth.refresh_token
        assert result.auth.expires_in == 15 * 60
        assert result.verification_token is not None

    async def test_access_token_is_valid_jwt(self, db_session: AsyncSession) -> None:
        result = await register_email(
            email="jwt@example.com",
            password="Testpass123",
            display_name="JWT User",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        payload = verify_user_token(result.auth.access_token, jwt_secret=JWT_SECRET)
        assert payload.sub == str(result.auth.user_id)

    async def test_duplicate_email_raises(self, db_session: AsyncSession) -> None:
        await register_email(
            email="dup@example.com",
            password="Testpass123",
            display_name="First",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        with pytest.raises(ValueError, match="Email already registered"):
            await register_email(
                email="dup@example.com",
                password="Testpass123",
                display_name="Second",
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )

    async def test_email_normalized_to_lowercase(self, db_session: AsyncSession) -> None:
        result = await register_email(
            email="UPPER@Example.COM",
            password="Testpass123",
            display_name="Upper",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        user = await get_user_by_id(db_session, result.auth.user_id)
        assert user is not None
        assert user.email == "upper@example.com"


# ---------------------------------------------------------------------------
# login_email
# ---------------------------------------------------------------------------


class TestLoginEmail:
    async def test_successful_login(self, db_session: AsyncSession) -> None:
        user = await _create_user(db_session)
        result = await login_email(
            email="alice@example.com",
            password="Testpass123",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        assert result.user_id == user.id
        assert result.access_token
        assert result.refresh_token

    async def test_wrong_password_raises(self, db_session: AsyncSession) -> None:
        await _create_user(db_session)
        with pytest.raises(ValueError, match="Invalid email or password"):
            await login_email(
                email="alice@example.com",
                password="WrongPass999",
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )

    async def test_nonexistent_email_raises(self, db_session: AsyncSession) -> None:
        with pytest.raises(ValueError, match="Invalid email or password"):
            await login_email(
                email="nobody@example.com",
                password="Testpass123",
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )

    async def test_account_lockout_after_max_failures(self, db_session: AsyncSession) -> None:
        await _create_user(db_session)
        for _ in range(5):
            with pytest.raises(ValueError, match="Invalid email or password"):
                await login_email(
                    email="alice@example.com",
                    password="WrongPass",
                    session=db_session,
                    jwt_secret=JWT_SECRET,
                    expiry_minutes=15,
                    refresh_expiry_days=30,
                    max_failed_attempts=5,
                )
        # After lockout, even correct password is rejected
        with pytest.raises(ValueError, match="Account temporarily locked"):
            await login_email(
                email="alice@example.com",
                password="Testpass123",
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
                max_failed_attempts=5,
            )

    async def test_inactive_user_cannot_login(self, db_session: AsyncSession) -> None:
        await _create_user(db_session, is_active=False)
        with pytest.raises(ValueError, match="Invalid email or password"):
            await login_email(
                email="alice@example.com",
                password="Testpass123",
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )

    async def test_successful_login_resets_failed_attempts(self, db_session: AsyncSession) -> None:
        user = await _create_user(db_session)
        # Fail twice
        for _ in range(2):
            with pytest.raises(ValueError):
                await login_email(
                    email="alice@example.com",
                    password="WrongPass",
                    session=db_session,
                    jwt_secret=JWT_SECRET,
                    expiry_minutes=15,
                    refresh_expiry_days=30,
                )
        # Succeed
        await login_email(
            email="alice@example.com",
            password="Testpass123",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        await db_session.refresh(user)
        assert user.failed_login_attempts == 0


# ---------------------------------------------------------------------------
# login_discord
# ---------------------------------------------------------------------------


class TestLoginDiscord:
    async def test_creates_new_user_on_first_login(self, db_session: AsyncSession) -> None:
        result = await login_discord(
            discord_id="12345",
            discord_username="djcool",
            discord_email="djcool@discord.com",
            discord_avatar="abc123",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        assert result.display_name == "djcool"
        user = await get_user_by_id(db_session, result.user_id)
        assert user is not None
        assert user.discord_id == "12345"
        assert user.avatar_url is not None
        assert "abc123" in user.avatar_url

    async def test_existing_discord_user_updates_profile(self, db_session: AsyncSession) -> None:
        # First login
        result1 = await login_discord(
            discord_id="99999",
            discord_username="oldname",
            discord_email=None,
            discord_avatar=None,
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        # Second login with updated profile
        result2 = await login_discord(
            discord_id="99999",
            discord_username="newname",
            discord_email=None,
            discord_avatar="newavatar",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        assert result1.user_id == result2.user_id
        user = await get_user_by_id(db_session, result2.user_id)
        assert user is not None
        assert user.discord_username == "newname"

    async def test_email_conflict_creates_separate_account(self, db_session: AsyncSession) -> None:
        # Create a user with an email first
        await _create_user(db_session, email="conflict@example.com")
        # Discord login with same email should create new account without email
        result = await login_discord(
            discord_id="77777",
            discord_username="conflictdj",
            discord_email="conflict@example.com",
            discord_avatar=None,
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        user = await get_user_by_id(db_session, result.user_id)
        assert user is not None
        assert user.email is None  # email not set due to conflict


# ---------------------------------------------------------------------------
# login_google
# ---------------------------------------------------------------------------


class TestLoginGoogle:
    async def test_creates_new_user_on_first_login(self, db_session: AsyncSession) -> None:
        result = await login_google(
            google_id="goog-123",
            google_email="user@gmail.com",
            google_name="Google User",
            google_picture="https://example.com/pic.jpg",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        assert result.display_name == "Google User"
        user = await get_user_by_id(db_session, result.user_id)
        assert user is not None
        assert user.google_id == "goog-123"
        assert user.email == "user@gmail.com"

    async def test_existing_google_user_updates_profile(self, db_session: AsyncSession) -> None:
        result1 = await login_google(
            google_id="goog-456",
            google_email="goog456@gmail.com",
            google_name="Old Name",
            google_picture=None,
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        result2 = await login_google(
            google_id="goog-456",
            google_email="goog456@gmail.com",
            google_name="New Name",
            google_picture="https://example.com/new.jpg",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        assert result1.user_id == result2.user_id
        user = await get_user_by_id(db_session, result2.user_id)
        assert user is not None
        assert user.display_name == "New Name"
        assert user.avatar_url == "https://example.com/new.jpg"


# ---------------------------------------------------------------------------
# refresh_access_token
# ---------------------------------------------------------------------------


class TestRefreshAccessToken:
    async def test_rotates_tokens(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="refresh@example.com",
            password="Testpass123",
            display_name="Refresher",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        new_auth = await refresh_access_token(
            refresh_token_value=reg.auth.refresh_token,
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        # Refresh token must always rotate (random value)
        assert new_auth.refresh_token != reg.auth.refresh_token
        # New access token should be valid
        payload = verify_user_token(new_auth.access_token, jwt_secret=JWT_SECRET)
        assert payload.sub == str(reg.auth.user_id)
        assert new_auth.user_id == reg.auth.user_id

    async def test_old_refresh_token_revoked_after_rotation(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="revoked@example.com",
            password="Testpass123",
            display_name="Revoker",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        await refresh_access_token(
            refresh_token_value=reg.auth.refresh_token,
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        # Old token should now be revoked
        with pytest.raises(ValueError, match="Invalid or revoked"):
            await refresh_access_token(
                refresh_token_value=reg.auth.refresh_token,
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )

    async def test_invalid_refresh_token_raises(self, db_session: AsyncSession) -> None:
        with pytest.raises(ValueError, match="Invalid or revoked"):
            await refresh_access_token(
                refresh_token_value="bogus-token-value",
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )


# ---------------------------------------------------------------------------
# revoke_refresh_token / revoke_all_user_tokens
# ---------------------------------------------------------------------------


class TestRevokeTokens:
    async def test_revoke_single_token(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="revoke1@example.com",
            password="Testpass123",
            display_name="Revoker",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        await revoke_refresh_token(
            refresh_token_value=reg.auth.refresh_token,
            session=db_session,
            jwt_secret=JWT_SECRET,
        )
        # Token should be revoked now
        with pytest.raises(ValueError, match="Invalid or revoked"):
            await refresh_access_token(
                refresh_token_value=reg.auth.refresh_token,
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )

    async def test_revoke_all_user_tokens(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="revokeall@example.com",
            password="Testpass123",
            display_name="RevokeAll",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        # Issue a second token via refresh
        auth2 = await refresh_access_token(
            refresh_token_value=reg.auth.refresh_token,
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        count = await revoke_all_user_tokens(db_session, reg.auth.user_id)
        assert count >= 1  # At least the second token
        with pytest.raises(ValueError, match="Invalid or revoked"):
            await refresh_access_token(
                refresh_token_value=auth2.refresh_token,
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )


# ---------------------------------------------------------------------------
# Session management
# ---------------------------------------------------------------------------


class TestSessionManagement:
    async def test_list_active_sessions(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="sessions@example.com",
            password="Testpass123",
            display_name="Sessions",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
            user_agent="TestBrowser/1.0",
            ip_address="192.168.1.1",
        )
        sessions = await list_active_sessions(db_session, reg.auth.user_id)
        assert len(sessions) >= 1
        assert sessions[0].user_agent == "TestBrowser/1.0"
        assert sessions[0].ip_address == "192.168.1.1"

    async def test_revoke_session_by_id(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="sess-revoke@example.com",
            password="Testpass123",
            display_name="SessRevoke",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        sessions = await list_active_sessions(db_session, reg.auth.user_id)
        assert len(sessions) == 1
        revoked = await revoke_session_by_id(
            db_session, sessions[0].id, reg.auth.user_id
        )
        assert revoked is not None
        assert revoked.revoked is True

    async def test_revoke_session_wrong_user_returns_none(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="sess-wrong@example.com",
            password="Testpass123",
            display_name="SessWrong",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        sessions = await list_active_sessions(db_session, reg.auth.user_id)
        # Try revoking with a random user_id
        result = await revoke_session_by_id(db_session, sessions[0].id, uuid.uuid4())
        assert result is None


# ---------------------------------------------------------------------------
# Password reset
# ---------------------------------------------------------------------------


class TestPasswordReset:
    async def test_request_reset_returns_token(self, db_session: AsyncSession) -> None:
        await _create_user(db_session, email="reset@example.com")
        token = await request_password_reset(
            email="reset@example.com",
            session=db_session,
            expiry_minutes=30,
        )
        assert token is not None
        assert len(token) > 20

    async def test_request_reset_nonexistent_email_returns_none(self, db_session: AsyncSession) -> None:
        token = await request_password_reset(
            email="nonexistent@example.com",
            session=db_session,
            expiry_minutes=30,
        )
        assert token is None

    async def test_reset_password_success(self, db_session: AsyncSession) -> None:
        await _create_user(db_session, email="resetpw@example.com", password="OldPass123")
        token = await request_password_reset(
            email="resetpw@example.com",
            session=db_session,
            expiry_minutes=30,
        )
        assert token is not None
        await reset_password(
            token_value=token,
            new_password="NewPass456",
            session=db_session,
        )
        # Should be able to login with new password
        result = await login_email(
            email="resetpw@example.com",
            password="NewPass456",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        assert result.access_token

    async def test_reset_password_invalid_token_raises(self, db_session: AsyncSession) -> None:
        with pytest.raises(ValueError, match="Invalid or already used"):
            await reset_password(
                token_value="bogus-token",
                new_password="NewPass456",
                session=db_session,
            )

    async def test_reset_password_token_used_only_once(self, db_session: AsyncSession) -> None:
        await _create_user(db_session, email="once@example.com")
        token = await request_password_reset(
            email="once@example.com",
            session=db_session,
            expiry_minutes=30,
        )
        assert token is not None
        await reset_password(
            token_value=token,
            new_password="NewPass456",
            session=db_session,
        )
        with pytest.raises(ValueError, match="Invalid or already used"):
            await reset_password(
                token_value=token,
                new_password="AnotherPass789",
                session=db_session,
            )

    async def test_reset_password_revokes_all_refresh_tokens(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="reset-revokes@example.com",
            password="OldPass123",
            display_name="ResetRevokes",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        token = await request_password_reset(
            email="reset-revokes@example.com",
            session=db_session,
            expiry_minutes=30,
        )
        assert token is not None
        await reset_password(
            token_value=token,
            new_password="NewPass456",
            session=db_session,
        )
        # Old refresh token should be revoked
        with pytest.raises(ValueError, match="Invalid or revoked"):
            await refresh_access_token(
                refresh_token_value=reg.auth.refresh_token,
                session=db_session,
                jwt_secret=JWT_SECRET,
                expiry_minutes=15,
                refresh_expiry_days=30,
            )


# ---------------------------------------------------------------------------
# Email verification
# ---------------------------------------------------------------------------


class TestEmailVerification:
    async def test_verify_email_success(self, db_session: AsyncSession) -> None:
        user = await _create_user(db_session, email="verify@example.com")
        assert user.email_verified is False
        token = await create_email_verification(user_id=user.id, session=db_session)
        await verify_email(token_value=token, session=db_session)
        await db_session.flush()
        await db_session.refresh(user)
        assert user.email_verified is True

    async def test_verify_email_invalid_token_raises(self, db_session: AsyncSession) -> None:
        with pytest.raises(ValueError, match="Invalid or already used"):
            await verify_email(token_value="not-a-real-token", session=db_session)

    async def test_verify_email_token_used_only_once(self, db_session: AsyncSession) -> None:
        user = await _create_user(db_session, email="verify-once@example.com")
        token = await create_email_verification(user_id=user.id, session=db_session)
        await verify_email(token_value=token, session=db_session)
        with pytest.raises(ValueError, match="Invalid or already used"):
            await verify_email(token_value=token, session=db_session)


# ---------------------------------------------------------------------------
# cleanup_expired_tokens
# ---------------------------------------------------------------------------


class TestCleanupExpiredTokens:
    async def test_deletes_revoked_tokens(self, db_session: AsyncSession) -> None:
        reg = await register_email(
            email="cleanup@example.com",
            password="Testpass123",
            display_name="Cleanup",
            session=db_session,
            jwt_secret=JWT_SECRET,
            expiry_minutes=15,
            refresh_expiry_days=30,
        )
        await revoke_refresh_token(
            refresh_token_value=reg.auth.refresh_token,
            session=db_session,
            jwt_secret=JWT_SECRET,
        )
        count = await cleanup_expired_tokens(db_session)
        assert count >= 1
