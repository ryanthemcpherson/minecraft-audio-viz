"""Tests for the pattern engine and registry."""

import os
import subprocess
import sys
from pathlib import Path

import pytest

import vj_server.patterns as patterns_module
from vj_server.patterns import (
    LUA_MEMORY_LIMIT_BYTES,
    AudioState,
    LuaPattern,
    PatternConfig,
    list_patterns,
)

# ============================================================================
# list_patterns
# ============================================================================


class TestListPatterns:
    def test_returns_non_empty_list(self):
        patterns = list_patterns()
        assert len(patterns) > 0

    def test_each_entry_has_required_keys(self):
        patterns = list_patterns()
        for pat in patterns:
            assert "id" in pat
            assert "name" in pat
            assert "description" in pat
            assert "recommended_entities" in pat

    def test_lib_lua_not_listed(self):
        """lib.lua is a helper, not a pattern — should be excluded."""
        patterns = list_patterns()
        ids = [p["id"] for p in patterns]
        assert "lib" not in ids

    def test_spectrum_pattern_present(self):
        patterns = list_patterns()
        ids = [p["id"] for p in patterns]
        assert "spectrum" in ids


# ============================================================================
# LuaPattern loading and calculation
# ============================================================================


class TestLuaPattern:
    def _make_audio(self, **overrides) -> AudioState:
        defaults = dict(
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            amplitude=0.5,
            is_beat=False,
            beat_intensity=0.0,
            frame=1,
            bpm=128.0,
            beat_phase=0.0,
        )
        defaults.update(overrides)
        return AudioState(**defaults)

    def test_load_spectrum_pattern(self):
        pat = LuaPattern("spectrum", PatternConfig(entity_count=16))
        assert pat.name  # Should have read name from Lua globals
        assert pat._calculate is not None

    def test_calculate_returns_entities(self):
        config = PatternConfig(entity_count=16)
        pat = LuaPattern("spectrum", config)
        audio = self._make_audio()
        entities = pat.calculate_entities(audio)
        assert isinstance(entities, list)
        assert len(entities) == 16

    def test_entity_has_required_fields(self):
        config = PatternConfig(entity_count=8)
        pat = LuaPattern("spectrum", config)
        audio = self._make_audio()
        entities = pat.calculate_entities(audio)
        assert len(entities) > 0
        entity = entities[0]
        for key in ("id", "x", "y", "z", "scale", "rotation", "band", "visible"):
            assert key in entity, f"Missing key: {key}"

    def test_coordinates_are_numeric(self):
        config = PatternConfig(entity_count=8)
        pat = LuaPattern("spectrum", config)
        audio = self._make_audio()
        entities = pat.calculate_entities(audio)
        for entity in entities:
            if entity.get("visible", True):
                assert isinstance(entity["x"], (int, float))
                assert isinstance(entity["y"], (int, float))
                assert isinstance(entity["z"], (int, float))

    def test_nonexistent_pattern_falls_back(self):
        """Loading a non-existent pattern key should log a warning, not crash."""
        pat = LuaPattern("definitely_not_a_real_pattern_xyz", PatternConfig(entity_count=4))
        audio = self._make_audio()
        entities = pat.calculate_entities(audio)
        # With no calculate function, should return empty list
        assert isinstance(entities, list)

    def test_lua_os_module_sandboxed(self):
        """Verify sandbox removes the Lua ``os`` module."""
        pat = LuaPattern("spectrum")
        lua = pat._lua
        if lua is None:
            pytest.skip("lupa not installed")
        lua.execute("_test_os_avail = (os ~= nil)")
        result = lua.globals()["_test_os_avail"]
        assert result is False, "os module should be removed from the Lua runtime"

    @pytest.mark.parametrize(
        "global_name",
        ("jit", "python", "coroutine", "loadstring", "getfenv", "setfenv", "__reset_hook"),
    )
    def test_dangerous_runtime_controls_sandboxed(self, global_name: str):
        """Patterns cannot recover host access or disable timeout enforcement."""
        pat = LuaPattern("spectrum")
        lua = pat._lua
        if lua is None:
            pytest.skip("lupa not installed")
        lua.execute(f"_test_global_avail = ({global_name} ~= nil)")
        result = lua.globals()["_test_global_avail"]
        assert result is False, f"{global_name} should be removed from the Lua runtime"


