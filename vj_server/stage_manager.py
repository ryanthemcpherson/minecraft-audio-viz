"""Pattern engine, zone management, transitions, effects, and scene persistence."""

from __future__ import annotations

import asyncio
import json
import logging
import math
import re
import time
from dataclasses import replace
from pathlib import Path
from typing import TYPE_CHECKING, Dict, List, Optional

from vj_server.config import PRESETS as AUDIO_PRESETS
from vj_server.models import (
    _USE_ASYNC_LUA,
    ZonePatternState,
    _json_str,
)
from vj_server.patterns import (
    AudioState,
    PatternConfig,
    _lua_pattern_exists,
    get_pattern,
    get_recommended_entity_count,
    list_patterns,
    refresh_pattern_cache,
)

if TYPE_CHECKING:
    pass

logger = logging.getLogger("vj_server")


class StageManagerMixin:
    """Mixin providing pattern, zone, transition, effect, and scene methods.

    Mixed into VJServer -- all methods access shared state via self.
    """

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

    # --- Scenes ---

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
            self._current_preset_name = preset_name
            config = AUDIO_PRESETS[preset_name]
            await self._broadcast_preset_to_djs(config.to_dict(), preset_name)

    # --- Pattern scripts ---

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

    # --- Bitmap & Zone Rendering ---

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
                if explicit_count is not None:
                    target_count = explicit_count
                elif zs.pattern_name:
                    target_count = zs.entity_count  # Keep current count
                else:
                    target_count = get_recommended_entity_count(str(pattern_name), zs.entity_count)
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

    # --- Hot Reload ---

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

    # --- Pattern Engine ---

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
        if explicit_entity_count is not None:
            target_count = max(1, int(explicit_entity_count))
        elif zone_state.pattern_name:
            # Zone already has a pattern — keep current entity count to avoid
            # constant resize churn when auto-rotating between patterns with
            # different recommended_entities values.
            target_count = old_count
        else:
            # First pattern assignment — use the pattern's recommended count.
            target_count = get_recommended_entity_count(pattern_name, old_count)
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

    async def _relay_bitmap_frame(self, data: dict):
        """Relay bitmap_frame from Minecraft to browser clients for 1:1 preview."""
        await self._broadcast_to_browsers(_json_str(data))

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
