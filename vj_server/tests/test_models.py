"""Tests for vj_server.models — data classes, sanitizers, and connect codes."""

import time

import pytest

from vj_server.models import (
    ConnectCode,
    DJAuthConfig,
    DJConnection,
    ZonePatternState,
    _clamp_finite,
    _sanitize_entities,
    _sanitize_name,
)

# ============================================================================
# _sanitize_name
# ============================================================================


class TestSanitizeName:
    def test_normal_name(self):
        assert _sanitize_name("Cool DJ") == "Cool DJ"

    def test_strips_non_printable(self):
        assert _sanitize_name("DJ\x00\x01\x02Cool") == "DJCool"

    def test_empty_returns_default(self):
        assert _sanitize_name("") == "DJ"

    def test_whitespace_only_returns_default(self):
        assert _sanitize_name("   ") == "DJ"

    def test_custom_default(self):
        assert _sanitize_name("", default="Anonymous") == "Anonymous"

    def test_truncates_to_max_length(self):
        long_name = "A" * 100
        result = _sanitize_name(long_name, max_length=10)
        assert len(result) == 10

    def test_unicode_preserved(self):
        assert _sanitize_name("DJ \u00e9toile") == "DJ \u00e9toile"


# ============================================================================
# _clamp_finite
# ============================================================================


class TestClampFinite:
    def test_value_in_range(self):
        assert _clamp_finite(0.5, 0.0, 1.0, 0.0) == 0.5

    def test_value_below_range(self):
        assert _clamp_finite(-1.0, 0.0, 1.0, 0.0) == 0.0

    def test_value_above_range(self):
        assert _clamp_finite(5.0, 0.0, 1.0, 0.0) == 1.0

    def test_nan_returns_default(self):
        assert _clamp_finite(float("nan"), 0.0, 1.0, 0.5) == 0.5

    def test_inf_returns_default(self):
        assert _clamp_finite(float("inf"), 0.0, 1.0, 0.5) == 0.5

    def test_neg_inf_returns_default(self):
        assert _clamp_finite(float("-inf"), 0.0, 1.0, 0.5) == 0.5

    def test_string_returns_default(self):
        assert _clamp_finite("not a number", 0.0, 1.0, 0.5) == 0.5

    def test_none_returns_default(self):
        assert _clamp_finite(None, 0.0, 1.0, 0.5) == 0.5

    def test_int_coerced_to_float(self):
        assert _clamp_finite(1, 0.0, 2.0, 0.0) == 1.0
        assert isinstance(_clamp_finite(1, 0.0, 2.0, 0.0), float)

    def test_boundary_values(self):
        assert _clamp_finite(0.0, 0.0, 1.0, 0.5) == 0.0
        assert _clamp_finite(1.0, 0.0, 1.0, 0.5) == 1.0


# ============================================================================
# _sanitize_entities
# ============================================================================


class TestSanitizeEntities:
    def test_valid_entity(self):
        entities = [{"id": "block_0", "x": 0.5, "y": 0.3, "z": 0.5, "scale": 0.2}]
        result = _sanitize_entities(entities)
        assert len(result) == 1
        assert result[0]["id"] == "block_0"
        assert result[0]["x"] == 0.5

    def test_clamps_coordinates(self):
        entities = [{"id": "block_0", "x": 2.0, "y": -1.0, "z": 0.5}]
        result = _sanitize_entities(entities)
        assert result[0]["x"] == 1.0
        assert result[0]["y"] == 0.0

    def test_clamps_scale(self):
        entities = [{"id": "block_0", "scale": 10.0}]
        result = _sanitize_entities(entities)
        assert result[0]["scale"] == 4.0

    def test_rejects_missing_id(self):
        entities = [{"x": 0.5}]
        result = _sanitize_entities(entities)
        assert len(result) == 0

    def test_rejects_empty_id(self):
        entities = [{"id": "", "x": 0.5}]
        result = _sanitize_entities(entities)
        assert len(result) == 0

    def test_max_count_enforced(self):
        entities = [{"id": f"block_{i}"} for i in range(1000)]
        result = _sanitize_entities(entities, max_count=10)
        assert len(result) == 10

    def test_non_list_returns_empty(self):
        assert _sanitize_entities("not a list") == []
        assert _sanitize_entities(None) == []

    def test_non_dict_entities_skipped(self):
        entities = [{"id": "block_0"}, "bad", 123, {"id": "block_1"}]
        result = _sanitize_entities(entities)
        assert len(result) == 2

    def test_material_validation(self):
        entities = [{"id": "block_0", "material": "STONE"}]
        result = _sanitize_entities(entities)
        assert result[0]["material"] == "STONE"

    def test_invalid_material_defaults_to_stone(self):
        entities = [{"id": "block_0", "material": "DROP TABLE;"}]
        result = _sanitize_entities(entities)
        assert result[0]["material"] == "STONE"

    def test_brightness_clamped(self):
        entities = [{"id": "block_0", "brightness": 20}]
        result = _sanitize_entities(entities)
        assert result[0]["brightness"] == 15

    def test_boolean_fields(self):
        entities = [{"id": "block_0", "glow": True, "visible": False}]
        result = _sanitize_entities(entities)
        assert result[0]["glow"] is True
        assert result[0]["visible"] is False


