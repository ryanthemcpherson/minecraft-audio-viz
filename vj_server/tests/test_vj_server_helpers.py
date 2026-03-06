"""Tests for VJ server helper functions — phase assist and audio frame sanitization edge cases."""

import time

import vj_server.vj_server as vj_mod

from .conftest import FakeDJConnection, make_audio_frame

# ============================================================================
# _apply_phase_beat_assist
# ============================================================================


class TestPhaseAssist:
    """Test phase-assisted beat firing via _apply_phase_beat_assist."""

    def _call(self, dj: FakeDJConnection, is_beat: bool, beat_intensity: float) -> tuple:
        return vj_mod.VJServer._apply_phase_beat_assist(None, dj, is_beat, beat_intensity)

    def test_real_beat_passes_through(self):
        """When is_beat is True, it should pass through and update last time."""
        dj = FakeDJConnection(
            bpm=128.0, tempo_confidence=0.9, beat_phase=0.0, phase_assist_last_time=0.0
        )
        result_beat, result_intensity = self._call(dj, True, 0.8)
        assert result_beat is True
        assert result_intensity == 0.8
        assert dj.phase_assist_last_time > 0.0

    def test_fires_when_phase_near_boundary(self, monkeypatch):
        """Phase assist should fire when phase is near 0 and confidence is high."""
        fake_now = 100.0
        monkeypatch.setattr(time, "time", lambda: fake_now)

        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.80,
            beat_phase=0.03,  # near boundary (< 0.08)
            phase_assist_last_time=0.0,  # never fired before
        )
        result_beat, result_intensity = self._call(dj, False, 0.0)
        assert result_beat is True
        assert result_intensity > 0.0

    def test_fires_when_phase_near_one(self, monkeypatch):
        """Phase > 0.92 should also be considered near boundary."""
        fake_now = 100.0
        monkeypatch.setattr(time, "time", lambda: fake_now)

        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.80,
            beat_phase=0.95,  # near end of cycle
            phase_assist_last_time=0.0,
        )
        result_beat, _ = self._call(dj, False, 0.0)
        assert result_beat is True

    def test_no_fire_when_confidence_low(self, monkeypatch):
        """Phase assist should not fire when tempo_confidence < 0.60."""
        monkeypatch.setattr(time, "time", lambda: 100.0)

        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.30,  # below 0.60 threshold
            beat_phase=0.02,
            phase_assist_last_time=0.0,
        )
        result_beat, _ = self._call(dj, False, 0.0)
        assert result_beat is False

    def test_no_fire_when_phase_mid_cycle(self, monkeypatch):
        """Phase assist should not fire when phase is in the middle (e.g., 0.5)."""
        monkeypatch.setattr(time, "time", lambda: 100.0)

        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.90,
            beat_phase=0.50,  # mid cycle — not near boundary
            phase_assist_last_time=0.0,
        )
        result_beat, _ = self._call(dj, False, 0.0)
        assert result_beat is False

    def test_cooldown_prevents_double_fire(self, monkeypatch):
        """Phase assist should not fire twice within the cooldown window (60% of beat period)."""
        # At 120 BPM, beat_period = 0.5s, cooldown = 0.3s
        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.90,
            beat_phase=0.03,
            phase_assist_last_time=0.0,
        )

        # First call — should fire
        monkeypatch.setattr(time, "time", lambda: 100.0)
        beat1, _ = self._call(dj, False, 0.0)
        assert beat1 is True

        # Second call only 0.1s later — within cooldown (0.3s)
        monkeypatch.setattr(time, "time", lambda: 100.1)
        dj.beat_phase = 0.03  # still near boundary
        beat2, _ = self._call(dj, False, 0.0)
        assert beat2 is False

    def test_fires_after_cooldown_expires(self, monkeypatch):
        """Phase assist should fire again once cooldown window has passed."""
        dj = FakeDJConnection(
            bpm=120.0,
            tempo_confidence=0.90,
            beat_phase=0.03,
            phase_assist_last_time=0.0,
        )

        # First fire
        monkeypatch.setattr(time, "time", lambda: 100.0)
        beat1, _ = self._call(dj, False, 0.0)
        assert beat1 is True

        # After cooldown (0.5s beat period * 0.60 = 0.30s)
        monkeypatch.setattr(time, "time", lambda: 100.4)
        dj.beat_phase = 0.03
        beat2, _ = self._call(dj, False, 0.0)
        assert beat2 is True

    def test_no_fire_when_bpm_too_low(self, monkeypatch):
        """Phase assist should not fire when BPM < 60."""
        monkeypatch.setattr(time, "time", lambda: 100.0)

        dj = FakeDJConnection(
            bpm=50.0,  # below 60
            tempo_confidence=0.90,
            beat_phase=0.03,
            phase_assist_last_time=0.0,
        )
        result_beat, _ = self._call(dj, False, 0.0)
        assert result_beat is False


# ============================================================================
# Audio frame sanitization — missing required keys
# ============================================================================


class TestAudioFrameSanitization:
    """Supplement test_audio_pipeline.py with edge cases for required key rejection."""

    def test_completely_empty_frame(self):
        from vj_server.models import _sanitize_audio_frame

        result = _sanitize_audio_frame({})
        assert result["bands"] == [0.0] * 5
        assert result["bpm"] == 120.0
        assert result["beat"] is False
        assert result["seq"] == 0

    def test_missing_peak_defaults(self):
        from vj_server.models import _sanitize_audio_frame

        frame = make_audio_frame()
        del frame["peak"]
        result = _sanitize_audio_frame(frame)
        assert result["peak"] == 0.0

    def test_missing_bpm_defaults_to_120(self):
        from vj_server.models import _sanitize_audio_frame

        frame = make_audio_frame()
        del frame["bpm"]
        result = _sanitize_audio_frame(frame)
        assert result["bpm"] == 120.0

    def test_none_values_for_all_numeric_fields(self):
        from vj_server.models import _sanitize_audio_frame

        frame = {
            "bands": None,
            "peak": None,
            "beat": None,
            "beat_i": None,
            "bpm": None,
            "tempo_conf": None,
            "beat_phase": None,
            "seq": None,
            "i_bass": None,
            "i_kick": None,
            "ts": None,
        }
        result = _sanitize_audio_frame(frame)
        assert len(result["bands"]) == 5
        assert isinstance(result["bpm"], float)
        assert isinstance(result["seq"], int)
