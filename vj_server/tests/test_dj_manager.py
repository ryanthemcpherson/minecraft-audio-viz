"""Tests for DJManagerMixin — pure logic functions (no WebSocket)."""

import json
from unittest.mock import AsyncMock

import pytest
from websockets.exceptions import ConnectionClosed, ConnectionClosedError
from websockets.frames import Close

from vj_server.models import ConnectCode, DJConnection

# ============================================================================
# DJManagerMixin helpers that can be tested without a full server
# ============================================================================


def test_connection_closed_exception_is_available_at_runtime() -> None:
    """Abrupt DJ disconnects must resolve the runtime exception handler."""
    from vj_server import dj_manager

    assert dj_manager.ConnectionClosed is ConnectionClosed


class DisconnectingDJSocket:
    """Complete the no-auth handshake, then drop the live frame stream."""

    remote_address = ("127.0.0.1", 43210)

    def __init__(self) -> None:
        self._received = [
            json.dumps(
                {
                    "type": "dj_auth",
                    "dj_id": "disconnect-dj",
                    "dj_key": "unused-no-auth",
                    "dj_name": "Disconnect DJ",
                }
            ),
            json.dumps(
                {
                    "type": "clock_sync_response",
                    "dj_recv_time": 100.0,
                    "dj_send_time": 100.0,
                }
            ),
        ]
        self.sent: list[str] = []

    async def recv(self) -> str:
        return self._received.pop(0)

    async def send(self, message: str) -> None:
        self.sent.append(message)

    async def close(self, code: int, reason: str) -> None:
        raise AssertionError(f"unexpected close {code}: {reason}")

    def __aiter__(self):
        return self

    async def __anext__(self):
        raise ConnectionClosedError(Close(1011, "transport lost"), None, None)


@pytest.mark.asyncio
async def test_handler_cleans_up_after_installed_connection_closed(caplog) -> None:
    """A real stream disconnect must clean up without masking it with NameError."""
    from vj_server.vj_server import VJServer

    server = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)
    websocket = DisconnectingDJSocket()

    await server._handle_dj_connection(websocket)

    assert "disconnect-dj" not in server._djs
    assert server._active_dj_id is None
    assert server._dj_disconnects == 1
    assert "connection closed: code=1011, reason=transport lost" in caplog.text
    assert "NameError" not in caplog.text


@pytest.mark.asyncio
async def test_handler_logs_unrelated_connection_errors(caplog) -> None:
    """The specific disconnect catch must not hide unrelated handler failures."""
    from vj_server.vj_server import VJServer

    server = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)
    websocket = AsyncMock()
    websocket.recv.side_effect = RuntimeError("unexpected auth transport failure")

    await server._handle_dj_connection(websocket)

    assert "DJ connection error: unexpected auth transport failure" in caplog.text


class FakeDJManager:
    """Minimal stub exposing DJManagerMixin methods for testing.

    Only includes the attributes that the testable methods reference.
    """

    def __init__(self):
        self._djs = {}
        self._active_dj_id = None
        self._dj_queue = []
        self._dj_presets = {}
        self._connect_codes = {}
        self._auth_attempts = {}
        self._auth_last_cleanup = 0.0
        self._auth_rate_limit_window = 60.0
        self._auth_rate_limit_max = 10

    # Import methods from the mixin
    from vj_server.dj_manager import DJManagerMixin

    active_dj = property(DJManagerMixin.active_dj.fget)
    _get_active_dj = DJManagerMixin._get_active_dj
    _dj_profile_dict = DJManagerMixin._dj_profile_dict
    _get_dj_roster = DJManagerMixin._get_dj_roster
    _check_auth_rate_limit = DJManagerMixin._check_auth_rate_limit
    _cleanup_expired_codes = DJManagerMixin._cleanup_expired_codes


def test_stream_route_is_relay_only_and_omits_pattern_scripts() -> None:
    from vj_server.vj_server import VJServer

    server = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)
    dj = DJConnection(
        dj_id="dj-1",
        dj_name="Containment DJ",
        websocket=None,
        direct_mode=True,
    )
    server._djs[dj.dj_id] = dj
    server._active_dj_id = dj.dj_id

    route = server._build_stream_route_message(dj.dj_id, dj)

    assert route["route_mode"] == "relay"
    assert route["reason"] == "phase0_remote_execution_disabled"
    assert "pattern_scripts" not in route
    assert "minecraft_host" not in route
    assert "minecraft_port" not in route


@pytest.mark.asyncio
async def test_connect_code_approval_identifies_relay_route_immediately() -> None:
    from vj_server.vj_server import VJServer

    server = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)
    websocket = AsyncMock()
    server._pending_djs["dj-code"] = {
        "dj_name": "Connect Code DJ",
        "websocket": websocket,
        "priority": 10,
        "direct_mode": False,
    }
    server._active_dj_id = "existing-active-dj"
    server._broadcast_dj_roster = AsyncMock()
    server._broadcast_stream_routes = AsyncMock()
    server._broadcast_to_browsers = AsyncMock()

    await server._approve_pending_dj("dj-code")

    auth_success = json.loads(websocket.send.await_args.args[0])
    assert auth_success["type"] == "auth_success"
    assert auth_success["route_mode"] == "relay"


# ============================================================================
# active_dj property
# ============================================================================


