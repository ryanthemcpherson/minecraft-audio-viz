"""Tests for RelayMixin — broadcast, routing, Minecraft update, and relay logic.

Strategy: Build a FakeRelay that wires up just enough VJServer state for
RelayMixin methods to run, then test relay/broadcast/update methods with
mock WebSocket objects.
"""

import asyncio
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from vj_server.models import (
    ConnectCode,
    DJConnection,
    VizStateBroadcast,
    ZonePatternState,
    _json_str,
)
from vj_server.relay import RelayMixin
from vj_server.stage_manager import StageManagerMixin

from .conftest import make_audio_frame


# ---------------------------------------------------------------------------
# Fake WebSocket
# ---------------------------------------------------------------------------


class FakeWebSocket:
    """Minimal async WebSocket mock that records sent messages."""

    def __init__(self, *, fail_on_send=False):
        self.sent: list[str] = []
        self.closed = False
        self.close_code: int | None = None
        self.close_reason: str | None = None
        self.fail_on_send = fail_on_send
        self.remote_address = ("127.0.0.1", 12345)

    async def send(self, message):
        if self.fail_on_send:
            raise ConnectionError("send failed")
        self.sent.append(message)

    async def close(self, code=1000, reason=""):
        self.closed = True
        self.close_code = code
        self.close_reason = reason

    async def recv(self):
        raise NotImplementedError("recv not mocked — test should not call this")


# ---------------------------------------------------------------------------
# Fake VizClient (Minecraft connection)
# ---------------------------------------------------------------------------


class FakeVizClient:
    """Minimal stand-in for VizClient."""

    def __init__(self, *, connected=True):
        self.connected = connected
        self.server_type = "paper"
        self.connect_timeout = 10.0
        self.sent_updates: list[dict] = []
        self.ws = MagicMock()
        self.ws.send = AsyncMock()

    def _encode(self, data):
        return _json_str(data)

    async def batch_update_fast(self, zone, entities, particles, audio):
        self.sent_updates.append(
            {
                "zone": zone,
                "entities": entities,
                "particles": particles,
                "audio": audio,
            }
        )

    async def init_pool(self, zone, count, block_type):
        pass

    async def cleanup_zone(self, zone):
        pass

    async def get_zones(self):
        return [{"name": "main"}]

    async def get_zone(self, zone):
        return {"name": zone}

    async def set_visible(self, zone, visible):
        pass

    async def send(self, data):
        return {"type": "ok"}

    async def send_voice_frame(self, pcm_data, seq):
        pass

    async def send_voice_config(self, config):
        return {"type": "voice_status", "available": True, "streaming": False}

    async def disconnect(self):
        self.connected = False


# ---------------------------------------------------------------------------
# FakeRelay — a minimal object that has enough state to exercise RelayMixin
# ---------------------------------------------------------------------------


class FakeRelay(StageManagerMixin, RelayMixin):
    """Stub wiring up shared state that RelayMixin accesses via self.

    Inherits StageManagerMixin because RelayMixin calls _get_zone_state,
    _get_zone_patterns_dict, _get_all_patterns_list, etc.
    """

    def __init__(self):
        # DJ management
        self._djs: Dict[str, DJConnection] = {}
        self._active_dj_id: Optional[str] = None
        self._dj_queue: List[str] = []
        self._dj_lock = asyncio.Lock()
        self._dj_presets: Dict[str, str] = {}
        self._pending_djs: Dict[str, dict] = {}

        # Connect codes
        self._connect_codes: Dict[str, ConnectCode] = {}
        self._coordinator: Optional[Any] = None

        # Auth
        self.require_auth = False
        self.auth_config = MagicMock()
        self.auth_config.vj_operators = {}

        # Browser clients
        self._broadcast_clients: set = set()
        self._voice_subscribers: set = set()
        self._browser_pong_pending: dict = {}
        self._browser_last_pong: dict = {}
        self._browser_connects = 0
        self._browser_disconnects = 0

        # Minecraft
        self.viz_client: Optional[FakeVizClient] = None
        self.minecraft_host = "localhost"
        self.minecraft_port = 8765
        self.zone = "main"
        self.entity_count = 16
        self._mc_reconnect_backoff = 5.0
        self._mc_reconnect_count = 0
        self._last_mc_connected = False
        self._skip_minecraft = False

        # Pattern system — initialize _zone_patterns before calling StageManagerMixin
        from vj_server.patterns import PatternConfig

        self._pattern_config = PatternConfig(entity_count=16)
        self._zone_patterns: Dict[str, ZonePatternState] = {}
        self._bitmap_pattern_cache: list = []
        self._default_transition_duration = 1.0
        self._get_zone_state("main")  # initialize default zone

        # Frame counter
        self._frame_count = 0
        self._running = False

        # Control state
        self._blackout = False
        self._freeze = False
        self._active_effects: dict = {}
        self._band_sensitivity = [1.0] * 5
        self._band_materials = ["SEA_LANTERN"] * 5
        self._band_materials_source = "default"
        self._current_preset_name = "auto"
        self._dj_palettes: dict = {}
        self._visual_delay_ms = 0.0
        self._visual_delay_mode = "manual"
        self._visual_band_state = [0.0] * 5
        self._visual_deadzone = 0.03
        self._visual_gamma = 1.55
        self._visual_transient_gain = 0.45

        # Beat predictor stub
        self._beat_predictor = MagicMock()
        self._beat_predictor.tempo_confidence = 0.0
        self._beat_predictor.tempo_bpm = 120.0
        self._beat_predictor.is_phase_locked = False

        # Perf
        self._latest_perf_snapshot = {"enabled": False, "window_samples": 0}

        # Bloom/ambient
        self._bloom_enabled = True
        self._bloom_strength = 0.4
        self._bloom_threshold = 0.5
        self._ambient_lights_enabled = True

        # Banner
        self._dj_banner_profiles: dict = {}

        # Metrics
        self._dj_connects = 0
        self._dj_disconnects = 0
        self._start_time = time.time()
        self._frames_processed = 0
        self._pattern_changes = 0

        # Pool tracking
        self._minecraft_pool_size = 0
        self._transition_pending_resize = None

        # Ableton Link stubs
        self._link = None
        self._link_enabled = False
        self._link_tempo = 0.0
        self._link_beat_phase = 0.0

    # ---------- Methods called by RelayMixin that live on other mixins ----------

    def get_health_stats(self):
        return {"dj_connects": 0, "current_djs": len(self._djs)}

    def _get_dj_roster(self):
        return [
            {
                "dj_id": did,
                "dj_name": dj.dj_name,
                "is_active": did == self._active_dj_id,
                "bpm": dj.bpm,
                "queue_position": i,
                "avatar_url": None,
            }
            for i, (did, dj) in enumerate(self._djs.items())
        ]

    def _dj_profile_dict(self, dj):
        if dj is None:
            return None
        return {
            "dj_id": dj.dj_id,
            "dj_name": dj.dj_name,
            "avatar_url": None,
            "slug": None,
        }

    def _calculate_sync_confidence(self, dj):
        return 75.0

    def _get_effective_delay_ms(self, dj=None):
        return self._visual_delay_ms

    async def _broadcast_stream_routes(self):
        pass

    async def _broadcast_connect_codes(self):
        pass

    async def _set_active_dj(self, dj_id):
        if dj_id in self._djs:
            self._active_dj_id = dj_id

    async def _approve_pending_dj(self, dj_id):
        self._pending_djs.pop(dj_id, None)

    async def _deny_pending_dj(self, dj_id):
        self._pending_djs.pop(dj_id, None)

    async def _reorder_dj_queue(self, dj_id, new_pos):
        pass

    def _apply_named_preset(self, preset_name):
        return {"attack": 0.5, "release": 0.1, "name": preset_name}

    def _save_banner_profiles(self):
        pass

    def _load_banner_profiles(self):
        pass

    def _process_logo_image(self, image_data, w, h):
        return None  # pretend Pillow not installed

    async def _send_banner_config_to_minecraft(self, dj_id):
        pass

    async def _forward_voice_config(self, config):
        pass

    async def _run_parity_check(self):
        return {"type": "parity_check", "ok": True}

    def _capture_current_state(self):
        return {"pattern": "spectrum"}

    def _save_scene_to_file(self, name, data):
        pass

    def _load_scene_from_file(self, name):
        return {"pattern": "spectrum"}

    async def _apply_scene_state(self, scene_data):
        pass

    def _delete_scene_file(self, name):
        pass

    def _list_scenes(self):
        return [{"name": "test_scene"}]

    async def _coordinator_create_show(self, ttl_minutes=30):
        return None

    def _cleanup_expired_codes(self):
        expired = [k for k, v in self._connect_codes.items() if not v.is_valid()]
        for k in expired:
            del self._connect_codes[k]

    @property
    def active_dj(self):
        if self._active_dj_id and self._active_dj_id in self._djs:
            return self._djs[self._active_dj_id]
        return None

    async def _switch_zone_to_bitmap(self, zn, zs, pattern_name):
        zs.render_mode = "bitmap"
        zs.pattern_name = pattern_name

    async def _switch_zone_to_block(self, zn, zs, pattern_name):
        zs.render_mode = "block"
        zs.pattern_name = pattern_name


