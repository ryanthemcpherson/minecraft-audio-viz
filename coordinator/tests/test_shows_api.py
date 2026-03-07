"""Integration tests for the shows router (/shows/).

The full flow (create -> resolve -> join -> end) is tested in test_api.py.
These tests cover isolated edge cases for each shows endpoint.
"""

from __future__ import annotations

import uuid

import pytest
from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str = "shows@example.com") -> str:
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Shows User"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _register_server(
    client: AsyncClient,
    user_token: str,
    name: str = "Show Server",
    api_key: str = "show-server-key",
) -> dict:
    resp = await client.post(
        "/api/v1/servers/register",
        json={"name": name, "websocket_url": "ws://localhost:9000", "api_key": api_key},
        headers={"Authorization": f"Bearer {user_token}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def _server_auth(api_key: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {api_key}"}


async def _create_show(
    client: AsyncClient,
    server_id: str,
    api_key: str,
    name: str = "Test Show",
    max_djs: int = 8,
) -> dict:
    resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": name, "max_djs": max_djs},
        headers=_server_auth(api_key),
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


# ---------------------------------------------------------------------------
# POST /shows — create
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_create_show_returns_201(client: AsyncClient) -> None:
    token = await _get_user_token(client, "create-show@example.com")
    srv = await _register_server(client, token, api_key="create-show-key")
    data = await _create_show(client, srv["server_id"], "create-show-key", name="My Show")
    assert data["name"] == "My Show"
    assert "show_id" in data
    assert "connect_code" in data
    assert "created_at" in data
    assert data["server_id"] == srv["server_id"]


@pytest.mark.asyncio
async def test_create_show_connect_code_format(client: AsyncClient) -> None:
    token = await _get_user_token(client, "code-format@example.com")
    srv = await _register_server(client, token, api_key="code-format-key")
    data = await _create_show(client, srv["server_id"], "code-format-key")
    code = data["connect_code"]
    # WORD-XXXX format: 4 alpha + dash + 4 alphanumeric
    assert len(code) == 9
    assert code[4] == "-"


@pytest.mark.asyncio
async def test_create_show_wrong_server_id_returns_403(client: AsyncClient) -> None:
    token = await _get_user_token(client, "wrong-sid@example.com")
    await _register_server(client, token, api_key="wrong-sid-key")
    fake_id = str(uuid.uuid4())
    resp = await client.post(
        "/api/v1/shows",
        json={"server_id": fake_id, "name": "Bad Show"},
        headers=_server_auth("wrong-sid-key"),
    )
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_create_show_bad_auth_returns_401(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/shows",
        json={"server_id": str(uuid.uuid4()), "name": "No Auth"},
        headers=_server_auth("nonexistent-key"),
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_create_show_custom_max_djs(client: AsyncClient) -> None:
    token = await _get_user_token(client, "maxdjs-create@example.com")
    srv = await _register_server(client, token, api_key="maxdjs-create-key")
    data = await _create_show(client, srv["server_id"], "maxdjs-create-key", max_djs=2)
    show_id = data["show_id"]

    # Verify via GET
    detail = await client.get(
        f"/api/v1/shows/{show_id}",
        headers=_server_auth("maxdjs-create-key"),
    )
    assert detail.status_code == 200
    assert detail.json()["max_djs"] == 2


# ---------------------------------------------------------------------------
# GET /shows/{show_id} — detail
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_show_detail(client: AsyncClient) -> None:
    token = await _get_user_token(client, "detail@example.com")
    srv = await _register_server(client, token, api_key="detail-key")
    show = await _create_show(client, srv["server_id"], "detail-key", name="Detail Show")

    resp = await client.get(
        f"/api/v1/shows/{show['show_id']}",
        headers=_server_auth("detail-key"),
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["name"] == "Detail Show"
    assert data["status"] == "active"
    assert data["current_djs"] == 0
    assert data["max_djs"] == 8
    assert data["connect_code"] is not None


@pytest.mark.asyncio
async def test_get_show_not_found_returns_404(client: AsyncClient) -> None:
    token = await _get_user_token(client, "404show@example.com")
    await _register_server(client, token, api_key="404show-key")
    resp = await client.get(
        f"/api/v1/shows/{uuid.uuid4()}",
        headers=_server_auth("404show-key"),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_get_show_wrong_server_returns_403(client: AsyncClient) -> None:
    """Server A cannot view Server B's show."""
    token_a = await _get_user_token(client, "srvA@example.com")
    srv_a = await _register_server(client, token_a, name="Server A", api_key="key-a-shows")

    token_b = await _get_user_token(client, "srvB@example.com")
    await _register_server(client, token_b, name="Server B", api_key="key-b-shows")

    show = await _create_show(client, srv_a["server_id"], "key-a-shows")

    # Server B tries to view Server A's show
    resp = await client.get(
        f"/api/v1/shows/{show['show_id']}",
        headers=_server_auth("key-b-shows"),
    )
    assert resp.status_code == 403


# ---------------------------------------------------------------------------
# DELETE /shows/{show_id} — end
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_end_show(client: AsyncClient) -> None:
    token = await _get_user_token(client, "end@example.com")
    srv = await _register_server(client, token, api_key="end-key")
    show = await _create_show(client, srv["server_id"], "end-key")

    resp = await client.delete(
        f"/api/v1/shows/{show['show_id']}",
        headers=_server_auth("end-key"),
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ended"
    assert data["ended_at"] is not None


@pytest.mark.asyncio
async def test_end_show_not_found_returns_404(client: AsyncClient) -> None:
    token = await _get_user_token(client, "end404@example.com")
    await _register_server(client, token, api_key="end404-key")
    resp = await client.delete(
        f"/api/v1/shows/{uuid.uuid4()}",
        headers=_server_auth("end404-key"),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_end_show_wrong_server_returns_403(client: AsyncClient) -> None:
    token_a = await _get_user_token(client, "endA@example.com")
    srv_a = await _register_server(client, token_a, name="End A", api_key="end-a-key")

    token_b = await _get_user_token(client, "endB@example.com")
    await _register_server(client, token_b, name="End B", api_key="end-b-key")

    show = await _create_show(client, srv_a["server_id"], "end-a-key")

    resp = await client.delete(
        f"/api/v1/shows/{show['show_id']}",
        headers=_server_auth("end-b-key"),
    )
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_end_show_already_ended_returns_400(client: AsyncClient) -> None:
    token = await _get_user_token(client, "double-end@example.com")
    srv = await _register_server(client, token, api_key="double-end-key")
    show = await _create_show(client, srv["server_id"], "double-end-key")

    # End once
    await client.delete(
        f"/api/v1/shows/{show['show_id']}",
        headers=_server_auth("double-end-key"),
    )

    # End again
    resp = await client.delete(
        f"/api/v1/shows/{show['show_id']}",
        headers=_server_auth("double-end-key"),
    )
    assert resp.status_code == 400
