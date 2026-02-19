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
            print(f"  \033[92mAll tests passed!\033[0m")
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
        suite.add(TestResult(
            f"get_zones (found {zone_count})", True,
            (time.monotonic() - start) * 1000
        ))
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
        suite.add(TestResult(
            f"get_zone '{zone_name}'", True,
            (time.monotonic() - start) * 1000
        ))
    except Exception as e:
        suite.add(TestResult(
            f"get_zone '{zone_name}'", False,
            (time.monotonic() - start) * 1000, str(e)
        ))


async def test_get_zone_not_found(ws, suite: TestSuite):
    """Test error response for non-existent zone."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_zone", "zone": "__nonexistent_zone__"})
        assert resp.get("type") == "error", f"Expected 'error', got '{resp.get('type')}'"
        assert "message" in resp, "Missing 'message' in error"
        suite.add(TestResult("get_zone (not found → error)", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("get_zone (not found → error)", False, (time.monotonic() - start) * 1000, str(e)))


async def test_unknown_type(ws, suite: TestSuite):
    """Test error response for unknown message type."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "__unknown_test_type__"})
        assert resp.get("type") == "error", f"Expected 'error', got '{resp.get('type')}'"
        assert "__unknown_test_type__" in resp.get("message", ""), "Error message should mention the type"
        suite.add(TestResult("unknown type → error", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("unknown type → error", False, (time.monotonic() - start) * 1000, str(e)))


async def test_renderer_capabilities(ws, suite: TestSuite):
    """Test renderer capabilities query."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {"type": "get_renderer_capabilities", "zone": "main"})
        assert resp.get("type") == "renderer_capabilities", f"Expected 'renderer_capabilities', got '{resp.get('type')}'"
        assert "supported_backends" in resp, "Missing 'supported_backends'"
        assert isinstance(resp["supported_backends"], list), "'supported_backends' should be array"
        backends = resp["supported_backends"]
        suite.add(TestResult(
            f"renderer_capabilities ({', '.join(backends)})", True,
            (time.monotonic() - start) * 1000
        ))
    except Exception as e:
        suite.add(TestResult("renderer_capabilities", False, (time.monotonic() - start) * 1000, str(e)))


async def test_audio_state(ws, suite: TestSuite):
    """Test audio state message (high-frequency, should return 'ok')."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {
            "type": "audio_state",
            "zone": "main",
            "bands": [0.5, 0.3, 0.2, 0.1, 0.05],
            "amplitude": 0.4,
            "is_beat": False,
            "beat_intensity": 0.0,
            "frame": 1
        })
        assert resp.get("type") == "ok", f"Expected 'ok', got '{resp.get('type')}'"
        suite.add(TestResult("audio_state → ok", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("audio_state → ok", False, (time.monotonic() - start) * 1000, str(e)))


async def test_audio_state_with_beat(ws, suite: TestSuite):
    """Test audio state with beat detection."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {
            "type": "audio_state",
            "zone": "main",
            "bands": [0.9, 0.6, 0.4, 0.2, 0.1],
            "amplitude": 0.85,
            "is_beat": True,
            "beat_intensity": 0.92,
            "frame": 2
        })
        assert resp.get("type") == "ok", f"Expected 'ok', got '{resp.get('type')}'"
        suite.add(TestResult("audio_state (beat) → ok", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("audio_state (beat) → ok", False, (time.monotonic() - start) * 1000, str(e)))


async def test_batch_update_empty(ws, suite: TestSuite, zone_name: str):
    """Test batch_update with no entities (should still succeed)."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {
            "type": "batch_update",
            "zone": zone_name,
            "entities": [],
        })
        assert resp.get("type") == "batch_updated", f"Expected 'batch_updated', got '{resp.get('type')}'"
        assert resp.get("updated") == 0, f"Expected 0 updated, got {resp.get('updated')}"
        suite.add(TestResult("batch_update (empty)", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("batch_update (empty)", False, (time.monotonic() - start) * 1000, str(e)))


async def test_set_render_mode_invalid(ws, suite: TestSuite):
    """Test invalid render mode returns error."""
    start = time.monotonic()
    try:
        resp = await send_and_receive(ws, {
            "type": "set_render_mode",
            "zone": "main",
            "mode": "__invalid_mode__"
        })
        assert resp.get("type") == "error", f"Expected 'error', got '{resp.get('type')}'"
        suite.add(TestResult("set_render_mode (invalid → error)", True, (time.monotonic() - start) * 1000))
    except Exception as e:
        suite.add(TestResult("set_render_mode (invalid → error)", False, (time.monotonic() - start) * 1000, str(e)))


async def test_latency(ws, suite: TestSuite, iterations: int = 10):
    """Measure round-trip latency over multiple pings."""
    start = time.monotonic()
    try:
        latencies = []
        for _ in range(iterations):
            t0 = time.monotonic()
            resp = await send_and_receive(ws, {"type": "ping"})
            latencies.append((time.monotonic() - t0) * 1000)

        avg = sum(latencies) / len(latencies)
        p50 = sorted(latencies)[len(latencies) // 2]
        p99 = sorted(latencies)[int(len(latencies) * 0.99)]

        suite.add(TestResult(
            f"latency ({iterations}x ping, avg={avg:.1f}ms, p50={p50:.1f}ms, p99={p99:.1f}ms)",
            True, (time.monotonic() - start) * 1000
        ))
    except Exception as e:
        suite.add(TestResult("latency", False, (time.monotonic() - start) * 1000, str(e)))


async def run_smoke_tests(host: str, port: int, quick: bool = False):
    """Run the full smoke test suite."""
    suite = TestSuite()
    uri = f"ws://{host}:{port}"

    print(f"\n  Connecting to {uri} ...")

    try:
        async with websockets.connect(uri, open_timeout=10) as ws:
            print(f"  Connected! Running smoke tests...\n")

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

            suite.print_summary()
            return suite

    except ConnectionRefusedError:
        print(f"\n  \033[91mERROR: Connection refused at {uri}\033[0m")
        print(f"  Make sure the Minecraft server is running with the AudioViz plugin.")
        print(f"  Check that WebSocket port {port} is accessible.\n")
        sys.exit(1)
    except asyncio.TimeoutError:
        print(f"\n  \033[91mERROR: Connection timed out to {uri}\033[0m")
        print(f"  The server may be unreachable or the port may be blocked.\n")
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
