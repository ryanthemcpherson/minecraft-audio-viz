"""
VJ Server - Central server for multi-DJ audio visualization.

Accepts connections from multiple remote DJs, manages active DJ selection,
and forwards visualization data to Minecraft and browser clients.

Usage:
    python -m vj_server.vj_server --config configs/dj_auth.json

Architecture:
    DJ 1 (Remote) â"€â"€â"
    DJ 2 (Remote) â"€â"€â"¼â"€â"€> VJ Server â"€â"€> Minecraft + Browsers
    DJ 3 (Remote) â"€â"€â"˜
                    â†‘
                VJ Admin Panel
"""

import argparse
import asyncio
import bisect
import json
import logging
import math
import os
import signal
import sys
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    import websockets  # noqa: F401 — used by RelayMixin at runtime
    from websockets.server import serve as ws_serve

    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False

try:
    import aalink

    HAS_LINK = True
except ImportError:
    HAS_LINK = False

from vj_server.beat_predictor import BeatPredictor
from vj_server.coordinator_client import CoordinatorClient
from vj_server.dj_manager import DJManagerMixin
from vj_server.models import (
    _USE_ASYNC_LUA,
    ConnectCode,
    DjAudioFrame,
    DJAuthConfig,
    DJConnection,
    ZonePatternState,
    _sanitize_audio_frame,
    run_http_server,
)
from vj_server.patterns import (
    AudioState,
    PatternConfig,
)
from vj_server.relay import RelayMixin
from vj_server.spectrograph import TerminalSpectrograph
from vj_server.stage_manager import StageManagerMixin
from vj_server.viz_client import VizClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("vj_server")


