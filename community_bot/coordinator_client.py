"""HTTP client for the MCAV Coordinator API's Discord webhook endpoints."""

from __future__ import annotations

import logging

import aiohttp

_logger = logging.getLogger(__name__)


class CoordinatorClient:
    """Async client for coordinator webhook endpoints.

    Uses aiohttp with lazy session creation. All requests include the
    X-Webhook-Secret header for authentication.
    """

    def __init__(self, *, base_url: str, webhook_secret: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._webhook_secret = webhook_secret
        self._session: aiohttp.ClientSession | None = None

    def _get_session(self) -> aiohttp.ClientSession:
        """Return existing session or create one lazily."""
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                headers={"X-Webhook-Secret": self._webhook_secret},
            )
        return self._session

    async def close(self) -> None:
        """Close the underlying HTTP session."""
        if self._session is not None and not self._session.closed:
            await self._session.close()
            self._session = None

    async def get_user_by_discord_id(self, discord_id: str) -> dict | None:
        """Fetch a user by their Discord ID.

        Returns the user dict (user_id, discord_id, discord_username, roles)
        or None if the user is not found (404).
        """
        session = self._get_session()
        url = f"{self._base_url}/webhooks/discord/users/{discord_id}"

        async with session.get(url) as resp:
            if resp.status == 404:
                return None
            resp.raise_for_status()
            return await resp.json()

    async def sync_roles(self, discord_id: str, roles: list[str]) -> dict | None:
        """Push Discord roles for a user to the coordinator (union merge).

        Returns the UserRolesResponse dict, or None if the user is not found (404).
        """
        session = self._get_session()
        url = f"{self._base_url}/webhooks/discord/role-sync"
        payload = {"discord_id": discord_id, "roles": roles}

        async with session.post(url, json=payload) as resp:
            if resp.status == 404:
                return None
            resp.raise_for_status()
            return await resp.json()

    async def remove_role(self, discord_id: str, role: str) -> dict | None:
        """Remove a specific role from a user.

        Returns the updated UserRolesResponse dict, or None if the user
        or role is not found (404).
        """
        session = self._get_session()
        url = f"{self._base_url}/webhooks/discord/role-sync/{discord_id}/{role}"

        async with session.delete(url) as resp:
            if resp.status == 404:
                return None
            resp.raise_for_status()
            return await resp.json()
