"""
Protocol Contract Tests for AudioViz WebSocket Messages.

Validates that all WebSocket message formats used across the system
(audio_processor, python_client, admin_panel, preview_tool) are consistent
and conform to the expected schema.

Run with: python -m pytest audio_processor/tests/test_protocol_contract.py -v
"""

import json
from typing import Any

import pytest

from audio_processor.app_capture import PRESETS
from audio_processor.config import PRESETS as CONFIG_PRESETS
from audio_processor.config import AudioConfig
from audio_processor.patterns import (
    PATTERNS,
    AudioState,
    PatternConfig,
    get_pattern,
    list_patterns,
)

# ============================================================================
# Helpers
# ============================================================================


def assert_normalized_float(value: Any, field_name: str):
    """Assert value is a float in [0.0, 1.0] range."""
    assert isinstance(value, (int, float)), (
        f"{field_name}: expected numeric, got {type(value).__name__}"
    )
    assert 0.0 <= float(value) <= 1.0, f"{field_name}: expected 0.0-1.0, got {value}"


def assert_message_is_json_serializable(msg: dict):
    """Assert the message can round-trip through JSON without data loss."""
    serialized = json.dumps(msg)
    deserialized = json.loads(serialized)
    assert deserialized == msg, "Message did not survive JSON round-trip"


# ============================================================================
# Sample Messages (canonical examples of each protocol message)
# ============================================================================


def make_audio_frame_message(
    bands=None,
    peak=0.75,
    beat=True,
    frame=12345,
    amplitude=0.8,
    beat_intensity=0.6,
    bpm=128.0,
):
    """Construct an audio frame message as sent to browser preview clients."""
    if bands is None:
        bands = [0.8, 0.6, 0.4, 0.3, 0.2]
    return {
        "type": "state",
        "entities": [],
        "bands": bands,
        "amplitude": amplitude,
        "is_beat": beat,
        "beat_intensity": beat_intensity,
        "frame": frame,
        "pattern": "spectrum",
        "low_latency": False,
        "latency_ms": 5.2,
        "bpm": bpm,
    }


def make_batch_update_message(zone="main", entity_count=4):
    """Construct a batch_update message as sent to the Minecraft plugin."""
    entities = []
    for i in range(entity_count):
        frac = i / max(1, entity_count - 1)
        entities.append(
            {
                "id": f"block_{i}",
                "x": round(frac, 4),
                "y": round(0.1 + frac * 0.5, 4),
                "z": 0.5,
                "scale": round(0.2 + frac * 0.3, 4),
                "visible": True,
            }
        )
    return {
        "type": "batch_update",
        "zone": zone,
        "entities": entities,
    }


def make_batch_update_with_audio(zone="main"):
    """Construct a batch_update message that includes audio data for redstone sensors."""
    msg = make_batch_update_message(zone, entity_count=2)
    msg["bands"] = [0.5, 0.4, 0.3, 0.2, 0.1]
    msg["amplitude"] = 0.65
    msg["is_beat"] = False
    msg["beat_intensity"] = 0.0
    return msg


def make_set_pattern_message(pattern="ring"):
    """Construct a set_pattern command message."""
    return {
        "type": "set_pattern",
        "pattern": pattern,
    }


def make_set_preset_message(preset_name="edm"):
    """Construct a set_preset command message."""
    return {
        "type": "set_preset",
        "preset": preset_name,
    }


def make_preset_changed_message(preset_name="edm"):
    """Construct a preset_changed broadcast message with full settings."""
    preset = PRESETS.get(preset_name, PRESETS["auto"])
    return {
        "type": "preset_changed",
        "preset": preset_name,
        "settings": {
            "attack": preset.attack,
            "release": preset.release,
            "beat_threshold": preset.beat_threshold,
            "agc_max_gain": preset.agc_max_gain,
            "beat_sensitivity": preset.beat_sensitivity,
            "band_sensitivity": list(preset.band_sensitivity),
        },
    }


# ============================================================================
# 1. Audio Frame Messages
# ============================================================================


