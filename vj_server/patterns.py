"""
Visualization Patterns for AudioViz

All visualization patterns are implemented in Lua (patterns/*.lua).
This module provides the pattern engine (LuaPattern) and registry functions.
"""

import logging
import os
import sys
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

# Feature flag: set MCAV_FLAT_PACK=0 to disable flat array optimization.
# On Windows + Python 3.13, disable by default due to observed native instability
# in some lupa/LuaJIT combinations during test teardown.
_USE_FLAT_PACK = os.environ.get("MCAV_FLAT_PACK", "1") != "0"
if sys.platform == "win32" and sys.version_info >= (3, 13):
    _USE_FLAT_PACK = False

logger = logging.getLogger(__name__)

# Lua execution timeout protection: instruction-count hook fires every
# LUA_HOOK_INTERVAL instructions. If total count exceeds LUA_INSTRUCTION_LIMIT
# in a single calculate() call, a Lua error is raised.
LUA_HOOK_INTERVAL = 1000
LUA_INSTRUCTION_LIMIT = 1_000_000
MAX_CONSECUTIVE_TIMEOUTS = 3


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
    attack: float = 0.5
    release: float = 0.1
    beat_threshold: float = 1.3


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

# Cached LuaRuntime class — resolved once on first use to avoid repeated
# import probing (4 try/except blocks) on every pattern creation.
_resolved_lua_runtime = None
_resolved_lua_runtime_name = None


def _resolve_lua_runtime():
    """Resolve the best available LuaRuntime class once and cache it."""
    global _resolved_lua_runtime, _resolved_lua_runtime_name
    if _resolved_lua_runtime is not None:
        return _resolved_lua_runtime

    # Prefer PUC Lua runtime on Windows/Python 3.13 for stability.
    if sys.platform == "win32" and sys.version_info >= (3, 13):
        try:
            from lupa import LuaRuntime as _LuaRuntime

            _resolved_lua_runtime = _LuaRuntime
            _resolved_lua_runtime_name = "default Lua runtime (PUC Lua)"
            logger.info("Resolved %s", _resolved_lua_runtime_name)
            return _resolved_lua_runtime
        except ImportError:
            pass

    try:
        from lupa.luajit21 import LuaRuntime as _LuaRuntime

        _resolved_lua_runtime = _LuaRuntime
        _resolved_lua_runtime_name = "LuaJIT 2.1 runtime"
    except ImportError:
        try:
            from lupa.luajit20 import LuaRuntime as _LuaRuntime

            _resolved_lua_runtime = _LuaRuntime
            _resolved_lua_runtime_name = "LuaJIT 2.0 runtime"
        except ImportError:
            try:
                from lupa import LuaRuntime as _LuaRuntime

                _resolved_lua_runtime = _LuaRuntime
                _resolved_lua_runtime_name = "default Lua runtime (PUC Lua)"
            except ImportError:
                logger.warning("lupa not installed, Lua patterns unavailable")
                return None

    logger.info("Resolved %s", _resolved_lua_runtime_name)
    return _resolved_lua_runtime


