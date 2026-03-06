"""
VJ Server data models, structs, and helper functions.

Extracted from vj_server.py — contains everything that lives before the
VJServer class definition: msgspec structs, dataclasses, validation helpers,
HTTP handlers, and module-level constants.
"""

import http.server
import json
import logging
import math
import os
import posixpath
import re
import secrets
import socketserver
import sys
import time
import urllib.parse
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any, Dict, List, Optional

import msgspec

if TYPE_CHECKING:
    import websockets

    from vj_server.patterns import PatternConfig
import msgspec.json as mjson

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