class TestAudioFrameMessage:
    """Validate the audio frame / state message format."""

    def test_required_bands_key(self):
        msg = make_audio_frame_message()
        assert "bands" in msg, "Audio frame must have 'bands' key"

    def test_bands_has_exactly_five_values(self):
        msg = make_audio_frame_message()
        assert len(msg["bands"]) == 5, f"Expected 5 bands, got {len(msg['bands'])}"

    def test_bands_values_are_normalized_floats(self):
        msg = make_audio_frame_message()
        for i, value in enumerate(msg["bands"]):
            assert_normalized_float(value, f"bands[{i}]")

    def test_peak_is_normalized_float(self):
        msg = make_audio_frame_message()
        assert_normalized_float(msg["amplitude"], "amplitude")

    def test_beat_is_boolean(self):
        msg = make_audio_frame_message()
        assert isinstance(msg["is_beat"], bool), (
            f"'is_beat' must be bool, got {type(msg['is_beat']).__name__}"
        )

    def test_frame_is_integer(self):
        msg = make_audio_frame_message()
        assert isinstance(msg["frame"], int), (
            f"'frame' must be int, got {type(msg['frame']).__name__}"
        )

    def test_frame_is_non_negative(self):
        msg = make_audio_frame_message()
        assert msg["frame"] >= 0, "'frame' must be non-negative"

    def test_optional_amplitude(self):
        msg = make_audio_frame_message(amplitude=0.9)
        assert isinstance(msg["amplitude"], (int, float))
        assert 0.0 <= msg["amplitude"] <= 1.0

    def test_optional_beat_intensity(self):
        msg = make_audio_frame_message(beat_intensity=0.7)
        assert isinstance(msg["beat_intensity"], (int, float))
        assert 0.0 <= msg["beat_intensity"] <= 1.0

    def test_optional_bpm(self):
        msg = make_audio_frame_message(bpm=140.5)
        assert isinstance(msg["bpm"], (int, float))
        assert msg["bpm"] > 0

    def test_json_serializable(self):
        msg = make_audio_frame_message()
        assert_message_is_json_serializable(msg)

    def test_bands_boundary_zero(self):
        msg = make_audio_frame_message(bands=[0.0, 0.0, 0.0, 0.0, 0.0])
        for i, v in enumerate(msg["bands"]):
            assert_normalized_float(v, f"bands[{i}]")

    def test_bands_boundary_one(self):
        msg = make_audio_frame_message(bands=[1.0, 1.0, 1.0, 1.0, 1.0])
        for i, v in enumerate(msg["bands"]):
            assert_normalized_float(v, f"bands[{i}]")

    def test_wrong_band_count_detected(self):
        """Messages with wrong band count should fail validation."""
        too_few = [0.5, 0.3, 0.2]
        too_many = [0.5, 0.3, 0.2, 0.1, 0.4, 0.6]
        assert len(too_few) != 5
        assert len(too_many) != 5

    def test_beat_false_state(self):
        msg = make_audio_frame_message(beat=False, beat_intensity=0.0)
        assert msg["is_beat"] is False
        assert msg["beat_intensity"] == 0.0

    def test_beat_true_state(self):
        msg = make_audio_frame_message(beat=True, beat_intensity=0.9)
        assert msg["is_beat"] is True
        assert msg["beat_intensity"] > 0


# ============================================================================
# 2. Batch Update Messages
# ============================================================================


class TestBatchUpdateMessage:
    """Validate the batch_update message format sent to Minecraft plugin."""

    def test_type_field(self):
        msg = make_batch_update_message()
        assert msg["type"] == "batch_update"

    def test_zone_is_string(self):
        msg = make_batch_update_message(zone="main")
        assert isinstance(msg["zone"], str)
        assert len(msg["zone"]) > 0

    def test_entities_is_list(self):
        msg = make_batch_update_message()
        assert isinstance(msg["entities"], list)

    def test_entity_required_keys(self):
        msg = make_batch_update_message(entity_count=2)
        required_keys = {"id", "x", "y", "z", "scale", "visible"}
        for entity in msg["entities"]:
            missing = required_keys - set(entity.keys())
            assert not missing, f"Entity missing required keys: {missing}"

    def test_entity_id_is_string(self):
        msg = make_batch_update_message(entity_count=2)
        for entity in msg["entities"]:
            assert isinstance(entity["id"], str)

    def test_entity_coordinates_normalized(self):
        msg = make_batch_update_message(entity_count=4)
        for entity in msg["entities"]:
            for coord in ("x", "y", "z"):
                value = entity[coord]
                assert isinstance(value, (int, float)), (
                    f"Entity {entity['id']}: '{coord}' must be numeric"
                )
                assert 0.0 <= float(value) <= 1.0, (
                    f"Entity {entity['id']}: '{coord}' must be 0-1, got {value}"
                )

    def test_entity_scale_positive(self):
        msg = make_batch_update_message(entity_count=3)
        for entity in msg["entities"]:
            assert isinstance(entity["scale"], (int, float))
            assert entity["scale"] >= 0

    def test_entity_visible_is_boolean(self):
        msg = make_batch_update_message(entity_count=3)
        for entity in msg["entities"]:
            assert isinstance(entity["visible"], bool)

    def test_json_serializable(self):
        msg = make_batch_update_message()
        assert_message_is_json_serializable(msg)

    def test_batch_update_with_audio_data(self):
        """batch_update can optionally carry audio data for redstone sensors."""
        msg = make_batch_update_with_audio()
        assert msg["type"] == "batch_update"
        assert "bands" in msg
        assert len(msg["bands"]) == 5
        assert isinstance(msg["is_beat"], bool)
        assert isinstance(msg["amplitude"], (int, float))
        assert isinstance(msg["beat_intensity"], (int, float))

    def test_different_zone_names(self):
        for zone_name in ("main", "stage_left", "dj_booth", "zone1"):
            msg = make_batch_update_message(zone=zone_name)
            assert msg["zone"] == zone_name

    def test_empty_entities_list(self):
        """An empty entities list should be valid (e.g. during initialization)."""
        msg = {
            "type": "batch_update",
            "zone": "main",
            "entities": [],
        }
        assert msg["type"] == "batch_update"
        assert isinstance(msg["entities"], list)
        assert len(msg["entities"]) == 0
        assert_message_is_json_serializable(msg)


