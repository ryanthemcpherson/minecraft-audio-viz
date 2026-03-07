"""Tests for StageManagerMixin — pure math/logic functions."""

import time

from vj_server.stage_manager import StageManagerMixin


class FakeStageManager(StageManagerMixin):
    """Minimal stub that exposes stage manager methods for testing."""

    def __init__(self):
        self._active_effects = {}


# ============================================================================
# _smoothstep
# ============================================================================


class TestSmoothstep:
    def setup_method(self):
        self.mgr = FakeStageManager()

    def test_zero(self):
        assert self.mgr._smoothstep(0.0) == 0.0

    def test_one(self):
        assert self.mgr._smoothstep(1.0) == 1.0

    def test_half(self):
        assert self.mgr._smoothstep(0.5) == 0.5

    def test_clamped_below(self):
        assert self.mgr._smoothstep(-0.5) == 0.0

    def test_clamped_above(self):
        assert self.mgr._smoothstep(1.5) == 1.0

    def test_monotonic(self):
        """Smoothstep should be monotonically increasing in [0, 1]."""
        prev = 0.0
        for i in range(1, 101):
            t = i / 100.0
            val = self.mgr._smoothstep(t)
            assert val >= prev
            prev = val


# ============================================================================
# _lerp
# ============================================================================


class TestLerp:
    def setup_method(self):
        self.mgr = FakeStageManager()

    def test_at_zero(self):
        assert self.mgr._lerp(10.0, 20.0, 0.0) == 10.0

    def test_at_one(self):
        assert self.mgr._lerp(10.0, 20.0, 1.0) == 20.0

    def test_at_half(self):
        assert self.mgr._lerp(0.0, 100.0, 0.5) == 50.0

    def test_negative_values(self):
        assert self.mgr._lerp(-10.0, 10.0, 0.5) == 0.0


# ============================================================================
# _lerp_angle
# ============================================================================


class TestLerpAngle:
    def test_same_angle(self):
        assert StageManagerMixin._lerp_angle(90.0, 90.0, 0.5) == 90.0

    def test_shortest_path_forward(self):
        result = StageManagerMixin._lerp_angle(10.0, 20.0, 0.5)
        assert abs(result - 15.0) < 0.01

    def test_shortest_path_wraps(self):
        """350 -> 10 should go through 0, not the long way."""
        result = StageManagerMixin._lerp_angle(350.0, 10.0, 0.5)
        assert abs(result - 0.0) < 0.01

    def test_at_zero(self):
        result = StageManagerMixin._lerp_angle(90.0, 270.0, 0.0)
        assert abs(result - 90.0) < 0.01

    def test_at_one(self):
        result = StageManagerMixin._lerp_angle(90.0, 270.0, 1.0)
        assert abs(result - 270.0) < 0.01


# ============================================================================
# _get_sibling_id
# ============================================================================


class TestGetSiblingId:
    def test_basic_wrapping(self):
        assert StageManagerMixin._get_sibling_id("block_5", 4) == "block_1"

    def test_within_range(self):
        assert StageManagerMixin._get_sibling_id("block_2", 10) == "block_2"

    def test_zero_sibling_count(self):
        assert StageManagerMixin._get_sibling_id("block_0", 0) is None

    def test_non_block_id(self):
        assert StageManagerMixin._get_sibling_id("other_0", 5) is None

    def test_invalid_index(self):
        assert StageManagerMixin._get_sibling_id("block_abc", 5) is None


# ============================================================================
# _blend_entities
# ============================================================================


