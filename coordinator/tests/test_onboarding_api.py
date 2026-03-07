"""Integration tests for the onboarding router (/onboarding/)."""

from __future__ import annotations

import pytest
from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _register_user(
    client: AsyncClient,
    email: str = "onboard@example.com",
    display_name: str = "Onboard User",
) -> tuple[str, dict]:
    """Register a user and return (access_token, user_data)."""
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": display_name},
    )
    assert resp.status_code == 201, resp.text
    data = resp.json()
    return data["access_token"], data["user"]


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# POST /onboarding/complete
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_complete_onboarding_dj(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "complete-dj@example.com")
    resp = await client.post(
        "/api/v1/onboarding/complete",
        json={"user_type": "dj"},
        headers=_auth(token),
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["onboarding_completed"] is True


@pytest.mark.asyncio
async def test_complete_onboarding_server_owner(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "complete-owner@example.com")
    resp = await client.post(
        "/api/v1/onboarding/complete",
        json={"user_type": "server_owner"},
        headers=_auth(token),
    )
    assert resp.status_code == 200
    assert resp.json()["onboarding_completed"] is True


@pytest.mark.asyncio
async def test_complete_onboarding_team_member(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "complete-team@example.com")
    resp = await client.post(
        "/api/v1/onboarding/complete",
        json={"user_type": "team_member"},
        headers=_auth(token),
    )
    assert resp.status_code == 200
    assert resp.json()["onboarding_completed"] is True


@pytest.mark.asyncio
async def test_complete_onboarding_invalid_type_returns_422(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "complete-bad@example.com")
    resp = await client.post(
        "/api/v1/onboarding/complete",
        json={"user_type": "invalid_type"},
        headers=_auth(token),
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_complete_onboarding_no_auth_returns_error(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/onboarding/complete",
        json={"user_type": "dj"},
    )
    assert resp.status_code in (401, 422)


# ---------------------------------------------------------------------------
# POST /onboarding/skip
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_skip_onboarding(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "skip@example.com")
    resp = await client.post("/api/v1/onboarding/skip", headers=_auth(token))
    assert resp.status_code == 200
    data = resp.json()
    assert data["onboarding_completed"] is True


@pytest.mark.asyncio
async def test_skip_onboarding_no_auth_returns_error(client: AsyncClient) -> None:
    resp = await client.post("/api/v1/onboarding/skip")
    assert resp.status_code in (401, 422)


# ---------------------------------------------------------------------------
# POST /onboarding/reset
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_reset_onboarding(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "reset@example.com")

    # Complete first
    await client.post(
        "/api/v1/onboarding/complete",
        json={"user_type": "dj"},
        headers=_auth(token),
    )

    # Then reset
    resp = await client.post("/api/v1/onboarding/reset", headers=_auth(token))
    assert resp.status_code == 200
    data = resp.json()
    assert data["onboarding_completed"] is False


@pytest.mark.asyncio
async def test_reset_onboarding_without_completing_first(client: AsyncClient) -> None:
    """Resetting onboarding when it was never completed should still succeed."""
    token, _ = await _register_user(client, "reset-fresh@example.com")
    resp = await client.post("/api/v1/onboarding/reset", headers=_auth(token))
    assert resp.status_code == 200
    assert resp.json()["onboarding_completed"] is False


# ---------------------------------------------------------------------------
# POST /onboarding/reset-full
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_reset_full_clears_onboarding(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "resetfull@example.com")

    # Complete onboarding
    await client.post(
        "/api/v1/onboarding/complete",
        json={"user_type": "dj"},
        headers=_auth(token),
    )

    # Full reset
    resp = await client.post("/api/v1/onboarding/reset-full", headers=_auth(token))
    assert resp.status_code == 200
    data = resp.json()
    assert data["onboarding_completed"] is False


@pytest.mark.asyncio
async def test_reset_full_clears_dj_profile(client: AsyncClient) -> None:
    token, _ = await _register_user(client, "resetfull-dj@example.com")

    # Create a DJ profile
    await client.post(
        "/api/v1/dj/profile",
        json={"dj_name": "DJ Reset"},
        headers=_auth(token),
    )

    # Full reset should delete the DJ profile
    resp = await client.post("/api/v1/onboarding/reset-full", headers=_auth(token))
    assert resp.status_code == 200

    # Verify DJ profile is gone
    profile_resp = await client.get("/api/v1/dj/profile", headers=_auth(token))
    assert profile_resp.status_code == 404


@pytest.mark.asyncio
async def test_reset_full_no_auth_returns_error(client: AsyncClient) -> None:
    resp = await client.post("/api/v1/onboarding/reset-full")
    assert resp.status_code in (401, 422)
