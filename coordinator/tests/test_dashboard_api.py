"""Integration tests for the dashboard summary endpoint."""

from __future__ import annotations

from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _register_and_auth(client: AsyncClient, email: str = "dash@example.com") -> dict:
    """Register a user and return the full auth response."""
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "testpass123", "display_name": "Dashboard Tester"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _complete_onboarding(client: AsyncClient, token: str, user_type: str) -> dict:
    """Complete onboarding with the given user type."""
    resp = await client.post(
        "/api/v1/onboarding/complete",
        json={"user_type": user_type},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


def _auth_header(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# Unauthenticated
# ---------------------------------------------------------------------------


class TestDashboardAuth:
    async def test_unauthenticated_returns_401(self, client: AsyncClient) -> None:
        resp = await client.get("/api/v1/dashboard/summary")
        assert resp.status_code in (401, 422)


# ---------------------------------------------------------------------------
# Server owner
# ---------------------------------------------------------------------------


class TestServerOwnerDashboard:
    async def test_server_owner_gets_dashboard_with_checklist(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="owner@example.com")
        token = auth["access_token"]
        await _complete_onboarding(client, token, "server_owner")

        resp = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert resp.status_code == 200
        data = resp.json()

        assert data["user_type"] == "server_owner"
        assert "checklist" in data
        checklist = data["checklist"]
        assert checklist["org_created"] is False
        assert checklist["server_registered"] is False
        assert checklist["invite_created"] is False
        assert checklist["show_started"] is False
        assert data["organizations"] == []
        assert data["recent_shows"] == []

    async def test_server_owner_checklist_updates_with_org(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="owner2@example.com")
        token = auth["access_token"]
        await _complete_onboarding(client, token, "server_owner")

        # Create an org
        resp = await client.post(
            "/api/v1/orgs",
            json={"name": "My Org", "slug": "my-org"},
            headers=_auth_header(token),
        )
        assert resp.status_code == 201

        resp = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert resp.status_code == 200
        data = resp.json()

        assert data["checklist"]["org_created"] is True
        assert data["checklist"]["server_registered"] is False
        assert len(data["organizations"]) == 1
        assert data["organizations"][0]["name"] == "My Org"
        assert data["organizations"][0]["role"] == "owner"


# ---------------------------------------------------------------------------
# Team member
# ---------------------------------------------------------------------------


class TestTeamMemberDashboard:
    async def test_team_member_gets_dashboard(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="member@example.com")
        token = auth["access_token"]
        await _complete_onboarding(client, token, "team_member")

        resp = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert resp.status_code == 200
        data = resp.json()

        assert data["user_type"] == "team_member"
        assert "organizations" in data
        assert "active_shows" in data
        assert isinstance(data["organizations"], list)
        assert isinstance(data["active_shows"], list)


# ---------------------------------------------------------------------------
# DJ
# ---------------------------------------------------------------------------


class TestDJDashboard:
    async def test_dj_gets_dashboard(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="dj@example.com")
        token = auth["access_token"]
        await _complete_onboarding(client, token, "dj")

        resp = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert resp.status_code == 200
        data = resp.json()

        assert data["user_type"] == "dj"
        assert data["dj_name"] == "Unknown"
        assert data["session_count"] == 0
        assert data["recent_sessions"] == []

    async def test_dj_with_profile_gets_dashboard(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="dj2@example.com")
        token = auth["access_token"]
        await _complete_onboarding(client, token, "dj")

        # Create DJ profile
        resp = await client.post(
            "/api/v1/dj/profile",
            json={"dj_name": "DJ Test", "bio": "Test bio", "genres": "House, Techno"},
            headers=_auth_header(token),
        )
        assert resp.status_code == 201

        resp = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert resp.status_code == 200
        data = resp.json()

        assert data["user_type"] == "dj"
        assert data["dj_name"] == "DJ Test"
        assert data["bio"] == "Test bio"
        assert data["genres"] == "House, Techno"
        assert data["session_count"] == 0


# ---------------------------------------------------------------------------
# Generic (skipped onboarding)
# ---------------------------------------------------------------------------


class TestGenericDashboard:
    async def test_skipped_user_gets_generic_dashboard(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="generic@example.com")
        token = auth["access_token"]

        # Skip onboarding (no user_type set)
        resp = await client.post(
            "/api/v1/onboarding/skip",
            headers=_auth_header(token),
        )
        assert resp.status_code == 200

        resp = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert resp.status_code == 200
        data = resp.json()

        assert data["user_type"] == "generic"
        assert "organizations" in data
        assert isinstance(data["organizations"], list)