# ============================================================================
# ConnectCode
# ============================================================================


class TestConnectCode:
    def test_generate_format(self):
        code = ConnectCode.generate()
        parts = code.code.split("-")
        assert len(parts) == 2
        assert len(parts[0]) == 4  # WORD
        assert len(parts[1]) == 4  # XXXX

    def test_generate_unique(self):
        codes = {ConnectCode.generate().code for _ in range(20)}
        assert len(codes) == 20  # All unique

    def test_is_valid_fresh(self):
        code = ConnectCode.generate()
        assert code.is_valid() is True

    def test_is_valid_after_use(self):
        code = ConnectCode.generate()
        code.used = True
        assert code.is_valid() is False

    def test_is_valid_expired(self):
        code = ConnectCode(code="TEST-1234", created_at=0.0, expires_at=1.0)
        assert code.is_valid() is False

    def test_default_ttl_30_minutes(self):
        code = ConnectCode.generate()
        expected_ttl = 30 * 60
        actual_ttl = code.expires_at - code.created_at
        assert abs(actual_ttl - expected_ttl) < 1.0

    def test_custom_ttl(self):
        code = ConnectCode.generate(ttl_minutes=5)
        actual_ttl = code.expires_at - code.created_at
        assert abs(actual_ttl - 300.0) < 1.0


# ============================================================================
# DJConnection
# ============================================================================


class TestDJConnection:
    def _make_dj(self, **kwargs):
        defaults = {"dj_id": "test_dj", "dj_name": "Test DJ", "websocket": None}
        defaults.update(kwargs)
        return DJConnection(**defaults)

    def test_default_values(self):
        dj = self._make_dj()
        assert dj.bpm == 120.0
        assert dj.priority == 10
        assert dj.bands == [0.0] * 5
        assert dj.frame_count == 0

    def test_check_rate_limit_allows_burst(self):
        dj = self._make_dj()
        # Should allow many rapid calls (bucket starts at 120 tokens)
        for _ in range(100):
            assert dj.check_rate_limit() is True

    def test_check_rate_limit_eventually_blocks(self):
        dj = self._make_dj()
        dj._rate_tokens = 1.0
        dj._rate_last_refill = time.time()
        assert dj.check_rate_limit() is True  # Uses last token
        assert dj.check_rate_limit() is False  # Empty bucket

    def test_update_fps(self):
        dj = self._make_dj()
        # Simulate 60 frames in rapid succession
        for _ in range(60):
            dj.update_fps()
        assert dj.frames_per_second == 60


# ============================================================================
# DJAuthConfig
# ============================================================================


class TestDJAuthConfig:
    def test_from_dict_empty(self):
        config = DJAuthConfig.from_dict({})
        assert config.djs == {}
        assert config.vj_operators == {}

    def test_from_dict_with_hashed_passwords(self):
        data = {
            "djs": {"dj1": {"key_hash": "sha256:abc123", "name": "DJ One"}},
            "vj_operators": {},
        }
        config = DJAuthConfig.from_dict(data)
        assert "dj1" in config.djs

    def test_rejects_plaintext_passwords(self):
        data = {
            "djs": {"dj1": {"key_hash": "plaintext_password"}},
            "vj_operators": {},
        }
        with pytest.raises(ValueError, match="Plaintext passwords detected"):
            DJAuthConfig.from_dict(data)

    def test_has_plaintext_passwords(self):
        config = DJAuthConfig(
            djs={"dj1": {"key_hash": "sha256:abc"}},
            vj_operators={},
        )
        assert config.has_plaintext_passwords() is False

    def test_verify_dj_unknown_id(self):
        config = DJAuthConfig(djs={}, vj_operators={})
        assert config.verify_dj("unknown", "pass") is None


# ============================================================================
# ZonePatternState
# ============================================================================


class TestZonePatternState:
    def test_defaults(self):
        state = ZonePatternState()
        assert state.pattern_name == "spectrum"
        assert state.entity_count == 16
        assert state.transitioning is False
        assert state.block_type == "SEA_LANTERN"
        assert state.render_mode == "block"
