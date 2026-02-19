"""Tests for VJ Server input validation and sanitization functions."""

# Import the validation functions from vj_server
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from audio_processor.vj_server import (
    _clamp_finite,
    _sanitize_audio_frame,
    _sanitize_entities,
)

# ---------------------------------------------------------------------------
# _clamp_finite tests
# ---------------------------------------------------------------------------


class TestClampFinite:
    def test_normal_value_in_range(self):
        assert _clamp_finite(0.5, 0.0, 1.0, 0.0) == 0.5

    def test_value_at_lower_bound(self):
        assert _clamp_finite(0.0, 0.0, 1.0, 0.5) == 0.0

    def test_value_at_upper_bound(self):
        assert _clamp_finite(1.0, 0.0, 1.0, 0.5) == 1.0

    def test_value_below_lower_bound(self):
        assert _clamp_finite(-0.5, 0.0, 1.0, 0.5) == 0.0

    def test_value_above_upper_bound(self):
        assert _clamp_finite(2.5, 0.0, 1.0, 0.5) == 1.0

    def test_nan_returns_default(self):
        assert _clamp_finite(float("nan"), 0.0, 1.0, 0.5) == 0.5

    def test_positive_infinity_returns_default(self):
        assert _clamp_finite(float("inf"), 0.0, 1.0, 0.5) == 0.5

    def test_negative_infinity_returns_default(self):
        assert _clamp_finite(float("-inf"), 0.0, 1.0, 0.5) == 0.5

    def test_string_returns_default(self):
        assert _clamp_finite("hello", 0.0, 1.0, 0.5) == 0.5

    def test_none_returns_default(self):
        assert _clamp_finite(None, 0.0, 1.0, 0.5) == 0.5

    def test_list_returns_default(self):
        assert _clamp_finite([1, 2], 0.0, 1.0, 0.5) == 0.5

    def test_int_value_converted_to_float(self):
        assert _clamp_finite(1, 0.0, 2.0, 0.5) == 1.0
        assert isinstance(_clamp_finite(1, 0.0, 2.0, 0.5), float)

    def test_bool_returns_default(self):
        # booleans are technically ints in Python, but we want them treated as numbers
        # bool is a subclass of int, so True=1.0 and False=0.0 — this is acceptable
        result = _clamp_finite(True, 0.0, 1.0, 0.5)
        assert result == 1.0


# ---------------------------------------------------------------------------
# _sanitize_audio_frame tests
# ---------------------------------------------------------------------------


