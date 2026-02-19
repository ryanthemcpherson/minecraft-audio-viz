"""
Beat Predictor - Predicts upcoming beats based on tempo tracking.

Instead of reacting to beats (which adds latency), this module tracks
the tempo and phase of the music and predicts when beats will occur,
allowing visualization updates to be sent AHEAD of time.

Algorithm based on:
- Tempo histogram with Gaussian voting (robust to outliers)
- Phase tracking via onset correlation
- Kalman-like smoothing for stable predictions

References:
- Beat-and-Tempo-Tracking: https://github.com/michaelkrzyzaniak/Beat-and-Tempo-Tracking
- Ellis "Beat tracking by dynamic programming" (2007)
"""

import time
import math
import logging
from collections import deque
from dataclasses import dataclass, field
from typing import Optional, Callable, List, Tuple

import numpy as np

logger = logging.getLogger(__name__)


@dataclass
class BeatPrediction:
    """Information about predicted beats."""
    next_beat_time: float       # Unix timestamp of next predicted beat
    time_to_next_beat: float    # Seconds until next beat
    beat_phase: float           # Current position in beat cycle (0-1)
    tempo_bpm: float            # Current tempo estimate
    tempo_confidence: float     # Confidence in tempo (0-1)
    is_downbeat: bool           # Whether next beat is a downbeat (bar start)
    beats_per_bar: int          # Detected meter (usually 4)