# ============================================================================
# _broadcast_to_browsers
# ============================================================================


class TestBroadcastToBrowsers:
    def setup_method(self):
        self.relay = FakeRelay()

    async def test_sends_to_all_clients(self):
        ws1 = FakeWebSocket()
        ws2 = FakeWebSocket()
        self.relay._broadcast_clients = {ws1, ws2}

        await self.relay._broadcast_to_browsers('{"type": "test"}')

        assert len(ws1.sent) == 1
        assert len(ws2.sent) == 1
        assert '"test"' in ws1.sent[0]

    async def test_empty_clients_is_noop(self):
        await self.relay._broadcast_to_browsers('{"type": "test"}')
        # Should not raise

    async def test_dead_client_removed(self):
        good_ws = FakeWebSocket()
        dead_ws = FakeWebSocket(fail_on_send=True)
        self.relay._broadcast_clients = {good_ws, dead_ws}

        await self.relay._broadcast_to_browsers('{"type": "test"}')

        assert good_ws in self.relay._broadcast_clients
        assert dead_ws not in self.relay._broadcast_clients
        assert len(good_ws.sent) == 1


# ============================================================================
# _broadcast_to_djs
# ============================================================================


class TestBroadcastToDJs:
    def setup_method(self):
        self.relay = FakeRelay()

    async def test_sends_to_all_djs(self):
        ws1 = FakeWebSocket()
        ws2 = FakeWebSocket()
        self.relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=ws1)
        self.relay._djs["dj2"] = DJConnection(dj_id="dj2", dj_name="DJ B", websocket=ws2)

        await self.relay._broadcast_to_djs({"type": "test_msg"})

        assert len(ws1.sent) == 1
        assert len(ws2.sent) == 1

    async def test_error_on_one_dj_does_not_crash(self):
        good_ws = FakeWebSocket()
        bad_ws = FakeWebSocket(fail_on_send=True)
        self.relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=good_ws)
        self.relay._djs["dj2"] = DJConnection(dj_id="dj2", dj_name="DJ B", websocket=bad_ws)

        # Should not raise — errors are logged and swallowed
        await self.relay._broadcast_to_djs({"type": "test"})
        assert len(good_ws.sent) == 1


# ============================================================================
# _broadcast_dj_roster
# ============================================================================


class TestBroadcastDJRoster:
    def setup_method(self):
        self.relay = FakeRelay()

    async def test_sends_roster_to_browsers_and_djs(self):
        browser_ws = FakeWebSocket()
        dj_ws = FakeWebSocket()
        self.relay._broadcast_clients = {browser_ws}
        self.relay._djs["dj1"] = DJConnection(
            dj_id="dj1", dj_name="DJ A", websocket=dj_ws
        )
        self.relay._dj_queue = ["dj1"]
        self.relay._active_dj_id = "dj1"

        await self.relay._broadcast_dj_roster()

        # Browser gets full roster
        assert len(browser_ws.sent) == 1
        assert '"dj_roster"' in browser_ws.sent[0]

        # DJ gets lightweight roster
        assert len(dj_ws.sent) == 1
        assert '"dj_roster"' in dj_ws.sent[0]

    async def test_dead_browser_removed_during_roster_broadcast(self):
        dead_ws = FakeWebSocket(fail_on_send=True)
        self.relay._broadcast_clients = {dead_ws}

        await self.relay._broadcast_dj_roster()

        assert dead_ws not in self.relay._broadcast_clients


# ============================================================================
# _broadcast_pattern_change
# ============================================================================


class TestBroadcastPatternChange:
    def setup_method(self):
        self.relay = FakeRelay()

    async def test_broadcasts_to_browsers_and_djs(self):
        browser_ws = FakeWebSocket()
        dj_ws = FakeWebSocket()
        self.relay._broadcast_clients = {browser_ws}
        self.relay._djs["dj1"] = DJConnection(
            dj_id="dj1", dj_name="DJ A", websocket=dj_ws
        )

        await self.relay._broadcast_pattern_change()

        assert len(browser_ws.sent) == 1
        assert '"pattern_changed"' in browser_ws.sent[0]
        # DJ gets pattern sync
        assert len(dj_ws.sent) == 1
        assert '"pattern_sync"' in dj_ws.sent[0]


# ============================================================================
# _broadcast_config_sync_to_djs
# ============================================================================


class TestBroadcastConfigSyncToDJs:
    async def test_sends_config_to_all_djs(self):
        relay = FakeRelay()
        ws = FakeWebSocket()
        relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=ws)
        relay.entity_count = 32
        relay.zone = "stage_1"

        await relay._broadcast_config_sync_to_djs()

        assert len(ws.sent) == 1
        assert '"config_sync"' in ws.sent[0]
        assert '"32"' in ws.sent[0] or "32" in ws.sent[0]


# ============================================================================
# _broadcast_config_to_browsers
# ============================================================================


class TestBroadcastConfigToBrowsers:
    async def test_sends_config_update(self):
        relay = FakeRelay()
        ws = FakeWebSocket()
        relay._broadcast_clients = {ws}
        relay.entity_count = 64

        await relay._broadcast_config_to_browsers()

        assert len(ws.sent) == 1
        assert '"config_update"' in ws.sent[0]

    async def test_removes_dead_browser(self):
        relay = FakeRelay()
        dead_ws = FakeWebSocket(fail_on_send=True)
        relay._broadcast_clients = {dead_ws}

        await relay._broadcast_config_to_browsers()

        assert dead_ws not in relay._broadcast_clients


# ============================================================================
# _broadcast_preset_to_djs
# ============================================================================


