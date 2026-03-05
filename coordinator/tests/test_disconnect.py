"""Tests for the DJ disconnect endpoint (POST /disconnect/{session_id})."""

from __future__ import annotations

import uuid

import jwt as pyjwt
from httpx import AsyncClient

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _get_user_token(client: AsyncClient, email: str) -> str:
    resp = await client.post(
        "/api/v1/auth/register",
        json={"email": email, "password": "Testpass123", "display_name": "Disc Tester"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _register_server(
    client: AsyncClient,
    user_token: str,
    name: str,
    websocket_url: str,
    api_key: str,
) -> dict:
    resp = await client.post(
        "/api/v1/servers/register",
        json={"name": name, "websocket_url": websocket_url, "api_key": api_key},
        headers={"Authorization": f"Bearer {user_token}"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _setup_show(
    client: AsyncClient, email: str, api_key: str, port: int, max_djs: int = 4
) -> tuple[str, str, str]:
    """Create a user, server, and show. Return (show_id, connect_code, api_key)."""
    user_token = await _get_user_token(client, email)
    reg = await _register_server(
        client,
        user_token,
        name=f"Disc Server {port}",
        websocket_url=f"ws://localhost:{port}",
        api_key=api_key,
    )
    server_id = reg["server_id"]

    show_resp = await client.post(
        "/api/v1/shows",
        json={"server_id": server_id, "name": "Disc Show", "max_djs": max_djs},
        headers={"Authorization": f"Bearer {api_key}"},
    )
    assert show_resp.status_code == 201
    show_data = show_resp.json()
    return show_data["show_id"], show_data["connect_code"], api_key


def _extract_session_id(token: str) -> str:
    """Decode a DJ JWT (without verification) and return the dj_session_id."""
    decoded = pyjwt.decode(token, options={"verify_signature": False})
    return decoded["sub"]


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestDisconnectIdempotent:
    async def test_disconnect_nonexistent_session_returns_204(self, client: AsyncClient) -> None:
        """Disconnecting a session that doesn't exist should be idempotent (204)."""
        user_token = await _get_user_token(client, "disc1@example.com")
        await _register_server(
            client, user_token, "Disc1 Server", "ws://localhost:9301", "disc1-key"
        )
        fake_id = str(uuid.uuid4())
        resp = await client.post(
            f"/api/v1/disconnect/{fake_id}",
            headers={"Authorization": "Bearer disc1-key"},
        )
        assert resp.status_code == 204

    async def test_double_disconnect_returns_204_both_times(self, client: AsyncClient) -> None:
        """Disconnecting the same session twice should return 204 both times."""
        show_id, connect_code, api_key = await _setup_show(
            client, "disc2@example.com", "disc2-key", 9302
        )

        # Connect a DJ
        resolve_resp = await client.post(f"/api/v1/connect/{connect_code}/join")
        assert resolve_resp.status_code == 200
        session_id = _extract_session_id(resolve_resp.json()["token"])

        # First disconnect
        resp1 = await client.post(
            f"/api/v1/disconnect/{session_id}",
            headers={"Authorization": f"Bearer {api_key}"},
        )
        assert resp1.status_code == 204

        # Second disconnect (already disconnected)
        resp2 = await client.post(
            f"/api/v1/disconnect/{session_id}",
            headers={"Authorization": f"Bearer {api_key}"},
        )
        assert resp2.status_code == 204

    async def test_disconnect_without_auth_returns_422(self, client: AsyncClient) -> None:
        """Disconnecting without server auth should be rejected."""
        fake_id = str(uuid.uuid4())
        resp = await client.post(f"/api/v1/disconnect/{fake_id}")
        assert resp.status_code == 422  # Missing required Authorization header

    async def test_disconnect_wrong_server_returns_403(self, client: AsyncClient) -> None:
        """Disconnecting a session belonging to another server should return 403."""
        show_id, connect_code, api_key = await _setup_show(
            client, "disc5@example.com", "disc5-key", 9305
        )
        # Register a second server
        user_token2 = await _get_user_token(client, "disc5b@example.com")
        await _register_server(
            client, user_token2, "Other Server", "ws://localhost:9306", "other-key"
        )

        # Connect a DJ to the first server's show
        resolve_resp = await client.post(f"/api/v1/connect/{connect_code}/join")
        assert resolve_resp.status_code == 200
        session_id = _extract_session_id(resolve_resp.json()["token"])

        # Try to disconnect using the wrong server's key
        resp = await client.post(
            f"/api/v1/disconnect/{session_id}",
            headers={"Authorization": "Bearer other-key"},
        )
        assert resp.status_code == 403


class TestDisconnectDJCount:
    async def test_disconnect_decrements_current_djs(self, client: AsyncClient) -> None:
        """Disconnecting a DJ should decrement the show's current_djs count."""
        show_id, connect_code, api_key = await _setup_show(
            client, "disc3@example.com", "disc3-key", 9303
        )

        # Connect two DJs
        resp1 = await client.post(f"/api/v1/connect/{connect_code}/join")
        assert resp1.status_code == 200
        session_id_1 = _extract_session_id(resp1.json()["token"])

        resp2 = await client.post(f"/api/v1/connect/{connect_code}/join")
        assert resp2.status_code == 200

        # Verify current_djs is 2
        detail = await client.get(
            f"/api/v1/shows/{show_id}",
            headers={"Authorization": f"Bearer {api_key}"},
        )
        assert detail.json()["current_djs"] == 2

        # Disconnect DJ #1
        disc_resp = await client.post(
            f"/api/v1/disconnect/{session_id_1}",
            headers={"Authorization": f"Bearer {api_key}"},
        )
        assert disc_resp.status_code == 204

        # Verify current_djs decremented to 1
        detail2 = await client.get(
            f"/api/v1/shows/{show_id}",
            headers={"Authorization": f"Bearer {api_key}"},
        )
        assert detail2.json()["current_djs"] == 1

    async def test_disconnect_does_not_go_below_zero(self, client: AsyncClient) -> None:
        """Double-disconnect should not push current_djs below zero."""
        show_id, connect_code, api_key = await _setup_show(
            client, "disc4@example.com", "disc4-key", 9304, max_djs=1
        )

        # Connect one DJ
        resp = await client.post(f"/api/v1/connect/{connect_code}/join")
        assert resp.status_code == 200
        session_id = _extract_session_id(resp.json()["token"])

        # Disconnect once (1 -> 0)
        await client.post(
            f"/api/v1/disconnect/{session_id}",
            headers={"Authorization": f"Bearer {api_key}"},
        )

        # Disconnect again (already disconnected, count should stay at 0)
        await client.post(
            f"/api/v1/disconnect/{session_id}",
            headers={"Authorization": f"Bearer {api_key}"},
        )

        detail = await client.get(
            f"/api/v1/shows/{show_id}",
            headers={"Authorization": f"Bearer {api_key}"},
        )
        assert detail.json()["current_djs"] == 0
