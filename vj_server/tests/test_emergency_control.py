"""Real browser relay behavior for server-authoritative emergency controls."""

import asyncio
import json
from pathlib import Path
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
        self._emergency_mutation_lock = asyncio.Lock()
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


class VoiceStatusRendererClient:
    connected = True

    def __init__(self, response=None, error: Exception | None = None):
        self.response = response
        self.error = error

    async def send(self, message: dict):
        assert message == {"type": "get_voice_status"}
        if self.error:
            raise self.error
        return self.response


class VoiceConfigRendererClient:
    connected = True

    def __init__(self, response=None, error: Exception | None = None):
        self.response = response
        self.error = error

    async def send_voice_config(self, config: dict):
        assert config["type"] == "voice_config"
        assert config["enabled"] is True
        if self.error:
            raise self.error
        return self.response


class InterleavedVisibilityRendererClient:
    connected = True

    def __init__(self):
        self.visible = True
        self.calls: list[bool] = []
        self.first_started = asyncio.Event()

    async def set_visible(self, zone: str, visible: bool):
        assert zone == "main"
        self.calls.append(visible)
        if len(self.calls) == 1:
            self.first_started.set()
            await asyncio.sleep(0.05)
        self.visible = visible
        return True


class BlockingVisibilityRendererClient:
    connected = True

    def __init__(self):
        self.visible = True
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def set_visible(self, zone: str, visible: bool):
        assert zone == "main"
        self.started.set()
        await self.release.wait()
        self.visible = visible
        return True


class FailingVisibilityRendererClient:
    connected = True

    async def set_visible(self, zone: str, visible: bool):
        raise RuntimeError("renderer-token=do-not-leak")


VOICE_STATUS_SCHEMA = json.loads(
    (Path(__file__).parents[2] / "protocol/schemas/messages/voice-status.schema.json").read_text()
)


def assert_voice_status_schema(message: dict) -> None:
    properties = VOICE_STATUS_SCHEMA["properties"]
    assert set(message) <= set(properties)
    assert set(VOICE_STATUS_SCHEMA["required"]) <= set(message)
    for name, value in message.items():
        rules = properties[name]
        if "const" in rules:
            assert value == rules["const"]
        if rules.get("type") == "string":
            assert isinstance(value, str)
            assert len(value) >= rules.get("minLength", 0)
            assert len(value) <= rules.get("maxLength", float("inf"))
        elif rules.get("type") == "boolean":
            assert type(value) is bool
        elif rules.get("type") == "integer":
            assert type(value) is int
            assert value >= rules.get("minimum", float("-inf"))
            assert value <= rules.get("maximum", float("inf"))
        if "enum" in rules:
            assert value in rules["enum"]


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
async def test_voice_status_is_rebuilt_from_allowed_renderer_fields():
    relay = EmergencyRelay()
    relay.viz_client = VoiceStatusRendererClient(
        {
            "type": "voice_status",
            "available": True,
            "streaming": False,
            "channel_type": "locational",
            "connected_players": 12,
            "buffer_size": 4,
            "distance": 96.0,
            "zone": "main",
            "_seq": 77,
            "renderer_secret": "must-not-cross-boundary",
        }
    )
    websocket = BrowserSocket([{"type": "get_voice_status"}])

    await relay._handle_browser_client(websocket)

    assert websocket.sent[-1] == {
        "type": "voice_status",
        "available": True,
        "streaming": False,
        "channel_type": "locational",
        "connected_players": 12,
    }
    assert_voice_status_schema(websocket.sent[-1])


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("response", "exception", "expected_error"),
    [
        (
            {"type": "error", "message": "renderer-token=do-not-leak", "_seq": 1},
            None,
            "Minecraft rejected the voice status request.",
        ),
        ({"type": "zone", "available": True}, None, "Minecraft returned an invalid voice status."),
        (
            {"type": "voice_status", "available": "yes", "streaming": False},
            None,
            "Minecraft returned an invalid voice status.",
        ),
        (None, None, "Minecraft voice status timed out."),
        (
            None,
            RuntimeError("renderer-token=do-not-leak"),
            "Minecraft voice status request failed.",
        ),
    ],
)
async def test_voice_status_failures_return_bounded_schema_valid_errors(
    response, exception, expected_error
):
    relay = EmergencyRelay()
    relay.viz_client = VoiceStatusRendererClient(response, exception)
    websocket = BrowserSocket([{"type": "get_voice_status"}])

    await relay._handle_browser_client(websocket)

    result = websocket.sent[-1]
    assert result == {
        "type": "voice_status",
        "available": False,
        "streaming": False,
        "channel_type": "static",
        "connected_players": 0,
        "error": expected_error,
    }
    assert "renderer-token" not in json.dumps(result)
    assert_voice_status_schema(result)