class BeatPredictor:
    """
    Predicts upcoming beats based on tempo and phase tracking.

    Key features:
    - Tempo estimation via onset autocorrelation + histogram voting
    - Phase tracking to sync with actual beat positions
    - Beat prediction with configurable lookahead
    - Smooth tempo transitions (no sudden jumps)
    """

    def __init__(
        self,
        min_bpm: float = 60.0,
        max_bpm: float = 200.0,
        prediction_lookahead: float = 0.100,  # Predict 100ms ahead
        tempo_smoothing: float = 0.95,        # How much to smooth tempo changes
        phase_correction_rate: float = 0.1,   # How fast to correct phase drift
    ):
        """
        Initialize beat predictor.

        Args:
            min_bpm: Minimum tempo to consider
            max_bpm: Maximum tempo to consider
            prediction_lookahead: How far ahead to predict beats (seconds)
            tempo_smoothing: Smoothing factor for tempo (0-1, higher = smoother)
            phase_correction_rate: How fast to correct phase drift (0-1)
        """
        self.min_bpm = min_bpm
        self.max_bpm = max_bpm
        self.prediction_lookahead = prediction_lookahead
        self.tempo_smoothing = tempo_smoothing
        self.phase_correction_rate = phase_correction_rate

        # Tempo estimation state
        self._tempo_bpm = 120.0           # Current tempo estimate
        self._tempo_confidence = 0.0       # Confidence in estimate
        self._beat_period = 0.5            # Seconds per beat (60/BPM)

        # Tempo histogram (bins from min_bpm to max_bpm)
        self._histogram_bins = int(max_bpm - min_bpm) + 1
        self._tempo_histogram = np.zeros(self._histogram_bins, dtype=np.float32)
        self._histogram_decay = 0.995      # Slow decay for stability

        # Phase tracking
        self._beat_phase = 0.0             # 0-1 position in beat cycle
        self._last_phase_update = time.time()
        self._phase_locked = False         # Whether we're confidently locked to phase

        # Onset tracking for tempo/phase detection
        self._onset_times: deque = deque(maxlen=64)
        self._ioi_history: deque = deque(maxlen=32)  # Inter-onset intervals

        # Beat counting (for downbeat detection)
        self._beat_count = 0
        self._beats_per_bar = 4            # Assume 4/4 time

        # Callbacks
        self._on_beat_predicted: Optional[Callable[[BeatPrediction], None]] = None
        self._last_predicted_beat_time = 0.0

    def set_callback(self, on_beat_predicted: Callable[[BeatPrediction], None]):
        """Set callback for when a beat is predicted."""
        self._on_beat_predicted = on_beat_predicted

    def process_onset(self, is_onset: bool, onset_strength: float = 1.0):
        """
        Process an onset detection event.

        Call this whenever a beat/kick onset is detected by the FFT analyzer.
        The predictor will use these to refine tempo and phase estimates.

        Args:
            is_onset: Whether an onset was detected this frame
            onset_strength: Strength of the onset (0-1)
        """
        current_time = time.time()

        if is_onset and onset_strength > 0.3:
            self._onset_times.append(current_time)

            # Calculate inter-onset interval
            if len(self._onset_times) >= 2:
                ioi = current_time - self._onset_times[-2]

                # Filter IOIs to valid tempo range
                min_ioi = 60.0 / self.max_bpm  # ~0.3s at 200 BPM
                max_ioi = 60.0 / self.min_bpm  # ~1.0s at 60 BPM

                if min_ioi <= ioi <= max_ioi:
                    # Check if IOI fits current tempo (or multiple/subdivision)
                    if self._tempo_confidence > 0.3:
                        expected_ioi = self._beat_period
                        # Allow IOIs that are multiples (half, normal, double)
                        ratios = [ioi / expected_ioi, ioi / (expected_ioi * 2),
                                  ioi / (expected_ioi * 0.5)]
                        best_ratio = min(ratios, key=lambda r: abs(r - 1.0))

                        # Only accept if reasonably close to expected
                        if abs(best_ratio - 1.0) < 0.25:
                            self._ioi_history.append(ioi)
                            self._update_tempo_histogram(ioi)
                            self._correct_phase(current_time)
                    else:
                        # Low confidence - accept any valid IOI
                        self._ioi_history.append(ioi)
                        self._update_tempo_histogram(ioi)

        # Update phase based on elapsed time
        self._update_phase(current_time)

    def _update_tempo_histogram(self, ioi: float):
        """Update tempo histogram with new IOI measurement."""
        # Decay existing histogram
        self._tempo_histogram *= self._histogram_decay

        # Convert IOI to BPM
        bpm = 60.0 / ioi

        # Vote for this tempo AND harmonics (helps with octave errors)
        for multiplier in [0.5, 1.0, 2.0]:
            candidate_bpm = bpm * multiplier
            if self.min_bpm <= candidate_bpm <= self.max_bpm:
                bin_idx = int(candidate_bpm - self.min_bpm)

                # Add Gaussian-weighted votes
                for offset in range(-3, 4):
                    idx = bin_idx + offset
                    if 0 <= idx < self._histogram_bins:
                        weight = math.exp(-0.5 * (offset / 1.5) ** 2)
                        # Prefer 90-150 BPM range (most common for electronic music)
                        if 90 <= candidate_bpm <= 150:
                            weight *= 1.3
                        self._tempo_histogram[idx] += weight

        # Extract tempo from histogram
        self._extract_tempo_from_histogram()

    def _extract_tempo_from_histogram(self):
        """Extract dominant tempo from histogram."""
        if np.max(self._tempo_histogram) < 1.0:
            return  # Not enough data

        # Find peak
        peak_idx = int(np.argmax(self._tempo_histogram))
        peak_bpm = float(peak_idx + self.min_bpm)

        # Refine with weighted average around peak
        start_idx = max(0, peak_idx - 2)
        end_idx = min(self._histogram_bins, peak_idx + 3)
        weights = self._tempo_histogram[start_idx:end_idx]

        if np.sum(weights) > 0:
            indices = np.arange(start_idx, end_idx) + self.min_bpm
            refined_bpm = float(np.average(indices, weights=weights))
        else:
            refined_bpm = peak_bpm

        # Calculate confidence
        peak_height = self._tempo_histogram[peak_idx]
        mean_height = np.mean(self._tempo_histogram) + 0.001
        prominence = peak_height / mean_height

        # More IOIs = more confidence
        sample_conf = min(1.0, len(self._ioi_history) / 16.0)

        # IOI consistency
        if len(self._ioi_history) >= 4:
            ioi_std = np.std(list(self._ioi_history))
            ioi_mean = np.mean(list(self._ioi_history))
            cv = ioi_std / (ioi_mean + 0.001)
            consistency_conf = max(0.0, 1.0 - cv * 2)
        else:
            consistency_conf = 0.5

        new_confidence = min(1.0, (prominence / 15.0) * sample_conf * consistency_conf)

        # Smooth tempo transition
        if self._tempo_confidence > 0.3:
            # Only update if new estimate is close or more confident
            tempo_diff = abs(refined_bpm - self._tempo_bpm)
            if tempo_diff < 5 or new_confidence > self._tempo_confidence:
                self._tempo_bpm = (self._tempo_bpm * self.tempo_smoothing +
                                   refined_bpm * (1 - self.tempo_smoothing))
        else:
            self._tempo_bpm = refined_bpm

        self._tempo_confidence = new_confidence
        self._beat_period = 60.0 / self._tempo_bpm

    def _correct_phase(self, onset_time: float):
        """Correct beat phase based on detected onset."""
        if self._tempo_confidence < 0.3:
            return

        # Calculate where in the beat cycle this onset occurred
        elapsed = onset_time - self._last_phase_update
        expected_phase = (self._beat_phase + elapsed / self._beat_period) % 1.0

        # Onset should ideally be at phase 0 (beat position)
        # Calculate phase error
        phase_error = expected_phase  # How far from beat position
        if phase_error > 0.5:
            phase_error -= 1.0  # Wrap to [-0.5, 0.5]

        # Gradually correct phase
        correction = phase_error * self.phase_correction_rate
        self._beat_phase = (self._beat_phase - correction) % 1.0
        self._phase_locked = abs(phase_error) < 0.1

    def _update_phase(self, current_time: float):
        """Update beat phase based on elapsed time."""
        elapsed = current_time - self._last_phase_update
        self._last_phase_update = current_time

        if self._beat_period > 0:
            phase_advance = elapsed / self._beat_period
            new_phase = (self._beat_phase + phase_advance) % 1.0

            # Check if we crossed a beat boundary
            if new_phase < self._beat_phase:
                self._beat_count = (self._beat_count + 1) % self._beats_per_bar

            self._beat_phase = new_phase

    def get_prediction(self) -> BeatPrediction:
        """
        Get current beat prediction.

        Returns prediction for the next upcoming beat, accounting for
        the configured lookahead time.
        """
        current_time = time.time()

        # Time until next beat (phase 0)
        time_to_beat = (1.0 - self._beat_phase) * self._beat_period

        # If beat is very soon, predict the one after
        if time_to_beat < 0.01:
            time_to_beat += self._beat_period

        next_beat_time = current_time + time_to_beat
        is_downbeat = (self._beat_count + 1) % self._beats_per_bar == 0

        return BeatPrediction(
            next_beat_time=next_beat_time,
            time_to_next_beat=time_to_beat,
            beat_phase=self._beat_phase,
            tempo_bpm=self._tempo_bpm,
            tempo_confidence=self._tempo_confidence,
            is_downbeat=is_downbeat,
            beats_per_bar=self._beats_per_bar
        )

    def should_fire_beat(self) -> Tuple[bool, float]:
        """
        Check if a beat should be fired NOW (accounting for lookahead).

        Returns:
            Tuple of (should_fire, beat_intensity)

        Call this every frame. It returns True when it's time to trigger
        a beat event, accounting for the prediction lookahead.
        """
        if self._tempo_confidence < 0.2:
            return False, 0.0

        current_time = time.time()
        prediction = self.get_prediction()

        # Fire beat if we're within lookahead window of next beat
        # and haven't already fired for this beat
        time_to_beat = prediction.time_to_next_beat

        if (time_to_beat <= self.prediction_lookahead and
            prediction.next_beat_time > self._last_predicted_beat_time + self._beat_period * 0.5):

            self._last_predicted_beat_time = prediction.next_beat_time

            # Intensity based on downbeat and confidence
            intensity = 0.7 + (0.3 if prediction.is_downbeat else 0.0)
            intensity *= self._tempo_confidence

            if self._on_beat_predicted:
                self._on_beat_predicted(prediction)

            return True, intensity

        return False, 0.0

    def get_beat_intensity_at_phase(self, phase_offset: float = 0.0) -> float:
        """
        Get beat intensity at current phase (for smooth animations).

        Args:
            phase_offset: Offset from current phase (for lookahead)

        Returns:
            Intensity value (0-1) that peaks at beat positions
        """
        phase = (self._beat_phase + phase_offset) % 1.0

        # Exponential decay from beat position
        # Peak at phase 0, decay quickly
        if phase < 0.15:
            # Attack: quick rise
            intensity = 1.0 - (phase / 0.15) * 0.3
        else:
            # Decay: exponential falloff
            intensity = 0.7 * math.exp(-5 * (phase - 0.15))

        return intensity * self._tempo_confidence

    @property
    def tempo_bpm(self) -> float:
        return self._tempo_bpm

    @property
    def tempo_confidence(self) -> float:
        return self._tempo_confidence

    @property
    def beat_phase(self) -> float:
        return self._beat_phase

    @property
    def is_phase_locked(self) -> bool:
        return self._phase_locked and self._tempo_confidence > 0.5


