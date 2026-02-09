"""Integration tests for the organizations API endpoints."""

from __future__ import annotations

import pytest
import pytest_asyncio
from httpx import AsyncClient


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _register_and_auth(client: AsyncClient, email: str = "orgtest@example.com") -> dict:
    """Register a user and return the full auth response."""
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "testpass123", "display_name": "Org Tester"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _create_org(
    client: AsyncClient, token: str, slug: str = "my-server", name: str = "My Server"
) -> dict:
    resp = await client.post(
        "/api/v1/orgs",
        json={"name": name, "slug": slug},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# Create org
# ---------------------------------------------------------------------------


class TestCreateOrg:
    async def test_create_org_success(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        org = await _create_org(client, auth["access_token"])
        assert org["name"] == "My Server"
        assert org["slug"] == "my-server"
        assert org["owner_id"] == auth["user"]["id"]

    async def test_create_org_duplicate_slug(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        await _create_org(client, auth["access_token"], slug="dupe")
        resp = await client.post(
            "/api/v1/orgs",
            json={"name": "Another", "slug": "dupe"},
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 409

    async def test_create_org_reserved_slug(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        resp = await client.post(
            "/api/v1/orgs",
            json={"name": "Admin Org", "slug": "admin"},
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 400

    async def test_create_org_invalid_slug_format(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        resp = await client.post(
            "/api/v1/orgs",
            json={"name": "Bad", "slug": "-leading-hyphen"},
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 400

    async def test_create_org_unauthenticated(self, client: AsyncClient) -> None:
        resp = await client.post(
            "/api/v1/orgs",
            json={"name": "No Auth", "slug": "noauth"},
        )
        assert resp.status_code in (401, 422)


# ---------------------------------------------------------------------------
# Get org
# ---------------------------------------------------------------------------


class TestGetOrg:
    async def test_get_org_success(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        org = await _create_org(client, auth["access_token"])
        resp = await client.get(
            f"/api/v1/orgs/{org['id']}",
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["slug"] == "my-server"

    async def test_get_org_non_member_forbidden(self, client: AsyncClient) -> None:
        auth1 = await _register_and_auth(client, email="owner@example.com")
        org = await _create_org(client, auth1["access_token"])

        auth2 = await _register_and_auth(client, email="outsider@example.com")
        resp = await client.get(
            f"/api/v1/orgs/{org['id']}",
            headers={"Authorization": f"Bearer {auth2['access_token']}"},
        )
        assert resp.status_code == 403


# ---------------------------------------------------------------------------
# Assign server
# ---------------------------------------------------------------------------


class TestAssignServer:
    async def test_assign_server(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        org = await _create_org(client, auth["access_token"])

        # Register a VJ server
        server_resp = await client.post(
            "/api/v1/servers/register",
            json={
                "name": "VJ Server 1",
                "websocket_url": "wss://example.com/ws",
                "api_key": "test-api-key",
            },
        )
        assert server_resp.status_code == 201
        server_id = server_resp.json()["server_id"]

        # Assign server to org
        resp = await client.post(
            f"/api/v1/orgs/{org['id']}/servers",
            json={"server_id": server_id},
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 200
        assert resp.json()["server_id"] == server_id
        assert resp.json()["org_id"] == org["id"]

    async def test_list_org_servers(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        org = await _create_org(client, auth["access_token"])

        resp = await client.get(
            f"/api/v1/orgs/{org['id']}/servers",
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)
