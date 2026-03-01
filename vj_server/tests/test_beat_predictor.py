"""Tests for vj_server.beat_predictor — construction, tempo tracking, phase."""

import time

from vj_server.beat_predictor import BeatPredictor

# ============================================================================
# Construction and boundary BPM ranges
# ============================================================================


class TestConstruction:
    def test_default_construction(self):
        bp = BeatPredictor()
        assert bp.tempo_bpm == 120.0
        assert bp.tempo_confidence == 0.0
        assert 0.0 <= bp.beat_phase <= 1.0

    def test_custom_bpm_range(self):
        bp = BeatPredictor(min_bpm=80.0, max_bpm=180.0)
        assert bp.min_bpm == 80.0
        assert bp.max_bpm == 180.0

    def test_min_equals_max_does_not_crash(self):
        bp = BeatPredictor(min_bpm=120.0, max_bpm=120.0)
        assert bp.tempo_bpm == 120.0

    def test_reset_clears_state(self):
        bp = BeatPredictor()
        # Simulate some usage then reset
        bp._tempo_confidence = 0.8
        bp._tempo_bpm = 140.0
        bp.reset()
        assert bp.tempo_bpm == 120.0
        assert bp.tempo_confidence == 0.0


# ============================================================================
# process_onset with synthetic beats converges toward known BPM
# ============================================================================


class TestProcessOnset:
    def test_consistent_beats_increase_confidence(self, monkeypatch):
        """Feed onsets at exactly 120 BPM (0.5s apart) — confidence should rise."""
        bp = BeatPredictor(min_bpm=60.0, max_bpm=200.0)
        fake_time = [0.0]

        def mock_time():
            return fake_time[0]

        monkeypatch.setattr(time, "time", mock_time)
        bp._last_phase_update = 0.0

        interval = 0.5  # 120 BPM
        for i in range(20):
            fake_time[0] = i * interval
            bp.process_onset(is_onset=True, onset_strength=1.0)

        assert bp.tempo_confidence > 0.0

    def test_tempo_converges_toward_input_bpm(self, monkeypatch):
        """After many consistent onsets at 100 BPM, tempo estimate should be near 100."""
        bp = BeatPredictor(min_bpm=60.0, max_bpm=200.0, tempo_smoothing=0.8)
        fake_time = [0.0]

        def mock_time():
            return fake_time[0]

        monkeypatch.setattr(time, "time", mock_time)
        bp._last_phase_update = 0.0

        interval = 0.6  # 100 BPM
        for i in range(40):
            fake_time[0] = i * interval
            bp.process_onset(is_onset=True, onset_strength=1.0)

        # Should be within 10 BPM of target
        assert abs(bp.tempo_bpm - 100.0) < 10.0, f"tempo={bp.tempo_bpm}"

    def test_weak_onsets_ignored(self, monkeypatch):
        """Onsets below the strength threshold (0.3) should not register."""
        bp = BeatPredictor()
        fake_time = [0.0]

        def mock_time():
            return fake_time[0]

        monkeypatch.setattr(time, "time", mock_time)
        bp._last_phase_update = 0.0

        for i in range(20):
            fake_time[0] = i * 0.5
            bp.process_onset(is_onset=True, onset_strength=0.1)

        # Confidence should remain very low — weak onsets are filtered
        assert bp.tempo_confidence < 0.1

    def test_no_onset_does_not_crash(self, monkeypatch):
        """Calling process_onset with is_onset=False should be a no-op."""
        bp = BeatPredictor()
        fake_time = [0.0]

        def mock_time():
            return fake_time[0]

        monkeypatch.setattr(time, "time", mock_time)
        bp._last_phase_update = 0.0

        for i in range(10):
            fake_time[0] = i * 0.5
            bp.process_onset(is_onset=False, onset_strength=1.0)

        assert bp.tempo_confidence == 0.0


# ============================================================================
# beat_phase stays in [0, 1]
# ============================================================================


class TestBeatPhase:
    def test_phase_always_in_range(self, monkeypatch):
        """After many updates the phase must remain in [0, 1)."""
        bp = BeatPredictor()
        fake_time = [0.0]

        def mock_time():
            return fake_time[0]

        monkeypatch.setattr(time, "time", mock_time)
        bp._last_phase_update = 0.0

        for i in range(200):
            fake_time[0] = i * 0.05  # 50ms steps
            bp.process_onset(is_onset=(i % 10 == 0), onset_strength=1.0)
            assert 0.0 <= bp.beat_phase < 1.0, f"step {i}: phase={bp.beat_phase}"

    def test_phase_advances_with_time(self, monkeypatch):
        """Phase should increase as time passes (within a single beat)."""
        bp = BeatPredictor()
        fake_time = [0.0]

        def mock_time():
            return fake_time[0]

        monkeypatch.setattr(time, "time", mock_time)
        bp._last_phase_update = 0.0
        bp._beat_period = 1.0  # 60 BPM — 1 second per beat

        fake_time[0] = 0.0
        bp._update_phase(0.0)
        phase_0 = bp.beat_phase

        fake_time[0] = 0.3
        bp._update_phase(0.3)
        phase_1 = bp.beat_phase

        assert phase_1 > phase_0


# ============================================================================
# Tempo confidence increases with consistent beats
# ============================================================================


class TestTempoConfidence:
    def test_confidence_monotonically_increases_with_consistent_input(self, monkeypatch):
        """With perfectly consistent onsets, confidence should generally trend up."""
        bp = BeatPredictor(min_bpm=60.0, max_bpm=200.0)
        fake_time = [0.0]

        def mock_time():
            return fake_time[0]

        monkeypatch.setattr(time, "time", mock_time)
        bp._last_phase_update = 0.0

        interval = 0.5  # 120 BPM
        confidences = []
        for i in range(30):
            fake_time[0] = i * interval
            bp.process_onset(is_onset=True, onset_strength=1.0)
            if i >= 5:
                confidences.append(bp.tempo_confidence)

        # Overall trend should be upward: last > first
        assert confidences[-1] > confidences[0], (
            f"confidence did not increase: first={confidences[0]}, last={confidences[-1]}"
        )


# ============================================================================
# get_prediction returns valid structure
# ============================================================================


class TestGetPrediction:
    def test_prediction_fields(self, monkeypatch):
        bp = BeatPredictor()
        fake_time = [10.0]

        def mock_time():
            return fake_time[0]

        monkeypatch.setattr(time, "time", mock_time)
        bp._last_phase_update = 10.0

        pred = bp.get_prediction()
        assert pred.tempo_bpm == 120.0
        assert pred.beats_per_bar == 4
        assert 0.0 <= pred.beat_phase <= 1.0
        assert pred.time_to_next_beat >= 0.0
        assert pred.next_beat_time >= 10.0