class TestActiveDJ:
    def setup_method(self):
        self.mgr = FakeDJManager()

    def test_no_active_dj(self):
        assert self.mgr.active_dj is None

    def test_active_dj_exists(self):
        dj = DJConnection(dj_id="dj1", dj_name="DJ One", websocket=None)
        self.mgr._djs["dj1"] = dj
        self.mgr._active_dj_id = "dj1"
        assert self.mgr.active_dj is dj

    def test_active_dj_id_stale(self):
        """If active_dj_id points to a disconnected DJ, return None."""
        self.mgr._active_dj_id = "gone"
        assert self.mgr.active_dj is None


# ============================================================================
# _dj_profile_dict
# ============================================================================


class TestDJProfileDict:
    def test_basic_profile(self):
        mgr = FakeDJManager()
        dj = DJConnection(
            dj_id="dj1",
            dj_name="Cool DJ",
            websocket=None,
            avatar_url="https://example.com/avatar.png",
            slug="cool-dj",
            bio="I play music",
            genres="EDM, House",
            color_palette=["#ff0000", "#00ff00"],
            block_palette=["DIAMOND_BLOCK"],
        )
        result = mgr._dj_profile_dict(dj)
        assert result["dj_id"] == "dj1"
        assert result["dj_name"] == "Cool DJ"
        assert result["avatar_url"] == "https://example.com/avatar.png"
        assert result["slug"] == "cool-dj"
        assert result["color_palette"] == ["#ff0000", "#00ff00"]

    def test_profile_with_none_fields(self):
        mgr = FakeDJManager()
        dj = DJConnection(dj_id="dj1", dj_name="DJ", websocket=None)
        result = mgr._dj_profile_dict(dj)
        assert result["avatar_url"] is None
        assert result["slug"] is None


# ============================================================================
# _get_dj_roster
# ============================================================================


class TestGetDJRoster:
    def setup_method(self):
        self.mgr = FakeDJManager()

    def test_empty_roster(self):
        assert self.mgr._get_dj_roster() == []

    def test_single_dj(self):
        dj = DJConnection(dj_id="dj1", dj_name="DJ One", websocket=None, bpm=128.0)
        self.mgr._djs["dj1"] = dj
        self.mgr._dj_queue = ["dj1"]
        self.mgr._active_dj_id = "dj1"

        roster = self.mgr._get_dj_roster()
        assert len(roster) == 1
        assert roster[0]["dj_id"] == "dj1"
        assert roster[0]["is_active"] is True
        assert roster[0]["bpm"] == 128.0

    def test_roster_sorted_by_queue_position(self):
        for i in range(3):
            dj = DJConnection(dj_id=f"dj{i}", dj_name=f"DJ {i}", websocket=None)
            self.mgr._djs[f"dj{i}"] = dj
        self.mgr._dj_queue = ["dj2", "dj0", "dj1"]

        roster = self.mgr._get_dj_roster()
        assert [r["dj_id"] for r in roster] == ["dj2", "dj0", "dj1"]

    def test_inactive_dj_marked(self):
        dj = DJConnection(dj_id="dj1", dj_name="DJ One", websocket=None)
        self.mgr._djs["dj1"] = dj
        self.mgr._dj_queue = ["dj1"]
        self.mgr._active_dj_id = "dj_other"

        roster = self.mgr._get_dj_roster()
        assert roster[0]["is_active"] is False


# ============================================================================
# _check_auth_rate_limit
# ============================================================================


class TestAuthRateLimit:
    def setup_method(self):
        self.mgr = FakeDJManager()
        self.mgr._auth_rate_limit_max = 3
        self.mgr._auth_rate_limit_window = 60.0

    def test_allows_under_limit(self):
        assert self.mgr._check_auth_rate_limit("1.2.3.4") is False
        assert self.mgr._check_auth_rate_limit("1.2.3.4") is False

    def test_blocks_at_limit(self):
        for _ in range(3):
            self.mgr._check_auth_rate_limit("1.2.3.4")
        assert self.mgr._check_auth_rate_limit("1.2.3.4") is True

    def test_different_ips_independent(self):
        for _ in range(3):
            self.mgr._check_auth_rate_limit("1.1.1.1")
        # Different IP should not be limited
        assert self.mgr._check_auth_rate_limit("2.2.2.2") is False

    def test_cleanup_stale_entries(self):
        self.mgr._auth_attempts = {f"10.0.0.{i}": [0.0] for i in range(60)}
        self.mgr._auth_last_cleanup = 0.0
        # Trigger cleanup by exceeding 50 entries
        self.mgr._check_auth_rate_limit("new_ip")
        # Stale entries should be cleaned
        assert len(self.mgr._auth_attempts) < 60


# ============================================================================
# _cleanup_expired_codes
# ============================================================================


class TestCleanupExpiredCodes:
    def setup_method(self):
        self.mgr = FakeDJManager()

    def test_removes_expired(self):
        expired = ConnectCode(code="TEST-AAAA", created_at=0.0, expires_at=1.0)
        self.mgr._connect_codes["TEST-AAAA"] = expired
        self.mgr._cleanup_expired_codes()
        assert "TEST-AAAA" not in self.mgr._connect_codes

    def test_removes_used(self):
        used = ConnectCode.generate()
        used.used = True
        self.mgr._connect_codes[used.code] = used
        self.mgr._cleanup_expired_codes()
        assert used.code not in self.mgr._connect_codes

    def test_keeps_valid(self):
        valid = ConnectCode.generate()
        self.mgr._connect_codes[valid.code] = valid
        self.mgr._cleanup_expired_codes()
        assert valid.code in self.mgr._connect_codes
