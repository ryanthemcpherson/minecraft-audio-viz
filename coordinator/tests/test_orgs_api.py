"""Integration tests for the organizations API endpoints."""

from __future__ import annotations

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


# ---------------------------------------------------------------------------
# Org server management (register, list details, remove)
# ---------------------------------------------------------------------------


class TestOrgServerManagement:
    async def test_register_server_for_org(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client)
        org = await _create_org(client, auth["access_token"])

        resp = await client.post(
            f"/api/v1/orgs/{org['id']}/servers/register",
            json={"name": "My VJ Server", "websocket_url": "wss://mc.example.com/ws"},
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 201
        data = resp.json()
        assert data["name"] == "My VJ Server"
        assert data["websocket_url"] == "wss://mc.example.com/ws"
        assert data["api_key"].startswith("mcav_")
        assert data["jwt_secret"].startswith("jws_")
        assert "server_id" in data

        # Verify it appears in the list
        list_resp = await client.get(
            f"/api/v1/orgs/{org['id']}/servers",
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert list_resp.status_code == 200
        servers = list_resp.json()
        assert len(servers) == 1
        assert servers[0]["name"] == "My VJ Server"

    async def test_register_server_non_owner_forbidden(self, client: AsyncClient) -> None:
        auth1 = await _register_and_auth(client, email="owner2@example.com")
        org = await _create_org(client, auth1["access_token"], slug="owner-org")

        auth2 = await _register_and_auth(client, email="nonowner@example.com")
        resp = await client.post(
            f"/api/v1/orgs/{org['id']}/servers/register",
            json={"name": "Rogue Server", "websocket_url": "wss://rogue.com/ws"},
            headers={"Authorization": f"Bearer {auth2['access_token']}"},
        )
        assert resp.status_code == 403

    async def test_list_servers_with_details(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="detail@example.com")
        org = await _create_org(client, auth["access_token"], slug="detail-org")

        # Register a server
        await client.post(
            f"/api/v1/orgs/{org['id']}/servers/register",
            json={"name": "Detail Server", "websocket_url": "wss://detail.com/ws"},
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )

        resp = await client.get(
            f"/api/v1/orgs/{org['id']}/servers",
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 200
        servers = resp.json()
        assert len(servers) == 1
        s = servers[0]
        assert "is_online" in s
        assert s["is_online"] is False  # no heartbeat yet
        assert "last_heartbeat" in s
        assert "active_show_count" in s
        assert s["active_show_count"] == 0
        assert "created_at" in s

    async def test_remove_server_from_org(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="remove@example.com")
        org = await _create_org(client, auth["access_token"], slug="remove-org")

        reg = await client.post(
            f"/api/v1/orgs/{org['id']}/servers/register",
            json={"name": "Temp Server", "websocket_url": "wss://temp.com/ws"},
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        server_id = reg.json()["server_id"]

        # Remove it
        del_resp = await client.delete(
            f"/api/v1/orgs/{org['id']}/servers/{server_id}",
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert del_resp.status_code == 204

        # Verify it's gone from the list
        list_resp = await client.get(
            f"/api/v1/orgs/{org['id']}/servers",
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert list_resp.status_code == 200
        assert len(list_resp.json()) == 0

    async def test_remove_server_non_owner_forbidden(self, client: AsyncClient) -> None:
        auth1 = await _register_and_auth(client, email="rmowner@example.com")
        org = await _create_org(client, auth1["access_token"], slug="rmowner-org")

        reg = await client.post(
            f"/api/v1/orgs/{org['id']}/servers/register",
            json={"name": "Protected", "websocket_url": "wss://protected.com/ws"},
            headers={"Authorization": f"Bearer {auth1['access_token']}"},
        )
        server_id = reg.json()["server_id"]

        auth2 = await _register_and_auth(client, email="rmhacker@example.com")
        resp = await client.delete(
            f"/api/v1/orgs/{org['id']}/servers/{server_id}",
            headers={"Authorization": f"Bearer {auth2['access_token']}"},
        )
        assert resp.status_code == 403

    async def test_remove_server_not_in_org_404(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="rm404@example.com")
        org = await _create_org(client, auth["access_token"], slug="rm404-org")

        import uuid

        fake_id = str(uuid.uuid4())
        resp = await client.delete(
            f"/api/v1/orgs/{org['id']}/servers/{fake_id}",
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 404

    async def test_get_org_by_slug(self, client: AsyncClient) -> None:
        auth = await _register_and_auth(client, email="slug@example.com")
        org = await _create_org(client, auth["access_token"], slug="slug-test")

        resp = await client.get(
            "/api/v1/orgs/by-slug/slug-test",
            headers={"Authorization": f"Bearer {auth['access_token']}"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["slug"] == "slug-test"
        assert data["id"] == org["id"]
