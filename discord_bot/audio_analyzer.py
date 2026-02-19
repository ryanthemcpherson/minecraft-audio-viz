"""
Lightweight FFT analyzer for Discord bot audio.

Processes PCM audio frames and produces dj_audio_frame-compatible results
with 5-band FFT, beat detection, BPM estimation, and bass lane.

Uses numpy only (no scipy or other heavy dependencies).
"""

import logging
import time
from collections import deque
from typing import List, Optional, Tuple

import numpy as np

logger = logging.getLogger(__name__)

# Frequency band definitions (Hz) - matches CLAUDE.md spec
BAND_RANGES = [
    (40, 250),  # Bass (kick drums, bass guitar)
    (250, 500),  # Low-mid (snare body, vocals)
    (500, 2000),  # Mid (vocals, instruments)
    (2000, 6000),  # High-mid (presence, snare crack)
    (6000, 20000),  # High (hi-hats, cymbals, air)
]


class BassLane:
    """Ultra-fast bass detection using manual IIR lowpass + envelope follower."""

    def __init__(
        self,
        sample_rate: int = 48000,
        cutoff_hz: float = 120.0,
        attack_ms: float = 1.0,
        release_ms: float = 50.0,
        onset_threshold: float = 0.15,
    ):
        # Single-pole IIR lowpass coefficient
        rc = 1.0 / (2.0 * np.pi * cutoff_hz)
        dt = 1.0 / sample_rate
        self._lp_alpha = dt / (rc + dt)

        # Envelope follower coefficients
        self._attack_coef = 1.0 - np.exp(-1.0 / (sample_rate * attack_ms / 1000.0))
        self._release_coef = 1.0 - np.exp(-1.0 / (sample_rate * release_ms / 1000.0))

        # Filter state
        self._lp_state = 0.0
        self._envelope = 0.0
        self._prev_envelope = 0.0
        self._peak_envelope = 0.0

        # Onset detection
        self._onset_threshold = onset_threshold
        self._last_onset_time = 0.0
        self._min_onset_interval = 0.15  # 150ms = max ~400 BPM

        # Normalization with AGC
        self._max_envelope_history: deque = deque(maxlen=300)

    def process_samples(self, mono: np.ndarray, timestamp: float) -> Tuple[float, bool, float]:
        """Process mono float32 samples. Returns (bass_energy, is_onset, onset_strength)."""
        n = len(mono)
        if n == 0:
            return 0.0, False, 0.0

        # Manual sample-by-sample IIR lowpass (no scipy)
        alpha = self._lp_alpha
        lp_out = np.empty(n, dtype=np.float32)
        state = self._lp_state
        for i in range(n):
            state = alpha * mono[i] + (1.0 - alpha) * state
            lp_out[i] = state
        self._lp_state = state

        # Rectify
        rectified = np.abs(lp_out)

        # Envelope follower (sequential — each sample depends on previous)
        envelope = self._envelope
        attack_c = self._attack_coef
        release_c = self._release_coef
        for i in range(n):
            r = float(rectified[i])
            if r > envelope:
                envelope += attack_c * (r - envelope)
            else:
                envelope += release_c * (r - envelope)

        prev_envelope = self._prev_envelope
        self._prev_envelope = self._envelope
        self._envelope = envelope

        # Peak tracking for normalization
        if envelope > self._peak_envelope:
            self._peak_envelope = envelope
        self._max_envelope_history.append(envelope)

        if len(self._max_envelope_history) > 30:
            recent_max = max(self._max_envelope_history)
            self._peak_envelope = max(self._peak_envelope * 0.999, recent_max * 1.1)

        # Normalize to 0-1
        norm_envelope = min(1.0, envelope / (self._peak_envelope + 1e-6))

        # Onset detection: positive slope above threshold
        envelope_delta = envelope - prev_envelope
        is_onset = False
        onset_strength = 0.0

        if envelope_delta > self._onset_threshold * self._peak_envelope:
            if timestamp - self._last_onset_time > self._min_onset_interval:
                is_onset = True
                onset_strength = min(1.0, envelope_delta / (self._peak_envelope * 0.5 + 1e-6))
                self._last_onset_time = timestamp

        return norm_envelope, is_onset, onset_strength


