"""Tests for CoordinatorClient using aiohttp test server."""

from __future__ import annotations


import pytest
from aiohttp import web

from community_bot.coordinator_client import CoordinatorClient

WEBHOOK_SECRET = "test-secret-abc123"  # nosec B105


# ---------------------------------------------------------------------------
# Helpers — build mock aiohttp apps
# ---------------------------------------------------------------------------


def _make_app(handlers: dict[str, web.AbstractRouteDef] | None = None) -> web.Application:
    """Create a minimal aiohttp app with the given route table."""
    app = web.Application()
    if handlers:
        app.router.add_routes(handlers)
    return app


# ---------------------------------------------------------------------------
# GET /webhooks/discord/users/{discord_id}
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_user_by_discord_id(aiohttp_server):
    """Client fetches user by Discord ID and returns parsed dict."""
    received_headers: dict[str, str] = {}

    async def handler(request: web.Request) -> web.Response:
        received_headers.update(dict(request.headers))
        discord_id = request.match_info["discord_id"]
        return web.json_response(
            {
                "user_id": "u-001",
                "discord_id": discord_id,
                "discord_username": "testuser",
                "roles": [
                    {"role": "dj", "source": "discord", "created_at": "2026-01-01T00:00:00"}
                ],
            }
        )

    app = web.Application()
    app.router.add_get("/webhooks/discord/users/{discord_id}", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret=WEBHOOK_SECRET,
    )
    try:
        result = await client.get_user_by_discord_id("123456")

        assert result is not None
        assert result["user_id"] == "u-001"
        assert result["discord_id"] == "123456"
        assert result["discord_username"] == "testuser"
        assert len(result["roles"]) == 1
        assert received_headers.get("X-Webhook-Secret") == WEBHOOK_SECRET
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_get_user_not_found(aiohttp_server):
    """Client returns None for unknown Discord ID (404)."""

    async def handler(request: web.Request) -> web.Response:
        return web.json_response({"detail": "User not found"}, status=404)

    app = web.Application()
    app.router.add_get("/webhooks/discord/users/{discord_id}", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret=WEBHOOK_SECRET,
    )
    try:
        result = await client.get_user_by_discord_id("999999")
        assert result is None
    finally:
        await client.close()


# ---------------------------------------------------------------------------
# POST /webhooks/discord/role-sync
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_sync_roles(aiohttp_server):
    """Client pushes roles to coordinator and returns parsed response."""
    received_body: dict = {}
    received_headers: dict[str, str] = {}

    async def handler(request: web.Request) -> web.Response:
        received_headers.update(dict(request.headers))
        received_body.update(await request.json())
        return web.json_response(
            {
                "user_id": "u-001",
                "roles": [
                    {"role": "dj", "source": "discord", "created_at": "2026-01-01T00:00:00"},
                    {"role": "vj", "source": "discord", "created_at": "2026-01-01T00:00:00"},
                ],
            }
        )

    app = web.Application()
    app.router.add_post("/webhooks/discord/role-sync", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret=WEBHOOK_SECRET,
    )
    try:
        result = await client.sync_roles("123456", ["dj", "vj"])

        assert result is not None
        assert result["user_id"] == "u-001"
        assert len(result["roles"]) == 2
        assert received_body["discord_id"] == "123456"
        assert received_body["roles"] == ["dj", "vj"]
        assert received_headers.get("X-Webhook-Secret") == WEBHOOK_SECRET
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_sync_roles_user_not_found(aiohttp_server):
    """Client returns None when user is not found during role sync."""

    async def handler(request: web.Request) -> web.Response:
        return web.json_response({"detail": "User not found"}, status=404)

    app = web.Application()
    app.router.add_post("/webhooks/discord/role-sync", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret=WEBHOOK_SECRET,
    )
    try:
        result = await client.sync_roles("999999", ["dj"])
        assert result is None
    finally:
        await client.close()


# ---------------------------------------------------------------------------
# DELETE /webhooks/discord/role-sync/{discord_id}/{role}
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_remove_role(aiohttp_server):
    """Client removes a role and returns updated roles."""
    received_headers: dict[str, str] = {}

    async def handler(request: web.Request) -> web.Response:
        received_headers.update(dict(request.headers))
        _ = request.match_info["discord_id"]
        _ = request.match_info["role"]
        return web.json_response(
            {
                "user_id": "u-001",
                "roles": [],  # role was removed
            }
        )

    app = web.Application()
    app.router.add_delete("/webhooks/discord/role-sync/{discord_id}/{role}", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret=WEBHOOK_SECRET,
    )
    try:
        result = await client.remove_role("123456", "dj")

        assert result is not None
        assert result["user_id"] == "u-001"
        assert result["roles"] == []
        assert received_headers.get("X-Webhook-Secret") == WEBHOOK_SECRET
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_remove_role_not_found(aiohttp_server):
    """Client returns None when role or user not found."""

    async def handler(request: web.Request) -> web.Response:
        return web.json_response({"detail": "Not found"}, status=404)

    app = web.Application()
    app.router.add_delete("/webhooks/discord/role-sync/{discord_id}/{role}", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret=WEBHOOK_SECRET,
    )
    try:
        result = await client.remove_role("123456", "dj")
        assert result is None
    finally:
        await client.close()


# ---------------------------------------------------------------------------
# Error handling
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_server_error_raises(aiohttp_server):
    """Client raises on non-404 HTTP errors (e.g. 500)."""

    async def handler(request: web.Request) -> web.Response:
        return web.json_response({"detail": "Internal server error"}, status=500)

    app = web.Application()
    app.router.add_get("/webhooks/discord/users/{discord_id}", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret=WEBHOOK_SECRET,
    )
    try:
        with pytest.raises(Exception):
            await client.get_user_by_discord_id("123456")
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_lazy_session_creation(aiohttp_server):
    """Session is not created until first request."""

    async def handler(request: web.Request) -> web.Response:
        return web.json_response(
            {"user_id": "u-001", "discord_id": "123", "discord_username": "x", "roles": []}
        )

    app = web.Application()
    app.router.add_get("/webhooks/discord/users/{discord_id}", handler)
    server = await aiohttp_server(app)

    client = CoordinatorClient(
        base_url=f"http://localhost:{server.port}",
        webhook_secret=WEBHOOK_SECRET,
    )
    try:
        # Session should not exist yet
        assert client._session is None

        # After a request, session should be created
        await client.get_user_by_discord_id("123")
        assert client._session is not None
    finally:
        await client.close()