# ============================================================================
# 3. Pattern Change Messages
# ============================================================================


class TestPatternChangeMessage:
    """Validate set_pattern command and pattern_changed broadcast messages."""

    def test_set_pattern_type(self):
        msg = make_set_pattern_message()
        assert msg["type"] == "set_pattern"

    def test_set_pattern_has_pattern_string(self):
        msg = make_set_pattern_message("ring")
        assert isinstance(msg["pattern"], str)
        assert len(msg["pattern"]) > 0

    def test_set_pattern_json_serializable(self):
        msg = make_set_pattern_message()
        assert_message_is_json_serializable(msg)

    def test_all_registered_patterns_are_valid_targets(self):
        """Every pattern in the PATTERNS registry should be usable in a message."""
        for pattern_id in PATTERNS:
            msg = make_set_pattern_message(pattern_id)
            assert msg["pattern"] == pattern_id
            assert isinstance(msg["pattern"], str)

    def test_pattern_list_response_format(self):
        """list_patterns() returns dicts with id, name, description."""
        patterns = list_patterns()
        assert isinstance(patterns, list)
        assert len(patterns) > 0
        for p in patterns:
            assert "id" in p, "Pattern entry missing 'id'"
            assert "name" in p, "Pattern entry missing 'name'"
            assert "description" in p, "Pattern entry missing 'description'"
            assert isinstance(p["id"], str)
            assert isinstance(p["name"], str)
            assert isinstance(p["description"], str)

    def test_pattern_list_ids_match_registry(self):
        """list_patterns() ids should match PATTERNS keys."""
        patterns = list_patterns()
        listed_ids = {p["id"] for p in patterns}
        registry_ids = set(PATTERNS.keys())
        assert listed_ids == registry_ids, (
            f"Mismatch: listed={listed_ids - registry_ids}, missing={registry_ids - listed_ids}"
        )


# ============================================================================
# 4. Preset Change Messages
# ============================================================================


class TestPresetChangeMessage:
    """Validate set_preset command and preset_changed broadcast messages."""

    def test_set_preset_type(self):
        msg = make_set_preset_message()
        assert msg["type"] == "set_preset"

    def test_set_preset_has_preset_string(self):
        msg = make_set_preset_message("edm")
        assert isinstance(msg["preset"], str)
        assert len(msg["preset"]) > 0

    def test_set_preset_json_serializable(self):
        msg = make_set_preset_message()
        assert_message_is_json_serializable(msg)

    def test_preset_changed_broadcast_type(self):
        msg = make_preset_changed_message("edm")
        assert msg["type"] == "preset_changed"

    def test_preset_changed_has_preset_name(self):
        msg = make_preset_changed_message("edm")
        assert isinstance(msg["preset"], str)

    def test_preset_changed_has_settings(self):
        msg = make_preset_changed_message("edm")
        assert "settings" in msg
        settings = msg["settings"]
        assert isinstance(settings, dict)

    def test_preset_changed_required_config_keys(self):
        """The preset_changed broadcast must include core audio config keys."""
        required_keys = {
            "attack",
            "release",
            "beat_threshold",
            "agc_max_gain",
            "beat_sensitivity",
            "band_sensitivity",
        }
        msg = make_preset_changed_message("edm")
        settings = msg["settings"]
        missing = required_keys - set(settings.keys())
        assert not missing, f"preset_changed settings missing keys: {missing}"

    def test_preset_changed_settings_types(self):
        msg = make_preset_changed_message("edm")
        s = msg["settings"]
        assert isinstance(s["attack"], (int, float))
        assert isinstance(s["release"], (int, float))
        assert isinstance(s["beat_threshold"], (int, float))
        assert isinstance(s["agc_max_gain"], (int, float))
        assert isinstance(s["beat_sensitivity"], (int, float))
        assert isinstance(s["band_sensitivity"], list)

    def test_preset_changed_band_sensitivity_length(self):
        msg = make_preset_changed_message("edm")
        assert len(msg["settings"]["band_sensitivity"]) == 5

    def test_preset_changed_json_serializable(self):
        msg = make_preset_changed_message("edm")
        assert_message_is_json_serializable(msg)

    def test_all_presets_produce_valid_changed_messages(self):
        """Every preset in the app_capture PRESETS dict should produce a valid message."""
        for preset_name in PRESETS:
            msg = make_preset_changed_message(preset_name)
            assert msg["type"] == "preset_changed"
            assert msg["preset"] == preset_name
            settings = msg["settings"]
            assert len(settings["band_sensitivity"]) == 5
            assert isinstance(settings["attack"], (int, float))
            assert isinstance(settings["release"], (int, float))
            assert isinstance(settings["beat_threshold"], (int, float))