class TestPatternContainment:
    """Pattern IDs and files must come from canonical safe discovery."""

    @staticmethod
    def _write_pattern(path: Path, name: str = "Test") -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            f'name = "{name}"\nfunction calculate(audio, config, dt) return {{}} end\n',
            encoding="utf-8",
        )

    def _use_pattern_root(self, monkeypatch, patterns_dir: Path) -> None:
        monkeypatch.setattr(patterns_module, "_PATTERNS_DIR", patterns_dir)
        monkeypatch.setattr(patterns_module, "_file_cache", {})
        monkeypatch.setattr(patterns_module, "_lib_cache", None)
        monkeypatch.setattr(patterns_module, "_cached_patterns", None)
        patterns_module.refresh_pattern_cache()

    def test_registry_rejects_traversal_absolute_backslash_and_symlink_ids(
        self, monkeypatch, tmp_path
    ):
        patterns_dir = tmp_path / "patterns"
        patterns_dir.mkdir()
        safe_path = patterns_dir / "safe.lua"
        outside_path = tmp_path / "outside.lua"
        symlink_path = patterns_dir / "linked.lua"
        self._write_pattern(safe_path, "Safe")
        self._write_pattern(outside_path, "Outside")
        symlink_path.symlink_to(outside_path)

        if os.name == "nt":
            backslash_path = patterns_dir / "nested" / "escaped.lua"
            backslash_key = r"nested\escaped"
        else:
            backslash_path = patterns_dir / r"nested\escaped.lua"
            backslash_key = r"nested\escaped"
        self._write_pattern(backslash_path, "Escaped")

        self._use_pattern_root(monkeypatch, patterns_dir)

        assert patterns_module._lua_pattern_exists("safe")
        assert not patterns_module._lua_pattern_exists("../outside")
        assert not patterns_module._lua_pattern_exists(str(outside_path.with_suffix("")))
        assert not patterns_module._lua_pattern_exists(backslash_key)
        assert not patterns_module._lua_pattern_exists("linked")
        assert [pattern["id"] for pattern in patterns_module.list_patterns()] == ["safe"]

    def test_lua_pattern_constructor_cannot_bypass_safe_discovery(self, monkeypatch, tmp_path):
        patterns_dir = tmp_path / "patterns"
        patterns_dir.mkdir()
        safe_path = patterns_dir / "safe.lua"
        outside_path = tmp_path / "outside.lua"
        symlink_path = patterns_dir / "linked.lua"
        self._write_pattern(safe_path, "Safe")
        self._write_pattern(outside_path, "Outside")
        symlink_path.symlink_to(outside_path)

        if os.name == "nt":
            backslash_path = patterns_dir / "nested" / "escaped.lua"
            backslash_key = r"nested\escaped"
        else:
            backslash_path = patterns_dir / r"nested\escaped.lua"
            backslash_key = r"nested\escaped"
        self._write_pattern(backslash_path, "Escaped")
        self._use_pattern_root(monkeypatch, patterns_dir)

        unsafe_keys = (
            "../outside",
            str(outside_path.with_suffix("")),
            backslash_key,
            "linked",
        )
        for unsafe_key in unsafe_keys:
            pattern = LuaPattern(unsafe_key, PatternConfig(entity_count=1))
            assert pattern._calculate is None