@pytest.mark.asyncio
async def test_voice_config_status_is_rebuilt_and_private_renderer_fields_are_stripped():
    relay = EmergencyRelay()
    relay.viz_client = VoiceConfigRendererClient(
        {
            "type": "voice_status",
            "available": True,
            "streaming": True,
            "channel_type": "locational",
            "connected_players": 7,
            "buffer_size": 4096,
            "distance": 64.0,
            "zone": "main",
            "_seq": 91,
            "renderer_secret": "must-not-cross-boundary",
        }
    )
    websocket = BrowserSocket([{"type": "voice_config", "enabled": True}])

    await relay._handle_browser_client(websocket)

    result = websocket.sent[-1]
    assert result == {
        "type": "voice_status",
        "available": True,
        "streaming": True,
        "channel_type": "locational",
        "connected_players": 7,
    }
    assert "renderer_secret" not in json.dumps(result)
    assert_voice_status_schema(result)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("response", "exception", "expected_error"),
    [
        (
            {"type": "error", "message": "renderer-token=do-not-leak", "_seq": 1},
            None,
            "Minecraft rejected the voice status request.",
        ),
        (
            {"type": "voice_status", "available": "yes", "buffer_size": 4096},
            None,
            "Minecraft returned an invalid voice status.",
        ),
        ("not-a-status", None, "Minecraft returned an invalid voice status."),
        (None, None, "Minecraft voice status timed out."),
        (
            None,
            RuntimeError("renderer-token=do-not-leak"),
            "Minecraft voice configuration failed.",
        ),
    ],
)
async def test_voice_config_failures_return_bounded_schema_valid_status(
    response, exception, expected_error
):
    relay = EmergencyRelay()
    relay.viz_client = VoiceConfigRendererClient(response, exception)
    websocket = BrowserSocket([{"type": "voice_config", "enabled": True}])

    await relay._handle_browser_client(websocket)

    result = websocket.sent[-1]
    assert result == {
        "type": "voice_status",
        "available": False,
        "streaming": False,
        "channel_type": "static",
        "connected_players": 0,
        "error": expected_error,
    }
    assert "renderer-token" not in json.dumps(result)
    assert_voice_status_schema(result)


@pytest.mark.asyncio
async def test_voice_config_without_renderer_returns_bounded_schema_valid_status():
    relay = EmergencyRelay()
    websocket = BrowserSocket([{"type": "voice_config", "enabled": True}])

    await relay._handle_browser_client(websocket)

    result = websocket.sent[-1]
    assert result == {
        "type": "voice_status",
        "available": False,
        "streaming": False,
        "channel_type": "static",
        "connected_players": 0,
        "error": "Minecraft voice service is unavailable.",
    }
    assert_voice_status_schema(result)


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


