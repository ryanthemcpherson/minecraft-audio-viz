"""Integration tests for the internal router (/internal/).

These endpoints are server-to-server (VJ server -> coordinator) and require
server API key authentication.
"""

from __future__ import annotations

import uuid

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str = "internal@example.com") -> str:
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Internal User"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _register_server(
    client: AsyncClient,
    user_token: str,
    api_key: str = "internal-key",
) -> dict:
    resp = await client.post(
        "/api/v1/servers/register",
        json={
            "name": "Internal Server",
            "websocket_url": "ws://localhost:9000",
            "api_key": api_key,
        },
        headers={"Authorization": f"Bearer {user_token}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def _server_auth(api_key: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {api_key}"}


async def _setup_dj_session_with_profile(
    client: AsyncClient,
    server_id: str,
    api_key: str,
    dj_email: str = "djuser@example.com",
) -> tuple[str, str]:
    """Create a user with DJ profile and a DJSession. Returns (dj_session_id, user_id)."""
    # Register a DJ user
    dj_resp = await client.post(
        "/api/v1/auth/register",
        json={"email": dj_email, "password": "Testpass123", "display_name": "DJ Internal"},
    )
    assert dj_resp.status_code == 201
    dj_token = dj_resp.json()["access_token"]
    dj_user_id = dj_resp.json()["user"]["id"]

    # Create DJ profile
    profile_resp = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ ProfileTest", "bio": "Test bio", "genres": "Techno"},
        headers={"Authorization": f"Bearer {dj_token}"},
    )
    assert profile_resp.status_code == 201

    # Create a show
    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Internal Show"},
        headers=_server_auth(api_key),
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]

    # Join the show as the authenticated DJ user (creates DJSession with user_id)
    join_resp = await client.post(
        f"/api/v1/connect/{connect_code}/join",
        headers={"Authorization": f"Bearer {dj_token}"},
    )
    assert join_resp.status_code == 200
    dj_session_id = join_resp.json()["dj_session_id"]

    return dj_session_id, dj_user_id


# ---------------------------------------------------------------------------
# GET /internal/dj-profile/{dj_session_id}
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_dj_profile_for_session(client: AsyncClient, db_session: AsyncSession) -> None:
    user_token = await _get_user_token(client, "int-profile@example.com")
    srv = await _register_server(client, user_token, api_key="int-profile-key")
    dj_session_id, _ = await _setup_dj_session_with_profile(
        client, srv["server_id"], "int-profile-key", "int-dj@example.com"
    )

    # Expire all cached objects so the internal endpoint re-fetches User with profile
    db_session.expire_all()

    resp = await client.get(
        f"/api/v1/internal/dj-profile/{dj_session_id}",
        headers=_server_auth("int-profile-key"),
    )
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["dj_name"] == "DJ ProfileTest"
    assert data["bio"] == "Test bio"
    assert data["genres"] == "Techno"
    assert "slug" in data
    assert "color_palette" in data
    assert "block_palette" in data
    assert "avatar_url" in data


@pytest.mark.asyncio
async def test_get_dj_profile_session_not_found_returns_404(
    client: AsyncClient,
) -> None:
    user_token = await _get_user_token(client, "int-404@example.com")
    await _register_server(client, user_token, api_key="int-404-key")

    fake_session_id = str(uuid.uuid4())
    resp = await client.get(
        f"/api/v1/internal/dj-profile/{fake_session_id}",
        headers=_server_auth("int-404-key"),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_get_dj_profile_no_profile_returns_404(
    client: AsyncClient,
) -> None:
    """DJSession exists but user has no DJ profile -> 404."""
    user_token = await _get_user_token(client, "int-noprof@example.com")
    srv = await _register_server(client, user_token, api_key="int-noprof-key")

    # Register a user without a DJ profile
    no_prof_resp = await client.post(
        "/api/v1/auth/register",
        json={
            "email": "noprofile@example.com",
            "password": "Testpass123",
            "display_name": "No Profile",
        },
    )
    assert no_prof_resp.status_code == 201
    no_prof_token = no_prof_resp.json()["access_token"]

    # Create a show and join as the user (no DJ profile)
    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": srv["server_id"], "name": "No Profile Show"},
        headers=_server_auth("int-noprof-key"),
    )
    assert show_resp.status_code == 201
    connect_code = show_resp.json()["connect_code"]

    join_resp = await client.post(
        f"/api/v1/connect/{connect_code}/join",
        headers={"Authorization": f"Bearer {no_prof_token}"},
    )
    assert join_resp.status_code == 200
    dj_session_id = join_resp.json()["dj_session_id"]

    resp = await client.get(
        f"/api/v1/internal/dj-profile/{dj_session_id}",
        headers=_server_auth("int-noprof-key"),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_get_dj_profile_no_auth_returns_error(client: AsyncClient) -> None:
    resp = await client.get(
        f"/api/v1/internal/dj-profile/{uuid.uuid4()}",
    )
    assert resp.status_code in (401, 422)


@pytest.mark.asyncio
async def test_get_dj_profile_bad_auth_returns_401(client: AsyncClient) -> None:
    resp = await client.get(
        f"/api/v1/internal/dj-profile/{uuid.uuid4()}",
        headers=_server_auth("totally-wrong-key"),
    )
    assert resp.status_code == 401
