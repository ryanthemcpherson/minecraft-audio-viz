"""
Visualization Patterns for AudioViz

All visualization patterns are implemented in Lua (patterns/*.lua).
This module provides the pattern engine (LuaPattern) and registry functions.
"""

import logging
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)


# ============================================================================
# Data Classes
# ============================================================================


@dataclass
class PatternConfig:
    """Configuration for visualization patterns."""

    entity_count: int = 16
    zone_size: float = 10.0
    beat_boost: float = 1.5
    base_scale: float = 0.2
    max_scale: float = 1.0


@dataclass
class AudioState:
    """Current audio state for pattern calculation."""

    bands: List[float]  # 5 frequency bands (bass, low-mid, mid, high-mid, high)
    amplitude: float  # Overall amplitude 0-1
    is_beat: bool  # Beat detected this frame
    beat_intensity: float  # Beat strength 0-1
    frame: int  # Frame counter
    bpm: float = 0.0  # Estimated BPM from DJ client
    beat_phase: float = 0.0  # Beat phase 0.0-1.0 (0 = on beat, 0.5 = halfway)


class VisualizationPattern(ABC):
    """Base class for visualization patterns."""

    name: str = "Base"
    description: str = "Base pattern"
    category: str = ""
    static_camera: bool = False
    recommended_entities: Optional[int] = None

    def __init__(self, config: PatternConfig = None):
        self.config = config or PatternConfig()
        self._time = 0.0
        self._beat_accumulator = 0.0

    @abstractmethod
    def calculate_entities(self, audio: AudioState) -> List[Dict[str, Any]]:
        """Calculate entity positions based on audio state."""
        pass

    def update(self, dt: float = 0.016):
        """Update internal time."""
        self._time += dt
        self._beat_accumulator *= 0.9


# ============================================================================
# Lua Pattern Runner
# ============================================================================


