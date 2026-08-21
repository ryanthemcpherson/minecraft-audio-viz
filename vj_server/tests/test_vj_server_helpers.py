"""Tests for VJ server helpers and listener lifecycle selection."""

import asyncio
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import msgspec.json as mjson
import pytest

import vj_server.cli as cli_module
import vj_server.vj_server as vj_mod
from vj_server.beat_predictor import BeatPredictor
from vj_server.models import DJConnection
from vj_server.web_gateway import UnifiedWebConfig

from .conftest import FakeDJConnection, make_audio_frame


def _make_unified_server(monkeypatch: pytest.MonkeyPatch, **overrides) -> vj_mod.VJServer:
    ssl_context = object()
    monkeypatch.setattr(vj_mod, "build_server_ssl_context", lambda *_args: ssl_context)
    options = {
        "http_port": 18080,
        "unified_web": True,
        "public_origin": "https://203.0.113.9:18080",
        "tls_cert": "test-cert.pem",
        "tls_key": "test-key.pem",
        "metrics_port": None,
        "show_spectrograph": False,
    }
    options.update(overrides)
    return vj_mod.VJServer(**options)


@pytest.mark.parametrize(
    "overrides",
    [
        {"public_origin": None},
        {"http_port": 0},
        {"http_port": 18080, "dj_port": 18080},
    ],
)
def test_unified_mode_rejects_missing_required_configuration(
    monkeypatch: pytest.MonkeyPatch,
    overrides: dict,
) -> None:
    with pytest.raises(ValueError):
        _make_unified_server(monkeypatch, **overrides)


def test_unified_mode_requires_tls() -> None:
    with pytest.raises(ValueError):
        vj_mod.VJServer(
            unified_web=True,
            public_origin="https://203.0.113.9:8080",
            metrics_port=None,
            show_spectrograph=False,
        )


@pytest.mark.parametrize(
    "public_origin",
    [
        "http://203.0.113.9:18080",
        "https://203.0.113.9",
        "https://203.0.113.9:18080/admin",
        "https://203.0.113.9:18080?query=1",
        "https://203.0.113.9:18080#fragment",
    ],
)
def test_unified_mode_rejects_non_exact_https_origin(
    monkeypatch: pytest.MonkeyPatch,
    public_origin: str,
) -> None:
    with pytest.raises(ValueError):
        _make_unified_server(monkeypatch, public_origin=public_origin)


def test_unified_mode_normalizes_https_origin(monkeypatch: pytest.MonkeyPatch) -> None:
    server = _make_unified_server(
        monkeypatch,
        public_origin="HTTPS://VJ.EXAMPLE.COM:18080/",
    )

    assert server.public_origin == "https://vj.example.com:18080"


def test_modern_cli_propagates_unified_web_options(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict = {}

    class FakeVJServer:
        def __init__(self, **kwargs):
            captured.update(kwargs)

        def stop(self):
            pass

    def discard_coroutine(coroutine):
        coroutine.close()

    cert_file = tmp_path / "tls.crt"
    key_file = tmp_path / "tls.key"
    monkeypatch.setattr(vj_mod, "VJServer", FakeVJServer)
    monkeypatch.setattr(cli_module.asyncio, "run", discard_coroutine)
    monkeypatch.setattr(cli_module.signal, "signal", lambda *_args: None)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "audioviz-vj",
            "--no-auth",
            "--unified-web",
            "--public-origin",
            "https://203.0.113.9:18080",
            "--tls-cert",
            str(cert_file),
            "--tls-key",
            str(key_file),
        ],
    )

    assert cli_module.vj_server() == 0
    assert captured["unified_web"] is True
    assert captured["public_origin"] == "https://203.0.113.9:18080"


