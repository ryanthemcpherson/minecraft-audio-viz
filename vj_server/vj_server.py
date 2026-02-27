"""
VJ Server - Central server for multi-DJ audio visualization.

Accepts connections from multiple remote DJs, manages active DJ selection,
and forwards visualization data to Minecraft and browser clients.

Usage:
    python -m vj_server.vj_server --config configs/dj_auth.json

Architecture:
    DJ 1 (Remote) â”€â”€â”
    DJ 2 (Remote) â”€â”€â”¼â”€â”€> VJ Server â”€â”€> Minecraft + Browsers
    DJ 3 (Remote) â”€â”€â”˜
                    â†‘
                VJ Admin Panel
"""

import argparse
import asyncio
import base64
import bisect
import http.server
import json
import logging
import math
import os
import posixpath
import re
import secrets
import signal
import socketserver
import sys
import threading
import time
import urllib.parse
from collections import deque
from dataclasses import dataclass, field, replace
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
from vj_server.config import ServerConfig
from vj_server.coordinator_client import CoordinatorClient
from vj_server.patterns import (
    AudioState,
    PatternConfig,
    _lua_pattern_exists,
    get_pattern,
    get_recommended_entity_count,
    list_patterns,
    refresh_pattern_cache,
)
from vj_server.spectrograph import TerminalSpectrograph
from vj_server.viz_client import VizClient

# Feature flag for threaded Lua execution.
#   MCAV_ASYNC_LUA=1  → force enabled
#   MCAV_ASYNC_LUA=0  → force disabled
#   unset / auto       → enabled only on free-threaded Python (3.13t+)
#
# With the GIL, threading is counterproductive: ~85% of calculate_entities()
# is Python code (_unpack_flat, _smooth_entity) that holds the GIL, causing
# ~10x slowdown from context-switch overhead.  On free-threaded builds (3.14t)
# threads run truly in parallel → 1.6-2.1x speedup at 5 zones.
_async_lua_env = os.environ.get("MCAV_ASYNC_LUA", "auto").lower()
if _async_lua_env == "1":
    _USE_ASYNC_LUA = True
elif _async_lua_env == "0":
    _USE_ASYNC_LUA = False
else:
    # Auto-detect: enable only when the GIL is disabled (free-threaded build)
    _gil_check = getattr(sys, "_is_gil_enabled", None)
    _USE_ASYNC_LUA = _gil_check is not None and not _gil_check()

# ---------------------------------------------------------------------------
# Input validation helpers (security hardening)
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# msgspec Structs for hot-path decode
# ---------------------------------------------------------------------------


class DjAudioFrame(msgspec.Struct):
    """Typed struct for dj_audio_frame messages (60fps hot path)."""

    type: str
    bands: List[float] = []
    peak: float = 0.0
    beat: bool = False
    bpm: float = 120.0
    beat_i: float = 0.0
    i_bass: float = 0.0
    i_kick: bool = False
    seq: int = 0
    ts: Optional[float] = None
    tempo_conf: float = 0.0
    beat_phase: float = 0.0


_frame_decoder = msgspec.json.Decoder(DjAudioFrame)


class ZoneStatus(msgspec.Struct):
    """Zone timing/tempo info embedded in state broadcasts."""

    bpm_estimate: float = 0.0
    tempo_confidence: float = 0.0
    beat_phase: float = 0.0


class VizStateBroadcast(msgspec.Struct):
    """Typed struct for the 60fps browser state broadcast message."""

    type: str = "state"
    entities: list = []
    bands: list = []
    amplitude: float = 0.0
    amplitude_raw: float = 0.0
    is_beat: bool = False
    beat_intensity: float = 0.0
    instant_bass: float = 0.0
    instant_kick: bool = False
    frame: int = 0
    pattern: str = ""
    active_dj: Optional[dict] = None
    latency_ms: float = 0.0
    ping_ms: float = 0.0
    pipeline_latency_ms: float = 0.0
    fps: float = 0.0
    jitter_ms: float = 0.0
    sync_confidence: float = 0.0
    visual_delay_ms: float = 0.0
    visual_delay_mode: str = "manual"
    zone_status: Optional[ZoneStatus] = None
    zone_patterns: Optional[dict] = None
    zone_entities: Optional[dict] = None
    perf: Optional[dict] = None


# Regex for stripping non-printable characters (keeps printable ASCII + common Unicode)
_NONPRINTABLE_RE = re.compile(r"[\x00-\x1f\x7f-\x9f]")

# Regex for valid Minecraft-style material names
_MATERIAL_RE = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")


def _json_str(data) -> str:
    """Encode to JSON string for WebSocket text frames.

    msgspec.json.encode() returns bytes which websockets sends as binary
    frames.  JavaScript's JSON.parse() and Java's onMessage(String) both
    require text frames, so we must decode to str before sending.
    """
    return mjson.encode(data).decode()


def _sanitize_name(value: str, max_length: int = 64, default: str = "DJ") -> str:
    """Sanitize a DJ name or ID: strip non-printable chars, enforce length limit."""
    value = _NONPRINTABLE_RE.sub("", value).strip()
    if not value:
        return default
    return value[:max_length]


def _clamp_finite(val, lo: float, hi: float, default: float) -> float:
    """Clamp a numeric value to [lo, hi], replacing non-finite/non-numeric with default."""
    if not isinstance(val, (int, float)):
        return default
    val = float(val)
    if not math.isfinite(val):
        return default
    return max(lo, min(hi, val))


def _sanitize_audio_frame(data: DjAudioFrame | dict) -> dict:
    """Validate and clamp an incoming DJ audio frame.

    Accepts either a DjAudioFrame struct (hot path) or a dict (tests/legacy
    defensive paths), then applies value clamping per dj-audio-frame.schema.json:
    - bands: exactly 5 floats in [0.0, 1.0]
    - peak, beat_i, i_bass: floats >= 0 (capped at 5.0)
    - bpm: float in [0.0, 300.0]
    - tempo_conf: float in [0.0, 1.0]
    - beat_phase: float in [0.0, 1.0]
    - seq: int >= 0
    - beat, i_kick: booleans
    """

    def _get(key: str, default):
        if isinstance(data, dict):
            return data.get(key, default)
        return getattr(data, key, default)

    # Bands: clamp to exactly 5 floats in [0, 1]
    raw_bands = _get("bands", [])
    if isinstance(raw_bands, list):
        bands = [_clamp_finite(b, 0.0, 1.0, 0.0) for b in raw_bands[:5]]
        while len(bands) < 5:
            bands.append(0.0)
    else:
        bands = [0.0] * 5

    return {
        "bands": bands,
        "peak": _clamp_finite(_get("peak", 0.0), 0.0, 5.0, 0.0),
        "beat": bool(_get("beat", False)),
        "beat_i": _clamp_finite(_get("beat_i", 0.0), 0.0, 5.0, 0.0),
        "bpm": _clamp_finite(_get("bpm", 120.0), 0.0, 300.0, 120.0),
        "tempo_conf": _clamp_finite(_get("tempo_conf", 0.0), 0.0, 1.0, 0.0),
        "beat_phase": _clamp_finite(_get("beat_phase", 0.0), 0.0, 1.0, 0.0),
        "seq": max(0, int(_clamp_finite(_get("seq", 0), 0.0, 1_000_000_000.0, 0.0))),
        "i_bass": _clamp_finite(_get("i_bass", 0.0), 0.0, 5.0, 0.0),
        "i_kick": bool(_get("i_kick", False)),
        "ts": _get("ts", None),  # validated separately in latency calc
    }


def _sanitize_entities(entities: list, max_count: int = 512) -> list:
    """Clamp entity fields to safe ranges before forwarding to Minecraft.

    Enforces the constraints from entity-update.schema.json:
    - x, y, z: [0.0, 1.0]
    - scale: [0.0, 4.0]
    - rotation: [0.0, 360.0]
    - brightness: [0, 15]
    - interpolation: [0, 100]
    """
    if not isinstance(entities, list):
        return []
    result = []
    for e in entities[:max_count]:
        if not isinstance(e, dict):
            continue
        clean = {}
        # ID is required
        eid = e.get("id")
        if not isinstance(eid, str) or not eid:
            continue
        clean["id"] = eid
        # Coordinates
        if "x" in e:
            clean["x"] = _clamp_finite(e["x"], 0.0, 1.0, 0.5)
        if "y" in e:
            clean["y"] = _clamp_finite(e["y"], 0.0, 1.0, 0.0)
        if "z" in e:
            clean["z"] = _clamp_finite(e["z"], 0.0, 1.0, 0.5)
        # Scale, rotation
        if "scale" in e:
            clean["scale"] = _clamp_finite(e["scale"], 0.0, 4.0, 0.5)
        if "rotation" in e:
            clean["rotation"] = _clamp_finite(e["rotation"], 0.0, 360.0, 0.0)
        # Brightness (integer 0-15)
        if "brightness" in e:
            clean["brightness"] = int(_clamp_finite(e["brightness"], 0, 15, 15))
        # Interpolation (integer ticks)
        if "interpolation" in e:
            clean["interpolation"] = int(_clamp_finite(e["interpolation"], 0, 100, 3))
        # Boolean fields
        if "glow" in e:
            clean["glow"] = bool(e["glow"])
        if "visible" in e:
            clean["visible"] = bool(e["visible"])
        # Material (validate against safe pattern, default to STONE)
        if "material" in e and isinstance(e["material"], str):
            mat = e["material"]
            clean["material"] = mat if _MATERIAL_RE.match(mat) else "STONE"
        result.append(clean)
    return result


# Messages that should be forwarded directly to Minecraft
# These are zone/rendering settings that the VJ server doesn't handle locally
FORWARD_TO_MINECRAFT = {
    "set_zone_config",
    "set_render_mode",
    "set_renderer_backend",
    "renderer_capabilities",
    "get_renderer_capabilities",
    "set_hologram_config",
    "set_particle_viz_config",
    "set_particle_effect",
    "set_particle_config",
    "init_pool",
    "cleanup_zone",
    "set_entity_glow",
    "set_entity_brightness",
    "banner_config",
    # Bitmap LED wall messages
    "init_bitmap",
    "teardown_bitmap",
    "get_bitmap_status",
    "set_bitmap_pattern",
    "get_bitmap_patterns",
    "bitmap_transition",
    "get_bitmap_transitions",
    "bitmap_palette",
    "get_bitmap_palettes",
    "bitmap_effects",
    "bitmap_marquee",
    "bitmap_track_display",
    "bitmap_countdown",
    "bitmap_chat",
    "bitmap_layer",
    "bitmap_firework",
    "bitmap_composition",
    "bitmap_image",
    "bitmap_dj_logo",
}

# Subset of FORWARD_TO_MINECRAFT that don't need a response relayed back.
# Fire-and-forget: send to Minecraft, immediately ack to the browser client.
FIRE_AND_FORGET = {
    "bitmap_dj_logo",
    "bitmap_effects",
    "bitmap_palette",
    "bitmap_marquee",
    "bitmap_track_display",
    "bitmap_countdown",
    "bitmap_chat",
    "bitmap_layer",
    "bitmap_firework",
    "bitmap_image",
    "set_bitmap_pattern",
    "bitmap_transition",
    "set_particle_effect",
    "set_particle_config",
    "set_particle_viz_config",
    "banner_config",
}

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("vj_server")


# Memorable words for connect codes (no confusables)
CONNECT_CODE_WORDS = [
    "BEAT",
    "BASS",
    "DROP",
    "WAVE",
    "KICK",
    "SYNC",
    "LOOP",
    "VIBE",
    "RAVE",
    "FUNK",
    "JAZZ",
    "ROCK",
    "FLOW",
    "PEAK",
    "PUMP",
    "TUNE",
    "PLAY",
    "SPIN",
    "FADE",
    "RISE",
    "BOOM",
    "DRUM",
    "HIGH",
    "DEEP",
]

# Valid characters for code suffix (no confusables: O/0/I/1/L)
CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"


@dataclass
class ConnectCode:
    """A temporary connect code for DJ authentication."""

    code: str  # Format: WORD-XXXX (e.g., BEAT-7K3M)
    created_at: float = field(default_factory=time.time)
    expires_at: float = 0.0
    used: bool = False

    def __post_init__(self):
        if self.expires_at == 0.0:
            # Default 30 minute TTL
            self.expires_at = self.created_at + 30 * 60

    def is_valid(self) -> bool:
        """Check if code is still valid (not expired and not used)."""
        return not self.used and time.time() < self.expires_at

    @staticmethod
    def generate(ttl_minutes: int = 30) -> "ConnectCode":
        """Generate a new connect code."""
        # Pick a random word
        word = secrets.choice(CONNECT_CODE_WORDS)
        # Generate 4 random characters
        suffix = "".join(secrets.choice(CODE_CHARS) for _ in range(4))
        code = f"{word}-{suffix}"

        now = time.time()
        return ConnectCode(code=code, created_at=now, expires_at=now + ttl_minutes * 60)