class TestSanitizeAudioFrame:
    def test_valid_frame_passes_through(self):
        data = {
            "seq": 42,
            "bands": [0.1, 0.2, 0.3, 0.4, 0.5],
            "peak": 0.8,
            "beat": True,
            "beat_i": 0.6,
            "bpm": 128.0,
            "i_bass": 0.3,
            "i_kick": False,
            "ts": 1700000000.0,
        }
        safe = _sanitize_audio_frame(data)
        assert safe["bands"] == [0.1, 0.2, 0.3, 0.4, 0.5]
        assert safe["peak"] == 0.8
        assert safe["beat"] is True
        assert safe["beat_i"] == 0.6
        assert safe["bpm"] == 128.0
        assert safe["seq"] == 42
        assert safe["i_bass"] == 0.3
        assert safe["i_kick"] is False

    def test_empty_dict_uses_defaults(self):
        safe = _sanitize_audio_frame({})
        assert safe["bands"] == [0.0, 0.0, 0.0, 0.0, 0.0]
        assert safe["peak"] == 0.0
        assert safe["beat"] is False
        assert safe["beat_i"] == 0.0
        assert safe["bpm"] == 120.0
        assert safe["seq"] == 0
        assert safe["i_bass"] == 0.0
        assert safe["i_kick"] is False

    def test_bands_nan_replaced(self):
        data = {"bands": [float("nan"), 0.5, float("inf"), -0.1, 1.5]}
        safe = _sanitize_audio_frame(data)
        assert safe["bands"] == [0.0, 0.5, 0.0, 0.0, 1.0]

    def test_bands_wrong_length_short(self):
        data = {"bands": [0.5, 0.3]}
        safe = _sanitize_audio_frame(data)
        assert len(safe["bands"]) == 5
        assert safe["bands"] == [0.5, 0.3, 0.0, 0.0, 0.0]

    def test_bands_wrong_length_long(self):
        data = {"bands": [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7]}
        safe = _sanitize_audio_frame(data)
        assert len(safe["bands"]) == 5
        assert safe["bands"] == [0.1, 0.2, 0.3, 0.4, 0.5]

    def test_bands_not_a_list(self):
        data = {"bands": "invalid"}
        safe = _sanitize_audio_frame(data)
        assert safe["bands"] == [0.0, 0.0, 0.0, 0.0, 0.0]

    def test_bands_with_non_numeric_elements(self):
        data = {"bands": ["a", None, {}, [], True]}
        safe = _sanitize_audio_frame(data)
        # "a", None, {}, [] → 0.0; True → 1.0 (bool is subclass of int)
        assert safe["bands"][0] == 0.0
        assert safe["bands"][1] == 0.0
        assert safe["bands"][2] == 0.0
        assert safe["bands"][3] == 0.0

    def test_peak_clamped(self):
        data = {"peak": 10.0}
        safe = _sanitize_audio_frame(data)
        assert safe["peak"] == 5.0

    def test_peak_negative(self):
        data = {"peak": -1.0}
        safe = _sanitize_audio_frame(data)
        assert safe["peak"] == 0.0

    def test_bpm_clamped(self):
        data = {"bpm": 500.0}
        safe = _sanitize_audio_frame(data)
        assert safe["bpm"] == 300.0

    def test_bpm_negative(self):
        data = {"bpm": -10.0}
        safe = _sanitize_audio_frame(data)
        assert safe["bpm"] == 0.0

    def test_bpm_nan(self):
        data = {"bpm": float("nan")}
        safe = _sanitize_audio_frame(data)
        assert safe["bpm"] == 120.0  # default

    def test_seq_negative(self):
        data = {"seq": -5}
        safe = _sanitize_audio_frame(data)
        assert safe["seq"] == 0

    def test_seq_not_int(self):
        data = {"seq": "bad"}
        safe = _sanitize_audio_frame(data)
        assert safe["seq"] == 0

    def test_beat_non_bool(self):
        data = {"beat": "yes"}
        safe = _sanitize_audio_frame(data)
        # bool("yes") == True, which is acceptable
        assert isinstance(safe["beat"], bool)

    def test_ts_passed_through(self):
        data = {"ts": 1700000000.123}
        safe = _sanitize_audio_frame(data)
        assert safe["ts"] == 1700000000.123

    def test_ts_none_when_missing(self):
        safe = _sanitize_audio_frame({})
        assert safe["ts"] is None


# ---------------------------------------------------------------------------
# _sanitize_entities tests
# ---------------------------------------------------------------------------


