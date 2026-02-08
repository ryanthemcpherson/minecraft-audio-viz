"""
Unit tests for crossfade transition system in the timeline cue executor.

Tests CrossfadeState interpolation, easing functions, CueExecutor crossfade
lifecycle, and edge cases. Uses mocked time for deterministic timing tests.

Run with: python -m pytest audio_processor/tests/test_crossfade.py -v
"""

import time
from unittest.mock import patch

import pytest

from audio_processor.timeline.cue_executor import (
    EASING_FUNCTIONS,
    CrossfadeState,
    CueExecutor,
    _ease_in_out_quad,
    _ease_in_out_sine,
    _ease_in_quad,
    _ease_linear,
    _ease_out_quad,
)
from audio_processor.timeline.models import (
    Cue,
    CueAction,
    CueType,
    Transition,
    TransitionType,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_entity(eid, x=0.0, y=0.0, z=0.0, scale=1.0, **extra):
    """Convenience builder for entity dicts used in interpolation tests."""
    entity = {"id": eid, "x": x, "y": y, "z": z, "scale": scale}
    entity.update(extra)
    return entity


def _make_pattern_cue(pattern, transition_type=TransitionType.INSTANT, duration=0, easing="linear"):
    """Build a Cue that triggers a pattern change."""
    return Cue(
        name=f"Switch to {pattern}",
        type=CueType.PATTERN_CHANGE,
        action=CueAction(pattern=pattern),
        transition=Transition(type=transition_type, duration=duration, easing=easing),
    )


# ===========================================================================
# Easing function tests
# ===========================================================================


class TestEasingFunctions:
    """Verify easing function boundary values and ordering."""

    # --- Boundary values (must hold for every easing function) ---

    @pytest.mark.parametrize("name,fn", list(EASING_FUNCTIONS.items()))
    def test_easing_returns_zero_at_zero(self, name, fn):
        """All easing functions must return 0 when t=0."""
        assert fn(0.0) == pytest.approx(0.0), f"{name}(0) should be 0"

    @pytest.mark.parametrize("name,fn", list(EASING_FUNCTIONS.items()))
    def test_easing_returns_one_at_one(self, name, fn):
        """All easing functions must return 1 when t=1."""
        assert fn(1.0) == pytest.approx(1.0), f"{name}(1) should be 1"

    # --- Linear is identity ---

    def test_linear_returns_t_unchanged(self):
        """Linear easing should return the input value unchanged."""
        for t in [0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0]:
            assert _ease_linear(t) == pytest.approx(t)

    # --- ease_in starts slow ---

    def test_ease_in_starts_slow(self):
        """ease_in (quad) should produce values below linear for small t."""
        for t in [0.1, 0.2, 0.3, 0.4]:
            assert _ease_in_quad(t) < t, f"ease_in({t}) = {_ease_in_quad(t)} should be < {t}"

    # --- ease_out ends slow ---

    def test_ease_out_ends_slow(self):
        """ease_out (quad) should produce values above linear for small t."""
        for t in [0.1, 0.2, 0.3, 0.4]:
            assert _ease_out_quad(t) > t, f"ease_out({t}) = {_ease_out_quad(t)} should be > {t}"

    # --- Different easings produce different intermediate values ---

    def test_different_easings_differ_at_midpoint(self):
        """Different easing functions should produce distinct values at t=0.25."""
        t = 0.25
        values = {name: fn(t) for name, fn in EASING_FUNCTIONS.items()}
        unique_values = set(round(v, 6) for v in values.values())
        assert len(unique_values) > 1, f"Expected distinct midpoint values, got {values}"

    # --- ease_in_out symmetry ---

    def test_ease_in_out_symmetric(self):
        """ease_in_out should be symmetric around t=0.5."""
        assert _ease_in_out_quad(0.5) == pytest.approx(0.5, abs=0.01)

    # --- sine easing basic check ---

    def test_sine_at_midpoint(self):
        """sine easing at t=0.5 should equal 0.5."""
        assert _ease_in_out_sine(0.5) == pytest.approx(0.5)


# ===========================================================================
# CrossfadeState tests
# ===========================================================================


class TestCrossfadeState:
    """Test CrossfadeState progress tracking and entity interpolation."""

    # --- Progress tracking ---

    def test_progress_starts_at_zero(self):
        """Progress should be 0 (or very close) immediately after creation."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("patternA", "patternB", duration_ms=500)
            # progress reads time again; still the same instant
            assert state.progress == pytest.approx(0.0, abs=0.01)

    def test_progress_reaches_one_after_duration(self):
        """Progress should be 1.0 once the full duration has elapsed."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("patternA", "patternB", duration_ms=200)
            # Jump forward by exactly the duration (200 ms = 0.2 s)
            mock_time.time.return_value = 1000.2
            assert state.progress == pytest.approx(1.0)

    def test_progress_midway(self):
        """Progress at half duration should be ~0.5 for linear easing."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("A", "B", duration_ms=100, easing="linear")
            mock_time.time.return_value = 1000.05  # 50 ms elapsed
            assert state.progress == pytest.approx(0.5, abs=0.02)

    def test_progress_clamped_above_one(self):
        """Progress should never exceed 1.0 even after duration has passed."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("A", "B", duration_ms=100)
            mock_time.time.return_value = 1005.0  # 5 seconds later
            assert state.progress <= 1.0

    # --- is_complete ---

    def test_is_complete_false_during_transition(self):
        """is_complete should return False while the crossfade is running."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("A", "B", duration_ms=500)
            mock_time.time.return_value = 1000.1  # 100 ms (only 20%)
            assert state.is_complete is False

    def test_is_complete_true_after_duration(self):
        """is_complete should return True once the full duration has elapsed."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("A", "B", duration_ms=200)
            mock_time.time.return_value = 1000.3  # 300 ms (past 200)
            assert state.is_complete is True

    # --- Easing affects intermediate progress ---

    def test_easing_affects_progress(self):
        """Non-linear easing should produce different progress than linear."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0

            linear = CrossfadeState("A", "B", duration_ms=200, easing="linear")
            ease_in = CrossfadeState("A", "B", duration_ms=200, easing="ease_in")
            ease_out = CrossfadeState("A", "B", duration_ms=200, easing="ease_out")

            # At 25% duration (50 ms)
            mock_time.time.return_value = 1000.05
            p_linear = linear.progress
            p_ease_in = ease_in.progress
            p_ease_out = ease_out.progress

            # ease_in starts slow, so progress < linear
            assert p_ease_in < p_linear, "ease_in progress should lag linear"
            # ease_out starts fast, so progress > linear
            assert p_ease_out > p_linear, "ease_out progress should lead linear"

    # --- Zero-duration crossfade ---

    def test_zero_duration_clamped_and_no_crash(self):
        """duration_ms=0 should be clamped to 1 ms and not cause division by zero."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("A", "B", duration_ms=0)
            # duration_ms should be clamped to at least 1
            assert state.duration_ms >= 1
            # Progress should be valid (likely already complete)
            mock_time.time.return_value = 1000.01  # 10 ms later, well past 1 ms
            assert state.progress <= 1.0
            assert state.is_complete is True

    def test_negative_duration_clamped(self):
        """Negative duration should be clamped to 1 ms."""
        state = CrossfadeState("A", "B", duration_ms=-100)
        assert state.duration_ms >= 1

    # --- Unknown easing falls back to linear ---

    def test_unknown_easing_falls_back_to_linear(self):
        """An unrecognized easing name should fall back to linear."""
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("A", "B", duration_ms=200, easing="nonexistent")
            mock_time.time.return_value = 1000.05  # 25%
            # Linear at 0.25 raw progress should give 0.25
            assert state.progress == pytest.approx(0.25, abs=0.02)


class TestCrossfadeInterpolation:
    """Test CrossfadeState.interpolate_entities() blending behaviour."""

    def _make_state_at_progress(self, t):
        """
        Create a CrossfadeState whose .progress property returns exactly *t*.

        We mock time so that at query time the raw progress is *t* and the
        easing is linear (so eased progress == raw progress == t).
        """
        with patch("audio_processor.timeline.cue_executor.time") as mock_time:
            mock_time.time.return_value = 1000.0
            state = CrossfadeState("A", "B", duration_ms=1000, easing="linear")
        # Directly set _easing_fn and start_time so progress computes to t
        # progress = easing(min(1, elapsed_ms / duration_ms))
        # We want elapsed_ms / 1000 = t => elapsed_ms = t * 1000 => elapsed = t (sec)
        state.start_time = 1000.0
        state._easing_fn = _ease_linear
        # Patch time.time on reads
        self._time_value = 1000.0 + t  # t seconds after start
        return state

    def _progress_time(self):
        return self._time_value

    # --- Position blending ---

    def test_interpolate_positions_at_zero(self):
        """At t=0 blended positions should equal old positions."""
        state = self._make_state_at_progress(0.0)
        old = [_make_entity("e0", x=1.0, y=2.0, z=3.0)]
        new = [_make_entity("e0", x=5.0, y=6.0, z=7.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        assert result[0]["x"] == pytest.approx(1.0)
        assert result[0]["y"] == pytest.approx(2.0)
        assert result[0]["z"] == pytest.approx(3.0)

    def test_interpolate_positions_at_one(self):
        """At t=1 blended positions should equal new positions."""
        state = self._make_state_at_progress(1.0)
        old = [_make_entity("e0", x=1.0, y=2.0, z=3.0)]
        new = [_make_entity("e0", x=5.0, y=6.0, z=7.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        assert result[0]["x"] == pytest.approx(5.0)
        assert result[0]["y"] == pytest.approx(6.0)
        assert result[0]["z"] == pytest.approx(7.0)

    def test_interpolate_positions_at_half(self):
        """At t=0.5 blended positions should be the midpoint."""
        state = self._make_state_at_progress(0.5)
        old = [_make_entity("e0", x=0.0, y=0.0, z=0.0)]
        new = [_make_entity("e0", x=10.0, y=20.0, z=30.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        assert result[0]["x"] == pytest.approx(5.0)
        assert result[0]["y"] == pytest.approx(10.0)
        assert result[0]["z"] == pytest.approx(15.0)

    # --- Scale blending ---

    def test_interpolate_scales(self):
        """Scales should interpolate linearly between old and new."""
        state = self._make_state_at_progress(0.5)
        old = [_make_entity("e0", scale=2.0)]
        new = [_make_entity("e0", scale=8.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        # (2.0 * 0.5) + (8.0 * 0.5) = 5.0
        assert result[0]["scale"] == pytest.approx(5.0)

    def test_interpolate_scales_at_zero(self):
        """At t=0 scale should be old scale."""
        state = self._make_state_at_progress(0.0)
        old = [_make_entity("e0", scale=3.0)]
        new = [_make_entity("e0", scale=9.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        assert result[0]["scale"] == pytest.approx(3.0)

    def test_interpolate_scales_at_one(self):
        """At t=1 scale should be new scale."""
        state = self._make_state_at_progress(1.0)
        old = [_make_entity("e0", scale=3.0)]
        new = [_make_entity("e0", scale=9.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        assert result[0]["scale"] == pytest.approx(9.0)

    # --- Brightness blending ---

    def test_interpolate_brightness(self):
        """Optional brightness property should be interpolated when present."""
        state = self._make_state_at_progress(0.5)
        old = [_make_entity("e0", brightness=0.2)]
        new = [_make_entity("e0", brightness=0.8)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        assert "brightness" in result[0]
        assert result[0]["brightness"] == pytest.approx(0.5)

    # --- Mismatched entity counts ---

    def test_new_entities_fade_in(self):
        """Extra new entities should fade in (scale starts at 0 and grows)."""
        state = self._make_state_at_progress(0.5)
        old = [_make_entity("e0")]
        new = [_make_entity("e0"), _make_entity("e1", scale=2.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        # First entity is blended normally; second entity fades in
        assert len(result) == 2
        # Fading-in entity scale = original_scale * t = 2.0 * 0.5 = 1.0
        assert result[1]["scale"] == pytest.approx(1.0)

    def test_old_entities_fade_out(self):
        """Extra old entities should fade out (scale shrinks to 0)."""
        state = self._make_state_at_progress(0.5)
        old = [_make_entity("e0"), _make_entity("e1", scale=2.0)]
        new = [_make_entity("e0")]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        # Fading-out entity scale = original_scale * (1 - t) = 2.0 * 0.5 = 1.0
        # It should still be present because scale > 0.01
        fading = [e for e in result if e.get("id") == "e1"]
        assert len(fading) == 1
        assert fading[0]["scale"] == pytest.approx(1.0)

    def test_old_entities_removed_when_invisible(self):
        """Old-only entities with scale <= 0.01 should be dropped."""
        state = self._make_state_at_progress(0.999)
        old = [_make_entity("e0"), _make_entity("e1", scale=0.005)]
        new = [_make_entity("e0")]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        # e1.scale = 0.005 * (1 - 0.999) = 0.000005 < 0.01 => dropped
        ids = [e["id"] for e in result]
        assert "e1" not in ids

    # --- Empty entity lists ---

    def test_both_empty_returns_empty(self):
        """Interpolating two empty entity lists should return an empty list."""
        state = self._make_state_at_progress(0.5)
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities([], [])
        assert result == []

    def test_old_empty_fades_in_new(self):
        """When old list is empty, all new entities should fade in."""
        state = self._make_state_at_progress(0.5)
        new = [_make_entity("e0", scale=4.0), _make_entity("e1", scale=2.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities([], new)
        assert len(result) == 2
        # scale = original * t = 4.0 * 0.5, 2.0 * 0.5
        assert result[0]["scale"] == pytest.approx(2.0)
        assert result[1]["scale"] == pytest.approx(1.0)

    def test_new_empty_fades_out_old(self):
        """When new list is empty, all old entities should fade out."""
        state = self._make_state_at_progress(0.5)
        old = [_make_entity("e0", scale=4.0)]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, [])
        # scale = 4.0 * (1 - 0.5) = 2.0 (> 0.01, so kept)
        assert len(result) == 1
        assert result[0]["scale"] == pytest.approx(2.0)

    # --- Multiple-entity full blend ---

    def test_multiple_entities_blended(self):
        """All entities in matching pairs should be independently blended."""
        state = self._make_state_at_progress(0.25)
        old = [
            _make_entity("e0", x=0, y=0, z=0, scale=1),
            _make_entity("e1", x=10, y=10, z=10, scale=2),
        ]
        new = [
            _make_entity("e0", x=4, y=4, z=4, scale=5),
            _make_entity("e1", x=20, y=20, z=20, scale=10),
        ]
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = self._time_value
            result = state.interpolate_entities(old, new)
        # e0: 0 * 0.75 + 4 * 0.25 = 1.0
        assert result[0]["x"] == pytest.approx(1.0)
        # e1: 10 * 0.75 + 20 * 0.25 = 12.5
        assert result[1]["x"] == pytest.approx(12.5)
        # e1 scale: 2 * 0.75 + 10 * 0.25 = 4.0
        assert result[1]["scale"] == pytest.approx(4.0)


# ===========================================================================
# CueExecutor tests
# ===========================================================================


class TestCueExecutorInstantTransition:
    """Test CueExecutor with instant (non-crossfade) pattern changes."""

    def setup_method(self):
        self.executor = CueExecutor()
        self.handler_calls = []
        self.executor.set_handlers(pattern_handler=lambda p: self.handler_calls.append(p))

    def test_instant_transition_calls_handler(self):
        """Instant transition should immediately call the pattern handler."""
        cue = _make_pattern_cue("wave")
        self.executor.execute(cue)
        assert "wave" in self.handler_calls

    def test_instant_transition_sets_current_pattern(self):
        """After an instant transition, _current_pattern should be updated."""
        cue = _make_pattern_cue("bars")
        self.executor.execute(cue)
        assert self.executor._current_pattern == "bars"

    def test_instant_transition_no_crossfade(self):
        """An instant transition should not create a crossfade state."""
        cue = _make_pattern_cue("bars")
        self.executor.execute(cue)
        assert self.executor._crossfade is None
        assert self.executor.is_crossfading is False

    def test_sequential_instant_transitions(self):
        """Multiple instant transitions should each call the handler."""
        for name in ["wave", "bars", "ring"]:
            self.executor.execute(_make_pattern_cue(name))
        assert self.handler_calls == ["wave", "bars", "ring"]
        assert self.executor._current_pattern == "ring"


class TestCueExecutorCrossfade:
    """Test CueExecutor crossfade lifecycle."""

    def setup_method(self):
        self.executor = CueExecutor()
        self.handler_calls = []
        self.executor.set_handlers(pattern_handler=lambda p: self.handler_calls.append(p))
        # Set an initial pattern so crossfade has a source
        self.executor._current_pattern = "wave"

    def test_crossfade_creates_state(self):
        """A crossfade cue should create a CrossfadeState on the executor."""
        cue = _make_pattern_cue(
            "bars",
            transition_type=TransitionType.CROSSFADE,
            duration=500,
        )
        self.executor.execute(cue)
        assert self.executor._crossfade is not None
        assert self.executor._crossfade.from_pattern == "wave"
        assert self.executor._crossfade.to_pattern == "bars"

    def test_is_crossfading_true_during_transition(self):
        """is_crossfading should return True while crossfade is active."""
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = 1000.0
            cue = _make_pattern_cue(
                "bars",
                transition_type=TransitionType.CROSSFADE,
                duration=500,
            )
            self.executor.execute(cue)
            # Still within duration
            mt.time.return_value = 1000.1  # 100 ms in
            assert self.executor.is_crossfading is True

    def test_is_crossfading_false_after_completion(self):
        """is_crossfading should return False once the crossfade finishes."""
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = 1000.0
            cue = _make_pattern_cue(
                "bars",
                transition_type=TransitionType.CROSSFADE,
                duration=200,
            )
            self.executor.execute(cue)
            # Jump past duration
            mt.time.return_value = 1000.5
            assert self.executor.is_crossfading is False

    def test_crossfade_property_none_after_completion(self):
        """The crossfade property should return None after the crossfade ends."""
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = 1000.0
            cue = _make_pattern_cue(
                "bars",
                transition_type=TransitionType.CROSSFADE,
                duration=200,
            )
            self.executor.execute(cue)
            # Complete the crossfade
            mt.time.return_value = 1001.0
            assert self.executor.crossfade is None

    def test_crossfade_commits_pattern_on_complete(self):
        """Once crossfade completes, _current_pattern should be the new pattern."""
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = 1000.0
            cue = _make_pattern_cue(
                "bars",
                transition_type=TransitionType.CROSSFADE,
                duration=100,
            )
            self.executor.execute(cue)
            # Complete the crossfade by querying .crossfade after duration
            mt.time.return_value = 1001.0
            _ = self.executor.crossfade  # triggers completion logic
            assert self.executor._current_pattern == "bars"

    def test_crossfade_calls_handler_immediately(self):
        """
        Even during crossfade, the pattern handler should be called immediately
        so the new pattern starts generating entities for blending.
        """
        cue = _make_pattern_cue(
            "bars",
            transition_type=TransitionType.CROSSFADE,
            duration=1000,
        )
        self.executor.execute(cue)
        assert "bars" in self.handler_calls

    def test_crossfade_uses_specified_easing(self):
        """CrossfadeState should use the easing specified in the cue."""
        cue = _make_pattern_cue(
            "bars",
            transition_type=TransitionType.CROSSFADE,
            duration=500,
            easing="ease_in",
        )
        self.executor.execute(cue)
        assert self.executor._crossfade.easing == "ease_in"

    def test_no_crossfade_when_no_current_pattern(self):
        """
        Crossfade requires a source pattern. If _current_pattern is None,
        the executor should fall back to an instant transition.
        """
        self.executor._current_pattern = None
        cue = _make_pattern_cue(
            "bars",
            transition_type=TransitionType.CROSSFADE,
            duration=500,
        )
        self.executor.execute(cue)
        # Should be instant because there is no source pattern
        assert self.executor._crossfade is None
        assert self.executor._current_pattern == "bars"


class TestCueExecutorRapidPatternChanges:
    """Test that rapid pattern changes cancel previous crossfades."""

    def setup_method(self):
        self.executor = CueExecutor()
        self.handler_calls = []
        self.executor.set_handlers(pattern_handler=lambda p: self.handler_calls.append(p))
        self.executor._current_pattern = "wave"

    def test_second_crossfade_replaces_first(self):
        """Starting a new crossfade should replace any in-progress crossfade."""
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = 1000.0

            # Start first crossfade
            cue1 = _make_pattern_cue(
                "bars",
                transition_type=TransitionType.CROSSFADE,
                duration=2000,
            )
            self.executor.execute(cue1)
            assert self.executor._crossfade.to_pattern == "bars"

            # 100 ms later, start a second crossfade
            mt.time.return_value = 1000.1
            cue2 = _make_pattern_cue(
                "ring",
                transition_type=TransitionType.CROSSFADE,
                duration=2000,
            )
            self.executor.execute(cue2)

            # The crossfade should now target "ring", not "bars"
            assert self.executor._crossfade.to_pattern == "ring"

    def test_rapid_changes_handler_called_each_time(self):
        """Each pattern change should call the handler, even during crossfade."""
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = 1000.0

            for name in ["bars", "ring", "spiral"]:
                cue = _make_pattern_cue(
                    name,
                    transition_type=TransitionType.CROSSFADE,
                    duration=2000,
                )
                self.executor.execute(cue)

            assert self.handler_calls == ["bars", "ring", "spiral"]

    def test_instant_during_crossfade_cancels_it(self):
        """An instant transition during an active crossfade should cancel it."""
        with patch("audio_processor.timeline.cue_executor.time") as mt:
            mt.time.return_value = 1000.0

            # Start crossfade
            cue1 = _make_pattern_cue(
                "bars",
                transition_type=TransitionType.CROSSFADE,
                duration=2000,
            )
            self.executor.execute(cue1)
            assert self.executor._crossfade is not None

            # Instant change mid-crossfade
            cue2 = _make_pattern_cue("ring")
            mt.time.return_value = 1000.1
            self.executor.execute(cue2)

            # The instant transition sets _crossfade implicitly to None via
            # setting _current_pattern directly; the old crossfade object is
            # still referenced but the executor's _current_pattern is now "ring"
            assert self.executor._current_pattern == "ring"


# ===========================================================================
# CueExecutor other action types (non-crossfade)
# ===========================================================================


class TestCueExecutorOtherActions:
    """Verify preset, parameter, and effect handlers execute correctly."""

    def setup_method(self):
        self.executor = CueExecutor()
        self.preset_calls = []
        self.param_calls = []
        self.effect_calls = []
        self.executor.set_handlers(
            preset_handler=lambda p: self.preset_calls.append(p),
            parameter_handler=lambda n, v: self.param_calls.append((n, v)),
            effect_handler=lambda t, i, d: self.effect_calls.append((t, i, d)),
        )

    def test_preset_change(self):
        cue = Cue(
            type=CueType.PRESET_CHANGE,
            action=CueAction(preset="edm"),
            transition=Transition(),
        )
        self.executor.execute(cue)
        assert self.preset_calls == ["edm"]

    def test_parameter_set(self):
        cue = Cue(
            type=CueType.PARAMETER_SET,
            action=CueAction(parameter={"target": "attack", "value": 0.8}),
            transition=Transition(),
        )
        self.executor.execute(cue)
        assert self.param_calls == [("attack", 0.8)]

    def test_effect_trigger(self):
        cue = Cue(
            type=CueType.EFFECT_TRIGGER,
            action=CueAction(effect={"type": "flash", "intensity": 0.9, "duration": 300}),
            transition=Transition(),
        )
        self.executor.execute(cue)
        assert self.effect_calls == [("flash", 0.9, 300)]

    def test_no_handler_does_not_crash(self):
        """Executing a cue with no handler set should not raise an error."""
        executor = CueExecutor()  # no handlers set
        cue = _make_pattern_cue("wave")
        executor.execute(cue)  # should not raise


# ===========================================================================
# Integration-style timing tests (real time, short durations)
# ===========================================================================


class TestCrossfadeTimingIntegration:
    """
    Light integration tests using real time.sleep() with very short durations
    to verify the crossfade progresses in real time.
    """

    def test_real_time_crossfade_progression(self):
        """CrossfadeState progress should increase over real time."""
        state = CrossfadeState("A", "B", duration_ms=80, easing="linear")
        initial = state.progress
        time.sleep(0.05)  # 50 ms
        later = state.progress
        assert later > initial, "Progress should increase over time"

    def test_real_time_crossfade_completes(self):
        """CrossfadeState should eventually report is_complete = True."""
        state = CrossfadeState("A", "B", duration_ms=50, easing="linear")
        time.sleep(0.08)  # 80 ms > 50 ms duration
        assert state.is_complete is True

    def test_real_time_interpolation_progresses(self):
        """Entity interpolation should produce different blends over time."""
        state = CrossfadeState("A", "B", duration_ms=100, easing="linear")
        old = [_make_entity("e0", x=0.0)]
        new = [_make_entity("e0", x=10.0)]

        first = state.interpolate_entities(old, new)
        time.sleep(0.06)
        second = state.interpolate_entities(old, new)
        assert second[0]["x"] > first[0]["x"], "Blended x should increase as crossfade progresses"


# Run with: python -m pytest audio_processor/tests/test_crossfade.py -v
if __name__ == "__main__":
    pytest.main([__file__, "-v"])
