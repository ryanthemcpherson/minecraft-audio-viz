"""
Tests for the parallel bass lane (instant kick detection).
"""

import time

import numpy as np
import pytest

from audio_processor.fft_analyzer import BassLane


class TestBassLane:
    """Tests for the BassLane class."""

    def test_create_default(self):
        """Test creation with default parameters."""
        bass_lane = BassLane()
        assert bass_lane.sample_rate == 44100
        assert bass_lane.cutoff_hz == 120.0

    def test_create_custom(self):
        """Test creation with custom parameters."""
        bass_lane = BassLane(
            sample_rate=48000, cutoff_hz=100.0, attack_ms=2.0, release_ms=100.0, onset_threshold=0.2
        )
        assert bass_lane.sample_rate == 48000
        assert bass_lane.cutoff_hz == 100.0

    def test_process_silence(self):
        """Test processing silent audio."""
        bass_lane = BassLane()

        # Generate silence
        silence = np.zeros(1024, dtype=np.float32)
        energy, onset, strength = bass_lane.process_samples(silence, time.time())

        assert energy == 0.0 or energy < 0.01  # Near zero
        assert not onset
        assert strength == 0.0

    def test_process_bass_sine(self):
        """Test processing a bass sine wave."""
        sample_rate = 44100
        bass_lane = BassLane(sample_rate=sample_rate, cutoff_hz=200.0)

        # Generate 80Hz sine wave (below cutoff, should pass through)
        t = np.arange(4096) / sample_rate
        bass_sine = np.sin(2 * np.pi * 80 * t).astype(np.float32) * 0.5

        # Process multiple chunks to allow envelope to settle
        timestamp = time.time()
        for i in range(10):
            energy, onset, strength = bass_lane.process_samples(
                bass_sine[i * 256 : (i + 1) * 256], timestamp + i * 0.006
            )

        # Should have non-zero bass energy
        assert energy > 0.1

    def test_process_high_freq_attenuated(self):
        """Test that high frequencies are attenuated relative to bass."""
        sample_rate = 44100

        # Test with bass
        bass_lane_bass = BassLane(sample_rate=sample_rate, cutoff_hz=120.0)
        t = np.arange(4096) / sample_rate
        bass_sine = np.sin(2 * np.pi * 80 * t).astype(np.float32) * 0.5

        # Process bass
        timestamp = time.time()
        bass_energy = 0.0
        for i in range(10):
            bass_energy, onset, strength = bass_lane_bass.process_samples(
                bass_sine[i * 256 : (i + 1) * 256], timestamp + i * 0.006
            )

        # Test with high frequency
        bass_lane_high = BassLane(sample_rate=sample_rate, cutoff_hz=120.0)
        high_sine = np.sin(2 * np.pi * 5000 * t).astype(np.float32) * 0.5

        # Process high freq (need to process more to see attenuation)
        high_energy = 0.0
        for i in range(10):
            high_energy, onset, strength = bass_lane_high.process_samples(
                high_sine[i * 256 : (i + 1) * 256], timestamp + i * 0.006
            )

        # The single-pole IIR filter will attenuate high frequencies relative to bass
        # but due to normalization both may show similar levels.
        # The key is that the filter passes bass and attenuates highs in raw terms.
        # With our AGC normalization, both may reach similar normalized levels,
        # but the raw envelope value should be lower for high frequencies.
        raw_bass = bass_lane_bass.current_envelope
        raw_high = bass_lane_high.current_envelope

        # High frequency raw envelope should be significantly lower
        assert raw_high < raw_bass * 0.5, (
            f"High freq raw {raw_high} should be << bass raw {raw_bass}"
        )

    def test_onset_detection(self):
        """Test onset detection on transient."""
        sample_rate = 44100
        bass_lane = BassLane(
            sample_rate=sample_rate,
            cutoff_hz=150.0,
            attack_ms=1.0,
            release_ms=50.0,
            onset_threshold=0.1,
        )

        # Simulate a kick drum transient:
        # 1. Start with silence to establish baseline
        silence = np.zeros(512, dtype=np.float32)
        bass_lane.process_samples(silence, 0.0)
        bass_lane.process_samples(silence, 0.01)

        # 2. Add sudden bass transient (simulated kick)
        t = np.arange(512) / sample_rate
        kick = np.sin(2 * np.pi * 60 * t).astype(np.float32)
        # Apply amplitude envelope (fast attack)
        envelope = np.exp(-t * 20)  # Exponential decay
        kick = kick * envelope * 0.8

        # Process the transient
        onset_detected = False
        for i in range(4):
            chunk = kick[i * 128 : (i + 1) * 128]
            energy, onset, strength = bass_lane.process_samples(chunk, 0.2 + i * 0.003)
            if onset:
                onset_detected = True
                break

        # Should detect an onset
        assert onset_detected

    def test_onset_minimum_interval(self):
        """Test that onsets respect minimum interval."""
        bass_lane = BassLane(sample_rate=44100, cutoff_hz=120.0)

        # Generate repeated bass pulses
        t = np.arange(256) / 44100
        pulse = np.sin(2 * np.pi * 80 * t).astype(np.float32) * 0.8

        onset_times = []
        base_time = time.time()

        # Process many pulses in quick succession
        for i in range(20):
            timestamp = base_time + i * 0.05  # 50ms apart
            energy, onset, strength = bass_lane.process_samples(pulse, timestamp)
            if onset:
                onset_times.append(timestamp)

        # Check intervals between detected onsets
        if len(onset_times) > 1:
            intervals = np.diff(onset_times)
            # All intervals should be >= minimum interval (0.15s)
            assert all(interval >= 0.15 - 0.01 for interval in intervals)

    def test_stereo_input(self):
        """Test processing stereo input."""
        bass_lane = BassLane()

        # Generate stereo bass
        t = np.arange(512) / 44100
        mono = np.sin(2 * np.pi * 80 * t).astype(np.float32) * 0.5
        stereo = np.column_stack([mono, mono * 0.8])  # Slight difference

        energy, onset, strength = bass_lane.process_samples(stereo, time.time())

        # Should work without error
        assert isinstance(energy, float)
        assert isinstance(onset, bool)

    def test_reset(self):
        """Test state reset."""
        bass_lane = BassLane()

        # Process some audio
        audio = np.random.randn(1024).astype(np.float32) * 0.5
        bass_lane.process_samples(audio, time.time())

        # State should be non-zero
        assert bass_lane.current_envelope > 0

        # Reset
        bass_lane.reset()

        # State should be zero
        assert bass_lane.current_envelope == 0.0
        assert bass_lane.normalized_envelope == 0.0

    def test_normalized_envelope(self):
        """Test normalized envelope stays in 0-1 range."""
        bass_lane = BassLane()

        # Process varying amplitude audio
        for amplitude in [0.1, 0.5, 1.0, 0.3]:
            t = np.arange(512) / 44100
            audio = np.sin(2 * np.pi * 80 * t).astype(np.float32) * amplitude

            for i in range(5):
                bass_lane.process_samples(audio[i * 100 : (i + 1) * 100], time.time())

            # Check normalized envelope is in valid range
            assert 0.0 <= bass_lane.normalized_envelope <= 1.0


class TestBassLaneIntegration:
    """Integration tests for bass lane with FFT analyzer."""

    def test_bass_lane_in_fft_result(self):
        """Test that bass lane results appear in FFTResult."""
        # Import FFTResult to check fields
        from audio_processor.fft_analyzer import FFTResult

        # Verify FFTResult has bass lane fields
        assert hasattr(FFTResult, "__dataclass_fields__")
        fields = FFTResult.__dataclass_fields__
        assert "instant_bass" in fields
        assert "instant_kick_onset" in fields


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
