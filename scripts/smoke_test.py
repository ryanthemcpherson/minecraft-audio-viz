#!/usr/bin/env python3
"""
Integration smoke test for the AudioViz Minecraft plugin.

Connects to the plugin's WebSocket server and exercises the protocol contract,
verifying that the plugin responds correctly to each message type.

Usage:
    python scripts/smoke_test.py                    # localhost:8765
    python scripts/smoke_test.py --host 192.168.1.204 --port 8765
    python scripts/smoke_test.py --quick            # ping-only check

Requires: websockets (pip install websockets)
"""

import argparse
import asyncio
import json
import sys
import time
from dataclasses import dataclass, field

try:
    import websockets
except ImportError:
    print("ERROR: 'websockets' package required. Install with: pip install websockets")
    sys.exit(1)


@dataclass
class TestResult:
    name: str
    passed: bool
    duration_ms: float = 0.0
    error: str = ""


@dataclass
class TestSuite:
    results: list = field(default_factory=list)

    def add(self, result: TestResult):
        self.results.append(result)

    @property
    def passed(self):
        return sum(1 for r in self.results if r.passed)

    @property
    def failed(self):
        return sum(1 for r in self.results if not r.passed)

    @property
    def total(self):
        return len(self.results)

    def print_summary(self):
        print("\n" + "=" * 60)
        print(f"  SMOKE TEST RESULTS: {self.passed}/{self.total} passed")
        print("=" * 60)

        for r in self.results:
            status = "\033[92mPASS\033[0m" if r.passed else "\033[91mFAIL\033[0m"
            print(f"  [{status}] {r.name} ({r.duration_ms:.0f}ms)")
            if r.error:
                print(f"         \033[91m{r.error}\033[0m")

        print("=" * 60)
        if self.failed > 0:
            print(f"  \033[91m{self.failed} test(s) FAILED\033[0m")
        else:
            print("  \033[92mAll tests passed!\033[0m")
        print()


async def send_and_receive(ws, message: dict, timeout: float = 5.0) -> dict:
    """Send a JSON message and wait for the response."""
    await ws.send(json.dumps(message))
    response = await asyncio.wait_for(ws.recv(), timeout=timeout)
    return json.loads(response)


async def test_ping(ws, suite: TestSuite):
    """Test ping/pong handshake."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "ping"})
        assert resp.get("type") == "pong", f"Expected 'pong', got '{resp.get('type')}'"
        assert "timestamp" in resp, "Missing timestamp in pong response"
        assert resp["timestamp"] > 0, "Timestamp should be positive"
        suite.add(TestResult("ping → pong", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("ping → pong", False, (time.monotonic() - start) * 1000, str(e)))


async def test_get_zones(ws, suite: TestSuite):
    """Test zone listing."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_zones"})
        assert resp.get("type") == "zones", f"Expected 'zones', got '{resp.get('type')}'"
        assert "zones" in resp, "Missing 'zones' array"
        assert isinstance(resp["zones"], list), "'zones' should be an array"
        zone_count = len(resp["zones"])
        suite.add(
            TestResult(f"get_zones (found {zone_count})", True, (time.monotonic() - start) * 1000)
        )
        return resp["zones"]
    except Exception as e:
        suite.add(TestResult("get_zones", False, (time.monotonic() - start) * 1000, str(e)))
        return []


async def test_get_zone(ws, suite: TestSuite, zone_name: str):
    """Test single zone retrieval."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_zone", "zone": zone_name})
        assert resp.get("type") == "zone", f"Expected 'zone', got '{resp.get('type')}'"
        assert "entity_count" in resp, "Missing 'entity_count'"
        suite.add(TestResult(f"get_zone '{zone_name}'", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(
            TestResult(f"get_zone '{zone_name}'", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_get_zone_not_found(ws, suite: TestSuite):
    """Test error response for non-existent zone."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_zone", "zone": "__nonexistent_zone__"})
        assert resp.get("type") == "error", f"Expected 'error', got '{resp.get('type')}'"
        assert "message" in resp, "Missing 'message' in error"
        suite.add(
            TestResult("get_zone (not found → error)", True, (time.monotonic() - start) * 1000)
        )
    except Exception as e:
        suite.add(
            TestResult(
                "get_zone (not found → error)", False, (time.monotonic() - start) * 1000, str(e)
            )
        )


