"""Integration tests for the uploads router (/uploads/).

R2 is not configured in tests, so we primarily test auth gating and the
501 response when R2 env vars are missing.
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str = "upload@example.com") -> str:
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Upload User"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# POST /uploads/presigned-url
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_presigned_url_no_auth_returns_error(client: AsyncClient) -> None:
    resp = await client.post(
        "/api/v1/uploads/presigned-url",
        json={"context": "avatar", "content_type": "image/png"},
    )
    assert resp.status_code in (401, 422)


@pytest.mark.asyncio
async def test_presigned_url_no_r2_config_returns_501(client: AsyncClient) -> None:
    """Without R2 env vars, the endpoint should return 501."""
    token = await _get_user_token(client, "no-r2@example.com")
    resp = await client.post(
        "/api/v1/uploads/presigned-url",
        json={"context": "avatar", "content_type": "image/png"},
        headers=_auth(token),
    )
    assert resp.status_code == 501
    assert "not configured" in resp.json()["detail"].lower()


@pytest.mark.asyncio
async def test_presigned_url_invalid_context_returns_422(client: AsyncClient) -> None:
    token = await _get_user_token(client, "bad-ctx@example.com")
    resp = await client.post(
        "/api/v1/uploads/presigned-url",
        json={"context": "invalid_context", "content_type": "image/png"},
        headers=_auth(token),
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_presigned_url_invalid_content_type_returns_422(client: AsyncClient) -> None:
    token = await _get_user_token(client, "bad-ct@example.com")
    resp = await client.post(
        "/api/v1/uploads/presigned-url",
        json={"context": "avatar", "content_type": "application/pdf"},
        headers=_auth(token),
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_presigned_url_banner_context_returns_501(client: AsyncClient) -> None:
    """Banner context is valid but R2 not configured -> 501."""
    token = await _get_user_token(client, "banner@example.com")
    resp = await client.post(
        "/api/v1/uploads/presigned-url",
        json={"context": "banner", "content_type": "image/jpeg"},
        headers=_auth(token),
    )
    assert resp.status_code == 501
