"""Integration tests for the full API flow.

Uses httpx.AsyncClient against the FastAPI app backed by an in-memory
SQLite database (see conftest.py).
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str = "test@example.com") -> str:
    """Register a user and return the access token for authenticated requests."""
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "testpass123", "display_name": "Test User"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _register_server(
    client: AsyncClient,
    user_token: str,
    name: str = "Test Stage",
    websocket_url: str = "ws://localhost:9000",
    api_key: str = "my-secret-api-key",
) -> dict:
    """Register a VJ server with user auth and return the response data."""
    resp = await client.post(
        "/api/v1/servers/register",
        json={"name": name, "websocket_url": websocket_url, "api_key": api_key},
        headers={"Authorization": f"Bearer {user_token}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_health_returns_ok(client: AsyncClient) -> None:
    resp = await client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["version"] == "0.1.0"
    assert data["active_shows"] == 0
    assert data["active_servers"] == 0


# ---------------------------------------------------------------------------
# Server registration
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_register_server(client: AsyncClient) -> None:
    user_token = await _get_user_token(client)
    data = await _register_server(client, user_token)
    assert data["name"] == "Test Stage"
    assert "server_id" in data
    assert data["jwt_secret"].startswith("jws_")


# ---------------------------------------------------------------------------
# Full flow: register -> create show -> resolve code
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_full_flow(client: AsyncClient) -> None:
    """Register a server, create a show, and resolve the connect code."""

    # 0. Get auth token
    user_token = await _get_user_token(client, "flow@example.com")

    # 1. Register a VJ server
    reg_data = await _register_server(
        client,
        user_token,
        name="Main Stage",
        websocket_url="ws://192.168.1.50:9000",
        api_key="test-key-12345",
    )
    server_id = reg_data["server_id"]
    jwt_secret = reg_data["jwt_secret"]

    # 2. Create a show (authenticated with the same api_key)
    show_resp = await client.post(
        "/api/v1/shows",
        json={
            "server_id": server_id,
            "name": "Friday Night Beats",
            "max_djs": 4,
        },
        headers={"Authorization": "Bearer test-key-12345"},
    )
    assert show_resp.status_code == 201
    show_data = show_resp.json()
    assert show_data["name"] == "Friday Night Beats"
    connect_code = show_data["connect_code"]
    show_id = show_data["show_id"]

    # Connect code should match WORD-XXXX format
    assert len(connect_code) == 9
    assert connect_code[4] == "-"

    # 3. Resolve the connect code (public endpoint)
    resolve_resp = await client.get(f"/api/v1/connect/{connect_code}")
    assert resolve_resp.status_code == 200
    resolve_data = resolve_resp.json()
    assert resolve_data["websocket_url"] == "ws://192.168.1.50:9000"
    assert resolve_data["show_name"] == "Friday Night Beats"
    assert resolve_data["dj_count"] == 1
    assert "token" in resolve_data

    # 4. Verify the JWT is valid
    import jwt as pyjwt

    decoded = pyjwt.decode(
        resolve_data["token"],
        jwt_secret,
        algorithms=["HS256"],
    )
    assert decoded["iss"] == "mcav-coordinator"
    assert decoded["show_id"] == show_id
    assert decoded["server_id"] == server_id
    assert decoded["permissions"] == ["stream"]

    # 5. Get show details
    detail_resp = await client.get(
        f"/api/v1/shows/{show_id}",
        headers={"Authorization": "Bearer test-key-12345"},
    )
    assert detail_resp.status_code == 200
    detail_data = detail_resp.json()
    assert detail_data["current_djs"] == 1
    assert detail_data["status"] == "active"

    # 6. End the show
    end_resp = await client.delete(
        f"/api/v1/shows/{show_id}",
        headers={"Authorization": "Bearer test-key-12345"},
    )
    assert end_resp.status_code == 200
    end_data = end_resp.json()
    assert end_data["status"] == "ended"

    # 7. Resolving the code again should fail (show ended, code cleared)
    resolve_again = await client.get(f"/api/v1/connect/{connect_code}")
    assert resolve_again.status_code == 404


# ---------------------------------------------------------------------------
# Error cases
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_resolve_unknown_code_returns_404(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/connect/BASS-ZZZZ")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_create_show_wrong_key_returns_401(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/shows",
        json={
            "server_id": "00000000-0000-0000-0000-000000000000",
            "name": "Bad Show",
        },
        headers={"Authorization": "Bearer wrong-key"},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_heartbeat(client: AsyncClient) -> None:
    # Register first
    user_token = await _get_user_token(client, "heartbeat@example.com")
    reg_data = await _register_server(
        client,
        user_token,
        name="Heartbeat Server",
        websocket_url="ws://localhost:9001",
        api_key="heartbeat-key",
    )
    server_id = reg_data["server_id"]

    # Send heartbeat
    hb_resp = await client.put(
        f"/api/v1/servers/{server_id}/heartbeat",
        headers={"Authorization": "Bearer heartbeat-key"},
    )
    assert hb_resp.status_code == 200
    assert hb_resp.json()["server_id"] == server_id
    assert "last_heartbeat" in hb_resp.json()


@pytest.mark.asyncio
async def test_heartbeat_wrong_server_returns_403(client: AsyncClient) -> None:
    # Register a server
    user_token = await _get_user_token(client, "hb403@example.com")
    await _register_server(
        client,
        user_token,
        name="Server A",
        websocket_url="ws://localhost:9002",
        api_key="key-a",
    )
    # Try to heartbeat a different server_id
    hb_resp = await client.put(
        "/api/v1/servers/00000000-0000-0000-0000-000000000000/heartbeat",
        headers={"Authorization": "Bearer key-a"},
    )
    assert hb_resp.status_code == 403


@pytest.mark.asyncio
async def test_end_show_twice_returns_400(client: AsyncClient) -> None:
    # Register + create show
    user_token = await _get_user_token(client, "doubleend@example.com")
    reg_data = await _register_server(
        client,
        user_token,
        name="Double End",
        websocket_url="ws://localhost:9003",
        api_key="double-end-key",
    )
    server_id = reg_data["server_id"]

    show = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Temp Show"},
        headers={"Authorization": "Bearer double-end-key"},
    )
    show_id = show.json()["show_id"]

    # End once -- should succeed
    end1 = await client.delete(
        f"/api/v1/shows/{show_id}",
        headers={"Authorization": "Bearer double-end-key"},
    )
    assert end1.status_code == 200

    # End again -- should fail
    end2 = await client.delete(
        f"/api/v1/shows/{show_id}",
        headers={"Authorization": "Bearer double-end-key"},
    )
    assert end2.status_code == 400
