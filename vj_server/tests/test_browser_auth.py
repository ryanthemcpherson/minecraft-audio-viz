"""Focused browser authentication boundary tests."""

import msgspec.json as mjson
import pytest

from vj_server.auth import hash_password
from vj_server.models import DJAuthConfig, _json_str
from vj_server.relay import RelayMixin


class AuthWebSocket:
    def __init__(self, auth_message: dict, *, remote_ip: str = "127.0.0.1"):
        self._auth_message = _json_str(auth_message)
        self._auth_received = False
        self.remote_address = (remote_ip, 12345)
        self.sent: list[str] = []
        self.close_code: int | None = None

    async def recv(self):
        if self._auth_received:
            raise RuntimeError("authentication requested more than once")
        self._auth_received = True
        return self._auth_message

    async def send(self, message):
        self.sent.append(message)

    async def close(self, code=1000, reason=""):
        self.close_code = code


class AuthRelay(RelayMixin):
    def __init__(self):
        self.require_auth = True
        self.auth_config = DJAuthConfig(
            vj_operators={
                "lighting": {"key_hash": hash_password("lighting-secret")},
                "video": {"key_hash": hash_password("video-secret")},
            }
        )
        self._browser_auth_attempts: dict[str, list[float]] = {}
        self._browser_auth_rate_limit_max = 5
        self._browser_auth_rate_limit_window = 60.0


@pytest.mark.asyncio
async def test_username_must_match_password_owner():
    relay = AuthRelay()
    websocket = AuthWebSocket(
        {
            "type": "vj_auth",
            "username": "lighting",
            "password": "video-secret",
        }
    )

    await relay._handle_browser_client(websocket)

    assert [mjson.decode(message) for message in websocket.sent] == [
        {"type": "auth_error", "error": "Invalid username or password"}
    ]
    assert websocket.close_code == 4004


@pytest.mark.asyncio
async def test_username_is_required():
    relay = AuthRelay()
    websocket = AuthWebSocket(
        {
            "type": "vj_auth",
            "password": "lighting-secret",
        }
    )

    await relay._handle_browser_client(websocket)

    assert [mjson.decode(message) for message in websocket.sent] == [
        {"type": "auth_error", "error": "Invalid username or password"}
    ]
    assert websocket.close_code == 4004


@pytest.mark.asyncio
async def test_sixth_failed_login_from_ip_is_rate_limited():
    relay = AuthRelay()
    sockets = [
        AuthWebSocket(
            {
                "type": "vj_auth",
                "username": "lighting",
                "password": "wrong-secret",
            },
            remote_ip="203.0.113.7",
        )
        for _ in range(6)
    ]

    for websocket in sockets:
        await relay._handle_browser_client(websocket)

    assert [websocket.close_code for websocket in sockets[:5]] == [4004] * 5
    assert sockets[5].close_code == 4008
    assert mjson.decode(sockets[5].sent[0]) == {
        "type": "auth_error",
        "error": "Invalid username or password",
    }
