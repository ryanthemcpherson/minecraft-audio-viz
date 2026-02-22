"""Tests for audio frame sanitization and BPM stabilization."""

# We can't import VJServer._stabilize_bpm directly (it's an instance method),
# so we import the class and instantiate minimally for BPM tests — but since
# VJServer.__init__ starts servers, we instead import and test the logic
# through a lightweight approach: import the module and call the unbound method.
import vj_server.vj_server as vj_mod
from vj_server.vj_server import _clamp_finite, _sanitize_audio_frame

# ── conftest helpers ──
from .conftest import FakeDJConnection, make_audio_frame

# ============================================================================
# _clamp_finite
# ============================================================================


class TestClampFinite:
    def test_normal_value_in_range(self):
        assert _clamp_finite(0.5, 0.0, 1.0, 0.0) == 0.5

    def test_clamps_above_max(self):
        assert _clamp_finite(2.0, 0.0, 1.0, 0.0) == 1.0

    def test_clamps_below_min(self):
        assert _clamp_finite(-1.0, 0.0, 1.0, 0.5) == 0.0

    def test_nan_returns_default(self):
        assert _clamp_finite(float("nan"), 0.0, 1.0, 0.42) == 0.42

    def test_inf_returns_default(self):
        assert _clamp_finite(float("inf"), 0.0, 1.0, 0.42) == 0.42

    def test_negative_inf_returns_default(self):
        assert _clamp_finite(float("-inf"), 0.0, 1.0, 0.42) == 0.42

    def test_string_returns_default(self):
        assert _clamp_finite("hello", 0.0, 1.0, 0.99) == 0.99

    def test_none_returns_default(self):
        assert _clamp_finite(None, 0.0, 1.0, 0.99) == 0.99

    def test_int_value(self):
        assert _clamp_finite(1, 0.0, 5.0, 0.0) == 1.0


# ============================================================================
# _sanitize_audio_frame
# ============================================================================


class TestSanitizeAudioFrame:
    def test_valid_frame_passes_through(self):
        frame = make_audio_frame()
        result = _sanitize_audio_frame(frame)
        assert result["bands"] == [0.5, 0.4, 0.3, 0.2, 0.1]
        assert result["peak"] == 0.7
        assert result["beat"] is False
        assert result["bpm"] == 128.0
        assert result["seq"] == 1

    def test_clamps_band_values_to_0_1(self):
        frame = make_audio_frame(bands=[1.5, -0.3, 0.5, 2.0, 0.0])
        result = _sanitize_audio_frame(frame)
        assert result["bands"] == [1.0, 0.0, 0.5, 1.0, 0.0]

    def test_fills_short_bands_list(self):
        frame = make_audio_frame(bands=[0.5, 0.3])
        result = _sanitize_audio_frame(frame)
        assert len(result["bands"]) == 5
        assert result["bands"][:2] == [0.5, 0.3]
        assert result["bands"][2:] == [0.0, 0.0, 0.0]

    def test_truncates_long_bands_list(self):
        frame = make_audio_frame(bands=[0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7])
        result = _sanitize_audio_frame(frame)
        assert len(result["bands"]) == 5

    def test_missing_bands_defaults_to_zeros(self):
        frame = make_audio_frame()
        del frame["bands"]
        result = _sanitize_audio_frame(frame)
        assert result["bands"] == [0.0] * 5

    def test_non_list_bands_defaults_to_zeros(self):
        frame = make_audio_frame(bands="not a list")
        result = _sanitize_audio_frame(frame)
        assert result["bands"] == [0.0] * 5

    def test_empty_dict_returns_all_defaults(self):
        result = _sanitize_audio_frame({})
        assert result["bands"] == [0.0] * 5
        assert result["peak"] == 0.0
        assert result["beat"] is False
        assert result["bpm"] == 120.0  # default for missing bpm
        assert result["seq"] == 0
        assert result["i_bass"] == 0.0
        assert result["i_kick"] is False

    def test_nan_in_bands_replaced_with_zero(self):
        frame = make_audio_frame(bands=[float("nan"), 0.5, 0.3, 0.2, 0.1])
        result = _sanitize_audio_frame(frame)
        assert result["bands"][0] == 0.0

    def test_peak_clamped_to_5(self):
        frame = make_audio_frame(peak=10.0)
        result = _sanitize_audio_frame(frame)
        assert result["peak"] == 5.0

    def test_bpm_clamped_to_300(self):
        frame = make_audio_frame(bpm=999.0)
        result = _sanitize_audio_frame(frame)
        assert result["bpm"] == 300.0

    def test_negative_bpm_clamped_to_zero(self):
        frame = make_audio_frame(bpm=-50.0)
        result = _sanitize_audio_frame(frame)
        assert result["bpm"] == 0.0

    def test_seq_non_numeric_defaults_to_zero(self):
        frame = make_audio_frame(seq="bad")
        result = _sanitize_audio_frame(frame)
        assert result["seq"] == 0

    def test_seq_negative_clamped_to_zero(self):
        frame = make_audio_frame(seq=-5)
        result = _sanitize_audio_frame(frame)
        assert result["seq"] == 0

    def test_beat_truthy_values(self):
        result = _sanitize_audio_frame(make_audio_frame(beat=1))
        assert result["beat"] is True

    def test_malformed_types_dont_crash(self):
        """Feed completely wrong types for every field."""
        garbage = {
            "bands": 42,
            "peak": "loud",
            "beat": "yes",
            "beat_i": [],
            "bpm": None,
            "tempo_conf": {},
            "beat_phase": True,
            "seq": "abc",
            "i_bass": object(),
            "i_kick": 0,
            "ts": 12345,
        }
        result = _sanitize_audio_frame(garbage)
        # Should not raise, and all numeric fields should have safe defaults
        assert len(result["bands"]) == 5
        assert isinstance(result["peak"], float)
        assert isinstance(result["bpm"], float)


