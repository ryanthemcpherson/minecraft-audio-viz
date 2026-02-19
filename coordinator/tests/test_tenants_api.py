"""Integration tests for the tenant resolution API endpoint."""

from __future__ import annotations

import pytest
import pytest_asyncio
from httpx import AsyncClient


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _setup_tenant(client: AsyncClient) -> dict:
    """Register user, create org, register server, assign server, create show.
    Returns dict with auth, org, server, and show data."""

    # Register user
    auth_resp = await client.post(
        "/api/v1/auth/register",
        json={
            "email": "tenant@example.com",
            "password": "testpass123",
            "display_name": "Tenant Owner",
        },
    )
    assert auth_resp.status_code == 201
    auth = auth_resp.json()

    # Create org
    org_resp = await client.post(
        "/api/v1/orgs",
        json={"name": "Cool Server", "slug": "cool-server", "description": "A cool VJ server"},
        headers={"Authorization": f"Bearer {auth['access_token']}"},
    )
    assert org_resp.status_code == 201
    org = org_resp.json()

    # Register VJ server
    server_resp = await client.post(
        "/api/v1/servers/register",
        json={
            "name": "VJ Server",
            "websocket_url": "wss://example.com/ws",
            "api_key": "tenant-test-key",
        },
    )
    assert server_resp.status_code == 201
    server = server_resp.json()

    # Assign server to org
    assign_resp = await client.post(
        f"/api/v1/orgs/{org['id']}/servers",
        json={"server_id": server["server_id"]},
        headers={"Authorization": f"Bearer {auth['access_token']}"},
    )
    assert assign_resp.status_code == 200

    # Create a show
    show_resp = await client.post(
        "/api/v1/shows",
        json={
            "server_id": server["server_id"],
            "name": "Friday Night Show",
            "max_djs": 8,
        },
        headers={"Authorization": f"Bearer tenant-test-key"},
    )
    assert show_resp.status_code == 201
    show = show_resp.json()

    return {"auth": auth, "org": org, "server": server, "show": show}


# ---------------------------------------------------------------------------
# Tenant resolution
# ---------------------------------------------------------------------------


class TestResolveTenant:
    async def test_resolve_returns_org_and_shows(self, client: AsyncClient) -> None:
        data = await _setup_tenant(client)
        resp = await client.get("/api/v1/tenants/resolve?slug=cool-server")
        assert resp.status_code == 200
        body = resp.json()

        assert body["org"]["name"] == "Cool Server"
        assert body["org"]["slug"] == "cool-server"
        assert body["org"]["description"] == "A cool VJ server"
        assert len(body["servers"]) == 1
        assert body["servers"][0]["name"] == "VJ Server"
        assert len(body["active_shows"]) == 1
        assert body["active_shows"][0]["name"] == "Friday Night Show"

    async def test_resolve_unknown_slug_404(self, client: AsyncClient) -> None:
        resp = await client.get("/api/v1/tenants/resolve?slug=nonexistent")
        assert resp.status_code == 404

    async def test_resolve_case_insensitive(self, client: AsyncClient) -> None:
        await _setup_tenant(client)
        resp = await client.get("/api/v1/tenants/resolve?slug=COOL-SERVER")
        assert resp.status_code == 200
        assert resp.json()["org"]["slug"] == "cool-server"
