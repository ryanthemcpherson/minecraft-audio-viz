"""Unit tests for app.services.google_oauth.

Mocks httpx to test the OAuth2 flow logic without calling Google APIs.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import httpx
import pytest

from app.services.google_oauth import (
    GOOGLE_AUTHORIZE_URL,
    GOOGLE_TOKEN_URL,
    GOOGLE_USERINFO_URL,
    GoogleUser,
    exchange_code,
    get_authorize_url,
    get_google_user,
)


# ---------------------------------------------------------------------------
# get_authorize_url
# ---------------------------------------------------------------------------


class TestGetAuthorizeUrl:
    def test_contains_required_params(self) -> None:
        url = get_authorize_url(
            client_id="google-client-id",
            redirect_uri="http://localhost:3000/callback",
            state="random-state",
        )
        assert GOOGLE_AUTHORIZE_URL in url
        assert "client_id=google-client-id" in url
        assert "redirect_uri=" in url
        assert "state=random-state" in url
        assert "response_type=code" in url
        assert "scope=openid+email+profile" in url

    def test_includes_google_specific_params(self) -> None:
        url = get_authorize_url(
            client_id="cid", redirect_uri="http://redir", state="s"
        )
        assert "access_type=offline" in url
        assert "prompt=consent" in url


# ---------------------------------------------------------------------------
# exchange_code
# ---------------------------------------------------------------------------


class TestExchangeCode:
    async def test_returns_access_token_on_success(self) -> None:
        mock_response = httpx.Response(
            200,
            json={"access_token": "google-token-456", "token_type": "Bearer"},
            request=httpx.Request("POST", GOOGLE_TOKEN_URL),
        )

        with patch("app.services.google_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.post.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            token = await exchange_code(
                code="google-auth-code",
                client_id="gcid",
                client_secret="gsec",
                redirect_uri="http://redir",
            )

        assert token == "google-token-456"
        call_kwargs = mock_client.post.call_args
        assert call_kwargs.args[0] == GOOGLE_TOKEN_URL
        assert call_kwargs.kwargs["data"]["code"] == "google-auth-code"

    async def test_raises_on_error_response(self) -> None:
        mock_response = httpx.Response(
            400,
            json={"error": "invalid_grant"},
            request=httpx.Request("POST", GOOGLE_TOKEN_URL),
        )

        with patch("app.services.google_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.post.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            with pytest.raises(httpx.HTTPStatusError):
                await exchange_code(
                    code="bad-code",
                    client_id="gcid",
                    client_secret="gsec",
                    redirect_uri="http://redir",
                )


# ---------------------------------------------------------------------------
# get_google_user
# ---------------------------------------------------------------------------


class TestGetGoogleUser:
    async def test_returns_google_user_with_all_fields(self) -> None:
        mock_response = httpx.Response(
            200,
            json={
                "sub": "google-id-789",
                "email": "user@gmail.com",
                "name": "Test User",
                "picture": "https://lh3.googleusercontent.com/photo.jpg",
            },
            request=httpx.Request("GET", GOOGLE_USERINFO_URL),
        )

        with patch("app.services.google_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            user = await get_google_user("google-access-token")

        assert isinstance(user, GoogleUser)
        assert user.id == "google-id-789"
        assert user.email == "user@gmail.com"
        assert user.name == "Test User"
        assert user.picture == "https://lh3.googleusercontent.com/photo.jpg"

        # Verify auth header
        call_kwargs = mock_client.get.call_args
        assert call_kwargs.kwargs["headers"]["Authorization"] == "Bearer google-access-token"

    async def test_returns_user_with_optional_fields_missing(self) -> None:
        mock_response = httpx.Response(
            200,
            json={"sub": "minimal-id"},
            request=httpx.Request("GET", GOOGLE_USERINFO_URL),
        )

        with patch("app.services.google_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            user = await get_google_user("token")

        assert user.id == "minimal-id"
        assert user.email is None
        assert user.name is None
        assert user.picture is None

    async def test_raises_on_unauthorized(self) -> None:
        mock_response = httpx.Response(
            401,
            json={"error": "invalid_token"},
            request=httpx.Request("GET", GOOGLE_USERINFO_URL),
        )

        with patch("app.services.google_oauth.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_client.get.return_value = mock_response
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_client

            with pytest.raises(httpx.HTTPStatusError):
                await get_google_user("expired-token")