class LuaPattern(VisualizationPattern):
    """Pattern implementation that runs a Lua script."""

    def __init__(self, pattern_key: str, config: PatternConfig = None):
        super().__init__(config)
        self._pattern_key = pattern_key
        self._lua = None
        self._calculate = None
        self._last_call_time = None
        self._audio_table = None
        self._bands_table = None
        self._config_table = None
        self._entity_state = {}
        self._position_deadband = 0.0015
        self._load_lua(pattern_key)

    def seed_entity_state(self, state: dict):
        """Seed entity positions from another pattern's state for smooth transitions."""
        self._entity_state = {k: dict(v) for k, v in state.items()}

    def _load_lua(self, pattern_key: str):
        try:
            from lupa import LuaRuntime
        except ImportError:
            # Fallback: if lupa not installed, use a no-op pattern
            logger.warning("lupa not installed, Lua patterns unavailable")
            return

        self._lua = LuaRuntime(unpack_returned_tuples=True)

        # Load lib.lua (prefer pre-loaded cache, fall back to disk)
        patterns_dir = Path(__file__).parent.parent / "patterns"
        lib_text = _lib_cache
        if lib_text is None:
            lib_path = patterns_dir / "lib.lua"
            if lib_path.exists():
                lib_text = lib_path.read_text(encoding="utf-8")
        if lib_text:
            self._lua.execute(lib_text)

        # Load pattern script (prefer pre-loaded cache, fall back to disk)
        pattern_text = _file_cache.get(pattern_key)
        if pattern_text is None:
            pattern_path = patterns_dir / f"{pattern_key}.lua"
            if pattern_path.exists():
                pattern_text = pattern_path.read_text(encoding="utf-8")
        if pattern_text is not None:
            self._lua.execute(pattern_text)
            self._calculate = self._lua.globals()["calculate"]
            # Pre-allocate persistent Lua tables for reuse each frame
            self._audio_table = self._lua.table_from(
                {
                    "amplitude": 0.0,
                    "peak": 0.0,
                    "is_beat": False,
                    "beat": False,
                    "beat_intensity": 0.0,
                    "frame": 0,
                    "bpm": 0.0,
                    "beat_phase": 0.0,
                }
            )
            self._bands_table = self._lua.table_from({1: 0.0, 2: 0.0, 3: 0.0, 4: 0.0, 5: 0.0})
            self._audio_table["bands"] = self._bands_table
            self._config_table = self._lua.table_from(
                {
                    "entity_count": 0,
                    "zone_size": 0,
                    "beat_boost": 0.0,
                    "base_scale": 0.0,
                    "max_scale": 0.0,
                }
            )
            # Read metadata from Lua globals
            g = self._lua.globals()
            try:
                lua_name = g["name"]
                if lua_name:
                    self.name = str(lua_name)
            except (KeyError, IndexError):
                pass
            try:
                lua_desc = g["description"]
                if lua_desc:
                    self.description = str(lua_desc)
            except (KeyError, IndexError):
                pass
            try:
                rec = g["recommended_entities"]
                if isinstance(rec, (int, float)):
                    self.recommended_entities = max(1, min(256, int(rec)))
            except (KeyError, IndexError):
                pass
            try:
                rec = g["start_blocks"]
                if self.recommended_entities is None and isinstance(rec, (int, float)):
                    self.recommended_entities = max(1, min(256, int(rec)))
            except (KeyError, IndexError):
                pass
            try:
                cat = g["category"]
                if cat:
                    self.category = str(cat)
            except (KeyError, IndexError):
                pass
            try:
                sc = g["static_camera"]
                if sc is not None:
                    self.static_camera = bool(sc)
            except (KeyError, IndexError):
                pass
        else:
            logger.warning(f"Pattern file not found: {pattern_key}.lua")

    def calculate_entities(self, audio: AudioState) -> list:
        if self._calculate is None:
            return []

        try:
            # Compute real dt
            now = time.monotonic()
            if self._last_call_time is not None:
                dt = min(now - self._last_call_time, 0.05)  # Cap at 50ms
            else:
                dt = 0.016
            self._last_call_time = now

            # Update pre-allocated audio table in-place
            audio_table = self._audio_table
            audio_table["amplitude"] = audio.amplitude
            audio_table["peak"] = audio.amplitude
            audio_table["is_beat"] = audio.is_beat
            audio_table["beat"] = audio.is_beat
            audio_table["beat_intensity"] = audio.beat_intensity
            audio_table["frame"] = audio.frame
            audio_table["bpm"] = audio.bpm
            audio_table["beat_phase"] = audio.beat_phase
            bands_table = self._bands_table
            for i, v in enumerate(audio.bands):
                bands_table[i + 1] = v

            # Update pre-allocated config table in-place
            config_table = self._config_table
            config_table["entity_count"] = self.config.entity_count
            config_table["zone_size"] = self.config.zone_size
            config_table["beat_boost"] = self.config.beat_boost
            config_table["base_scale"] = self.config.base_scale
            config_table["max_scale"] = self.config.max_scale

            result = self._calculate(audio_table, config_table, dt)

            # Convert Lua table to Python list of dicts
            entities = []
            seen_ids = set()
            if result is not None:
                for i in range(1, len(result) + 1):
                    entry = result[i]
                    if entry is not None:
                        entity_id = str(entry["id"]) if entry["id"] else f"block_{i - 1}"
                        seen_ids.add(entity_id)
                        target_x = float(entry["x"] or 0.5)
                        target_y = float(entry["y"] or 0.5)
                        target_z = float(entry["z"] or 0.5)
                        target_scale = float(entry["scale"] or 0.2)
                        target_rotation = float(entry["rotation"] or 0.0)
                        prev = self._entity_state.get(entity_id)

                        if prev is None:
                            x = target_x
                            y = target_y
                            z = target_z
                            scale = target_scale
                            rotation = target_rotation % 360.0
                        else:
                            # dt-aware smoothing: effective_alpha adapts to actual frame time
                            # so animation speed is consistent regardless of frame rate.
                            dt_ratio = dt / 0.016

                            # Fast for larger moves, gentler for tiny changes to reduce wobble.
                            pos_delta = max(
                                abs(target_x - prev["x"]),
                                abs(target_y - prev["y"]),
                                abs(target_z - prev["z"]),
                            )
                            base_pos_alpha = 0.78 if pos_delta > 0.035 else 0.48
                            pos_alpha = 1.0 - (1.0 - base_pos_alpha) ** dt_ratio
                            x = prev["x"] + (target_x - prev["x"]) * pos_alpha
                            y = prev["y"] + (target_y - prev["y"]) * pos_alpha
                            z = prev["z"] + (target_z - prev["z"]) * pos_alpha
                            if abs(x - prev["x"]) < self._position_deadband:
                                x = prev["x"]
                            if abs(y - prev["y"]) < self._position_deadband:
                                y = prev["y"]
                            if abs(z - prev["z"]) < self._position_deadband:
                                z = prev["z"]

                            base_scale_alpha = 0.84 if target_scale > prev["scale"] else 0.56
                            scale_alpha = 1.0 - (1.0 - base_scale_alpha) ** dt_ratio
                            scale = prev["scale"] + (target_scale - prev["scale"]) * scale_alpha

                            # Shortest-path angle smoothing.
                            current_rot = prev["rotation"] % 360.0
                            desired_rot = target_rotation % 360.0
                            delta_rot = ((desired_rot - current_rot + 180.0) % 360.0) - 180.0
                            rot_alpha = 1.0 - (1.0 - 0.52) ** dt_ratio
                            rotation = (current_rot + delta_rot * rot_alpha) % 360.0

                        self._entity_state[entity_id] = {
                            "x": x,
                            "y": y,
                            "z": z,
                            "scale": scale,
                            "rotation": rotation,
                        }
                        entity = {
                            "id": entity_id,
                            "x": x,
                            "y": y,
                            "z": z,
                            "scale": scale,
                            "rotation": rotation,
                            "band": int(entry["band"] or 0),
                            "visible": bool(entry["visible"])
                            if entry["visible"] is not None
                            else True,
                        }
                        # Forward optional rendering fields when set by pattern
                        if entry["glow"] is not None:
                            entity["glow"] = bool(entry["glow"])
                        if entry["brightness"] is not None:
                            entity["brightness"] = int(entry["brightness"])
                        if entry["material"] is not None:
                            entity["material"] = str(entry["material"])
                        if entry["interpolation"] is not None:
                            entity["interpolation"] = int(entry["interpolation"])
                        entities.append(entity)
            # Enforce a strict entity budget for all patterns.
            target_count = max(0, int(self.config.entity_count))
            if len(entities) > target_count:
                entities = entities[:target_count]
            elif len(entities) < target_count:
                pad_index = len(entities)
                while len(entities) < target_count:
                    pad_id = f"block_{pad_index}"
                    if pad_id in seen_ids:
                        pad_id = f"__pad_{pad_index}"
                    pad_index += 1
                    seen_ids.add(pad_id)
                    self._entity_state[pad_id] = {
                        "x": 0.5,
                        "y": 0.5,
                        "z": 0.5,
                        "scale": 0.0,
                        "rotation": 0.0,
                    }
                    entities.append(
                        {
                            "id": pad_id,
                            "x": 0.5,
                            "y": 0.5,
                            "z": 0.5,
                            "scale": 0.0,
                            "rotation": 0.0,
                            "band": 0,
                            "visible": False,
                        }
                    )

            seen_ids = {entity["id"] for entity in entities}

            # Cleanup stale cached entities.
            if self._entity_state:
                stale = [eid for eid in self._entity_state.keys() if eid not in seen_ids]
                for eid in stale:
                    del self._entity_state[eid]
            return entities
        except Exception as e:
            logger.error(f"Lua pattern error ({self._pattern_key}): {e}")
            return []