@dataclass
class DJConnection:
    """Represents a connected DJ."""

    dj_id: str
    dj_name: str
    websocket: "websockets.WebSocketServerProtocol"  # Type hint for websocket connection
    connected_at: float = field(default_factory=time.time)
    last_frame_at: float = field(default_factory=time.time)
    last_heartbeat: float = field(default_factory=time.time)
    frame_count: int = 0
    priority: int = 10  # Lower = higher priority

    # Last audio state from this DJ (5 bands: bass, low-mid, mid, high-mid, high)
    bands: List[float] = field(default_factory=lambda: [0.0] * 5)
    peak: float = 0.0
    is_beat: bool = False
    beat_intensity: float = 0.0
    bpm: float = 120.0
    tempo_confidence: float = 0.0
    beat_phase: float = 0.0
    seq: int = 0

    # Bass lane (instant kick detection, ~1ms latency vs ~15ms for FFT)
    instant_bass: float = 0.0
    instant_kick: bool = False

    # Connection health
    # Display latency metric shown in admin (prefer heartbeat RTT "ping")
    latency_ms: float = 0.0
    # Network RTT measured from heartbeat echo timestamps
    network_rtt_ms: float = 0.0
    # End-to-end pipeline latency inferred from dj_audio_frame timestamps
    pipeline_latency_ms: float = 0.0
    frames_per_second: float = 0.0
    _fps_samples: deque = field(default_factory=lambda: deque(maxlen=120))

    # Clock synchronization - offset to add to DJ timestamps to match server time
    # Positive offset means DJ clock is behind server clock
    clock_offset: float = 0.0  # seconds
    clock_sync_done: bool = False
    _clock_sync_count: int = 0  # Number of successful clock syncs
    _last_clock_resync: float = 0.0  # Timestamp of last successful resync
    _clock_drift_rate: float = 0.0  # ms/sec drift rate
    _rtt_samples: deque = field(
        default_factory=lambda: deque(maxlen=30)
    )  # Recent RTT samples for variance

    # Direct mode support
    direct_mode: bool = False  # Whether this DJ is using direct Minecraft connection
    mc_connected: bool = False  # Whether DJ's direct Minecraft connection is alive
    phase_assist_last_time: float = 0.0  # Last phase-assisted beat fire time

    # Voice streaming state
    voice_streaming: bool = False  # Whether this DJ is sending voice audio

    # DJ profile data (populated from coordinator on connect)
    avatar_url: Optional[str] = None
    color_palette: Optional[List[str]] = None
    block_palette: Optional[List[str]] = None
    slug: Optional[str] = None
    bio: Optional[str] = None
    genres: Optional[str] = None

    # Frame buffer for visual delay (timestamped audio state ring buffer)
    _frame_buffer: deque = field(default_factory=deque)  # (timestamp, data) pairs; trimmed manually

    # Jitter tracking
    _jitter_ms: float = 0.0  # Smoothed frame arrival jitter
    _frame_gaps: deque = field(
        default_factory=lambda: deque(maxlen=60)
    )  # Recent inter-frame intervals
    _last_frame_arrival: float = 0.0  # Timestamp of last frame arrival

    # Rate limiting (token bucket: 120 tokens/sec, 2x expected 60fps)
    _rate_tokens: float = 120.0
    _rate_last_refill: float = field(default_factory=time.time)

    def check_rate_limit(self) -> bool:
        """Check if this DJ is within the frame rate limit. Returns True if allowed."""
        now = time.time()
        elapsed = now - self._rate_last_refill
        self._rate_last_refill = now
        # Refill tokens (120 per second, max bucket size 120)
        self._rate_tokens = min(120.0, self._rate_tokens + elapsed * 120.0)
        if self._rate_tokens >= 1.0:
            self._rate_tokens -= 1.0
            return True
        return False

    def update_fps(self):
        """Update FPS calculation."""
        now = time.time()
        self._fps_samples.append(now)
        # Evict samples older than 1 second from the left
        cutoff = now - 1.0
        while self._fps_samples and self._fps_samples[0] <= cutoff:
            self._fps_samples.popleft()
        self.frames_per_second = len(self._fps_samples)


@dataclass
class ZonePatternState:
    """Per-zone pattern state for independent zone patterns."""

    pattern_name: str = "spectrum"
    pattern: Any = None  # LuaPattern instance
    config: Optional["PatternConfig"] = None
    entity_count: int = 16
    # Per-zone crossfade transition
    transitioning: bool = False
    transition_start: float = 0.0
    transition_duration: float = 1.0
    old_pattern: Any = None
    old_pattern_name: Optional[str] = None
    transition_pending_resize: Optional[int] = None
    minecraft_pool_size: int = 0
    last_entities: List[dict] = field(default_factory=list)
    block_type: str = "SEA_LANTERN"
    bitmap_initialized: bool = False
    bitmap_width: int = 0
    bitmap_height: int = 0
    render_mode: str = "block"  # "block" (Display Entities) or "bitmap" (LED wall)


@dataclass
class DJAuthConfig:
    """DJ authentication configuration."""

    djs: Dict[str, dict] = field(default_factory=dict)
    vj_operators: Dict[str, dict] = field(default_factory=dict)

    @classmethod
    def load(cls, filepath: str) -> "DJAuthConfig":
        """Load auth config from file."""
        try:
            with open(filepath, "r") as f:
                data = json.load(f)
            config = cls(djs=data.get("djs", {}), vj_operators=data.get("vj_operators", {}))
            config._warn_plaintext_passwords()
            return config
        except Exception as e:
            logger.error(f"Failed to load auth config: {e}")
            return cls()

    @classmethod
    def from_dict(cls, data: dict) -> "DJAuthConfig":
        """Create from a dictionary (e.g., parsed JSON)."""
        config = cls(djs=data.get("djs", {}), vj_operators=data.get("vj_operators", {}))
        config._warn_plaintext_passwords()
        return config

    def _warn_plaintext_passwords(self) -> list:
        """Check for plaintext passwords and reject them. Returns list of offending IDs."""
        plaintext_ids = []
        for section_name, section in [
            ("djs", self.djs),
            ("vj_operators", self.vj_operators),
        ]:
            for entry_id, entry in section.items():
                key_hash = entry.get("key_hash", "")
                if key_hash and not key_hash.startswith(("bcrypt:", "sha256:")):
                    plaintext_ids.append(f"{section_name}/{entry_id}")
        if plaintext_ids:
            raise ValueError(
                f"Plaintext passwords detected in auth config for: {', '.join(plaintext_ids)}. "
                f"Hash them with: python -m vj_server.auth hash <password>"
            )
        return plaintext_ids

    def has_plaintext_passwords(self) -> bool:
        """Return True if any entries have plaintext (unhashed) passwords."""
        for section in [self.djs, self.vj_operators]:
            for entry in section.values():
                key_hash = entry.get("key_hash", "")
                if key_hash and not key_hash.startswith(("bcrypt:", "sha256:")):
                    return True
        return False

    def verify_dj(self, dj_id: str, key: str) -> Optional[dict]:
        """Verify DJ credentials. Returns DJ info if valid."""
        if dj_id not in self.djs:
            return None
        dj = self.djs[dj_id]
        expected_hash = dj.get("key_hash", "")

        # Use auth module for secure password verification
        from vj_server.auth import verify_password

        if not verify_password(key, expected_hash):
            return None

        return dj

    def verify_vj(self, vj_id: str, key: str) -> Optional[dict]:
        """Verify VJ operator credentials."""
        if vj_id not in self.vj_operators:
            return None
        vj = self.vj_operators[vj_id]
        expected_hash = vj.get("key_hash", "")

        # Use auth module for secure password verification
        from vj_server.auth import verify_password

        if not verify_password(key, expected_hash):
            return None

        return vj


def _make_directory_handler(directory_map: dict):
    """Create a handler class with its own directory_map to avoid shared mutable state."""

    class _Handler(http.server.SimpleHTTPRequestHandler):
        """HTTP handler that serves from multiple directories."""

        _directory_map = directory_map

        def _safe_join(self, base: str, path: str) -> str:
            path = path.split("?", 1)[0].split("#", 1)[0]
            path = posixpath.normpath(urllib.parse.unquote(path))
            words = [word for word in path.split("/") if word not in ("", ".", "..")]
            full_path = base
            for word in words:
                full_path = os.path.join(full_path, word)
            return full_path

        def translate_path(self, path):
            path = path.split("?", 1)[0].split("#", 1)[0]
            for url_prefix, fs_directory in self._directory_map.items():
                if path == url_prefix or path.startswith(f"{url_prefix}/"):
                    relative_path = path[len(url_prefix) :].lstrip("/")
                    return self._safe_join(fs_directory, relative_path)
            if "/" in self._directory_map:
                return self._safe_join(self._directory_map["/"], path)
            return super().translate_path(path)

        def log_message(self, format, *args):
            pass

    return _Handler


class MultiDirectoryHandler(http.server.SimpleHTTPRequestHandler):
    """HTTP handler that serves from multiple directories (legacy, prefer _make_directory_handler)."""

    def __init__(self, *args, **kwargs):
        if not hasattr(self, "directory_map"):
            self.directory_map = {}
        super().__init__(*args, **kwargs)

    def _safe_join(self, base: str, path: str) -> str:
        path = path.split("?", 1)[0].split("#", 1)[0]
        path = posixpath.normpath(urllib.parse.unquote(path))
        words = [word for word in path.split("/") if word not in ("", ".", "..")]
        full_path = base
        for word in words:
            full_path = os.path.join(full_path, word)
        return full_path

    def translate_path(self, path):
        path = path.split("?", 1)[0].split("#", 1)[0]
        for url_prefix, fs_directory in self.directory_map.items():
            if path == url_prefix or path.startswith(f"{url_prefix}/"):
                relative_path = path[len(url_prefix) :].lstrip("/")
                return self._safe_join(fs_directory, relative_path)
        if "/" in self.directory_map:
            return self._safe_join(self.directory_map["/"], path)
        return super().translate_path(path)

    def log_message(self, format, *args):
        pass


def run_http_server(port: int, directory: str):
    """Run HTTP server for admin panel."""
    # directory is the project root
    project_root = Path(directory)
    admin_dir = project_root / "admin_panel"
    frontend_dir = project_root / "preview_tool" / "frontend"

    # Admin panel at root, preview at /preview (absolute paths, no os.chdir)
    dir_map = {
        "/preview": str(frontend_dir) if frontend_dir.exists() else str(directory),
        "/": str(admin_dir) if admin_dir.exists() else str(directory),
    }
    handler_cls = _make_directory_handler(dir_map)

    # Allow port reuse so restarts don't fail with "Address already in use"
    class ReusableTCPServer(socketserver.TCPServer):
        allow_reuse_address = True

    try:
        with ReusableTCPServer(("", port), handler_cls) as httpd:
            httpd.serve_forever()
    except OSError as e:
        logger.error(f"HTTP server failed to start on port {port}: {e}")
        logger.error("Another instance may be running. Kill it or use a different port.")