class PredictiveBeatSync:
    """
    High-level interface for beat-synced visualization.

    Combines FFT onset detection with beat prediction to provide
    beat events that arrive BEFORE the actual beat, allowing
    visualization to be perfectly synchronized.
    """

    def __init__(self, lookahead_ms: float = 80.0):
        """
        Initialize predictive beat sync.

        Args:
            lookahead_ms: How many ms ahead to predict beats.
                         Should match your total system latency.
        """
        self.predictor = BeatPredictor(
            prediction_lookahead=lookahead_ms / 1000.0
        )
        self._last_kick_time = 0.0
        self._min_kick_interval = 0.15  # 150ms min between kicks

    def process_fft_result(self, kick_onset: bool, beat_intensity: float = 0.0):
        """
        Process FFT result to update beat prediction.

        Args:
            kick_onset: Whether kick drum onset was detected
            beat_intensity: Strength of the beat (0-1)
        """
        current_time = time.time()

        # Debounce kick onsets
        if kick_onset and current_time - self._last_kick_time > self._min_kick_interval:
            self._last_kick_time = current_time
            self.predictor.process_onset(True, beat_intensity)
        else:
            self.predictor.process_onset(False, 0.0)

    def get_beat_state(self) -> dict:
        """
        Get current beat state for visualization.

        Returns dict with:
            - predicted_beat: Whether a beat should fire NOW
            - beat_intensity: Intensity of the beat (0-1)
            - tempo_bpm: Current tempo
            - tempo_confidence: Confidence (0-1)
            - beat_phase: Position in beat cycle (0-1)
            - phase_intensity: Smooth intensity curve based on phase
        """
        should_fire, intensity = self.predictor.should_fire_beat()
        prediction = self.predictor.get_prediction()

        return {
            'predicted_beat': should_fire,
            'beat_intensity': intensity,
            'tempo_bpm': prediction.tempo_bpm,
            'tempo_confidence': prediction.tempo_confidence,
            'beat_phase': prediction.beat_phase,
            'phase_intensity': self.predictor.get_beat_intensity_at_phase(),
            'is_downbeat': prediction.is_downbeat,
            'time_to_next_beat': prediction.time_to_next_beat,
        }


if __name__ == "__main__":
    # Test beat predictor with simulated onsets
    import random

    predictor = BeatPredictor(prediction_lookahead=0.08)

    # Simulate 120 BPM (0.5s per beat)
    beat_interval = 0.5
    next_beat = time.time()

    print("Testing beat predictor with simulated 120 BPM...")
    print("Prediction lookahead: 80ms")
    print()

    start_time = time.time()
    while time.time() - start_time < 10:
        current_time = time.time()

        # Simulate onset at beat positions (with slight jitter)
        if current_time >= next_beat:
            jitter = random.gauss(0, 0.02)  # 20ms jitter
            predictor.process_onset(True, 0.8)
            next_beat += beat_interval
        else:
            predictor.process_onset(False, 0.0)

        # Check predictions
        should_fire, intensity = predictor.should_fire_beat()
        if should_fire:
            prediction = predictor.get_prediction()
            print(f"PREDICTED BEAT! BPM: {prediction.tempo_bpm:.1f}, "
                  f"Confidence: {prediction.tempo_confidence:.2f}, "
                  f"Lookahead: {prediction.time_to_next_beat*1000:.0f}ms")

        time.sleep(0.016)  # 60 FPS
