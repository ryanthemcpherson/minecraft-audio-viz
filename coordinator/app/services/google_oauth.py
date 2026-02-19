"""Google OAuth2 authorization code flow."""

from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import urlencode

import httpx

GOOGLE_AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"  # nosec B105 — not a password
GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"

SCOPES = "openid email profile"


@dataclass(frozen=True)
class GoogleUser:
    """Relevant fields from Google's userinfo response."""

    id: str
    email: str | None
    name: str | None
    picture: str | None


def get_authorize_url(*, client_id: str, redirect_uri: str, state: str) -> str:
    """Build the Google OAuth2 authorization URL."""
    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPES,
        "state": state,
        "access_type": "offline",
        "prompt": "consent",
    }
    return f"{GOOGLE_AUTHORIZE_URL}?{urlencode(params)}"


async def exchange_code(
    *,
    code: str,
    client_id: str,
    client_secret: str,
    redirect_uri: str,
) -> str:
    """Exchange an authorization code for an access token.

    Returns the Google access token string.
    """
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            GOOGLE_TOKEN_URL,
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


async def get_google_user(access_token: str) -> GoogleUser:
    """Fetch the authenticated user's Google profile."""
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            GOOGLE_USERINFO_URL,
            headers={"Authorization": f"Bearer {access_token}"},
        )
        resp.raise_for_status()
        data = resp.json()

    return GoogleUser(
        id=data["sub"],
        email=data.get("email"),
        name=data.get("name"),
        picture=data.get("picture"),
    )
