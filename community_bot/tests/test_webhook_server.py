"""Tests for community bot webhook authentication."""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from community_bot.webhook_server import create_webhook_app


class FakeMember:
    def __init__(self) -> None:
        self.roles: list[object] = []


class FakeGuild:
    def __init__(self) -> None:
        self.id = 123
        self.roles: list[object] = []
        self.member = FakeMember()

    def get_member(self, _discord_id: int) -> FakeMember:
        return self.member


class FakeBot:
    def __init__(self, webhook_secret: str) -> None:
        self.config = SimpleNamespace(webhook_secret=webhook_secret, guild_id=123)
        self.guild = FakeGuild()

    def get_guild(self, _guild_id: int) -> FakeGuild:
        return self.guild


@pytest.mark.asyncio
async def test_empty_configured_secret_never_authenticates(aiohttp_client) -> None:
    bot = FakeBot(webhook_secret="")  # nosec B106
    client = await aiohttp_client(create_webhook_app(bot))

    response = await client.post(
        "/notify/role-change",
        json={"discord_id": "1", "roles": []},
    )

    assert response.status == 503


@pytest.mark.asyncio
async def test_missing_webhook_secret_header_is_unauthorized(aiohttp_client) -> None:
    bot = FakeBot(webhook_secret="configured-secret")  # nosec B106
    client = await aiohttp_client(create_webhook_app(bot))

    response = await client.post(
        "/notify/role-change",
        json={"discord_id": "1", "roles": []},
    )

    assert response.status == 401


@pytest.mark.asyncio
async def test_wrong_webhook_secret_header_is_unauthorized(aiohttp_client) -> None:
    bot = FakeBot(webhook_secret="configured-secret")  # nosec B106
    client = await aiohttp_client(create_webhook_app(bot))

    response = await client.post(
        "/notify/role-change",
        headers={"X-Webhook-Secret": "wrong-secret"},
        json={"discord_id": "1", "roles": []},
    )

    assert response.status == 401


@pytest.mark.asyncio
async def test_non_ascii_webhook_secret_header_is_unauthorized(aiohttp_client) -> None:
    bot = FakeBot(webhook_secret="configured-secret")  # nosec B106
    client = await aiohttp_client(create_webhook_app(bot))

    response = await client.post(
        "/notify/role-change",
        headers={"X-Webhook-Secret": "café"},
        json={"discord_id": "1", "roles": []},
    )

    assert response.status == 401


@pytest.mark.asyncio
async def test_correct_webhook_secret_header_authenticates(aiohttp_client) -> None:
    bot = FakeBot(webhook_secret="configured-secret")  # nosec B106
    client = await aiohttp_client(create_webhook_app(bot))

    response = await client.post(
        "/notify/role-change",
        headers={"X-Webhook-Secret": "configured-secret"},
        json={"discord_id": "1", "roles": []},
    )

    assert response.status == 200
    assert await response.json() == {"status": "synced"}