class VJServer(DJManagerMixin, StageManagerMixin, RelayMixin):
    """
    Central VJ Server that manages multiple DJ connections.

    Responsibilities:
    - Accept DJ WebSocket connections (port 9000)
    - Authenticate DJs
    - Manage active DJ selection
    - Forward active DJ's audio to visualization
    - Serve VJ admin panel
    - Connect to Minecraft
    """

    def __init__(
        self,
        dj_port: int = 9000,
        broadcast_port: int = 8766,
        http_port: int = 8080,
        minecraft_host: str = "localhost",
        minecraft_port: int = 8765,
        zone: str = "main",
        entity_count: int = 16,
        auth_config: Optional[DJAuthConfig] = None,
        require_auth: bool = True,
        show_spectrograph: bool = True,
        metrics_port: Optional[int] = 9001,
        visual_delay_ms: float = 0.0,
        visual_delay_mode: str = "manual",
        enable_link: bool = False,
    ):
        self.dj_port = dj_port
        self.broadcast_port = broadcast_port
        self.http_port = http_port
        self.minecraft_host = minecraft_host
        self.minecraft_port = minecraft_port
        self.zone = zone
        self.entity_count = entity_count
        self.auth_config = auth_config or DJAuthConfig()
        self.require_auth = require_auth
        self.metrics_port = metrics_port

        # DJ management
        self._djs: Dict[str, DJConnection] = {}
        self._active_dj_id: Optional[str] = None
        self._dj_queue: List[str] = []  # Priority queue of DJ IDs
        self._dj_lock = asyncio.Lock()  # Lock for DJ dictionary operations

        # Pending DJ approval queue (connect-code DJs that need VJ approval)
        self._pending_djs: Dict[
            str, dict
        ] = {}  # dj_id -> {dj_id, dj_name, websocket, waiting_since, ...}

        # Track last known MC connection state for change detection
        self._last_mc_connected: bool = False

        # Connect codes for DJ client authentication
        self._connect_codes: Dict[str, ConnectCode] = {}  # code -> ConnectCode
        self._code_cleanup_task: Optional[asyncio.Task] = None
        self._code_show_ids: Dict[str, str] = {}  # code -> coordinator show_id

        # Auth rate limiting: per-IP sliding window
        self._auth_attempts: dict[str, list[float]] = {}  # IP -> list of attempt timestamps
        self._auth_rate_limit_max: int = 5  # max attempts per window
        self._auth_rate_limit_window: float = 60.0  # seconds
        self._auth_last_cleanup: float = time.time()  # For time-based periodic cleanup

        # Coordinator integration (for centralized connect codes)
        self._coordinator: Optional["CoordinatorClient"] = None
        self._coordinator_heartbeat_task: Optional[asyncio.Task] = None

        # Browser clients
        self._broadcast_clients: Set = set()
        self._voice_subscribers: Set = set()  # Clients subscribed to voice_audio frames
        self._browser_heartbeat_task: Optional[asyncio.Task] = None
        self._browser_pong_pending: Dict = {}  # websocket -> missed_pong_count
        self._browser_last_pong: Dict = {}  # websocket -> timestamp of last pong

        # Minecraft client
        self.viz_client: Optional[VizClient] = None
        self._mc_reconnect_task: Optional[asyncio.Task] = None
        self._mc_reconnect_backoff: float = 5.0  # Initial backoff (seconds)
        self._skip_minecraft: bool = False  # Set True for --no-minecraft mode

        # Pattern system
        self._pattern_config = PatternConfig(entity_count=entity_count)
        self._zone_patterns: Dict[str, ZonePatternState] = {}
        self._bitmap_pattern_cache: list[dict] = []  # Cached bitmap patterns from MC plugin
        self._default_transition_duration = 1.0  # Default crossfade duration for new zones

        # Initialize default zone pattern state
        self._get_zone_state(zone)

        # Spectrograph
        self.spectrograph = TerminalSpectrograph() if show_spectrograph else None

        # Frame counter
        self._frame_count = 0
        self._running = False

        # Control state
        self._blackout = False
        self._freeze = False
        self._active_effects = {}  # Active effects with end times
        self._band_sensitivity = [1.0, 1.0, 1.0, 1.0, 1.0]  # Per-band sensitivity
        self._band_materials: List[Optional[str]] = [
            "SEA_LANTERN",  # Bass: cyan glow
            "VERDANT_FROGLIGHT",  # Low-mid: green-blue glow
            "PEARLESCENT_FROGLIGHT",  # Mid: purple glow
            "SHROOMLIGHT",  # High-mid: orange glow
            "GLOWSTONE",  # High: amber glow
        ]  # Per-band block overrides
        self._dj_palettes: Dict[str, list] = {}  # dj_id -> 5-element block palette
        self._dj_presets: Dict[str, str] = {}  # dj_id -> preset name (e.g. "edm")
        self._current_preset_name: str = "auto"  # Currently active preset name
        self._band_materials_source: str = "default"  # "default" | "dj_palette" | "admin"
        # Visual-state shaping for snappier motion with less low-level wobble.
        self._visual_band_state = [0.0] * 5
        self._visual_deadzone = 0.03
        self._visual_gamma = 1.55
        self._visual_transient_gain = 0.45

        # Connection health metrics
        self._dj_connects = 0
        self._dj_disconnects = 0
        self._browser_connects = 0
        self._browser_disconnects = 0
        self._mc_reconnect_count = 0
        self._health_log_task: Optional[asyncio.Task] = None
        self._last_health_log = time.time()
        self._last_profile_log = time.monotonic()

        # Metrics tracking
        self._start_time = time.time()
        self._frames_processed = 0
        self._pattern_changes = 0

        # Visual delay buffer for audio-visual sync
        self._visual_delay_ms: float = max(0.0, min(500.0, visual_delay_ms))
        self._visual_delay_mode: str = (
            visual_delay_mode
            if visual_delay_mode in ("manual", "auto", "discord", "svc")
            else "manual"
        )

        # Beat predictor for phase-locked beat firing during delayed playback
        self._beat_predictor = BeatPredictor()

        # Ableton Link integration
        self._link_enabled = enable_link and HAS_LINK
        self._link: Optional[Any] = None
        self._link_task: Optional[asyncio.Task] = None
        self._link_peers: int = 0
        self._link_tempo: float = 0.0
        self._link_beat_phase: float = 0.0

        # Bloom / ambient-light state (controlled by admin panel)
        self._bloom_enabled: bool = True
        self._bloom_strength: float = 0.4
        self._bloom_threshold: float = 0.5
        self._ambient_lights_enabled: bool = True

        if self._link_enabled:
            try:
                self._link = aalink.Link(120.0)
                self._link.enabled = True
                logger.info("Ableton Link enabled (waiting for peers)")
            except Exception as e:
                logger.warning(f"Failed to initialize Ableton Link: {e}")
                self._link_enabled = False
                self._link = None

        # Fallback state (used when no DJ is active)
        self._fallback_bands = [0.0] * 5
        self._fallback_peak = 0.0
        self._last_frame_time = time.time()
        # Live profiling for end-to-end frame timing.
        self._live_profile_enabled = True
        self._live_profile_interval_sec = 10.0
        self._profile_samples = deque(maxlen=300)
        self._latest_perf_snapshot: Dict[str, Any] = {
            "enabled": True,
            "window_samples": 0,
            "entities_avg": 0.0,
            "frame_ms_avg": 0.0,
            "frame_ms_max": 0.0,
            "calc_ms_avg": 0.0,
            "effects_ms_avg": 0.0,
            "mc_ms_avg": 0.0,
            "broadcast_ms_avg": 0.0,
            "sleep_ms_avg": 0.0,
            "loop_hz_avg": 0.0,
        }

        # DJ banner profiles: dj_id -> banner config dict
        self._dj_banner_profiles: Dict[str, dict] = {}
        self._load_banner_profiles()

        # Pattern hot-reload
        self._pattern_hot_reload_enabled = True  # Can be disabled via CLI arg
        self._pattern_hot_reload_task: Optional[asyncio.Task] = None
        self._pattern_file_mtimes: Dict[str, float] = {}  # filename -> mtime

    def get_health_stats(self) -> dict:
        """Get connection health statistics.

        Returns a dictionary with counters for:
        - dj_connects: Total DJ connections since server start
        - dj_disconnects: Total DJ disconnections since server start
        - browser_connects: Total browser client connections since server start
        - browser_disconnects: Total browser client disconnections since server start
        - mc_reconnect_count: Total Minecraft reconnections since server start
        - current_djs: Current number of connected DJs
        - current_browsers: Current number of connected browser clients
        - mc_connected: Whether Minecraft is currently connected
        """
        # Snapshot mutable collections to avoid races with async DJ handlers
        djs_count = len(self._djs)
        browsers_count = len(self._broadcast_clients)
        return {
            "dj_connects": self._dj_connects,
            "dj_disconnects": self._dj_disconnects,
            "browser_connects": self._browser_connects,
            "browser_disconnects": self._browser_disconnects,
            "mc_reconnect_count": self._mc_reconnect_count,
            "current_djs": djs_count,
            "current_browsers": browsers_count,
            "mc_connected": self.viz_client is not None and self.viz_client.connected,
        }

    def _update_live_profile(
        self,
        frame_ms: float,
        calc_ms: float,
        effects_ms: float,
        mc_ms: float,
        broadcast_ms: float,
        sleep_ms: float,
        entities_count: int,
    ) -> None:
        """Track rolling frame timings and emit periodic profiling logs."""
        if not self._live_profile_enabled:
            return

        sample = {
            "frame_ms": float(frame_ms),
            "calc_ms": float(calc_ms),
            "effects_ms": float(effects_ms),
            "mc_ms": float(mc_ms),
            "broadcast_ms": float(broadcast_ms),
            "sleep_ms": float(sleep_ms),
            "entities": float(entities_count),
        }
        self._profile_samples.append(sample)
        n = len(self._profile_samples)
        if n <= 0:
            return

        samples = list(self._profile_samples)

        def avg(key: str) -> float:
            return sum(s[key] for s in samples) / n

        frame_avg = avg("frame_ms")
        frame_max = max(s["frame_ms"] for s in samples)
        loop_hz = 1000.0 / frame_avg if frame_avg > 0.0 else 0.0
        self._latest_perf_snapshot = {
            "enabled": True,
            "window_samples": n,
            "entities_avg": round(avg("entities"), 1),
            "frame_ms_avg": round(frame_avg, 3),
            "frame_ms_max": round(frame_max, 3),
            "calc_ms_avg": round(avg("calc_ms"), 3),
            "effects_ms_avg": round(avg("effects_ms"), 3),
            "mc_ms_avg": round(avg("mc_ms"), 3),
            "broadcast_ms_avg": round(avg("broadcast_ms"), 3),
            "sleep_ms_avg": round(avg("sleep_ms"), 3),
            "loop_hz_avg": round(loop_hz, 2),
        }

        now = time.monotonic()
        if now - self._last_profile_log >= self._live_profile_interval_sec:
            p = self._latest_perf_snapshot
            logger.info(
                "[PROFILE] n=%s frame=%.2fms(max=%.2f) calc=%.2f effects=%.2f mc=%.2f "
                "broadcast=%.2f sleep=%.2f hz=%.1f entities=%.1f",
                p["window_samples"],
                p["frame_ms_avg"],
                p["frame_ms_max"],
                p["calc_ms_avg"],
                p["effects_ms_avg"],
                p["mc_ms_avg"],
                p["broadcast_ms_avg"],
                p["sleep_ms_avg"],
                p["loop_hz_avg"],
                p["entities_avg"],
            )
            self._last_profile_log = now

    async def _handle_dj_frame(self, dj: DJConnection, data: "DjAudioFrame"):
        """Process an audio frame from a DJ."""
        # Rate limit: drop excess frames (allows bursts up to 120fps, sustain ~60fps)
        if not dj.check_rate_limit():
            logger.debug(f"Rate limit: dropping frame from {dj.dj_id}")
            return

        # Validate and clamp all incoming values
        safe = _sanitize_audio_frame(data)

        dj.seq = safe["seq"]
        dj.bands = safe["bands"]
        dj.peak = safe["peak"]
        dj.is_beat = safe["beat"]
        dj.beat_intensity = safe["beat_i"]
        dj.bpm = self._stabilize_bpm(dj, safe["bpm"])
        dj.tempo_confidence = safe["tempo_conf"]
        dj.beat_phase = safe["beat_phase"]
        dj.instant_bass = safe["i_bass"]
        dj.instant_kick = safe["i_kick"]
        dj.last_frame_at = time.time()
        dj.frame_count += 1
        dj.update_fps()

        # Track jitter from frame arrival times
        now = dj.last_frame_at
        if dj._last_frame_arrival > 0:
            gap = (now - dj._last_frame_arrival) * 1000.0  # ms
            dj._frame_gaps.append(gap)
            if len(dj._frame_gaps) >= 4:
                mean_gap = sum(dj._frame_gaps) / len(dj._frame_gaps)
                variance = sum((g - mean_gap) ** 2 for g in dj._frame_gaps) / len(dj._frame_gaps)
                jitter = variance**0.5
                dj._jitter_ms = dj._jitter_ms * 0.8 + jitter * 0.2
        dj._last_frame_arrival = now

        # Append timestamped frame to buffer (for visual delay feature)
        frame_data_snapshot = {
            "bands": list(dj.bands),
            "peak": dj.peak,
            "is_beat": dj.is_beat,
            "beat_intensity": dj.beat_intensity,
            "bpm": dj.bpm,
            "tempo_confidence": dj.tempo_confidence,
            "beat_phase": dj.beat_phase,
            "instant_bass": dj.instant_bass,
            "instant_kick": dj.instant_kick,
        }
        dj._frame_buffer.append((now, frame_data_snapshot))

        # Adaptive buffer trimming: keep only what's needed + margin
        effective_delay = self._get_effective_delay_ms(dj)
        max_frames = max(60, int(effective_delay / 16.0 * 2))
        while len(dj._frame_buffer) > max_frames:
            dj._frame_buffer.popleft()

        # Feed beats to predictor when this is the active DJ
        if dj.dj_id == self._active_dj_id:
            self._beat_predictor.process_onset(dj.is_beat, dj.beat_intensity)

        # Calculate latency if timestamp provided
        # Apply clock offset to correct for clock skew between DJ and server
        ts = safe["ts"]
        if ts is not None and isinstance(ts, (int, float)) and math.isfinite(ts):
            dj_timestamp = float(ts)
            server_time = time.time()

            # Adjust DJ timestamp by clock offset (offset is DJ_time - server_time)
            # So corrected_dj_time = dj_timestamp - clock_offset gives equivalent server time
            if dj.clock_sync_done:
                corrected_dj_time = dj_timestamp - dj.clock_offset
                latency = (server_time - corrected_dj_time) * 1000
            else:
                # Fallback to raw calculation if sync not done
                latency = (server_time - dj_timestamp) * 1000

            latency = max(0.0, min(latency, 60000.0))  # Clamp to [0, 60s]
            # Smooth pipeline latency with EMA to prevent spikes from event loop stalls
            if dj.pipeline_latency_ms > 0:
                dj.pipeline_latency_ms = dj.pipeline_latency_ms * 0.8 + latency * 0.2
            else:
                dj.pipeline_latency_ms = latency
            # Backward-compatible display metric: prefer network RTT when available.
            if dj.network_rtt_ms > 0:
                dj.latency_ms = dj.network_rtt_ms
            else:
                dj.latency_ms = dj.pipeline_latency_ms

    def _apply_clock_resync(self, dj: DJConnection, data: dict) -> None:
        """Process an incoming clock_sync_response for periodic drift correction."""
        t4 = time.time()
        t2 = data.get("dj_recv_time")
        t3 = data.get("dj_send_time")
        if (
            not isinstance(t2, (int, float))
            or not isinstance(t3, (int, float))
            or not math.isfinite(t2)
            or not math.isfinite(t3)
        ):
            return
        # Estimate the server_time (t1) from the response's context
        t1 = data.get("server_time")
        if not isinstance(t1, (int, float)) or not math.isfinite(t1):
            return
        rtt = (t4 - t1) - (t3 - t2)
        if rtt < 0 or rtt > 30:
            return
        new_offset = ((t2 - t1) + (t3 - t4)) / 2
        old_offset = dj.clock_offset
        # Blend with existing offset using EMA (slow adaptation)
        dj.clock_offset = old_offset * 0.9 + new_offset * 0.1
        dj._clock_sync_count += 1
        # Track drift rate (offset change per second)
        if dj._last_clock_resync > 0:
            elapsed = t4 - dj._last_clock_resync
            if elapsed > 1.0:
                drift_ms_per_sec = abs(new_offset - old_offset) * 1000.0 / elapsed
                dj._clock_drift_rate = dj._clock_drift_rate * 0.7 + drift_ms_per_sec * 0.3
                # Warn if drift > 5ms/minute (0.083ms/sec)
                if dj._clock_drift_rate > 0.083:
                    logger.warning(
                        f"[CLOCK DRIFT] {dj.dj_name}: drift={dj._clock_drift_rate * 60:.1f}ms/min"
                    )
        dj._last_clock_resync = t4
        logger.debug(
            f"[CLOCK RESYNC] {dj.dj_name}: offset={dj.clock_offset * 1000:.1f}ms, "
            f"RTT={rtt * 1000:.1f}ms, syncs={dj._clock_sync_count}"
        )

    def _calculate_sync_confidence(self, dj: DJConnection) -> float:
        """Calculate sync quality score 0-100 for a DJ connection.

        Score based on 4 components (25 pts each):
        - Clock sync freshness (penalty if > 5 min since last sync)
        - RTT stability (low variance = good)
        - Beat predictor phase lock status
        - Frame delivery consistency (low jitter)
        """
        score = 0.0

        # 1. Clock sync age (0-25): full marks if < 60s, zero if > 300s
        if dj.clock_sync_done and dj._last_clock_resync > 0:
            age = time.time() - dj._last_clock_resync
            if age < 60:
                score += 25.0
            elif age < 300:
                score += 25.0 * (1.0 - (age - 60) / 240.0)
            # else: 0 points
        # No sync done = 0 points

        # 2. RTT stability (0-25): based on variance of recent RTT samples
        if len(dj._rtt_samples) >= 3:
            samples = list(dj._rtt_samples)
            mean_rtt = sum(samples) / len(samples)
            variance = sum((s - mean_rtt) ** 2 for s in samples) / len(samples)
            std_dev = variance**0.5
            # < 5ms std dev = full marks, > 50ms = 0
            if std_dev < 5.0:
                score += 25.0
            elif std_dev < 50.0:
                score += 25.0 * (1.0 - (std_dev - 5.0) / 45.0)
        elif dj.network_rtt_ms > 0:
            # Few samples but have RTT - give partial credit
            score += 10.0

        # 3. Beat predictor phase lock (0-25)
        if self._beat_predictor.is_phase_locked:
            score += 25.0
        elif self._beat_predictor.tempo_confidence > 0.5:
            score += 25.0 * self._beat_predictor.tempo_confidence

        # 4. Frame delivery consistency (0-25): based on jitter
        if dj._jitter_ms < 3.0:
            score += 25.0
        elif dj._jitter_ms < 20.0:
            score += 25.0 * (1.0 - (dj._jitter_ms - 3.0) / 17.0)
        # else: 0 for high jitter

        return max(0.0, min(100.0, score))

    def _get_effective_delay_ms(self, dj: Optional[DJConnection] = None) -> float:
        """Calculate effective visual delay based on mode and DJ metrics."""
        mode = self._visual_delay_mode
        if mode == "manual":
            return self._visual_delay_ms
        if dj is None:
            return self._visual_delay_ms

        rtt_half = (dj.network_rtt_ms / 2.0) if dj.network_rtt_ms > 0 else 0.0
        if mode == "discord":
            return 200.0 + rtt_half
        elif mode == "svc":
            return 80.0 + rtt_half
        elif mode == "auto":
            return dj.pipeline_latency_ms + 80.0
        return self._visual_delay_ms

    def _read_delayed_frame(self, dj: DJConnection, delay_ms: float) -> Optional[dict]:
        """Read a frame from the DJ's buffer at `now - delay_ms`.

        Uses bisect for O(log n) lookup on timestamps extracted from the buffer.
        Returns an audio state dict matching the main loop's expected fields,
        or None if the buffer is empty or delay is zero (use live state).
        """
        if delay_ms <= 0 or not dj._frame_buffer:
            return None

        buf = dj._frame_buffer
        target_time = time.time() - (delay_ms / 1000.0)

        # Extract timestamps from buffer tuples for bisect lookup.
        # Buffer is typically 60-100 entries, so this is cheap.
        timestamps = [t for t, _ in buf]

        # bisect_left finds insertion point for target_time in sorted timestamps
        idx = bisect.bisect_left(timestamps, target_time)

        # Choose the closest frame (idx-1 or idx)
        best_idx = None
        if idx == 0:
            best_idx = 0
        elif idx >= len(timestamps):
            best_idx = len(timestamps) - 1
        else:
            # Compare neighbours
            if abs(timestamps[idx] - target_time) < abs(timestamps[idx - 1] - target_time):
                best_idx = idx
            else:
                best_idx = idx - 1

        if best_idx is not None and best_idx < len(buf):
            return buf[best_idx][1]
        return None

    def _stabilize_bpm(self, dj: DJConnection, raw_bpm: float) -> float:
        """Normalize octave errors and smooth BPM per-DJ."""
        if not isinstance(raw_bpm, (int, float)) or not math.isfinite(raw_bpm):
            return dj.bpm if dj.bpm > 0 else 120.0

        raw = float(raw_bpm)
        prev = dj.bpm if 40.0 <= dj.bpm <= 240.0 else 120.0

        # Consider half/double-time candidates and choose closest to prior.
        candidates = [raw, raw * 2.0, raw * 0.5]
        valid = [c for c in candidates if 60.0 <= c <= 200.0]
        if not valid:
            valid = [max(60.0, min(200.0, raw))]
        chosen = min(valid, key=lambda c: abs(c - prev))

        # Apply bounded smoothing to avoid jumpy UI.
        alpha = 0.25 if abs(chosen - prev) > 8.0 else 0.4
        bpm = (1.0 - alpha) * prev + alpha * chosen
        return max(60.0, min(200.0, bpm))

    def _apply_phase_beat_assist(
        self, dj: DJConnection, is_beat: bool, beat_intensity: float
    ) -> tuple:
        """Use explicit DJ phase/confidence to fill missed beats conservatively."""
        if is_beat:
            dj.phase_assist_last_time = time.time()
            return is_beat, beat_intensity

        if dj.tempo_confidence < 0.60 or dj.bpm < 60.0:
            return is_beat, beat_intensity

        beat_period = 60.0 / max(60.0, dj.bpm)
        phase = max(0.0, min(1.0, dj.beat_phase))
        near_boundary = phase < 0.08 or phase > 0.92
        now = time.time()
        can_fire = dj.phase_assist_last_time <= 0.0 or (now - dj.phase_assist_last_time) >= (
            beat_period * 0.60
        )
        if near_boundary and can_fire:
            dj.phase_assist_last_time = now
            assisted_intensity = max(beat_intensity, min(1.0, 0.50 + dj.tempo_confidence * 0.25))
            return True, assisted_intensity

        return is_beat, beat_intensity

    def _enhance_visual_state(
        self,
        bands: List[float],
        peak: float,
        is_beat: bool,
        beat_intensity: float,
        instant_bass: float,
        instant_kick: bool,
        dt: float = 0.016,
    ) -> tuple:
        """Shape audio state for visuals: less wobble, higher contrast, snappier transients."""
        dt_ratio = dt / 0.016
        shaped = [0.0] * 5
        for i in range(5):
            src = bands[i] if i < len(bands) else 0.0
            src = max(0.0, min(1.0, float(src)))
            prev = self._visual_band_state[i]

            # Gate tiny fluctuations, then apply contrast curve.
            gated = max(0.0, src - self._visual_deadzone) / (1.0 - self._visual_deadzone)
            contrasted = gated**self._visual_gamma

            # Emphasize rising edges to reduce mushy wobble.
            transient = max(0.0, contrasted - prev)
            enhanced = min(1.0, contrasted + transient * self._visual_transient_gain)

            # Beat punch focused on bass/low-mid for stronger rhythmic definition.
            beat_punch = max(beat_intensity * 0.12, instant_bass * 0.16)
            if instant_kick:
                beat_punch += 0.14
            if i <= 1 and beat_punch > 0.0:
                enhanced = min(1.0, enhanced + beat_punch)

            # Fast attack, controlled release: responsive but still smooth.
            # dt-aware so smoothing is consistent regardless of frame rate.
            base_alpha = 0.85 if enhanced > prev else 0.42
            alpha = 1.0 - (1.0 - base_alpha) ** dt_ratio
            out = prev + (enhanced - prev) * alpha
            shaped[i] = out
            self._visual_band_state[i] = out

        shaped_peak = max(float(peak), max(shaped) * 1.15, instant_bass * 0.75)
        shaped_peak = max(0.0, min(5.0, shaped_peak))
        shaped_beat = max(
            float(beat_intensity), instant_bass * 0.55 + (0.25 if instant_kick else 0.0)
        )
        shaped_beat = max(0.0, min(5.0, shaped_beat))
        return shaped, shaped_peak, shaped_beat

    # -- Relay/broadcast methods are in relay.py (RelayMixin) --
    # _handle_browser_client, _broadcast_dj_roster, _broadcast_to_browsers,
    # _broadcast_minecraft_status, _broadcast_pattern_change, _broadcast_pattern_sync_to_djs,
    # _broadcast_config_sync_to_djs, _broadcast_config_to_browsers, _broadcast_preset_to_djs,
    # _broadcast_effect_trigger, _broadcast_to_djs, _send_with_timeout, _broadcast_viz_state,
    # connect_minecraft, _minecraft_reconnect_loop, _browser_heartbeat_loop,
    # _update_minecraft, _update_minecraft_zone, _relay_voice_audio,
    # _forward_voice_config, _broadcast_voice_status, _link_sync_loop

    async def _main_loop(self):
        """Main visualization loop."""
        frame_interval = 0.016  # 60 FPS simulation/preview loop
        mc_frame_interval = 0.05  # 20 TPS-aligned Minecraft update pacing
        next_mc_send_at = time.monotonic()
        next_frame_at = time.perf_counter()
        last_frame_start = time.perf_counter()
        consecutive_errors = 0
        max_consecutive_errors = 50  # After 50 errors in a row, slow down

        while self._running:
            try:
                frame_start = time.perf_counter()
                loop_dt = min(frame_start - last_frame_start, 0.05)  # Cap at 50ms
                last_frame_start = frame_start
                calc_ms = 0.0
                effects_ms = 0.0
                mc_ms = 0.0
                broadcast_ms = 0.0
                entities_count = 0
                self._frame_count += 1
                self._frames_processed += 1

                # Get audio from active DJ or use fallback
                dj = self.active_dj
                if dj:
                    # Check for delayed frame (audio-visual sync)
                    effective_delay = self._get_effective_delay_ms(dj)
                    delayed = self._read_delayed_frame(dj, effective_delay)
                    if delayed:
                        bands = delayed["bands"]
                        peak = delayed["peak"]
                        is_beat = delayed["is_beat"]
                        beat_intensity = delayed["beat_intensity"]
                        instant_bass = delayed["instant_bass"]
                        instant_kick = delayed["instant_kick"]
                        tempo_confidence = delayed["tempo_confidence"]
                        beat_phase = delayed["beat_phase"]
                    else:
                        bands = dj.bands
                        peak = dj.peak
                        is_beat = dj.is_beat
                        beat_intensity = dj.beat_intensity
                        instant_bass = dj.instant_bass
                        instant_kick = dj.instant_kick
                        tempo_confidence = dj.tempo_confidence
                        beat_phase = dj.beat_phase
                    is_beat, beat_intensity = self._apply_phase_beat_assist(
                        dj, is_beat, beat_intensity
                    )

                    # Beat prediction: when delay is active, use predictor for tighter sync
                    if effective_delay > 0 and self._beat_predictor.tempo_confidence > 0.6:
                        self._beat_predictor.prediction_lookahead = effective_delay / 1000.0
                        predicted_fire, predicted_intensity = (
                            self._beat_predictor.should_fire_beat()
                        )
                        if predicted_fire:
                            is_beat = True
                            beat_intensity = max(beat_intensity, predicted_intensity)
                else:
                    # No active DJ - fade to silence
                    bands = self._fallback_bands
                    peak = self._fallback_peak
                    is_beat = False
                    beat_intensity = 0.0
                    instant_bass = 0.0
                    instant_kick = False
                    tempo_confidence = 0.0
                    beat_phase = 0.0

                    # Decay fallback values (dt-aware for consistent fade rate)
                    decay = 0.95 ** (loop_dt / 0.016)
                    for i in range(5):
                        self._fallback_bands[i] *= decay
                    self._fallback_peak *= decay

                # Apply band sensitivity
                adjusted_bands = [
                    bands[i] * self._band_sensitivity[i] for i in range(min(5, len(bands)))
                ]
                visual_bands, visual_peak, visual_beat_intensity = self._enhance_visual_state(
                    adjusted_bands,
                    peak,
                    is_beat,
                    beat_intensity,
                    instant_bass,
                    instant_kick,
                    dt=loop_dt,
                )

                # Send to Minecraft whenever the MC plugin is connected.
                # Patterns run fine with silent audio (idle animations),
                # so no need to gate on DJ presence.
                should_send_to_mc = self.viz_client is not None and self.viz_client.connected

                # Clean up expired effects
                now = time.time()
                expired = [k for k, v in self._active_effects.items() if now >= v["end_time"]]
                for k in expired:
                    if k == "blackout":
                        self._blackout = False
                        if self.viz_client and self.viz_client.connected:
                            try:
                                asyncio.ensure_future(self.viz_client.set_visible(self.zone, True))
                            except Exception:
                                pass
                    elif k == "freeze":
                        self._freeze = False
                    del self._active_effects[k]

                # Pace Minecraft sends to a stable 20Hz cadence.
                now_mono = time.monotonic()
                should_send_mc_this_frame = False
                if should_send_to_mc:
                    if now_mono >= next_mc_send_at:
                        should_send_mc_this_frame = True
                        while next_mc_send_at <= now_mono:
                            next_mc_send_at += mc_frame_interval
                else:
                    # Reset cadence when MC relay is inactive (e.g., DJ direct path).
                    next_mc_send_at = now_mono + mc_frame_interval

                # Calculate entities only when needed (MC send tick or active browser previews).
                need_entities = should_send_mc_this_frame or bool(self._broadcast_clients)
                # Per-zone entity calculation
                zone_entities: Dict[str, List[dict]] = {}
                if need_entities:
                    audio_state = AudioState(
                        bands=visual_bands,
                        amplitude=visual_peak,
                        is_beat=is_beat,
                        beat_intensity=visual_beat_intensity,
                        frame=self._frame_count,
                        bpm=dj.bpm if dj else 0.0,
                        beat_phase=beat_phase,
                    )
                    calc_start = time.perf_counter()
                    # Collect zones that need Lua calculation
                    calc_tasks = {}
                    for zone_name, zone_state in self._zone_patterns.items():
                        if zone_state.render_mode == "bitmap":
                            zone_entities[zone_name] = []  # Bitmap zones render on MC side
                            continue
                        if self._freeze and zone_state.last_entities:
                            zone_entities[zone_name] = zone_state.last_entities
                        elif self._blackout:
                            zone_entities[zone_name] = []
                        else:
                            calc_tasks[zone_name] = self._calculate_entities_for_zone(
                                zone_state, audio_state, zone_name
                            )
                    if calc_tasks:
                        results = await asyncio.gather(*calc_tasks.values())
                        for zn, zents in zip(calc_tasks.keys(), results):
                            zents = self._apply_effects(zents, visual_bands)
                            self._zone_patterns[zn].last_entities = zents
                            zone_entities[zn] = zents
                    calc_ms = (time.perf_counter() - calc_start) * 1000.0

                    # Apply per-band material overrides to entities
                    if any(self._band_materials):
                        for zents in zone_entities.values():
                            for ent in zents:
                                if "material" not in ent:
                                    band = ent.get("band", 0)
                                    if 0 <= band < 5:
                                        mat = self._band_materials[band]
                                        if mat:
                                            ent["material"] = mat

                # For backward compat: entities for the active zone (used by browser broadcast)
                entities = zone_entities.get(self.zone, [])
                entities_count = len(entities)

                # Update spectrograph
                if self.spectrograph:
                    mode_str = "VJ"
                    if dj:
                        if dj.direct_mode:
                            mc_status = "MC:OK" if dj.mc_connected else "MC:?"
                            mode_str = f"VJ:DIRECT ({mc_status})"
                        else:
                            mode_str = "VJ:RELAY"
                    self.spectrograph.set_stats(
                        preset=mode_str,
                        bpm=dj.bpm if dj else 0,
                        clients=len(self._broadcast_clients),
                        using_fft=True,
                    )
                    self.spectrograph.display(
                        bands=bands,
                        amplitude=peak,
                        is_beat=is_beat,
                        beat_intensity=beat_intensity,
                    )

                if should_send_mc_this_frame:
                    mc_start = time.perf_counter()
                    for zn, zents in zone_entities.items():
                        zs = self._zone_patterns.get(zn)
                        await self._update_minecraft_zone(
                            zn,
                            zs,
                            zents,
                            visual_bands,
                            visual_peak,
                            is_beat,
                            visual_beat_intensity,
                            bpm=dj.bpm if dj else 0.0,
                            tempo_confidence=tempo_confidence,
                            beat_phase=beat_phase,
                        )
                    mc_ms = (time.perf_counter() - mc_start) * 1000.0

                # Send to browser clients at ~20fps (every 3rd frame) to avoid
                # overwhelming slow clients. Beats always send immediately.
                broadcast_start = time.perf_counter()
                should_broadcast = is_beat or (self._frame_count % 3 == 0)
                if should_broadcast:
                    await self._broadcast_viz_state(
                        entities,
                        visual_bands,
                        visual_peak,
                        is_beat,
                        visual_beat_intensity,
                        instant_bass,
                        instant_kick,
                        tempo_confidence,
                        beat_phase,
                        zone_entities=zone_entities if len(zone_entities) > 1 else None,
                    )
                broadcast_ms = (time.perf_counter() - broadcast_start) * 1000.0

                # Log health summary every 60 seconds
                current_time = time.time()
                if current_time - self._last_health_log >= 60.0:
                    stats = self.get_health_stats()
                    logger.info(
                        f"[HEALTH] DJs: {stats['current_djs']} (conn={stats['dj_connects']}, disc={stats['dj_disconnects']}) | "
                        f"Browsers: {stats['current_browsers']} (conn={stats['browser_connects']}, disc={stats['browser_disconnects']}) | "
                        f"MC: {'connected' if stats['mc_connected'] else 'disconnected'} (reconn={stats['mc_reconnect_count']})"
                    )
                    self._last_health_log = current_time

                consecutive_errors = 0  # Reset on successful frame
                next_frame_at += frame_interval
                sleep_for = next_frame_at - time.perf_counter()
                if sleep_for < 0.0:
                    # If we fell behind, skip sleeping and realign on large stalls.
                    if sleep_for < -0.25:
                        next_frame_at = time.perf_counter()
                    sleep_for = 0.0
                # Enforce minimum sleep to prevent busy-spinning when idle
                if sleep_for < 0.001:
                    sleep_for = 0.001

                sleep_start = time.perf_counter()
                await asyncio.sleep(sleep_for)
                sleep_ms = (time.perf_counter() - sleep_start) * 1000.0
                frame_ms = (time.perf_counter() - frame_start) * 1000.0
                self._update_live_profile(
                    frame_ms=frame_ms,
                    calc_ms=calc_ms,
                    effects_ms=effects_ms,
                    mc_ms=mc_ms,
                    broadcast_ms=broadcast_ms,
                    sleep_ms=sleep_ms,
                    entities_count=entities_count,
                )

            except asyncio.CancelledError:
                raise  # Let cancellation propagate
            except Exception as e:
                consecutive_errors += 1
                if consecutive_errors <= 3 or consecutive_errors % 100 == 0:
                    logger.error(f"Main loop error (#{consecutive_errors}): {e}", exc_info=True)
                if consecutive_errors >= max_consecutive_errors:
                    # Slow down to avoid log spam on persistent errors
                    await asyncio.sleep(1.0)
                else:
                    await asyncio.sleep(frame_interval)

    async def run(self):
        """Start the VJ server."""
        if not HAS_WEBSOCKETS:
            logger.error("websockets not installed. Run: pip install websockets")
            return

        self._running = True

        # Start HTTP server for admin panel
        if self.http_port > 0:
            project_root = Path(__file__).parent.parent
            http_thread = threading.Thread(
                target=run_http_server,
                args=(self.http_port, str(project_root)),
                daemon=True,
            )
            http_thread.start()
            logger.info(f"Admin panel: http://localhost:{self.http_port}/")
            logger.info(f"3D Preview: http://localhost:{self.http_port}/preview/")

        # Start DJ listener (64KB max message â€" valid audio frames are ~200 bytes)
        dj_server = await ws_serve(
            self._handle_dj_connection,
            "0.0.0.0",
            self.dj_port,
            max_size=65_536,
        )
        logger.info(f"DJ WebSocket server: ws://localhost:{self.dj_port}")

        # Start browser broadcast server (64KB — browsers only receive viz data)
        broadcast_server = await ws_serve(
            self._handle_browser_client,
            "0.0.0.0",
            self.broadcast_port,
            max_size=65_536,
        )
        logger.info(f"Browser WebSocket: ws://localhost:{self.broadcast_port}")

        # Start metrics HTTP server if enabled
        metrics_server = None
        if self.metrics_port is not None:
            from vj_server.metrics import start_metrics_server

            metrics_server = await start_metrics_server(self, self.metrics_port)

        # Register with coordinator if configured
        await self._init_coordinator()

        _ft = getattr(sys, "_is_gil_enabled", None)
        _ft_label = f"free-threaded (GIL={'on' if _ft() else 'off'})" if _ft else "standard"
        logger.info(
            "VJ Server ready. Python %s (%s), async_lua=%s",
            sys.version.split()[0],
            _ft_label,
            _USE_ASYNC_LUA,
        )

        # Start Minecraft reconnection loop (runs in background)
        # Skip when --no-minecraft is set to avoid spamming connection attempts
        if not self._skip_minecraft:
            self._mc_reconnect_task = asyncio.create_task(self._minecraft_reconnect_loop())
            logger.debug("Started Minecraft reconnection monitor")
        else:
            logger.info("Minecraft reconnection disabled (--no-minecraft mode)")

        # Start browser heartbeat loop (runs in background)
        self._browser_heartbeat_task = asyncio.create_task(self._browser_heartbeat_loop())
        logger.debug("Started browser heartbeat monitor")

        # Start pattern hot-reload loop (runs in background)
        if self._pattern_hot_reload_enabled:
            self._pattern_hot_reload_task = asyncio.create_task(self._pattern_hot_reload_loop())
            logger.info("Pattern hot-reload enabled (checking every 2.5s)")
        else:
            logger.info("Pattern hot-reload disabled")

        # Start Ableton Link sync loop (runs in background)
        if self._link_enabled and self._link is not None:
            self._link_task = asyncio.create_task(self._link_sync_loop())
            logger.info("Ableton Link sync loop started")

        # Register signal handlers for graceful shutdown
        import signal as _signal

        loop = asyncio.get_running_loop()
        for sig in (_signal.SIGINT, _signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, self.stop)
            except NotImplementedError:
                # Windows doesn't support add_signal_handler for all signals
                pass

        try:
            await self._main_loop()
        finally:
            # Notify all connected clients of shutdown
            logger.info("Shutting down VJ server...")
            shutdown_msg = json.dumps({"type": "server_shutdown"})
            drain_tasks = []
            for ws in list(self._broadcast_clients):
                drain_tasks.append(asyncio.wait_for(ws.send(shutdown_msg), timeout=2.0))
            async with self._dj_lock:
                for dj in self._djs.values():
                    if dj.websocket:
                        drain_tasks.append(
                            asyncio.wait_for(dj.websocket.send(shutdown_msg), timeout=2.0)
                        )
            if drain_tasks:
                await asyncio.gather(*drain_tasks, return_exceptions=True)
                logger.info("Notified %d clients of shutdown", len(drain_tasks))

            # Cancel all background tasks with timeout
            bg_tasks = [
                t
                for t in [
                    self._mc_reconnect_task,
                    self._browser_heartbeat_task,
                    getattr(self, "_pattern_hot_reload_task", None),
                    self._coordinator_heartbeat_task,
                    self._link_task,
                    getattr(self, "_health_log_task", None),
                ]
                if t is not None and not t.done()
            ]
            for task in bg_tasks:
                task.cancel()
            if bg_tasks:
                await asyncio.wait(bg_tasks, timeout=5.0)
                logger.info("Cancelled %d background tasks", len(bg_tasks))

            # Disable Ableton Link
            if self._link is not None:
                try:
                    self._link.enabled = False
                except Exception:
                    pass

            # Close WebSocket servers
            dj_server.close()
            broadcast_server.close()
            if metrics_server:
                metrics_server.close()
                await metrics_server.wait_closed()
            await dj_server.wait_closed()
            await broadcast_server.wait_closed()
            logger.info("VJ server shutdown complete")

    def stop(self):
        """Stop the server."""
        self._running = False

    async def cleanup(self):
        """Clean up resources."""
        # Cancel reconnect task if running
        if self._mc_reconnect_task and not self._mc_reconnect_task.done():
            self._mc_reconnect_task.cancel()
            try:
                await self._mc_reconnect_task
            except asyncio.CancelledError:
                pass

        # Cancel browser heartbeat task if running
        if self._browser_heartbeat_task and not self._browser_heartbeat_task.done():
            self._browser_heartbeat_task.cancel()
            try:
                await self._browser_heartbeat_task
            except asyncio.CancelledError:
                pass

        # Cancel pattern hot-reload task if running
        if self._pattern_hot_reload_task and not self._pattern_hot_reload_task.done():
            self._pattern_hot_reload_task.cancel()
            try:
                await self._pattern_hot_reload_task
            except asyncio.CancelledError:
                pass

        if self.viz_client and self.viz_client.connected:
            await self.viz_client.set_visible(self.zone, False)
            await self.viz_client.disconnect()

        if self.spectrograph:
            self.spectrograph.clear()


