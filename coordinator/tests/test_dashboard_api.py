"""Integration tests for the dashboard summary endpoint."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from app.models.db import DJSession, Show, VJServer
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

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

    async def test_dj_recent_sessions_include_server_name(
        self, client: AsyncClient, db_session: AsyncSession
    ) -> None:
        auth = await _register_and_auth(client, email="dj3@example.com")
        token = auth["access_token"]
        await _complete_onboarding(client, token, "dj")

        # Create DJ profile
        resp = await client.post(
            "/api/v1/dj/profile",
            json={"dj_name": "DJ Recent"},
            headers=_auth_header(token),
        )
        assert resp.status_code == 201

        server_id = uuid.uuid4()
        show_id = uuid.uuid4()
        db_session.add(
            VJServer(
                id=server_id,
                name="Recent Server",
                websocket_url="wss://example.com/ws",
                api_key_hash="hash",
                jwt_secret=uuid.uuid4().hex,
                org_id=None,
                is_active=True,
            )
        )
        db_session.add(
            Show(
                id=show_id,
                server_id=server_id,
                name="Recent Show",
                connect_code="ABCD-EFGH",
                status="active",
                max_djs=8,
                current_djs=1,
            )
        )
        db_session.add(
            DJSession(
                id=uuid.uuid4(),
                show_id=show_id,
                dj_name="DJ Recent",
                connected_at=datetime.now(timezone.utc),
                ip_address="127.0.0.1",
            )
        )
        await db_session.commit()

        dash = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert dash.status_code == 200
        data = dash.json()
        assert data["user_type"] == "dj"
        assert data["session_count"] == 1
        assert len(data["recent_sessions"]) == 1
        assert data["recent_sessions"][0]["server_name"] == "Recent Server"


# ---------------------------------------------------------------------------
# Generic (skipped onboarding)
# ---------------------------------------------------------------------------


class TestFullAccountReset:
    async def test_full_reset_clears_everything(self, client: AsyncClient) -> None:
        """Full reset should wipe orgs, servers, DJ profile, and onboarding."""
        auth = await _register_and_auth(client, email="reset@example.com")
        token = auth["access_token"]
        await _complete_onboarding(client, token, "server_owner")

        # Create org + register server + create invite
        org_resp = await client.post(
            "/api/v1/orgs",
            json={"name": "Reset Org", "slug": "reset-org"},
            headers=_auth_header(token),
        )
        assert org_resp.status_code == 201
        org_id = org_resp.json()["id"]

        srv_resp = await client.post(
            f"/api/v1/orgs/{org_id}/servers/register",
            json={"name": "Reset Server", "websocket_url": "wss://reset.com/ws"},
            headers=_auth_header(token),
        )
        assert srv_resp.status_code == 201

        inv_resp = await client.post(
            f"/api/v1/orgs/{org_id}/invites",
            headers=_auth_header(token),
        )
        assert inv_resp.status_code == 201

        # Verify dashboard shows the org
        dash = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert len(dash.json()["organizations"]) == 1

        # Full reset
        reset_resp = await client.post(
            "/api/v1/onboarding/reset-full",
            headers=_auth_header(token),
        )
        assert reset_resp.status_code == 200
        assert reset_resp.json()["onboarding_completed"] is False

        # Complete onboarding again to access dashboard
        await _complete_onboarding(client, token, "server_owner")

        # Dashboard should now be empty
        dash2 = await client.get("/api/v1/dashboard/summary", headers=_auth_header(token))
        assert dash2.status_code == 200
        data = dash2.json()
        assert len(data["organizations"]) == 0
        assert data["checklist"]["org_created"] is False
        assert data["checklist"]["server_registered"] is False
        assert data["checklist"]["invite_created"] is False

    async def test_full_reset_clears_dj_profile(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="resetdj@example.com")
        token = auth["access_token"]
        await _complete_onboarding(client, token, "dj")

        # Create DJ profile
        await client.post(
            "/api/v1/dj/profile",
            json={"dj_name": "DJ Resetter"},
            headers=_auth_header(token),
        )

        # Full reset
        resp = await client.post(
            "/api/v1/onboarding/reset-full",
            headers=_auth_header(token),
        )
        assert resp.status_code == 200

        # DJ profile should be gone
        profile_resp = await client.get(
            "/api/v1/dj/profile",
            headers=_auth_header(token),
        )
        assert profile_resp.status_code == 404


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