@pytest.mark.asyncio
async def test_interleaved_blackout_renderer_calls_finish_at_authoritative_visibility():
    relay = EmergencyRelay()
    renderer = InterleavedVisibilityRendererClient()
    relay.viz_client = renderer
    unused_block = asyncio.Event()
    unused_release = asyncio.Event()
    first = ConcurrentBrowserSocket(50011, -1, unused_block, unused_release)
    second = ConcurrentBrowserSocket(50012, -1, unused_block, unused_release)
    first_task = asyncio.create_task(relay._handle_browser_client(first))
    second_task = asyncio.create_task(relay._handle_browser_client(second))

    try:
        await first.wait_for_message(lambda message: message["type"] == "vj_state")
        await second.wait_for_message(lambda message: message["type"] == "vj_state")
        await first.send_from_browser(
            {
                "type": "trigger_effect",
                "effect": "blackout",
                "intensity": 1.0,
                "request_id": "interleaved-blackout-on",
            }
        )
        await asyncio.wait_for(renderer.first_started.wait(), timeout=1)
        await second.send_from_browser(
            {
                "type": "set_blackout",
                "enabled": False,
                "request_id": "interleaved-blackout-off",
            }
        )

        await first.wait_for_message(lambda message: message.get("emergency_revision") == 2)
        await second.wait_for_message(lambda message: message.get("emergency_revision") == 2)
    finally:
        await first.close_input()
        await second.close_input()
        await asyncio.gather(first_task, second_task)

    assert renderer.calls == [False, True]
    assert renderer.visible is True
    assert relay._blackout is False
    assert relay._emergency_revision == 2
    assert any(message.get("effect") == "blackout" for message in first.sent)


@pytest.mark.asyncio
async def test_pending_blackout_keeps_snapshot_and_freeze_at_visible_renderer_state():
    relay = EmergencyRelay()
    renderer = BlockingVisibilityRendererClient()
    relay.viz_client = renderer
    unused_block = asyncio.Event()
    unused_release = asyncio.Event()
    first = ConcurrentBrowserSocket(50021, -1, unused_block, unused_release)
    second = ConcurrentBrowserSocket(50022, -1, unused_block, unused_release)
    first_task = asyncio.create_task(relay._handle_browser_client(first))
    second_task = asyncio.create_task(relay._handle_browser_client(second))

    try:
        await first.wait_for_message(lambda message: message["type"] == "vj_state")
        await second.wait_for_message(lambda message: message["type"] == "vj_state")
        await first.send_from_browser(
            {
                "type": "set_blackout",
                "enabled": True,
                "request_id": "blocking-blackout",
            }
        )
        await asyncio.wait_for(renderer.started.wait(), timeout=1)
        await second.send_from_browser(
            {
                "type": "set_freeze",
                "enabled": True,
                "request_id": "blocked-freeze",
            }
        )
        await asyncio.sleep(0)

        observer = BrowserSocket([{"type": "get_state"}])
        await relay._handle_browser_client(observer)
        snapshots = [message for message in observer.sent if message["type"] == "vj_state"]
        assert snapshots[-1]["blackout"] is False
        assert snapshots[-1]["freeze"] is False
        assert snapshots[-1]["emergency_revision"] == 0
        assert renderer.visible is True

        renderer.release.set()
        await first.wait_for_message(lambda message: message.get("emergency_revision") == 2)
        await second.wait_for_message(lambda message: message.get("emergency_revision") == 2)
    finally:
        renderer.release.set()
        await first.close_input()
        await second.close_input()
        await asyncio.gather(first_task, second_task)

    assert renderer.visible is False
    assert relay._blackout is True
    assert relay._freeze is True
    assert relay._emergency_revision == 2


@pytest.mark.asyncio
async def test_failed_renderer_blackout_does_not_publish_or_commit_authority():
    relay = EmergencyRelay()
    relay.viz_client = FailingVisibilityRendererClient()
    websocket = BrowserSocket(
        [
            {
                "type": "trigger_effect",
                "effect": "blackout",
                "intensity": 1.0,
                "request_id": "failed-blackout",
            }
        ]
    )

    await relay._handle_browser_client(websocket)

    assert relay._blackout is False
    assert relay._emergency_revision == 0
    assert "blackout" not in relay._active_effects
    assert not any(message["type"] == "emergency_state" for message in websocket.sent)
    assert not any(message["type"] == "effect_triggered" for message in websocket.sent)
    assert websocket.sent[-1] == {
        "type": "error",
        "message": "Minecraft did not apply the emergency control change.",
        "request_id": "failed-blackout",
    }
    assert "renderer-token" not in json.dumps(websocket.sent[-1])


def test_server_processes_receive_distinct_stable_emergency_epochs():
    first = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)
    second = VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)

    assert isinstance(first._emergency_epoch, str)
    assert len(first._emergency_epoch) >= 16
    assert first._emergency_epoch != second._emergency_epoch