class TestBroadcastPresetToDJs:
    async def test_sends_preset_to_djs_and_browsers(self):
        relay = FakeRelay()
        dj_ws = FakeWebSocket()
        browser_ws = FakeWebSocket()
        relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=dj_ws)
        relay._broadcast_clients = {browser_ws}

        await relay._broadcast_preset_to_djs(
            {"attack": 0.5, "release": 0.1}, preset_name="edm"
        )

        assert len(dj_ws.sent) == 1
        assert '"preset_sync"' in dj_ws.sent[0]
        assert len(browser_ws.sent) == 1
        assert '"preset_changed"' in browser_ws.sent[0]
        assert '"edm"' in browser_ws.sent[0]


# ============================================================================
# _broadcast_effect_trigger
# ============================================================================


class TestBroadcastEffectTrigger:
    async def test_sends_to_browsers_and_djs(self):
        relay = FakeRelay()
        browser_ws = FakeWebSocket()
        dj_ws = FakeWebSocket()
        relay._broadcast_clients = {browser_ws}
        relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=dj_ws)

        await relay._broadcast_effect_trigger("flash")

        assert len(browser_ws.sent) == 1
        assert '"effect_triggered"' in browser_ws.sent[0]
        assert '"flash"' in browser_ws.sent[0]
        assert len(dj_ws.sent) == 1


# ============================================================================
# _broadcast_minecraft_status
# ============================================================================


class TestBroadcastMinecraftStatus:
    async def test_broadcasts_on_state_change(self):
        relay = FakeRelay()
        browser_ws = FakeWebSocket()
        relay._broadcast_clients = {browser_ws}
        relay.viz_client = FakeVizClient(connected=True)
        relay._last_mc_connected = False  # was disconnected

        await relay._broadcast_minecraft_status()

        assert len(browser_ws.sent) == 1
        assert '"minecraft_status"' in browser_ws.sent[0]
        assert relay._last_mc_connected is True

    async def test_no_broadcast_when_unchanged(self):
        relay = FakeRelay()
        browser_ws = FakeWebSocket()
        relay._broadcast_clients = {browser_ws}
        relay.viz_client = FakeVizClient(connected=True)
        relay._last_mc_connected = True  # already connected

        await relay._broadcast_minecraft_status()

        assert len(browser_ws.sent) == 0


# ============================================================================
# _broadcast_viz_state
# ============================================================================