# ============================================================================
# _stabilize_bpm (method on VJServer)
# ============================================================================


class TestStabilizeBpm:
    """Test BPM stabilization by calling the unbound method directly.

    VJServer._stabilize_bpm is a regular method that only reads/writes
    dj.bpm, so we pass a FakeDJConnection.
    """

    def _call(self, dj: FakeDJConnection, raw_bpm: float) -> float:
        # Call the unbound method with a dummy self (it only uses dj arg)
        return vj_mod.VJServer._stabilize_bpm(None, dj, raw_bpm)

    def test_stable_bpm_stays_close(self):
        dj = FakeDJConnection(bpm=128.0)
        result = self._call(dj, 128.0)
        assert 126.0 <= result <= 130.0

    def test_double_time_corrected(self):
        """If raw BPM is ~256 (double of 128), should correct toward 128."""
        dj = FakeDJConnection(bpm=128.0)
        result = self._call(dj, 256.0)
        assert result < 200.0  # Should pick half-time candidate

    def test_half_time_corrected(self):
        """If raw BPM is ~64 (half of 128), should correct toward 128."""
        dj = FakeDJConnection(bpm=128.0)
        result = self._call(dj, 64.0)
        assert result > 80.0  # Should pick double-time candidate

    def test_nan_returns_previous_bpm(self):
        dj = FakeDJConnection(bpm=140.0)
        result = self._call(dj, float("nan"))
        assert result == 140.0

    def test_inf_returns_previous_bpm(self):
        dj = FakeDJConnection(bpm=140.0)
        result = self._call(dj, float("inf"))
        assert result == 140.0

    def test_output_always_in_range(self):
        """Output BPM should always be in [60, 200]."""
        dj = FakeDJConnection(bpm=120.0)
        for raw in [0, 30, 50, 100, 150, 300, 500, -10]:
            result = self._call(dj, raw)
            assert 60.0 <= result <= 200.0, f"raw={raw} produced out-of-range {result}"

    def test_smoothing_doesnt_jump(self):
        """Large BPM changes should be smoothed, not instantaneous."""
        dj = FakeDJConnection(bpm=120.0)
        result = self._call(dj, 180.0)
        # Should not jump all the way to 180 in one step
        assert result < 170.0
