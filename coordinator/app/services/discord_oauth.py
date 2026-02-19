"""Discord OAuth2 authorization code flow."""

from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import urlencode

import httpx

DISCORD_API = "https://discord.com/api/v10"
DISCORD_AUTHORIZE_URL = "https://discord.com/oauth2/authorize"
DISCORD_TOKEN_URL = f"{DISCORD_API}/oauth2/token"
DISCORD_USER_URL = f"{DISCORD_API}/users/@me"

SCOPES = "identify email"


@dataclass(frozen=True)
class DiscordUser:
    """Relevant fields from Discord's ``/users/@me`` response."""

    id: str
    username: str
    email: str | None
    avatar: str | None


def get_authorize_url(
    *, client_id: str, redirect_uri: str, state: str
) -> str:
    """Build the Discord OAuth2 authorization URL."""
    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPES,
        "state": state,
    }
    return f"{DISCORD_AUTHORIZE_URL}?{urlencode(params)}"


async def exchange_code(
    *,
    code: str,
    client_id: str,
    client_secret: str,
    redirect_uri: str,
) -> str:
    """Exchange an authorization code for an access token.

    Returns the Discord access token string.
    """
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            DISCORD_TOKEN_URL,
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
                "client_id": client_id,
                "client_secret": client_secret,
            },
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        resp.raise_for_status()
        return resp.json()["access_token"]


async def get_discord_user(access_token: str) -> DiscordUser:
    """Fetch the authenticated user's Discord profile."""
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            DISCORD_USER_URL,
            headers={"Authorization": f"Bearer {access_token}"},
        )
        resp.raise_for_status()
        data = resp.json()

    return DiscordUser(
        id=data["id"],
        username=data["username"],
        email=data.get("email"),
        avatar=data.get("avatar"),
    )