def _validate_port(value: str) -> int:
    """Validate port number is in valid range."""
    try:
        port = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"Invalid port number: {value}")
    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError(f"Port must be between 1 and 65535, got: {port}")
    return port


def _validate_positive_int(value: str) -> int:
    """Validate positive integer."""
    try:
        num = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"Invalid integer: {value}")
    if num <= 0:
        raise argparse.ArgumentTypeError(f"Value must be positive, got: {num}")
    return num


async def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(description="VJ Server - Multi-DJ Audio Visualization")
    parser.add_argument(
        "--dj-port",
        type=_validate_port,
        default=9000,
        help="Port for DJ connections (default: 9000)",
    )
    parser.add_argument(
        "--broadcast-port",
        type=_validate_port,
        default=8766,
        help="Port for browser clients (default: 8766)",
    )
    parser.add_argument(
        "--http-port",
        type=_validate_port,
        default=8080,
        help="HTTP port for admin panel (default: 8080)",
    )
    parser.add_argument(
        "--minecraft-host",
        "--host",
        type=str,
        default="localhost",
        help="Minecraft server host",
    )
    parser.add_argument(
        "--port", type=_validate_port, default=8765, help="Minecraft WebSocket port"
    )
    parser.add_argument("--zone", type=str, default="main", help="Visualization zone")
    parser.add_argument("--entities", type=_validate_positive_int, default=16, help="Entity count")
    parser.add_argument(
        "--config",
        type=str,
        default="configs/dj_auth.json",
        help="Path to DJ auth config",
    )
    parser.add_argument(
        "--no-auth", action="store_true", help="Disable DJ authentication (demo/dev only)"
    )
    parser.add_argument("--no-minecraft", action="store_true", help="Run without Minecraft")
    parser.add_argument(
        "--no-spectrograph", action="store_true", help="Disable terminal spectrograph"
    )
    parser.add_argument(
        "--no-hot-reload",
        action="store_true",
        help="Disable pattern hot-reload (default: enabled)",
    )

    args = parser.parse_args()

    # Load auth config
    auth_config = None
    config_path = Path(args.config)
    if config_path.exists():
        auth_config = DJAuthConfig.load(str(config_path))
        logger.info(
            f"Loaded auth config: {len(auth_config.djs)} DJs, {len(auth_config.vj_operators)} VJs"
        )
    elif not args.no_auth:
        logger.error(f"Auth config not found: {args.config}")
        sys.exit(1)

    # Create server
    server = VJServer(
        dj_port=args.dj_port,
        broadcast_port=args.broadcast_port,
        http_port=args.http_port,
        minecraft_host=args.minecraft_host,
        minecraft_port=args.port,
        zone=args.zone,
        entity_count=args.entities,
        auth_config=auth_config,
        require_auth=not args.no_auth,
        show_spectrograph=sys.stdout.isatty() and not args.no_spectrograph,
    )

    # Signal handling
    def signal_handler(sig, frame):
        server.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    # Configure hot-reload
    if args.no_hot_reload:
        server._pattern_hot_reload_enabled = False

    # Connect to Minecraft
    if args.no_minecraft:
        server._skip_minecraft = True
        logger.info("Running without Minecraft (--no-minecraft)")
    else:
        if not await server.connect_minecraft():
            logger.warning("Continuing without Minecraft...")

    try:
        await server.run()
    finally:
        await server.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
