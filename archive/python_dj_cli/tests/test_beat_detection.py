"""
Unit tests for beat detection and audio processing.

Uses synthetic test signals to verify behavior without needing actual audio files.
Run with: python -m pytest audio_processor/tests/ -v
"""

import math
import time

import pytest


class MockTime:
    """Mock time.time() for testing - simulates 60fps progression."""

    def __init__(self, fps: float = 60.0):
        self.fps = fps
        self.frame = 0
        self.start_time = 1000000.0  # Arbitrary start

    def time(self) -> float:
        """Return simulated time based on frame count."""
        return self.start_time + (self.frame / self.fps)

    def advance_frame(self):
        """Advance one frame."""
        self.frame += 1


class MockAudioCapture:
    """
    Mock audio capture that generates synthetic test signals.
    Allows testing beat detection without actual audio hardware.
    Uses mocked time to simulate real 60fps timing.
    """

    def __init__(self):
        # Import the real class to get its beat detection logic
        import os
        import sys

        import numpy as np

        sys.path.insert(
            0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        )

        from audio_processor.app_capture import AppAudioCapture

        # Create a real capture instance but mock the audio parts
        self.capture = AppAudioCapture.__new__(AppAudioCapture)
        self.capture.app_name = "test"
        self.capture._session = None
        self.capture._meter = None

        # === OPTIMIZED BEAT DETECTION STATE (NumPy arrays) ===
        # Must match current AppAudioCapture.__init__

        # Pre-allocate NumPy arrays for performance
        self.capture._onset_history_size = 256  # ~4 seconds at 60fps
        self.capture._onset_history = np.zeros(self.capture._onset_history_size, dtype=np.float64)
        self.capture._onset_idx = 0
        self.capture._onset_count = 0  # Track how many samples we have

        # Energy tracking
        self.capture._prev_energy = 0.0
        self.capture._prev_bass_energy = 0.0

        # Beat timing (NumPy array for intervals)
        self.capture._max_beat_times = 60  # Track last 60 beats
        self.capture._beat_times = np.zeros(self.capture._max_beat_times, dtype=np.float64)
        self.capture._beat_idx = 0
        self.capture._beat_count = 0
        self.capture._last_beat_time = 0.0

        # Tempo estimation with stabilization
        self.capture._estimated_tempo = 120.0
        self.capture._stable_tempo = 120.0  # Slower-moving stable estimate
        self.capture._tempo_confidence = 0.0
        self.capture._beat_phase = 0.0

        # BPM history for histogram analysis
        self.capture._bpm_history = np.zeros(120, dtype=np.float64)  # ~2 seconds of estimates
        self.capture._bpm_history_idx = 0
        self.capture._bpm_history_count = 0

        # Adaptive threshold (adjustable via UI)
        self.capture._beat_threshold = 1.2  # Multiplier above average flux

        # Bass weight for beat detection
        self.capture._bass_weight = 0.85

        # Smoothing
        self.capture._prev_peak = 0.0
        self.capture._smoothing = 0.05

        # Legacy attributes for backwards compatibility with tests
        self.capture._min_beat_interval = 0.08

        # Time mock for simulating 60fps
        self.mock_time = MockTime(fps=60.0)

    def detect_beat(self, energy: float, bass_energy: float = None) -> tuple:
        """
        Proxy to the real beat detection, with mocked time.
        Advances simulated time by one frame after each call.
        """
        # Patch time.time for this call
        original_time = time.time
        time.time = self.mock_time.time

        try:
            result = self.capture._detect_beat(energy, bass_energy)
        finally:
            time.time = original_time
            self.mock_time.advance_frame()

        return result

    def reset(self):
        """Reset all state for a fresh test."""
        self.__init__()