class LuaPattern(VisualizationPattern):
    """Pattern implementation that runs a Lua script."""

    def __init__(self, pattern_key: str, config: PatternConfig = None):
        super().__init__(config)
        self._pattern_key = pattern_key
        self._lua = None
        self._calculate = None
        self._calculate_orig = None  # Original calculate (for optional field reads)
        self._flat_mode = None  # "flat", "dual", or None (disabled)
        self._last_call_time = None
        self._audio_table = None
        self._bands_table = None
        self._config_table = None
        self._reset_hook = None
        self._consecutive_timeouts = 0
        self._entity_state = {}
        self._position_deadband = 0.0015
        self._load_lua(pattern_key)

    def seed_entity_state(self, state: dict):
        """Seed entity positions from another pattern's state for smooth transitions."""
        self._entity_state = {k: dict(v) for k, v in state.items()}

    def _load_lua(self, pattern_key: str):
        LuaRuntime = _resolve_lua_runtime()
        if LuaRuntime is None:
            return

        self._lua = LuaRuntime(unpack_returned_tuples=True)

        # Install instruction-count hook BEFORE sandbox removes debug.
        # This prevents infinite-loop patterns from blocking the event loop.
        # Uses do-end block so count/limit are upvalues, not globals —
        # patterns cannot tamper with them even before debug is removed.
        self._lua.execute(f"""
            do
                local _count = 0
                local _limit = {LUA_INSTRUCTION_LIMIT}
                debug.sethook(function()
                    _count = _count + 1
                    if _count > _limit then
                        error("pattern exceeded instruction limit")
                    end
                end, "", {LUA_HOOK_INTERVAL})
                function __reset_hook()
                    _count = 0
                end
            end
        """)
        self._reset_hook = self._lua.globals()["__reset_hook"]

        # Sandbox: remove dangerous globals before loading any pattern code
        self._lua.execute("""
            os = nil; io = nil; debug = nil; package = nil
            require = nil; load = nil; loadfile = nil; dofile = nil
            collectgarbage = nil; rawget = nil; rawset = nil
            pcall = nil; xpcall = nil; rawequal = nil; rawlen = nil
            string.dump = nil; string.rep = nil
        """)

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

            # Inject flat_pack wrapper for fast Lua→Python bridge transfer
            if _USE_FLAT_PACK and pattern_text is not None:
                has_optional = any(
                    kw in pattern_text for kw in ("glow", "brightness", "material", "interpolation")
                )
                self._calculate_orig = self._calculate
                if has_optional:
                    # Dual mode: return flat array + original tables for optional fields
                    self._lua.execute("""
                        local _orig = calculate
                        function _flat_wrapper(audio, config, dt)
                            local result = _orig(audio, config, dt)
                            if not result then return nil, nil, 0 end
                            local flat, count = flat_pack(result)
                            return flat, result, count
                        end
                    """)
                    self._calculate = self._lua.globals()["_flat_wrapper"]
                    self._flat_mode = "dual"
                else:
                    # Flat-only mode: no optional fields to read
                    self._lua.execute("""
                        local _orig = calculate
                        function _flat_wrapper(audio, config, dt)
                            local result = _orig(audio, config, dt)
                            if not result then return nil, 0 end
                            local flat, count = flat_pack(result)
                            return flat, count
                        end
                    """)
                    self._calculate = self._lua.globals()["_flat_wrapper"]
                    self._flat_mode = "flat"
                logger.debug("Pattern %s: flat_pack mode=%s", pattern_key, self._flat_mode)
        else:
            logger.warning(f"Pattern file not found: {pattern_key}.lua")

    def _smooth_entity(
        self, entity_id, target_x, target_y, target_z, target_scale, target_rotation, dt
    ):
        """Apply dt-aware smoothing to entity position/scale/rotation.

        Returns (x, y, z, scale, rotation).
        """
        prev = self._entity_state.get(entity_id)

        if prev is None:
            x, y, z = target_x, target_y, target_z
            scale = target_scale
            rotation = target_rotation % 360.0
        else:
            dt_ratio = dt / 0.016

            # Position: fast for larger moves, gentler for tiny changes
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
            deadband = self._position_deadband
            if abs(x - prev["x"]) < deadband:
                x = prev["x"]
            if abs(y - prev["y"]) < deadband:
                y = prev["y"]
            if abs(z - prev["z"]) < deadband:
                z = prev["z"]

            # Scale: faster attack, slower release
            base_scale_alpha = 0.84 if target_scale > prev["scale"] else 0.56
            scale_alpha = 1.0 - (1.0 - base_scale_alpha) ** dt_ratio
            scale = prev["scale"] + (target_scale - prev["scale"]) * scale_alpha

            # Rotation: shortest-path angle smoothing
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
        return x, y, z, scale, rotation

    def _unpack_flat(self, flat, entity_count, dt, table_result):
        """Unpack flat numeric array into entity dicts with smoothing."""
        entities = []
        stride = 7
        for i in range(entity_count):
            offset = i * stride
            entity_id = f"block_{i}"
            target_x = float(flat[offset + 1])  # Lua 1-indexed
            target_y = float(flat[offset + 2])
            target_z = float(flat[offset + 3])
            target_scale = float(flat[offset + 4])
            target_rotation = float(flat[offset + 5])
            band = int(flat[offset + 6])
            visible = flat[offset + 7] != 0

            x, y, z, scale, rotation = self._smooth_entity(
                entity_id,
                target_x,
                target_y,
                target_z,
                target_scale,
                target_rotation,
                dt,
            )

            entity = {
                "id": entity_id,
                "x": x,
                "y": y,
                "z": z,
                "scale": scale,
                "rotation": rotation,
                "band": band,
                "visible": visible,
            }

            # Read optional fields from original table (dual mode only)
            if table_result is not None:
                entry = table_result[i + 1]  # Lua 1-indexed
                if entry is not None:
                    if entry["glow"] is not None:
                        entity["glow"] = bool(entry["glow"])
                    if entry["brightness"] is not None:
                        entity["brightness"] = int(entry["brightness"])
                    if entry["material"] is not None:
                        entity["material"] = str(entry["material"])
                    if entry["interpolation"] is not None:
                        entity["interpolation"] = int(entry["interpolation"])

            entities.append(entity)
        return entities

    def _unpack_tables(self, result, dt):
        """Legacy path: unpack Lua table-of-tables into entity dicts with smoothing."""
        entities = []
        seen_ids = set()
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

                x, y, z, scale, rotation = self._smooth_entity(
                    entity_id,
                    target_x,
                    target_y,
                    target_z,
                    target_scale,
                    target_rotation,
                    dt,
                )

                entity = {
                    "id": entity_id,
                    "x": x,
                    "y": y,
                    "z": z,
                    "scale": scale,
                    "rotation": rotation,
                    "band": int(entry["band"] or 0),
                    "visible": bool(entry["visible"]) if entry["visible"] is not None else True,
                }
                if entry["glow"] is not None:
                    entity["glow"] = bool(entry["glow"])
                if entry["brightness"] is not None:
                    entity["brightness"] = int(entry["brightness"])
                if entry["material"] is not None:
                    entity["material"] = str(entry["material"])
                if entry["interpolation"] is not None:
                    entity["interpolation"] = int(entry["interpolation"])
                entities.append(entity)
        return entities, seen_ids

    def set_dj_palette(
        self,
        color_palette: Optional[List[str]],
        block_palette: Optional[List[str]],
    ) -> None:
        """Update the DJ palette fields in the Lua config table."""
        if self._config_table is None:
            return
        if color_palette:
            self._config_table["dj_colors"] = self._lua.table_from(color_palette)
        else:
            self._config_table["dj_colors"] = None
        if block_palette:
            self._config_table["dj_blocks"] = self._lua.table_from(block_palette)
        else:
            self._config_table["dj_blocks"] = None

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

            # Reset instruction counter before each Lua call
            if self._reset_hook is not None:
                self._reset_hook()

            result = self._calculate(audio_table, config_table, dt)

            # Convert Lua result to Python list of dicts
            entities = []
            seen_ids = set()

            if self._flat_mode == "flat" and result is not None:
                # Fast path: flat array (7 values per entity), no optional fields
                flat_result, entity_count = result, None
                # unpack_returned_tuples=True means multi-return becomes a tuple
                if isinstance(result, tuple):
                    flat_result, entity_count = result
                if flat_result is not None:
                    entity_count = int(entity_count or 0)
                    entities = self._unpack_flat(flat_result, entity_count, dt, None)
                    seen_ids = {e["id"] for e in entities}

            elif self._flat_mode == "dual" and result is not None:
                # Dual path: flat array + original tables for optional field reads
                flat_result, table_result, entity_count = None, None, 0
                if isinstance(result, tuple):
                    flat_result, table_result, entity_count = result
                if flat_result is not None:
                    entity_count = int(entity_count or 0)
                    entities = self._unpack_flat(flat_result, entity_count, dt, table_result)
                    seen_ids = {e["id"] for e in entities}

            elif result is not None:
                # Legacy path: table-of-tables (no flat_pack)
                entities, seen_ids = self._unpack_tables(result, dt)
            # Enforce a strict entity budget for all patterns.
            target_count = max(0, int(self.config.entity_count))
            if len(entities) > target_count:
                entities = entities[:target_count]
            elif len(entities) < target_count:
                # Use stable padding IDs (__pad_0, __pad_1, ...) so they don't
                # accumulate as stale keys in _entity_state across frames.
                pad_seq = 0
                while len(entities) < target_count:
                    pad_id = f"__pad_{pad_seq}"
                    # Skip if a Lua-returned entity already claimed this ID
                    while pad_id in seen_ids:
                        pad_seq += 1
                        pad_id = f"__pad_{pad_seq}"
                    pad_seq += 1
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
            self._consecutive_timeouts = 0
            return entities
        except Exception as e:
            is_timeout = "instruction limit" in str(e)
            if is_timeout:
                self._consecutive_timeouts += 1
                logger.warning(
                    "Lua pattern timeout (%s): %d/%d consecutive",
                    self._pattern_key,
                    self._consecutive_timeouts,
                    MAX_CONSECUTIVE_TIMEOUTS,
                )
                if self._consecutive_timeouts >= MAX_CONSECUTIVE_TIMEOUTS:
                    logger.error(
                        "Lua pattern auto-disabled (%s): exceeded instruction limit %d consecutive times",
                        self._pattern_key,
                        MAX_CONSECUTIVE_TIMEOUTS,
                    )
                    self._calculate = None
            else:
                logger.error("Lua pattern error (%s): %s", self._pattern_key, e)
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