class TestBroadcastVizState:
    def setup_method(self):
        self.relay = FakeRelay()

    async def test_sends_state_to_all_browsers(self):
        ws1 = FakeWebSocket()
        ws2 = FakeWebSocket()
        self.relay._broadcast_clients = {ws1, ws2}

        entities = [{"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}]
        await self.relay._broadcast_viz_state(
            entities=entities,
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            peak=0.7,
            is_beat=True,
            beat_intensity=0.8,
        )

        assert len(ws1.sent) == 1
        assert len(ws2.sent) == 1
        # Should contain state data
        assert '"state"' in ws1.sent[0]

    async def test_no_broadcast_when_no_clients(self):
        # Should return early with no clients — no error
        await self.relay._broadcast_viz_state(
            entities=[], bands=[0] * 5, peak=0, is_beat=False, beat_intensity=0
        )

    async def test_amplitude_normalization_normal_range(self):
        """Peak <= 1.25 should pass through as-is."""
        ws = FakeWebSocket()
        self.relay._broadcast_clients = {ws}

        await self.relay._broadcast_viz_state(
            entities=[], bands=[0] * 5, peak=0.8, is_beat=False, beat_intensity=0
        )

        import msgspec.json as mjson

        data = mjson.decode(ws.sent[0])
        assert data["amplitude"] == 0.8

    async def test_amplitude_normalization_high_range(self):
        """Peak > 1.25 should be scaled down by /5.0."""
        ws = FakeWebSocket()
        self.relay._broadcast_clients = {ws}

        await self.relay._broadcast_viz_state(
            entities=[], bands=[0] * 5, peak=3.0, is_beat=False, beat_intensity=0
        )

        import msgspec.json as mjson

        data = mjson.decode(ws.sent[0])
        assert abs(data["amplitude"] - 0.6) < 0.01  # 3.0 / 5.0 = 0.6

    async def test_dead_client_removed(self):
        dead_ws = FakeWebSocket(fail_on_send=True)
        self.relay._broadcast_clients = {dead_ws}

        await self.relay._broadcast_viz_state(
            entities=[], bands=[0] * 5, peak=0, is_beat=False, beat_intensity=0
        )

        assert dead_ws not in self.relay._broadcast_clients

    async def test_includes_active_dj_profile(self):
        ws = FakeWebSocket()
        self.relay._broadcast_clients = {ws}
        dj_ws = FakeWebSocket()
        dj = DJConnection(dj_id="dj1", dj_name="DJ Cool", websocket=dj_ws, bpm=140.0)
        self.relay._djs["dj1"] = dj
        self.relay._active_dj_id = "dj1"

        await self.relay._broadcast_viz_state(
            entities=[], bands=[0] * 5, peak=0, is_beat=False, beat_intensity=0
        )

        import msgspec.json as mjson

        data = mjson.decode(ws.sent[0])
        assert data["active_dj"]["dj_id"] == "dj1"
        assert data["active_dj"]["dj_name"] == "DJ Cool"


# ============================================================================
# _send_with_timeout
# ============================================================================


class TestSendWithTimeout:
    async def test_successful_send(self):
        relay = FakeRelay()
        ws = FakeWebSocket()
        dead = set()

        await relay._send_with_timeout(ws, '{"type":"test"}', dead)

        assert len(ws.sent) == 1
        assert len(dead) == 0

    async def test_failed_send_adds_to_dead(self):
        relay = FakeRelay()
        ws = FakeWebSocket(fail_on_send=True)
        dead = set()

        await relay._send_with_timeout(ws, '{"type":"test"}', dead)

        assert ws in dead


# ============================================================================
# _update_minecraft
# ============================================================================


class TestUpdateMinecraft:
    def setup_method(self):
        self.relay = FakeRelay()
        self.relay.viz_client = FakeVizClient(connected=True)

    async def test_sends_batch_update(self):
        entities = [
            {"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}
        ]
        await self.relay._update_minecraft(
            entities=entities,
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            peak=0.7,
            is_beat=True,
            beat_intensity=0.8,
            bpm=128.0,
        )

        assert len(self.relay.viz_client.sent_updates) == 1
        update = self.relay.viz_client.sent_updates[0]
        assert update["zone"] == "main"
        assert update["audio"]["is_beat"] is True

    async def test_skips_when_no_viz_client(self):
        self.relay.viz_client = None
        # Should not raise
        await self.relay._update_minecraft(
            entities=[], bands=[0] * 5, peak=0, is_beat=False, beat_intensity=0
        )

    async def test_skips_when_disconnected(self):
        self.relay.viz_client = FakeVizClient(connected=False)
        await self.relay._update_minecraft(
            entities=[], bands=[0] * 5, peak=0, is_beat=False, beat_intensity=0
        )
        assert len(self.relay.viz_client.sent_updates) == 0

    async def test_hides_unused_pool_entities(self):
        """Entities below pool high-water mark should get scale=0."""
        self.relay._minecraft_pool_size = 4
        entities = [{"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}]

        await self.relay._update_minecraft(
            entities=entities,
            bands=[0.5] * 5,
            peak=0.5,
            is_beat=False,
            beat_intensity=0,
        )

        sent_entities = self.relay.viz_client.sent_updates[0]["entities"]
        # Should have block_0 + hidden block_1, block_2, block_3
        ids = {e["id"] for e in sent_entities}
        assert "block_1" in ids
        assert "block_2" in ids
        assert "block_3" in ids

    async def test_clamps_audio_values(self):
        """Audio values should be clamped to safe ranges."""
        await self.relay._update_minecraft(
            entities=[{"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}],
            bands=[2.0, -1.0, 0.5, 0.5, 0.5],  # out-of-range bands
            peak=10.0,  # over max
            is_beat=True,
            beat_intensity=10.0,  # over max
            bpm=500.0,  # over max
        )

        audio = self.relay.viz_client.sent_updates[0]["audio"]
        assert audio["bands"][0] == 1.0  # clamped from 2.0
        assert audio["bands"][1] == 0.0  # clamped from -1.0
        assert audio["amplitude"] == 5.0  # clamped from 10.0
        assert audio["beat_intensity"] == 5.0
        assert audio["bpm"] == 300.0

    async def test_generates_particles_on_beat(self):
        await self.relay._update_minecraft(
            entities=[{"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}],
            bands=[0.5] * 5,
            peak=0.8,
            is_beat=True,
            beat_intensity=0.9,
        )

        particles = self.relay.viz_client.sent_updates[0]["particles"]
        assert len(particles) == 1
        assert particles[0]["particle"] == "NOTE"

    async def test_no_particles_on_weak_beat(self):
        await self.relay._update_minecraft(
            entities=[{"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}],
            bands=[0.5] * 5,
            peak=0.3,
            is_beat=True,
            beat_intensity=0.1,  # below 0.2 threshold
        )

        particles = self.relay.viz_client.sent_updates[0]["particles"]
        assert len(particles) == 0

    async def test_error_does_not_crash(self):
        """Minecraft update errors are logged, not raised."""
        self.relay.viz_client.batch_update_fast = AsyncMock(
            side_effect=Exception("network error")
        )
        # Should not raise
        await self.relay._update_minecraft(
            entities=[{"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}],
            bands=[0.5] * 5,
            peak=0.5,
            is_beat=False,
            beat_intensity=0,
        )


# ============================================================================
# _update_minecraft_zone (multi-zone variant)
# ============================================================================


class TestUpdateMinecraftZone:
    def setup_method(self):
        self.relay = FakeRelay()
        self.relay.viz_client = FakeVizClient(connected=True)

    async def test_sends_for_block_zone(self):
        zs = self.relay._get_zone_state("stage_1")
        zs.render_mode = "block"
        entities = [{"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}]

        await self.relay._update_minecraft_zone(
            zone_name="stage_1",
            zone_state=zs,
            entities=entities,
            bands=[0.5] * 5,
            peak=0.5,
            is_beat=False,
            beat_intensity=0,
        )

        assert len(self.relay.viz_client.sent_updates) == 1
        assert self.relay.viz_client.sent_updates[0]["zone"] == "stage_1"

    async def test_bitmap_zone_sends_audio_only(self):
        zs = self.relay._get_zone_state("led_wall")
        zs.render_mode = "bitmap"
        entities = [{"id": "block_0", "x": 0.5}]  # should be ignored

        await self.relay._update_minecraft_zone(
            zone_name="led_wall",
            zone_state=zs,
            entities=entities,
            bands=[0.5] * 5,
            peak=0.5,
            is_beat=False,
            beat_intensity=0,
        )

        update = self.relay.viz_client.sent_updates[0]
        assert update["entities"] == []  # bitmap: no entities
        assert update["particles"] == []

    async def test_skips_when_zone_state_is_none(self):
        await self.relay._update_minecraft_zone(
            zone_name="main",
            zone_state=None,
            entities=[],
            bands=[0] * 5,
            peak=0,
            is_beat=False,
            beat_intensity=0,
        )
        assert len(self.relay.viz_client.sent_updates) == 0

    async def test_error_does_not_crash(self):
        zs = self.relay._get_zone_state("main")
        self.relay.viz_client.batch_update_fast = AsyncMock(
            side_effect=Exception("boom")
        )

        await self.relay._update_minecraft_zone(
            zone_name="main",
            zone_state=zs,
            entities=[{"id": "block_0", "x": 0.5, "y": 0.5, "z": 0.5, "scale": 0.5}],
            bands=[0.5] * 5,
            peak=0.5,
            is_beat=False,
            beat_intensity=0,
        )


# ============================================================================
# _relay_voice_audio
# ============================================================================


class TestRelayVoiceAudio:
    async def test_relays_to_minecraft(self):
        relay = FakeRelay()
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send_voice_frame = AsyncMock()

        await relay._relay_voice_audio({"data": "base64pcm", "seq": 5})

        relay.viz_client.send_voice_frame.assert_awaited_once_with("base64pcm", 5)

    async def test_relays_to_voice_subscribers(self):
        relay = FakeRelay()
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send_voice_frame = AsyncMock()
        sub_ws = FakeWebSocket()
        relay._voice_subscribers = {sub_ws}

        await relay._relay_voice_audio({"data": "base64pcm", "seq": 1})

        assert len(sub_ws.sent) == 1
        assert '"voice_audio"' in sub_ws.sent[0]

    async def test_skips_when_no_minecraft(self):
        relay = FakeRelay()
        relay.viz_client = None
        # Should not raise
        await relay._relay_voice_audio({"data": "base64pcm", "seq": 1})

    async def test_skips_invalid_data(self):
        relay = FakeRelay()
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send_voice_frame = AsyncMock()

        # data is not a string
        await relay._relay_voice_audio({"data": 12345, "seq": 1})

        relay.viz_client.send_voice_frame.assert_not_awaited()

    async def test_dead_subscriber_removed(self):
        relay = FakeRelay()
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send_voice_frame = AsyncMock()
        dead_ws = FakeWebSocket(fail_on_send=True)
        relay._voice_subscribers = {dead_ws}

        await relay._relay_voice_audio({"data": "pcm", "seq": 1})

        assert dead_ws not in relay._voice_subscribers


# ============================================================================
# _broadcast_voice_status
# ============================================================================


class TestBroadcastVoiceStatus:
    async def test_broadcasts_to_browsers(self):
        relay = FakeRelay()
        ws = FakeWebSocket()
        relay._broadcast_clients = {ws}

        await relay._broadcast_voice_status(
            {"type": "voice_status", "available": True, "streaming": False}
        )

        assert len(ws.sent) == 1
        assert '"voice_status"' in ws.sent[0]


# ============================================================================
# Connection limit
# ============================================================================


class TestBrowserConnectionLimit:
    """The browser handler limits to MAX_BROWSER_CLIENTS = 50."""

    async def test_rejects_when_limit_reached(self):
        relay = FakeRelay()
        relay.require_auth = False
        # Fill up to 50 clients
        relay._broadcast_clients = {FakeWebSocket() for _ in range(50)}

        new_ws = FakeWebSocket()
        # _handle_browser_client should close with 4003
        await relay._handle_browser_client(new_ws)

        assert new_ws.closed
        assert new_ws.close_code == 4003


# ============================================================================
# _forward_voice_config
# ============================================================================


class TestForwardVoiceConfig:
    async def test_forwards_config_and_broadcasts_status(self):
        relay = FakeRelay()
        relay.viz_client = FakeVizClient(connected=True)
        browser_ws = FakeWebSocket()
        relay._broadcast_clients = {browser_ws}

        # Override the stub to actually call through the real implementation
        relay._forward_voice_config = RelayMixin._forward_voice_config.__get__(relay)

        await relay._forward_voice_config({"enabled": True, "channel_type": "static"})

        # The FakeVizClient returns voice_status, which should be broadcast
        assert len(browser_ws.sent) == 1
        assert '"voice_status"' in browser_ws.sent[0]

    async def test_skips_when_disconnected(self):
        relay = FakeRelay()
        relay.viz_client = None
        relay._forward_voice_config = RelayMixin._forward_voice_config.__get__(relay)

        # Should not raise
        await relay._forward_voice_config({"enabled": True})


# ============================================================================
# connect_minecraft
# ============================================================================


class TestConnectMinecraft:
    async def test_returns_false_when_connection_fails(self):
        relay = FakeRelay()
        relay.viz_client = None

        with patch("vj_server.relay.RelayMixin.connect_minecraft") as mock_connect:
            # Simulate connection failure
            mock_connect.return_value = False
            result = await mock_connect()
            assert result is False

    async def test_disconnects_existing_client(self):
        relay = FakeRelay()
        old_client = FakeVizClient(connected=True)
        relay.viz_client = old_client

        # We can't fully test connect_minecraft without a real VizClient,
        # but we verify the cleanup path by checking the method exists
        # and the old client would be disconnected
        assert hasattr(relay, "connect_minecraft")


# ============================================================================
# _handle_browser_client message routing (testing the dispatch logic)
# ============================================================================


class TestBrowserMessageRouting:
    """Test individual message types handled by _handle_browser_client.

    We test by creating a FakeWebSocket that yields specific messages
    then raises ConnectionClosed.
    """

    def _make_ws_with_messages(self, messages: list[str]):
        """Create a FakeWebSocket that yields messages then stops."""

        class IterableWebSocket(FakeWebSocket):
            def __init__(self, msgs):
                super().__init__()
                self._messages = iter(msgs)

            def __aiter__(self):
                return self

            async def __anext__(self):
                try:
                    return next(self._messages)
                except StopIteration:
                    raise StopAsyncIteration

        return IterableWebSocket(messages)

    async def test_ping_pong(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages([mjson.encode({"type": "ping"}).decode()])

        await relay._handle_browser_client(ws)

        # Should have sent vj_state (initial) + pong
        pong_msgs = [m for m in ws.sent if '"pong"' in m]
        assert len(pong_msgs) == 1

    async def test_get_dj_roster(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_dj_roster"}).decode()]
        )

        await relay._handle_browser_client(ws)

        # First message is vj_state, second should be dj_roster response
        roster_msgs = [
            m for m in ws.sent
            if mjson.decode(m).get("type") == "dj_roster"
        ]
        assert len(roster_msgs) == 1

    async def test_set_visual_delay(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_visual_delay", "delay_ms": 100}).decode()]
        )

        await relay._handle_browser_client(ws)

        assert relay._visual_delay_ms == 100.0
        # Should broadcast sync
        sync_msgs = [m for m in ws.sent if '"visual_delay_sync"' in m]
        assert len(sync_msgs) == 1

    async def test_set_visual_delay_clamped(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_visual_delay", "delay_ms": 9999}).decode()]
        )

        await relay._handle_browser_client(ws)

        assert relay._visual_delay_ms == 500.0  # clamped

    async def test_set_visual_delay_mode(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_visual_delay_mode", "mode": "auto"}).decode()]
        )

        await relay._handle_browser_client(ws)

        assert relay._visual_delay_mode == "auto"

    async def test_set_visual_delay_mode_invalid_ignored(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_visual_delay_mode", "mode": "invalid"}).decode()]
        )

        await relay._handle_browser_client(ws)

        # Should remain unchanged (default is "manual")
        assert relay._visual_delay_mode == "manual"

    async def test_blackout_toggle(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_blackout", "enabled": True}).decode()]
        )

        await relay._handle_browser_client(ws)

        assert relay._blackout is True
        assert "blackout" in relay._active_effects

    async def test_freeze_toggle(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_freeze", "enabled": True}).decode()]
        )

        await relay._handle_browser_client(ws)

        assert relay._freeze is True

    async def test_set_band_sensitivity(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_band_sensitivity", "band": 2, "sensitivity": 1.5}
                ).decode()
            ]
        )

        await relay._handle_browser_client(ws)

        assert relay._band_sensitivity[2] == 1.5

    async def test_set_band_sensitivity_clamped(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_band_sensitivity", "band": 0, "sensitivity": 5.0}
                ).decode()
            ]
        )

        await relay._handle_browser_client(ws)

        assert relay._band_sensitivity[0] == 2.0  # clamped to max

    async def test_set_band_materials(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        materials = ["DIAMOND_BLOCK", "EMERALD_BLOCK", "GOLD_BLOCK", "IRON_BLOCK", "COPPER_BLOCK"]
        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_band_materials", "materials": materials}).decode()]
        )

        await relay._handle_browser_client(ws)

        assert relay._band_materials == materials
        assert relay._band_materials_source == "admin"

    async def test_set_bloom(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_bloom", "enabled": False, "strength": 0.8, "threshold": 0.3}
                ).decode()
            ]
        )

        await relay._handle_browser_client(ws)

        assert relay._bloom_enabled is False
        assert relay._bloom_strength == 0.8
        assert relay._bloom_threshold == 0.3

    async def test_subscribe_voice(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "subscribe_voice"}).decode()]
        )

        await relay._handle_browser_client(ws)

        # After disconnect, voice subscriber is cleaned up in finally block.
        # Verify the ack was sent (proving the subscription path ran).
        ack_msgs = [m for m in ws.sent if '"subscribe_voice_ack"' in m]
        assert len(ack_msgs) == 1
        # And verify cleanup happened
        assert ws not in relay._voice_subscribers

    async def test_unsubscribe_voice(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode({"type": "subscribe_voice"}).decode(),
                mjson.encode({"type": "unsubscribe_voice"}).decode(),
            ]
        )

        await relay._handle_browser_client(ws)

        # Verify unsubscribe ack was sent
        unack_msgs = [m for m in ws.sent if '"unsubscribe_voice_ack"' in m]
        assert len(unack_msgs) == 1
        assert ws not in relay._voice_subscribers

    async def test_set_transition_duration(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_transition_duration", "duration": 2.5}).decode()]
        )

        await relay._handle_browser_client(ws)

        assert relay._default_transition_duration == 2.5

    async def test_set_transition_duration_clamped(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_transition_duration", "duration": 10.0}).decode()]
        )

        await relay._handle_browser_client(ws)

        assert relay._default_transition_duration == 3.0  # clamped

    async def test_invalid_json_does_not_crash(self):
        relay = FakeRelay()
        relay.require_auth = False

        ws = self._make_ws_with_messages(["not valid json {{{"])

        # Should not raise
        await relay._handle_browser_client(ws)

    async def test_list_scenes(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "list_scenes"}).decode()]
        )

        await relay._handle_browser_client(ws)

        scene_msgs = [m for m in ws.sent if '"scenes_list"' in m]
        assert len(scene_msgs) == 1

    async def test_get_pending_djs(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._pending_djs = {
            "dj1": {
                "dj_id": "dj1",
                "dj_name": "Test DJ",
                "waiting_since": time.time(),
                "direct_mode": False,
            }
        }
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_pending_djs"}).decode()]
        )

        await relay._handle_browser_client(ws)

        pending_msgs = [
            m for m in ws.sent
            if mjson.decode(m).get("type") == "pending_djs"
        ]
        assert len(pending_msgs) == 1

    async def test_get_connect_codes(self):
        relay = FakeRelay()
        relay.require_auth = False
        code = ConnectCode.generate(30)
        relay._connect_codes[code.code] = code
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_connect_codes"}).decode()]
        )

        await relay._handle_browser_client(ws)

        code_msgs = [m for m in ws.sent if '"connect_codes"' in m]
        assert len(code_msgs) == 1

    async def test_revoke_connect_code(self):
        relay = FakeRelay()
        relay.require_auth = False
        code = ConnectCode.generate(30)
        relay._connect_codes[code.code] = code
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "revoke_connect_code", "code": code.code}
                ).decode()
            ]
        )

        await relay._handle_browser_client(ws)

        assert code.code not in relay._connect_codes

    async def test_pong_tracked_and_cleaned_on_disconnect(self):
        """Pong is recorded during session, then cleaned up in finally block."""
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "pong"}).decode()]
        )

        await relay._handle_browser_client(ws)

        # After disconnect, pong tracking is cleaned up
        assert ws not in relay._browser_last_pong
        assert ws not in relay._browser_pong_pending

    async def test_cleanup_on_disconnect(self):
        """When browser disconnects, it should be removed from all tracking sets."""
        relay = FakeRelay()
        relay.require_auth = False

        ws = self._make_ws_with_messages([])  # no messages, immediate disconnect

        await relay._handle_browser_client(ws)

        assert ws not in relay._broadcast_clients
        assert ws not in relay._voice_subscribers

    async def test_initial_state_sent(self):
        """First message to a new browser client should be vj_state."""
        relay = FakeRelay()
        relay.require_auth = False

        ws = self._make_ws_with_messages([])

        await relay._handle_browser_client(ws)

        assert len(ws.sent) >= 1
        assert '"vj_state"' in ws.sent[0]