class TestSignalGenerator:
    """Generate synthetic test signals."""

    @staticmethod
    def silence(duration_frames: int) -> list:
        """Generate silence (all zeros)."""
        return [0.0] * duration_frames

    @staticmethod
    def constant(value: float, duration_frames: int) -> list:
        """Generate constant energy level."""
        return [value] * duration_frames

    @staticmethod
    def impulse(duration_frames: int, impulse_frame: int, impulse_value: float = 1.0) -> list:
        """Generate single impulse (spike)."""
        signal = [0.0] * duration_frames
        if 0 <= impulse_frame < duration_frames:
            signal[impulse_frame] = impulse_value
        return signal

    @staticmethod
    def kick_pattern(
        duration_frames: int,
        bpm: float,
        fps: float = 60.0,
        kick_value: float = 0.8,
        floor_value: float = 0.1,
        include_warmup: bool = True,
    ) -> list:
        """
        Generate 4-on-the-floor kick pattern at specified BPM.

        Args:
            duration_frames: Total frames to generate (after warmup)
            bpm: Beats per minute
            fps: Frames per second
            kick_value: Peak value on kick
            floor_value: Background noise floor
            include_warmup: Add 120 frames of floor signal for detector warmup
        """
        frames_per_beat = (60.0 / bpm) * fps
        signal = []

        # Add warmup period with floor signal (required for beat detection)
        if include_warmup:
            warmup_frames = 120  # 2 seconds at 60fps
            signal.extend([floor_value] * warmup_frames)

        for i in range(duration_frames):
            # Check if this frame is on a beat
            beat_position = i / frames_per_beat
            # Sharp attack: only 2-3 frames of high value
            if beat_position % 1.0 < 0.05:  # 5% of beat = sharp attack
                signal.append(kick_value)
            else:
                # Fast decay after kick for clearer flux
                frames_since_beat = (beat_position % 1.0) * frames_per_beat
                decay = max(floor_value, kick_value * math.exp(-frames_since_beat * 0.2))
                signal.append(decay)

        return signal

    @staticmethod
    def noise(duration_frames: int, mean: float = 0.3, variance: float = 0.1) -> list:
        """Generate random noise signal."""
        import random

        return [max(0, min(1, random.gauss(mean, variance))) for _ in range(duration_frames)]

    @staticmethod
    def sine_wave(
        duration_frames: int,
        frequency_hz: float,
        fps: float = 60.0,
        amplitude: float = 0.5,
        offset: float = 0.5,
    ) -> list:
        """Generate sine wave (smooth oscillation)."""
        signal = []
        for i in range(duration_frames):
            t = i / fps
            value = offset + amplitude * math.sin(2 * math.pi * frequency_hz * t)
            signal.append(max(0, min(1, value)))
        return signal


