"""Unit tests for app.services.discord_oauth.

Mocks httpx to test the OAuth2 flow logic without calling Discord APIs.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import httpx
import pytest

from app.services.discord_oauth import (
    DISCORD_AUTHORIZE_URL,
    DISCORD_TOKEN_URL,
    DISCORD_USER_URL,
    DiscordUser,
    exchange_code,
    get_authorize_url,
    get_discord_user,
)


# ---------------------------------------------------------------------------
# get_authorize_url
# ---------------------------------------------------------------------------


class TestGetAuthorizeUrl:
    def test_contains_required_params(self) -> None:
        url = get_authorize_url(
            client_id="my-client-id",
            redirect_uri="http://localhost:3000/callback",
            state="random-state",
        )
        assert DISCORD_AUTHORIZE_URL in url
        assert "client_id=my-client-id" in url
        assert "redirect_uri=" in url
        assert "state=random-state" in url
        assert "response_type=code" in url
        assert "scope=identify+email" in url

    def test_includes_prompt_none(self) -> None:
        url = get_authorize_url(
            client_id="cid", redirect_uri="http://redir", state="s"
        )
        assert "prompt=none" in url


# ---------------------------------------------------------------------------
# exchange_code
# ---------------------------------------------------------------------------


class TestExchangeCode:
    async def test_returns_access_token_on_success(self) -> None:
        mock_response = httpx.Response(
            200,
            json={"access_token": "discord-token-123", "token_type": "Bearer"},
            request=httpx.Request("POST", DISCORD_TOKEN_URL),
        )

        with patch("app.services.discord_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.post.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            token = await exchange_code(
                code="auth-code-xyz",
                client_id="cid",
                client_secret="csec",
                redirect_uri="http://redir",
            )

        assert token == "discord-token-123"
        call_kwargs = mock_client.post.call_args
        assert call_kwargs.args[0] == DISCORD_TOKEN_URL
        assert call_kwargs.kwargs["data"]["code"] == "auth-code-xyz"
        assert call_kwargs.kwargs["data"]["grant_type"] == "authorization_code"

    async def test_raises_on_error_response(self) -> None:
        mock_response = httpx.Response(
            401,
            json={"error": "invalid_grant"},
            request=httpx.Request("POST", DISCORD_TOKEN_URL),
        )

        with patch("app.services.discord_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.post.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            with pytest.raises(httpx.HTTPStatusError):
                await exchange_code(
                    code="bad-code",
                    client_id="cid",
                    client_secret="csec",
                    redirect_uri="http://redir",
                )


# ---------------------------------------------------------------------------
# get_discord_user
# ---------------------------------------------------------------------------


class TestGetDiscordUser:
    async def test_returns_discord_user_with_all_fields(self) -> None:
        mock_response = httpx.Response(
            200,
            json={
                "id": "12345",
                "username": "testuser",
                "email": "test@example.com",
                "avatar": "abc123",
            },
            request=httpx.Request("GET", DISCORD_USER_URL),
        )

        with patch("app.services.discord_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            user = await get_discord_user("access-token-xyz")

        assert isinstance(user, DiscordUser)
        assert user.id == "12345"
        assert user.username == "testuser"
        assert user.email == "test@example.com"
        assert user.avatar == "abc123"

        # Verify auth header
        call_kwargs = mock_client.get.call_args
        assert call_kwargs.kwargs["headers"]["Authorization"] == "Bearer access-token-xyz"

    async def test_returns_user_with_optional_fields_missing(self) -> None:
        mock_response = httpx.Response(
            200,
            json={"id": "99999", "username": "noemail"},
            request=httpx.Request("GET", DISCORD_USER_URL),
        )

        with patch("app.services.discord_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            user = await get_discord_user("token")

        assert user.id == "99999"
        assert user.email is None
        assert user.avatar is None

    async def test_raises_on_unauthorized(self) -> None:
        mock_response = httpx.Response(
            401,
            json={"message": "401: Unauthorized"},
            request=httpx.Request("GET", DISCORD_USER_URL),
        )

        with patch("app.services.discord_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            with pytest.raises(httpx.HTTPStatusError):
                await get_discord_user("bad-token")