async def test_unknown_type(ws, suite: TestSuite):
    """Test error response for unknown message type."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "__unknown_test_type__"})
        assert resp.get("type") == "error", f"Expected 'error', got '{resp.get('type')}'"
        assert "__unknown_test_type__" in resp.get("message", ""), (
            "Error message should mention the type"
        )
        suite.add(TestResult("unknown type → error", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(
            TestResult("unknown type → error", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_renderer_capabilities(ws, suite: TestSuite):
    """Test renderer capabilities query."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_renderer_capabilities", "zone": "main"})
        assert resp.get("type") == "renderer_capabilities", (
            f"Expected 'renderer_capabilities', got '{resp.get('type')}'"
        )
        assert "supported_backends" in resp, "Missing 'supported_backends'"
        assert isinstance(resp["supported_backends"], list), "'supported_backends' should be array"
        backends = resp["supported_backends"]
        suite.add(
            TestResult(
                f"renderer_capabilities ({', '.join(backends)})",
                True,
                (time.monotonic() - start) * 1000,
            )
        )
    except Exception as e:
        suite.add(
            TestResult("renderer_capabilities", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_audio_state(ws, suite: TestSuite):
    """Test audio state message (high-frequency, should return 'ok')."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws,
            {
                "type": "audio_state",
                "zone": "main",
                "bands": [0.5, 0.3, 0.2, 0.1, 0.05],
                "amplitude": 0.4,
                "is_beat": False,
                "beat_intensity": 0.0,
                "frame": 1,
            },
        )
        assert resp.get("type") == "ok", f"Expected 'ok', got '{resp.get('type')}'"
        suite.add(TestResult("audio_state → ok", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("audio_state → ok", False, (time.monotonic() - start) * 1000, str(e)))


async def test_audio_state_with_beat(ws, suite: TestSuite):
    """Test audio state with beat detection."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws,
            {
                "type": "audio_state",
                "zone": "main",
                "bands": [0.9, 0.6, 0.4, 0.2, 0.1],
                "amplitude": 0.85,
                "is_beat": True,
                "beat_intensity": 0.92,
                "frame": 2,
            },
        )
        assert resp.get("type") == "ok", f"Expected 'ok', got '{resp.get('type')}'"
        suite.add(TestResult("audio_state (beat) → ok", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(
            TestResult("audio_state (beat) → ok", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_batch_update_empty(ws, suite: TestSuite, zone_name: str):
    """Test batch_update with no entities (should still succeed)."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws,
            {
                "type": "batch_update",
                "zone": zone_name,
                "entities": [],
            },
        )
        assert resp.get("type") == "batch_updated", (
            f"Expected 'batch_updated', got '{resp.get('type')}'"
        )
        assert resp.get("updated") == 0, f"Expected 0 updated, got {resp.get('updated')}"
        suite.add(TestResult("batch_update (empty)", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(
            TestResult("batch_update (empty)", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_set_render_mode_invalid(ws, suite: TestSuite):
    """Test invalid render mode returns error."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws, {"type": "set_render_mode", "zone": "main", "mode": "__invalid_mode__"}
        )
        assert resp.get("type") == "error", f"Expected 'error', got '{resp.get('type')}'"
        suite.add(
            TestResult("set_render_mode (invalid → error)", True, (time.monotonic() - start) * 1000)
        )
    except Exception as e:
        suite.add(
            TestResult(
                "set_render_mode (invalid → error)",
                False,
                (time.monotonic() - start) * 1000,
                str(e),
            )
        )


async def test_latency(ws, suite: TestSuite, iterations: int = 10):
    """Measure round-trip latency over multiple pings."""
    start = time.monotonic()
    try:
        latencies = []
        for _ in range(iterations):
            t0 = time.monotonic()
            await send_and_receive(ws, {"type": "ping"})
            latencies.append((time.monotonic() - t0) * 1000)

        avg = sum(latencies) / len(latencies)
        p50 = sorted(latencies)[len(latencies) // 2]
        p99 = sorted(latencies)[int(len(latencies) * 0.99)]

        suite.add(
            TestResult(
                f"latency ({iterations}x ping, avg={avg:.1f}ms, p50={p50:.1f}ms, p99={p99:.1f}ms)",
                True,
                (time.monotonic() - start) * 1000,
            )
        )
    except Exception as e:
        suite.add(TestResult("latency", False, (time.monotonic() - start) * 1000, str(e)))


# ======================================================================
# Bitmap / Phase 3 Smoke Tests
# ======================================================================


async def test_get_bitmap_patterns(ws, suite: TestSuite):
    """Verify get_bitmap_patterns returns registered patterns."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_bitmap_patterns"})
        assert resp["type"] == "bitmap_patterns", f"Expected bitmap_patterns, got {resp['type']}"
        patterns = resp["patterns"]
        assert len(patterns) >= 13, f"Expected >=13 patterns, got {len(patterns)}"

        ids = {p["id"] for p in patterns}
        required = {
            "bmp_spectrum",
            "bmp_plasma",
            "bmp_waveform",
            "bmp_marquee",
            "bmp_track_display",
            "bmp_countdown",
            "bmp_chat_wall",
            "bmp_crowd_cam",
            "bmp_fireworks",
            "bmp_minimap",
            "bmp_image",
        }
        missing = required - ids
        assert not missing, f"Missing patterns: {missing}"

        for p in patterns:
            assert "id" in p and "name" in p and "description" in p

        suite.add(
            TestResult(
                f"get_bitmap_patterns ({len(patterns)} patterns)",
                True,
                (time.monotonic() - start) * 1000,
            )
        )
    except Exception as e:
        suite.add(
            TestResult("get_bitmap_patterns", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_get_bitmap_transitions(ws, suite: TestSuite):
    """Verify get_bitmap_transitions returns built-in transitions."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_bitmap_transitions"})
        assert resp["type"] == "bitmap_transitions"
        transitions = resp["transitions"]
        assert len(transitions) == 8, f"Expected 8 transitions, got {len(transitions)}"

        ids = {t["id"] for t in transitions}
        assert "crossfade" in ids
        assert "dissolve" in ids
        assert "wipe_left" in ids

        suite.add(
            TestResult(
                f"get_bitmap_transitions ({len(transitions)} transitions)",
                True,
                (time.monotonic() - start) * 1000,
            )
        )
    except Exception as e:
        suite.add(
            TestResult("get_bitmap_transitions", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_get_bitmap_palettes(ws, suite: TestSuite):
    """Verify get_bitmap_palettes returns built-in palettes."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_bitmap_palettes"})
        assert resp["type"] == "bitmap_palettes"
        palettes = resp["palettes"]
        assert len(palettes) == 10, f"Expected 10 palettes, got {len(palettes)}"

        ids = {p["id"] for p in palettes}
        assert "spectrum" in ids
        assert "neon" in ids
        assert "cyberpunk" in ids

        suite.add(
            TestResult(
                f"get_bitmap_palettes ({len(palettes)} palettes)",
                True,
                (time.monotonic() - start) * 1000,
            )
        )
    except Exception as e:
        suite.add(
            TestResult("get_bitmap_palettes", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_init_bitmap(ws, suite: TestSuite, zone_name: str):
    """Initialize a bitmap zone for subsequent tests."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws,
            {
                "type": "init_bitmap",
                "zone": zone_name,
                "width": 32,
                "height": 16,
                "pattern": "bmp_spectrum",
            },
        )
        assert resp["type"] == "bitmap_initialized", (
            f"Expected bitmap_initialized, got {resp['type']}"
        )
        assert resp["zone"] == zone_name
        assert resp["width"] == 32
        assert resp["height"] == 16

        suite.add(TestResult("init_bitmap", True, (time.monotonic() - start) * 1000))
        return True
    except Exception as e:
        suite.add(TestResult("init_bitmap", False, (time.monotonic() - start) * 1000, str(e)))
        return False


async def test_set_bitmap_pattern(ws, suite: TestSuite, zone_name: str):
    """Switch bitmap pattern."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws, {"type": "set_bitmap_pattern", "zone": zone_name, "pattern": "bmp_plasma"}
        )
        assert resp["type"] == "bitmap_pattern_set", (
            f"Got {resp['type']}: {resp.get('message', '')}"
        )
        assert resp["pattern"] == "bmp_plasma"

        suite.add(
            TestResult("set_bitmap_pattern → bmp_plasma", True, (time.monotonic() - start) * 1000)
        )
    except Exception as e:
        suite.add(
            TestResult("set_bitmap_pattern", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_bitmap_transition(ws, suite: TestSuite, zone_name: str):
    """Start a crossfade transition."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws,
            {
                "type": "bitmap_transition",
                "zone": zone_name,
                "pattern": "bmp_waveform",
                "transition": "crossfade",
                "duration_ticks": 20,
            },
        )
        assert resp["type"] == "bitmap_transition_started", (
            f"Got {resp['type']}: {resp.get('message', '')}"
        )

        suite.add(
            TestResult(
                "bitmap_transition (crossfade → waveform)", True, (time.monotonic() - start) * 1000
            )
        )
    except Exception as e:
        suite.add(TestResult("bitmap_transition", False, (time.monotonic() - start) * 1000, str(e)))


async def test_bitmap_effects(ws, suite: TestSuite):
    """Test effects pipeline: brightness, strobe, wash, reset."""
    start = time.monotonic()
    try:
        # Set brightness
        resp = await send_and_receive(
            ws, {"type": "bitmap_effects", "action": "brightness", "level": 0.7}
        )
        assert resp["type"] == "ok"

        # Enable strobe
        resp = await send_and_receive(
            ws,
            {
                "type": "bitmap_effects",
                "action": "strobe",
                "enabled": True,
                "divisor": 2,
                "color": 0xFFFFFFFF,
            },
        )
        assert resp["type"] == "ok"

        # Color wash
        resp = await send_and_receive(
            ws, {"type": "bitmap_effects", "action": "wash", "color": 0xFFFF0000, "opacity": 0.3}
        )
        assert resp["type"] == "ok"

        # Reset all
        resp = await send_and_receive(ws, {"type": "bitmap_effects", "action": "reset"})
        assert resp["type"] == "ok"

        suite.add(
            TestResult(
                "bitmap_effects (brightness/strobe/wash/reset)",
                True,
                (time.monotonic() - start) * 1000,
            )
        )
    except Exception as e:
        suite.add(TestResult("bitmap_effects", False, (time.monotonic() - start) * 1000, str(e)))


async def test_bitmap_palette(ws, suite: TestSuite):
    """Set and clear a color palette."""
    start = time.monotonic()
    try:
        # Set palette
        resp = await send_and_receive(ws, {"type": "bitmap_palette", "palette": "neon"})
        assert resp["type"] == "bitmap_palette_set"
        assert resp["palette"] == "neon"

        # Clear palette
        resp = await send_and_receive(ws, {"type": "bitmap_palette", "palette": "none"})
        assert resp["type"] == "bitmap_palette_set"

        suite.add(
            TestResult("bitmap_palette (set neon → clear)", True, (time.monotonic() - start) * 1000)
        )
    except Exception as e:
        suite.add(TestResult("bitmap_palette", False, (time.monotonic() - start) * 1000, str(e)))


async def test_bitmap_marquee(ws, suite: TestSuite, zone_name: str):
    """Queue a marquee message."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws,
            {
                "type": "bitmap_marquee",
                "zone": zone_name,
                "text": "SMOKE TEST",
                "color": 0xFFFFFF00,
            },
        )
        assert resp["type"] == "ok"

        suite.add(TestResult("bitmap_marquee", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("bitmap_marquee", False, (time.monotonic() - start) * 1000, str(e)))


async def test_bitmap_track_display(ws, suite: TestSuite, zone_name: str):
    """Set track display info."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws,
            {
                "type": "bitmap_track_display",
                "zone": zone_name,
                "artist": "Deadmau5",
                "title": "Strobe",
            },
        )
        assert resp["type"] == "ok"

        suite.add(TestResult("bitmap_track_display", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(
            TestResult("bitmap_track_display", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_bitmap_countdown(ws, suite: TestSuite, zone_name: str):
    """Start and stop a countdown."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws, {"type": "bitmap_countdown", "zone": zone_name, "action": "start", "seconds": 5}
        )
        assert resp["type"] == "ok"

        resp = await send_and_receive(
            ws, {"type": "bitmap_countdown", "zone": zone_name, "action": "stop"}
        )
        assert resp["type"] == "ok"

        suite.add(
            TestResult("bitmap_countdown (start/stop)", True, (time.monotonic() - start) * 1000)
        )
    except Exception as e:
        suite.add(TestResult("bitmap_countdown", False, (time.monotonic() - start) * 1000, str(e)))


async def test_bitmap_chat(ws, suite: TestSuite, zone_name: str):
    """Push a chat message to the LED wall."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws,
            {
                "type": "bitmap_chat",
                "zone": zone_name,
                "player": "SmokeBot",
                "message": "Hello from smoke test!",
            },
        )
        assert resp["type"] == "ok"

        suite.add(TestResult("bitmap_chat", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("bitmap_chat", False, (time.monotonic() - start) * 1000, str(e)))


async def test_bitmap_firework(ws, suite: TestSuite):
    """Spawn a firework particle."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "bitmap_firework", "x": 0.5, "y": 0.3})
        assert resp["type"] == "ok"

        suite.add(TestResult("bitmap_firework", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("bitmap_firework", False, (time.monotonic() - start) * 1000, str(e)))


async def test_bitmap_unknown_pattern(ws, suite: TestSuite, zone_name: str):
    """Verify error for unknown pattern."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(
            ws, {"type": "set_bitmap_pattern", "zone": zone_name, "pattern": "bmp_does_not_exist"}
        )
        assert resp["type"] == "error", "Should return error for unknown pattern"

        suite.add(
            TestResult(
                "set_bitmap_pattern (unknown → error)", True, (time.monotonic() - start) * 1000
            )
        )
    except Exception as e:
        suite.add(
            TestResult(
                "set_bitmap_pattern (unknown → error)",
                False,
                (time.monotonic() - start) * 1000,
                str(e),
            )
        )


async def test_bitmap_unknown_palette(ws, suite: TestSuite):
    """Verify error for unknown palette."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "bitmap_palette", "palette": "nonexistent_xyz"})
        assert resp["type"] == "error", "Should return error for unknown palette"

        suite.add(
            TestResult("bitmap_palette (unknown → error)", True, (time.monotonic() - start) * 1000)
        )
    except Exception as e:
        suite.add(
            TestResult(
                "bitmap_palette (unknown → error)", False, (time.monotonic() - start) * 1000, str(e)
            )
        )


async def test_get_bitmap_status(ws, suite: TestSuite, zone_name: str):
    """Query bitmap status for an initialized zone."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_bitmap_status", "zone": zone_name})
        assert resp["type"] == "bitmap_status", f"Expected bitmap_status, got {resp['type']}"
        assert resp["zone"] == zone_name
        assert resp["active"] is True, "Zone should be active after init"
        assert resp["width"] == 32
        assert resp["height"] == 16

        suite.add(TestResult("get_bitmap_status", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("get_bitmap_status", False, (time.monotonic() - start) * 1000, str(e)))


async def test_bitmap_composition(ws, suite: TestSuite):
    """Test composition manager: get_zones, set_sync_mode, flash_all."""
    start = time.monotonic()
    try:
        # Get zones (starts empty)
        resp = await send_and_receive(ws, {"type": "bitmap_composition", "action": "get_zones"})
        assert resp["type"] == "bitmap_composition_zones"
        assert "sync_mode" in resp

        # Set sync mode
        resp = await send_and_receive(
            ws, {"type": "bitmap_composition", "action": "set_sync_mode", "mode": "MIRROR"}
        )
        assert resp["type"] == "ok"

        # Flash all zones
        resp = await send_and_receive(
            ws,
            {
                "type": "bitmap_composition",
                "action": "flash_all",
                "color": 0xFFFFFF00,
                "intensity": 0.5,
            },
        )
        assert resp["type"] == "ok"

        # Reset to independent
        resp = await send_and_receive(
            ws, {"type": "bitmap_composition", "action": "set_sync_mode", "mode": "INDEPENDENT"}
        )
        assert resp["type"] == "ok"

        suite.add(
            TestResult(
                "bitmap_composition (zones/sync/flash)", True, (time.monotonic() - start) * 1000
            )
        )
    except Exception as e:
        suite.add(
            TestResult("bitmap_composition", False, (time.monotonic() - start) * 1000, str(e))
        )


async def test_bitmap_frame(ws, suite: TestSuite, zone_name: str):
    """Push a raw frame via JSON pixel array format."""
    start = time.monotonic()
    try:
        # Build a small 32x16 frame (all blue)
        pixel_count = 32 * 16
        pixels = [0xFF0000FF] * pixel_count  # ARGB blue

        resp = await send_and_receive(
            ws, {"type": "bitmap_frame", "zone": zone_name, "pixel_array": pixels}
        )
        assert resp["type"] == "ok", f"Expected ok, got {resp['type']}: {resp.get('message', '')}"

        suite.add(
            TestResult("bitmap_frame (512px JSON array)", True, (time.monotonic() - start) * 1000)
        )
    except Exception as e:
        suite.add(TestResult("bitmap_frame", False, (time.monotonic() - start) * 1000, str(e)))


async def run_smoke_tests(host: str, port: int, quick: bool = False):
    """Run the full smoke test suite."""
    suite = TestSuite()
    uri = f"ws://{host}:{port}"

    print(f"\n  Connecting to {uri} ...")

    try:
        async with websockets.connect(uri, open_timeout=10) as ws:
            print("  Connected! Running smoke tests...\n")

            # Always run ping
            await test_ping(ws, suite)

            if quick:
                suite.print_summary()
                return suite

            # Protocol tests
            await test_unknown_type(ws, suite)
            zones = await test_get_zones(ws, suite)
            await test_get_zone_not_found(ws, suite)

            # If zones exist, test zone-specific operations
            if zones:
                zone_name = zones[0].get("name", "main")
                await test_get_zone(ws, suite, zone_name)
                await test_batch_update_empty(ws, suite, zone_name)
            else:
                print("  ⚠ No zones found — skipping zone-specific tests")
                print("    Create a zone with /audioviz createzone main")

            # Renderer
            await test_renderer_capabilities(ws, suite)

            # Audio state
            await test_audio_state(ws, suite)
            await test_audio_state_with_beat(ws, suite)

            # Render mode validation
            await test_set_render_mode_invalid(ws, suite)

            # Latency benchmark
            await test_latency(ws, suite)

            # ========== Bitmap / Phase 3 Tests ==========
            print("  --- Bitmap subsystem ---")

            # Catalog queries (no zone needed)
            await test_get_bitmap_patterns(ws, suite)
            await test_get_bitmap_transitions(ws, suite)
            await test_get_bitmap_palettes(ws, suite)

            # Effects and palettes (no active zone needed)
            await test_bitmap_effects(ws, suite)
            await test_bitmap_palette(ws, suite)
            await test_bitmap_unknown_palette(ws, suite)
            await test_bitmap_firework(ws, suite)
            await test_bitmap_composition(ws, suite)

            # Zone-dependent bitmap tests
            if zones:
                zone_name = zones[0].get("name", "main")
                bitmap_ok = await test_init_bitmap(ws, suite, zone_name)
                if bitmap_ok:
                    await test_get_bitmap_status(ws, suite, zone_name)
                    await test_set_bitmap_pattern(ws, suite, zone_name)
                    await test_bitmap_transition(ws, suite, zone_name)
                    await test_bitmap_marquee(ws, suite, zone_name)
                    await test_bitmap_track_display(ws, suite, zone_name)
                    await test_bitmap_countdown(ws, suite, zone_name)
                    await test_bitmap_chat(ws, suite, zone_name)
                    await test_bitmap_frame(ws, suite, zone_name)
                    await test_bitmap_unknown_pattern(ws, suite, zone_name)
                else:
                    print("  ⚠ init_bitmap failed — skipping zone bitmap tests")
            else:
                print("  ⚠ No zones — skipping zone bitmap tests")

            suite.print_summary()
            return suite

    except ConnectionRefusedError:
        print(f"\n  \033[91mERROR: Connection refused at {uri}\033[0m")
        print("  Make sure the Minecraft server is running with the AudioViz plugin.")
        print(f"  Check that WebSocket port {port} is accessible.\n")
        sys.exit(1)
    except asyncio.TimeoutError:
        print(f"\n  \033[91mERROR: Connection timed out to {uri}\033[0m")
        print("  The server may be unreachable or the port may be blocked.\n")
        sys.exit(1)
    except Exception as e:
        print(f"\n  \033[91mERROR: {e}\033[0m\n")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="AudioViz Minecraft plugin smoke test")
    parser.add_argument("--host", default="localhost", help="WebSocket host (default: localhost)")
    parser.add_argument("--port", type=int, default=8765, help="WebSocket port (default: 8765)")
    parser.add_argument("--quick", action="store_true", help="Quick ping-only test")
    args = parser.parse_args()

    suite = asyncio.run(run_smoke_tests(args.host, args.port, args.quick))

    sys.exit(0 if suite.failed == 0 else 1)


if __name__ == "__main__":
    main()