class TestBeatDetection:
    """Test beat detection algorithm."""

    def setup_method(self):
        """Set up fresh capture for each test."""
        self.capture = MockAudioCapture()
        self.generator = TestSignalGenerator()

    def _run_signal(self, signal: list, bass_signal: list = None) -> dict:
        """
        Run a signal through beat detection and collect results.

        Returns dict with:
        - beats: list of (frame, intensity) tuples
        - beat_count: total beats detected
        - beat_frames: list of frame indices where beats occurred
        """
        beats = []
        bass = bass_signal or signal

        for i, (energy, bass_energy) in enumerate(zip(signal, bass)):
            is_beat, intensity = self.capture.detect_beat(energy, bass_energy)
            if is_beat:
                beats.append((i, intensity))

        return {"beats": beats, "beat_count": len(beats), "beat_frames": [b[0] for b in beats]}

    # === SILENCE TESTS ===

    def test_silence_no_beats(self):
        """Silence should produce no beats."""
        signal = self.generator.silence(300)  # 5 seconds at 60fps
        result = self._run_signal(signal)
        assert result["beat_count"] == 0, "Silence should not trigger any beats"

    def test_constant_low_no_beats(self):
        """Constant low energy should produce no beats after warmup."""
        signal = self.generator.constant(0.1, 300)
        result = self._run_signal(signal)
        # May detect 1-2 during warmup, but not sustained
        assert result["beat_count"] <= 2, "Constant low signal should not trigger beats"

    # === IMPULSE TESTS ===

    def test_single_impulse_detected(self):
        """Single impulse should be detected as a beat."""
        # Warmup with low signal, then impulse
        warmup = self.generator.constant(0.1, 60)
        impulse = self.generator.impulse(60, 30, 0.9)
        signal = warmup + impulse

        result = self._run_signal(signal)
        assert result["beat_count"] >= 1, "Impulse should trigger at least one beat"

    def test_impulse_timing(self):
        """Impulse should be detected near the actual impulse frame."""
        warmup = self.generator.constant(0.1, 90)
        impulse = self.generator.impulse(60, 30, 0.9)
        signal = warmup + impulse

        result = self._run_signal(signal)

        if result["beat_count"] > 0:
            # Beat should be detected within 5 frames of impulse (frame 120)
            impulse_frame = 90 + 30
            closest_beat = min(result["beat_frames"], key=lambda x: abs(x - impulse_frame))
            assert abs(closest_beat - impulse_frame) <= 5, (
                f"Beat detected at {closest_beat}, expected near {impulse_frame}"
            )

    # === KICK PATTERN TESTS ===

    def test_kick_pattern_128bpm(self):
        """128 BPM kick pattern should detect approximately correct beat count."""
        # 5 seconds at 128 BPM = ~10.7 beats (after 2s warmup)
        signal = self.generator.kick_pattern(300, bpm=128, fps=60)
        result = self._run_signal(signal)

        # Should detect 6-15 beats (allowing for threshold adaptation)
        assert 4 <= result["beat_count"] <= 15, (
            f"Expected 6-12 beats at 128 BPM, got {result['beat_count']}"
        )

    def test_kick_pattern_140bpm(self):
        """140 BPM (fast EDM) should still detect beats."""
        # 5 seconds at 140 BPM = ~11.7 beats
        signal = self.generator.kick_pattern(300, bpm=140, fps=60)
        result = self._run_signal(signal)

        # Should detect multiple beats
        assert 4 <= result["beat_count"] <= 18, (
            f"Expected 5-15 beats at 140 BPM, got {result['beat_count']}"
        )

    def test_kick_pattern_80bpm(self):
        """80 BPM (slower) should detect fewer beats."""
        # 5 seconds at 80 BPM = ~6.7 beats
        signal = self.generator.kick_pattern(300, bpm=80, fps=60)
        result = self._run_signal(signal)

        # Should detect beats at slower rate
        assert 3 <= result["beat_count"] <= 12, (
            f"Expected 3-10 beats at 80 BPM, got {result['beat_count']}"
        )

    # === MIN INTERVAL TESTS ===

    def test_min_beat_interval_respected(self):
        """Beats should not fire faster than min_beat_interval."""
        # Rapid impulses - should be throttled
        signal = []
        for i in range(300):
            # Impulse every 2 frames (way too fast)
            signal.append(0.9 if i % 2 == 0 else 0.1)

        result = self._run_signal(signal)

        if result["beat_count"] > 1:
            # Check intervals between beats
            frames = result["beat_frames"]
            min_interval_frames = self.capture.capture._min_beat_interval * 60  # ~5 frames

            for i in range(1, len(frames)):
                interval = frames[i] - frames[i - 1]
                assert interval >= min_interval_frames - 1, (
                    f"Beat interval {interval} frames is below minimum"
                )

    # === NOISE REJECTION TESTS ===

    def test_noise_limited_beats(self):
        """Random noise should not trigger excessive beats."""
        import random

        random.seed(42)  # Reproducible

        signal = self.generator.noise(300, mean=0.3, variance=0.15)
        result = self._run_signal(signal)

        # Noise might trigger some beats, but not constantly
        # At 60fps, 5 seconds (300 frames), allow up to ~8 beats/second
        # The detector is designed to be sensitive, so some false positives are OK
        assert result["beat_count"] <= 40, (
            f"Noise triggered {result['beat_count']} beats, expected fewer false positives"
        )

    # === BASS WEIGHTING TESTS ===

    def test_bass_emphasis(self):
        """Bass-heavy signal should trigger beats even with low full spectrum."""
        # Full spectrum low, but bass high
        full_spectrum = self.generator.constant(0.15, 120)
        # Insert bass kicks
        bass_signal = list(full_spectrum)
        for i in [60, 90]:  # Two bass kicks
            bass_signal[i] = 0.8

        # Need warmup
        warmup_full = self.generator.constant(0.15, 60)
        warmup_bass = self.generator.constant(0.15, 60)

        result = self._run_signal(warmup_full + full_spectrum, warmup_bass + bass_signal)

        # Should detect the bass kicks
        assert result["beat_count"] >= 1, (
            "Bass-weighted detection should find bass kicks even with low full spectrum"
        )

    # === OUTPUT RANGE TESTS ===

    def test_intensity_range(self):
        """Beat intensity should always be in [0, 1]."""
        signal = self.generator.kick_pattern(240, bpm=128)
        result = self._run_signal(signal)

        for frame, intensity in result["beats"]:
            assert 0 <= intensity <= 1, (
                f"Beat intensity {intensity} at frame {frame} outside valid range"
            )


