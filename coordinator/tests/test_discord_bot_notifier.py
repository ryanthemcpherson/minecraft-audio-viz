"""Unit tests for app.services.discord_bot_notifier.

Mocks httpx to test the fire-and-forget notification logic
without making real HTTP calls.
"""

from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, patch

import httpx
import pytest

from app.config import Settings
from app.services.discord_bot_notifier import notify_role_change


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _settings_with_bot(
    bot_url: str = "http://localhost:8100",
    webhook_secret: str = "test-secret",
) -> Settings:
    return Settings(
        database_url="sqlite+aiosqlite:///:memory:",
        user_jwt_secret="test-user-jwt-secret-for-tests-32+chars",
        community_bot_url=bot_url,
        discord_webhook_secret=webhook_secret,
    )


def _settings_no_bot() -> Settings:
    return Settings(
        database_url="sqlite+aiosqlite:///:memory:",
        user_jwt_secret="test-user-jwt-secret-for-tests-32+chars",
        community_bot_url="",
    )


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestNotifyRoleChange:
    async def test_returns_false_when_bot_url_not_configured(self) -> None:
        result = await notify_role_change(
            settings=_settings_no_bot(),
            discord_id="123456",
            user_id=uuid.uuid4(),
            roles=["dj"],
        )
        assert result is False

    async def test_returns_true_on_200_response(self) -> None:
        mock_response = httpx.Response(200, request=httpx.Request("POST", "http://test"))

        with patch("app.services.discord_bot_notifier.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.post.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            result = await notify_role_change(
                settings=_settings_with_bot(),
                discord_id="123456",
                user_id=uuid.uuid4(),
                roles=["dj", "beta_tester"],
            )

        assert result is True
        mock_client.post.assert_called_once()
        call_kwargs = mock_client.post.call_args
        assert call_kwargs.kwargs["json"]["discord_id"] == "123456"
        assert call_kwargs.kwargs["json"]["roles"] == ["dj", "beta_tester"]
        assert call_kwargs.kwargs["headers"]["X-Webhook-Secret"] == "test-secret"

    async def test_returns_false_on_non_200_response(self) -> None:
        mock_response = httpx.Response(500, request=httpx.Request("POST", "http://test"))

        with patch("app.services.discord_bot_notifier.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.post.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            result = await notify_role_change(
                settings=_settings_with_bot(),
                discord_id="123456",
                user_id=uuid.uuid4(),
                roles=["dj"],
            )

        assert result is False

    async def test_returns_false_on_http_error(self) -> None:
        with patch("app.services.discord_bot_notifier.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.post.side_effect = httpx.ConnectError("Connection refused")
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            result = await notify_role_change(
                settings=_settings_with_bot(),
                discord_id="123456",
                user_id=uuid.uuid4(),
                roles=["dj"],
            )

        assert result is False

    async def test_no_webhook_secret_header_when_empty(self) -> None:
        mock_response = httpx.Response(200, request=httpx.Request("POST", "http://test"))

        with patch("app.services.discord_bot_notifier.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.post.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            result = await notify_role_change(
                settings=_settings_with_bot(webhook_secret=""),
                discord_id="123456",
                user_id=uuid.uuid4(),
                roles=["dj"],
            )

        assert result is True
        call_kwargs = mock_client.post.call_args
        assert "X-Webhook-Secret" not in call_kwargs.kwargs["headers"]