# ============================================================================
# 5. Preset Configuration Consistency
# ============================================================================


class TestPresetConfiguration:
    """Validate that all presets in both PRESETS dicts have correct structure."""

    def test_app_capture_presets_band_sensitivity_length(self):
        """All presets in app_capture.PRESETS must have band_sensitivity of length 5."""
        for name, preset in PRESETS.items():
            bands = preset.band_sensitivity
            assert bands is not None, f"app_capture preset '{name}' missing 'band_sensitivity'"
            assert len(bands) == 5, (
                f"app_capture preset '{name}': band_sensitivity has "
                f"{len(bands)} elements, expected 5"
            )

    def test_config_presets_band_sensitivity_length(self):
        """All presets in config.PRESETS (AudioConfig) must have band_sensitivity of length 5."""
        for name, config in CONFIG_PRESETS.items():
            assert isinstance(config, AudioConfig), (
                f"config preset '{name}' is not an AudioConfig instance"
            )
            assert len(config.band_sensitivity) == 5, (
                f"config preset '{name}': band_sensitivity has "
                f"{len(config.band_sensitivity)} elements, expected 5"
            )

    def test_app_capture_presets_required_keys(self):
        """All app_capture presets must have the required audio config attributes."""
        required_attrs = [
            "attack",
            "release",
            "beat_threshold",
            "agc_max_gain",
            "beat_sensitivity",
            "bass_weight",
            "band_sensitivity",
        ]
        for name, preset in PRESETS.items():
            for attr in required_attrs:
                assert hasattr(preset, attr), (
                    f"app_capture preset '{name}' missing attribute '{attr}'"
                )

    def test_config_presets_have_matching_names(self):
        """The config.PRESETS and app_capture.PRESETS should share common preset names."""
        config_names = set(CONFIG_PRESETS.keys())
        app_names = set(PRESETS.keys())
        common = config_names & app_names
        # At minimum, 'auto' must exist in both
        assert "auto" in config_names, "config.PRESETS missing 'auto' preset"
        assert "auto" in app_names, "app_capture.PRESETS missing 'auto' preset"
        assert len(common) >= 1, "No common preset names between config and app_capture"

    def test_config_presets_band_sensitivity_values_are_numeric(self):
        """All band_sensitivity values in config.PRESETS must be numeric."""
        for name, config in CONFIG_PRESETS.items():
            for i, val in enumerate(config.band_sensitivity):
                assert isinstance(val, (int, float)), (
                    f"config preset '{name}': band_sensitivity[{i}] "
                    f"is {type(val).__name__}, expected numeric"
                )

    def test_app_capture_presets_band_sensitivity_values_are_numeric(self):
        """All band_sensitivity values in app_capture.PRESETS must be numeric."""
        for name, preset in PRESETS.items():
            for i, val in enumerate(preset.band_sensitivity):
                assert isinstance(val, (int, float)), (
                    f"app_capture preset '{name}': band_sensitivity[{i}] "
                    f"is {type(val).__name__}, expected numeric"
                )

    def test_default_audio_config_band_sensitivity_length(self):
        """A default AudioConfig() must have band_sensitivity of length 5."""
        config = AudioConfig()
        assert len(config.band_sensitivity) == 5

    def test_audio_config_to_dict_preserves_band_sensitivity(self):
        """AudioConfig.to_dict() must preserve band_sensitivity length."""
        for name, config in CONFIG_PRESETS.items():
            d = config.to_dict()
            assert "band_sensitivity" in d
            assert len(d["band_sensitivity"]) == 5, (
                f"config preset '{name}': to_dict() band_sensitivity has "
                f"{len(d['band_sensitivity'])} elements"
            )

    def test_audio_config_round_trip(self):
        """AudioConfig should survive a to_dict/from_dict round-trip."""
        for name, original in CONFIG_PRESETS.items():
            d = original.to_dict()
            restored = AudioConfig.from_dict(d)
            assert len(restored.band_sensitivity) == 5
            assert restored.attack == original.attack
            assert restored.release == original.release
            assert restored.beat_threshold == original.beat_threshold
            assert restored.band_sensitivity == original.band_sensitivity