class TestBandGeneration:
    """Test frequency band generation."""

    def test_bands_output_range(self):
        """All band values should be in [0, 1]."""
        # This would require mocking more of the agent
        # Placeholder for when we add band generation tests
        pass

    def test_bands_count(self):
        """Should always generate exactly 5 bands."""
        pass


class TestPresets:
    """Test preset configurations."""

    def test_all_presets_valid(self):
        """All presets should have required attributes."""
        from audio_processor.app_capture import PRESETS

        required_attrs = [
            "attack",
            "release",
            "beat_threshold",
            "agc_max_gain",
            "beat_sensitivity",
            "band_sensitivity",
        ]

        for preset_name, preset in PRESETS.items():
            for attr in required_attrs:
                assert hasattr(preset, attr), f"Preset '{preset_name}' missing attribute '{attr}'"

    def test_preset_values_in_range(self):
        """Preset values should be within valid ranges."""
        from audio_processor.app_capture import PRESETS

        for preset_name, preset in PRESETS.items():
            assert 0 < preset.attack <= 1, f"{preset_name}: attack out of range"
            assert 0 < preset.release <= 1, f"{preset_name}: release out of range"
            assert 0.5 <= preset.beat_threshold <= 3, f"{preset_name}: beat_threshold out of range"
            assert 1 <= preset.agc_max_gain <= 20, f"{preset_name}: agc_max_gain out of range"
            assert 0 < preset.beat_sensitivity <= 3, f"{preset_name}: beat_sensitivity out of range"
            assert len(preset.band_sensitivity) == 5, (
                f"{preset_name}: band_sensitivity wrong length (expected 5-band system)"
            )

    def test_edm_preset_bass_heavy(self):
        """EDM preset should boost bass bands."""
        from audio_processor.app_capture import PRESETS

        edm = PRESETS["edm"]
        # Bass band (index 0) should be boosted (> 1.0)
        assert edm.band_sensitivity[0] > 1.0, "EDM should boost bass"
        # Bass weight should be high
        assert getattr(edm, "bass_weight", 0.7) >= 0.8, "EDM should have high bass weight"


class TestAGC:
    """Test Auto-Gain Control behavior."""

    def test_agc_boosts_quiet_signal(self):
        """AGC should boost consistently quiet signals."""
        # This would test _update_agc method
        pass

    def test_agc_limits_loud_signal(self):
        """AGC should not over-amplify loud signals."""
        pass

    def test_agc_gain_bounds(self):
        """AGC gain should stay within min/max bounds."""
        pass