class TestBlendEntities:
    def setup_method(self):
        self.mgr = FakeStageManager()

    def test_alpha_zero_returns_old(self):
        old = [{"id": "block_0", "x": 0.0, "y": 0.0, "z": 0.0, "scale": 0.5, "rotation": 0.0}]
        new = [{"id": "block_0", "x": 1.0, "y": 1.0, "z": 1.0, "scale": 1.0, "rotation": 180.0}]
        result = self.mgr._blend_entities(old, new, 0.0)
        assert len(result) == 1
        assert result[0]["x"] == 0.0
        assert result[0]["scale"] == 0.5

    def test_alpha_one_returns_new(self):
        old = [{"id": "block_0", "x": 0.0, "y": 0.0, "z": 0.0, "scale": 0.5, "rotation": 0.0}]
        new = [{"id": "block_0", "x": 1.0, "y": 1.0, "z": 1.0, "scale": 1.0, "rotation": 180.0}]
        result = self.mgr._blend_entities(old, new, 1.0)
        assert len(result) == 1
        assert result[0]["x"] == 1.0
        assert result[0]["scale"] == 1.0

    def test_alpha_half_blends(self):
        old = [{"id": "block_0", "x": 0.0, "scale": 0.0}]
        new = [{"id": "block_0", "x": 1.0, "scale": 1.0}]
        result = self.mgr._blend_entities(old, new, 0.5)
        assert abs(result[0]["x"] - 0.5) < 0.01
        assert abs(result[0]["scale"] - 0.5) < 0.01

    def test_new_only_entity_fades_in(self):
        """Entity only in new pattern should emerge (scale from 0)."""
        old = []
        new = [{"id": "block_0", "x": 0.5, "scale": 0.5}]
        result = self.mgr._blend_entities(old, new, 0.5)
        assert len(result) == 1
        # Scale should be reduced by alpha since fading in
        assert result[0]["scale"] < 0.5

    def test_old_only_entity_fades_out(self):
        """Entity only in old pattern should collapse."""
        old = [{"id": "block_0", "x": 0.5, "scale": 0.5}]
        new = []
        result = self.mgr._blend_entities(old, new, 0.5)
        assert len(result) == 1
        # Scale should be reduced since fading out
        assert result[0]["scale"] < 0.5


# ============================================================================
# _apply_effects
# ============================================================================


class TestApplyEffects:
    def setup_method(self):
        self.mgr = FakeStageManager()

    def test_no_effects_returns_original(self):
        entities = [{"id": "block_0", "x": 0.5, "y": 0.5, "scale": 0.5}]
        result = self.mgr._apply_effects(entities, [0.5] * 5)
        assert result[0]["x"] == 0.5

    def test_flash_increases_scale(self):
        self.mgr._active_effects = {
            "flash": {"intensity": 1.0, "start_time": time.time(), "duration": 1000}
        }
        entities = [{"id": "block_0", "scale": 0.3, "y": 0.2}]
        result = self.mgr._apply_effects(entities, [0.5] * 5)
        assert result[0]["scale"] > 0.3

    def test_strobe_alternates(self):
        self.mgr._active_effects = {
            "strobe": {"intensity": 1.0, "start_time": time.time(), "duration": 2000}
        }
        entities = [{"id": "block_0", "scale": 0.5}]
        result = self.mgr._apply_effects(entities, [0.5] * 5)
        # Strobe either shows full or near-zero scale
        assert result[0]["scale"] in (0.5, 0.01)

    def test_effects_dont_mutate_original(self):
        self.mgr._active_effects = {
            "flash": {"intensity": 1.0, "start_time": time.time(), "duration": 1000}
        }
        original = [{"id": "block_0", "scale": 0.3, "y": 0.2}]
        self.mgr._apply_effects(original, [0.5] * 5)
        assert original[0]["scale"] == 0.3  # Original unchanged

    def test_wave_modifies_y(self):
        self.mgr._active_effects = {
            "wave": {"intensity": 1.0, "start_time": time.time(), "duration": 2000}
        }
        entities = [{"id": f"block_{i}", "y": 0.5} for i in range(8)]
        result = self.mgr._apply_effects(entities, [0.5] * 5)
        # At least some entities should have modified y
        y_values = [e["y"] for e in result]
        assert not all(y == 0.5 for y in y_values)

    def test_blackout_and_freeze_skipped(self):
        """Blackout and freeze are handled in main loop, not _apply_effects."""
        self.mgr._active_effects = {
            "blackout": {"intensity": 1.0, "start_time": time.time(), "duration": 1000}
        }
        entities = [{"id": "block_0", "scale": 0.5}]
        result = self.mgr._apply_effects(entities, [0.5] * 5)
        assert result[0]["scale"] == 0.5  # Unchanged
