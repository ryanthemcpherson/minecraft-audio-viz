"""Real browser relay behavior for server-authoritative emergency controls."""

import asyncio
from types import SimpleNamespace

import msgspec.json as mjson
import pytest

from vj_server.models import _json_str
from vj_server.relay import RelayMixin
from vj_server.vj_server import VJServer


class BrowserSocket:
    def __init__(self, messages: list[dict]):
        self.messages = [_json_str(message) for message in messages]
        self.sent: list[dict] = []
        self.remote_address = ("127.0.0.1", 50000)

    def __aiter__(self):
        return self._messages()

    async def _messages(self):
        for message in self.messages:
            yield message

    async def send(self, message: str):
        self.sent.append(mjson.decode(message))


_CLOSE_SOCKET = object()


class ConcurrentBrowserSocket:
    def __init__(
        self,
        remote_port: int,
        blocked_revision: int,
        revision_blocked: asyncio.Event,
        release_revision: asyncio.Event,
    ):
        self.incoming: asyncio.Queue[str | object] = asyncio.Queue()
        self.sent: list[dict] = []
        self.message_sent = asyncio.Event()
        self.remote_address = ("127.0.0.1", remote_port)
        self.blocked_revision = blocked_revision
        self.revision_blocked = revision_blocked
        self.release_revision = release_revision

    def __aiter__(self):
        return self

    async def __anext__(self):
        message = await self.incoming.get()
        if message is _CLOSE_SOCKET:
            raise StopAsyncIteration
        return message

    async def send(self, message: str):
        decoded = mjson.decode(message)
        if (
            decoded.get("type") == "emergency_state"
            and decoded.get("emergency_revision") == self.blocked_revision
        ):
            self.revision_blocked.set()
            await self.release_revision.wait()
        self.sent.append(decoded)
        self.message_sent.set()

    async def send_from_browser(self, message: dict):
        await self.incoming.put(_json_str(message))

    async def close_input(self):
        await self.incoming.put(_CLOSE_SOCKET)

    async def wait_for_message(self, predicate):
        while not any(predicate(message) for message in self.sent):
            self.message_sent.clear()
            if any(predicate(message) for message in self.sent):
                break
            await asyncio.wait_for(self.message_sent.wait(), timeout=1)


class EmergencyRelay(RelayMixin):
    def __init__(self):
        self.require_auth = False
        self._broadcast_clients = set()
        self._voice_subscribers = set()
        self._browser_pong_pending = {}
        self._browser_last_pong = {}
        self._browser_connects = 0
        self._browser_disconnects = 0
        self._pending_djs = {}
        self._djs = {}
        self._active_dj_id = None
        self.viz_client = None
        self.zone = "main"
        self.entity_count = 16
        self._pattern_name = "spectrum"
        self._blackout = False
        self._freeze = False
        self._emergency_epoch = "test-emergency-epoch"
        self._emergency_revision = 0
        self._active_effects = {}
        self._band_materials = []
        self._band_materials_source = "default"
        self._visual_delay_ms = 0
        self._visual_delay_mode = "manual"
        self._beat_predictor = SimpleNamespace(
            tempo_confidence=0,
            tempo_bpm=0,
            is_phase_locked=False,
        )
        self._dj_banner_profiles = {}
        self._bloom_enabled = False
        self._bloom_strength = 0
        self._bloom_threshold = 0
        self._ambient_lights_enabled = False

    def _get_all_patterns_list(self):
        return ["spectrum"]

    def _get_zone_patterns_dict(self):
        return {"main": "spectrum"}

    def _get_dj_roster(self):
        return []

    def get_health_stats(self):
        return {}

    def _get_bitmap_zones_dict(self):
        return {}


@pytest.mark.asyncio
async def test_initial_get_state_and_trigger_ack_are_authoritative_and_correlated():
    relay = EmergencyRelay()
    websocket = BrowserSocket(
        [
            {"type": "get_state"},
            {
                "type": "trigger_effect",
                "effect": "blackout",
                "intensity": 1.0,
                "request_id": "emergency-blackout-1",
            },
        ]
    )

    await relay._handle_browser_client(websocket)

    snapshots = [message for message in websocket.sent if message["type"] == "vj_state"]
    assert len(snapshots) == 2
    assert all(snapshot["blackout"] is False for snapshot in snapshots)
    assert all(snapshot["freeze"] is False for snapshot in snapshots)
    assert [snapshot["emergency_revision"] for snapshot in snapshots] == [0, 0]
    assert [snapshot["emergency_epoch"] for snapshot in snapshots] == [
        "test-emergency-epoch",
        "test-emergency-epoch",
    ]
    assert {message["type"] for message in websocket.sent} >= {
        "effect_triggered",
        "emergency_state",
    }
    assert {
        key: value
        for key, value in websocket.sent[-1].items()
        if key
        in {
            "type",
            "blackout",
            "freeze",
            "request_id",
            "emergency_epoch",
            "emergency_revision",
        }
    } == {
        "type": "emergency_state",
        "blackout": True,
        "freeze": False,
        "request_id": "emergency-blackout-1",
        "emergency_epoch": "test-emergency-epoch",
        "emergency_revision": 1,
    }