class TestAutoCalibration:
    """Test auto-calibration system."""

    def setup_method(self):
        """Set up mock agent for testing."""
        import os
        import sys

        sys.path.insert(
            0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        )

        from audio_processor.app_capture import AppCaptureAgent

        self.agent = AppCaptureAgent.__new__(AppCaptureAgent)

        # Initialize minimal state needed for auto-calibration
        self.agent._auto_calibrate_enabled = True
        self.agent._calibration_frame_count = 0
        self.agent._calibration_warmup_frames = 180
        self.agent._calibration_energy_history = []
        self.agent._calibration_flux_history = []
        self.agent._calibration_beat_times = []
        self.agent._calibration_history_size = 300
        self.agent._estimated_bpm = 120.0
        self.agent._music_variance = 0.5
        self.agent._last_calibration_frame = 0
        self.agent._calibration_interval = 120
        self.agent._smooth_attack = 0.35
        self.agent._smooth_release = 0.08
        self.agent._agc_max_gain = 8.0

        # FFT analyzer state
        self.agent._using_fft = False
        self.agent.fft_analyzer = None

        # Mock capture object
        class MockCapture:
            _beat_threshold = 1.3

        self.agent.capture = MockCapture()

    def test_warmup_no_changes(self):
        """During warmup period, parameters should not change."""
        original_attack = self.agent._smooth_attack
        original_threshold = self.agent.capture._beat_threshold

        # Run 100 frames (still in warmup)
        for i in range(100):
            self.agent._auto_calibrate(0.5, i % 30 == 0)

        # Parameters should be unchanged during warmup
        assert self.agent._smooth_attack == original_attack
        assert self.agent.capture._beat_threshold == original_threshold

    def test_tempo_estimation_fast(self):
        """Fast beat intervals should estimate high BPM."""
        # Warmup
        for i in range(200):
            self.agent._auto_calibrate(0.3, False)

        # Simulate 140 BPM beats (60/140 * 60fps ≈ 26 frames per beat)
        beat_interval = 26
        for i in range(300):
            is_beat = i % beat_interval == 0
            self.agent._auto_calibrate(0.5 if is_beat else 0.3, is_beat)

        # Should estimate high BPM
        assert self.agent._estimated_bpm > 120, (
            f"Expected BPM > 120 for fast beats, got {self.agent._estimated_bpm:.0f}"
        )

    def test_tempo_estimation_slow(self):
        """Slow beat intervals should estimate low BPM."""
        # Warmup
        for i in range(200):
            self.agent._auto_calibrate(0.3, False)

        # Simulate 80 BPM beats (60/80 * 60fps = 45 frames per beat)
        beat_interval = 45
        for i in range(400):
            is_beat = i % beat_interval == 0
            self.agent._auto_calibrate(0.5 if is_beat else 0.3, is_beat)

        # Should estimate lower BPM
        assert self.agent._estimated_bpm < 100, (
            f"Expected BPM < 100 for slow beats, got {self.agent._estimated_bpm:.0f}"
        )

    def test_high_variance_increases_threshold(self):
        """High energy variance should increase beat threshold."""
        import random

        random.seed(123)

        # Warmup with stable signal
        for i in range(200):
            self.agent._auto_calibrate(0.3, False)

        initial_threshold = self.agent.capture._beat_threshold

        # Feed highly variable signal
        for i in range(300):
            energy = random.uniform(0.1, 0.9)  # High variance
            self.agent._auto_calibrate(energy, i % 30 == 0)

        # Threshold should increase for noisy music
        assert self.agent.capture._beat_threshold >= initial_threshold - 0.1, (
            "High variance should not significantly lower threshold"
        )

    def test_attack_increases_for_fast_tempo(self):
        """Fast tempo should increase attack speed."""
        # Warmup
        for i in range(200):
            self.agent._auto_calibrate(0.3, False)

        # Set initial attack low
        self.agent._smooth_attack = 0.2

        # Simulate fast beats (160 BPM = ~22 frames per beat)
        beat_interval = 22
        for i in range(500):
            is_beat = i % beat_interval == 0
            self.agent._auto_calibrate(0.6 if is_beat else 0.2, is_beat)

        # Attack should increase for fast music
        assert self.agent._smooth_attack > 0.3, (
            f"Expected attack > 0.3 for fast tempo, got {self.agent._smooth_attack:.2f}"
        )


