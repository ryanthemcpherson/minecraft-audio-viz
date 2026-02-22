"""Tests for authentication error paths.

Covers login failures, duplicate registration, missing/invalid tokens,
and admin-only endpoint access control.
"""

from __future__ import annotations

from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _register(client: AsyncClient, email: str = "autherr@example.com") -> dict:
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Auth Err User"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def _auth_header(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# Login errors
# ---------------------------------------------------------------------------


class TestLoginErrors:
    async def test_login_wrong_password_returns_401(self, client: AsyncClient) -> None:
        await _register(client, "wrongpw@example.com")
        resp = await client.post(
            "/api/v1/auth/login",
            json={"email": "wrongpw@example.com", "password": "WrongPass999"},
        )
        assert resp.status_code == 401

    async def test_login_nonexistent_email_returns_401(self, client: AsyncClient) -> None:
        """Should return 401 (not 404) to avoid leaking whether the email exists."""
        resp = await client.post(
            "/api/v1/auth/login",
            json={"email": "noone@nowhere.com", "password": "DoesntMatter1"},
        )
        assert resp.status_code == 401

    async def test_login_wrong_and_nonexistent_same_status(self, client: AsyncClient) -> None:
        """Both wrong-password and non-existent email should return the same HTTP status."""
        await _register(client, "samestatus@example.com")

        resp_wrong = await client.post(
            "/api/v1/auth/login",
            json={"email": "samestatus@example.com", "password": "BadPass123"},
        )
        resp_missing = await client.post(
            "/api/v1/auth/login",
            json={"email": "ghost@example.com", "password": "BadPass123"},
        )
        assert resp_wrong.status_code == resp_missing.status_code == 401


# ---------------------------------------------------------------------------
# Registration errors
# ---------------------------------------------------------------------------


class TestRegistrationErrors:
    async def test_register_duplicate_email_returns_409(self, client: AsyncClient) -> None:
        await _register(client, "dupe@example.com")
        resp = await client.post(
            "/api/v1/auth/register",
            json={"email": "dupe@example.com", "password": "Anotherpass1", "display_name": "Dupe"},
        )
        assert resp.status_code == 409

    async def test_register_short_password_returns_422(self, client: AsyncClient) -> None:
        resp = await client.post(
            "/api/v1/auth/register",
            json={"email": "short@example.com", "password": "Abc1", "display_name": "Short"},
        )
        assert resp.status_code == 422

    async def test_register_no_uppercase_returns_422(self, client: AsyncClient) -> None:
        resp = await client.post(
            "/api/v1/auth/register",
            json={"email": "noup@example.com", "password": "alllower1", "display_name": "NoUp"},
        )
        assert resp.status_code == 422

    async def test_register_no_digit_returns_422(self, client: AsyncClient) -> None:
        resp = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "nodig@example.com",
                "password": "NoDigitsHere",
                "display_name": "NoDig",
            },
        )
        assert resp.status_code == 422


# ---------------------------------------------------------------------------
# Protected endpoint access
# ---------------------------------------------------------------------------


class TestProtectedEndpoints:
    async def test_me_without_token_returns_401_or_422(self, client: AsyncClient) -> None:
        resp = await client.get("/api/v1/auth/me")
        assert resp.status_code in (401, 422)

    async def test_me_with_invalid_token_returns_401(self, client: AsyncClient) -> None:
        resp = await client.get(
            "/api/v1/auth/me",
            headers=_auth_header("not-a-real-jwt"),
        )
        assert resp.status_code == 401

    async def test_me_with_empty_bearer_returns_401(self, client: AsyncClient) -> None:
        resp = await client.get(
            "/api/v1/auth/me",
            headers={"Authorization": "Bearer "},
        )
        assert resp.status_code == 401

    async def test_dashboard_without_token_returns_401_or_422(self, client: AsyncClient) -> None:
        resp = await client.get("/api/v1/dashboard/summary")
        assert resp.status_code in (401, 422)


# ---------------------------------------------------------------------------
# Admin endpoint access control
# ---------------------------------------------------------------------------


class TestAdminAccessControl:
    async def test_admin_stats_as_non_admin_returns_403(self, client: AsyncClient) -> None:
        """A normal user should not be able to access admin endpoints."""
        data = await _register(client, "nonadmin@example.com")
        resp = await client.get(
            "/api/v1/admin/stats",
            headers=_auth_header(data["access_token"]),
        )
        assert resp.status_code == 403

    async def test_admin_users_as_non_admin_returns_403(self, client: AsyncClient) -> None:
        data = await _register(client, "nonadmin2@example.com")
        resp = await client.get(
            "/api/v1/admin/users",
            headers=_auth_header(data["access_token"]),
        )
        assert resp.status_code == 403

    async def test_admin_stats_without_token_returns_401_or_422(self, client: AsyncClient) -> None:
        resp = await client.get("/api/v1/admin/stats")
        assert resp.status_code in (401, 422)

    async def test_admin_cleanup_as_non_admin_returns_403(self, client: AsyncClient) -> None:
        data = await _register(client, "nonadmin3@example.com")
        resp = await client.post(
            "/api/v1/auth/admin/cleanup-tokens",
            headers=_auth_header(data["access_token"]),
        )
        assert resp.status_code == 403