@pytest.mark.asyncio
async def test_direct_freeze_broadcasts_current_emergency_state():
    relay = EmergencyRelay()
    websocket = BrowserSocket(
        [{"type": "set_freeze", "enabled": True, "request_id": "emergency-freeze-1"}]
    )

    await relay._handle_browser_client(websocket)

    assert websocket.sent[-1] == {
        "type": "emergency_state",
        "blackout": False,
        "freeze": True,
        "request_id": "emergency-freeze-1",
        "emergency_epoch": "test-emergency-epoch",
        "emergency_revision": 1,
    }


@pytest.mark.asyncio
async def test_rate_limit_error_preserves_request_correlation():
    relay = EmergencyRelay()
    websocket = BrowserSocket(
        [
            {
                "type": "set_blackout",
                "enabled": bool(index % 2),
                "request_id": f"emergency-rate-{index}",
            }
            for index in range(20)
        ]
    )

    await relay._handle_browser_client(websocket)

    errors = [message for message in websocket.sent if message["type"] == "error"]
    assert errors
    assert all(error["request_id"].startswith("emergency-rate-") for error in errors)


@pytest.mark.asyncio
async def test_concurrent_clients_receive_monotonic_revisions_despite_inverse_delivery():
    relay = EmergencyRelay()
    revision_blocked = asyncio.Event()
    release_revision = asyncio.Event()
    first = ConcurrentBrowserSocket(50001, 1, revision_blocked, release_revision)
    second = ConcurrentBrowserSocket(50002, 1, revision_blocked, release_revision)
    first_task = asyncio.create_task(relay._handle_browser_client(first))
    second_task = asyncio.create_task(relay._handle_browser_client(second))

    try:
        await first.wait_for_message(lambda message: message["type"] == "vj_state")
        await second.wait_for_message(lambda message: message["type"] == "vj_state")

        await first.send_from_browser(
            {
                "type": "set_blackout",
                "enabled": True,
                "request_id": "shared-emergency-id",
            }
        )
        await asyncio.wait_for(revision_blocked.wait(), timeout=1)
        await second.send_from_browser(
            {
                "type": "set_blackout",
                "enabled": False,
                "request_id": "shared-emergency-id",
            }
        )
        await first.wait_for_message(lambda message: message.get("emergency_revision") == 2)
        await second.wait_for_message(lambda message: message.get("emergency_revision") == 2)
        assert not any(
            message.get("emergency_revision") == 1 for message in [*first.sent, *second.sent]
        )

        release_revision.set()
        await first.wait_for_message(lambda message: message.get("emergency_revision") == 1)
        await second.wait_for_message(lambda message: message.get("emergency_revision") == 1)

        assert [
            message["emergency_revision"]
            for message in first.sent
            if message["type"] == "emergency_state"
        ] == [2, 1]
        assert [
            message["emergency_revision"]
            for message in second.sent
            if message["type"] == "emergency_state"
        ] == [2, 1]
        first_states = [message for message in first.sent if message["type"] == "emergency_state"]
        second_states = [message for message in second.sent if message["type"] == "emergency_state"]
        assert first_states[0] == {
            "type": "emergency_state",
            "blackout": False,
            "freeze": False,
            "emergency_epoch": "test-emergency-epoch",
            "emergency_revision": 2,
        }
        assert first_states[1]["request_id"] == "shared-emergency-id"
        assert second_states[0]["request_id"] == "shared-emergency-id"
        assert "request_id" not in second_states[1]
        assert relay._emergency_revision == 2
        assert relay._blackout is False
    finally:
        release_revision.set()
        await first.close_input()
        await second.close_input()
        await asyncio.gather(first_task, second_task)


def test_server_processes_receive_distinct_stable_emergency_epochs():
    first = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)
    second = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)

    assert isinstance(first._emergency_epoch, str)
    assert len(first._emergency_epoch) >= 16
    assert first._emergency_epoch != second._emergency_epoch