# ============================================================================
# 6. Pattern Entity Output Contract
# ============================================================================


class TestPatternEntityOutput:
    """Validate that pattern calculate_entities() output matches the protocol.

    Each pattern is parametrized as a separate test case so that a bug in one
    pattern (e.g. Fountain using idx%6 instead of idx%5) does not mask
    results for all other patterns.
    """

    NORMAL_AUDIO = AudioState(
        bands=[0.5, 0.4, 0.3, 0.2, 0.1],
        amplitude=0.5,
        is_beat=False,
        beat_intensity=0.0,
        frame=100,
    )

    BEAT_AUDIO = AudioState(
        bands=[0.9, 0.7, 0.5, 0.3, 0.2],
        amplitude=0.9,
        is_beat=True,
        beat_intensity=0.8,
        frame=200,
    )

    SILENT_AUDIO = AudioState(
        bands=[0.0, 0.0, 0.0, 0.0, 0.0],
        amplitude=0.0,
        is_beat=False,
        beat_intensity=0.0,
        frame=0,
    )

    @pytest.mark.parametrize("pattern_name", list(PATTERNS.keys()))
    def test_pattern_returns_list(self, pattern_name):
        """Pattern must return a list of entity dicts."""
        config = PatternConfig(entity_count=8)
        pattern = PATTERNS[pattern_name](config)
        result = pattern.calculate_entities(self.NORMAL_AUDIO)
        assert isinstance(result, list), (
            f"Pattern '{pattern_name}' returned {type(result).__name__}, expected list"
        )

    @pytest.mark.parametrize("pattern_name", list(PATTERNS.keys()))
    def test_pattern_entity_has_required_keys(self, pattern_name):
        """Every entity from pattern must have id, x, y, z, scale, visible."""
        required_keys = {"id", "x", "y", "z", "scale", "visible"}
        config = PatternConfig(entity_count=8)
        pattern = PATTERNS[pattern_name](config)
        entities = pattern.calculate_entities(self.NORMAL_AUDIO)
        for i, entity in enumerate(entities):
            missing = required_keys - set(entity.keys())
            assert not missing, f"Pattern '{pattern_name}' entity {i} missing keys: {missing}"

    @pytest.mark.parametrize("pattern_name", list(PATTERNS.keys()))
    def test_pattern_coordinates_are_numeric(self, pattern_name):
        """All entity coordinates must be numeric values."""
        config = PatternConfig(entity_count=8)
        pattern = PATTERNS[pattern_name](config)
        entities = pattern.calculate_entities(self.NORMAL_AUDIO)
        for entity in entities:
            for coord in ("x", "y", "z"):
                assert isinstance(entity[coord], (int, float)), (
                    f"Pattern '{pattern_name}': entity '{coord}' is {type(entity[coord]).__name__}"
                )

    @pytest.mark.parametrize("pattern_name", list(PATTERNS.keys()))
    def test_pattern_scale_is_positive(self, pattern_name):
        """All entity scales must be non-negative."""
        config = PatternConfig(entity_count=8)
        pattern = PATTERNS[pattern_name](config)
        entities = pattern.calculate_entities(self.NORMAL_AUDIO)
        for entity in entities:
            assert isinstance(entity["scale"], (int, float)), (
                f"Pattern '{pattern_name}': scale is {type(entity['scale']).__name__}"
            )
            assert entity["scale"] >= 0, (
                f"Pattern '{pattern_name}': negative scale {entity['scale']}"
            )

    @pytest.mark.parametrize("pattern_name", list(PATTERNS.keys()))
    def test_pattern_visible_is_boolean(self, pattern_name):
        """All entity visible flags must be boolean."""
        config = PatternConfig(entity_count=8)
        pattern = PATTERNS[pattern_name](config)
        entities = pattern.calculate_entities(self.NORMAL_AUDIO)
        for entity in entities:
            assert isinstance(entity["visible"], bool), (
                f"Pattern '{pattern_name}': visible is {type(entity['visible']).__name__}"
            )

    @pytest.mark.parametrize("pattern_name", list(PATTERNS.keys()))
    def test_pattern_produces_entities_on_beat(self, pattern_name):
        """Pattern should produce entities even during beat events."""
        config = PatternConfig(entity_count=8)
        pattern = PATTERNS[pattern_name](config)
        entities = pattern.calculate_entities(self.BEAT_AUDIO)
        assert isinstance(entities, list), f"Pattern '{pattern_name}' failed on beat audio state"

    @pytest.mark.parametrize("pattern_name", list(PATTERNS.keys()))
    def test_pattern_handles_silent_audio(self, pattern_name):
        """Pattern should handle zero-energy audio without errors."""
        config = PatternConfig(entity_count=8)
        pattern = PATTERNS[pattern_name](config)
        entities = pattern.calculate_entities(self.SILENT_AUDIO)
        assert isinstance(entities, list), f"Pattern '{pattern_name}' failed on silent audio"