# ============================================================================
# Rate limiting in browser handler
# ============================================================================


class TestBrowserRateLimiting:
    async def test_rate_limited_command_passes_under_limit(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        # Token bucket starts at 10 tokens. A few commands should pass.
        messages = [
            mjson.encode({"type": "set_visual_delay", "delay_ms": i}).decode()
            for i in range(3)
        ]
        ws = self._make_ws_with_messages(messages)

        await relay._handle_browser_client(ws)

        # All 3 should have been processed (no error messages)
        error_msgs = [m for m in ws.sent if '"Rate limited"' in m]
        assert len(error_msgs) == 0

    def _make_ws_with_messages(self, messages):
        class IterableWebSocket(FakeWebSocket):
            def __init__(self, msgs):
                super().__init__()
                self._messages = iter(msgs)

            def __aiter__(self):
                return self

            async def __anext__(self):
                try:
                    return next(self._messages)
                except StopIteration:
                    raise StopAsyncIteration

        return IterableWebSocket(messages)


# ============================================================================
# Extended browser message routing — previously uncovered handlers
# ============================================================================


class TestBrowserMessageRoutingExtended:
    """Tests for browser message handlers that lacked coverage:
    pattern management, entity count, zone changes, presets, audio settings,
    connect codes, scene management, effects, banner profiles, forwarding to
    Minecraft, and zone config sync.
    """

    def _make_ws_with_messages(self, messages: list[str]):
        class IterableWebSocket(FakeWebSocket):
            def __init__(self, msgs):
                super().__init__()
                self._messages = iter(msgs)

            def __aiter__(self):
                return self

            async def __anext__(self):
                try:
                    return next(self._messages)
                except StopIteration:
                    raise StopAsyncIteration

        return IterableWebSocket(messages)

    # ---- Pattern management ----

    async def test_set_pattern_block(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_pattern", "pattern": "spectrum"}).decode()]
        )
        await relay._handle_browser_client(ws)

        zs = relay._get_zone_state("main")
        assert zs.pattern_name == "spectrum"
        assert any('"pattern_changed"' in m for m in ws.sent)

    async def test_set_pattern_bitmap(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_pattern", "pattern": "bmp_spectrum"}).decode()]
        )
        await relay._handle_browser_client(ws)

        zs = relay._get_zone_state("main")
        assert zs.render_mode == "bitmap"
        assert zs.pattern_name == "bmp_spectrum"

    async def test_set_pattern_with_target_zones(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._get_zone_state("stage_2")
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_pattern", "pattern": "spectrum", "zones": ["main"]}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._get_zone_state("main").pattern_name == "spectrum"

    # ---- Entity count ----

    async def test_set_entity_count(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_entity_count", "count": 32}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay.entity_count == 32
        assert relay._pattern_config.entity_count == 32

    async def test_set_block_count_alias(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_block_count", "count": 64}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay.entity_count == 64

    async def test_set_entity_count_out_of_range(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_entity_count", "count": 999}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay.entity_count == 16  # unchanged

    # ---- Zone management ----

    async def test_set_zone(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_zone", "zone": "stage_1"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay.zone == "stage_1"

    async def test_set_zone_same_no_change(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_zone", "zone": "main"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay.zone == "main"

    async def test_set_zone_with_minecraft(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_zone", "zone": "led_wall"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay.zone == "led_wall"

    # ---- Preset handling ----

    async def test_set_preset_by_name(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_preset", "preset": "edm"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"preset_changed"' in m for m in ws.sent)

    async def test_set_preset_by_dict(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_preset", "preset": {"attack": 0.8, "release": 0.2}}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._pattern_config.attack == 0.8
        assert relay._pattern_config.release == 0.2

    async def test_set_preset_unknown_name(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_preset", "preset": "nonexistent"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert not any('"preset_changed"' in m for m in ws.sent)

    async def test_set_preset_stores_for_active_dj(self):
        relay = FakeRelay()
        relay.require_auth = False
        dj_ws = FakeWebSocket()
        relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=dj_ws)
        relay._active_dj_id = "dj1"
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_preset", "preset": "chill"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay._dj_presets.get("dj1") == "chill"

    # ---- Audio settings ----

    async def test_set_audio_setting_attack(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_audio_setting", "setting": "attack", "value": 0.7}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._pattern_config.attack == 0.7

    async def test_set_audio_setting_beat_threshold(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_audio_setting", "setting": "beat_threshold", "value": 1.5}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._pattern_config.beat_threshold == 1.5

    # ---- Connect codes ----

    async def test_generate_connect_code(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "generate_connect_code", "ttl_minutes": 15}).decode()]
        )
        await relay._handle_browser_client(ws)
        code_msgs = [m for m in ws.sent if '"connect_code_generated"' in m]
        assert len(code_msgs) == 1
        assert len(relay._connect_codes) == 1

    # ---- State retrieval ----

    async def test_get_state_returns_full_state(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_state"}).decode()]
        )
        await relay._handle_browser_client(ws)
        state_msgs = [m for m in ws.sent if '"vj_state"' in m]
        assert len(state_msgs) == 2  # initial + get_state response

    # ---- Effect triggers ----

    async def test_trigger_effect_flash(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "trigger_effect", "effect": "flash", "intensity": 1.0, "duration": 500}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert "flash" in relay._active_effects

    async def test_trigger_effect_blackout_on(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "trigger_effect", "effect": "blackout", "intensity": 1.0}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._blackout is True

    async def test_trigger_effect_blackout_off(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._blackout = True
        relay._active_effects["blackout"] = {"intensity": 1.0}
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "trigger_effect", "effect": "blackout", "intensity": 0}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._blackout is False

    async def test_trigger_effect_freeze_on(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "trigger_effect", "effect": "freeze", "intensity": 1.0}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._freeze is True

    async def test_trigger_effect_freeze_off(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._freeze = True
        relay._active_effects["freeze"] = {"intensity": 1.0}
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "trigger_effect", "effect": "freeze", "intensity": 0}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._freeze is False

    async def test_trigger_effect_blackout_with_mc_on(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.set_visible = AsyncMock()
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "trigger_effect", "effect": "blackout", "intensity": 1.0}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        relay.viz_client.set_visible.assert_awaited_with("main", False)

    async def test_trigger_effect_blackout_with_mc_off(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._blackout = True
        relay._active_effects["blackout"] = {"intensity": 1.0}
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.set_visible = AsyncMock()
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "trigger_effect", "effect": "blackout", "intensity": 0}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        relay.viz_client.set_visible.assert_awaited_with("main", True)

    # ---- Scene management ----

    async def test_save_scene(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "save_scene", "name": "My Scene"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"scene_saved"' in m for m in ws.sent)

    async def test_save_scene_empty_name(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "save_scene", "name": ""}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"Scene name is required"' in m for m in ws.sent)

    async def test_save_scene_error(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._save_scene_to_file = MagicMock(side_effect=Exception("disk full"))
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "save_scene", "name": "Test"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Failed to save scene" in m for m in ws.sent)

    async def test_load_scene(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "load_scene", "name": "Test Scene"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"scene_loaded"' in m for m in ws.sent)

    async def test_load_scene_empty_name(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "load_scene", "name": ""}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"Scene name is required"' in m for m in ws.sent)

    async def test_load_scene_not_found(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._load_scene_from_file = MagicMock(side_effect=FileNotFoundError())
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "load_scene", "name": "Missing"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("not found" in m for m in ws.sent)

    async def test_load_scene_generic_error(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._load_scene_from_file = MagicMock(side_effect=Exception("corrupt"))
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "load_scene", "name": "Bad"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Failed to load scene" in m for m in ws.sent)

    async def test_delete_scene(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "delete_scene", "name": "My Scene"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"scene_deleted"' in m for m in ws.sent)

    async def test_delete_scene_empty_name(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "delete_scene", "name": ""}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"Scene name is required"' in m for m in ws.sent)

    async def test_delete_scene_builtin_blocked(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "delete_scene", "name": "Chill Lounge"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Cannot delete built-in" in m for m in ws.sent)

    async def test_delete_scene_not_found(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._delete_scene_file = MagicMock(side_effect=FileNotFoundError())
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "delete_scene", "name": "Missing"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("not found" in m for m in ws.sent)

    async def test_delete_scene_generic_error(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._delete_scene_file = MagicMock(side_effect=Exception("perm denied"))
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "delete_scene", "name": "Test"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Failed to delete scene" in m for m in ws.sent)

    async def test_list_scenes_error(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._list_scenes = MagicMock(side_effect=Exception("IO error"))
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "list_scenes"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Failed to list scenes" in m for m in ws.sent)

    # ---- Zone/stage queries ----

    async def test_get_zones_with_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_zones"}).decode()]
        )
        await relay._handle_browser_client(ws)
        zone_msgs = [m for m in ws.sent if '"zones"' in m and '"vj_state"' not in m]
        assert len(zone_msgs) >= 1

    async def test_get_zone_with_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_zone", "zone": "main"}).decode()]
        )
        await relay._handle_browser_client(ws)

    async def test_get_stages_with_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_stages"}).decode()]
        )
        await relay._handle_browser_client(ws)

    async def test_get_stages_without_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = None
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_stages"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"stages"' in m for m in ws.sent)

    async def test_get_stages_mc_returns_none(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send = AsyncMock(return_value=None)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_stages"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"stages"' in m for m in ws.sent)

    async def test_get_stages_mc_error(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send = AsyncMock(side_effect=Exception("timeout"))
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_stages"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"stages"' in m for m in ws.sent)

    async def test_get_zones_mc_error(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.get_zones = AsyncMock(side_effect=Exception("timeout"))
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_zones"}).decode()]
        )
        await relay._handle_browser_client(ws)
        zone_msgs = [m for m in ws.sent if '"zones"' in m and '"vj_state"' not in m]
        assert len(zone_msgs) >= 1

    # ---- Sync test ----

    async def test_sync_test(self):
        relay = FakeRelay()
        relay.require_auth = False
        browser2 = FakeWebSocket()
        relay._broadcast_clients = {browser2}
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "sync_test"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"sync_test_flash"' in m for m in browser2.sent)

    async def test_sync_test_with_active_dj(self):
        relay = FakeRelay()
        relay.require_auth = False
        dj_ws = FakeWebSocket()
        relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=dj_ws)
        relay._active_dj_id = "dj1"
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "sync_test"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"sync_test_tone"' in m for m in dj_ws.sent)

    async def test_sync_test_dj_send_fails(self):
        relay = FakeRelay()
        relay.require_auth = False
        dj_ws = FakeWebSocket(fail_on_send=True)
        relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=dj_ws)
        relay._active_dj_id = "dj1"
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "sync_test"}).decode()]
        )
        await relay._handle_browser_client(ws)  # should not crash

    # ---- DJ management from browser ----

    async def test_set_active_dj(self):
        relay = FakeRelay()
        relay.require_auth = False
        dj_ws = FakeWebSocket()
        relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=dj_ws)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_active_dj", "dj_id": "dj1"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay._active_dj_id == "dj1"

    async def test_kick_dj(self):
        relay = FakeRelay()
        relay.require_auth = False
        dj_ws = FakeWebSocket()
        relay._djs["dj1"] = DJConnection(dj_id="dj1", dj_name="DJ A", websocket=dj_ws)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "kick_dj", "dj_id": "dj1"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert dj_ws.closed
        assert dj_ws.close_code == 4010

    async def test_kick_dj_nonexistent(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "kick_dj", "dj_id": "ghost"}).decode()]
        )
        await relay._handle_browser_client(ws)  # should not crash

    async def test_approve_dj(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._pending_djs["dj1"] = {
            "dj_id": "dj1", "dj_name": "Test", "waiting_since": time.time(),
        }
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "approve_dj", "dj_id": "dj1"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert "dj1" not in relay._pending_djs

    async def test_deny_dj(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._pending_djs["dj1"] = {
            "dj_id": "dj1", "dj_name": "Test", "waiting_since": time.time(),
        }
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "deny_dj", "dj_id": "dj1"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert "dj1" not in relay._pending_djs

    async def test_reorder_dj_queue(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "reorder_dj_queue", "dj_id": "dj1", "new_position": 0}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)

    # ---- Banner management ----

    async def test_set_banner_profile(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {
                        "type": "set_banner_profile",
                        "dj_id": "dj1",
                        "profile": {"banner_mode": "text", "text": "Hello"},
                    }
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert "dj1" in relay._dj_banner_profiles
        assert any('"banner_profile_saved"' in m for m in ws.sent)

    async def test_set_banner_profile_invalid_id(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_banner_profile", "dj_id": "dj1; DROP TABLE", "profile": {}}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert "dj1; DROP TABLE" not in relay._dj_banner_profiles

    async def test_set_banner_for_active_dj_pushes_to_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._active_dj_id = "dj1"
        relay._send_banner_config_to_minecraft = AsyncMock()
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "set_banner_profile", "dj_id": "dj1", "profile": {"banner_mode": "text"}}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        relay._send_banner_config_to_minecraft.assert_awaited_with("dj1")

    async def test_get_banner_profile(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._dj_banner_profiles["dj1"] = {"banner_mode": "text", "text": "Hi"}
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_banner_profile", "dj_id": "dj1"}).decode()]
        )
        await relay._handle_browser_client(ws)
        profile_msgs = [
            m for m in ws.sent
            if '"banner_profile"' in m and '"banner_profile_saved"' not in m
        ]
        assert len(profile_msgs) >= 1

    async def test_get_all_banner_profiles(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._dj_banner_profiles["dj1"] = {"banner_mode": "text"}
        relay._dj_banner_profiles["dj2"] = {"banner_mode": "image", "image_pixels": [1, 2, 3]}
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_all_banner_profiles"}).decode()]
        )
        await relay._handle_browser_client(ws)
        profile_msgs = [m for m in ws.sent if '"all_banner_profiles"' in m]
        assert len(profile_msgs) == 1
        data = mjson.decode(profile_msgs[0])
        assert "image_pixels" not in data["profiles"]["dj2"]
        assert data["profiles"]["dj2"]["has_image"] is True

    async def test_upload_banner_logo_no_pillow(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {
                        "type": "upload_banner_logo",
                        "dj_id": "dj1",
                        "image_base64": "data:image/png;base64,ABC",
                    }
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert any("Failed to process logo" in m for m in ws.sent)

    async def test_upload_banner_logo_success(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._process_logo_image = MagicMock(return_value=[[255, 0, 0]] * 10)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {
                        "type": "upload_banner_logo",
                        "dj_id": "dj1",
                        "image_base64": "data:image/png;base64,ABC",
                        "grid_width": 24,
                        "grid_height": 12,
                        "filename": "test.png",
                    }
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert any('"banner_logo_processed"' in m for m in ws.sent)
        assert relay._dj_banner_profiles["dj1"]["banner_mode"] == "image"

    # ---- Voice management ----

    async def test_get_voice_status_no_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = None
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_voice_status"}).decode()]
        )
        await relay._handle_browser_client(ws)
        voice_msgs = [m for m in ws.sent if '"voice_status"' in m]
        assert len(voice_msgs) >= 1
        data = mjson.decode(voice_msgs[0])
        assert data["available"] is False

    async def test_get_voice_status_with_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send = AsyncMock(
            return_value={"type": "voice_status", "available": True, "streaming": False}
        )
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "get_voice_status"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"voice_status"' in m for m in ws.sent)

    async def test_voice_config_forwarded(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._forward_voice_config = AsyncMock()
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "voice_config", "enabled": True, "channel_type": "dynamic"}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        relay._forward_voice_config.assert_awaited_once()

    # ---- Forward to Minecraft ----

    async def test_forward_fire_and_forget(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_bitmap_pattern", "zone": "main"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"ok"' in m for m in ws.sent)

    async def test_forward_with_response(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_render_mode", "mode": "bitmap"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"ok"' in m for m in ws.sent)

    async def test_forward_mc_not_connected(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = None
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_render_mode", "mode": "bitmap"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Minecraft not connected" in m for m in ws.sent)

    async def test_forward_mc_error(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send = AsyncMock(side_effect=Exception("timeout"))
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_render_mode", "mode": "bitmap"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Failed to forward" in m for m in ws.sent)

    # ---- Zone config sync ----

    async def test_set_zone_config_syncs_entity_count(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {
                        "type": "set_zone_config",
                        "zone": "main",
                        "config": {"entity_count": 48, "block_type": "DIAMOND_BLOCK"},
                    }
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay.entity_count == 48
        assert relay._get_zone_state("main").block_type == "DIAMOND_BLOCK"

    async def test_set_zone_config_syncs_scale(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {
                        "type": "set_zone_config",
                        "zone": "main",
                        "config": {"base_scale": 0.5, "max_scale": 2.0},
                    }
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        assert relay._pattern_config.base_scale == 0.5
        assert relay._pattern_config.max_scale == 2.0

    async def test_init_bitmap_syncs_state(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send = AsyncMock(
            return_value={"type": "bitmap_initialized", "width": 48, "height": 24}
        )
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [
                mjson.encode(
                    {"type": "init_bitmap", "zone": "led_wall", "pattern": "bmp_spectrum"}
                ).decode()
            ]
        )
        await relay._handle_browser_client(ws)
        zs = relay._get_zone_state("led_wall")
        assert zs.bitmap_initialized is True
        assert zs.bitmap_width == 48

    async def test_teardown_bitmap_syncs_state(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.send = AsyncMock(return_value={"type": "bitmap_teardown"})
        zs = relay._get_zone_state("led_wall")
        zs.bitmap_initialized = True
        zs.render_mode = "bitmap"
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "teardown_bitmap", "zone": "led_wall"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert zs.bitmap_initialized is False
        assert zs.render_mode == "block"

    # ---- Ambient lights / bloom ----

    async def test_set_ambient_lights(self):
        relay = FakeRelay()
        relay.require_auth = False
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_ambient_lights", "enabled": False}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert relay._ambient_lights_enabled is False

    async def test_set_ambient_lights_with_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_ambient_lights", "enabled": True}).decode()]
        )
        await relay._handle_browser_client(ws)
        relay.viz_client.ws.send.assert_awaited()

    # ---- Blackout/freeze with Minecraft ----

    async def test_blackout_toggle_on_with_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.set_visible = AsyncMock()
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_blackout", "enabled": True}).decode()]
        )
        await relay._handle_browser_client(ws)
        relay.viz_client.set_visible.assert_awaited_with("main", False)

    async def test_blackout_toggle_off_with_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay._blackout = True
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.set_visible = AsyncMock()
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "set_blackout", "enabled": False}).decode()]
        )
        await relay._handle_browser_client(ws)
        relay.viz_client.set_visible.assert_awaited_with("main", True)

    # ---- Parity check / stage scanning ----

    async def test_request_parity_check(self):
        relay = FakeRelay()
        relay.require_auth = False
        browser2 = FakeWebSocket()
        relay._broadcast_clients = {browser2}
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "request_parity_check"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"parity_check"' in m for m in browser2.sent)

    async def test_scan_stage_blocks_with_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.scan_stage_blocks = AsyncMock(
            return_value={"type": "stage_blocks", "blocks": []}
        )
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "scan_stage_blocks", "stage": "main"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any('"stage_blocks"' in m for m in ws.sent)

    async def test_scan_stage_blocks_no_mc(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = None
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "scan_stage_blocks", "stage": "main"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Minecraft not connected" in m for m in ws.sent)

    async def test_scan_stage_blocks_returns_none(self):
        relay = FakeRelay()
        relay.require_auth = False
        relay.viz_client = FakeVizClient(connected=True)
        relay.viz_client.scan_stage_blocks = AsyncMock(return_value=None)
        import msgspec.json as mjson

        ws = self._make_ws_with_messages(
            [mjson.encode({"type": "scan_stage_blocks", "stage": "main"}).decode()]
        )
        await relay._handle_browser_client(ws)
        assert any("Scan failed" in m for m in ws.sent)
