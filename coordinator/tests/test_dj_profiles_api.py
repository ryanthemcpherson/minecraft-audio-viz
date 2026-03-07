"""Integration tests for the DJ profiles router (/api/v1/dj/)."""

from __future__ import annotations

import pytest
from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str = "dj@example.com") -> str:
    """Register a user and return the access token."""
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "DJ User"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# Create profile
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_create_dj_profile(client: AsyncClient) -> None:
    token = await _get_user_token(client, "create@example.com")
    resp = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Test", "bio": "Test bio", "genres": "EDM"},
        headers=_auth(token),
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["dj_name"] == "DJ Test"
    assert data["bio"] == "Test bio"
    assert data["genres"] == "EDM"
    assert data["is_public"] is True
    assert "id" in data
    assert "user_id" in data
    assert "created_at" in data


@pytest.mark.asyncio
async def test_create_dj_profile_with_slug(client: AsyncClient) -> None:
    token = await _get_user_token(client, "slugcreate@example.com")
    resp = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Slug", "slug": "dj-slug-test"},
        headers=_auth(token),
    )
    assert resp.status_code == 201
    assert resp.json()["slug"] == "dj-slug-test"


@pytest.mark.asyncio
async def test_create_dj_profile_duplicate_returns_409(client: AsyncClient) -> None:
    token = await _get_user_token(client, "dup@example.com")
    resp1 = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ First"},
        headers=_auth(token),
    )
    assert resp1.status_code == 201

    resp2 = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Second"},
        headers=_auth(token),
    )
    assert resp2.status_code == 409


@pytest.mark.asyncio
async def test_create_dj_profile_duplicate_slug_returns_409(client: AsyncClient) -> None:
    token1 = await _get_user_token(client, "slug1@example.com")
    token2 = await _get_user_token(client, "slug2@example.com")

    resp1 = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ One", "slug": "unique-slug"},
        headers=_auth(token1),
    )
    assert resp1.status_code == 201

    resp2 = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Two", "slug": "unique-slug"},
        headers=_auth(token2),
    )
    assert resp2.status_code == 409


# ---------------------------------------------------------------------------
# Get own profile
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_own_profile(client: AsyncClient) -> None:
    token = await _get_user_token(client, "getown@example.com")
    await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Mine", "bio": "My bio"},
        headers=_auth(token),
    )

    resp = await client.get("/api/v1/dj/profile", headers=_auth(token))
    assert resp.status_code == 200
    assert resp.json()["dj_name"] == "DJ Mine"


@pytest.mark.asyncio
async def test_get_own_profile_not_found(client: AsyncClient) -> None:
    token = await _get_user_token(client, "noprofile@example.com")
    resp = await client.get("/api/v1/dj/profile", headers=_auth(token))
    assert resp.status_code == 404


# ---------------------------------------------------------------------------
# Update profile
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_update_dj_profile(client: AsyncClient) -> None:
    token = await _get_user_token(client, "update@example.com")
    await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Before"},
        headers=_auth(token),
    )

    resp = await client.put(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ After", "bio": "Updated bio", "is_public": False},
        headers=_auth(token),
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["dj_name"] == "DJ After"
    assert data["bio"] == "Updated bio"
    assert data["is_public"] is False


@pytest.mark.asyncio
async def test_update_profile_not_found(client: AsyncClient) -> None:
    token = await _get_user_token(client, "updateno@example.com")
    resp = await client.put(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Ghost"},
        headers=_auth(token),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_update_profile_slug_conflict(client: AsyncClient) -> None:
    token1 = await _get_user_token(client, "slugconf1@example.com")
    token2 = await _get_user_token(client, "slugconf2@example.com")

    await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ A", "slug": "taken-slug"},
        headers=_auth(token1),
    )
    await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ B", "slug": "other-slug"},
        headers=_auth(token2),
    )

    resp = await client.put(
        "/api/v1/dj/profile",
        json={"slug": "taken-slug"},
        headers=_auth(token2),
    )
    assert resp.status_code == 409


# ---------------------------------------------------------------------------
# Slug check (public)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_slug_check_available(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/dj/slug-check/fresh-slug")
    assert resp.status_code == 200
    assert resp.json()["available"] is True


@pytest.mark.asyncio
async def test_slug_check_taken(client: AsyncClient) -> None:
    token = await _get_user_token(client, "slugcheck@example.com")
    await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Taken", "slug": "taken-name"},
        headers=_auth(token),
    )

    resp = await client.get("/api/v1/dj/slug-check/taken-name")
    assert resp.status_code == 200
    assert resp.json()["available"] is False


# ---------------------------------------------------------------------------
# Public profile by slug
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_profile_by_slug_public(client: AsyncClient) -> None:
    token = await _get_user_token(client, "pubslug@example.com")
    await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Public", "slug": "dj-public"},
        headers=_auth(token),
    )

    resp = await client.get("/api/v1/dj/by-slug/dj-public")
    assert resp.status_code == 200
    assert resp.json()["dj_name"] == "DJ Public"


@pytest.mark.asyncio
async def test_get_profile_by_slug_private_returns_404(client: AsyncClient) -> None:
    token = await _get_user_token(client, "privslug@example.com")
    create_resp = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Private", "slug": "dj-private"},
        headers=_auth(token),
    )
    assert create_resp.status_code == 201

    # Make private
    await client.put(
        "/api/v1/dj/profile",
        json={"is_public": False},
        headers=_auth(token),
    )

    resp = await client.get("/api/v1/dj/by-slug/dj-private")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_get_profile_by_slug_nonexistent(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/dj/by-slug/no-such-slug")
    assert resp.status_code == 404


# ---------------------------------------------------------------------------
# Unauthorized access
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_create_profile_no_auth_returns_401(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Unauth"},
    )
    assert resp.status_code in (401, 422)


@pytest.mark.asyncio
async def test_get_own_profile_no_auth_returns_401(client: AsyncClient) -> None:
    resp = await client.get("/api/v1/dj/profile")
    assert resp.status_code in (401, 422)


@pytest.mark.asyncio
async def test_update_profile_no_auth_returns_401(client: AsyncClient) -> None:
    resp = await client.put(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Unauth"},
    )
    assert resp.status_code in (401, 422)


@pytest.mark.asyncio
async def test_create_profile_invalid_token_returns_401(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Bad"},
        headers={"Authorization": "Bearer invalid-token"},
    )
    assert resp.status_code == 401