class TestPatternBridgeBounds:
    """Attacker-controlled Lua tables cannot expand Python bridge work."""

    @staticmethod
    def _make_audio() -> AudioState:
        return AudioState(
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            amplitude=0.5,
            is_beat=False,
            beat_intensity=0.0,
            frame=1,
            bpm=128.0,
            beat_phase=0.0,
        )

    def test_pattern_cannot_replace_trusted_flat_pack_in_subprocess(self):
        probe = r'''
import tempfile
from pathlib import Path

import vj_server.patterns as patterns
from vj_server.patterns import AudioState, LuaPattern, PatternConfig

with tempfile.TemporaryDirectory() as directory:
    pattern_root = Path(directory)
    repository_root = Path.cwd()
    (pattern_root / "lib.lua").write_text(
        (repository_root / "patterns" / "lib.lua").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (pattern_root / "hostile.lua").write_text("""
name = "Hostile"
flat_pack = function(entities)
    local synthetic = setmetatable({}, {
        __index = function(_, index)
            local field = ((index - 1) % 7) + 1
            if field == 7 then return 1 end
            return 0.5
        end,
    })
    return synthetic, 1000000000
end
function calculate(audio, config, dt) return {} end
""", encoding="utf-8")
    patterns._PATTERNS_DIR = pattern_root
    patterns.refresh_pattern_cache()
    pattern = LuaPattern("hostile", PatternConfig(entity_count=1))
    if pattern._lua is None:
        raise SystemExit("memory-limited Lua runtime unavailable")
    audio = AudioState(
        bands=[0.5, 0.4, 0.3, 0.2, 0.1], amplitude=0.5, is_beat=False,
        beat_intensity=0.0, frame=1, bpm=128.0, beat_phase=0.0,
    )
    entities = pattern.calculate_entities(audio)
    if len(entities) != 1 or pattern._consecutive_timeouts != 0:
        raise SystemExit("untrusted flat_pack affected bridge work")
'''
        try:
            completed = subprocess.run(
                [sys.executable, "-c", probe],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
                cwd=Path(__file__).resolve().parents[2],
            )
        except subprocess.TimeoutExpired:
            pytest.fail("untrusted flat_pack expanded Python bridge work")

        assert completed.returncode == 0, completed.stdout + completed.stderr

    def test_metatable_length_cannot_choose_flat_bridge_work(self, monkeypatch, tmp_path):
        pattern_root = tmp_path / "patterns"
        pattern_root.mkdir()
        repository_root = Path(__file__).resolve().parents[2]
        (pattern_root / "lib.lua").write_text(
            (repository_root / "patterns" / "lib.lua").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        (pattern_root / "metatable.lua").write_text(
            """
name = "Metatable"
function calculate(audio, config, dt)
    return setmetatable({}, {
        __len = function() return 1000000000 end,
        __index = function()
            return {x = 0.5, y = 0.5, z = 0.5, scale = 0.2}
        end,
    })
end
""",
            encoding="utf-8",
        )
        monkeypatch.setattr(patterns_module, "_PATTERNS_DIR", pattern_root)
        monkeypatch.setattr(patterns_module, "_file_cache", {})
        monkeypatch.setattr(patterns_module, "_lib_cache", None)
        monkeypatch.setattr(patterns_module, "_cached_patterns", None)
        patterns_module.refresh_pattern_cache()
        pattern = LuaPattern("metatable", PatternConfig(entity_count=1))
        if pattern._lua is None:
            pytest.skip("memory-limited lupa runtime not installed")

        entities = pattern.calculate_entities(self._make_audio())

        assert len(entities) == 1
        assert pattern._consecutive_timeouts == 0

    def test_legacy_table_iteration_is_clamped_before_python_field_access(self):
        pattern = LuaPattern("spectrum", PatternConfig(entity_count=2))
        if pattern._lua is None:
            pytest.skip("memory-limited lupa runtime not installed")

        pattern._lua.execute("""
            _bridge_field_reads = 0
            function _legacy_oversized(audio, config, dt)
                local result = {}
                for i = 1, 100 do
                    result[i] = setmetatable({}, {
                        __index = function(_, key)
                            _bridge_field_reads = _bridge_field_reads + 1
                            if key == "id" then return "block_" .. (i - 1) end
                            if key == "visible" then return true end
                            return 0.5
                        end,
                    })
                end
                return result
            end
        """)
        pattern._calculate = pattern._lua.globals()["_legacy_oversized"]
        pattern._flat_mode = None

        entities = pattern.calculate_entities(self._make_audio())

        assert [entity["id"] for entity in entities] == ["block_0", "block_1"]
        field_reads = pattern._lua.globals()["_bridge_field_reads"]
        assert 0 < field_reads <= 50


# ============================================================================
# Pattern padding logic
# ============================================================================


class TestPatternPadding:
    def _make_audio(self) -> AudioState:
        return AudioState(
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            amplitude=0.5,
            is_beat=False,
            beat_intensity=0.0,
            frame=1,
            bpm=128.0,
            beat_phase=0.0,
        )

    def test_pads_to_entity_count(self):
        """If pattern returns fewer entities than config.entity_count, pad to match."""
        config = PatternConfig(entity_count=100)
        pat = LuaPattern("spectrum", config)
        entities = pat.calculate_entities(self._make_audio())
        assert len(entities) == 100

    def test_padded_entities_are_invisible(self):
        """Padded entities should have visible=False and scale=0."""
        config = PatternConfig(entity_count=100)
        pat = LuaPattern("spectrum", config)
        entities = pat.calculate_entities(self._make_audio())
        # The pattern itself returns some visible entities, extras should be invisible
        invisible = [e for e in entities if not e.get("visible", True)]
        for e in invisible:
            assert e["scale"] == 0.0

    def test_truncates_excess_entities(self):
        """If pattern returns more than entity_count, truncate."""
        config = PatternConfig(entity_count=4)
        pat = LuaPattern("spectrum", config)
        entities = pat.calculate_entities(self._make_audio())
        assert len(entities) == 4

    def test_zero_entity_count(self):
        """entity_count=0 should return empty list."""
        config = PatternConfig(entity_count=0)
        pat = LuaPattern("spectrum", config)
        entities = pat.calculate_entities(self._make_audio())
        assert entities == []


# ============================================================================
# Lua timeout protection
# ============================================================================


class TestLuaTimeout:
    """Tests for instruction-count-based Lua timeout protection."""

    def _make_audio(self) -> AudioState:
        return AudioState(
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            amplitude=0.5,
            is_beat=False,
            beat_intensity=0.0,
            frame=1,
            bpm=128.0,
            beat_phase=0.0,
        )

    def test_over_budget_loop_returns_empty_entities(self):
        """A pattern that exceeds its instruction budget should return no entities."""
        config = PatternConfig(entity_count=16)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        # Exceed the instruction budget with a finite loop so a broken hook
        # fails the assertion instead of hanging the entire test process.
        pat._lua.execute("""
            function calculate(audio, config, dt)
                for _ = 1, 10000000 do end
            end
        """)
        pat._calculate = pat._lua.globals()["calculate"]
        pat._flat_mode = None  # Disable flat_pack wrapper

        audio = self._make_audio()
        entities = pat.calculate_entities(audio)
        assert entities == [], "Over-budget loop should return empty entities"

    def test_overridden_error_cannot_disable_timeout(self):
        """The timeout hook must not resolve its error function through pattern globals."""
        config = PatternConfig(entity_count=16)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        pat._lua.execute("""
            error = function() end
            function calculate(audio, config, dt)
                for _ = 1, 10000000 do end
            end
        """)
        pat._calculate = pat._lua.globals()["calculate"]
        pat._flat_mode = None

        assert pat.calculate_entities(self._make_audio()) == []

    def test_auto_disable_after_consecutive_timeouts(self):
        """After MAX_CONSECUTIVE_TIMEOUTS consecutive timeouts, the pattern
        should auto-disable (set _calculate to None)."""
        from vj_server.patterns import MAX_CONSECUTIVE_TIMEOUTS

        config = PatternConfig(entity_count=16)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        # Use a finite over-budget loop to keep hook regressions bounded.
        pat._lua.execute("""
            function calculate(audio, config, dt)
                for _ = 1, 10000000 do end
            end
        """)
        pat._calculate = pat._lua.globals()["calculate"]
        pat._flat_mode = None

        audio = self._make_audio()

        # Each call should return empty (timeout caught)
        for i in range(MAX_CONSECUTIVE_TIMEOUTS):
            entities = pat.calculate_entities(audio)
            assert entities == [], f"Call {i + 1} should return empty"

        # After MAX_CONSECUTIVE_TIMEOUTS, pattern should be disabled
        assert pat._calculate is None, "Pattern should be auto-disabled"

    def test_successful_call_resets_timeout_counter(self):
        """A successful calculate() should reset the consecutive timeout counter."""
        config = PatternConfig(entity_count=8)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        audio = self._make_audio()

        # Normal call should work
        entities = pat.calculate_entities(audio)
        assert len(entities) == 8

        # Verify internal counter is 0 (no timeouts)
        assert pat._consecutive_timeouts == 0

    def test_true_infinite_loop_is_interrupted_in_subprocess(self):
        """The supported memory-limited runtime must interrupt a true infinite loop."""
        probe = """
from vj_server.patterns import AudioState, LuaPattern, PatternConfig

pattern = LuaPattern("spectrum", PatternConfig(entity_count=1))
if pattern._lua is None:
    raise SystemExit("memory-limited Lua runtime unavailable")
pattern._lua.execute("function calculate(audio, config, dt) while true do end end")
pattern._calculate = pattern._lua.globals()["calculate"]
pattern._flat_mode = None
audio = AudioState(
    bands=[0.5, 0.4, 0.3, 0.2, 0.1],
    amplitude=0.5,
    is_beat=False,
    beat_intensity=0.0,
    frame=1,
    bpm=128.0,
    beat_phase=0.0,
)
if pattern.calculate_entities(audio) != []:
    raise SystemExit("infinite loop did not produce a timeout")
"""
        try:
            completed = subprocess.run(
                [sys.executable, "-c", probe],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
                cwd=Path(__file__).resolve().parents[2],
            )
        except subprocess.TimeoutExpired:
            pytest.fail("Lua runtime did not interrupt the infinite loop")

        assert completed.returncode == 0, completed.stdout + completed.stderr

    def test_runtime_enforces_memory_limit(self):
        pat = LuaPattern("spectrum", PatternConfig(entity_count=0))
        if pat._lua is None:
            pytest.skip("memory-limited lupa runtime not installed")

        assert pat._lua.get_max_memory() == LUA_MEMORY_LIMIT_BYTES

    def test_cumulative_allocation_disables_and_releases_runtime(self, monkeypatch):
        """Sub-budget frame allocations cannot grow the process without bound."""
        monkeypatch.setattr(patterns_module, "LUA_MEMORY_LIMIT_BYTES", 2 * 1024 * 1024)
        pat = LuaPattern("spectrum", PatternConfig(entity_count=0))
        if pat._lua is None:
            pytest.skip("memory-limited lupa runtime not installed")

        pat._lua.execute("""
            retained_chunks = {}
            function calculate(audio, config, dt)
                local chunk = {}
                for i = 1, 20000 do
                    chunk[i] = i
                end
                retained_chunks[#retained_chunks + 1] = chunk
                return {}
            end
        """)
        pat._calculate = pat._lua.globals()["calculate"]
        pat._flat_mode = None

        for _ in range(20):
            pat.calculate_entities(self._make_audio())
            if pat._calculate is None:
                break

        assert pat._calculate is None
        assert pat._lua is None

    def test_success_between_timeouts_resets_counter(self):
        """A successful call between timeouts should reset the counter,
        preventing auto-disable from accumulating across non-consecutive failures."""
        from vj_server.patterns import MAX_CONSECUTIVE_TIMEOUTS

        config = PatternConfig(entity_count=8)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        audio = self._make_audio()
        good_calculate = pat._calculate
        good_flat_mode = pat._flat_mode

        # Use a finite over-budget loop to keep hook regressions bounded.
        pat._lua.execute("""
            function _bad_calc(audio, config, dt)
                for _ = 1, 10000000 do end
            end
        """)
        bad_calculate = pat._lua.globals()["_bad_calc"]

        # Timeout twice (just under the limit)
        pat._calculate = bad_calculate
        pat._flat_mode = None
        for _ in range(MAX_CONSECUTIVE_TIMEOUTS - 1):
            pat.calculate_entities(audio)

        assert pat._consecutive_timeouts == MAX_CONSECUTIVE_TIMEOUTS - 1

        # One successful call should reset the counter
        pat._calculate = good_calculate
        pat._flat_mode = good_flat_mode
        entities = pat.calculate_entities(audio)
        assert len(entities) == 8
        assert pat._consecutive_timeouts == 0

        # Now timeout again — should NOT disable (counter was reset)
        pat._calculate = bad_calculate
        pat._flat_mode = None
        pat.calculate_entities(audio)
        assert pat._calculate is not None, "Should not be disabled after non-consecutive timeouts"