@pytest.mark.asyncio
async def test_unified_mode_starts_gateway_without_legacy_browser_listener(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    starts: list[tuple[str, int]] = []
    cleanup_calls = 0
    main_loop_entered = asyncio.Event()

    class FakeListener:
        def close(self):
            return None

        async def wait_closed(self):
            return None

    class FakeGatewayRunner:
        async def cleanup(self):
            nonlocal cleanup_calls
            cleanup_calls += 1

    async def fake_gateway(*args, **_kwargs):
        starts.append(("gateway", args[2]))
        assert isinstance(args[4], UnifiedWebConfig)
        assert args[4].public_origin == "https://203.0.113.9:18080"
        return FakeGatewayRunner()

    async def fake_ws_serve(_handler, _host, port, **_kwargs):
        starts.append(("websocket", port))
        return FakeListener()

    async def no_op():
        return None

    async def wait_for_cancellation():
        main_loop_entered.set()
        await asyncio.Future()

    monkeypatch.setattr(vj_mod, "start_unified_web_gateway", fake_gateway)
    monkeypatch.setattr(vj_mod, "ws_serve", fake_ws_serve)
    monkeypatch.setattr(
        vj_mod.threading,
        "Thread",
        lambda **_kwargs: pytest.fail("unified mode started the legacy HTTP thread"),
    )
    server = _make_unified_server(monkeypatch, broadcast_port=18766)
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = wait_for_cancellation

    task = asyncio.create_task(server.run())
    await asyncio.wait_for(main_loop_entered.wait(), timeout=1.0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    assert starts.count(("gateway", 18080)) == 1
    assert starts.count(("websocket", server.dj_port)) == 1
    assert all(port != 18766 for _kind, port in starts)
    assert cleanup_calls == 1


@pytest.mark.asyncio
async def test_unified_gateway_is_cleaned_up_when_dj_listener_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cleanup_calls = 0

    class FakeGatewayRunner:
        async def cleanup(self):
            nonlocal cleanup_calls
            cleanup_calls += 1

    async def fake_gateway(*_args, **_kwargs):
        return FakeGatewayRunner()

    async def failing_ws_serve(*_args, **_kwargs):
        raise OSError("DJ listener bind failed")

    monkeypatch.setattr(vj_mod, "start_unified_web_gateway", fake_gateway)
    monkeypatch.setattr(vj_mod, "ws_serve", failing_ws_serve)
    server = _make_unified_server(monkeypatch)

    with pytest.raises(OSError, match="DJ listener bind failed"):
        await server.run()

    assert cleanup_calls == 1


@pytest.mark.parametrize(
    "exit_scenario",
    ["normal", "cancellation", "dj_bind_failure", "broadcast_bind_failure"],
)
@pytest.mark.asyncio
async def test_legacy_http_listener_and_thread_stop_on_every_exit_path(
    monkeypatch: pytest.MonkeyPatch,
    exit_scenario: str,
) -> None:
    class ControlledHTTPServer:
        def __init__(self) -> None:
            self.events: list[str] = []
            self.serve_started = threading.Event()
            self.stop_requested = threading.Event()

        def serve_forever(self) -> None:
            self.serve_started.set()
            self.stop_requested.wait()

        def shutdown(self) -> None:
            self.events.append("shutdown")
            self.stop_requested.set()

        def server_close(self) -> None:
            self.events.append("server_close")

    class CapturingThread:
        def __init__(self, *, target, args=(), daemon=None) -> None:
            self.target = target
            self._thread = threading.Thread(target=target, args=args, daemon=daemon)
            self.join_timeouts: list[float | None] = []

        def start(self) -> None:
            self._thread.start()

        def join(self, timeout: float | None = None) -> None:
            self.join_timeouts.append(timeout)
            self._thread.join(timeout)

        def is_alive(self) -> bool:
            return self._thread.is_alive()

    class FakeWebSocketListener:
        def close(self) -> None:
            return None

        async def wait_closed(self) -> None:
            return None

    http_server = ControlledHTTPServer()
    threads: list[CapturingThread] = []
    websocket_starts = 0
    main_loop_entered = asyncio.Event()

    def make_thread(**kwargs) -> CapturingThread:
        thread = CapturingThread(**kwargs)
        threads.append(thread)
        return thread

    def fake_create_http_server(*_args):
        return http_server

    async def fake_ws_serve(*_args, **_kwargs):
        nonlocal websocket_starts
        websocket_starts += 1
        if exit_scenario == "dj_bind_failure" and websocket_starts == 1:
            raise OSError("DJ listener bind failed")
        if exit_scenario == "broadcast_bind_failure" and websocket_starts == 2:
            raise OSError("Browser listener bind failed")
        return FakeWebSocketListener()

    async def no_op() -> None:
        return None

    async def main_loop() -> None:
        main_loop_entered.set()
        if exit_scenario == "cancellation":
            await asyncio.Future()

    monkeypatch.setattr(vj_mod, "create_http_server", fake_create_http_server)
    monkeypatch.setattr(
        vj_mod,
        "threading",
        SimpleNamespace(Event=threading.Event, Thread=make_thread),
    )
    monkeypatch.setattr(vj_mod, "ws_serve", fake_ws_serve)
    server = vj_mod.VJServer(
        http_port=18080,
        metrics_port=None,
        show_spectrograph=False,
    )
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = main_loop

    try:
        if exit_scenario == "cancellation":
            task = asyncio.create_task(server.run())
            await asyncio.wait_for(main_loop_entered.wait(), timeout=1.0)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        elif exit_scenario == "normal":
            await server.run()
        else:
            with pytest.raises(OSError, match="listener bind failed"):
                await server.run()

        assert http_server.serve_started.wait(timeout=1.0)
        assert http_server.events == ["shutdown", "server_close"]
        serve_threads = [thread for thread in threads if thread.target == http_server.serve_forever]
        assert len(serve_threads) == 1
        assert serve_threads[0].join_timeouts == [5.0]
        assert serve_threads[0].is_alive() is False
        assert all(thread.is_alive() is False for thread in threads)
    finally:
        http_server.stop_requested.set()
        for thread in threads:
            thread.join(timeout=1.0)


@pytest.mark.asyncio
async def test_legacy_http_bind_failure_does_not_prevent_websocket_startup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    websocket_starts = 0

    class FakeWebSocketListener:
        def close(self) -> None:
            return None

        async def wait_closed(self) -> None:
            return None

    def failing_create_http_server(*_args):
        raise OSError("HTTP listener bind failed")

    async def fake_ws_serve(*_args, **_kwargs):
        nonlocal websocket_starts
        websocket_starts += 1
        return FakeWebSocketListener()

    async def no_op() -> None:
        return None

    monkeypatch.setattr(vj_mod, "create_http_server", failing_create_http_server)
    monkeypatch.setattr(vj_mod, "ws_serve", fake_ws_serve)
    server = vj_mod.VJServer(
        http_port=18080,
        metrics_port=None,
        show_spectrograph=False,
    )
    server._skip_minecraft = True
    server._pattern_hot_reload_enabled = False
    server._init_coordinator = no_op
    server._browser_heartbeat_loop = no_op
    server._main_loop = no_op

    await server.run()

    assert websocket_starts == 2


@pytest.mark.asyncio
async def test_legacy_http_cleanup_action_timeout_does_not_block_event_loop() -> None:
    action_started = threading.Event()
    release_action = threading.Event()

    def blocking_action() -> None:
        action_started.set()
        release_action.wait()

    server = vj_mod.VJServer(metrics_port=None, show_spectrograph=False)
    loop = asyncio.get_running_loop()
    started_at = loop.time()

    try:
        await asyncio.wait_for(
            server._run_bounded_legacy_http_action(
                "blocking test action",
                blocking_action,
                timeout=0.01,
            ),
            timeout=0.2,
        )
        assert action_started.is_set()
        assert loop.time() - started_at < 0.2
    finally:
        release_action.set()


# ============================================================================
# _apply_phase_beat_assist
# ============================================================================


class TestPhaseAssist:
    """Test phase-assisted beat firing via _apply_phase_beat_assist."""

    def _call(self, dj: FakeDJConnection, is_beat: bool, beat_intensity: float) -> tuple:
        return vj_mod.VJServer._apply_phase_beat_assist(None, dj, is_beat, beat_intensity)

    def test_real_beat_passes_through(self):
        """When is_beat is True, it should pass through and update last time."""
        dj = FakeDJConnection(
            bpm=128.0, tempo_confidence=0.9, beat_phase=0.0, phase_assist_last_time=0.0
        )
        result_beat, result_intensity = self._call(dj, True, 0.8)
        assert result_beat is True
        assert result_intensity == 0.8
        assert dj.phase_assist_last_time > 0.0

    def test_fires_when_phase_near_boundary(self, monkeypatch):
        """Phase assist should fire when phase is near 0 and confidence is high."""
        fake_now = 100.0
        monkeypatch.setattr(time, "time", lambda: fake_now)

        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.80,
            beat_phase=0.03,  # near boundary (< 0.08)
            phase_assist_last_time=0.0,  # never fired before
        )
        result_beat, result_intensity = self._call(dj, False, 0.0)
        assert result_beat is True
        assert result_intensity > 0.0

    def test_fires_when_phase_near_one(self, monkeypatch):
        """Phase > 0.92 should also be considered near boundary."""
        fake_now = 100.0
        monkeypatch.setattr(time, "time", lambda: fake_now)

        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.80,
            beat_phase=0.95,  # near end of cycle
            phase_assist_last_time=0.0,
        )
        result_beat, _ = self._call(dj, False, 0.0)
        assert result_beat is True

    def test_no_fire_when_confidence_low(self, monkeypatch):
        """Phase assist should not fire when tempo_confidence < 0.60."""
        monkeypatch.setattr(time, "time", lambda: 100.0)

        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.30,  # below 0.60 threshold
            beat_phase=0.02,
            phase_assist_last_time=0.0,
        )
        result_beat, _ = self._call(dj, False, 0.0)
        assert result_beat is False

    def test_no_fire_when_phase_mid_cycle(self, monkeypatch):
        """Phase assist should not fire when phase is in the middle (e.g., 0.5)."""
        monkeypatch.setattr(time, "time", lambda: 100.0)

        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.90,
            beat_phase=0.50,  # mid cycle — not near boundary
            phase_assist_last_time=0.0,
        )
        result_beat, _ = self._call(dj, False, 0.0)
        assert result_beat is False

    def test_cooldown_prevents_double_fire(self, monkeypatch):
        """Phase assist should not fire twice within the cooldown window (60% of beat period)."""
        # At 120 BPM, beat_period = 0.5s, cooldown = 0.3s
        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.90,
            beat_phase=0.03,
            phase_assist_last_time=0.0,
        )

        # First call — should fire
        monkeypatch.setattr(time, "time", lambda: 100.0)
        beat1, _ = self._call(dj, False, 0.0)
        assert beat1 is True

        # Second call only 0.1s later — within cooldown (0.3s)
        monkeypatch.setattr(time, "time", lambda: 100.1)
        dj.beat_phase = 0.03  # still near boundary
        beat2, _ = self._call(dj, False, 0.0)
        assert beat2 is False

    def test_fires_after_cooldown_expires(self, monkeypatch):
        """Phase assist should fire again once cooldown window has passed."""
        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.90,
            beat_phase=0.03,
            phase_assist_last_time=0.0,
        )

        # First fire
        monkeypatch.setattr(time, "time", lambda: 100.0)
        beat1, _ = self._call(dj, False, 0.0)
        assert beat1 is True

        # After cooldown (0.5s beat period * 0.60 = 0.30s)
        monkeypatch.setattr(time, "time", lambda: 100.4)
        dj.beat_phase = 0.03
        beat2, _ = self._call(dj, False, 0.0)
        assert beat2 is True

    def test_no_fire_when_bpm_too_low(self, monkeypatch):
        """Phase assist should not fire when BPM < 60."""
        monkeypatch.setattr(time, "time", lambda: 100.0)

        dj = FakeDJConnection(
            bpm=50.0,  # below 60
            tempo_confidence=0.90,
            beat_phase=0.03,
            phase_assist_last_time=0.0,
        )
        result_beat, _ = self._call(dj, False, 0.0)
        assert result_beat is False


