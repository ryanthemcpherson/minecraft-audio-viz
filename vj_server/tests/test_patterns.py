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

    def test_lua_os_module_accessible(self):
        """Verify Lua runtime state: os module is currently available (not sandboxed).

        NOTE: If a sandbox is added later that restricts os.execute,
        update this test to assert it's None instead.
        """
        pat = LuaPattern("spectrum")
        lua = pat._lua
        if lua is None:
            pytest.skip("lupa not installed")
        lua.execute("_test_os_avail = (os ~= nil)")
        result = lua.globals()["_test_os_avail"]
        assert result is True, "os module should be available in the Lua runtime"


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
