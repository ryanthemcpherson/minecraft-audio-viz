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
import logging
import math
import os
import re
import signal
import sys
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

import msgspec
import msgspec.json as mjson

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    import websockets
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
from vj_server.config import PRESETS as AUDIO_PRESETS
from vj_server.coordinator_client import CoordinatorClient
from vj_server.dj_manager import DJManagerMixin
from vj_server.models import (
    _USE_ASYNC_LUA,
    FIRE_AND_FORGET,
    FORWARD_TO_MINECRAFT,
    ConnectCode,
    DjAudioFrame,
    DJAuthConfig,
    DJConnection,
    VizStateBroadcast,
    ZonePatternState,
    ZoneStatus,
    _json_str,
    _sanitize_audio_frame,
    _sanitize_entities,
    run_http_server,
)
from vj_server.patterns import (
    AudioState,
    PatternConfig,
    _lua_pattern_exists,
    get_pattern,
)
from vj_server.spectrograph import TerminalSpectrograph
from vj_server.stage_manager import StageManagerMixin
from vj_server.viz_client import VizClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("vj_server")


class VJServer(DJManagerMixin, StageManagerMixin):
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

    async def _handle_browser_client(self, websocket):
        """Handle browser preview/admin panel connection."""

        # --- VJ Authentication Gate ---
        if self.require_auth:
            try:
                raw = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                auth_data = mjson.decode(raw)
                if auth_data.get("type") != "vj_auth":
                    await websocket.close(4003, "Expected vj_auth message")
                    return
                password = auth_data.get("password", "")
                # Check against any configured VJ operator
                from vj_server.auth import verify_password

                authenticated = False
                for vj_id, vj_info in self.auth_config.vj_operators.items():
                    if verify_password(password, vj_info.get("key_hash", "")):
                        authenticated = True
                        break
                if not authenticated:
                    logger.warning(f"Browser VJ auth failed from {websocket.remote_address}")
                    await websocket.send(
                        _json_str(
                            {
                                "type": "auth_error",
                                "error": "Invalid VJ password",
                            }
                        )
                    )
                    await websocket.close(4004, "Authentication failed")
                    return
                await websocket.send(_json_str({"type": "auth_success"}))
                logger.info(f"Browser VJ auth succeeded from {websocket.remote_address}")
            except asyncio.TimeoutError:
                logger.warning(f"Browser auth timeout from {websocket.remote_address}")
                await websocket.close(4003, "Auth timeout")
                return
            except (msgspec.DecodeError, Exception) as exc:
                logger.warning(f"Browser auth error: {exc}")
                await websocket.close(4003, "Auth error")
                return

        # --- Rate limiter for state-mutating browser commands ---
        _RATE_LIMITED_COMMANDS = {
            "set_pattern",
            "set_entity_count",
            "set_block_count",
            "set_zone",
            "set_preset",
            "set_band_sensitivity",
            "set_band_materials",
            "set_audio_setting",
            "set_visual_delay",
            "set_visual_delay_mode",
            "set_transition_duration",
            "set_zone_config",
            "generate_connect_code",
            "revoke_connect_code",
            "set_active_dj",
            "kick_dj",
            "approve_dj",
            "deny_dj",
            "reorder_dj_queue",
            "trigger_effect",
            "blackout",
            "set_blackout",
            "freeze",
            "set_freeze",
            "set_banner_profile",
            "upload_banner_logo",
            "voice_config",
            "save_scene",
            "load_scene",
            "delete_scene",
        }
        _cmd_rate_tokens = 10.0
        _cmd_rate_last_refill = time.time()

        MAX_BROWSER_CLIENTS = 50
        if len(self._broadcast_clients) >= MAX_BROWSER_CLIENTS:
            await websocket.close(4003, "Connection limit reached")
            return

        self._broadcast_clients.add(websocket)
        self._browser_connects += 1
        logger.info(f"Browser client connected. Total: {len(self._broadcast_clients)}")

        # Send initial state (includes MC status and pending DJs)
        mc_connected = self.viz_client is not None and self.viz_client.connected
        pending_list = [
            {
                "dj_id": info["dj_id"],
                "dj_name": info["dj_name"],
                "waiting_since": info["waiting_since"],
                "direct_mode": info.get("direct_mode", False),
            }
            for info in self._pending_djs.values()
        ]
        await websocket.send(
            _json_str(
                {
                    "type": "vj_state",
                    "patterns": self._get_all_patterns_list(),
                    "current_pattern": self._pattern_name,
                    "entity_count": self.entity_count,
                    "zone": self.zone,
                    "zone_patterns": self._get_zone_patterns_dict(),
                    "dj_roster": self._get_dj_roster(),
                    "active_dj": self._active_dj_id,
                    "health_stats": self.get_health_stats(),
                    "minecraft_connected": mc_connected,
                    "minecraft_server_type": (
                        getattr(self.viz_client, "server_type", None) if self.viz_client else None
                    ),
                    "pending_djs": pending_list,
                    "band_materials": self._band_materials,
                    "band_materials_source": self._band_materials_source,
                    "visual_delay_ms": self._visual_delay_ms,
                    "visual_delay_mode": self._visual_delay_mode,
                    "beat_predictor_confidence": float(self._beat_predictor.tempo_confidence),
                    "beat_predictor_bpm": float(self._beat_predictor.tempo_bpm),
                    "beat_predictor_locked": bool(self._beat_predictor.is_phase_locked),
                    "banner_profiles": {
                        did: {k: v for k, v in prof.items() if k != "image_pixels"}
                        for did, prof in self._dj_banner_profiles.items()
                    },
                    "bitmap_zones": self._get_bitmap_zones_dict(),
                    "bloom_enabled": self._bloom_enabled,
                    "bloom_strength": self._bloom_strength,
                    "bloom_threshold": self._bloom_threshold,
                    "ambient_lights_enabled": self._ambient_lights_enabled,
                }
            )
        )

        try:
            async for message in websocket:
                try:
                    data = mjson.decode(message)
                    msg_type = data.get("type")

                    # Rate-limit state-mutating commands (10/sec token bucket)
                    if msg_type in _RATE_LIMITED_COMMANDS:
                        now_rl = time.time()
                        elapsed_rl = now_rl - _cmd_rate_last_refill
                        _cmd_rate_last_refill = now_rl
                        _cmd_rate_tokens = min(10.0, _cmd_rate_tokens + elapsed_rl * 10.0)
                        if _cmd_rate_tokens >= 1.0:
                            _cmd_rate_tokens -= 1.0
                        else:
                            logger.debug(f"Browser rate limit: dropping {msg_type}")
                            await websocket.send(
                                _json_str(
                                    {
                                        "type": "error",
                                        "message": "Rate limited — too many commands",
                                    }
                                )
                            )
                            continue

                    if msg_type == "ping":
                        await websocket.send(_json_str({"type": "pong"}))

                    elif msg_type == "pong":
                        # Record pong response for heartbeat tracking
                        self._browser_last_pong[websocket] = time.time()

                    elif msg_type == "get_state":
                        mc_status = self.viz_client is not None and self.viz_client.connected
                        pending = [
                            {
                                "dj_id": info["dj_id"],
                                "dj_name": info["dj_name"],
                                "waiting_since": info["waiting_since"],
                                "direct_mode": info.get("direct_mode", False),
                            }
                            for info in self._pending_djs.values()
                        ]
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "vj_state",
                                    "patterns": self._get_all_patterns_list(),
                                    "current_pattern": self._pattern_name,
                                    "entity_count": self.entity_count,
                                    "zone": self.zone,
                                    "zone_patterns": self._get_zone_patterns_dict(),
                                    "dj_roster": self._get_dj_roster(),
                                    "active_dj": self._active_dj_id,
                                    "health_stats": self.get_health_stats(),
                                    "minecraft_connected": mc_status,
                                    "minecraft_server_type": (
                                        getattr(self.viz_client, "server_type", None)
                                        if self.viz_client
                                        else None
                                    ),
                                    "pending_djs": pending,
                                    "band_materials": self._band_materials,
                                    "band_materials_source": self._band_materials_source,
                                    "visual_delay_ms": self._visual_delay_ms,
                                    "visual_delay_mode": self._visual_delay_mode,
                                    "beat_predictor_confidence": float(
                                        self._beat_predictor.tempo_confidence
                                    ),
                                    "beat_predictor_bpm": float(self._beat_predictor.tempo_bpm),
                                    "beat_predictor_locked": bool(
                                        self._beat_predictor.is_phase_locked
                                    ),
                                    "banner_profiles": {
                                        did: {k: v for k, v in prof.items() if k != "image_pixels"}
                                        for did, prof in self._dj_banner_profiles.items()
                                    },
                                    "bitmap_zones": self._get_bitmap_zones_dict(),
                                    "bloom_enabled": self._bloom_enabled,
                                    "bloom_strength": self._bloom_strength,
                                    "bloom_threshold": self._bloom_threshold,
                                    "ambient_lights_enabled": self._ambient_lights_enabled,
                                }
                            )
                        )

                    elif msg_type == "set_pattern":
                        pattern_name = data.get("pattern", "spectrum")
                        is_bitmap = pattern_name.startswith("bmp_")
                        if is_bitmap or _lua_pattern_exists(pattern_name):
                            target_zones = data.get("zones", None)
                            if target_zones is None:
                                zones_to_update = list(self._zone_patterns.keys()) or [self.zone]
                            else:
                                zones_to_update = [
                                    zn for zn in target_zones if zn in self._zone_patterns
                                ]
                                if not zones_to_update:
                                    zones_to_update = [self.zone]
                            old_count = self.entity_count
                            for zn in zones_to_update:
                                zs = self._get_zone_state(zn)
                                if is_bitmap:
                                    await self._switch_zone_to_bitmap(zn, zs, pattern_name)
                                else:
                                    await self._switch_zone_to_block(zn, zs, pattern_name)
                            await self._broadcast_pattern_change()
                            if old_count != self.entity_count:
                                await self._broadcast_config_sync_to_djs()
                                await self._broadcast_config_to_browsers()
                                await self._broadcast_stream_routes()
                                logger.info(
                                    f"Pattern '{pattern_name}' default blocks: {old_count} -> {self.entity_count}"
                                )

                    elif msg_type == "set_active_dj":
                        dj_id = data.get("dj_id")
                        if dj_id:
                            await self._set_active_dj(dj_id)

                    elif msg_type == "kick_dj":
                        dj_id = data.get("dj_id")
                        if dj_id and dj_id in self._djs:
                            dj = self._djs[dj_id]
                            try:
                                await dj.websocket.close(4010, "Kicked by VJ")
                            except Exception:
                                pass  # Ignore errors when closing already-closed connections
                            logger.info(f"DJ kicked: {dj.dj_name}")

                    elif msg_type == "generate_connect_code":
                        # Generate a new connect code for DJ client auth
                        ttl_minutes = data.get("ttl_minutes", 30)

                        # Try coordinator first (centralized codes)
                        connect_code = await self._coordinator_create_show(ttl_minutes)
                        if connect_code is None:
                            # Fallback to local code generation
                            connect_code = ConnectCode.generate(ttl_minutes)

                        self._connect_codes[connect_code.code] = connect_code

                        # Clean up expired codes
                        self._cleanup_expired_codes()

                        logger.info(
                            f"Generated connect code: {connect_code.code} (expires in {ttl_minutes}m)"
                        )

                        await websocket.send(
                            _json_str(
                                {
                                    "type": "connect_code_generated",
                                    "code": connect_code.code,
                                    "expires_at": connect_code.expires_at,
                                    "ttl_minutes": ttl_minutes,
                                }
                            )
                        )

                        # Broadcast updated code list to all admin clients
                        await self._broadcast_connect_codes()

                    elif msg_type == "get_connect_codes":
                        # Get list of active connect codes
                        self._cleanup_expired_codes()
                        codes = [
                            {
                                "code": code.code,
                                "created_at": code.created_at,
                                "expires_at": code.expires_at,
                                "used": code.used,
                            }
                            for code in self._connect_codes.values()
                            if code.is_valid()
                        ]
                        await websocket.send(_json_str({"type": "connect_codes", "codes": codes}))

                    elif msg_type == "revoke_connect_code":
                        # Revoke a connect code
                        code = data.get("code", "").upper()
                        if code in self._connect_codes:
                            del self._connect_codes[code]
                            logger.info(f"Revoked connect code: {code}")
                            await self._broadcast_connect_codes()

                    elif msg_type == "get_dj_roster":
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "dj_roster",
                                    "roster": self._get_dj_roster(),
                                    "active_dj": self._active_dj_id,
                                }
                            )
                        )

                    elif msg_type == "get_pending_djs":
                        pending_list = [
                            {
                                "dj_id": info["dj_id"],
                                "dj_name": info["dj_name"],
                                "waiting_since": info["waiting_since"],
                                "direct_mode": info.get("direct_mode", False),
                            }
                            for info in self._pending_djs.values()
                        ]
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "pending_djs",
                                    "pending": pending_list,
                                }
                            )
                        )

                    elif msg_type == "approve_dj":
                        await self._approve_pending_dj(data.get("dj_id"))

                    elif msg_type == "deny_dj":
                        await self._deny_pending_dj(data.get("dj_id"))

                    elif msg_type == "reorder_dj_queue":
                        dj_id = data.get("dj_id")
                        new_pos = data.get("new_position")
                        if dj_id and new_pos is not None:
                            await self._reorder_dj_queue(dj_id, int(new_pos))

                    elif msg_type in ("set_entity_count", "set_block_count"):
                        new_count = data.get("count", 16)
                        if 1 <= new_count <= 256:
                            old_count = self.entity_count
                            self.entity_count = new_count
                            self._pattern_config.entity_count = new_count
                            # Re-init pattern with new count
                            self._current_pattern = get_pattern(
                                self._pattern_name, self._pattern_config
                            )
                            # Re-init Minecraft pool (cleanup first to remove old entities)
                            # Skip for bitmap-initialized zones — they use TextDisplay grids
                            zs_ec = self._get_zone_state(self.zone)
                            if (
                                not zs_ec.bitmap_initialized
                                and self.viz_client
                                and self.viz_client.connected
                            ):
                                try:
                                    await self.viz_client.cleanup_zone(self.zone)
                                    await self.viz_client.init_pool(
                                        self.zone, self.entity_count, zs_ec.block_type
                                    )
                                except Exception as e:
                                    logger.warning(f"Failed to update Minecraft pool: {e}")
                            # Sync to all DJs and browser clients
                            await self._broadcast_config_sync_to_djs()
                            await self._broadcast_config_to_browsers()
                            logger.info(f"Entity count changed: {old_count} -> {new_count}")

                    elif msg_type == "set_zone":
                        new_zone = data.get("zone", "main")
                        if new_zone != self.zone:
                            self.zone = new_zone
                            # Re-init Minecraft pool in new zone (skip bitmap zones)
                            zs_zc = self._get_zone_state(self.zone)
                            if (
                                not zs_zc.bitmap_initialized
                                and self.viz_client
                                and self.viz_client.connected
                            ):
                                try:
                                    await self.viz_client.init_pool(
                                        self.zone, self.entity_count, zs_zc.block_type
                                    )
                                except Exception as e:
                                    logger.warning(f"Failed to init Minecraft pool: {e}")
                            # Sync to all DJs and browser clients
                            await self._broadcast_config_sync_to_djs()
                            await self._broadcast_config_to_browsers()
                            logger.info(f"Zone changed to: {new_zone}")

                    elif msg_type == "set_preset":
                        # Handle preset selection by name or raw settings dict
                        preset = data.get("preset", {})
                        if isinstance(preset, str):
                            # Admin panel sends preset name (e.g., "edm", "chill")
                            preset_name = preset.lower()
                            if preset_name in AUDIO_PRESETS:
                                preset_dict = self._apply_named_preset(preset_name)
                                # Store for active DJ so it restores on swap
                                if self._active_dj_id:
                                    self._dj_presets[self._active_dj_id] = preset_name
                                # Broadcast settings to DJs
                                await self._broadcast_preset_to_djs(preset_dict, preset_name)
                                logger.info(f"Preset applied: {preset_name}")
                            else:
                                logger.warning(f"Unknown preset: {preset_name}")
                        elif isinstance(preset, dict):
                            # Raw settings dict (from DJs or other sources)
                            if "attack" in preset:
                                self._pattern_config.attack = float(preset["attack"])
                            if "release" in preset:
                                self._pattern_config.release = float(preset["release"])
                            if "beat_threshold" in preset:
                                self._pattern_config.beat_threshold = float(
                                    preset["beat_threshold"]
                                )
                            if "band_sensitivity" in preset:
                                self._band_sensitivity = list(preset["band_sensitivity"])
                            await self._broadcast_preset_to_djs(preset)
                            logger.info("Preset settings updated")

                    elif msg_type == "set_band_sensitivity":
                        # Apply band sensitivity locally (not forwarded to MC)
                        band = data.get("band", 0)
                        sensitivity = data.get("sensitivity", 1.0)
                        if 0 <= band < 5:
                            self._band_sensitivity[band] = max(0.0, min(2.0, sensitivity))
                            logger.debug(f"Band {band} sensitivity: {sensitivity}")
                            # Sync to DJs
                            await self._broadcast_to_djs(
                                {
                                    "type": "band_sensitivity_sync",
                                    "sensitivity": list(self._band_sensitivity),
                                }
                            )

                    elif msg_type == "set_band_materials":
                        # Per-band block type overrides
                        materials = data.get("materials")
                        if isinstance(materials, list) and len(materials) == 5:
                            self._band_materials = [
                                (m if isinstance(m, str) and m else None) for m in materials
                            ]
                            self._band_materials_source = "admin"
                            logger.info(f"Band materials set: {self._band_materials}")
                            # Sync to all browser clients
                            await self._broadcast_to_browsers(
                                _json_str(
                                    {
                                        "type": "band_materials_sync",
                                        "materials": self._band_materials,
                                        "source": self._band_materials_source,
                                    }
                                )
                            )

                    elif msg_type == "set_audio_setting":
                        # Apply audio settings locally (not forwarded to MC)
                        setting = data.get("setting")
                        value = data.get("value")
                        if setting and value is not None:
                            if setting == "attack":
                                self._pattern_config.attack = float(value)
                            elif setting == "release":
                                self._pattern_config.release = float(value)
                            elif setting == "beat_threshold":
                                self._pattern_config.beat_threshold = float(value)
                            logger.debug(f"Audio setting {setting}: {value}")
                            # Sync to DJs
                            await self._broadcast_to_djs(
                                {
                                    "type": "audio_setting_sync",
                                    "setting": setting,
                                    "value": float(value),
                                }
                            )

                    elif msg_type == "set_visual_delay":
                        delay = data.get("delay_ms", 0)
                        self._visual_delay_ms = max(0.0, min(500.0, float(delay)))
                        logger.info(f"Visual delay set to {self._visual_delay_ms:.0f}ms")
                        await self._broadcast_to_browsers(
                            _json_str(
                                {
                                    "type": "visual_delay_sync",
                                    "delay_ms": self._visual_delay_ms,
                                }
                            )
                        )

                    elif msg_type == "set_visual_delay_mode":
                        mode = data.get("mode", "manual")
                        if mode in ("manual", "auto", "discord", "svc"):
                            self._visual_delay_mode = mode
                            logger.info(f"Visual delay mode set to {mode}")
                            await self._broadcast_to_browsers(
                                _json_str(
                                    {
                                        "type": "visual_delay_mode_sync",
                                        "mode": self._visual_delay_mode,
                                    }
                                )
                            )

                    elif msg_type == "set_transition_duration":
                        # Set pattern crossfade transition duration for all zones
                        duration = data.get("duration", 1.0)
                        clamped = max(0.0, min(3.0, float(duration)))
                        self._default_transition_duration = clamped
                        for zs in self._zone_patterns.values():
                            zs.transition_duration = clamped
                        logger.info(f"Pattern transition duration set to {clamped}s")
                        # Broadcast to all browsers
                        await self._broadcast_to_browsers(
                            _json_str(
                                {
                                    "type": "transition_duration_sync",
                                    "duration": clamped,
                                }
                            )
                        )

                    elif msg_type == "sync_test":
                        # Sync test: send flash to all browser clients and tone request to active DJ
                        logger.info("[SYNC TEST] Triggered by admin")
                        test_ts = time.time()
                        # Flash all browsers
                        await self._broadcast_to_browsers(
                            _json_str(
                                {
                                    "type": "sync_test_flash",
                                    "server_time": test_ts,
                                }
                            )
                        )
                        # Request tone from active DJ
                        dj = self.active_dj
                        if dj:
                            try:
                                await dj.websocket.send(
                                    _json_str(
                                        {
                                            "type": "sync_test_tone",
                                            "server_time": test_ts,
                                        }
                                    )
                                )
                            except Exception:
                                pass

                    elif msg_type == "get_zones":
                        # Forward to Minecraft and return zones
                        if self.viz_client and self.viz_client.connected:
                            try:
                                zones = await self.viz_client.get_zones()
                                await websocket.send(
                                    _json_str({"type": "zones", "zones": zones or []})
                                )
                            except Exception as e:
                                logger.warning(f"Failed to get zones: {e}")
                                await websocket.send(_json_str({"type": "zones", "zones": []}))

                    elif msg_type == "get_zone":
                        # Forward to Minecraft
                        zone_name = data.get("zone", "main")
                        if self.viz_client and self.viz_client.connected:
                            try:
                                zone = await self.viz_client.get_zone(zone_name)
                                await websocket.send(_json_str({"type": "zone", "zone": zone}))
                            except Exception as e:
                                logger.warning(f"Failed to get zone: {e}")

                    elif msg_type == "get_stages":
                        # Forward to Minecraft and return stages
                        if self.viz_client and self.viz_client.connected:
                            try:
                                result = await self.viz_client.send({"type": "get_stages"})
                                if result:
                                    await websocket.send(_json_str(result))
                                else:
                                    await websocket.send(
                                        _json_str({"type": "stages", "stages": [], "count": 0})
                                    )
                            except Exception as e:
                                logger.warning(f"Failed to get stages: {e}")
                                await websocket.send(
                                    _json_str({"type": "stages", "stages": [], "count": 0})
                                )
                        else:
                            await websocket.send(
                                _json_str({"type": "stages", "stages": [], "count": 0})
                            )

                    elif msg_type == "trigger_effect":
                        # Handle effect triggers (blackout, freeze, flash, strobe, etc.)
                        effect = data.get("effect", "flash")
                        intensity = data.get("intensity", 1.0)
                        duration = data.get("duration", 500)

                        if effect in ("blackout", "freeze"):
                            # Toggle effects
                            if intensity <= 0:
                                # Turn off
                                if effect == "blackout":
                                    self._blackout = False
                                    if effect in self._active_effects:
                                        del self._active_effects[effect]
                                    # Re-show entities
                                    if self.viz_client and self.viz_client.connected:
                                        try:
                                            await self.viz_client.set_visible(self.zone, True)
                                        except Exception:
                                            pass
                                elif effect == "freeze":
                                    self._freeze = False
                                    if effect in self._active_effects:
                                        del self._active_effects[effect]
                                logger.info(f"{effect.capitalize()} OFF")
                            else:
                                # Turn on
                                if effect == "blackout":
                                    self._blackout = True
                                    # Hide entities in MC
                                    if self.viz_client and self.viz_client.connected:
                                        try:
                                            await self.viz_client.set_visible(self.zone, False)
                                        except Exception:
                                            pass
                                elif effect == "freeze":
                                    self._freeze = True
                                self._active_effects[effect] = {
                                    "intensity": intensity,
                                    "end_time": time.time() + 999999,
                                    "duration": 999999999,
                                    "start_time": time.time(),
                                }
                                logger.info(f"{effect.capitalize()} ON")
                        else:
                            # Timed effects (flash, strobe, pulse, wave, spiral, explode)
                            self._active_effects[effect] = {
                                "intensity": intensity,
                                "end_time": time.time() + (duration / 1000),
                                "duration": duration,
                                "start_time": time.time(),
                            }
                            logger.info(
                                f"Effect triggered: {effect} (intensity={intensity}, duration={duration}ms)"
                            )

                        await self._broadcast_effect_trigger(effect)

                    elif msg_type in ("blackout", "set_blackout"):
                        self._blackout = data.get("enabled", not self._blackout)
                        if self._blackout:
                            self._active_effects["blackout"] = {
                                "intensity": 1.0,
                                "end_time": time.time() + 999999,
                                "duration": 999999999,
                                "start_time": time.time(),
                            }
                            if self.viz_client and self.viz_client.connected:
                                try:
                                    await self.viz_client.set_visible(self.zone, False)
                                except Exception:
                                    pass
                        else:
                            self._active_effects.pop("blackout", None)
                            if self.viz_client and self.viz_client.connected:
                                try:
                                    await self.viz_client.set_visible(self.zone, True)
                                except Exception:
                                    pass
                        logger.info(f"Blackout: {self._blackout}")

                    elif msg_type in ("freeze", "set_freeze"):
                        self._freeze = data.get("enabled", not self._freeze)
                        if self._freeze:
                            self._active_effects["freeze"] = {
                                "intensity": 1.0,
                                "end_time": time.time() + 999999,
                                "duration": 999999999,
                                "start_time": time.time(),
                            }
                        else:
                            self._active_effects.pop("freeze", None)
                        logger.info(f"Freeze: {self._freeze}")

                    # ========== Banner Profile Management ==========

                    elif msg_type == "set_banner_profile":
                        dj_id = data.get("dj_id")
                        profile = data.get("profile", {})
                        if dj_id and re.match(r"^[a-zA-Z0-9_\-]+$", dj_id):
                            self._dj_banner_profiles[dj_id] = profile
                            self._save_banner_profiles()
                            # If this DJ is currently active, push to Minecraft
                            if dj_id == self._active_dj_id:
                                await self._send_banner_config_to_minecraft(dj_id)
                            await websocket.send(
                                _json_str({"type": "banner_profile_saved", "dj_id": dj_id})
                            )
                            logger.info(f"Banner profile saved for DJ: {dj_id}")

                    elif msg_type == "get_banner_profile":
                        dj_id = data.get("dj_id")
                        profile = self._dj_banner_profiles.get(dj_id, {})
                        # Strip image_pixels for transport (send separately if needed)
                        safe_profile = {k: v for k, v in profile.items() if k != "image_pixels"}
                        safe_profile["has_image"] = bool(profile.get("image_pixels"))
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "banner_profile",
                                    "dj_id": dj_id,
                                    "profile": safe_profile,
                                }
                            )
                        )

                    elif msg_type == "get_all_banner_profiles":
                        profiles_summary = {}
                        for did, prof in self._dj_banner_profiles.items():
                            summary = {k: v for k, v in prof.items() if k != "image_pixels"}
                            summary["has_image"] = bool(prof.get("image_pixels"))
                            profiles_summary[did] = summary
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "all_banner_profiles",
                                    "profiles": profiles_summary,
                                }
                            )
                        )

                    elif msg_type == "upload_banner_logo":
                        dj_id = data.get("dj_id")
                        image_data = data.get("image_base64")
                        grid_width = min(128, max(4, data.get("grid_width", 24)))
                        grid_height = min(128, max(2, data.get("grid_height", 12)))
                        if dj_id and image_data:
                            pixels = self._process_logo_image(image_data, grid_width, grid_height)
                            if pixels is not None:
                                profile = self._dj_banner_profiles.setdefault(dj_id, {})
                                profile["banner_mode"] = "image"
                                profile["image_pixels"] = pixels
                                profile["grid_width"] = grid_width
                                profile["grid_height"] = grid_height
                                profile["logo_filename"] = data.get("filename", "logo.png")
                                self._save_banner_profiles()
                                if dj_id == self._active_dj_id:
                                    await self._send_banner_config_to_minecraft(dj_id)
                                await websocket.send(
                                    _json_str(
                                        {
                                            "type": "banner_logo_processed",
                                            "dj_id": dj_id,
                                            "grid_width": grid_width,
                                            "grid_height": grid_height,
                                            "pixel_count": len(pixels),
                                        }
                                    )
                                )
                                logger.info(
                                    f"Logo processed for DJ {dj_id}: {grid_width}x{grid_height}"
                                )
                            else:
                                await websocket.send(
                                    _json_str(
                                        {
                                            "type": "error",
                                            "message": "Failed to process logo image. Is Pillow installed?",
                                        }
                                    )
                                )

                    elif msg_type == "voice_config":
                        # Forward voice config to Minecraft plugin
                        await self._forward_voice_config(data)
                        logger.info(
                            f"Voice config forwarded: enabled={data.get('enabled')}, "
                            f"type={data.get('channel_type')}"
                        )

                    elif msg_type == "get_voice_status":
                        # Forward get_voice_status to Minecraft and relay response
                        if self.viz_client and self.viz_client.connected:
                            response = await self.viz_client.send({"type": "get_voice_status"})
                            if response:
                                await websocket.send(_json_str(response))
                        else:
                            await websocket.send(
                                _json_str(
                                    {
                                        "type": "voice_status",
                                        "available": False,
                                        "streaming": False,
                                        "channel_type": "static",
                                        "connected_players": 0,
                                    }
                                )
                            )

                    elif msg_type == "scan_stage_blocks":
                        # Forward to Minecraft and relay block scan response
                        # Use longer timeout (30s) since scanning large stages is slow
                        stage = data.get("stage", "")
                        if self.viz_client and self.viz_client.connected:
                            saved_timeout = self.viz_client.connect_timeout
                            self.viz_client.connect_timeout = 30.0
                            try:
                                result = await self.viz_client.scan_stage_blocks(stage)
                            finally:
                                self.viz_client.connect_timeout = saved_timeout
                            if result:
                                await websocket.send(_json_str(result))
                            else:
                                await websocket.send(
                                    _json_str(
                                        {
                                            "type": "stage_blocks",
                                            "error": "Scan failed or timed out",
                                        }
                                    )
                                )
                        else:
                            await websocket.send(
                                _json_str(
                                    {
                                        "type": "stage_blocks",
                                        "error": "Minecraft not connected",
                                    }
                                )
                            )

                    elif msg_type == "request_parity_check":
                        parity_result = await self._run_parity_check()
                        await self._broadcast_to_browsers(_json_str(parity_result))

                    elif msg_type == "subscribe_voice":
                        self._voice_subscribers.add(websocket)
                        logger.info(
                            f"Voice subscriber added. Total: {len(self._voice_subscribers)}"
                        )
                        await websocket.send(_json_str({"type": "subscribe_voice_ack"}))

                    elif msg_type == "unsubscribe_voice":
                        self._voice_subscribers.discard(websocket)
                        logger.info(
                            f"Voice subscriber removed. Total: {len(self._voice_subscribers)}"
                        )
                        await websocket.send(_json_str({"type": "unsubscribe_voice_ack"}))

                    elif msg_type == "save_scene":
                        # Save current VJ state as a named scene
                        scene_name = data.get("name", "").strip()
                        if not scene_name:
                            await websocket.send(
                                _json_str({"type": "error", "message": "Scene name is required"})
                            )
                            continue

                        # Slur filter on scene name
                        try:
                            from vj_server.content_filter import contains_slur as _contains_slur

                            if _contains_slur(scene_name):
                                await websocket.send(
                                    _json_str(
                                        {
                                            "type": "error",
                                            "message": "Scene name contains language that is not allowed",
                                        }
                                    )
                                )
                                continue
                        except ImportError:
                            pass

                        try:
                            scene_data = self._capture_current_state()
                            self._save_scene_to_file(scene_name, scene_data)
                            logger.info(f"Scene saved: {scene_name}")
                            await websocket.send(
                                _json_str({"type": "scene_saved", "name": scene_name})
                            )
                            # Broadcast updated scene list to all browsers
                            scenes = self._list_scenes()
                            await self._broadcast_to_browsers(
                                _json_str({"type": "scenes_list", "scenes": scenes})
                            )
                        except Exception as e:
                            logger.error(f"Failed to save scene '{scene_name}': {e}")
                            await websocket.send(
                                _json_str(
                                    {"type": "error", "message": f"Failed to save scene: {str(e)}"}
                                )
                            )

                    elif msg_type == "load_scene":
                        # Load a saved scene
                        scene_name = data.get("name", "").strip()
                        if not scene_name:
                            await websocket.send(
                                _json_str({"type": "error", "message": "Scene name is required"})
                            )
                            continue

                        try:
                            scene_data = self._load_scene_from_file(scene_name)
                            await self._apply_scene_state(scene_data)
                            logger.info(f"Scene loaded: {scene_name}")
                            await websocket.send(
                                _json_str({"type": "scene_loaded", "name": scene_name})
                            )
                        except FileNotFoundError:
                            logger.warning(f"Scene not found: {scene_name}")
                            await websocket.send(
                                _json_str(
                                    {"type": "error", "message": f"Scene '{scene_name}' not found"}
                                )
                            )
                        except Exception as e:
                            logger.error(f"Failed to load scene '{scene_name}': {e}")
                            await websocket.send(
                                _json_str(
                                    {"type": "error", "message": f"Failed to load scene: {str(e)}"}
                                )
                            )

                    elif msg_type == "delete_scene":
                        # Delete a saved scene
                        scene_name = data.get("name", "").strip()
                        if not scene_name:
                            await websocket.send(
                                _json_str({"type": "error", "message": "Scene name is required"})
                            )
                            continue

                        # Prevent deletion of built-in scenes
                        if scene_name in ["Chill Lounge", "EDM Stage", "Rock Arena", "Ambient"]:
                            await websocket.send(
                                _json_str(
                                    {"type": "error", "message": "Cannot delete built-in scenes"}
                                )
                            )
                            continue

                        try:
                            self._delete_scene_file(scene_name)
                            logger.info(f"Scene deleted: {scene_name}")
                            await websocket.send(
                                _json_str({"type": "scene_deleted", "name": scene_name})
                            )
                            # Broadcast updated scene list to all browsers
                            scenes = self._list_scenes()
                            await self._broadcast_to_browsers(
                                _json_str({"type": "scenes_list", "scenes": scenes})
                            )
                        except FileNotFoundError:
                            logger.warning(f"Scene not found: {scene_name}")
                            await websocket.send(
                                _json_str(
                                    {"type": "error", "message": f"Scene '{scene_name}' not found"}
                                )
                            )
                        except Exception as e:
                            logger.error(f"Failed to delete scene '{scene_name}': {e}")
                            await websocket.send(
                                _json_str(
                                    {
                                        "type": "error",
                                        "message": f"Failed to delete scene: {str(e)}",
                                    }
                                )
                            )

                    elif msg_type == "list_scenes":
                        # List all saved scenes
                        try:
                            scenes = self._list_scenes()
                            await websocket.send(
                                _json_str({"type": "scenes_list", "scenes": scenes})
                            )
                        except Exception as e:
                            logger.error(f"Failed to list scenes: {e}")
                            await websocket.send(
                                _json_str(
                                    {"type": "error", "message": f"Failed to list scenes: {str(e)}"}
                                )
                            )

                    elif msg_type == "set_bloom":
                        self._bloom_enabled = data.get("enabled", self._bloom_enabled)
                        if "strength" in data:
                            self._bloom_strength = max(0.0, min(1.0, float(data["strength"])))
                        if "threshold" in data:
                            self._bloom_threshold = max(0.0, min(1.0, float(data["threshold"])))
                        await self._broadcast_to_browsers(
                            _json_str(
                                {
                                    "type": "bloom_state",
                                    "enabled": self._bloom_enabled,
                                    "strength": self._bloom_strength,
                                    "threshold": self._bloom_threshold,
                                }
                            )
                        )

                    elif msg_type == "set_ambient_lights":
                        self._ambient_lights_enabled = data.get(
                            "enabled", self._ambient_lights_enabled
                        )
                        # Forward to MC plugin
                        if self.viz_client and self.viz_client.connected:
                            try:
                                await self.viz_client.ws.send(self.viz_client._encode(data))
                            except Exception:
                                pass
                        await self._broadcast_to_browsers(
                            _json_str(
                                {
                                    "type": "ambient_lights_state",
                                    "enabled": self._ambient_lights_enabled,
                                }
                            )
                        )

                    # Forward zone/rendering messages directly to Minecraft
                    elif msg_type in FORWARD_TO_MINECRAFT:
                        # Sync local state when zone config changes entity count,
                        # block type, or scale so patterns generate correct data
                        if msg_type == "set_zone_config":
                            try:
                                config = data.get("config", {})
                                new_count = config.get("entity_count")
                                if (
                                    new_count
                                    and 1 <= new_count <= 1000
                                    and new_count != self.entity_count
                                ):
                                    old_count = self.entity_count
                                    self.entity_count = new_count
                                    self._pattern_config.entity_count = new_count
                                    self._current_pattern = get_pattern(
                                        self._pattern_name, self._pattern_config
                                    )
                                    logger.info(
                                        f"Entity count synced from zone config: {old_count} -> {new_count}"
                                    )
                                    # Sync to all DJs and browser clients
                                    await self._broadcast_config_sync_to_djs()
                                    await self._broadcast_config_to_browsers()
                                # Sync block type to zone state
                                new_block_type = config.get("block_type")
                                if new_block_type:
                                    zone_name = data.get("zone", self.zone)
                                    zs = self._get_zone_state(zone_name)
                                    zs.block_type = new_block_type
                                    logger.info(
                                        f"Block type synced for zone '{zone_name}': {new_block_type}"
                                    )
                                # Sync scale settings to pattern config
                                if "base_scale" in config:
                                    self._pattern_config.base_scale = float(config["base_scale"])
                                if "max_scale" in config:
                                    self._pattern_config.max_scale = float(config["max_scale"])
                            except Exception as e:
                                logger.warning(f"Failed to sync zone config locally: {e}")

                        if self.viz_client and self.viz_client.connected:
                            try:
                                if msg_type in FIRE_AND_FORGET:
                                    # Send without waiting for response — instant for the UI
                                    await self.viz_client.ws.send(self.viz_client._encode(data))
                                    await websocket.send(_json_str({"type": "ok"}))
                                    logger.debug(f"Fire-and-forget {msg_type} to Minecraft")
                                else:
                                    response = await self.viz_client.send(data)
                                    if response:
                                        await websocket.send(_json_str(response))
                                        logger.debug(f"Forwarded {msg_type} to Minecraft")
                                        # Sync local state when init_bitmap succeeds
                                        if (
                                            msg_type == "init_bitmap"
                                            and response.get("type") == "bitmap_initialized"
                                        ):
                                            zn = data.get("zone", self.zone)
                                            zs = self._get_zone_state(zn)
                                            zs.bitmap_initialized = True
                                            zs.render_mode = "bitmap"
                                            zs.bitmap_width = response.get("width", 0)
                                            zs.bitmap_height = response.get("height", 0)
                                            zs.pattern_name = data.get("pattern", zs.pattern_name)
                                            logger.info(
                                                f"Synced bitmap state for zone '{zn}' via browser init_bitmap"
                                            )
                                        # Sync local state when teardown_bitmap succeeds
                                        if (
                                            msg_type == "teardown_bitmap"
                                            and response.get("type") == "bitmap_teardown"
                                        ):
                                            zn = data.get("zone", self.zone)
                                            zs = self._get_zone_state(zn)
                                            zs.bitmap_initialized = False
                                            zs.bitmap_width = 0
                                            zs.bitmap_height = 0
                                            zs.render_mode = "block"
                                            logger.info(
                                                f"Synced block state for zone '{zn}' via browser teardown_bitmap"
                                            )
                            except Exception as e:
                                logger.warning(f"Failed to forward {msg_type} to Minecraft: {e}")
                                await websocket.send(
                                    _json_str(
                                        {
                                            "type": "error",
                                            "message": f"Failed to forward to Minecraft: {e}",
                                        }
                                    )
                                )
                        else:
                            logger.warning(f"Cannot forward {msg_type}: Minecraft not connected")
                            await websocket.send(
                                _json_str(
                                    {
                                        "type": "error",
                                        "message": "Minecraft not connected",
                                    }
                                )
                            )

                except msgspec.DecodeError:
                    logger.debug("Invalid JSON received from browser client")
                except Exception as e:
                    logger.error(f"Error handling browser message: {e}")
                    # Don't close connection on error, just log and continue

        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self._broadcast_clients.discard(websocket)
            self._voice_subscribers.discard(websocket)
            # Clean up heartbeat tracking for this client
            self._browser_pong_pending.pop(websocket, None)
            self._browser_last_pong.pop(websocket, None)
            self._browser_disconnects += 1
            logger.info(f"Browser client disconnected. Total: {len(self._broadcast_clients)}")

    async def _broadcast_dj_roster(self):
        """Broadcast DJ roster to all browser clients and connected DJs."""
        roster = self._get_dj_roster()
        active_dj = self._active_dj_id

        # Browser clients get full roster
        browser_msg = _json_str(
            {
                "type": "dj_roster",
                "roster": roster,
                "active_dj": active_dj,
            }
        )
        dead_clients = set()
        for client in list(self._broadcast_clients):
            try:
                await client.send(browser_msg)
            except Exception:
                dead_clients.add(client)
        self._broadcast_clients -= dead_clients

        # DJs get a lightweight roster (no admin-level stats)
        for dj_id, dj in dict(self._djs).items():
            try:
                dj_roster = [
                    {
                        "dj_id": r["dj_id"],
                        "dj_name": r["dj_name"],
                        "is_active": r["is_active"],
                        "avatar_url": r.get("avatar_url"),
                        "queue_position": r["queue_position"],
                    }
                    for r in roster
                ]
                await dj.websocket.send(
                    _json_str(
                        {
                            "type": "dj_roster",
                            "djs": dj_roster,
                            "active_dj_id": active_dj,
                            "your_position": next(
                                (r["queue_position"] for r in roster if r["dj_id"] == dj_id), 999
                            ),
                            "rotation_interval_sec": getattr(self, "_rotation_interval_sec", 0),
                        }
                    )
                )
            except Exception:
                pass

    async def _broadcast_to_browsers(self, message: str):
        """Broadcast a pre-serialized JSON message to all browser clients."""
        dead_clients = set()
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                dead_clients.add(client)
        self._broadcast_clients -= dead_clients

    async def _broadcast_minecraft_status(self):
        """Broadcast Minecraft connection status to all browser clients."""
        mc_connected = self.viz_client is not None and self.viz_client.connected
        if mc_connected != self._last_mc_connected:
            self._last_mc_connected = mc_connected
            server_type = getattr(self.viz_client, "server_type", None) if self.viz_client else None
            msg = {
                "type": "minecraft_status",
                "connected": mc_connected,
            }
            if server_type:
                msg["server_type"] = server_type
            await self._broadcast_to_browsers(_json_str(msg))
            logger.info(
                f"[MC STATUS] Broadcasting minecraft_connected={mc_connected} server_type={server_type}"
            )

    async def _broadcast_pattern_change(self):
        """Broadcast pattern change to all browser clients."""
        message = _json_str(
            {
                "type": "pattern_changed",
                "pattern": self._pattern_name,
                "patterns": self._get_all_patterns_list(),
                "transitioning": self._transitioning,
                "transition_duration": self._transition_duration if self._transitioning else 0,
                "zone_patterns": self._get_zone_patterns_dict(),
            }
        )
        dead_clients = set()
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                dead_clients.add(client)
        self._broadcast_clients -= dead_clients

        # Also broadcast to DJs (for direct mode pattern sync)
        await self._broadcast_pattern_sync_to_djs()

    async def _broadcast_pattern_sync_to_djs(self):
        """Broadcast pattern sync to all connected DJs (for direct mode)."""
        message = _json_str(
            {
                "type": "pattern_sync",
                "pattern": self._pattern_name,
                "config": {
                    "entity_count": self.entity_count,
                    "zone_size": self._pattern_config.zone_size,
                    "beat_boost": self._pattern_config.beat_boost,
                    "base_scale": self._pattern_config.base_scale,
                    "max_scale": self._pattern_config.max_scale,
                },
            }
        )

        for dj in list(self._djs.values()):
            try:
                await dj.websocket.send(message)
            except Exception as e:
                logger.debug(f"Failed to send pattern sync to DJ {dj.dj_id}: {e}")

    async def _broadcast_config_sync_to_djs(self):
        """Broadcast config sync (entity count, zone) to all connected DJs."""
        message = _json_str(
            {
                "type": "config_sync",
                "entity_count": self.entity_count,
                "zone": self.zone,
            }
        )

        for dj in list(self._djs.values()):
            try:
                await dj.websocket.send(message)
            except Exception as e:
                logger.debug(f"Failed to send config sync to DJ {dj.dj_id}: {e}")

    async def _broadcast_config_to_browsers(self):
        """Broadcast config changes (entity count, zone, pattern) to all browser clients."""
        message = _json_str(
            {
                "type": "config_update",
                "entity_count": self.entity_count,
                "zone": self.zone,
                "current_pattern": self._pattern_name,
            }
        )

        dead_clients = set()
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                dead_clients.add(client)
        self._broadcast_clients -= dead_clients

    async def _broadcast_preset_to_djs(self, preset: dict, preset_name: str = None):
        """Broadcast preset changes (attack/release/threshold) to all DJs."""
        payload = dict(preset)
        if preset_name:
            payload["name"] = preset_name
        message = _json_str({"type": "preset_sync", "preset": payload})

        for dj in list(self._djs.values()):
            try:
                await dj.websocket.send(message)
            except Exception as e:
                logger.debug(f"Failed to send preset sync to DJ {dj.dj_id}: {e}")

        # Also broadcast to browser clients
        browser_msg = _json_str(
            {
                "type": "preset_changed",
                "preset": preset_name or "custom",
                "settings": preset,
            }
        )
        dead_clients = set()
        for client in list(self._broadcast_clients):
            try:
                await client.send(browser_msg)
            except Exception:
                dead_clients.add(client)
        self._broadcast_clients -= dead_clients

    async def _broadcast_effect_trigger(self, effect: str):
        """Broadcast effect trigger to all clients."""
        message = _json_str({"type": "effect_triggered", "effect": effect})

        # Send to browser clients
        dead_clients = set()
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                dead_clients.add(client)
        self._broadcast_clients -= dead_clients

        # Send to DJs
        for dj in list(self._djs.values()):
            try:
                await dj.websocket.send(message)
            except Exception:
                pass

    async def _broadcast_to_djs(self, msg: dict):
        """Broadcast a message dict to all connected DJs."""
        message = _json_str(msg)
        for dj in list(self._djs.values()):
            try:
                await dj.websocket.send(message)
            except Exception as e:
                logger.debug(f"Failed to broadcast to DJ {dj.dj_id}: {e}")

    async def _send_with_timeout(
        self, client, message: str, dead_clients: set, timeout: float = 2.0
    ):
        """Send a message to a client with a timeout. Adds to dead_clients on failure."""
        try:
            await asyncio.wait_for(client.send(message), timeout=timeout)
        except (
            asyncio.TimeoutError,
            websockets.exceptions.ConnectionClosed,
            Exception,
        ):
            dead_clients.add(client)

    async def _broadcast_viz_state(
        self,
        entities: List[dict],
        bands: List[float],
        peak: float,
        is_beat: bool,
        beat_intensity: float,
        instant_bass: float = 0.0,
        instant_kick: bool = False,
        tempo_confidence: float = 0.0,
        beat_phase: float = 0.0,
        zone_entities: Optional[Dict[str, List[dict]]] = None,
    ):
        """Broadcast visualization state to browser clients."""
        if not self._broadcast_clients:
            return
        # Browser/admin meters expect normalized amplitude in [0, 1].
        # Internal pipeline may run amplitude in [0, 5], so normalize here.
        raw_amplitude = max(0.0, float(peak))
        norm_amplitude = min(1.0, raw_amplitude if raw_amplitude <= 1.25 else (raw_amplitude / 5.0))

        # Get stats from active DJ
        latency_ms = 0.0
        ping_ms = 0.0
        pipeline_latency_ms = 0.0
        bpm = 0.0
        fps = 0.0
        jitter_ms = 0.0
        sync_confidence = 0.0
        visual_delay_ms = self._visual_delay_ms
        # Snapshot active DJ once to avoid TOCTOU races
        active_dj_id = self._active_dj_id
        dj = self._djs.get(active_dj_id) if active_dj_id else None
        if dj is not None:
            latency_ms = dj.latency_ms
            ping_ms = dj.network_rtt_ms
            pipeline_latency_ms = dj.pipeline_latency_ms
            bpm = dj.bpm
            fps = dj.frames_per_second
            jitter_ms = dj._jitter_ms
            sync_confidence = self._calculate_sync_confidence(dj)
            visual_delay_ms = self._get_effective_delay_ms(dj)

        # Build active DJ profile for state broadcast
        active_dj_profile = self._dj_profile_dict(dj) if dj is not None else None

        state = VizStateBroadcast(
            entities=entities,
            bands=bands,
            amplitude=norm_amplitude,
            amplitude_raw=raw_amplitude,
            is_beat=is_beat,
            beat_intensity=beat_intensity,
            instant_bass=instant_bass,
            instant_kick=instant_kick,
            frame=self._frame_count,
            pattern=self._pattern_name,
            active_dj=active_dj_profile,
            latency_ms=round(latency_ms, 1),
            ping_ms=round(ping_ms, 1),
            pipeline_latency_ms=round(pipeline_latency_ms, 1),
            fps=round(fps, 1),
            jitter_ms=round(jitter_ms, 1),
            sync_confidence=round(sync_confidence, 0),
            visual_delay_ms=round(visual_delay_ms, 0),
            visual_delay_mode=self._visual_delay_mode,
            zone_status=ZoneStatus(
                bpm_estimate=round(bpm, 1),
                tempo_confidence=round(tempo_confidence, 3),
                beat_phase=round(beat_phase, 3),
            ),
            zone_patterns=self._get_zone_patterns_dict(),
            zone_entities=zone_entities,
            perf=self._latest_perf_snapshot,
        )
        message = mjson.encode(state).decode()

        # Send to all clients concurrently with a short timeout to prevent
        # slow/dead clients from blocking the event loop (which causes latency spikes)
        dead_clients = set()
        if self._broadcast_clients:
            send_tasks = []
            client_list = list(self._broadcast_clients)
            for client in client_list:
                send_tasks.append(self._send_with_timeout(client, message, dead_clients))
            try:
                await asyncio.gather(*send_tasks)
            except Exception:
                pass  # Individual failures already captured in dead_clients
            finally:
                if dead_clients:
                    self._broadcast_clients -= dead_clients

    async def connect_minecraft(self) -> bool:
        """Connect to Minecraft server with timeout."""
        # Clean up existing client before creating new one
        if self.viz_client:
            try:
                await self.viz_client.disconnect()
            except Exception as e:
                logger.debug(f"Error disconnecting old Minecraft client: {e}")
            self.viz_client = None

        logger.info(f"Connecting to Minecraft at {self.minecraft_host}:{self.minecraft_port}...")
        self.viz_client = VizClient(self.minecraft_host, self.minecraft_port, enable_heartbeat=True)
        self.viz_client.on("stage_zone_configs", self._handle_stage_zone_configs)
        self.viz_client.on("bitmap_frame", self._relay_bitmap_frame)

        try:
            # 10 second timeout for initial connection
            connected = await asyncio.wait_for(self.viz_client.connect(), timeout=10.0)
            if not connected:
                logger.error(
                    f"Failed to connect to Minecraft at {self.minecraft_host}:{self.minecraft_port}"
                )
                return False
        except asyncio.TimeoutError:
            logger.error(
                f"Timeout connecting to Minecraft at {self.minecraft_host}:{self.minecraft_port}"
            )
            return False

        logger.info(f"Connected to Minecraft at {self.minecraft_host}:{self.minecraft_port}")

        try:
            # 5 second timeout for zone query
            zones = await asyncio.wait_for(self.viz_client.get_zones(), timeout=5.0)
        except asyncio.TimeoutError:
            logger.error("Timeout getting zones from Minecraft")
            return False

        zone_names = [z["name"] for z in zones] if zones else []

        if self.zone not in zone_names:
            if zone_names:
                self.zone = zone_names[0]
                logger.info(f"Using zone: {self.zone}")
            else:
                logger.error("No zones available!")
                return False

        # Initialize per-zone pattern states for all discovered zones
        for zn in zone_names:
            self._get_zone_state(zn)

        # Pull active stage config so reconnect/startup preserves expected
        # pattern + entity-count state before pools/bitmaps are initialized.
        await self._rehydrate_zone_states_from_active_stage()

        # Re-init each zone based on its current render_mode.
        # New zones default to block mode (Display Entities).
        # Zones already in bitmap mode get re-initialized as bitmap.
        for zn in zone_names:
            zs = self._get_zone_state(zn)
            if zs.render_mode == "bitmap":
                pattern = zs.pattern_name if zs.pattern_name.startswith("bmp_") else "bmp_spectrum"
                try:
                    response = await asyncio.wait_for(
                        self.viz_client.init_bitmap(zn, pattern=pattern),
                        timeout=10.0,
                    )
                    if response and response.get("type") == "bitmap_initialized":
                        zs.bitmap_initialized = True
                        zs.bitmap_width = response.get("width", 0)
                        zs.bitmap_height = response.get("height", 0)
                        logger.info(
                            f"Bitmap re-init: zone '{zn}' → "
                            f"{zs.bitmap_width}x{zs.bitmap_height} pattern={pattern}"
                        )
                        await self._broadcast_to_browsers(_json_str(response))
                except asyncio.TimeoutError:
                    logger.warning(f"Bitmap re-init: timeout for zone '{zn}'")
                except Exception as e:
                    logger.warning(f"Bitmap re-init: failed for zone '{zn}': {e}")
            else:
                # Block mode: init entity pool
                try:
                    await self.viz_client.init_pool(zn, zs.entity_count, zs.block_type)
                except Exception as e:
                    logger.warning(f"Block pool init failed for zone '{zn}': {e}")

        # Cache bitmap patterns from MC plugin for unified pattern list
        try:
            self._bitmap_pattern_cache = await asyncio.wait_for(
                self.viz_client.get_bitmap_patterns(), timeout=5.0
            )
            logger.info(f"Cached {len(self._bitmap_pattern_cache)} bitmap patterns from MC")
        except Exception as e:
            logger.warning(f"Failed to cache bitmap patterns: {e}")
            self._bitmap_pattern_cache = []

        return True

    async def _minecraft_reconnect_loop(self):
        """
        Continuously monitor Minecraft connection and attempt reconnection.

        Checks connection every 5 seconds. On disconnect, attempts reconnection
        with exponential backoff (1.5x multiplier, max 10s). Resets backoff on
        successful reconnection.
        """
        check_interval = 5.0  # Check connection every 5 seconds
        max_backoff = 10.0  # Maximum backoff time in seconds
        backoff_multiplier = 1.5

        while self._running:
            try:
                await asyncio.sleep(check_interval)

                # Check if we need to reconnect
                if self.viz_client is None or not self.viz_client.connected:
                    # Broadcast disconnection status to browsers
                    await self._broadcast_minecraft_status()
                    # Notify DJs so they can adapt direct/relay routing policy.
                    await self._broadcast_stream_routes()
                    logger.info(
                        f"[MC RECONNECT] Minecraft disconnected, attempting reconnection (backoff={self._mc_reconnect_backoff:.1f}s)"
                    )

                    # Wait for backoff period
                    await asyncio.sleep(self._mc_reconnect_backoff)

                    # Attempt reconnection
                    try:
                        success = await self.connect_minecraft()
                        if success:
                            logger.info("[MC RECONNECT] Successfully reconnected to Minecraft")
                            self._mc_reconnect_count += 1
                            # Reset backoff on success
                            self._mc_reconnect_backoff = 5.0
                            # Broadcast MC status change to browsers
                            await self._broadcast_minecraft_status()
                            # Notify DJs so direct publishers can re-initialize MC path.
                            await self._broadcast_stream_routes()
                        else:
                            logger.warning(
                                f"[MC RECONNECT] Reconnection failed, will retry in {self._mc_reconnect_backoff:.1f}s"
                            )
                            # Increase backoff with exponential factor
                            self._mc_reconnect_backoff = min(
                                self._mc_reconnect_backoff * backoff_multiplier,
                                max_backoff,
                            )
                    except Exception as e:
                        logger.error(f"[MC RECONNECT] Reconnection error: {e}")
                        # Increase backoff on error
                        self._mc_reconnect_backoff = min(
                            self._mc_reconnect_backoff * backoff_multiplier, max_backoff
                        )
                else:
                    # Connected - reset backoff to initial value
                    self._mc_reconnect_backoff = 5.0

            except asyncio.CancelledError:
                logger.debug("[MC RECONNECT] Reconnect loop cancelled")
                break
            except Exception as e:
                logger.error(f"[MC RECONNECT] Loop error: {e}")
                await asyncio.sleep(check_interval)

    async def _browser_heartbeat_loop(self):
        """
        Send periodic heartbeat pings to browser clients and remove unresponsive ones.

        Sends ping every 15 seconds. Clients must respond with pong within 10 seconds.
        Clients that miss 2 consecutive pongs are disconnected.
        """
        ping_interval = 15.0  # Send ping every 15 seconds
        max_missed_pongs = 2  # Remove client after this many missed pongs

        while self._running:
            try:
                await asyncio.sleep(ping_interval)

                if not self._broadcast_clients:
                    continue

                # Send ping to all browser clients
                ping_message = _json_str({"type": "ping"})
                current_time = time.time()
                dead_clients = set()

                for client in list(self._broadcast_clients):
                    try:
                        # Check if previous pong was missed
                        if client in self._browser_pong_pending:
                            last_ping_time = self._browser_pong_pending[client]["ping_time"]
                            last_pong_time = self._browser_last_pong.get(client, 0)

                            # If no pong received since last ping
                            if last_pong_time < last_ping_time:
                                missed_count = (
                                    self._browser_pong_pending[client].get("missed", 0) + 1
                                )
                                self._browser_pong_pending[client]["missed"] = missed_count

                                if missed_count >= max_missed_pongs:
                                    logger.info(
                                        f"[BROWSER HEARTBEAT] Removing client {client.remote_address} - missed {missed_count} pongs"
                                    )
                                    dead_clients.add(client)
                                    try:
                                        await client.close(4100, "Heartbeat timeout")
                                    except Exception:
                                        pass
                                    continue
                            else:
                                # Pong received, reset missed count
                                self._browser_pong_pending[client]["missed"] = 0

                        # Send new ping
                        await client.send(ping_message)
                        self._browser_pong_pending[client] = {
                            "ping_time": current_time,
                            "missed": self._browser_pong_pending.get(client, {}).get("missed", 0),
                        }

                    except websockets.exceptions.ConnectionClosed:
                        dead_clients.add(client)
                    except Exception as e:
                        logger.debug(f"[BROWSER HEARTBEAT] Error pinging client: {e}")
                        dead_clients.add(client)

                # Remove dead clients
                if dead_clients:
                    self._broadcast_clients -= dead_clients
                    for client in dead_clients:
                        self._browser_pong_pending.pop(client, None)
                        self._browser_last_pong.pop(client, None)

            except asyncio.CancelledError:
                logger.debug("[BROWSER HEARTBEAT] Heartbeat loop cancelled")
                break
            except Exception as e:
                logger.error(f"[BROWSER HEARTBEAT] Loop error: {e}")
                await asyncio.sleep(ping_interval)

    async def _update_minecraft(
        self,
        entities: List[dict],
        bands: List[float],
        peak: float,
        is_beat: bool,
        beat_intensity: float,
        bpm: float = 0.0,
        tempo_confidence: float = 0.0,
        beat_phase: float = 0.0,
    ):
        """Send entities to Minecraft."""
        if not self.viz_client or not self.viz_client.connected:
            return

        try:
            # Track the high-water mark of entities in the Minecraft pool.
            # Hide any pool entities not covered by the current frame to
            # prevent "ghost" blocks stuck at their last position.
            entity_count = len(entities)
            self._minecraft_pool_size = max(self._minecraft_pool_size, entity_count)
            if entity_count < self._minecraft_pool_size:
                covered_ids = {e.get("id") for e in entities}
                for i in range(self._minecraft_pool_size):
                    eid = f"block_{i}"
                    if eid not in covered_ids:
                        entities.append({"id": eid, "scale": 0})
            # After a deferred pool shrink completes, lower the high-water mark
            if not self._transitioning and self._transition_pending_resize is None:
                self._minecraft_pool_size = self.entity_count

            # Sanitize entity data before forwarding to Minecraft
            entities = _sanitize_entities(
                entities, max_count=max(len(entities), self.entity_count * 2)
            )

            particles = []
            if is_beat and beat_intensity > 0.2:
                particles.append(
                    {
                        "particle": "NOTE",
                        "x": 0.5,
                        "y": 0.5,
                        "z": 0.5,
                        "count": max(1, min(100, int(20 * beat_intensity))),
                    }
                )

            # Clamp audio values forwarded to Minecraft
            safe_bands = [max(0.0, min(1.0, b)) for b in bands[:5]]
            audio = {
                "bands": safe_bands,
                "amplitude": max(0.0, min(5.0, peak)),
                "is_beat": bool(is_beat),
                "beat_intensity": max(0.0, min(5.0, beat_intensity)),
                "bpm": max(0.0, min(300.0, bpm)),
                "tempo_confidence": max(0.0, min(1.0, tempo_confidence)),
                "beat_phase": max(0.0, min(1.0, beat_phase)),
            }
            await self.viz_client.batch_update_fast(self.zone, entities, particles, audio)
        except Exception as e:
            logger.error(f"Minecraft update error: {e}")

    async def _update_minecraft_zone(
        self,
        zone_name: str,
        zone_state: Optional[ZonePatternState],
        entities: List[dict],
        bands: List[float],
        peak: float,
        is_beat: bool,
        beat_intensity: float,
        bpm: float = 0.0,
        tempo_confidence: float = 0.0,
        beat_phase: float = 0.0,
    ):
        """Send entities for a specific zone to Minecraft."""
        if not self.viz_client or not self.viz_client.connected:
            return
        if zone_state is None:
            return

        # Bitmap zones: send lightweight audio update only (patterns run on MC side)
        if zone_state.render_mode == "bitmap":
            try:
                safe_bands = [max(0.0, min(1.0, b)) for b in bands[:5]]
                audio = {
                    "bands": safe_bands,
                    "amplitude": max(0.0, min(5.0, peak)),
                    "is_beat": bool(is_beat),
                    "beat_intensity": max(0.0, min(5.0, beat_intensity)),
                    "bpm": max(0.0, min(300.0, bpm)),
                    "tempo_confidence": max(0.0, min(1.0, tempo_confidence)),
                    "beat_phase": max(0.0, min(1.0, beat_phase)),
                }
                await self.viz_client.batch_update_fast(zone_name, [], [], audio)
            except Exception as e:
                logger.error(f"Bitmap audio update error for zone '{zone_name}': {e}")
            return

        try:
            entity_count = len(entities)
            zone_state.minecraft_pool_size = max(zone_state.minecraft_pool_size, entity_count)
            if entity_count < zone_state.minecraft_pool_size:
                covered_ids = {e.get("id") for e in entities}
                for i in range(zone_state.minecraft_pool_size):
                    eid = f"block_{i}"
                    if eid not in covered_ids:
                        entities.append({"id": eid, "scale": 0})
            if not zone_state.transitioning and zone_state.transition_pending_resize is None:
                zone_state.minecraft_pool_size = zone_state.entity_count

            entities = _sanitize_entities(
                entities, max_count=max(len(entities), zone_state.entity_count * 2)
            )

            particles = []
            if is_beat and beat_intensity > 0.2:
                particles.append(
                    {
                        "particle": "NOTE",
                        "x": 0.5,
                        "y": 0.5,
                        "z": 0.5,
                        "count": max(1, min(100, int(20 * beat_intensity))),
                    }
                )

            safe_bands = [max(0.0, min(1.0, b)) for b in bands[:5]]
            audio = {
                "bands": safe_bands,
                "amplitude": max(0.0, min(5.0, peak)),
                "is_beat": bool(is_beat),
                "beat_intensity": max(0.0, min(5.0, beat_intensity)),
                "bpm": max(0.0, min(300.0, bpm)),
                "tempo_confidence": max(0.0, min(1.0, tempo_confidence)),
                "beat_phase": max(0.0, min(1.0, beat_phase)),
            }
            await self.viz_client.batch_update_fast(zone_name, entities, particles, audio)
        except Exception as e:
            logger.error(f"Minecraft update error for zone '{zone_name}': {e}")

    async def _relay_voice_audio(self, data: dict):
        """Relay a voice_audio message from the active DJ to Minecraft."""
        if not self.viz_client or not self.viz_client.connected:
            return

        # Validate required fields
        pcm_data = data.get("data")
        seq = data.get("seq", 0)
        if not isinstance(pcm_data, str):
            return

        try:
            await self.viz_client.send_voice_frame(pcm_data, seq)
        except Exception:
            pass  # Fire and forget

        # Broadcast to voice subscribers (Discord bot, etc.)
        if self._voice_subscribers:
            voice_msg = _json_str(
                {
                    "type": "voice_audio",
                    "data": pcm_data,
                    "seq": seq,
                    "codec": data.get("codec", "pcm"),
                }
            )
            dead_clients = set()
            for client in list(self._voice_subscribers):
                try:
                    await client.send(voice_msg)
                except Exception:
                    dead_clients.add(client)
            self._voice_subscribers -= dead_clients

    async def _forward_voice_config(self, config: dict):
        """Forward voice_config to the Minecraft plugin and relay voice_status response."""
        if not self.viz_client or not self.viz_client.connected:
            return

        response = await self.viz_client.send_voice_config(config)
        if response and response.get("type") == "voice_status":
            # Broadcast voice_status to all connected browser clients
            await self._broadcast_voice_status(response)

    async def _broadcast_voice_status(self, status: dict):
        """Broadcast voice_status to all connected browser clients."""
        await self._broadcast_to_browsers(_json_str(status))

    async def _link_sync_loop(self):
        """Poll Ableton Link for tempo/beat/phase at ~60Hz."""
        prev_peers = 0
        while self._running:
            try:
                if self._link is None:
                    await asyncio.sleep(1.0)
                    continue

                state = self._link.captureSessionState()
                tempo = state.tempo()
                beat = state.beatAtTime(self._link.clock(), 4)
                phase = beat % 1.0  # 0.0 - 1.0 within a beat
                peers = self._link.numPeers()

                self._link_tempo = tempo
                self._link_beat_phase = max(0.0, min(1.0, phase))
                self._link_peers = peers

                # Override active DJ's tempo/phase when Link has peers
                if peers > 0:
                    dj = self.active_dj
                    if dj:
                        dj.bpm = tempo
                        dj.beat_phase = self._link_beat_phase

                    # Log peer changes
                    if peers != prev_peers:
                        logger.info(f"[LINK] Peers: {peers}, Tempo: {tempo:.1f} BPM")
                        # Broadcast Link status to admin panel
                        await self._broadcast_to_browsers(
                            _json_str(
                                {
                                    "type": "link_status",
                                    "enabled": True,
                                    "peers": peers,
                                    "tempo": round(tempo, 1),
                                }
                            )
                        )

                prev_peers = peers
                await asyncio.sleep(1.0 / 60.0)  # ~60Hz

            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.debug(f"[LINK] Error in sync loop: {e}")
                await asyncio.sleep(1.0)

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

        try:
            await self._main_loop()
        finally:
            # Cancel reconnect task
            if self._mc_reconnect_task:
                self._mc_reconnect_task.cancel()
                try:
                    await self._mc_reconnect_task
                except asyncio.CancelledError:
                    pass
            # Cancel browser heartbeat task
            if self._browser_heartbeat_task:
                self._browser_heartbeat_task.cancel()
                try:
                    await self._browser_heartbeat_task
                except asyncio.CancelledError:
                    pass
            # Cancel pattern hot-reload task
            if self._pattern_hot_reload_task:
                self._pattern_hot_reload_task.cancel()
                try:
                    await self._pattern_hot_reload_task
                except asyncio.CancelledError:
                    pass
            # Cancel coordinator heartbeat task
            if self._coordinator_heartbeat_task:
                self._coordinator_heartbeat_task.cancel()
                try:
                    await self._coordinator_heartbeat_task
                except asyncio.CancelledError:
                    pass
            # Cancel Link sync task
            if self._link_task:
                self._link_task.cancel()
                try:
                    await self._link_task
                except asyncio.CancelledError:
                    pass
            # Disable Link
            if self._link is not None:
                try:
                    self._link.enabled = False
                except Exception:
                    pass
            dj_server.close()
            broadcast_server.close()
            if metrics_server:
                metrics_server.close()
                await metrics_server.wait_closed()
            await dj_server.wait_closed()
            await broadcast_server.wait_closed()

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