# ============================================================================
# Pattern Registry
# ============================================================================

_PATTERNS_DIR = Path(__file__).parent.parent / "patterns"

# Pre-loaded file cache: pattern_key -> file_text (populated at startup, refreshed on hot-reload)
_file_cache: Dict[str, str] = {}
_lib_cache: Optional[str] = None

# Cached pattern metadata list (built once at startup, refreshed on hot-reload)
_cached_patterns: Optional[List[Dict[str, Any]]] = None


def _preload_pattern_files() -> None:
    """Pre-load all pattern Lua files into memory to avoid blocking I/O in async context."""
    global _lib_cache
    if not _PATTERNS_DIR.is_dir():
        return
    lib_path = _PATTERNS_DIR / "lib.lua"
    if lib_path.exists():
        _lib_cache = lib_path.read_text(encoding="utf-8")
    for lua_file in _PATTERNS_DIR.glob("*.lua"):
        if lua_file.name == "lib.lua":
            continue
        _file_cache[lua_file.stem] = lua_file.read_text(encoding="utf-8")


def refresh_pattern_cache() -> None:
    """Rebuild the cached pattern list (called on startup and hot-reload)."""
    global _cached_patterns
    _preload_pattern_files()
    _cached_patterns = _build_pattern_list()


def _build_pattern_list() -> List[Dict[str, Any]]:
    """Scan patterns/*.lua and build pattern metadata list."""
    result = []
    if not _PATTERNS_DIR.is_dir():
        return result
    for lua_file in sorted(_PATTERNS_DIR.glob("*.lua")):
        if lua_file.name == "lib.lua":
            continue
        key = lua_file.stem
        try:
            pat = LuaPattern(key)
            result.append(
                {
                    "id": key,
                    "name": pat.name,
                    "description": pat.description,
                    "category": pat.category,
                    "static_camera": pat.static_camera,
                    "recommended_entities": pat.recommended_entities or 64,
                }
            )
        except Exception as e:
            logger.warning(f"Failed to load pattern '{key}': {e}")
            result.append(
                {
                    "id": key,
                    "name": key.replace("_", " ").title(),
                    "description": "",
                    "category": "",
                    "static_camera": False,
                    "recommended_entities": 64,
                }
            )
    return result


def _lua_pattern_exists(key: str) -> bool:
    """Check if a Lua pattern file exists for the given key."""
    return (_PATTERNS_DIR / f"{key}.lua").exists()


def get_pattern(name: str, config: PatternConfig = None) -> VisualizationPattern:
    """Get a pattern by name. Returns a LuaPattern instance."""
    key = name.lower()
    if not _lua_pattern_exists(key):
        logger.warning(f"Pattern '{key}' not found, falling back to 'spectrum'")
        key = "spectrum"
    return LuaPattern(key, config)


def get_recommended_entity_count(name: str, fallback: int = 64) -> int:
    """Get recommended block count for a pattern from its Lua metadata."""
    key = name.lower()
    if _lua_pattern_exists(key):
        try:
            pat = LuaPattern(key)
            if pat.recommended_entities is not None:
                return pat.recommended_entities
        except Exception:
            pass
    return max(1, min(256, int(fallback)))


def list_patterns() -> List[Dict[str, Any]]:
    """List all available patterns. Returns cached list (refreshed on hot-reload)."""
    global _cached_patterns
    if _cached_patterns is None:
        refresh_pattern_cache()
    return _cached_patterns
