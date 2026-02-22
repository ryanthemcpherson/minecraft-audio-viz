"""Coordinator API client for VJ server registration and show management.

Enables connect code resolution through the central coordinator at api.mcav.live.
Uses only stdlib (no extra dependencies) via urllib + asyncio.to_thread.
"""

from __future__ import annotations

import asyncio
import logging
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import msgspec.json as mjson

logger = logging.getLogger(__name__)

# Persistent state file for server registration
_STATE_DIR = Path.home() / ".config" / "audioviz"
_STATE_FILE = _STATE_DIR / "coordinator_state.json"


@dataclass
class CoordinatorState:
    """Persisted coordinator registration state."""

    server_id: Optional[str] = None
    jwt_secret: Optional[str] = None

    def save(self) -> None:
        _STATE_DIR.mkdir(parents=True, exist_ok=True)
        _STATE_FILE.write_bytes(
            mjson.encode({"server_id": self.server_id, "jwt_secret": self.jwt_secret})
        )

    @classmethod
    def load(cls) -> "CoordinatorState":
        if _STATE_FILE.exists():
            try:
                data = mjson.decode(_STATE_FILE.read_bytes())
                return cls(
                    server_id=data.get("server_id"),
                    jwt_secret=data.get("jwt_secret"),
                )
            except Exception:
                pass
        return cls()


@dataclass
class ShowInfo:
    """Info returned after creating a show on the coordinator."""

    show_id: str
    connect_code: str
    name: str


class CoordinatorClient:
    """Async client for the MCAV coordinator API."""

    def __init__(self, base_url: str, api_key: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._state = CoordinatorState.load()

    @property
    def server_id(self) -> Optional[str]:
        return self._state.server_id

    @property
    def is_registered(self) -> bool:
        return self._state.server_id is not None

    # ------------------------------------------------------------------
    # Low-level HTTP (runs urllib in a thread to stay async-friendly)
    # ------------------------------------------------------------------

    def _sync_request(
        self,
        method: str,
        path: str,
        body: Optional[dict] = None,
        auth: bool = True,
    ) -> dict:
        url = f"{self._base_url}{path}"
        data = mjson.encode(body) if body else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("User-Agent", "mcav-vj-server/1.0")
        if auth:
            req.add_header("Authorization", f"Bearer {self._api_key}")
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:  # nosec B310
                return mjson.decode(resp.read())
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            try:
                detail = mjson.decode(detail).get("detail", detail)
            except Exception:
                pass
            raise RuntimeError(
                f"Coordinator {method} {path} returned {exc.code}: {detail}"
            ) from exc

    async def _request(
        self,
        method: str,
        path: str,
        body: Optional[dict] = None,
        auth: bool = True,
    ) -> dict:
        return await asyncio.to_thread(self._sync_request, method, path, body, auth)

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    async def register(self, name: str, websocket_url: str) -> None:
        """Register this VJ server with the coordinator (idempotent)."""
        if self.is_registered:
            logger.info(
                "Already registered with coordinator (server_id=%s)",
                self._state.server_id,
            )
            # Send a heartbeat to confirm we're still active
            try:
                await self.heartbeat()
            except Exception as exc:
                logger.warning("Heartbeat failed, re-registering: %s", exc)
                self._state = CoordinatorState()
            else:
                return

        logger.info("Registering with coordinator at %s ...", self._base_url)
        result = await self._request(
            "POST",
            "/servers/register",
            body={
                "name": name,
                "websocket_url": websocket_url,
                "api_key": self._api_key,
            },
            auth=False,  # Registration doesn't use Bearer auth
        )
        self._state.server_id = result["server_id"]
        self._state.jwt_secret = result.get("jwt_secret")
        self._state.save()
        logger.info("Registered with coordinator: server_id=%s", self._state.server_id)

    # ------------------------------------------------------------------
    # Heartbeat
    # ------------------------------------------------------------------

    async def heartbeat(self) -> None:
        """Send a heartbeat to keep the server marked as active."""
        if not self._state.server_id:
            return
        await self._request(
            "PUT",
            f"/servers/{self._state.server_id}/heartbeat",
        )

    # ------------------------------------------------------------------
    # Show management
    # ------------------------------------------------------------------

    async def create_show(self, name: str = "Live Show", max_djs: int = 8) -> ShowInfo:
        """Create a show on the coordinator and return the connect code."""
        if not self._state.server_id:
            raise RuntimeError("Not registered with coordinator")

        result = await self._request(
            "POST",
            "/shows",
            body={
                "server_id": self._state.server_id,
                "name": name,
                "max_djs": max_djs,
            },
        )
        return ShowInfo(
            show_id=result["show_id"],
            connect_code=result["connect_code"],
            name=result["name"],
        )

    async def fetch_dj_profile(self, dj_session_id: str) -> Optional[dict]:
        """Fetch DJ profile for a session from the coordinator.

        Returns profile dict or None if unavailable.
        """
        try:
            return await self._request(
                "GET",
                f"/internal/dj-profile/{dj_session_id}",
            )
        except Exception as exc:
            logger.warning(
                "Failed to fetch DJ profile for session %s: %s",
                dj_session_id,
                exc,
            )
            return None

    async def end_show(self, show_id: str) -> None:
        """End a show on the coordinator."""
        try:
            await self._request("DELETE", f"/shows/{show_id}")
        except Exception as exc:
            logger.warning("Failed to end show %s on coordinator: %s", show_id, exc)