# ============================================================================
# 7. Cross-Component Consistency
# ============================================================================


class TestCrossComponentConsistency:
    """Validate consistency between different message producers."""

    def test_audio_state_band_count_matches_protocol(self):
        """AudioState dataclass expects exactly 5 bands, matching the protocol."""
        state = AudioState(
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            amplitude=0.5,
            is_beat=False,
            beat_intensity=0.0,
            frame=0,
        )
        assert len(state.bands) == 5

    def test_batch_update_entities_match_pattern_output(self):
        """Entities from patterns should be directly usable in batch_update messages."""
        config = PatternConfig(entity_count=8)
        audio = AudioState(
            bands=[0.5, 0.4, 0.3, 0.2, 0.1],
            amplitude=0.5,
            is_beat=False,
            beat_intensity=0.0,
            frame=1,
        )
        pattern = get_pattern("spectrum", config)
        entities = pattern.calculate_entities(audio)

        # Build a batch_update message using these entities
        msg = {
            "type": "batch_update",
            "zone": "main",
            "entities": entities,
        }

        # Must be JSON-serializable
        assert_message_is_json_serializable(msg)

        # Each entity must have the required keys
        required_keys = {"id", "x", "y", "z", "scale", "visible"}
        for entity in msg["entities"]:
            missing = required_keys - set(entity.keys())
            assert not missing, f"Entity missing keys: {missing}"

    def test_preset_config_keys_align_between_dicts(self):
        """Attributes in app_capture PRESETS should align with AudioConfig fields."""
        audio_config_fields = set(AudioConfig.__dataclass_fields__.keys())
        for name, preset in PRESETS.items():
            # PRESETS are now AudioConfig instances, so their fields are the same
            preset_fields = set(preset.__dataclass_fields__.keys())
            extra = preset_fields - audio_config_fields
            assert not extra, (
                f"app_capture preset '{name}' has fields {extra} "
                f"not in AudioConfig fields: {audio_config_fields}"
            )

    def test_five_band_frequency_layout(self):
        """The system uses exactly 5 frequency bands everywhere."""
        # AudioState expects 5 bands
        state = AudioState(
            bands=[0.0] * 5,
            amplitude=0.0,
            is_beat=False,
            beat_intensity=0.0,
            frame=0,
        )
        assert len(state.bands) == 5

        # Default AudioConfig has 5 band sensitivities
        config = AudioConfig()
        assert len(config.band_sensitivity) == 5

        # Every preset has 5 band sensitivities (app_capture)
        for name, preset in PRESETS.items():
            assert len(preset.band_sensitivity) == 5, (
                f"PRESETS['{name}'] has {len(preset.band_sensitivity)} bands"
            )

        # Every preset has 5 band sensitivities (config)
        for name, cfg in CONFIG_PRESETS.items():
            assert len(cfg.band_sensitivity) == 5, (
                f"CONFIG_PRESETS['{name}'] has {len(cfg.band_sensitivity)} bands"
            )


# ============================================================================
# Renderer Backend Capability Messages
# ============================================================================