# ============================================================================
# Audio frame sanitization — missing required keys
# ============================================================================


class TestAudioFrameSanitization:
    """Supplement test_audio_pipeline.py with edge cases for required key rejection."""

    def test_completely_empty_frame(self):
        from vj_server.models import _sanitize_audio_frame

        result = _sanitize_audio_frame({})
        assert result["bands"] == [0.0] * 5
        assert result["bpm"] == 120.0
        assert result["beat"] is False
        assert result["seq"] == 0

    def test_missing_peak_defaults(self):
        from vj_server.models import _sanitize_audio_frame

        frame = make_audio_frame()
        del frame["peak"]
        result = _sanitize_audio_frame(frame)
        assert result["peak"] == 0.0

    def test_missing_bpm_defaults_to_120(self):
        from vj_server.models import _sanitize_audio_frame

        frame = make_audio_frame()
        del frame["bpm"]
        result = _sanitize_audio_frame(frame)
        assert result["bpm"] == 120.0

    def test_none_values_for_all_numeric_fields(self):
        from vj_server.models import _sanitize_audio_frame

        frame = {
            "bands": None,
            "peak": None,
            "beat": None,
            "beat_i": None,
            "bpm": None,
            "tempo_conf": None,
            "beat_phase": None,
            "seq": None,
            "i_bass": None,
            "i_kick": None,
            "ts": None,
        }
        result = _sanitize_audio_frame(frame)
        assert len(result["bands"]) == 5
        assert isinstance(result["bpm"], float)
        assert isinstance(result["seq"], int)