class AudioAnalyzer:
    """
    Lightweight FFT analyzer for Discord bot audio.

    Processes 48kHz stereo s16le PCM (Discord's native format) and produces
    dj_audio_frame-compatible analysis results.
    """

    def __init__(
        self,
        sample_rate: int = 48000,
        fft_size: int = 1024,
        hop_size: int = 256,
    ):
        self.sample_rate = sample_rate
        self.fft_size = fft_size
        self.hop_size = hop_size

        # Frame counter for sequencing
        self.frame_count = 0

        # Pre-compute FFT window
        self._window = np.hanning(fft_size).astype(np.float32)

        # Pre-compute frequency bin ranges for each band
        freq_per_bin = sample_rate / fft_size
        self._band_bins: List[Tuple[int, int]] = []
        for low_hz, high_hz in BAND_RANGES:
            low_bin = max(1, int(low_hz / freq_per_bin))
            high_bin = min(fft_size // 2, int(high_hz / freq_per_bin))
            self._band_bins.append((low_bin, high_bin))

        # Sample accumulation buffer
        self._sample_buffer = np.zeros(fft_size, dtype=np.float32)
        self._buffer_pos = 0

        # Spectrum state
        self._prev_spectrum = np.zeros(fft_size // 2 + 1, dtype=np.float32)
        self._raw_bands = np.zeros(5, dtype=np.float32)
        self._smoothed_bands = np.zeros(5, dtype=np.float32)

        # Smoothing (attack/release)
        self._band_attack = 0.4
        self._band_release = 0.08
        self._noise_floor = 0.02

        # Per-band AGC history
        self._band_histories: List[deque] = [deque(maxlen=180) for _ in range(5)]
        self._band_max = np.full(5, 0.1, dtype=np.float32)

        # Spectral flux for onset detection
        self._flux_histories: List[deque] = [deque(maxlen=30) for _ in range(5)]
        self._onset_threshold = 1.5
        self._min_onset_intervals = [0.20, 0.12, 0.10, 0.08, 0.06]
        self._last_onset_time = np.zeros(5)
        self._onsets = [False] * 5

        # BPM estimation
        self._onset_times: deque = deque(maxlen=64)
        self._ioi_history: deque = deque(maxlen=32)
        self._tempo_histogram = np.zeros(200, dtype=np.float32)  # 40-240 BPM
        self._histogram_decay = 0.995
        self._estimated_bpm = 120.0
        self._bpm_confidence = 0.0

        # Beat phase tracking
        self._beat_phase = 0.0
        self._last_beat_time = 0.0

        # Bass lane
        self._bass_lane = BassLane(
            sample_rate=sample_rate,
            cutoff_hz=120.0,
            attack_ms=1.0,
            release_ms=50.0,
        )
        self._instant_bass = 0.0
        self._instant_kick = False

    def feed_pcm(self, pcm_bytes: bytes, channels: int = 2) -> Optional[dict]:
        """
        Feed raw PCM audio bytes and run FFT analysis.

        Args:
            pcm_bytes: Raw s16le PCM bytes (Discord's 48kHz stereo format)
            channels: Number of audio channels (default 2 for stereo)

        Returns:
            Dict with dj_audio_frame fields, or None if not enough samples yet.
        """
        # Convert int16 PCM bytes to float32 [-1.0, 1.0]
        samples = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32) / 32768.0

        # Convert to mono
        if channels >= 2 and len(samples) >= 2:
            mono = samples.reshape(-1, channels).mean(axis=1)
        else:
            mono = samples

        now = time.time()

        # Feed bass lane for instant detection (before FFT buffering)
        bass_energy, kick_onset, _ = self._bass_lane.process_samples(mono, now)
        self._instant_bass = bass_energy
        if kick_onset:
            self._instant_kick = True

        # Accumulate into FFT buffer
        remaining = mono
        result = None

        while len(remaining) > 0:
            space = self.fft_size - self._buffer_pos
            to_add = min(len(remaining), space)
            self._sample_buffer[self._buffer_pos : self._buffer_pos + to_add] = remaining[:to_add]
            self._buffer_pos += to_add
            remaining = remaining[to_add:]

            # Run FFT when buffer is full
            if self._buffer_pos >= self.fft_size:
                result = self._analyze_buffer(now)
                # Shift by hop_size
                overlap = self.fft_size - self.hop_size
                self._sample_buffer[:overlap] = self._sample_buffer[self.hop_size :]
                self._buffer_pos = overlap

        return result

    def _analyze_buffer(self, timestamp: float) -> dict:
        """Run FFT analysis on the current buffer and return a result dict."""
        buffer = self._sample_buffer.copy()

        # Windowed FFT
        windowed = buffer * self._window
        spectrum = np.abs(np.fft.rfft(windowed)).astype(np.float32)

        # Peak level
        peak = float(np.max(np.abs(buffer)))

        # Extract band magnitudes
        if peak < self._noise_floor:
            # Silence - decay smoothly
            for i in range(5):
                self._smoothed_bands[i] *= 1.0 - self._band_release
            self._raw_bands[:] = 0
            normalized_bands = np.zeros(5, dtype=np.float32)
        else:
            for i, (low_bin, high_bin) in enumerate(self._band_bins):
                if high_bin > low_bin:
                    self._raw_bands[i] = np.mean(spectrum[low_bin:high_bin])
                else:
                    self._raw_bands[i] = 0.0

            # Update AGC history
            for i in range(5):
                mag = self._raw_bands[i]
                if mag > self._noise_floor * 10:
                    self._band_histories[i].append(mag)

                if len(self._band_histories[i]) > 20:
                    p95 = float(np.percentile(list(self._band_histories[i]), 95))
                    self._band_max[i] = p95 * 1.1 + 0.001
                elif len(self._band_histories[i]) > 5:
                    self._band_max[i] = max(self._band_histories[i]) * 1.2 + 0.001

            # Normalize
            normalized_bands = np.minimum(1.0, self._raw_bands / self._band_max)

            # Attack/release smoothing
            for i in range(5):
                target = normalized_bands[i]
                current = self._smoothed_bands[i]
                if target > current:
                    self._smoothed_bands[i] = current + (target - current) * self._band_attack
                else:
                    self._smoothed_bands[i] = current + (target - current) * self._band_release

        # Spectral flux per band
        band_flux = np.zeros(5, dtype=np.float32)
        flux_diff = np.maximum(0, spectrum - self._prev_spectrum)
        for i, (low_bin, high_bin) in enumerate(self._band_bins):
            if high_bin > low_bin:
                band_flux[i] = np.mean(flux_diff[low_bin:high_bin])

        # Total spectral flux
        spectral_flux = float(np.mean(flux_diff))

        # Save spectrum for next frame
        self._prev_spectrum[:] = spectrum

        # Onset detection per band
        for i in range(5):
            fv = band_flux[i]
            self._flux_histories[i].append(fv)
            self._onsets[i] = False

            if len(self._flux_histories[i]) > 5:
                hist = self._flux_histories[i]
                total = sum(hist) - hist[-1]
                avg_flux = total / (len(hist) - 1) if len(hist) > 1 else 0.0
                threshold = avg_flux * self._onset_threshold + 0.001

                if (
                    fv > threshold
                    and timestamp - self._last_onset_time[i] > self._min_onset_intervals[i]
                ):
                    self._onsets[i] = True
                    self._last_onset_time[i] = timestamp

        # Beat detection (kick = bass onset)
        kick_onset_fft = self._onsets[0]
        beat = kick_onset_fft or self._instant_kick

        # Update BPM on kick onset
        if kick_onset_fft:
            self._update_bpm(timestamp)

        # Beat phase (0-1 ramp between beats)
        if self._estimated_bpm > 0 and self._last_beat_time > 0:
            beat_period = 60.0 / self._estimated_bpm
            elapsed = timestamp - self._last_beat_time
            self._beat_phase = (elapsed / beat_period) % 1.0

        if beat:
            self._last_beat_time = timestamp
            self._beat_phase = 0.0

        # Capture and reset instant kick
        i_kick = self._instant_kick
        self._instant_kick = False

        self.frame_count += 1

        return {
            "bands": [round(float(b), 4) for b in self._smoothed_bands],
            "peak": round(peak, 4),
            "beat": beat,
            "beat_i": round(spectral_flux, 4),
            "bpm": round(self._estimated_bpm, 1),
            "tempo_conf": round(self._bpm_confidence, 3),
            "beat_phase": round(self._beat_phase, 3),
            "i_bass": round(self._instant_bass, 4),
            "i_kick": i_kick,
        }

    def _update_bpm(self, current_time: float):
        """Update BPM estimate from kick onset timing."""
        if self._onset_times:
            ioi = current_time - self._onset_times[-1]
            # Only consider valid tempo range (250ms to 1500ms = 40-240 BPM)
            if 0.25 <= ioi <= 1.5:
                # Filter out IOIs that don't fit established tempo
                if len(self._ioi_history) >= 4 and self._bpm_confidence > 0.3:
                    expected_ioi = 60.0 / self._estimated_bpm
                    ratios = [
                        ioi / expected_ioi,
                        ioi / (expected_ioi * 2),
                        ioi / (expected_ioi * 0.5),
                    ]
                    best_ratio = min(ratios, key=lambda r: abs(r - 1.0))
                    if abs(best_ratio - 1.0) > 0.20:
                        self._onset_times.append(current_time)
                        return

                self._ioi_history.append(ioi)
                self._update_tempo_histogram(ioi)

        self._onset_times.append(current_time)

    def _update_tempo_histogram(self, ioi: float):
        """Update tempo histogram with Gaussian voting."""
        self._tempo_histogram *= self._histogram_decay

        bpm = 60.0 / ioi
        for multiplier in [0.5, 1.0, 2.0]:
            candidate = bpm * multiplier
            if 40 <= candidate <= 240:
                bin_idx = int(candidate - 40)
                for offset in range(-3, 4):
                    idx = bin_idx + offset
                    if 0 <= idx < 200:
                        weight = np.exp(-0.5 * (offset / 1.5) ** 2)
                        if 80 <= candidate <= 160:
                            weight *= 1.5
                        self._tempo_histogram[idx] += weight

        self._estimated_bpm, self._bpm_confidence = self._estimate_bpm()

    def _estimate_bpm(self) -> Tuple[float, float]:
        """Extract dominant tempo from histogram."""
        if len(self._ioi_history) < 4:
            return self._estimated_bpm, 0.0

        if np.max(self._tempo_histogram) < 1.0:
            return self._estimated_bpm, 0.0

        peak_idx = int(np.argmax(self._tempo_histogram))
        peak_bpm = float(peak_idx + 40)

        # Refine via weighted average around peak
        start = max(0, peak_idx - 2)
        end = min(200, peak_idx + 3)
        weights = self._tempo_histogram[start:end]
        if np.sum(weights) > 0:
            indices = np.arange(start, end) + 40
            peak_bpm = float(np.average(indices, weights=weights))

        # Confidence
        peak_height = self._tempo_histogram[peak_idx]
        mean_height = float(np.mean(self._tempo_histogram))
        prominence = peak_height / (mean_height + 0.001)
        sample_conf = min(1.0, len(self._ioi_history) / 16.0)

        if len(self._ioi_history) >= 4:
            recent = list(self._ioi_history)[-8:]
            ioi_std = float(np.std(recent))
            ioi_mean = float(np.mean(recent))
            cv = ioi_std / (ioi_mean + 0.001)
            consistency = max(0.0, 1.0 - cv * 2)
        else:
            consistency = 0.5

        confidence = min(1.0, (prominence / 15.0) * sample_conf * consistency)
        return peak_bpm, confidence
