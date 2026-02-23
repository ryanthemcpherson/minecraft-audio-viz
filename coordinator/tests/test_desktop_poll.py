"""Tests for the polling-based desktop OAuth flow."""

from __future__ import annotations

import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
class TestDesktopPoll:
    async def test_poll_unknown_token_returns_pending(self, client: AsyncClient):
        resp = await client.get("/api/v1/auth/desktop-poll/nonexistent")
        assert resp.status_code == 200
        assert resp.json()["status"] == "pending"

    async def test_discord_desktop_returns_poll_token(self, client: AsyncClient):
        resp = await client.get("/api/v1/auth/discord", params={"desktop": "true"})
        assert resp.status_code == 200
        data = resp.json()
        assert "authorize_url" in data
        assert "poll_token" in data
        assert len(data["poll_token"]) > 0

    async def test_google_desktop_returns_poll_token(self, client: AsyncClient):
        resp = await client.get("/api/v1/auth/google", params={"desktop": "true"})
        assert resp.status_code == 200
        data = resp.json()
        assert "authorize_url" in data
        assert "poll_token" in data

    async def test_non_desktop_has_no_poll_token(self, client: AsyncClient):
        resp = await client.get("/api/v1/auth/discord")
        assert resp.status_code == 200
        data = resp.json()
        assert data.get("poll_token") is None