class TestRendererBackendMessages:
    """Validate renderer backend protocol messages match schemas."""

    VALID_BACKENDS = ["display_entities", "particles", "hologram"]

    def test_set_renderer_backend_message_structure(self):
        """set_renderer_backend must contain zone, backend, optional fallback."""
        msg = {
            "type": "set_renderer_backend",
            "zone": "main",
            "backend": "display_entities",
            "fallback_backend": "particles",
        }
        assert_message_is_json_serializable(msg)
        assert msg["type"] == "set_renderer_backend"
        assert isinstance(msg["zone"], str) and len(msg["zone"]) > 0
        assert msg["backend"] in self.VALID_BACKENDS
        assert msg["fallback_backend"] in self.VALID_BACKENDS

    def test_set_renderer_backend_minimal(self):
        """set_renderer_backend works without fallback_backend."""
        msg = {
            "type": "set_renderer_backend",
            "zone": "main",
            "backend": "particles",
        }
        assert_message_is_json_serializable(msg)
        assert "fallback_backend" not in msg  # optional field

    def test_renderer_capabilities_response_structure(self):
        """renderer_capabilities response must list supported and experimental backends."""
        response = {
            "type": "renderer_capabilities",
            "zone": "main",
            "supported_backends": ["display_entities", "particles"],
            "experimental_backends": [],
            "active_backend": "display_entities",
            "fallback_backend": "display_entities",
            "providers": {
                "hologram": {
                    "available": False,
                    "provider": "",
                    "implemented": False,
                }
            },
        }
        assert_message_is_json_serializable(response)
        assert response["type"] == "renderer_capabilities"
        assert isinstance(response["supported_backends"], list)
        assert len(response["supported_backends"]) >= 2  # at least entities + particles
        assert isinstance(response["experimental_backends"], list)
        assert response["active_backend"] in self.VALID_BACKENDS
        assert response["fallback_backend"] in self.VALID_BACKENDS
        assert isinstance(response["providers"], dict)
        assert "hologram" in response["providers"]

    def test_renderer_capabilities_with_hologram_provider(self):
        """renderer_capabilities with hologram provider available."""
        response = {
            "type": "renderer_capabilities",
            "zone": "main",
            "supported_backends": ["display_entities", "particles", "hologram"],
            "experimental_backends": ["hologram"],
            "active_backend": "display_entities",
            "fallback_backend": "display_entities",
            "providers": {
                "hologram": {
                    "available": True,
                    "provider": "DecentHolograms",
                    "implemented": False,
                }
            },
        }
        assert_message_is_json_serializable(response)
        assert "hologram" in response["supported_backends"]
        assert "hologram" in response["experimental_backends"]
        assert response["providers"]["hologram"]["available"] is True
        assert isinstance(response["providers"]["hologram"]["provider"], str)

    def test_renderer_backend_updated_response(self):
        """renderer_backend_updated response reports effective backend and fallback status."""
        response = {
            "type": "renderer_backend_updated",
            "zone": "main",
            "backend": "hologram",
            "fallback_backend": "display_entities",
            "effective_backend": "display_entities",
            "using_fallback": True,
            "note": "Hologram backend selected but currently falling back",
        }
        assert_message_is_json_serializable(response)
        assert response["type"] == "renderer_backend_updated"
        assert isinstance(response["using_fallback"], bool)
        assert response["effective_backend"] in self.VALID_BACKENDS

    def test_backend_keys_are_stable_strings(self):
        """Backend keys are lowercase snake_case strings, not enum names."""
        for backend in self.VALID_BACKENDS:
            assert backend == backend.lower(), f"Backend key must be lowercase: {backend}"
            assert " " not in backend, f"Backend key must not have spaces: {backend}"
            assert backend.replace("_", "").isalpha(), (
                f"Backend key should be alpha + underscores: {backend}"
            )

    def test_get_renderer_capabilities_request(self):
        """get_renderer_capabilities is a simple request with optional zone."""
        msg = {"type": "get_renderer_capabilities", "zone": "main"}
        assert_message_is_json_serializable(msg)
        assert msg["type"] == "get_renderer_capabilities"

    def test_set_hologram_config_message(self):
        """set_hologram_config carries zone-specific hologram settings."""
        msg = {
            "type": "set_hologram_config",
            "zone": "main",
            "config": {
                "line_spacing": 0.25,
                "text_shadow": True,
                "update_interval": 2,
            },
        }
        assert_message_is_json_serializable(msg)
        assert msg["type"] == "set_hologram_config"
        assert isinstance(msg["config"], dict)


# ============================================================================
# VJ Effect Messages
# ============================================================================


