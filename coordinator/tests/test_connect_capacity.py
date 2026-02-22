"""Tests for show capacity enforcement (max_djs) with connect and disconnect."""

from __future__ import annotations

import pytest
from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str = "cap@example.com") -> str:
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Cap Tester"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _register_server(
    client: AsyncClient,
    user_token: str,
    name: str = "Cap Server",
    websocket_url: str = "ws://localhost:9100",
    api_key: str = "cap-test-key",
) -> dict:
    resp = await client.post(
        "/api/v1/servers/register",
        json={"name": name, "websocket_url": websocket_url, "api_key": api_key},
        headers={"Authorization": f"Bearer {user_token}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _create_show(
    client: AsyncClient,
    server_id: str,
    api_key: str,
    name: str = "Cap Show",
    max_djs: int = 2,
) -> dict:
    resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": name, "max_djs": max_djs},
        headers={"Authorization": f"Bearer {api_key}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_capacity_enforcement_with_disconnect(client: AsyncClient) -> None:
    """Full capacity lifecycle: fill show, reject 3rd DJ, disconnect one, reconnect."""
    user_token = await _get_user_token(client, "cap1@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Cap Server 1",
        websocket_url="ws://localhost:9101",
        api_key="cap-key-1",
    )
    server_id = reg["server_id"]
    show = await _create_show(client, server_id, "cap-key-1", max_djs=2)
    connect_code = show["connect_code"]

    # Connect DJ #1 — should succeed
    resp1 = await client.get(f"/api/v1/connect/{connect_code}")
    assert resp1.status_code == 200
    data1 = resp1.json()
    assert data1["dj_count"] == 1

    # Connect DJ #2 — should succeed (fills the show)
    resp2 = await client.get(f"/api/v1/connect/{connect_code}")
    assert resp2.status_code == 200
    data2 = resp2.json()
    assert data2["dj_count"] == 2

    # Connect DJ #3 — should be rejected (show full)
    resp3 = await client.get(f"/api/v1/connect/{connect_code}")
    assert resp3.status_code == 409
    assert "full" in resp3.json()["detail"].lower()

    # We need the DJ session ID from the JWT to disconnect.
    # Decode the token from DJ #1 to get the session ID (stored in "sub").
    import jwt as pyjwt

    token1 = data1["token"]
    decoded = pyjwt.decode(token1, options={"verify_signature": False})
    dj_session_id = decoded["sub"]

    # Disconnect DJ #1
    disc_resp = await client.post(
        f"/api/v1/shows/disconnect/{dj_session_id}",
        headers={"Authorization": "Bearer cap-key-1"},
    )
    assert disc_resp.status_code == 204

    # Verify the show now has 1 DJ
    detail_resp = await client.get(
        f"/api/v1/shows/{show['show_id']}",
        headers={"Authorization": "Bearer cap-key-1"},
    )
    assert detail_resp.status_code == 200
    assert detail_resp.json()["current_djs"] == 1

    # Connect DJ #3 again — should succeed now
    resp4 = await client.get(f"/api/v1/connect/{connect_code}")
    assert resp4.status_code == 200
    assert resp4.json()["dj_count"] == 2


@pytest.mark.asyncio
async def test_capacity_max_djs_one(client: AsyncClient) -> None:
    """Show with max_djs=1 should reject second connection immediately."""
    user_token = await _get_user_token(client, "cap2@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Solo Server",
        websocket_url="ws://localhost:9102",
        api_key="solo-key",
    )
    server_id = reg["server_id"]
    show = await _create_show(client, server_id, "solo-key", name="Solo Show", max_djs=1)
    connect_code = show["connect_code"]

    # First connection fills the show
    resp1 = await client.get(f"/api/v1/connect/{connect_code}")
    assert resp1.status_code == 200

    # Second connection rejected
    resp2 = await client.get(f"/api/v1/connect/{connect_code}")
    assert resp2.status_code == 409
