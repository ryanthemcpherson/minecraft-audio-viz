"""Tests for vj_server.config — audio presets, AudioConfig roundtrip, defaults."""

from vj_server.config import PRESETS, AudioConfig, get_preset, list_presets

# ============================================================================
# Preset field-range validation
# ============================================================================


class TestPresetFieldRanges:
    """Every preset must have valid, in-range fields."""

    def test_all_presets_attack_in_range(self):
        for name, cfg in PRESETS.items():
            assert 0.0 <= cfg.attack <= 1.0, f"{name}.attack={cfg.attack}"

    def test_all_presets_release_in_range(self):
        for name, cfg in PRESETS.items():
            assert 0.0 <= cfg.release <= 1.0, f"{name}.release={cfg.release}"

    def test_all_presets_beat_threshold_above_one(self):
        for name, cfg in PRESETS.items():
            assert cfg.beat_threshold > 1.0, f"{name}.beat_threshold={cfg.beat_threshold}"

    def test_all_presets_band_sensitivity_length(self):
        for name, cfg in PRESETS.items():
            assert len(cfg.band_sensitivity) == 5, (
                f"{name}.band_sensitivity has {len(cfg.band_sensitivity)} elements"
            )

    def test_all_presets_band_sensitivity_positive(self):
        for name, cfg in PRESETS.items():
            for i, s in enumerate(cfg.band_sensitivity):
                assert s > 0.0, f"{name}.band_sensitivity[{i}]={s}"

    def test_all_presets_beat_sensitivity_positive(self):
        for name, cfg in PRESETS.items():
            assert cfg.beat_sensitivity > 0.0, f"{name}.beat_sensitivity={cfg.beat_sensitivity}"

    def test_all_presets_agc_max_gain_positive(self):
        for name, cfg in PRESETS.items():
            assert cfg.agc_max_gain > 0.0, f"{name}.agc_max_gain={cfg.agc_max_gain}"

    def test_all_presets_bass_weight_in_range(self):
        for name, cfg in PRESETS.items():
            assert 0.0 <= cfg.bass_weight <= 1.0, f"{name}.bass_weight={cfg.bass_weight}"


# ============================================================================
# AudioConfig roundtrip via to_dict / from_dict
# ============================================================================


class TestAudioConfigRoundtrip:
    def test_default_roundtrip(self):
        original = AudioConfig()
        rebuilt = AudioConfig.from_dict(original.to_dict())
        assert rebuilt.attack == original.attack
        assert rebuilt.release == original.release
        assert rebuilt.beat_threshold == original.beat_threshold
        assert rebuilt.band_sensitivity == original.band_sensitivity

    def test_custom_values_roundtrip(self):
        original = AudioConfig(
            attack=0.9,
            release=0.01,
            beat_threshold=2.5,
            beat_sensitivity=0.3,
            agc_max_gain=12.0,
            bass_weight=0.1,
            band_sensitivity=[2.0, 1.5, 1.0, 0.5, 0.2],
            auto_calibrate=False,
        )
        rebuilt = AudioConfig.from_dict(original.to_dict())
        assert rebuilt.attack == 0.9
        assert rebuilt.release == 0.01
        assert rebuilt.beat_threshold == 2.5
        assert rebuilt.band_sensitivity == [2.0, 1.5, 1.0, 0.5, 0.2]
        assert rebuilt.auto_calibrate is False

    def test_from_dict_ignores_unknown_keys(self):
        data = AudioConfig().to_dict()
        data["not_a_real_field"] = 42
        rebuilt = AudioConfig.from_dict(data)
        assert not hasattr(rebuilt, "not_a_real_field")

    def test_to_dict_returns_plain_dict(self):
        d = AudioConfig().to_dict()
        assert isinstance(d, dict)
        assert "attack" in d
        assert "band_sensitivity" in d


# ============================================================================
# Default preset values are sane
# ============================================================================


class TestDefaultPresetValues:
    def test_auto_preset_exists(self):
        assert "auto" in PRESETS

    def test_get_preset_returns_auto_for_unknown(self):
        result = get_preset("nonexistent_genre_xyz")
        auto = PRESETS["auto"]
        assert result.attack == auto.attack
        assert result.beat_threshold == auto.beat_threshold

    def test_get_preset_case_insensitive(self):
        upper = get_preset("EDM")
        lower = get_preset("edm")
        assert upper.attack == lower.attack

    def test_list_presets_non_empty(self):
        names = list_presets()
        assert len(names) >= 4
        assert "auto" in names
        assert "edm" in names

    def test_default_audio_config_matches_auto(self):
        default = AudioConfig()
        auto = PRESETS["auto"]
        assert default.attack == auto.attack
        assert default.release == auto.release
        assert default.beat_threshold == auto.beat_threshold