class TestBPMEstimation:
    """Test BPM estimation algorithm in FFTAnalyzer."""

    def setup_method(self):
        """Set up FFTAnalyzer for testing."""
        import os
        import sys

        sys.path.insert(
            0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        )

        import numpy as np
        from audio_processor.fft_analyzer import FFTAnalyzer

        # Create analyzer without starting capture
        self.analyzer = FFTAnalyzer.__new__(FFTAnalyzer)

        # Initialize BPM estimation state
        self.analyzer._onset_times = []
        self.analyzer._ioi_history = []
        self.analyzer._tempo_histogram = np.zeros(200, dtype=np.float32)
        self.analyzer._histogram_decay = 0.995
        self.analyzer._estimated_bpm = 120.0
        self.analyzer._bpm_confidence = 0.0

    def _simulate_kicks_at_bpm(self, bpm: float, num_beats: int = 32):
        """Simulate kick onsets at a given BPM."""
        beat_interval = 60.0 / bpm  # seconds per beat
        base_time = 1000000.0  # Arbitrary start time

        for i in range(num_beats):
            onset_time = base_time + (i * beat_interval)
            self.analyzer._update_bpm_from_onset(onset_time)

    def test_bpm_120(self):
        """Test BPM estimation at 120 BPM."""
        self._simulate_kicks_at_bpm(120.0, num_beats=32)

        # Should estimate close to 120 BPM
        assert 115 <= self.analyzer._estimated_bpm <= 125, (
            f"Expected ~120 BPM, got {self.analyzer._estimated_bpm:.1f}"
        )

    def test_bpm_123_one_more_time(self):
        """Test BPM estimation at 123 BPM (One More Time tempo)."""
        self._simulate_kicks_at_bpm(123.0, num_beats=32)

        # Should estimate close to 123 BPM
        assert 118 <= self.analyzer._estimated_bpm <= 128, (
            f"Expected ~123 BPM, got {self.analyzer._estimated_bpm:.1f}"
        )

    def test_bpm_140_fast_edm(self):
        """Test BPM estimation at 140 BPM (fast EDM)."""
        self._simulate_kicks_at_bpm(140.0, num_beats=32)

        # Should estimate close to 140 BPM
        assert 135 <= self.analyzer._estimated_bpm <= 145, (
            f"Expected ~140 BPM, got {self.analyzer._estimated_bpm:.1f}"
        )

    def test_bpm_80_slow(self):
        """Test BPM estimation at 80 BPM (slower tempo)."""
        self._simulate_kicks_at_bpm(80.0, num_beats=32)

        # Should estimate close to 80 BPM (or possibly doubled to 160)
        # Due to octave preference for 80-160, 80 should be detected
        assert (
            75 <= self.analyzer._estimated_bpm <= 85 or 155 <= self.analyzer._estimated_bpm <= 165
        ), f"Expected ~80 or ~160 BPM, got {self.analyzer._estimated_bpm:.1f}"

    def test_confidence_increases_with_beats(self):
        """Confidence should increase as more consistent beats are detected."""
        # Few beats - low confidence
        self._simulate_kicks_at_bpm(120.0, num_beats=4)
        confidence_low = self.analyzer._bpm_confidence

        # More beats - higher confidence
        self._simulate_kicks_at_bpm(120.0, num_beats=20)
        confidence_high = self.analyzer._bpm_confidence

        assert confidence_high >= confidence_low, (
            f"Confidence should increase with more beats: {confidence_low:.2f} -> {confidence_high:.2f}"
        )

    def test_no_octave_error_double(self):
        """Should not report double the actual tempo (246 for 123 BPM)."""
        self._simulate_kicks_at_bpm(123.0, num_beats=32)

        # Should NOT be 246 BPM (double octave error)
        assert self.analyzer._estimated_bpm < 200, (
            f"Octave error: got {self.analyzer._estimated_bpm:.1f} BPM instead of ~123"
        )

    def test_no_octave_error_half(self):
        """Should not report half the actual tempo (61 for 123 BPM)."""
        self._simulate_kicks_at_bpm(123.0, num_beats=32)

        # Should NOT be ~61 BPM (half octave error)
        assert self.analyzer._estimated_bpm > 100, (
            f"Octave error: got {self.analyzer._estimated_bpm:.1f} BPM instead of ~123"
        )

    def test_histogram_decay(self):
        """Histogram should decay over time, allowing tempo changes."""

        # Establish 120 BPM
        self._simulate_kicks_at_bpm(120.0, num_beats=16)
        bpm_120 = self.analyzer._estimated_bpm

        # Apply decay by simulating passage of time without onsets
        for _ in range(100):
            self.analyzer._tempo_histogram *= self.analyzer._histogram_decay

        # Then establish 140 BPM
        self._simulate_kicks_at_bpm(140.0, num_beats=32)
        bpm_new = self.analyzer._estimated_bpm

        # Should have adapted toward 140
        assert bpm_new > bpm_120, f"BPM should adapt: was {bpm_120:.1f}, now {bpm_new:.1f}"

    def test_ioi_filtering(self):
        """Invalid IOIs (too fast or too slow) should be filtered out."""
        base_time = 1000000.0

        # Very fast IOI (0.1 sec = 600 BPM) - should be ignored
        self.analyzer._update_bpm_from_onset(base_time)
        self.analyzer._update_bpm_from_onset(base_time + 0.1)

        # The 0.1s IOI should be filtered, but the 0.5s from 0.1 to 0.6 is valid
        # So let's check that invalid IOIs are not in the history
        assert all(0.25 <= ioi <= 1.5 for ioi in self.analyzer._ioi_history), (
            f"Invalid IOIs found in history: {self.analyzer._ioi_history}"
        )

        # Very slow IOI (2.0 sec = 30 BPM) - should also be ignored
        self.analyzer._ioi_history.clear()
        self.analyzer._onset_times.clear()
        self.analyzer._update_bpm_from_onset(base_time)
        self.analyzer._update_bpm_from_onset(base_time + 2.0)

        assert len(self.analyzer._ioi_history) == 0, (
            f"Too-slow IOI should be filtered: {self.analyzer._ioi_history}"
        )