class VJServer:
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
            None,
            None,
            None,
            None,
            None,
        ]  # Per-band block overrides
        self._dj_palettes: Dict[str, list] = {}  # dj_id -> 5-element block palette
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

    # --- Per-zone pattern helpers & backward-compat properties ---

    def _get_zone_state(self, zone_name: str) -> ZonePatternState:
        """Get or create a ZonePatternState for the given zone."""
        if zone_name not in self._zone_patterns:
            config = PatternConfig(entity_count=self._pattern_config.entity_count)
            pattern = get_pattern("spectrum", config)
            self._zone_patterns[zone_name] = ZonePatternState(
                pattern_name="spectrum",
                pattern=pattern,
                config=config,
                entity_count=self._pattern_config.entity_count,
                transition_duration=self._default_transition_duration,
            )
        return self._zone_patterns[zone_name]

    @property
    def _current_pattern(self):
        """Backward-compat: delegates to active zone's pattern."""
        return self._get_zone_state(self.zone).pattern

    @_current_pattern.setter
    def _current_pattern(self, value):
        self._get_zone_state(self.zone).pattern = value

    @property
    def _pattern_name(self):
        """Backward-compat: delegates to active zone's pattern name."""
        return self._get_zone_state(self.zone).pattern_name

    @_pattern_name.setter
    def _pattern_name(self, value):
        self._get_zone_state(self.zone).pattern_name = value

    @property
    def _transitioning(self):
        """Backward-compat: delegates to active zone's transition state."""
        return self._get_zone_state(self.zone).transitioning

    @_transitioning.setter
    def _transitioning(self, value):
        self._get_zone_state(self.zone).transitioning = value

    @property
    def _transition_start(self):
        return self._get_zone_state(self.zone).transition_start

    @_transition_start.setter
    def _transition_start(self, value):
        self._get_zone_state(self.zone).transition_start = value

    @property
    def _transition_duration(self):
        return self._get_zone_state(self.zone).transition_duration

    @_transition_duration.setter
    def _transition_duration(self, value):
        self._get_zone_state(self.zone).transition_duration = value

    @property
    def _old_pattern(self):
        return self._get_zone_state(self.zone).old_pattern

    @_old_pattern.setter
    def _old_pattern(self, value):
        self._get_zone_state(self.zone).old_pattern = value

    @property
    def _old_pattern_name(self):
        return self._get_zone_state(self.zone).old_pattern_name

    @_old_pattern_name.setter
    def _old_pattern_name(self, value):
        self._get_zone_state(self.zone).old_pattern_name = value

    @property
    def _transition_pending_resize(self):
        return self._get_zone_state(self.zone).transition_pending_resize

    @_transition_pending_resize.setter
    def _transition_pending_resize(self, value):
        self._get_zone_state(self.zone).transition_pending_resize = value

    @property
    def _minecraft_pool_size(self):
        return self._get_zone_state(self.zone).minecraft_pool_size

    @_minecraft_pool_size.setter
    def _minecraft_pool_size(self, value):
        self._get_zone_state(self.zone).minecraft_pool_size = value

    @property
    def _last_entities(self):
        return self._get_zone_state(self.zone).last_entities

    @_last_entities.setter
    def _last_entities(self, value):
        self._get_zone_state(self.zone).last_entities = value

    def _get_zone_patterns_dict(self) -> Dict[str, dict]:
        """Get a dict mapping zone_name -> {pattern, render_mode} for all zones."""
        return {
            zn: {"pattern": zs.pattern_name, "render_mode": zs.render_mode}
            for zn, zs in self._zone_patterns.items()
        }

    def _get_bitmap_zones_dict(self) -> Dict[str, dict]:
        """Get bitmap init state for all zones (for vj_state sync)."""
        return {
            zn: {
                "initialized": zs.bitmap_initialized,
                "width": zs.bitmap_width,
                "height": zs.bitmap_height,
            }
            for zn, zs in self._zone_patterns.items()
            if zs.bitmap_initialized
        }

    def _get_all_patterns_list(self) -> list:
        """Get merged list of Lua patterns + bitmap patterns for the admin panel."""
        all_patterns = list(list_patterns())  # copy — don't mutate the global cache
        for p in self._bitmap_pattern_cache:
            all_patterns.append(
                {
                    "id": p.get("id", ""),
                    "name": p.get("name", p.get("id", "")),
                    "description": p.get("description", ""),
                    "category": "Bitmap",
                }
            )
        return all_patterns

    @property
    def active_dj(self) -> Optional[DJConnection]:
        """Get the currently active DJ."""
        # Snapshot to avoid TOCTOU race on _djs dict
        active_id = self._active_dj_id
        djs = self._djs
        if active_id:
            return djs.get(active_id)
        return None

    def _get_active_dj(self) -> Optional[DJConnection]:
        """Get the currently active DJ (non-property version for metrics)."""
        return self.active_dj

    async def _get_active_dj_safe(self) -> Optional[DJConnection]:
        """Thread-safe version of getting active DJ."""
        async with self._dj_lock:
            if self._active_dj_id and self._active_dj_id in self._djs:
                return self._djs[self._active_dj_id]
            return None

    def _dj_profile_dict(self, dj: DJConnection) -> dict:
        """Build a profile dict for broadcasting to browser clients."""
        return {
            "dj_id": dj.dj_id,
            "dj_name": dj.dj_name,
            "avatar_url": dj.avatar_url,
            "color_palette": dj.color_palette,
            "block_palette": dj.block_palette,
            "slug": dj.slug,
            "bio": dj.bio,
            "genres": dj.genres,
        }

    async def _hydrate_dj_profile(self, dj: DJConnection, coordinator_token: str) -> None:
        """Fetch DJ profile from coordinator and populate DJConnection fields.

        Decodes the coordinator JWT (without signature verification — the
        session ID is only used to call the coordinator's authenticated internal
        API, so a forged ID would simply get a 404) to extract dj_session_id,
        then fetches the profile. Never raises — logs warnings on failure.
        """
        try:
            payload_b64 = coordinator_token.split(".")[1]
            payload_b64 += "=" * (4 - len(payload_b64) % 4)
            payload = mjson.decode(base64.urlsafe_b64decode(payload_b64))
            dj_session_id = payload.get("sub")

            if dj_session_id:
                profile = await asyncio.wait_for(
                    self._coordinator.fetch_dj_profile(str(dj_session_id)),
                    timeout=5.0,
                )
                if profile:
                    dj.dj_name = profile.get("dj_name", dj.dj_name)
                    dj.avatar_url = profile.get("avatar_url")
                    dj.color_palette = profile.get("color_palette")
                    dj.block_palette = profile.get("block_palette")
                    dj.slug = profile.get("slug")
                    dj.bio = profile.get("bio")
                    dj.genres = profile.get("genres")
                    logger.info(
                        "[DJ PROFILE] Loaded profile for %s: slug=%s, %d colors, %d blocks",
                        dj.dj_name,
                        dj.slug,
                        len(dj.color_palette) if dj.color_palette else 0,
                        len(dj.block_palette) if dj.block_palette else 0,
                    )
        except Exception as exc:
            logger.warning("[DJ PROFILE] Failed to load profile for %s: %s", dj.dj_id, exc)

    def _get_dj_roster(self) -> List[dict]:
        """Get DJ roster for admin panel.

        Note: This takes a snapshot of the current DJ list. For concurrent-safe
        operations, the caller should use _dj_lock.
        """
        roster = []
        # Take snapshot of current DJs dict to avoid iteration issues
        djs_snapshot = dict(self._djs)
        active_dj_id = self._active_dj_id
        queue_snapshot = list(self._dj_queue)

        for dj_id, dj in djs_snapshot.items():
            # Determine queue position (0-based index in _dj_queue)
            queue_pos = queue_snapshot.index(dj_id) if dj_id in queue_snapshot else 999
            roster.append(
                {
                    "dj_id": dj_id,
                    "dj_name": dj.dj_name,
                    "is_active": dj_id == active_dj_id,
                    "connected_at": dj.connected_at,
                    "fps": round(dj.frames_per_second, 1),
                    "latency_ms": round(dj.latency_ms, 1),
                    "ping_ms": round(dj.network_rtt_ms, 1),
                    "pipeline_latency_ms": round(dj.pipeline_latency_ms, 1),
                    "bpm": round(dj.bpm, 1),
                    "tempo_confidence": round(dj.tempo_confidence, 3),
                    "beat_phase": round(dj.beat_phase, 3),
                    "priority": dj.priority,
                    "last_frame_age_ms": round((time.time() - dj.last_frame_at) * 1000, 0),
                    "direct_mode": dj.direct_mode,
                    "mc_connected": dj.mc_connected if dj.direct_mode else None,
                    "queue_position": queue_pos,
                    "jitter_ms": round(dj._jitter_ms, 1),
                    "clock_sync_count": dj._clock_sync_count,
                    "clock_drift_rate": round(dj._clock_drift_rate * 60 * 1000, 1),  # ms/min
                    "clock_sync_age_s": round(time.time() - dj._last_clock_resync, 0)
                    if dj._last_clock_resync > 0
                    else None,
                    "avatar_url": dj.avatar_url,
                    "color_palette": dj.color_palette,
                    "block_palette": dj.block_palette,
                    "slug": dj.slug,
                }
            )
        # Sort by queue position (respects manual reordering)
        roster.sort(key=lambda x: x["queue_position"])
        return roster

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

    async def _process_dj_heartbeat(self, dj: "DJConnection", websocket, frame_data: dict) -> None:
        """Process a dj_heartbeat message (shared between code_auth and dj_auth paths)."""
        now = time.time()
        dj.last_heartbeat = now
        reported_latency_ms = frame_data.get("latency_ms")
        heartbeat_ts = frame_data.get("ts")
        rtt_ms = None
        if isinstance(reported_latency_ms, (int, float)) and math.isfinite(reported_latency_ms):
            rtt_ms = max(0.0, min(float(reported_latency_ms), 60_000.0))
        elif isinstance(heartbeat_ts, (int, float)) and math.isfinite(heartbeat_ts):
            corrected_ts = (
                float(heartbeat_ts) - dj.clock_offset if dj.clock_sync_done else float(heartbeat_ts)
            )
            rtt_ms = max(0.0, min((now - corrected_ts) * 1000.0, 60_000.0))
        if rtt_ms is not None:
            if dj.network_rtt_ms > 0:
                dj.network_rtt_ms = dj.network_rtt_ms * 0.8 + rtt_ms * 0.2
            else:
                dj.network_rtt_ms = rtt_ms
            dj.latency_ms = dj.network_rtt_ms
            dj._rtt_samples.append(rtt_ms)
        if dj.direct_mode:
            dj.mc_connected = frame_data.get("mc_connected", False)
        await websocket.send(
            _json_str(
                {
                    "type": "heartbeat_ack",
                    "server_time": now,
                    "echo_ts": heartbeat_ts,
                }
            )
        )
        # Periodic clock resync (every 30s)
        if dj.clock_sync_done and now - dj._last_clock_resync >= 30.0:
            try:
                await websocket.send(_json_str({"type": "clock_sync_request", "server_time": now}))
                dj._last_clock_resync = now
            except Exception:
                pass

    def _check_auth_rate_limit(self, ip: str) -> bool:
        """Check if an IP has exceeded the auth rate limit. Returns True if rate limited."""
        now = time.time()
        window = self._auth_rate_limit_window

        # Periodic cleanup: by size threshold or every 60 seconds
        if len(self._auth_attempts) > 50 or (now - self._auth_last_cleanup) >= 60.0:
            stale_ips = [
                addr
                for addr, timestamps in self._auth_attempts.items()
                if not timestamps or timestamps[-1] < now - window
            ]
            for addr in stale_ips:
                del self._auth_attempts[addr]
            self._auth_last_cleanup = now

        if ip not in self._auth_attempts:
            self._auth_attempts[ip] = []

        # Remove timestamps outside the window
        attempts = self._auth_attempts[ip]
        self._auth_attempts[ip] = [t for t in attempts if t > now - window]
        attempts = self._auth_attempts[ip]

        if len(attempts) >= self._auth_rate_limit_max:
            return True  # rate limited

        # Record this attempt
        attempts.append(now)
        return False

    async def _handle_dj_connection(self, websocket):
        """Handle an incoming DJ connection."""
        dj_id = None

        try:
            # Wait for authentication message
            auth_timeout = 10.0  # 10 second timeout for auth
            try:
                message = await asyncio.wait_for(websocket.recv(), timeout=auth_timeout)
            except asyncio.TimeoutError:
                logger.warning("DJ connection timed out waiting for auth")
                await websocket.close(4001, "Authentication timeout")
                return

            try:
                data = mjson.decode(message)
            except msgspec.DecodeError:
                await websocket.close(4002, "Invalid JSON")
                return

            # Rate limit auth attempts per IP
            client_ip = websocket.remote_address[0] if websocket.remote_address else "unknown"
            if self._check_auth_rate_limit(client_ip):
                logger.warning("Auth rate limited for IP %s", client_ip)
                await websocket.close(4029, "Too many auth attempts, try again later")
                return

            msg_type = data.get("type")

            # Support both traditional auth and code-based auth
            if msg_type == "code_auth":
                # Code-based authentication (from DJ client)
                code = data.get("code", "").upper()
                dj_name = _sanitize_name(data.get("dj_name", "DJ"), default="DJ")

                # Slur filter on DJ name
                try:
                    from vj_server.content_filter import contains_slur as _contains_slur

                    if _contains_slur(dj_name):
                        logger.warning("DJ code auth rejected: DJ name failed content filter")
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "auth_error",
                                    "error": "DJ name contains language that is not allowed",
                                }
                            )
                        )
                        await websocket.close(4005, "Content policy violation")
                        return
                except ImportError:
                    logger.warning(
                        "better-profanity not installed — DJ name content filter disabled"
                    )

                # Validate connect code (locked to prevent race condition
                # where two concurrent auths could both pass is_valid())
                async with self._dj_lock:
                    if code not in self._connect_codes:
                        logger.warning(f"DJ code auth failed: invalid code {code}")
                        await websocket.send(
                            _json_str({"type": "auth_error", "error": "Invalid connect code"})
                        )
                        await websocket.close(4004, "Invalid connect code")
                        return

                    connect_code = self._connect_codes[code]
                    if not connect_code.is_valid():
                        logger.warning(f"DJ code auth failed: expired code {code}")
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "auth_error",
                                    "error": "Connect code has expired",
                                }
                            )
                        )
                        await websocket.close(4004, "Connect code expired")
                        return

                    # Mark code as used (atomically with validation)
                    connect_code.used = True

                # Generate a unique DJ ID for code-authenticated users
                dj_id = f"dj_{code.replace('-', '_').lower()}"
                priority = 10  # Default priority for code-authenticated DJs

                logger.info(f"DJ code auth successful: {dj_name} with code {code}")

                # Connect-code DJs go into pending approval queue
                direct_mode = data.get("direct_mode", False)
                pending_info = {
                    "dj_id": dj_id,
                    "dj_name": dj_name,
                    "websocket": websocket,
                    "waiting_since": time.time(),
                    "direct_mode": direct_mode,
                    "priority": priority,
                    "code": code,
                    "coordinator_token": data.get("token"),  # Coordinator JWT for profile lookup
                }
                self._pending_djs[dj_id] = pending_info

                # Tell the DJ they're waiting for approval
                await websocket.send(
                    _json_str(
                        {
                            "type": "auth_pending",
                            "message": "Waiting for VJ approval...",
                            "dj_id": dj_id,
                        }
                    )
                )

                # Notify admin panel
                await self._broadcast_to_browsers(
                    _json_str(
                        {
                            "type": "dj_pending",
                            "dj": {
                                "dj_id": dj_id,
                                "dj_name": dj_name,
                                "waiting_since": pending_info["waiting_since"],
                                "direct_mode": direct_mode,
                            },
                        }
                    )
                )

                logger.info(f"DJ {dj_name} ({dj_id}) placed in approval queue")

                # Wait for approval or denial (the DJ stays connected)
                try:
                    while dj_id in self._pending_djs:
                        # Check for messages from the pending DJ (heartbeat/disconnect)
                        try:
                            msg = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                            if len(msg) > 65536:
                                continue
                            msg_data = mjson.decode(msg)
                            if msg_data.get("type") == "ping":
                                await websocket.send(_json_str({"type": "pong"}))
                        except asyncio.TimeoutError:
                            # Just a timeout on recv, keep waiting
                            pass
                        except websockets.exceptions.ConnectionClosed:
                            # DJ disconnected while waiting
                            self._pending_djs.pop(dj_id, None)
                            logger.info(
                                f"Pending DJ {dj_name} disconnected while waiting for approval"
                            )
                            await self._broadcast_to_browsers(
                                _json_str(
                                    {
                                        "type": "dj_denied",
                                        "dj_id": dj_id,
                                    }
                                )
                            )
                            return
                except Exception as e:
                    self._pending_djs.pop(dj_id, None)
                    logger.warning(f"Error in pending DJ wait loop: {e}")
                    return

                # If we get here, the DJ was removed from pending (approved or denied)
                # Check if they were approved (they'll be in self._djs by now)
                if dj_id not in self._djs:
                    # They were denied
                    return

                # They were approved - run the frame handling loop
                dj = self._djs[dj_id]

                # Perform clock synchronization (same as dj_auth path).
                # The DJ's heartbeat task may already be running, so we drain
                # any non-sync messages while waiting for the sync response.
                try:
                    t1 = time.time()
                    await websocket.send(
                        _json_str({"type": "clock_sync_request", "server_time": t1})
                    )
                    sync_deadline = asyncio.get_running_loop().time() + 5.0
                    while True:
                        remaining = sync_deadline - asyncio.get_running_loop().time()
                        if remaining <= 0:
                            logger.warning(
                                f"[DJ CLOCK SYNC] {dj_name}: timeout waiting for sync response"
                            )
                            break
                        raw = await asyncio.wait_for(websocket.recv(), timeout=remaining)
                        t4 = time.time()
                        sync_data = mjson.decode(raw)

                        if sync_data.get("type") != "clock_sync_response":
                            # Interleaved heartbeat or other message — process and retry
                            if sync_data.get("type") == "dj_heartbeat":
                                dj.last_heartbeat = t4
                            continue

                        t2 = sync_data.get("dj_recv_time", t1)
                        t3 = sync_data.get("dj_send_time", t4)

                        if (
                            not isinstance(t2, (int, float))
                            or not isinstance(t3, (int, float))
                            or not math.isfinite(t2)
                            or not math.isfinite(t3)
                        ):
                            logger.warning(
                                f"[DJ CLOCK SYNC] {dj_name}: non-finite timestamps, skipping"
                            )
                        elif abs(t2 - t1) > 3600 or abs(t3 - t4) > 3600:
                            logger.warning(
                                f"[DJ CLOCK SYNC] {dj_name}: timestamps too far, skipping"
                            )
                        else:
                            clock_offset = ((t2 - t1) + (t3 - t4)) / 2
                            rtt = (t4 - t1) - (t3 - t2)
                            if rtt < 0 or rtt > 30:
                                logger.warning(
                                    f"[DJ CLOCK SYNC] {dj_name}: invalid RTT={rtt * 1000:.1f}ms"
                                )
                            else:
                                dj.clock_offset = clock_offset
                                dj.clock_sync_done = True
                                dj._last_clock_resync = time.time()
                                dj._clock_sync_count = 1
                                logger.info(
                                    f"[DJ CLOCK SYNC] {dj_name}: offset={clock_offset * 1000:.1f}ms, RTT={rtt * 1000:.1f}ms"
                                )
                        break
                except asyncio.TimeoutError:
                    logger.warning(f"[DJ CLOCK SYNC] {dj_name}: timeout waiting for sync response")
                except Exception as e:
                    logger.warning(f"[DJ CLOCK SYNC] {dj_name}: sync failed: {e}")

                # Send explicit stream route (same as dj_auth path)
                try:
                    await websocket.send(_json_str(self._build_stream_route_message(dj_id, dj)))
                except Exception as e:
                    logger.debug(f"Failed to send stream route to DJ {dj_id}: {e}")

                # Handle incoming frames (same pattern as credentialed DJs)
                async for message in websocket:
                    try:
                        # Fast path: audio frames are ~95% of DJ traffic.
                        # Substring check + typed decode avoids a generic decode.
                        if isinstance(message, str) and '"dj_audio_frame"' in message:
                            frame = _frame_decoder.decode(message)
                            await self._handle_dj_frame(dj, frame)
                            continue

                        frame_data = mjson.decode(message)
                        frame_type = frame_data.get("type")

                        if frame_type == "dj_heartbeat":
                            await self._process_dj_heartbeat(dj, websocket, frame_data)
                        elif frame_type == "clock_sync_response":
                            self._apply_clock_resync(dj, frame_data)
                        elif frame_type == "voice_audio":
                            # Relay voice audio from active DJ to Minecraft
                            dj.voice_streaming = True
                            if dj.dj_id == self._active_dj_id:
                                await self._relay_voice_audio(frame_data)
                        elif frame_type == "going_offline":
                            logger.info(
                                f"[DJ GOING OFFLINE] {dj.dj_name} ({dj.dj_id}) going offline gracefully"
                            )
                            break
                    except msgspec.DecodeError:
                        logger.debug(f"Invalid JSON from DJ {dj_name}")
                    except Exception as e:
                        logger.error(
                            f"Error processing DJ frame from {dj_name}: {e}",
                            exc_info=True,
                        )
                return

            elif msg_type == "dj_auth":
                # Traditional credential-based authentication
                dj_id = _sanitize_name(data.get("dj_id", ""), max_length=64, default="")
                dj_key = data.get("dj_key", "")
                dj_name = _sanitize_name(data.get("dj_name", dj_id), default=dj_id or "DJ")

                # Slur filter on DJ name
                try:
                    from vj_server.content_filter import contains_slur as _contains_slur

                    if _contains_slur(dj_name):
                        logger.warning("DJ auth rejected: DJ name failed content filter")
                        await websocket.send(
                            _json_str(
                                {
                                    "type": "auth_error",
                                    "error": "DJ name contains language that is not allowed",
                                }
                            )
                        )
                        await websocket.close(4005, "Content policy violation")
                        return
                except ImportError:
                    logger.warning(
                        "better-profanity not installed — DJ name content filter disabled"
                    )

                # Verify credentials
                if self.require_auth:
                    dj_info = self.auth_config.verify_dj(dj_id, dj_key)
                    if not dj_info:
                        logger.warning(f"DJ auth failed: {dj_id}")
                        await websocket.close(4004, "Authentication failed")
                        return
                    dj_name = dj_info.get("name", dj_name)
                    priority = dj_info.get("priority", 10)
                else:
                    priority = 10
            else:
                await websocket.close(4003, "Expected dj_auth or code_auth message")
                return

            # Check for duplicate connection (with lock)
            async with self._dj_lock:
                if dj_id in self._djs:
                    logger.warning(f"DJ {dj_id} already connected, rejecting duplicate")
                    await websocket.close(4005, "Already connected")
                    return

                # Check if DJ is using direct mode
                direct_mode = data.get("direct_mode", False)

                # Create DJ connection
                dj = DJConnection(
                    dj_id=dj_id,
                    dj_name=dj_name,
                    websocket=websocket,
                    priority=priority,
                    direct_mode=direct_mode,
                )
                self._djs[dj_id] = dj
                self._dj_queue.append(dj_id)

            mode_str = " (DIRECT)" if direct_mode else ""
            logger.info(
                f"[DJ CONNECT] {dj_name} ({dj_id}){mode_str} from {websocket.remote_address}"
            )
            self._dj_connects += 1

            # Fetch DJ profile from coordinator (non-blocking, best-effort)
            coordinator_token = data.get("token")
            if coordinator_token and self._coordinator:
                await self._hydrate_dj_profile(dj, coordinator_token)
                dj_name = dj.dj_name  # Update local var for auth_response

            # Build auth success response
            auth_response = {
                "type": "auth_success",
                "dj_id": dj_id,
                "dj_name": dj_name,
                "is_active": self._active_dj_id == dj_id,
                # Pattern info for direct mode
                "current_pattern": self._pattern_name,
                "pattern_config": {
                    "entity_count": self.entity_count,
                    "zone_size": self._pattern_config.zone_size,
                    "beat_boost": self._pattern_config.beat_boost,
                    "base_scale": self._pattern_config.base_scale,
                    "max_scale": self._pattern_config.max_scale,
                },
            }

            # Include Minecraft connection info for direct mode DJs
            if direct_mode:
                auth_response["minecraft_host"] = self.minecraft_host
                auth_response["minecraft_port"] = self.minecraft_port
                auth_response["zone"] = self.zone
                auth_response["entity_count"] = self.entity_count
            # Initial route hint for newer clients (legacy clients ignore unknown fields)
            auth_response["route_mode"] = (
                "dual" if (direct_mode and self._active_dj_id == dj_id) else "relay"
            )

            await websocket.send(_json_str(auth_response))
            logger.info(f"[DJ AUTH SUCCESS] {dj_name} ({dj_id}) authenticated, priority={priority}")

            # Perform clock synchronization to handle clock skew between DJ and server
            # Uses NTP-style algorithm: send server time, DJ responds with its time
            try:
                t1 = time.time()  # Server time when sync request sent
                await websocket.send(_json_str({"type": "clock_sync_request", "server_time": t1}))
                # Wait for DJ response with timeout
                sync_response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                t4 = time.time()  # Server time when response received
                sync_data = mjson.decode(sync_response)

                if sync_data.get("type") == "clock_sync_response":
                    t2 = sync_data.get("dj_recv_time", t1)  # DJ time when it received request
                    t3 = sync_data.get("dj_send_time", t4)  # DJ time when it sent response

                    # Validate clock sync values are finite and within reasonable range
                    if (
                        not isinstance(t2, (int, float))
                        or not isinstance(t3, (int, float))
                        or not math.isfinite(t2)
                        or not math.isfinite(t3)
                    ):
                        logger.warning(
                            f"[DJ CLOCK SYNC] {dj_name}: non-finite timestamps, skipping sync"
                        )
                    elif abs(t2 - t1) > 3600 or abs(t3 - t4) > 3600:
                        logger.warning(
                            f"[DJ CLOCK SYNC] {dj_name}: timestamps too far from server time (>1h), skipping sync"
                        )
                    else:
                        # Calculate clock offset using NTP algorithm
                        # offset = ((t2 - t1) + (t3 - t4)) / 2
                        # Positive offset means DJ clock is ahead of server
                        clock_offset = ((t2 - t1) + (t3 - t4)) / 2
                        rtt = (t4 - t1) - (t3 - t2)  # Round-trip time

                        if rtt < 0 or rtt > 30:
                            logger.warning(
                                f"[DJ CLOCK SYNC] {dj_name}: invalid RTT={rtt * 1000:.1f}ms, skipping sync"
                            )
                        else:
                            dj.clock_offset = clock_offset
                            dj.clock_sync_done = True
                            dj._last_clock_resync = time.time()
                            dj._clock_sync_count = 1
                            logger.info(
                                f"[DJ CLOCK SYNC] {dj_name}: offset={clock_offset * 1000:.1f}ms, RTT={rtt * 1000:.1f}ms"
                            )
                else:
                    actual_type = sync_data.get("type", "unknown")
                    logger.warning(
                        f"[DJ CLOCK SYNC] {dj_name}: expected 'clock_sync_response' but got '{actual_type}', skipping sync"
                    )
            except asyncio.TimeoutError:
                logger.warning(f"[DJ CLOCK SYNC] {dj_name}: timeout waiting for sync response")
            except Exception as e:
                logger.warning(f"[DJ CLOCK SYNC] {dj_name}: sync failed: {e}")

            # Send explicit routing policy after handshake.
            try:
                await websocket.send(_json_str(self._build_stream_route_message(dj_id, dj)))
            except Exception as e:
                logger.debug(f"Failed to send initial stream route to DJ {dj_id}: {e}")

            # If no active DJ, make this one active
            if self._active_dj_id is None:
                await self._set_active_dj(dj_id)

            # Broadcast roster update
            await self._broadcast_dj_roster()

            # Broadcast DJ joined with full profile to browser clients
            await self._broadcast_to_browsers(
                _json_str(
                    {
                        "type": "dj_joined",
                        "dj": self._dj_profile_dict(dj),
                    }
                )
            )

            # Handle incoming frames
            async for message in websocket:
                try:
                    # Fast path: audio frames are ~95% of DJ traffic.
                    # Substring check + typed decode avoids a generic decode.
                    if isinstance(message, str) and '"dj_audio_frame"' in message:
                        frame = _frame_decoder.decode(message)
                        await self._handle_dj_frame(dj, frame)
                        continue

                    data = mjson.decode(message)
                    msg_type = data.get("type")

                    if msg_type == "dj_heartbeat":
                        await self._process_dj_heartbeat(dj, websocket, data)

                    elif msg_type == "clock_sync_response":
                        self._apply_clock_resync(dj, data)

                    elif msg_type == "voice_audio":
                        # Relay voice audio from active DJ to Minecraft
                        dj.voice_streaming = True
                        if dj.dj_id == self._active_dj_id:
                            await self._relay_voice_audio(data)

                    elif msg_type == "set_my_palette":
                        palette = data.get("band_materials")
                        if isinstance(palette, list) and len(palette) == 5:
                            cleaned = [(m if isinstance(m, str) and m else None) for m in palette]
                            self._dj_palettes[dj.dj_id] = cleaned
                            logger.info(f"DJ {dj.dj_name} set block palette: {cleaned}")
                            # If this DJ is currently active, apply immediately
                            if dj.dj_id == self._active_dj_id:
                                self._band_materials = list(cleaned)
                                self._band_materials_source = "dj_palette"
                                await self._broadcast_to_browsers(
                                    _json_str(
                                        {
                                            "type": "band_materials_sync",
                                            "materials": self._band_materials,
                                            "source": self._band_materials_source,
                                        }
                                    )
                                )
                        else:
                            logger.warning(f"Invalid set_my_palette from DJ {dj.dj_id}")

                    elif msg_type == "going_offline":
                        logger.info(
                            f"[DJ GOING OFFLINE] {dj.dj_name} ({dj.dj_id}) going offline gracefully"
                        )
                        break

                    else:
                        logger.debug(f"Unknown message type from DJ {dj.dj_id}: {msg_type}")

                except msgspec.DecodeError as e:
                    logger.warning(f"Invalid JSON from DJ {dj.dj_id}: {e}")

        except websockets.exceptions.ConnectionClosed as e:
            dj_name = (
                self._djs[dj_id].dj_name if dj_id and dj_id in self._djs else dj_id or "unknown"
            )
            logger.info(
                f"DJ {dj_name} ({dj_id}) connection closed: code={e.code}, reason={e.reason}"
            )
        except Exception as e:
            logger.error(f"DJ connection error: {e}", exc_info=True)
        finally:
            # Clean up pending DJs
            if dj_id and dj_id in self._pending_djs:
                self._pending_djs.pop(dj_id, None)

            # Clean up active DJs (with lock)
            if dj_id:
                _left_dj_name = None
                async with self._dj_lock:
                    if dj_id in self._djs:
                        _left_dj_name = self._djs[dj_id].dj_name
                        del self._djs[dj_id]
                        self._dj_palettes.pop(dj_id, None)
                        if dj_id in self._dj_queue:
                            self._dj_queue.remove(dj_id)
                        logger.info(f"[DJ DISCONNECT] {_left_dj_name} ({dj_id})")
                        self._dj_disconnects += 1

                        # If this was the active DJ, switch to next
                        if self._active_dj_id == dj_id:
                            await self._auto_switch_dj_locked()

                # Broadcast DJ left to browser clients
                if _left_dj_name:
                    await self._broadcast_to_browsers(
                        _json_str(
                            {
                                "type": "dj_left",
                                "dj_id": dj_id,
                                "dj_name": _left_dj_name,
                            }
                        )
                    )

                await self._broadcast_dj_roster()

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

    async def _set_active_dj(self, dj_id: str):
        """Set the active DJ."""
        async with self._dj_lock:
            await self._set_active_dj_locked(dj_id)

    async def _set_active_dj_locked(self, dj_id: str):
        """Set the active DJ (caller must hold _dj_lock)."""
        if dj_id not in self._djs:
            logger.warning(f"Cannot set active DJ: {dj_id} not found")
            return

        self._active_dj_id = dj_id
        self._beat_predictor.reset()

        # Apply DJ's block palette (if set)
        palette = self._dj_palettes.get(dj_id)
        if palette and any(m is not None for m in palette):
            self._band_materials = list(palette)
            self._band_materials_source = "dj_palette"
        else:
            self._band_materials = [None, None, None, None, None]
            self._band_materials_source = "default"

        logger.info(f"Active DJ: {self._djs[dj_id].dj_name}")

        # Take snapshot for notification (release lock during network IO)
        djs_snapshot = list(self._djs.items())

        # Notify all DJs of status change (outside lock to avoid deadlock)
        for did, dj in djs_snapshot:
            try:
                await dj.websocket.send(
                    _json_str({"type": "status_update", "is_active": did == dj_id})
                )
            except Exception as e:
                logger.debug(f"Failed to send status to DJ {did}: {e}")
            # Push per-DJ stream routing policy after every active switch.
            try:
                await dj.websocket.send(_json_str(self._build_stream_route_message(did, dj)))
            except Exception as e:
                logger.debug(f"Failed to send stream route to DJ {did}: {e}")

        await self._broadcast_dj_roster()

        # Broadcast band materials after DJ switch
        await self._broadcast_to_browsers(
            _json_str(
                {
                    "type": "band_materials_sync",
                    "materials": self._band_materials,
                    "source": self._band_materials_source,
                }
            )
        )

        # Send dj_info to Minecraft for stage decorators (billboard, transitions)
        await self._send_dj_info_to_minecraft(dj_id)

    def _build_stream_route_message(self, dj_id: str, dj: DJConnection) -> dict:
        """Build stream routing policy for a DJ client.

        route_mode:
        - relay: DJ sends audio to VJ only (default / standby DJs)
        - dual: DJ sends audio to VJ and publishes visualization directly to Minecraft
        """
        is_active = self._active_dj_id == dj_id
        route_mode = "dual" if (dj.direct_mode and is_active) else "relay"

        return {
            "type": "stream_route",
            "route_mode": route_mode,
            "is_active": is_active,
            "minecraft_host": self.minecraft_host,
            "minecraft_port": self.minecraft_port,
            "zone": self.zone,
            "entity_count": self.entity_count,
            "current_pattern": self._pattern_name,
            "pattern_config": {
                "entity_count": self.entity_count,
                "zone_size": self._pattern_config.zone_size,
                "beat_boost": self._pattern_config.beat_boost,
                "base_scale": self._pattern_config.base_scale,
                "max_scale": self._pattern_config.max_scale,
            },
            "pattern_scripts": self._get_pattern_scripts(),
            "band_sensitivity": list(self._band_sensitivity),
            "relay_fallback": True,
            "reason": "active_direct_dj" if route_mode == "dual" else "standby_or_relay_mode",
        }

    async def _broadcast_stream_routes(self):
        """Broadcast routing policy to all connected DJs."""
        async with self._dj_lock:
            djs_snapshot = list(self._djs.items())
        for did, dj in djs_snapshot:
            try:
                await dj.websocket.send(_json_str(self._build_stream_route_message(did, dj)))
            except Exception as e:
                logger.debug(f"Failed to broadcast stream route to DJ {did}: {e}")

    async def _send_dj_info_to_minecraft(self, dj_id: Optional[str]):
        """Send DJ info to Minecraft plugin for stage decorator effects."""
        if not self.viz_client or not self.viz_client.connected:
            return

        if dj_id and dj_id in self._djs:
            dj = self._djs[dj_id]
            msg = {
                "type": "dj_info",
                "dj_name": dj.dj_name,
                "dj_id": dj.dj_id,
                "bpm": dj.bpm,
                "is_active": True,
            }
        else:
            msg = {
                "type": "dj_info",
                "dj_name": "",
                "dj_id": "",
                "bpm": 0.0,
                "is_active": False,
            }

        try:
            await self.viz_client.send(msg)
            logger.debug(f"Sent dj_info to Minecraft: {msg.get('dj_name', 'none')}")
        except Exception as e:
            logger.debug(f"Failed to send dj_info to Minecraft: {e}")

        # Also send banner config for the active DJ
        await self._send_banner_config_to_minecraft(dj_id)

    async def _send_banner_config_to_minecraft(self, dj_id: Optional[str]):
        """Send banner config for the active DJ to Minecraft."""
        if not self.viz_client or not self.viz_client.connected:
            return

        profile = self._dj_banner_profiles.get(dj_id, {}) if dj_id else {}

        msg = {
            "type": "banner_config",
            "banner_mode": profile.get("banner_mode", "text"),
            "text_style": profile.get("text_style", "bold"),
            "text_color_mode": profile.get("text_color_mode", "frequency"),
            "text_fixed_color": profile.get("text_fixed_color", "f"),
            "text_format": profile.get("text_format", "%s"),
            "grid_width": profile.get("grid_width", 24),
            "grid_height": profile.get("grid_height", 12),
            "image_pixels": profile.get("image_pixels", []),
        }

        try:
            await self.viz_client.send(msg)
            logger.debug(f"Sent banner_config to Minecraft for DJ: {dj_id}")
        except Exception as e:
            logger.debug(f"Failed to send banner_config: {e}")

    # ========== Banner Profile Management ==========

    def _load_banner_profiles(self):
        """Load banner profiles from disk."""
        path = Path("configs/dj_banner_profiles.json")
        if not path.exists():
            return

        try:
            with open(path, "r") as f:
                profiles = json.load(f)

            for dj_id, profile in profiles.items():
                if profile.get("has_image"):
                    pixel_path = Path(f"configs/banners/{dj_id}_pixels.bin")
                    if pixel_path.exists():
                        import struct

                        with open(pixel_path, "rb") as f:
                            data = f.read()
                        pixels = [
                            struct.unpack(">i", data[i : i + 4])[0] for i in range(0, len(data), 4)
                        ]
                        profile["image_pixels"] = pixels
                self._dj_banner_profiles[dj_id] = profile

            logger.info(f"Loaded {len(self._dj_banner_profiles)} banner profiles")
        except Exception as e:
            logger.warning(f"Failed to load banner profiles: {e}")

    def _save_banner_profiles(self):
        """Save banner profiles to disk."""
        path = Path("configs/dj_banner_profiles.json")
        path.parent.mkdir(parents=True, exist_ok=True)

        profiles_for_save = {}
        for dj_id, profile in self._dj_banner_profiles.items():
            save_profile = {k: v for k, v in profile.items() if k != "image_pixels"}
            if profile.get("image_pixels"):
                # Save pixel data as a separate binary file
                pixel_dir = Path("configs/banners")
                pixel_dir.mkdir(parents=True, exist_ok=True)
                pixel_path = pixel_dir / f"{dj_id}_pixels.bin"
                import struct

                try:
                    with open(pixel_path, "wb") as f:
                        for p in profile["image_pixels"]:
                            f.write(struct.pack(">i", p))
                    save_profile["has_image"] = True
                except Exception as e:
                    logger.warning(f"Failed to save pixel data for {dj_id}: {e}")
            profiles_for_save[dj_id] = save_profile

        try:
            with open(path, "w") as f:
                json.dump(profiles_for_save, f, indent=2)
        except Exception as e:
            logger.warning(f"Failed to save banner profiles: {e}")

    def _process_logo_image(
        self, image_base64: str, grid_width: int, grid_height: int
    ) -> Optional[List[int]]:
        """Downsample a PNG image to a pixel grid for TextDisplay rendering.

        Returns list of ARGB int values.
        """
        try:
            import base64
            import io

            from PIL import Image

            image_data = base64.b64decode(image_base64)
            img = Image.open(io.BytesIO(image_data))
            img = img.convert("RGBA")
            img = img.resize((grid_width, grid_height), Image.Resampling.LANCZOS)

            pixels = []
            for y in range(grid_height):
                for x in range(grid_width):
                    r, g, b, a = img.getpixel((x, y))
                    # Pack as ARGB int (Java Color.fromARGB format)
                    argb = (a << 24) | (r << 16) | (g << 8) | b
                    pixels.append(argb)

            return pixels
        except ImportError:
            logger.error("Pillow (PIL) required for logo processing: pip install Pillow")
            return None
        except Exception as e:
            logger.error(f"Failed to process logo image: {e}")
            return None

    def _capture_current_state(self) -> dict:
        """Capture the current VJ state for saving as a scene."""
        return {
            "pattern": self._pattern_name,
            "preset": self._pattern_config.preset
            if hasattr(self._pattern_config, "preset")
            else "auto",
            "transition_duration": self._transition_duration,
            "band_sensitivity": self._band_sensitivity.copy(),
            "attack": self._pattern_config.attack,
            "release": self._pattern_config.release,
            "beat_threshold": self._pattern_config.beat_threshold,
            "entity_count": self.entity_count,
            "block_type": self._get_zone_state(self.zone).block_type,
            "zone_patterns": self._get_zone_patterns_dict(),
        }

    @staticmethod
    def _sanitize_scene_name(name: str) -> str:
        """Sanitize a scene name to prevent path traversal."""
        sanitized = re.sub(r"[^a-zA-Z0-9_\- ]", "", name).strip()
        if not sanitized:
            raise ValueError("Invalid scene name")
        return sanitized

    def _save_scene_to_file(self, name: str, scene_data: dict):
        """Save a scene to disk as JSON."""
        name = self._sanitize_scene_name(name)
        scenes_dir = Path("configs/scenes")
        scenes_dir.mkdir(parents=True, exist_ok=True)

        scene_path = scenes_dir / f"{name}.json"
        # Ensure resolved path stays within scenes_dir
        if not scene_path.resolve().is_relative_to(scenes_dir.resolve()):
            raise ValueError("Invalid scene path")
        with open(scene_path, "w") as f:
            json.dump(scene_data, f, indent=2)

    def _load_scene_from_file(self, name: str) -> dict:
        """Load a scene from disk."""
        name = self._sanitize_scene_name(name)
        scenes_dir = Path("configs/scenes")
        scene_path = scenes_dir / f"{name}.json"
        if not scene_path.resolve().is_relative_to(scenes_dir.resolve()):
            raise ValueError("Invalid scene path")
        if not scene_path.exists():
            raise FileNotFoundError(f"Scene '{name}' not found")

        with open(scene_path, "r") as f:
            return json.load(f)

    def _delete_scene_file(self, name: str):
        """Delete a scene file from disk."""
        name = self._sanitize_scene_name(name)
        scenes_dir = Path("configs/scenes")
        scene_path = scenes_dir / f"{name}.json"
        if not scene_path.resolve().is_relative_to(scenes_dir.resolve()):
            raise ValueError("Invalid scene path")
        if not scene_path.exists():
            raise FileNotFoundError(f"Scene '{name}' not found")
        scene_path.unlink()

    def _list_scenes(self) -> list:
        """List all available scenes."""
        scenes_dir = Path("configs/scenes")
        if not scenes_dir.exists():
            return []

        scenes = []
        for scene_file in scenes_dir.glob("*.json"):
            try:
                with open(scene_file, "r") as f:
                    scene_data = json.load(f)
                scenes.append(
                    {
                        "name": scene_file.stem,
                        "pattern": scene_data.get("pattern", "unknown"),
                        "preset": scene_data.get("preset", "auto"),
                        "entity_count": scene_data.get("entity_count", 16),
                    }
                )
            except Exception as e:
                logger.warning(f"Failed to load scene {scene_file.stem}: {e}")

        return scenes

    async def _apply_scene_state(self, scene_data: dict):
        """Apply a saved scene state with crossfade transition."""
        # Apply pattern with crossfade (use existing transition system)
        pattern_name = scene_data.get("pattern", "spectrum")
        if pattern_name != self._pattern_name:
            # Start crossfade transition
            self._old_pattern = self._current_pattern
            self._old_pattern_name = self._pattern_name
            self._transitioning = True
            self._transition_start = time.monotonic()

            # Load new pattern
            self._pattern_name = pattern_name
            self._current_pattern = get_pattern(pattern_name, self._pattern_config)
            self._pattern_changes += 1

            logger.info(
                f"Scene transition: {self._old_pattern_name} -> {pattern_name} ({self._transition_duration}s)"
            )

        # Apply audio settings
        if "band_sensitivity" in scene_data:
            bs = scene_data["band_sensitivity"]
            if isinstance(bs, list) and len(bs) == 5:
                self._band_sensitivity = bs.copy()
            else:
                logger.warning("Ignoring invalid band_sensitivity in scene: %r", bs)
        if "attack" in scene_data:
            self._pattern_config.attack = scene_data["attack"]
        if "release" in scene_data:
            self._pattern_config.release = scene_data["release"]
        if "beat_threshold" in scene_data:
            self._pattern_config.beat_threshold = scene_data["beat_threshold"]

        # Apply transition duration
        if "transition_duration" in scene_data:
            self._transition_duration = scene_data["transition_duration"]

        # Apply entity count
        if "entity_count" in scene_data:
            new_count = scene_data["entity_count"]
            if not isinstance(new_count, int) or new_count < 1 or new_count > 1000:
                new_count = 16
            if new_count != self.entity_count:
                self.entity_count = new_count
                self._pattern_config.entity_count = new_count
                # Reinit pattern with new count
                self._current_pattern = get_pattern(self._pattern_name, self._pattern_config)

        # Restore per-zone patterns if present
        saved_zone_patterns = scene_data.get("zone_patterns")
        if saved_zone_patterns and isinstance(saved_zone_patterns, dict):
            for zn, entry in saved_zone_patterns.items():
                # Handle both old (string) and new ({pattern, render_mode}) formats
                pname = entry["pattern"] if isinstance(entry, dict) else entry
                if _lua_pattern_exists(pname):
                    zs = self._get_zone_state(zn)
                    await self._set_pattern_for_zone(zs, zn, pname)

        # Broadcast state to all connected clients
        await self._broadcast_config_to_browsers()
        await self._broadcast_pattern_change()

        # Sync audio settings to DJs
        preset_name = scene_data.get("preset", "auto")
        if preset_name in AUDIO_PRESETS:
            config = AUDIO_PRESETS[preset_name]
            await self._broadcast_preset_to_djs(config.to_dict(), preset_name)

    async def _auto_switch_dj(self):
        """Automatically switch to next available DJ."""
        async with self._dj_lock:
            await self._auto_switch_dj_locked()

    async def _auto_switch_dj_locked(self):
        """Automatically switch to next available DJ (caller must hold _dj_lock)."""
        if not self._dj_queue:
            self._active_dj_id = None
            self._beat_predictor.reset()
            logger.info("No DJs available")
            await self._send_dj_info_to_minecraft(None)
            return

        # Find highest priority connected DJ
        available = [dj_id for dj_id in self._dj_queue if dj_id in self._djs]
        if available:
            # Sort by priority
            available.sort(key=lambda x: self._djs[x].priority)
            await self._set_active_dj_locked(available[0])
        else:
            self._active_dj_id = None
            self._beat_predictor.reset()
            await self._send_dj_info_to_minecraft(None)

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
                                config = AUDIO_PRESETS[preset_name]
                                self._pattern_config.attack = config.attack
                                self._pattern_config.release = config.release
                                self._pattern_config.beat_threshold = config.beat_threshold
                                self._band_sensitivity = list(config.band_sensitivity)
                                # Broadcast settings to DJs
                                await self._broadcast_preset_to_djs(config.to_dict(), preset_name)
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
                        grid_width = min(48, max(4, data.get("grid_width", 24)))
                        grid_height = min(24, max(2, data.get("grid_height", 12)))
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
            await self._broadcast_to_browsers(
                _json_str(
                    {
                        "type": "minecraft_status",
                        "connected": mc_connected,
                    }
                )
            )
            logger.info(f"[MC STATUS] Broadcasting minecraft_connected={mc_connected}")

    async def _approve_pending_dj(self, dj_id: str):
        """Approve a pending DJ and move them to the active DJ list."""
        async with self._dj_lock:
            if not dj_id or dj_id not in self._pending_djs:
                logger.warning(f"Cannot approve DJ {dj_id}: not in pending queue")
                return

            info = self._pending_djs.pop(dj_id)
            ws = info["websocket"]
            if dj_id in self._djs:
                logger.warning(f"DJ {dj_id} already in active list")
                return

            dj = DJConnection(
                dj_id=dj_id,
                dj_name=info["dj_name"],
                websocket=ws,
                priority=info.get("priority", 10),
                direct_mode=info.get("direct_mode", False),
            )
            self._djs[dj_id] = dj
            self._dj_queue.append(dj_id)

        self._dj_connects += 1
        logger.info(f"[DJ APPROVED] {info['dj_name']} ({dj_id})")

        # Fetch DJ profile from coordinator (non-blocking, best-effort)
        coordinator_token = info.get("coordinator_token")
        if coordinator_token and self._coordinator:
            await self._hydrate_dj_profile(dj, coordinator_token)

        # Send auth_success to the DJ (same type as dj_auth path,
        # so the Rust client handles it via the existing ServerMessage enum)
        try:
            await ws.send(
                _json_str(
                    {
                        "type": "auth_success",
                        "dj_id": dj_id,
                        "dj_name": dj.dj_name,
                        "is_active": self._active_dj_id == dj_id,
                        "current_pattern": self._pattern_name,
                        "pattern_config": {
                            "entity_count": self.entity_count,
                            "zone_size": self._pattern_config.zone_size,
                            "beat_boost": self._pattern_config.beat_boost,
                            "base_scale": self._pattern_config.base_scale,
                            "max_scale": self._pattern_config.max_scale,
                        },
                    }
                )
            )
        except Exception as e:
            logger.warning(f"Failed to send auth_success to DJ {dj_id}: {e}")

        # If no active DJ, make this one active
        if self._active_dj_id is None:
            await self._set_active_dj(dj_id)

        # Broadcast roster update
        await self._broadcast_dj_roster()
        await self._broadcast_stream_routes()
        await self._broadcast_to_browsers(
            _json_str(
                {
                    "type": "dj_approved",
                    "dj_id": dj_id,
                }
            )
        )

        # Broadcast DJ joined with full profile to browser clients
        dj = self._djs.get(dj_id)
        if dj:
            await self._broadcast_to_browsers(
                _json_str(
                    {
                        "type": "dj_joined",
                        "dj": self._dj_profile_dict(dj),
                    }
                )
            )

    async def _deny_pending_dj(self, dj_id: str):
        """Deny a pending DJ and close their connection."""
        if not dj_id or dj_id not in self._pending_djs:
            logger.warning(f"Cannot deny DJ {dj_id}: not in pending queue")
            return

        info = self._pending_djs.pop(dj_id)
        ws = info["websocket"]

        logger.info(f"[DJ DENIED] {info['dj_name']} ({dj_id})")

        # Send denial and close
        try:
            await ws.send(
                _json_str(
                    {
                        "type": "auth_denied",
                        "message": "Connection denied by VJ",
                    }
                )
            )
            await ws.close(4006, "Connection denied by VJ")
        except Exception:
            pass

        await self._broadcast_to_browsers(
            _json_str(
                {
                    "type": "dj_denied",
                    "dj_id": dj_id,
                }
            )
        )

    async def _reorder_dj_queue(self, dj_id: str, new_position: int):
        """Move a DJ to a new position in the queue."""
        async with self._dj_lock:
            if dj_id not in self._dj_queue:
                return
            self._dj_queue.remove(dj_id)
            new_position = max(0, min(len(self._dj_queue), new_position))
            self._dj_queue.insert(new_position, dj_id)
            logger.info(f"DJ queue reordered: {dj_id} -> position {new_position}")

        await self._broadcast_dj_roster()

    # ------------------------------------------------------------------
    # Coordinator integration
    # ------------------------------------------------------------------

    async def _init_coordinator(self):
        """Register with the coordinator if configured."""
        cfg = ServerConfig.from_env()
        url = cfg.coordinator_url
        api_key = cfg.coordinator_api_key
        if not url or not api_key:
            logger.info(
                "Coordinator integration disabled (set COORDINATOR_URL and COORDINATOR_API_KEY to enable)"
            )
            return

        ws_url = cfg.coordinator_ws_url
        if not ws_url:
            ws_url = f"ws://localhost:{self.dj_port}"
            logger.warning(
                "COORDINATOR_WS_URL not set, using %s (DJs may not be able to reach this)", ws_url
            )

        server_name = cfg.coordinator_server_name

        self._coordinator = CoordinatorClient(url, api_key)
        try:
            await self._coordinator.register(server_name, ws_url)
            logger.info(
                "Coordinator integration active (server_id=%s)", self._coordinator.server_id
            )
            # Start periodic heartbeat
            self._coordinator_heartbeat_task = asyncio.create_task(
                self._coordinator_heartbeat_loop()
            )
        except Exception as exc:
            logger.error("Failed to register with coordinator: %s", exc)
            self._coordinator = None

    async def _coordinator_heartbeat_loop(self):
        """Send periodic heartbeats to the coordinator."""
        while True:
            await asyncio.sleep(120)  # every 2 minutes
            if self._coordinator:
                try:
                    await self._coordinator.heartbeat()
                except Exception as exc:
                    logger.warning("Coordinator heartbeat failed: %s", exc)

    async def _coordinator_create_show(self, ttl_minutes: int = 30) -> Optional[ConnectCode]:
        """Create a show on the coordinator and return a local ConnectCode with the same code."""
        if not self._coordinator:
            return None
        try:
            show_info = await self._coordinator.create_show(
                name="Live Show",
                max_djs=8,
            )
            # Create a local ConnectCode with the coordinator-generated code
            code = ConnectCode(
                code=show_info.connect_code,
                expires_at=time.time() + ttl_minutes * 60,
            )
            self._code_show_ids[show_info.connect_code] = show_info.show_id
            logger.info(
                "Created show on coordinator: code=%s show_id=%s",
                show_info.connect_code,
                show_info.show_id,
            )
            return code
        except Exception as exc:
            logger.error("Failed to create show on coordinator: %s", exc)
            return None

    def _cleanup_expired_codes(self):
        """Remove expired or used connect codes."""
        expired = [code for code, obj in self._connect_codes.items() if not obj.is_valid()]
        for code in expired:
            del self._connect_codes[code]
        if expired:
            logger.debug(f"Cleaned up {len(expired)} expired connect codes")

    async def _broadcast_connect_codes(self):
        """Broadcast active connect codes to all browser clients."""
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

        message = _json_str({"type": "connect_codes", "codes": codes})

        dead_clients = set()
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                dead_clients.add(client)
        self._broadcast_clients -= dead_clients

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
        message = _json_str({"type": "preset_sync", "preset": preset})

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

    def _get_pattern_scripts(self) -> dict:
        """Load all Lua pattern scripts for sending to DJs."""
        scripts = {}
        patterns_dir = Path(__file__).parent.parent / "patterns"
        if not patterns_dir.exists():
            return scripts
        # Load lib.lua
        lib_path = patterns_dir / "lib.lua"
        if lib_path.exists():
            scripts["lib"] = lib_path.read_text(encoding="utf-8")
        # Load all pattern files
        for lua_file in patterns_dir.glob("*.lua"):
            if lua_file.name != "lib.lua":
                key = lua_file.stem
                scripts[key] = lua_file.read_text(encoding="utf-8")
        return scripts

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

    async def _auto_init_bitmap_zones(self, zone_names: list[str]):
        """Auto-initialize bitmap grids for all zones after Minecraft connect."""
        for zn in zone_names:
            zs = self._get_zone_state(zn)
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
                        f"Bitmap auto-init: zone '{zn}' → "
                        f"{zs.bitmap_width}x{zs.bitmap_height} pattern={pattern}"
                    )
                    # Broadcast to browser clients so admin panel syncs
                    await self._broadcast_to_browsers(_json_str(response))
                else:
                    logger.warning(f"Bitmap auto-init: zone '{zn}' returned unexpected response")
            except asyncio.TimeoutError:
                logger.warning(f"Bitmap auto-init: timeout for zone '{zn}'")
            except Exception as e:
                logger.warning(f"Bitmap auto-init: failed for zone '{zn}': {e}")

    async def _run_parity_check(self) -> dict:
        """Query Minecraft zone status and compare against VJ server state.

        Automatically repairs any mismatches found.  Returns a parity_check_result
        message suitable for broadcasting to admin browsers.
        """
        result: dict = {"type": "parity_check_result", "ok": True, "zones": {}}

        if not self.viz_client or not self.viz_client.connected:
            result["ok"] = False
            result["error"] = "Minecraft not connected"
            return result

        # Increase timeout for the query (zone iteration can be slow)
        saved_timeout = self.viz_client.connect_timeout
        self.viz_client.connect_timeout = 15.0
        try:
            mc_report = await self.viz_client.query_zone_status()
        finally:
            self.viz_client.connect_timeout = saved_timeout

        if not mc_report or "zones" not in mc_report:
            result["ok"] = False
            result["error"] = "Failed to query Minecraft zone status"
            return result

        mc_zones: dict = mc_report["zones"]

        # Compare each zone the VJ server knows about
        for zone_name, zs in self._zone_patterns.items():
            expected = {
                "entity_count": zs.entity_count,
                "render_mode": zs.render_mode,
                "pattern": zs.pattern_name,
            }
            if zs.render_mode == "bitmap":
                expected["bitmap_initialized"] = zs.bitmap_initialized
                expected["bitmap_width"] = zs.bitmap_width
                expected["bitmap_height"] = zs.bitmap_height

            actual_mc = mc_zones.get(zone_name)
            if actual_mc is None:
                result["zones"][zone_name] = {
                    "ok": False,
                    "mismatches": ["zone missing in Minecraft"],
                    "expected": expected,
                    "actual": None,
                    "repaired": [],
                }
                result["ok"] = False
                continue

            actual = {
                "entity_count": actual_mc.get("entity_count", 0),
                "bitmap_active": actual_mc.get("bitmap_active", False),
                "bitmap_width": actual_mc.get("bitmap_width", 0),
                "bitmap_height": actual_mc.get("bitmap_height", 0),
                "bitmap_pattern": actual_mc.get("bitmap_pattern"),
            }

            mismatches: list[str] = []
            repairs: list[str] = []

            # Check render mode alignment
            expected_bitmap = zs.render_mode == "bitmap"
            actual_bitmap = actual["bitmap_active"]

            if expected_bitmap and not actual_bitmap:
                mismatches.append("render_mode: expected bitmap, actual block (no bitmap grid)")
                # Repair: re-init bitmap
                try:
                    await self._switch_zone_to_bitmap(zone_name, zs, zs.pattern_name)
                    repairs.append("re-initialized bitmap grid")
                except Exception as e:
                    repairs.append(f"bitmap repair failed: {e}")

            elif not expected_bitmap and actual_bitmap:
                mismatches.append("render_mode: expected block, actual bitmap")
                # Repair: switch back to block mode
                try:
                    await self._switch_zone_to_block(zone_name, zs, zs.pattern_name)
                    repairs.append("switched back to block mode")
                except Exception as e:
                    repairs.append(f"block repair failed: {e}")

            elif not expected_bitmap:
                # Block mode — check entity count
                mc_count = actual["entity_count"]
                if mc_count != zs.entity_count and zs.entity_count > 0:
                    mismatches.append(
                        f"entity_count: expected {zs.entity_count}, actual {mc_count}"
                    )
                    # Repair: re-init pool
                    try:
                        if self.viz_client and self.viz_client.connected:
                            await self.viz_client.init_pool(
                                zone_name, zs.entity_count, zs.block_type
                            )
                            repairs.append(f"re-initialized pool with {zs.entity_count} entities")
                    except Exception as e:
                        repairs.append(f"pool repair failed: {e}")

            zone_ok = len(mismatches) == 0
            if not zone_ok:
                result["ok"] = False

            result["zones"][zone_name] = {
                "ok": zone_ok,
                "expected": expected,
                "actual": actual,
            }
            if mismatches:
                result["zones"][zone_name]["mismatches"] = mismatches
            if repairs:
                result["zones"][zone_name]["repaired"] = repairs

        # Also flag zones that exist in MC but not in VJ server
        for mc_zone in mc_zones:
            if mc_zone not in self._zone_patterns:
                result["zones"][mc_zone] = {
                    "ok": False,
                    "mismatches": ["zone exists in Minecraft but not tracked by VJ server"],
                    "expected": None,
                    "actual": {
                        "entity_count": mc_zones[mc_zone].get("entity_count", 0),
                        "bitmap_active": mc_zones[mc_zone].get("bitmap_active", False),
                    },
                }
                result["ok"] = False

        zone_count = len(result["zones"])
        ok_count = sum(1 for z in result["zones"].values() if z.get("ok"))
        logger.info(f"Parity check: {ok_count}/{zone_count} zones OK")

        return result

    async def _switch_zone_to_bitmap(self, zone_name: str, zs: ZonePatternState, pattern_name: str):
        """Switch a zone from block mode to bitmap mode (or change bitmap pattern)."""
        if zs.render_mode != "bitmap":
            # Update server state immediately so browsers see the change
            zs.render_mode = "bitmap"
            zs.pattern_name = pattern_name

            # Cleanup block entities on Minecraft side (best-effort)
            if self.viz_client and self.viz_client.connected:
                try:
                    await self.viz_client.cleanup_zone(zone_name)
                except Exception as e:
                    logger.warning(f"Failed to cleanup block entities for '{zone_name}': {e}")

            # Init bitmap grid on Minecraft side (best-effort)
            if self.viz_client and self.viz_client.connected:
                try:
                    resp = await asyncio.wait_for(
                        self.viz_client.init_bitmap(zone_name, pattern=pattern_name),
                        timeout=10.0,
                    )
                    if resp and resp.get("type") == "bitmap_initialized":
                        zs.bitmap_initialized = True
                        zs.bitmap_width = resp.get("width", 0)
                        zs.bitmap_height = resp.get("height", 0)
                        await self._broadcast_to_browsers(_json_str(resp))
                        logger.info(
                            f"Zone '{zone_name}' switched to bitmap: "
                            f"{zs.bitmap_width}x{zs.bitmap_height} pattern={pattern_name}"
                        )
                except asyncio.TimeoutError:
                    logger.warning(f"Bitmap init timeout for zone '{zone_name}'")
                except Exception as e:
                    logger.warning(f"Bitmap init failed for zone '{zone_name}': {e}")
        else:
            # Already bitmap — just switch pattern
            zs.pattern_name = pattern_name
            if self.viz_client and self.viz_client.connected:
                try:
                    await self.viz_client.set_bitmap_pattern(zone_name, pattern_name)
                except Exception as e:
                    logger.warning(f"Failed to set bitmap pattern for '{zone_name}': {e}")

    async def _switch_zone_to_block(self, zone_name: str, zs: ZonePatternState, pattern_name: str):
        """Switch a zone from bitmap mode to block mode (or change block pattern)."""
        if zs.render_mode == "bitmap":
            # Teardown bitmap grid
            if self.viz_client and self.viz_client.connected:
                try:
                    await self.viz_client.teardown_bitmap(zone_name)
                except Exception as e:
                    logger.warning(f"Failed to teardown bitmap for '{zone_name}': {e}")
            zs.bitmap_initialized = False
            zs.bitmap_width = 0
            zs.bitmap_height = 0
            zs.render_mode = "block"
            # Init block entity pool
            if self.viz_client and self.viz_client.connected:
                try:
                    await self.viz_client.init_pool(zone_name, zs.entity_count, zs.block_type)
                except Exception as e:
                    logger.warning(f"Failed to init block pool for '{zone_name}': {e}")
        # Set Lua pattern (handles crossfade etc)
        await self._set_pattern_for_zone(zs, zone_name, pattern_name)

    async def _rehydrate_zone_states_from_active_stage(self) -> bool:
        """Pull active stage config from Minecraft and apply it to local zone state.

        This ensures reconnect/startup uses the same zone patterns/entity counts that
        the control center and plugin stage system consider active.
        """
        if not self.viz_client or not self.viz_client.connected:
            return False

        try:
            stages_resp = await asyncio.wait_for(
                self.viz_client.send({"type": "get_stages"}), timeout=5.0
            )
        except Exception as e:
            logger.warning(f"Failed to fetch stages for reconnect rehydrate: {e}")
            return False

        if not stages_resp or stages_resp.get("type") != "stages":
            return False

        active_stage = None
        for stage in stages_resp.get("stages", []):
            if isinstance(stage, dict) and stage.get("active"):
                active_stage = stage
                break

        if not active_stage:
            logger.info("No active stage reported by Minecraft; keeping current zone state")
            return False

        zones = active_stage.get("zones", {})
        if not isinstance(zones, dict) or not zones:
            return False

        applied = 0
        for zone_entry in zones.values():
            if not isinstance(zone_entry, dict):
                continue
            zone_name = zone_entry.get("zone_name")
            if not zone_name:
                continue

            config = (
                zone_entry.get("config", {}) if isinstance(zone_entry.get("config"), dict) else {}
            )
            pattern_name = config.get("pattern", "spectrum")
            render_mode = config.get("render_mode")
            if render_mode not in ("block", "bitmap"):
                render_mode = "bitmap" if str(pattern_name).startswith("bmp_") else "block"

            explicit_count = config.get("entity_count", zone_entry.get("entity_count"))
            try:
                explicit_count = int(explicit_count)
            except Exception:
                explicit_count = None
            if explicit_count is not None and explicit_count < 1:
                explicit_count = None

            block_type = config.get("block_type")

            zs = self._get_zone_state(zone_name)
            if block_type:
                zs.block_type = str(block_type)

            if render_mode == "bitmap":
                if not str(pattern_name).startswith("bmp_"):
                    pattern_name = "bmp_spectrum"
                zs.render_mode = "bitmap"
                zs.pattern_name = str(pattern_name)
                zs.transitioning = False
                zs.transition_pending_resize = None
                zs.bitmap_initialized = False
                zs.bitmap_width = 0
                zs.bitmap_height = 0
                if explicit_count is not None:
                    zs.entity_count = explicit_count
                    zs.config.entity_count = explicit_count
            else:
                if not _lua_pattern_exists(str(pattern_name)):
                    pattern_name = "spectrum"
                zs.render_mode = "block"
                zs.bitmap_initialized = False
                zs.bitmap_width = 0
                zs.bitmap_height = 0
                target_count = (
                    explicit_count
                    if explicit_count is not None
                    else get_recommended_entity_count(str(pattern_name), zs.entity_count)
                )
                zs.entity_count = target_count
                zs.config.entity_count = target_count
                zs.pattern_name = str(pattern_name)
                zs.pattern = get_pattern(str(pattern_name), zs.config)
                zs.transitioning = False
                zs.transition_pending_resize = None

            if zone_name == self.zone:
                self.entity_count = zs.entity_count
                self._pattern_config.entity_count = zs.entity_count
            applied += 1

        logger.info(
            "Rehydrated %d zone(s) from active stage '%s'",
            applied,
            active_stage.get("name", "unknown"),
        )
        return applied > 0

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

    async def _pattern_hot_reload_loop(self):
        """
        Monitor pattern files for changes and reload them automatically.

        Polls the patterns/ directory every 2.5 seconds for file modifications.
        When a pattern file changes:
        - Reload its Lua script
        - If it's the active pattern, switch to the reloaded version
        - Broadcast updated pattern list to browsers
        """
        check_interval = 2.5  # Check every 2.5 seconds
        patterns_dir = Path(__file__).parent.parent / "patterns"

        # Initialize mtime cache
        if patterns_dir.exists():
            for lua_file in patterns_dir.glob("*.lua"):
                try:
                    self._pattern_file_mtimes[lua_file.name] = lua_file.stat().st_mtime
                except Exception:
                    pass

        while self._running:
            try:
                await asyncio.sleep(check_interval)

                if not self._pattern_hot_reload_enabled or not patterns_dir.exists():
                    continue

                # Track changes
                changed_patterns = []
                new_patterns = []
                deleted_patterns = []
                lib_changed = False

                # Check if lib.lua changed (affects all patterns)
                lib_file = patterns_dir / "lib.lua"
                if lib_file.exists():
                    try:
                        lib_mtime = lib_file.stat().st_mtime
                        last_lib_mtime = self._pattern_file_mtimes.get("lib.lua")
                        if last_lib_mtime is None:
                            self._pattern_file_mtimes["lib.lua"] = lib_mtime
                        elif lib_mtime > last_lib_mtime:
                            lib_changed = True
                            self._pattern_file_mtimes["lib.lua"] = lib_mtime
                            logger.info(
                                "[PATTERN RELOAD] Detected change in 'lib.lua' (affects all patterns)"
                            )
                    except Exception as e:
                        logger.debug(f"[PATTERN RELOAD] Error checking lib.lua: {e}")

                # Get current pattern files
                current_files = set()
                for lua_file in patterns_dir.glob("*.lua"):
                    if lua_file.name == "lib.lua":
                        continue
                    current_files.add(lua_file.name)

                    try:
                        current_mtime = lua_file.stat().st_mtime
                        last_mtime = self._pattern_file_mtimes.get(lua_file.name)

                        if last_mtime is None:
                            # New file
                            new_patterns.append(lua_file.stem)
                            self._pattern_file_mtimes[lua_file.name] = current_mtime
                        elif current_mtime > last_mtime or lib_changed:
                            # Modified file or lib.lua changed
                            changed_patterns.append(lua_file.stem)
                            self._pattern_file_mtimes[lua_file.name] = current_mtime
                    except Exception as e:
                        logger.debug(f"[PATTERN RELOAD] Error checking {lua_file.name}: {e}")

                # Check for deleted files (exclude lib.lua which is tracked separately)
                cached_files = set(self._pattern_file_mtimes.keys()) - {"lib.lua"}
                for filename in cached_files - current_files:
                    pattern_key = Path(filename).stem
                    deleted_patterns.append(pattern_key)
                    del self._pattern_file_mtimes[filename]

                # Handle changes
                if changed_patterns:
                    for pattern_key in changed_patterns:
                        logger.info(f"[PATTERN RELOAD] Detected change in '{pattern_key}.lua'")

                        # Reload in any zone using this pattern
                        for zn, zs in self._zone_patterns.items():
                            if zs.pattern_name == pattern_key:
                                try:
                                    old_state = (
                                        zs.pattern._entity_state.copy()
                                        if hasattr(zs.pattern, "_entity_state")
                                        else {}
                                    )
                                    zs.pattern = get_pattern(pattern_key, zs.config)
                                    if old_state:
                                        zs.pattern.seed_entity_state(old_state)
                                    logger.info(
                                        f"[PATTERN RELOAD] Reloaded pattern '{pattern_key}' in zone '{zn}'"
                                    )
                                    if zs.transitioning and zs.old_pattern_name == pattern_key:
                                        zs.old_pattern = get_pattern(pattern_key, zs.config)
                                except Exception as e:
                                    logger.error(
                                        f"[PATTERN RELOAD] Failed to reload pattern '{pattern_key}' in zone '{zn}': {e}"
                                    )

                    # Refresh cached pattern list and broadcast to browsers
                    refresh_pattern_cache()
                    await self._broadcast_pattern_list()

                if new_patterns:
                    for pattern_key in new_patterns:
                        logger.info(f"[PATTERN RELOAD] New pattern discovered: '{pattern_key}.lua'")
                    refresh_pattern_cache()
                    await self._broadcast_pattern_list()

                if deleted_patterns:
                    for pattern_key in deleted_patterns:
                        logger.info(f"[PATTERN RELOAD] Pattern deleted: '{pattern_key}.lua'")

                        # Switch any zone using deleted pattern to fallback
                        for zn, zs in self._zone_patterns.items():
                            if zs.pattern_name == pattern_key:
                                fallback = "spectrum"
                                logger.warning(
                                    f"[PATTERN RELOAD] Pattern '{pattern_key}' deleted, zone '{zn}' switching to '{fallback}'"
                                )
                                zs.pattern_name = fallback
                                zs.pattern = get_pattern(fallback, zs.config)
                                zs.transitioning = False

                        await self._broadcast_pattern_change()

                    refresh_pattern_cache()
                    await self._broadcast_pattern_list()

            except asyncio.CancelledError:
                logger.debug("[PATTERN RELOAD] Pattern reload loop cancelled")
                break
            except Exception as e:
                logger.error(f"[PATTERN RELOAD] Loop error: {e}")
                await asyncio.sleep(check_interval)

    async def _broadcast_pattern_list(self):
        """Broadcast updated pattern list to all browser clients."""
        message = _json_str(
            {
                "type": "patterns",
                "patterns": self._get_all_patterns_list(),
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

    def _apply_effects(self, entities: List[dict], bands: List[float]) -> List[dict]:
        """Apply active timed effects (flash, strobe, pulse, wave, etc.) to entities."""
        if not self._active_effects:
            return entities

        now = time.time()
        modified = [dict(e) for e in entities]

        for effect_type, effect in self._active_effects.items():
            if effect_type in ("blackout", "freeze"):
                continue  # Handled in main loop

            intensity = effect["intensity"]
            elapsed = now - effect["start_time"]
            duration_s = effect["duration"] / 1000.0
            progress = min(1.0, elapsed / duration_s) if duration_s > 0 else 1.0

            if effect_type == "flash":
                flash_mult = intensity * (1.0 - progress)
                for e in modified:
                    e["scale"] = min(1.0, e.get("scale", 0.5) + flash_mult * 0.5)
                    e["y"] = min(1.0, e.get("y", 0) + flash_mult * 0.2)

            elif effect_type == "strobe":
                strobe_on = int(elapsed * 8) % 2 == 0
                if not strobe_on:
                    for e in modified:
                        e["scale"] = 0.01

            elif effect_type == "pulse":
                pulse_val = math.sin(elapsed * math.pi * 4) * intensity
                for e in modified:
                    base_scale = e.get("scale", 0.5)
                    e["scale"] = max(0.05, base_scale * (1.0 + pulse_val * 0.5))

            elif effect_type == "wave":
                for i, e in enumerate(modified):
                    phase = (i / max(1, len(modified))) * math.pi * 2
                    wave_val = math.sin(elapsed * 3.0 + phase) * intensity
                    e["y"] = max(0, min(1.0, e.get("y", 0) + wave_val * 0.3))

            elif effect_type == "spiral":
                for i, e in enumerate(modified):
                    angle = elapsed * 2.0 + (i / max(1, len(modified))) * math.pi * 2
                    radius = 0.3 * intensity * (1.0 - progress * 0.5)
                    e["x"] = max(0, min(1.0, 0.5 + math.cos(angle) * radius))
                    e["z"] = max(0, min(1.0, 0.5 + math.sin(angle) * radius))

            elif effect_type == "explode":
                explode_force = intensity * (1.0 - progress)
                for e in modified:
                    dx = e.get("x", 0.5) - 0.5
                    dy = e.get("y", 0.5) - 0.5
                    dz = e.get("z", 0.5) - 0.5
                    dist = max(0.1, (dx * dx + dy * dy + dz * dz) ** 0.5)
                    force = explode_force / dist * 0.3
                    e["x"] = max(0, min(1.0, e.get("x", 0.5) + dx * force))
                    e["y"] = max(0, min(1.0, e.get("y", 0.5) + dy * force))
                    e["z"] = max(0, min(1.0, e.get("z", 0.5) + dz * force))
                    e["scale"] = max(0.05, e.get("scale", 0.5) * (1.0 + explode_force * 0.5))

        return modified

    async def _set_pattern_for_zone(
        self,
        zone_state: ZonePatternState,
        zone_name: str,
        pattern_name: str,
        explicit_entity_count: Optional[int] = None,
    ):
        """Set pattern on a specific zone with crossfade support."""
        if not _lua_pattern_exists(pattern_name):
            return

        old_count = zone_state.entity_count
        target_count = (
            max(1, int(explicit_entity_count))
            if explicit_entity_count is not None
            else get_recommended_entity_count(pattern_name, old_count)
        )
        if target_count != zone_state.entity_count:
            zone_state.entity_count = target_count
            zone_state.config.entity_count = target_count

        if zone_state.transition_duration > 0 and pattern_name != zone_state.pattern_name:
            zone_state.old_pattern = zone_state.pattern
            zone_state.old_pattern_name = zone_state.pattern_name
            if old_count != zone_state.entity_count:
                zone_state.old_pattern.config = replace(
                    zone_state.old_pattern.config, entity_count=old_count
                )
            zone_state.pattern = get_pattern(pattern_name, zone_state.config)
            if (
                hasattr(zone_state.old_pattern, "_entity_state")
                and zone_state.old_pattern._entity_state
            ):
                zone_state.pattern.seed_entity_state(zone_state.old_pattern._entity_state)
            zone_state.pattern_name = pattern_name
            self._pattern_changes += 1
            zone_state.transitioning = True
            zone_state.transition_start = time.monotonic()
            zone_state.transition_pending_resize = None

            if (
                old_count != zone_state.entity_count
                and not zone_state.bitmap_initialized
                and self.viz_client
                and self.viz_client.connected
            ):
                try:
                    if zone_state.entity_count > old_count:
                        await self.viz_client.init_pool(
                            zone_name, zone_state.entity_count, zone_state.block_type
                        )
                    else:
                        zone_state.transition_pending_resize = zone_state.entity_count
                except Exception as e:
                    logger.warning(
                        f"Failed to resize pool for pattern '{pattern_name}' in zone '{zone_name}': {e}"
                    )

            logger.info(
                f"Starting {zone_state.transition_duration}s crossfade in zone '{zone_name}': "
                f"{zone_state.old_pattern_name} -> {pattern_name}"
            )
        else:
            if pattern_name != zone_state.pattern_name:
                self._pattern_changes += 1
            zone_state.pattern_name = pattern_name
            zone_state.pattern = get_pattern(pattern_name, zone_state.config)
            zone_state.transitioning = False

            if (
                old_count != zone_state.entity_count
                and not zone_state.bitmap_initialized
                and self.viz_client
                and self.viz_client.connected
            ):
                try:
                    # Just resize — init_pool resizes existing pools in place
                    # (no cleanup_zone needed; that would destroy + recreate = flash)
                    await self.viz_client.init_pool(
                        zone_name, zone_state.entity_count, zone_state.block_type
                    )
                except Exception as e:
                    logger.warning(
                        f"Failed to apply entity count for pattern '{pattern_name}' in zone '{zone_name}': {e}"
                    )

        # Sync entity_count on the active zone to self.entity_count for backward compat
        if zone_name == self.zone:
            self.entity_count = zone_state.entity_count
            self._pattern_config.entity_count = zone_state.entity_count

    async def _handle_stage_zone_configs(self, data: dict):
        """Handle stage_zone_configs from MC plugin to apply patterns with correct entity counts."""
        zones = data.get("zones", [])
        stage_name = data.get("stage", "unknown")
        logger.info(f"Received stage zone configs for stage '{stage_name}' ({len(zones)} zones)")

        for zone_info in zones:
            zone_name = zone_info.get("zone")
            pattern_name = zone_info.get("pattern")
            if not zone_name or not pattern_name:
                continue

            zs = self._get_zone_state(zone_name)
            if zone_info.get("block_type"):
                zs.block_type = str(zone_info.get("block_type"))
            explicit_count = zone_info.get("entity_count")
            try:
                explicit_count = int(explicit_count) if explicit_count is not None else None
            except Exception:
                explicit_count = None
            if explicit_count is not None and explicit_count < 1:
                explicit_count = None

            render_mode = zone_info.get("render_mode")
            if render_mode not in ("block", "bitmap"):
                render_mode = "bitmap" if str(pattern_name).startswith("bmp_") else "block"

            if render_mode == "bitmap":
                if explicit_count is not None:
                    zs.entity_count = explicit_count
                    zs.config.entity_count = explicit_count
                await self._switch_zone_to_bitmap(zone_name, zs, str(pattern_name))
            else:
                await self._set_pattern_for_zone(
                    zs,
                    zone_name,
                    str(pattern_name),
                    explicit_entity_count=explicit_count,
                )
            logger.info(
                f"Stage zone '{zone_name}': pattern={pattern_name}, mode={render_mode}, entities={zs.entity_count}"
            )

    async def _calculate_entities_for_zone(
        self, zone_state: ZonePatternState, audio_state: "AudioState", zone_name: str = ""
    ) -> List[dict]:
        """Calculate entity positions for a specific zone, with crossfade support.

        When MCAV_ASYNC_LUA=1, pattern calculation runs on a thread pool
        worker via asyncio.to_thread.  Disabled by default because ~85%
        of the work is Python code (_unpack_flat, _smooth_entity) that
        holds the GIL, making threading counterproductive.
        """
        # Inject active DJ palette into zone pattern config
        active_dj = self._get_active_dj()
        if zone_state.pattern and hasattr(zone_state.pattern, "set_dj_palette"):
            if active_dj:
                zone_state.pattern.set_dj_palette(active_dj.color_palette, active_dj.block_palette)
            else:
                zone_state.pattern.set_dj_palette(None, None)

        if zone_state.transitioning:
            elapsed = time.monotonic() - zone_state.transition_start

            if elapsed >= zone_state.transition_duration:
                zone_state.transitioning = False
                zone_state.old_pattern = None
                zone_state.old_pattern_name = None
                if zone_state.transition_pending_resize is not None:
                    pending = zone_state.transition_pending_resize
                    zone_state.transition_pending_resize = None
                    if (
                        not zone_state.bitmap_initialized
                        and self.viz_client
                        and self.viz_client.connected
                    ):
                        asyncio.ensure_future(
                            self.viz_client.init_pool(
                                zone_name or self.zone, pending, zone_state.block_type
                            )
                        )
                if _USE_ASYNC_LUA:
                    return await asyncio.to_thread(
                        zone_state.pattern.calculate_entities, audio_state
                    )
                return zone_state.pattern.calculate_entities(audio_state)

            t = elapsed / zone_state.transition_duration
            alpha = self._smoothstep(t)
            if _USE_ASYNC_LUA:
                old_entities, new_entities = await asyncio.gather(
                    asyncio.to_thread(zone_state.old_pattern.calculate_entities, audio_state),
                    asyncio.to_thread(zone_state.pattern.calculate_entities, audio_state),
                )
            else:
                old_entities = zone_state.old_pattern.calculate_entities(audio_state)
                new_entities = zone_state.pattern.calculate_entities(audio_state)
            return self._blend_entities(old_entities, new_entities, alpha)

        if _USE_ASYNC_LUA:
            return await asyncio.to_thread(zone_state.pattern.calculate_entities, audio_state)
        return zone_state.pattern.calculate_entities(audio_state)

    def _calculate_entities(
        self,
        bands: List[float],
        peak: float,
        is_beat: bool,
        beat_intensity: float,
        bpm: float = 0.0,
        beat_phase: float = 0.0,
    ) -> List[dict]:
        """Calculate entity positions from audio state, with pattern crossfade support."""
        audio_state = AudioState(
            bands=bands,
            amplitude=peak,
            is_beat=is_beat,
            beat_intensity=beat_intensity,
            frame=self._frame_count,
            bpm=bpm,
            beat_phase=beat_phase,
        )

        # Inject active DJ palette into pattern config
        active_dj = self._get_active_dj()
        if self._current_pattern and hasattr(self._current_pattern, "set_dj_palette"):
            if active_dj:
                self._current_pattern.set_dj_palette(
                    active_dj.color_palette, active_dj.block_palette
                )
            else:
                self._current_pattern.set_dj_palette(None, None)

        # Check if we're transitioning between patterns
        if self._transitioning:
            elapsed = time.monotonic() - self._transition_start

            # Check if transition is complete
            if elapsed >= self._transition_duration:
                self._transitioning = False
                self._old_pattern = None
                self._old_pattern_name = None
                # Deferred pool shrink: remove excess entities after merge animation
                if self._transition_pending_resize is not None:
                    pending = self._transition_pending_resize
                    self._transition_pending_resize = None
                    zs_legacy = self._get_zone_state(self.zone)
                    if (
                        not zs_legacy.bitmap_initialized
                        and self.viz_client
                        and self.viz_client.connected
                    ):
                        asyncio.ensure_future(
                            self.viz_client.init_pool(self.zone, pending, zs_legacy.block_type)
                        )
                entities = self._current_pattern.calculate_entities(audio_state)
                logger.info(
                    f"Crossfade complete: now on {self._pattern_name}, "
                    f"entity_count={self.entity_count}, config_count={self._pattern_config.entity_count}, "
                    f"pattern_returned={len(entities)}, pending_resize={pending if self._transition_pending_resize is None else 'done'}"
                )
                return entities

            # Calculate blend alpha using smoothstep easing
            t = elapsed / self._transition_duration
            alpha = self._smoothstep(t)

            # Get entities from both patterns
            old_entities = self._old_pattern.calculate_entities(audio_state)
            new_entities = self._current_pattern.calculate_entities(audio_state)

            # Blend the entities
            blended = self._blend_entities(old_entities, new_entities, alpha)
            if self._frame_count % 30 == 0:
                logger.debug(
                    f"Transition alpha={alpha:.2f}, old={len(old_entities)}, "
                    f"new={len(new_entities)}, blended={len(blended)}"
                )
            return blended

        # No transition - just return current pattern
        return self._current_pattern.calculate_entities(audio_state)

    def _smoothstep(self, t: float) -> float:
        """Smoothstep interpolation for smooth easing (0->1)."""
        t = max(0.0, min(1.0, t))
        return t * t * (3.0 - 2.0 * t)

    def _blend_entities(
        self, old_entities: List[dict], new_entities: List[dict], alpha: float
    ) -> List[dict]:
        """
        Blend between old and new entity lists using alpha (0=old, 1=new).
        Split/merge: new-only entities emerge from a sibling's position (split),
        old-only entities collapse into a sibling's position (merge).
        """
        # Build dictionaries by entity ID for fast lookup
        old_dict = {e.get("id", f"e{i}"): e for i, e in enumerate(old_entities)}
        new_dict = {e.get("id", f"e{i}"): e for i, e in enumerate(new_entities)}
        old_count = len(old_entities)
        new_count = len(new_entities)

        # All entity IDs from both patterns
        all_ids = set(old_dict.keys()) | set(new_dict.keys())

        blended = []
        for eid in all_ids:
            old_e = old_dict.get(eid)
            new_e = new_dict.get(eid)

            if old_e and new_e:
                # Entity exists in both patterns - lerp all properties
                blended_e = {
                    "id": eid,
                    "x": self._lerp(old_e.get("x", 0.5), new_e.get("x", 0.5), alpha),
                    "y": self._lerp(old_e.get("y", 0.5), new_e.get("y", 0.5), alpha),
                    "z": self._lerp(old_e.get("z", 0.5), new_e.get("z", 0.5), alpha),
                    "scale": self._lerp(old_e.get("scale", 0.5), new_e.get("scale", 0.5), alpha),
                    "rotation": self._lerp_angle(
                        old_e.get("rotation", 0.0), new_e.get("rotation", 0.0), alpha
                    ),
                    "band": new_e.get("band", old_e.get("band", 0)),
                    "visible": True,
                }
                # Prefer new pattern's material (new pattern takes over appearance)
                mat = new_e.get("material") or old_e.get("material")
                if mat:
                    blended_e["material"] = mat
            elif new_e:
                # Split: entity only in new pattern — emerge from a sibling's position
                sibling_id = self._get_sibling_id(eid, old_count)
                sibling = old_dict.get(sibling_id)
                if sibling:
                    blended_e = {
                        "id": eid,
                        "x": self._lerp(sibling.get("x", 0.5), new_e.get("x", 0.5), alpha),
                        "y": self._lerp(sibling.get("y", 0.5), new_e.get("y", 0.5), alpha),
                        "z": self._lerp(sibling.get("z", 0.5), new_e.get("z", 0.5), alpha),
                        "scale": self._lerp(
                            sibling.get("scale", 0.5), new_e.get("scale", 0.5), alpha
                        ),
                        "rotation": self._lerp_angle(
                            sibling.get("rotation", 0.0), new_e.get("rotation", 0.0), alpha
                        ),
                        "band": new_e.get("band", 0),
                        "visible": True,
                    }
                else:
                    # No sibling found — fade in
                    blended_e = new_e.copy()
                    blended_e["scale"] = new_e.get("scale", 0.5) * alpha
                # Use new entity's material
                mat = new_e.get("material")
                if mat:
                    blended_e["material"] = mat
            else:
                # Merge: entity only in old pattern — collapse into a sibling's position
                sibling_id = self._get_sibling_id(eid, new_count)
                sibling = new_dict.get(sibling_id)
                if sibling:
                    blended_e = {
                        "id": eid,
                        "x": self._lerp(old_e.get("x", 0.5), sibling.get("x", 0.5), alpha),
                        "y": self._lerp(old_e.get("y", 0.5), sibling.get("y", 0.5), alpha),
                        "z": self._lerp(old_e.get("z", 0.5), sibling.get("z", 0.5), alpha),
                        "scale": self._lerp(
                            old_e.get("scale", 0.5), sibling.get("scale", 0.5), alpha
                        ),
                        "rotation": self._lerp_angle(
                            old_e.get("rotation", 0.0), sibling.get("rotation", 0.0), alpha
                        ),
                        "band": old_e.get("band", 0),
                        "visible": True,
                    }
                else:
                    # No sibling found — fade out
                    blended_e = old_e.copy()
                    blended_e["scale"] = old_e.get("scale", 0.5) * (1.0 - alpha)
                # Keep old entity's material while fading out
                mat = old_e.get("material")
                if mat:
                    blended_e["material"] = mat

            blended.append(blended_e)

        return blended

    @staticmethod
    def _get_sibling_id(eid: str, sibling_count: int) -> Optional[str]:
        """Map an entity to its sibling in the other pattern via modulo wrapping."""
        if sibling_count <= 0:
            return None
        # Extract index from "block_N" format
        if eid.startswith("block_"):
            try:
                idx = int(eid[6:])
                return f"block_{idx % sibling_count}"
            except ValueError:
                pass
        return None

    def _lerp(self, a: float, b: float, t: float) -> float:
        """Linear interpolation between a and b."""
        return a + (b - a) * t

    @staticmethod
    def _lerp_angle(a: float, b: float, t: float) -> float:
        """Shortest-path angle interpolation (degrees)."""
        delta = ((b - a + 180.0) % 360.0) - 180.0
        return (a + delta * t) % 360.0

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

        # Start DJ listener (64KB max message â€” valid audio frames are ~200 bytes)
        dj_server = await ws_serve(
            self._handle_dj_connection,
            "0.0.0.0",
            self.dj_port,
            max_size=65_536,
        )
        logger.info(f"DJ WebSocket server: ws://localhost:{self.dj_port}")

        # Start browser broadcast server (256KB max â€” config messages can be larger)
        broadcast_server = await ws_serve(
            self._handle_browser_client,
            "0.0.0.0",
            self.broadcast_port,
            max_size=262_144,
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
    parser.add_argument("--require-auth", action="store_true", help="Require DJ authentication")
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
    elif args.require_auth:
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
        require_auth=args.require_auth,
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
