"""Tests for connect code resolution edge cases."""

from __future__ import annotations

import uuid

import pytest
from app.models.db import Show, VJServer
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str) -> str:
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Edge Tester"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _register_server(
    client: AsyncClient,
    user_token: str,
    name: str = "Edge Server",
    websocket_url: str = "ws://localhost:9200",
    api_key: str = "edge-key",
) -> dict:
    resp = await client.post(
        "/api/v1/servers/register",
        json={"name": name, "websocket_url": websocket_url, "api_key": api_key},
        headers={"Authorization": f"Bearer {user_token}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_resolve_nonexistent_code_returns_404(client: AsyncClient) -> None:
    """Resolving a code that does not exist should return 404."""
    resp = await client.get("/api/v1/connect/BASS-9999")
    assert resp.status_code == 404
    assert "not found" in resp.json()["detail"].lower()


@pytest.mark.asyncio
async def test_resolve_expired_code_returns_404(client: AsyncClient) -> None:
    """After ending a show, its connect code should no longer resolve."""
    user_token = await _get_user_token(client, "expired@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Expired Server",
        websocket_url="ws://localhost:9201",
        api_key="expired-key",
    )
    server_id = reg["server_id"]

    # Create and immediately end a show
    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Temp Show", "max_djs": 4},
        headers={"Authorization": "Bearer expired-key"},
    )
    assert show_resp.status_code == 201
    show_data = show_resp.json()
    connect_code = show_data["connect_code"]

    end_resp = await client.delete(
        f"/api/v1/shows/{show_data['show_id']}",
        headers={"Authorization": "Bearer expired-key"},
    )
    assert end_resp.status_code == 200

    # Resolve the now-expired code
    resolve_resp = await client.get(f"/api/v1/connect/{connect_code}")
    assert resolve_resp.status_code == 404


@pytest.mark.asyncio
async def test_resolve_inactive_server_returns_503(
    client: AsyncClient, db_session: AsyncSession
) -> None:
    """Resolving a code whose server is inactive should return 503."""
    # Create server and show directly in the DB, then deactivate the server
    server_id = uuid.uuid4()
    show_id = uuid.uuid4()

    from app.services.password import hash_password

    db_session.add(
        VJServer(
            id=server_id,
            name="Inactive Server",
            websocket_url="ws://inactive:9999",
            api_key_hash=hash_password("inactive-key"),
            jwt_secret="secret-inactive",  # nosec B106
            is_active=False,  # server is offline
        )
    )
    db_session.add(
        Show(
            id=show_id,
            server_id=server_id,
            name="Ghost Show",
            connect_code="BASS-DEAD",
            status="active",
            max_djs=4,
            current_djs=0,
        )
    )
    await db_session.commit()

    resp = await client.get("/api/v1/connect/BASS-DEAD")
    assert resp.status_code == 503
    assert "offline" in resp.json()["detail"].lower()


@pytest.mark.asyncio
async def test_resolve_case_insensitive(client: AsyncClient) -> None:
    """Connect code resolution should be case-insensitive."""
    user_token = await _get_user_token(client, "case@example.com")
    reg = await _register_server(
        client,
        user_token,
        name="Case Server",
        websocket_url="ws://localhost:9202",
        api_key="case-key",
    )
    server_id = reg["server_id"]

    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Case Show", "max_djs": 4},
        headers={"Authorization": "Bearer case-key"},
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]  # e.g. "BASS-A2B3"

    # Resolve with lowercase version
    lower_code = connect_code.lower()
    resp = await client.get(f"/api/v1/connect/{lower_code}")
    assert resp.status_code == 200
    assert resp.json()["show_name"] == "Case Show"


@pytest.mark.asyncio
async def test_resolve_with_whitespace(client: AsyncClient) -> None:
    """Connect code resolution should strip leading/trailing whitespace."""
    user_token = await _get_user_token(client, "ws@example.com")
    reg = await _register_server(
        client, user_token, name="WS Server", websocket_url="ws://localhost:9203", api_key="ws-key"
    )
    server_id = reg["server_id"]

    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "WS Show", "max_djs": 4},
        headers={"Authorization": "Bearer ws-key"},
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]

    # URL-encoded space is %20; in practice the path stripping handles this
    # but we test the normalise_code function via the endpoint
    resp = await client.get(f"/api/v1/connect/ {connect_code} ")
    # FastAPI strips path params, but normalise_code also strips
    assert resp.status_code == 200