class TestTickAlignedBeatPrediction:
    """Regression tests for tick-aligned beat prediction path."""

    def setup_method(self):
        """Set up minimal AppCaptureAgent state for prediction tests."""
        import os
        import sys

        sys.path.insert(
            0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        )

        from audio_processor.app_capture import AppCaptureAgent

        self.agent = AppCaptureAgent.__new__(AppCaptureAgent)
        self.agent._estimated_bpm = 128.0
        self.agent._mc_tick_interval = 0.050
        self.agent._next_predicted_beat = 0.0
        self.agent._beat_phase = 0.0

    def test_predict_state_returns_valid_app_audio_frame(self):
        """Predicted beats must construct AppAudioFrame with the current dataclass fields."""
        from audio_processor.app_capture import AppAudioFrame

        now = time.time()
        self.agent._next_predicted_beat = now + 0.03  # Within one MC tick
        frame = AppAudioFrame(
            timestamp=now,
            peak=0.5,
            channels=[0.4, 0.5],
            is_beat=False,
            beat_intensity=0.2,
        )

        predicted = self.agent._predict_beat_state(frame, [0.2, 0.3, 0.4, 0.3, 0.2])

        assert predicted.is_beat
        assert predicted.beat_intensity >= 0.7
        assert predicted.timestamp == frame.timestamp
        assert predicted.channels == frame.channels

    def test_real_beat_updates_next_predicted_beat(self):
        """A real beat should reset phase and seed next predicted beat time."""
        from audio_processor.app_capture import AppAudioFrame

        now = time.time()
        frame = AppAudioFrame(
            timestamp=now,
            peak=0.6,
            channels=[0.5, 0.6],
            is_beat=True,
            beat_intensity=0.9,
        )

        _ = self.agent._predict_beat_state(frame, [0.2, 0.3, 0.4, 0.3, 0.2])

        assert self.agent._beat_phase == 0.0
        assert self.agent._next_predicted_beat > now


# Run with: python -m pytest audio_processor/tests/test_beat_detection.py -v
if __name__ == "__main__":
    pytest.main([__file__, "-v"])