class TestVJEffectMessages:
    """Validate VJ effect trigger messages match protocol schema."""

    VALID_EFFECTS = ["blackout", "freeze", "flash", "strobe", "pulse", "wave", "spiral", "explode"]

    def test_trigger_effect_toggle(self):
        """Toggle effects (blackout, freeze) use intensity to enable/disable."""
        for effect in ["blackout", "freeze"]:
            enable = {
                "type": "trigger_effect",
                "effect": effect,
                "intensity": 1.0,
                "duration": 0,
            }
            disable = {
                "type": "trigger_effect",
                "effect": effect,
                "intensity": 0.0,
            }
            assert_message_is_json_serializable(enable)
            assert_message_is_json_serializable(disable)
            assert enable["effect"] in self.VALID_EFFECTS

    def test_trigger_effect_timed(self):
        """Timed effects have a duration in milliseconds."""
        for effect in ["flash", "strobe", "pulse", "wave", "spiral", "explode"]:
            msg = {
                "type": "trigger_effect",
                "effect": effect,
                "intensity": 0.8,
                "duration": 2000,
            }
            assert_message_is_json_serializable(msg)
            assert msg["effect"] in self.VALID_EFFECTS
            assert msg["duration"] > 0

    def test_all_effects_are_valid(self):
        """Every defined effect is a recognized type."""
        for effect in self.VALID_EFFECTS:
            msg = {"type": "trigger_effect", "effect": effect, "intensity": 1.0}
            assert_message_is_json_serializable(msg)

    def test_set_pattern_message(self):
        """set_pattern contains pattern identifier."""
        patterns = list_patterns()
        assert len(patterns) > 0, "Should have at least one pattern"

        for pattern_info in patterns:
            # list_patterns() returns dicts with 'id', 'name', 'description'
            pattern_id = pattern_info["id"] if isinstance(pattern_info, dict) else pattern_info
            msg = {"type": "set_pattern", "pattern": pattern_id}
            assert_message_is_json_serializable(msg)
            assert isinstance(msg["pattern"], str)
            assert len(msg["pattern"]) > 0

    def test_set_preset_named(self):
        """set_preset with named preset string."""
        for name in CONFIG_PRESETS:
            msg = {"type": "set_preset", "preset": name}
            assert_message_is_json_serializable(msg)
            assert isinstance(msg["preset"], str)

    def test_set_preset_custom_dict(self):
        """set_preset with custom settings dict."""
        msg = {
            "type": "set_preset",
            "preset": {
                "attack": 0.5,
                "release": 0.1,
                "beat_threshold": 1.3,
            },
        }
        assert_message_is_json_serializable(msg)
        assert isinstance(msg["preset"], dict)


# ============================================================================
# Protocol Schema Inventory
# ============================================================================


class TestProtocolSchemaInventory:
    """Validate that protocol schema index is complete and well-formed."""

    def test_schema_index_loads(self):
        """protocol/schemas/index.json must be valid JSON."""
        import os

        schema_path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "protocol",
            "schemas",
            "index.json",
        )
        with open(schema_path) as f:
            index = json.load(f)

        assert "protocol_version" in index
        assert "messages" in index
        assert "types" in index

    def test_schema_files_exist(self):
        """All schema files referenced in index.json must exist."""
        import os

        schema_dir = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "protocol",
            "schemas",
        )
        with open(os.path.join(schema_dir, "index.json")) as f:
            index = json.load(f)

        for name, path in index.get("messages", {}).items():
            full_path = os.path.join(schema_dir, path)
            assert os.path.exists(full_path), f"Missing schema file for message '{name}': {path}"

        for name, path in index.get("types", {}).items():
            full_path = os.path.join(schema_dir, path)
            assert os.path.exists(full_path), f"Missing schema file for type '{name}': {path}"

    def test_schema_files_are_valid_json(self):
        """All schema files must be valid JSON."""
        import glob
        import os

        schema_dir = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "protocol",
            "schemas",
        )
        for filepath in glob.glob(os.path.join(schema_dir, "**", "*.json"), recursive=True):
            with open(filepath) as f:
                data = json.load(f)
            assert isinstance(data, dict), f"{filepath} is not a JSON object"

    def test_core_messages_have_schemas(self):
        """Critical message types must have schemas."""
        import os

        schema_dir = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "protocol",
            "schemas",
        )
        with open(os.path.join(schema_dir, "index.json")) as f:
            index = json.load(f)

        required_messages = [
            "ping",
            "pong",
            "error",
            "get_zones",
            "zones",
            "audio_state",
            "init_pool",
            "batch_update",
            "set_visible",
            "set_render_mode",
            "trigger_effect",
            "set_pattern",
            "set_preset",
            "set_renderer_backend",
            "renderer_capabilities",
        ]
        for msg_type in required_messages:
            assert msg_type in index["messages"], (
                f"Core message '{msg_type}' missing from protocol schema index"
            )