def _extract_lua_string(text: str, varname: str) -> Optional[str]:
    """Extract a top-level Lua string assignment like: name = "Foo Bar" """
    import re

    # Match: varname = "value" or varname = 'value' (top-level, not inside functions)
    pattern = rf'^{varname}\s*=\s*["\'](.+?)["\']'
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else None


def _extract_lua_number(text: str, varname: str) -> Optional[int]:
    """Extract a top-level Lua number assignment like: recommended_entities = 64"""
    import re

    pattern = rf"^{varname}\s*=\s*(\d+)"
    match = re.search(pattern, text, re.MULTILINE)
    return int(match.group(1)) if match else None


def _extract_lua_bool(text: str, varname: str) -> Optional[bool]:
    """Extract a top-level Lua boolean assignment like: static_camera = true"""
    import re

    pattern = rf"^{varname}\s*=\s*(true|false)"
    match = re.search(pattern, text, re.MULTILINE)
    if match:
        return match.group(1) == "true"
    return None


def _build_pattern_list() -> List[Dict[str, Any]]:
    """Scan patterns/*.lua and build pattern metadata list.

    Uses lightweight regex extraction instead of creating Lua VMs,
    making startup ~100x faster (string parsing vs N Lua VM creations).
    """
    result = []
    if not _PATTERNS_DIR.is_dir():
        return result
    for lua_file in sorted(_PATTERNS_DIR.glob("*.lua")):
        if lua_file.name == "lib.lua":
            continue
        key = lua_file.stem
        try:
            text = _file_cache.get(key)
            if text is None:
                text = lua_file.read_text(encoding="utf-8")

            name = _extract_lua_string(text, "name") or key.replace("_", " ").title()
            description = _extract_lua_string(text, "description") or ""
            category = _extract_lua_string(text, "category") or ""
            static_camera = _extract_lua_bool(text, "static_camera") or False
            rec = _extract_lua_number(text, "recommended_entities")
            if rec is None:
                rec = _extract_lua_number(text, "start_blocks")
            recommended_entities = max(1, min(256, rec)) if rec else 64

            result.append(
                {
                    "id": key,
                    "name": name,
                    "description": description,
                    "category": category,
                    "static_camera": static_camera,
                    "recommended_entities": recommended_entities,
                }
            )
        except Exception as e:
            logger.warning(f"Failed to parse pattern metadata for '{key}': {e}")
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
    """Get recommended block count for a pattern from cached metadata.

    Uses the pre-built pattern list instead of creating a throwaway Lua VM.
    """
    key = name.lower()
    patterns = list_patterns()
    for pat in patterns:
        if pat["id"] == key:
            rec = pat.get("recommended_entities")
            if rec is not None:
                return max(1, min(256, int(rec)))
            break
    return max(1, min(256, int(fallback)))


def list_patterns() -> List[Dict[str, Any]]:
    """List all available patterns. Returns cached list (refreshed on hot-reload)."""
    global _cached_patterns
    if _cached_patterns is None:
        refresh_pattern_cache()
    return _cached_patterns