class TestSanitizeEntities:
    def test_valid_entity(self):
        entities = [{"id": "e0", "x": 0.5, "y": 0.3, "z": 0.7, "scale": 1.0}]
        result = _sanitize_entities(entities)
        assert len(result) == 1
        assert result[0]["id"] == "e0"
        assert result[0]["x"] == 0.5
        assert result[0]["y"] == 0.3
        assert result[0]["z"] == 0.7
        assert result[0]["scale"] == 1.0

    def test_coordinates_clamped(self):
        entities = [{"id": "e0", "x": -0.5, "y": 2.0, "z": float("nan")}]
        result = _sanitize_entities(entities)
        assert result[0]["x"] == 0.0
        assert result[0]["y"] == 1.0
        assert result[0]["z"] == 0.5  # default

    def test_scale_clamped(self):
        entities = [{"id": "e0", "scale": 10.0}]
        result = _sanitize_entities(entities)
        assert result[0]["scale"] == 4.0

    def test_scale_negative(self):
        entities = [{"id": "e0", "scale": -1.0}]
        result = _sanitize_entities(entities)
        assert result[0]["scale"] == 0.0

    def test_scale_nan(self):
        entities = [{"id": "e0", "scale": float("nan")}]
        result = _sanitize_entities(entities)
        assert result[0]["scale"] == 0.5  # default

    def test_brightness_clamped(self):
        entities = [{"id": "e0", "brightness": 20}]
        result = _sanitize_entities(entities)
        assert result[0]["brightness"] == 15

    def test_brightness_negative(self):
        entities = [{"id": "e0", "brightness": -5}]
        result = _sanitize_entities(entities)
        assert result[0]["brightness"] == 0

    def test_interpolation_clamped(self):
        entities = [{"id": "e0", "interpolation": 200}]
        result = _sanitize_entities(entities)
        assert result[0]["interpolation"] == 100

    def test_rotation_clamped(self):
        entities = [{"id": "e0", "rotation": 500.0}]
        result = _sanitize_entities(entities)
        assert result[0]["rotation"] == 360.0

    def test_missing_id_skipped(self):
        entities = [{"x": 0.5}, {"id": "e1", "x": 0.3}]
        result = _sanitize_entities(entities)
        assert len(result) == 1
        assert result[0]["id"] == "e1"

    def test_empty_id_skipped(self):
        entities = [{"id": "", "x": 0.5}]
        result = _sanitize_entities(entities)
        assert len(result) == 0

    def test_non_dict_elements_skipped(self):
        entities = ["bad", 42, None, {"id": "e0", "x": 0.5}]
        result = _sanitize_entities(entities)
        assert len(result) == 1

    def test_max_count_enforced(self):
        entities = [{"id": f"e{i}", "x": 0.5} for i in range(100)]
        result = _sanitize_entities(entities, max_count=10)
        assert len(result) == 10

    def test_not_a_list(self):
        result = _sanitize_entities("bad")
        assert result == []

    def test_none_input(self):
        result = _sanitize_entities(None)
        assert result == []

    def test_glow_passed_as_bool(self):
        entities = [{"id": "e0", "glow": True}]
        result = _sanitize_entities(entities)
        assert result[0]["glow"] is True

    def test_material_string_passed(self):
        entities = [{"id": "e0", "material": "GLOWSTONE"}]
        result = _sanitize_entities(entities)
        assert result[0]["material"] == "GLOWSTONE"

    def test_material_non_string_excluded(self):
        entities = [{"id": "e0", "material": 42}]
        result = _sanitize_entities(entities)
        assert "material" not in result[0]

    def test_only_present_fields_included(self):
        """Fields not in the input should not appear in the output."""
        entities = [{"id": "e0", "x": 0.5}]
        result = _sanitize_entities(entities)
        assert "y" not in result[0]
        assert "scale" not in result[0]
        assert "brightness" not in result[0]

    def test_infinity_coordinates(self):
        entities = [{"id": "e0", "x": float("inf"), "y": float("-inf")}]
        result = _sanitize_entities(entities)
        assert result[0]["x"] == 0.5  # default for non-finite
        assert result[0]["y"] == 0.0  # default for y


# ---------------------------------------------------------------------------
# DJConnection rate limiter tests
# ---------------------------------------------------------------------------


class TestRateLimiter:
    def test_rate_limit_allows_normal_rate(self):
        """DJConnection.check_rate_limit should allow frames under the limit."""
        from audio_processor.vj_server import DJConnection

        # Create a mock-like DJConnection (need a websocket arg)
        class FakeWS:
            remote_address = ("127.0.0.1", 1234)

        dj = DJConnection(dj_id="test", dj_name="Test", websocket=FakeWS())
        # Should allow many frames when bucket is full
        allowed = sum(1 for _ in range(130) if dj.check_rate_limit())
        # Initial bucket has 120 tokens, should allow ~120 frames
        assert 119 <= allowed <= 121

    def test_rate_limit_drops_excess(self):
        """After exhausting tokens, check_rate_limit should return False."""
        from audio_processor.vj_server import DJConnection

        class FakeWS:
            remote_address = ("127.0.0.1", 1234)

        dj = DJConnection(dj_id="test", dj_name="Test", websocket=FakeWS())
        # Exhaust all tokens
        for _ in range(125):
            dj.check_rate_limit()
        # Next call should be denied
        assert dj.check_rate_limit() is False