@pytest.mark.asyncio
async def test_broadcast_viz_state_serializes_predictor_generated_confidence() -> None:
    """The real browser broadcast must accept confidence produced by NumPy math."""
    server = vj_mod.VJServer(require_auth=False, show_spectrograph=False, metrics_port=None)
    predictor = BeatPredictor(min_bpm=60.0, max_bpm=200.0)
    predictor._tempo_histogram[:] = 1.0
    predictor._tempo_histogram[60] = 10.0
    predictor._ioi_history.extend([0.5] * 16)
    predictor._extract_tempo_from_histogram()
    assert 0.5 < predictor.tempo_confidence < 1.0

    server._beat_predictor = predictor
    dj = DJConnection(dj_id="broadcast-dj", dj_name="Broadcast DJ", websocket=None)
    dj._jitter_ms = 1.0
    server._djs[dj.dj_id] = dj
    server._active_dj_id = dj.dj_id
    browser = AsyncMock()
    server._broadcast_clients = {browser}

    await server._broadcast_viz_state(
        entities=[],
        bands=[0.8, 0.6, 0.4, 0.2, 0.1],
        peak=0.9,
        is_beat=True,
        beat_intensity=0.95,
        tempo_confidence=predictor.tempo_confidence,
    )

    browser.send.assert_awaited_once()
    payload = mjson.decode(browser.send.await_args.args[0])
    assert payload["zone_status"]["tempo_confidence"] == round(predictor.tempo_confidence, 3)
    assert payload["sync_confidence"] == round(server._calculate_sync_confidence(dj), 0)
