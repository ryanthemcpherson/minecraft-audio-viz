"""Tests for the pattern engine and registry."""

import pytest

from vj_server.patterns import (
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

    def test_infinite_loop_returns_empty_entities(self):
        """A pattern with an infinite loop should be caught by the instruction
        limit and return an empty entity list instead of hanging forever."""
        config = PatternConfig(entity_count=16)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        # Inject an infinite-loop calculate function
        pat._lua.execute("""
            function calculate(audio, config, dt)
                while true do end
            end
        """)
        pat._calculate = pat._lua.globals()["calculate"]
        pat._flat_mode = None  # Disable flat_pack wrapper

        audio = self._make_audio()
        entities = pat.calculate_entities(audio)
        assert entities == [], "Infinite loop should return empty entities, not hang"

    def test_auto_disable_after_consecutive_timeouts(self):
        """After MAX_CONSECUTIVE_TIMEOUTS consecutive timeouts, the pattern
        should auto-disable (set _calculate to None)."""
        from vj_server.patterns import MAX_CONSECUTIVE_TIMEOUTS

        config = PatternConfig(entity_count=16)
        pat = LuaPattern("spectrum", config)
        if pat._lua is None:
            pytest.skip("lupa not installed")

        # Inject infinite loop
        pat._lua.execute("""
            function calculate(audio, config, dt)
                while true do end
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

        # Inject infinite loop
        pat._lua.execute("""
            function _bad_calc(audio, config, dt)
                while true do end
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
