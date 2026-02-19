"""Integration tests for the auth API endpoints."""

from __future__ import annotations

import time

import jwt as pyjwt
from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _register(client: AsyncClient, **overrides: str) -> dict:
    defaults = {
        "email": "test@example.com",
        "password": "testpass123",
        "display_name": "Test User",
    }
    defaults.update(overrides)
    resp = await client.post("/api/v1/auth/register", json=defaults)
    assert resp.status_code == 201, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# Registration
# ---------------------------------------------------------------------------


class TestRegister:
    async def test_register_returns_tokens(self, client: AsyncClient) -> None:
        data = await _register(client)
        assert "access_token" in data
        assert "refresh_token" in data
        assert data["token_type"] == "bearer"
        assert data["user"]["email"] == "test@example.com"
        assert data["user"]["display_name"] == "Test User"

    async def test_register_duplicate_email(self, client: AsyncClient) -> None:
        await _register(client)
        resp = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "test@example.com",
                "password": "anotherpass",
                "display_name": "Another",
            },
        )
        assert resp.status_code == 409

    async def test_register_short_password(self, client: AsyncClient) -> None:
        resp = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "short@example.com",
                "password": "short",
                "display_name": "User",
            },
        )
        assert resp.status_code == 422  # validation error


# ---------------------------------------------------------------------------
# Login
# ---------------------------------------------------------------------------


class TestLogin:
    async def test_login_success(self, client: AsyncClient) -> None:
        await _register(client)
        resp = await client.post(
            "/api/v1/auth/login",
            json={"email": "test@example.com", "password": "testpass123"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert "access_token" in data
        assert data["user"]["email"] == "test@example.com"

    async def test_login_wrong_password(self, client: AsyncClient) -> None:
        await _register(client)
        resp = await client.post(
            "/api/v1/auth/login",
            json={"email": "test@example.com", "password": "wrongpass"},
        )
        assert resp.status_code == 401

    async def test_login_unknown_email(self, client: AsyncClient) -> None:
        resp = await client.post(
            "/api/v1/auth/login",
            json={"email": "nobody@example.com", "password": "whatever"},
        )
        assert resp.status_code == 401


# ---------------------------------------------------------------------------
# Me
# ---------------------------------------------------------------------------


class TestMe:
    async def test_me_returns_profile(self, client: AsyncClient) -> None:
        data = await _register(client)
        resp = await client.get(
            "/api/v1/auth/me",
            headers={"Authorization": f"Bearer {data['access_token']}"},
        )
        assert resp.status_code == 200
        profile = resp.json()
        assert profile["email"] == "test@example.com"
        assert "organizations" in profile

    async def test_me_without_token_returns_401(self, client: AsyncClient) -> None:
        resp = await client.get("/api/v1/auth/me")
        assert resp.status_code in (401, 422)

    async def test_me_invalid_token_returns_401(self, client: AsyncClient) -> None:
        resp = await client.get(
            "/api/v1/auth/me",
            headers={"Authorization": "Bearer invalid-token"},
        )
        assert resp.status_code == 401

    async def test_me_non_uuid_sub_token_returns_401(self, client: AsyncClient) -> None:
        now = int(time.time())
        token = pyjwt.encode(
            {
                "sub": "legacy-non-uuid-subject",
                "token_type": "user_session",
                "iss": "mcav-coordinator",
                "iat": now,
                "exp": now + 3600,
            },
            "test-user-jwt-secret",
            algorithm="HS256",
        )

        resp = await client.get(
            "/api/v1/auth/me",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 401


# ---------------------------------------------------------------------------
# Refresh
# ---------------------------------------------------------------------------


class TestRefresh:
    async def test_refresh_returns_new_tokens(self, client: AsyncClient) -> None:
        data = await _register(client)
        resp = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": data["refresh_token"]},
        )
        assert resp.status_code == 200
        new_data = resp.json()
        assert "access_token" in new_data
        # Tokens should be different (rotation)
        assert new_data["refresh_token"] != data["refresh_token"]

    async def test_refresh_old_token_revoked(self, client: AsyncClient) -> None:
        data = await _register(client)
        # First refresh succeeds
        resp1 = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": data["refresh_token"]},
        )
        assert resp1.status_code == 200

        # Second use of the same refresh token should fail (revoked)
        resp2 = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": data["refresh_token"]},
        )
        assert resp2.status_code == 401

    async def test_refresh_invalid_token(self, client: AsyncClient) -> None:
        resp = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": "invalid-refresh-token"},
        )
        assert resp.status_code == 401


# ---------------------------------------------------------------------------
# Logout
# ---------------------------------------------------------------------------


class TestLogout:
    async def test_logout_revokes_token(self, client: AsyncClient) -> None:
        data = await _register(client)
        resp = await client.post(
            "/api/v1/auth/logout",
            json={"refresh_token": data["refresh_token"]},
        )
        assert resp.status_code == 204

        # Refresh should now fail
        resp2 = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": data["refresh_token"]},
        )
        assert resp2.status_code == 401
