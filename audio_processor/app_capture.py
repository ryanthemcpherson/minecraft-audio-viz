"""
Per-Application Audio Capture using pycaw
Captures audio levels from a specific Windows application (e.g., Spotify).

Requirements:
    pip install pycaw comtypes websockets

Usage:
    python -m audio_processor.app_capture --app spotify
"""

import argparse
import asyncio
import os
import signal
import sys

# Fix Windows console encoding for unicode characters (spectrograph uses Ã¢â€“Ë†Ã¢â€“â€˜Ã¢â€”Â etc)
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
import http.server
import json
import logging
import math
import socketserver
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set

import numpy as np

# Optional aubio for optimized beat detection
try:
    import aubio

    HAS_AUBIO = True
except ImportError:
    HAS_AUBIO = False
    aubio = None

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from audio_processor.patterns import PATTERNS, AudioState, PatternConfig, get_pattern, list_patterns
from audio_processor.spectrograph import TerminalSpectrograph
from python_client.viz_client import VizClient

try:
    import websockets
    from websockets.server import serve as ws_serve

    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False

# Optional FFT analyzer
try:
    from audio_processor.fft_analyzer import FFTResult, HybridAnalyzer

    HAS_FFT = True
except ImportError:
    HAS_FFT = False
    HybridAnalyzer = None
    FFTResult = None

# Optional timeline engine
try:
    from audio_processor.timeline import Cue, Show, TimelineEngine, TimelineState
    from audio_processor.timeline.cue_executor import CueExecutor
    from audio_processor.timeline.show_storage import ShowStorage

    HAS_TIMELINE = True
except ImportError:
    HAS_TIMELINE = False
    TimelineEngine = None
    TimelineState = None
    Show = None

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("app_capture")


# === AUDIO PRESETS ===
# Pre-tuned settings for different music genres/styles
PRESETS = {
    "auto": {
        "attack": 0.35,
        "release": 0.08,
        "beat_threshold": 1.3,
        "agc_max_gain": 8.0,
        "beat_sensitivity": 1.0,
        "bass_weight": 0.7,  # Balanced bass weight
        "band_sensitivity": [1.0, 1.0, 1.0, 1.0, 1.0],  # [bass, low_mid, mid, high_mid, high]
        "auto_calibrate": True,  # Future: self-tuning
    },
    "edm": {
        "attack": 0.7,  # Fast attack for punchy beats
        "release": 0.15,  # Quick decay for fast BPM
        "beat_threshold": 1.1,  # Lower threshold = more beats detected
        "agc_max_gain": 10.0,  # Higher gain for dynamic range
        "beat_sensitivity": 1.5,  # Stronger beat response
        "bass_weight": 0.85,  # Heavy bass focus for EDM kicks
        "band_sensitivity": [1.5, 0.8, 0.9, 1.2, 1.0],  # Boost bass
        "auto_calibrate": False,
    },
    "chill": {
        "attack": 0.25,  # Slower attack for smoother response
        "release": 0.05,  # Smooth decay
        "beat_threshold": 1.6,  # Higher threshold = fewer beats
        "agc_max_gain": 6.0,
        "beat_sensitivity": 0.7,
        "bass_weight": 0.5,  # Less bass focus, more balanced
        "band_sensitivity": [0.9, 1.0, 1.1, 1.2, 1.3],  # Boost highs
        "auto_calibrate": False,
    },
    "rock": {
        "attack": 0.5,
        "release": 0.12,
        "beat_threshold": 1.3,
        "agc_max_gain": 8.0,
        "beat_sensitivity": 1.2,
        "bass_weight": 0.65,  # Drum-focused
        "band_sensitivity": [1.2, 1.0, 1.0, 0.9, 0.8],  # Guitar/drums focus
        "auto_calibrate": False,
    },
}


@dataclass
class AppAudioFrame:
    """Audio frame from application capture."""

    timestamp: float
    peak: float  # Peak level (0-1)
    channels: List[float]  # Per-channel levels
    is_beat: bool
    beat_intensity: float


class AubioBeatDetector:
    """
    High-performance beat detector using aubio library (C-based).
    Provides accurate BPM estimation and beat detection.

    Includes octave-error correction and histogram-based tempo stabilization.
    """

    def __init__(self, sample_rate: int = 44100, hop_size: int = 512, buf_size: int = 1024):
        """
        Initialize aubio beat detector.

        Args:
            sample_rate: Audio sample rate
            hop_size: Samples between analysis frames
            buf_size: FFT buffer size (must be power of 2)
        """
        self.sample_rate = sample_rate
        self.hop_size = hop_size
        self.buf_size = buf_size

        # Aubio tempo detector
        self.tempo = aubio.tempo("default", buf_size, hop_size, sample_rate)

        # Aubio onset detector for backup/enhancement
        self.onset = aubio.onset("energy", buf_size, hop_size, sample_rate)
        self.onset.set_threshold(0.3)

        # State tracking - longer history for stability
        self._last_bpm = 120.0
        self._stable_bpm = 120.0  # More stable estimate with octave correction
        self._bpm_history = np.zeros(60)  # Longer history for better stability
        self._bpm_idx = 0
        self._confidence = 0.0

        # Beat time tracking for our own IOI analysis
        self._beat_times = []
        self._max_beat_history = 32  # Track last 32 beats

        logger.info(f"Aubio beat detector initialized: {sample_rate}Hz, hop={hop_size}")

    def _correct_octave_errors(self, bpm: float, reference_bpm: float) -> float:
        """
        Correct octave errors by checking if BPM is a simple ratio of reference.
        Common errors: 0.5x, 0.66x, 0.75x, 1.33x, 1.5x, 2x
        """
        if reference_bpm <= 0 or bpm <= 0:
            return bpm

        ratio = bpm / reference_bpm

        # Check for common octave-related ratios
        corrections = [
            (0.5, 2.0),  # Half tempo -> double it
            (0.66, 1.5),  # 2/3 tempo -> multiply by 1.5
            (0.75, 1.333),  # 3/4 tempo -> multiply by 4/3
            (1.33, 0.75),  # 4/3 tempo -> multiply by 3/4
            (1.5, 0.666),  # 3/2 tempo -> multiply by 2/3
            (2.0, 0.5),  # Double tempo -> halve it
        ]

        for target_ratio, correction in corrections:
            if 0.95 < ratio / target_ratio < 1.05:  # Within 5% of ratio
                return bpm * correction

        return bpm

    def _estimate_tempo_from_histogram(self, bpm_values: np.ndarray) -> float:
        """
        Use histogram-based estimation to find the dominant tempo.
        More robust than simple median against octave errors.
        """
        if len(bpm_values) < 5:
            return float(np.median(bpm_values)) if len(bpm_values) > 0 else 120.0

        # Create histogram with 1 BPM resolution
        hist_min, hist_max = 60, 200
        bins = np.arange(hist_min, hist_max + 1, 1)
        hist, edges = np.histogram(bpm_values, bins=bins)

        # Apply Gaussian smoothing to histogram (reduces noise)
        from scipy.ndimage import gaussian_filter1d

        try:
            smoothed = gaussian_filter1d(hist.astype(float), sigma=2)
        except Exception:
            smoothed = hist.astype(float)

        # Find peaks in histogram
        peak_idx = np.argmax(smoothed)
        dominant_bpm = edges[peak_idx] + 0.5  # Center of bin

        return dominant_bpm

    def _analyze_beat_intervals(self) -> tuple:
        """
        Analyze beat intervals to get tempo estimate and confidence.
        Returns (bpm, confidence)
        """
        if len(self._beat_times) < 4:
            return 0.0, 0.0

        times = np.array(self._beat_times)
        intervals = np.diff(times)

        # Filter to reasonable intervals (50-180 BPM range)
        valid = intervals[(intervals > 0.333) & (intervals < 1.2)]

        if len(valid) < 3:
            return 0.0, 0.0

        # Convert to BPM
        bpms = 60.0 / valid

        # Use histogram to find dominant tempo
        dominant_bpm = self._estimate_tempo_from_histogram(bpms)

        # Calculate confidence based on how many intervals agree
        agreeing = np.sum(np.abs(bpms - dominant_bpm) < 5)  # Within 5 BPM
        confidence = agreeing / len(bpms)

        return dominant_bpm, confidence

    def process(self, samples: np.ndarray) -> tuple:
        """
        Process audio samples and detect beats.

        Args:
            samples: Audio samples as numpy array (mono, float32)

        Returns:
            (is_beat, bpm, confidence)
        """
        import time as time_module

        # Ensure correct format
        if samples.dtype != np.float32:
            samples = samples.astype(np.float32)

        # Process through tempo detector
        is_beat = self.tempo(samples)
        aubio_bpm = self.tempo.get_bpm()

        # Also check onset detector as backup
        is_onset = self.onset(samples)

        # Track beat times for our own analysis
        current_time = time_module.time()
        if is_beat:
            self._beat_times.append(current_time)
            # Keep only recent beats
            cutoff = current_time - 15.0  # Last 15 seconds
            self._beat_times = [t for t in self._beat_times if t > cutoff]

        # Update BPM history
        if 30 < aubio_bpm < 250:
            # Apply octave correction before adding to history
            corrected_bpm = self._correct_octave_errors(aubio_bpm, self._stable_bpm)

            self._bpm_history[self._bpm_idx] = corrected_bpm
            self._bpm_idx = (self._bpm_idx + 1) % len(self._bpm_history)

            # Get valid BPM values
            valid_bpms = self._bpm_history[self._bpm_history > 0]

            if len(valid_bpms) >= 10:
                # Use histogram-based estimation for stability
                histogram_bpm = self._estimate_tempo_from_histogram(valid_bpms)

                # Also get our own IOI-based estimate
                ioi_bpm, ioi_conf = self._analyze_beat_intervals()

                # Combine estimates (prefer IOI if confident)
                if ioi_conf > 0.6 and ioi_bpm > 0:
                    # Weight IOI estimate more heavily when confident
                    combined_bpm = ioi_bpm * 0.6 + histogram_bpm * 0.4
                else:
                    combined_bpm = histogram_bpm

                # Smooth the stable BPM (slow adaptation)
                alpha = 0.1  # Slower adaptation for stability
                self._stable_bpm = (1 - alpha) * self._stable_bpm + alpha * combined_bpm
                self._last_bpm = self._stable_bpm

                # Confidence based on agreement between methods
                bpm_std = float(np.std(valid_bpms))
                base_conf = max(0, min(1, 1.0 - bpm_std / 15))
                self._confidence = base_conf * 0.7 + ioi_conf * 0.3

        # Combine tempo beat and onset detection
        detected = bool(is_beat) or (bool(is_onset) and self._confidence < 0.5)

        return detected, self._last_bpm, self._confidence

    def get_bpm(self) -> float:
        """Get current estimated BPM."""
        return self._last_bpm

    def get_confidence(self) -> float:
        """Get tempo confidence (0-1)."""
        return self._confidence


class AppAudioCapture:
    """
    Captures audio levels from a specific Windows application using pycaw.

    Uses Windows Audio Session API (WASAPI) to get real-time audio meters
    from individual applications.

    Supports automatic fallback to system loopback if target app not found.
    """

    def __init__(
        self,
        app_name: str = "spotify",
        fallback_to_loopback: bool = True,
        max_retries: int = 5,
        retry_interval: float = 2.0,
    ):
        """
        Initialize app audio capture.

        Args:
            app_name: Part of the process name to match (case-insensitive)
            fallback_to_loopback: Fall back to system loopback if app not found
            max_retries: Number of retries before falling back
            retry_interval: Seconds between retry attempts
        """
        self.app_name = app_name.lower()
        self._fallback_to_loopback = fallback_to_loopback
        self._max_retries = max_retries
        self._retry_interval = retry_interval

        self._session = None
        self._meter = None
        self._using_fallback = False
        self._fallback_analyzer = None
        self._last_app_check = 0.0
        self._app_check_interval = 5.0  # Check for app every 5 seconds

        # === OPTIMIZED BEAT DETECTION STATE (NumPy arrays) ===

        # Pre-allocate NumPy arrays for performance
        self._onset_history_size = 256  # ~4 seconds at 60fps
        self._onset_history = np.zeros(self._onset_history_size, dtype=np.float64)
        self._onset_idx = 0
        self._onset_count = 0  # Track how many samples we have

        # Energy tracking
        self._prev_energy = 0.0
        self._prev_bass_energy = 0.0

        # Beat timing (NumPy array for intervals)
        self._max_beat_times = 60  # Track last 60 beats
        self._beat_times = np.zeros(self._max_beat_times, dtype=np.float64)
        self._beat_idx = 0
        self._beat_count = 0
        self._last_beat_time = 0.0

        # Tempo estimation with stabilization
        self._estimated_tempo = 120.0
        self._stable_tempo = 120.0  # Slower-moving stable estimate
        self._tempo_confidence = 0.0
        self._beat_phase = 0.0

        # BPM history for histogram analysis
        self._bpm_history = np.zeros(120, dtype=np.float64)  # ~2 seconds of estimates
        self._bpm_history_idx = 0
        self._bpm_history_count = 0

        # Adaptive threshold (adjustable via UI)
        self._beat_threshold = 1.2  # Multiplier above average flux

        # Bass weight for beat detection
        self._bass_weight = 0.85

        # Smoothing
        self._prev_peak = 0.0
        self._smoothing = 0.05

        # Optional aubio detector (much more accurate)
        self._aubio_detector = None
        self._use_aubio = False

        # Sample buffer for aubio (needs ~512 samples per call)
        self._sample_buffer = np.zeros(512, dtype=np.float32)
        self._sample_buffer_pos = 0

    def find_session(self) -> bool:
        """Find the audio session for the target application."""
        try:
            from pycaw.pycaw import AudioUtilities, IAudioMeterInformation

            sessions = AudioUtilities.GetAllSessions()

            for session in sessions:
                if session.Process:
                    process_name = session.Process.name().lower()
                    if self.app_name in process_name:
                        logger.info(
                            f"Found audio session: {session.Process.name()} (PID: {session.Process.pid})"
                        )
                        self._session = session

                        # Get the audio meter interface
                        self._meter = session._ctl.QueryInterface(IAudioMeterInformation)

                        # If we were using fallback, switch back to app capture
                        if self._using_fallback:
                            logger.info(f"Switching from fallback to app capture: {self.app_name}")
                            self._using_fallback = False
                            if self._fallback_analyzer:
                                self._fallback_analyzer.stop()
                                self._fallback_analyzer = None

                        return True

            logger.warning(f"No audio session found for '{self.app_name}'")
            return False

        except Exception as e:
            logger.error(f"Error finding session: {e}")
            return False

    def find_session_with_retry(self) -> bool:
        """
        Attempt to find session with retries, then fall back to loopback.

        Returns:
            True if session found or fallback enabled, False otherwise
        """
        for attempt in range(self._max_retries):
            if self.find_session():
                return True

            if attempt < self._max_retries - 1:
                logger.info(
                    f"Retry {attempt + 1}/{self._max_retries} for '{self.app_name}' "
                    f"in {self._retry_interval}s..."
                )
                time.sleep(self._retry_interval)

        # All retries failed - try fallback
        if self._fallback_to_loopback:
            return self._enable_fallback()

        return False

    def _enable_fallback(self) -> bool:
        """Enable fallback to system loopback capture."""
        logger.warning(f"App '{self.app_name}' not found, falling back to system loopback")

        try:
            if HAS_FFT:
                self._fallback_analyzer = HybridAnalyzer(low_latency=True, ultra_low_latency=True)
                self._using_fallback = True
                logger.info("Fallback to HybridAnalyzer (system loopback) enabled")
                return True
            else:
                logger.error("HybridAnalyzer not available for fallback")
                return False

        except Exception as e:
            logger.error(f"Failed to enable fallback: {e}")
            return False

    def check_app_available(self) -> bool:
        """
        Periodically check if target app becomes available.

        Call this periodically when using fallback to switch back to app capture.
        """
        current_time = time.time()
        if current_time - self._last_app_check < self._app_check_interval:
            return not self._using_fallback

        self._last_app_check = current_time

        if self._using_fallback:
            # Try to find the app again
            if self.find_session():
                logger.info(f"App '{self.app_name}' found, switching from fallback")
                return True

        return not self._using_fallback

    @property
    def using_fallback(self) -> bool:
        """Check if currently using fallback mode."""
        return self._using_fallback

    @property
    def fallback_analyzer(self) -> Optional["HybridAnalyzer"]:
        """Get the fallback analyzer (if in fallback mode)."""
        return self._fallback_analyzer

    def list_sessions(self) -> List[Dict]:
        """List all active audio sessions."""
        try:
            from pycaw.pycaw import AudioUtilities

            sessions = AudioUtilities.GetAllSessions()
            result = []

            for session in sessions:
                if session.Process:
                    result.append({"name": session.Process.name(), "pid": session.Process.pid})

            return result

        except Exception as e:
            logger.error(f"Error listing sessions: {e}")
            return []

    def get_peak(self) -> float:
        """Get the current peak audio level (0-1)."""
        if not self._meter:
            return 0.0

        try:
            return self._meter.GetPeakValue()
        except Exception:
            # Session may have ended
            return 0.0

    def get_channel_peaks(self) -> List[float]:
        """Get peak levels for each channel."""
        if not self._meter:
            return [0.0, 0.0]

        try:
            count = self._meter.GetMeteringChannelCount()
            peaks = self._meter.GetChannelsPeakValues(count)
            return list(peaks)
        except Exception:
            return [0.0, 0.0]

    def get_frame(self) -> AppAudioFrame:
        """Get current audio frame with beat detection."""
        peak = self.get_peak()
        channels = self.get_channel_peaks()

        # Apply smoothing
        smoothed_peak = self._prev_peak * self._smoothing + peak * (1 - self._smoothing)
        self._prev_peak = smoothed_peak

        # Estimate bass energy from stereo channels
        # Left channel often has more bass due to stereo panning conventions
        bass_estimate = max(channels) if channels else smoothed_peak

        # Beat detection with bass estimate
        is_beat, beat_intensity = self._detect_beat(smoothed_peak, bass_estimate)

        return AppAudioFrame(
            timestamp=time.time(),
            peak=smoothed_peak,
            channels=channels,
            is_beat=is_beat,
            beat_intensity=beat_intensity,
        )

    def _detect_beat(self, energy: float, bass_energy: float = None) -> tuple:
        """
        Optimized beat detection using NumPy for all statistics.
        ~10-50x faster than pure Python statistics module.

        Uses:
        1. Spectral flux onset detection (half-wave rectified)
        2. NumPy-accelerated adaptive thresholding
        3. Inter-onset interval tempo estimation
        4. Beat prediction with phase tracking
        """
        current_time = time.time()

        # Use bass energy if provided
        if bass_energy is None:
            bass_energy = energy

        # === 1. ONSET STRENGTH SIGNAL (OSS) ===
        # Half-wave rectified spectral flux (only positive changes)
        bass_flux = max(0.0, bass_energy - self._prev_bass_energy)
        full_flux = max(0.0, energy - self._prev_energy)
        self._prev_bass_energy = bass_energy
        self._prev_energy = energy

        # Combined onset strength (bass-weighted for kick detection)
        onset_strength = (bass_flux * 0.7) + (full_flux * 0.3)

        # === 2. UPDATE ONSET HISTORY (circular buffer - O(1)) ===
        self._onset_history[self._onset_idx] = onset_strength
        self._onset_idx = (self._onset_idx + 1) % self._onset_history_size
        self._onset_count = min(self._onset_count + 1, self._onset_history_size)

        # Need history for detection
        if self._onset_count < 30:
            return False, 0.0

        # === 3. ADAPTIVE THRESHOLD (NumPy - 10-50x faster than statistics) ===
        # Get the valid portion of the circular buffer
        if self._onset_count >= self._onset_history_size:
            recent_onsets = self._onset_history
        else:
            recent_onsets = self._onset_history[: self._onset_count]

        # NumPy vectorized operations (with empty array guards)
        if len(recent_onsets) > 0:
            mean_onset = float(np.mean(recent_onsets))
            std_onset = float(np.std(recent_onsets)) if len(recent_onsets) > 1 else 0.01
        else:
            mean_onset = 0.0
            std_onset = 0.01

        # Dynamic threshold: mean + multiplier * std
        threshold = mean_onset + std_onset * self._beat_threshold
        threshold = max(threshold, 0.02)

        # === 4. ONSET DETECTION (peak picking) ===
        is_onset = False
        if self._onset_count >= 5:
            # Get last 5 values from circular buffer (most recent first)
            indices = np.array(
                [(self._onset_idx - 1 - i) % self._onset_history_size for i in range(5)]
            )
            window = self._onset_history[indices]
            current = window[0]

            # Must be above threshold AND local maximum
            is_local_max = current >= np.max(window[1:])
            is_onset = current > threshold and is_local_max

        # === 5. TEMPO ESTIMATION via Inter-Onset Intervals ===
        is_beat = False
        intensity = 0.0
        time_since_last = current_time - self._last_beat_time

        if is_onset and time_since_last > 0.15:  # Min 150ms (~400 BPM max)
            # Record beat time in circular buffer
            self._beat_times[self._beat_idx] = current_time
            self._beat_idx = (self._beat_idx + 1) % self._max_beat_times
            self._beat_count = min(self._beat_count + 1, self._max_beat_times)

            # === TEMPO ESTIMATION from beat intervals ===
            if self._beat_count >= 4:
                # Get valid beat times
                if self._beat_count >= self._max_beat_times:
                    valid_times = self._beat_times.copy()
                else:
                    valid_times = self._beat_times[: self._beat_count].copy()

                # Sort times (circular buffer may be out of order)
                sorted_times = np.sort(valid_times[valid_times > 0])

                # Filter to recent beats (last 10 seconds)
                cutoff = current_time - 10.0
                recent_times = sorted_times[sorted_times > cutoff]

                if len(recent_times) >= 4:
                    # Calculate intervals using NumPy diff
                    intervals = np.diff(recent_times)

                    # Filter to reasonable range (60-200 BPM = 0.3-1.0 seconds)
                    valid_intervals = intervals[(intervals > 0.3) & (intervals < 1.0)]

                    if len(valid_intervals) >= 3:
                        # Convert intervals to BPM
                        interval_bpms = 60.0 / valid_intervals

                        # === HISTOGRAM-BASED TEMPO ESTIMATION ===
                        # More robust than median against octave errors
                        hist_min, hist_max = 60, 200
                        bins = np.arange(hist_min, hist_max + 1, 2)  # 2 BPM resolution
                        hist, edges = np.histogram(interval_bpms, bins=bins)

                        # Find dominant tempo from histogram peak
                        peak_idx = np.argmax(hist)
                        histogram_bpm = edges[peak_idx] + 1.0  # Center of bin

                        # Also calculate median for comparison
                        median_bpm = 60.0 / np.median(valid_intervals)

                        # === OCTAVE ERROR CORRECTION ===
                        # Check if median is an octave error relative to histogram
                        ratio = median_bpm / histogram_bpm if histogram_bpm > 0 else 1.0
                        if 0.48 < ratio < 0.52:  # Half tempo
                            new_tempo = median_bpm * 2
                        elif 1.95 < ratio < 2.05:  # Double tempo
                            new_tempo = median_bpm * 0.5
                        elif 0.64 < ratio < 0.68:  # 2/3 tempo
                            new_tempo = median_bpm * 1.5
                        elif 1.48 < ratio < 1.52:  # 3/2 tempo
                            new_tempo = median_bpm * 0.667
                        else:
                            # Use histogram estimate (more stable)
                            new_tempo = histogram_bpm

                        # Add to BPM history for even more stable estimation
                        self._bpm_history[self._bpm_history_idx] = new_tempo
                        self._bpm_history_idx = (self._bpm_history_idx + 1) % len(self._bpm_history)
                        self._bpm_history_count = min(
                            self._bpm_history_count + 1, len(self._bpm_history)
                        )

                        # Use stable tempo from history
                        if self._bpm_history_count >= 10:
                            valid_history = self._bpm_history[: self._bpm_history_count]
                            # Re-run histogram on history for maximum stability
                            hist2, edges2 = np.histogram(valid_history, bins=bins)
                            peak_idx2 = np.argmax(hist2)
                            stable_bpm = edges2[peak_idx2] + 1.0

                            # Very slow adaptation for stability
                            alpha = 0.08
                            self._stable_tempo = (
                                1 - alpha
                            ) * self._stable_tempo + alpha * stable_bpm
                            self._estimated_tempo = self._stable_tempo
                        else:
                            # Fast adaptation during warmup
                            alpha = 0.2
                            self._estimated_tempo = (
                                1 - alpha
                            ) * self._estimated_tempo + alpha * new_tempo

                        self._estimated_tempo = float(np.clip(self._estimated_tempo, 60, 200))

                        # Confidence from interval consistency
                        # How many intervals agree with the estimated tempo?
                        expected_interval = 60.0 / self._estimated_tempo
                        agreeing = np.sum(np.abs(valid_intervals - expected_interval) < 0.05)
                        self._tempo_confidence = float(
                            np.clip(agreeing / len(valid_intervals), 0, 1)
                        )

            # This is a beat!
            is_beat = True
            self._last_beat_time = current_time
            intensity = min(1.0, onset_strength / max(0.05, threshold))

        # === 6. BEAT PREDICTION (fill in missed beats) ===
        if self._tempo_confidence > 0.5 and not is_beat:
            beat_period = 60.0 / self._estimated_tempo
            time_since_last = current_time - self._last_beat_time

            # Check if near expected beat boundary
            expected_beats = time_since_last / beat_period
            fractional = expected_beats - int(expected_beats)

            if fractional < 0.1 or fractional > 0.9:
                if onset_strength > mean_onset * 0.8 and time_since_last > beat_period * 0.8:
                    is_beat = True
                    self._last_beat_time = current_time
                    intensity = 0.6  # Lower intensity for predicted beats

        return is_beat, intensity


class MultiDirectoryHandler(http.server.SimpleHTTPRequestHandler):
    """HTTP handler that serves from multiple directories based on URL path."""

    directory_map = {}

    def translate_path(self, path):
        """Translate URL path to file system path."""
        path = path.split("?")[0].split("#")[0]
        for url_prefix, fs_directory in self.directory_map.items():
            if path.startswith(url_prefix):
                relative_path = path[len(url_prefix) :].lstrip("/")
                return os.path.join(fs_directory, relative_path)
        if "/" in self.directory_map:
            return os.path.join(self.directory_map["/"], path.lstrip("/"))
        return super().translate_path(path)

    def log_message(self, format, *args):
        pass  # Suppress logging


def run_http_server(port: int, directory: str):
    """Run HTTP server for static files in a separate thread."""
    project_root = Path(directory).parent.parent
    admin_dir = project_root / "admin_panel"

    # Configure directory mapping
    MultiDirectoryHandler.directory_map = {
        "/admin": str(admin_dir) if admin_dir.exists() else str(directory),
        "/": str(directory),
    }

    os.chdir(str(project_root))

    with socketserver.TCPServer(("", port), MultiDirectoryHandler) as httpd:
        httpd.serve_forever()


class AppCaptureAgent:
    """
    Captures audio from a specific application and sends visualization
    data to Minecraft and browser preview.
    """

    def __init__(
        self,
        app_name: str = "spotify",
        minecraft_host: str = "localhost",
        minecraft_port: int = 8765,
        zone: str = "main",
        entity_count: int = 16,
        show_spectrograph: bool = True,
        compact_spectrograph: bool = False,
        broadcast_port: int = 8766,
        http_port: int = 8080,
        vscode_mode: bool = False,
        use_fft: bool = True,
        low_latency: bool = False,
        ultra_low_latency: bool = False,
        use_beat_prediction: bool = False,
        prediction_lookahead_ms: float = 80.0,
        tick_aligned: bool = False,
    ):

        self.app_name = app_name
        self.minecraft_host = minecraft_host
        self.minecraft_port = minecraft_port
        self.zone = zone
        self.entity_count = entity_count
        self.show_spectrograph = show_spectrograph
        self.compact_spectrograph = compact_spectrograph
        self.broadcast_port = broadcast_port
        self.http_port = http_port
        self.vscode_mode = vscode_mode
        self.use_fft = use_fft
        self.low_latency = low_latency
        self.ultra_low_latency = ultra_low_latency
        self.use_beat_prediction = use_beat_prediction
        self.prediction_lookahead_ms = prediction_lookahead_ms
        self.tick_aligned = tick_aligned

        # Minecraft tick alignment (20 TPS = 50ms per tick)
        self._mc_tick_interval = 0.050  # 50ms
        self._last_mc_send_time = 0.0
        self._next_predicted_beat = 0.0
        self._beat_phase = 0.0  # 0-1 position within beat cycle

        # Components
        self.capture = AppAudioCapture(app_name)
        self.viz_client: Optional[VizClient] = None

        if show_spectrograph:
            if compact_spectrograph:
                from audio_processor.spectrograph import CompactSpectrograph

                self.spectrograph = CompactSpectrograph()
            else:
                # Pass vscode_mode - None means auto-detect, True forces VS Code mode
                self.spectrograph = TerminalSpectrograph(vscode_mode=True if vscode_mode else None)
        else:
            self.spectrograph = None

        # Initialize FFT analyzer if available and requested
        self.fft_analyzer = None
        self._using_fft = False
        if use_fft and HAS_FFT and HybridAnalyzer is not None:
            try:
                self.fft_analyzer = HybridAnalyzer(
                    low_latency=low_latency,
                    ultra_low_latency=ultra_low_latency,
                    use_beat_prediction=use_beat_prediction,
                    prediction_lookahead_ms=prediction_lookahead_ms,
                )
                self._using_fft = self.fft_analyzer.using_fft
                if self._using_fft:
                    stats = self.fft_analyzer.latency_stats
                    fft_ms = stats.get("fft_latency_ms", 0)
                    hop_ms = stats.get("hop_interval_ms", 0)
                    if ultra_low_latency:
                        mode = "ULTRA-LOW-LATENCY"
                    elif low_latency:
                        mode = "LOW-LATENCY"
                    else:
                        mode = "NORMAL"
                    if use_beat_prediction:
                        mode += "+PREDICTION"
                    logger.info(
                        f"FFT analyzer active [{mode}] - FFT: {fft_ms:.0f}ms, Update: {hop_ms:.0f}ms"
                    )
                else:
                    logger.info("FFT analyzer initialized - will use when audio available")
            except Exception as e:
                logger.warning(f"Failed to initialize FFT analyzer: {e}")
        elif use_fft and not HAS_FFT:
            logger.info("FFT not available (install pyaudiowpatch). Using synthetic bands.")

        # Initialize aubio beat detector if available (much more accurate than custom detection)
        self._aubio_detector = None
        self._use_aubio = False
        if HAS_AUBIO:
            try:
                self._aubio_detector = AubioBeatDetector(sample_rate=44100, hop_size=512)
                self._use_aubio = True
                logger.info("Aubio beat detector initialized (production-grade tempo tracking)")
            except Exception as e:
                logger.warning(f"Failed to initialize aubio: {e}")
        else:
            logger.info("Aubio not available (pip install aubio). Using NumPy-based detection.")

        self._running = False
        self._frame_count = 0

        # WebSocket broadcast clients (browser previews)
        self._broadcast_clients: Set = set()

        # Pattern system
        self._pattern_config = PatternConfig(entity_count=entity_count)
        self._current_pattern = get_pattern("spectrum", self._pattern_config)
        self._pattern_name = "spectrum"

        # === MODERN AUDIO VISUALIZATION STATE ===

        # Rolling energy history for AGC (Auto-Gain Control)
        self._agc_history_size = 90  # ~1.5 seconds at 60fps
        self._energy_history = []  # Rolling peak values
        self._agc_gain = 1.0  # Current gain multiplier
        self._agc_target = 0.85  # Target output level (85% of range)
        self._agc_attack = 0.15  # Fast attack - respond quickly to loud
        self._agc_release = 0.008  # Very slow release - don't drop too fast
        self._agc_min_gain = 1.0  # Minimum gain (never reduce below input)
        self._agc_max_gain = 8.0  # Maximum gain boost

        # Per-band rolling history for adaptive normalization
        self._band_history_size = 45  # ~0.75 seconds per band
        self._band_histories = [[] for _ in range(5)]
        self._band_max_history = [[] for _ in range(5)]  # Track per-band peaks

        # Temporal smoothing (exponential moving average)
        self._smoothed_bands = [0.0] * 5
        self._smooth_attack = 0.35  # Fast attack (respond to increases)
        self._smooth_release = 0.08  # Slower release (decay smoothly)

        # Band simulation state
        self._prev_bands = [0.0] * 5
        self._band_velocities = [0.0] * 5

        # Per-band characteristics (5 bands: bass, low-mid, mid, high-mid, high)
        self._band_phases = [0.0, 0.5, 1.0, 1.5, 2.0]  # Phase offsets
        self._band_speeds = [1.0, 1.5, 2.2, 3.0, 4.0]  # Oscillation speeds
        self._band_decay = [0.92, 0.88, 0.82, 0.72, 0.60]  # Energy decay rates
        self._band_energy = [0.0] * 5
        self._band_drift = [0.0] * 5

        # Transient detection
        self._last_peak = 0.0
        self._peak_delta = 0.0
        self._transient_history = []
        self._transient_history_size = 10

        # Beat sensitivity multiplier (adjustable via UI)
        self._beat_sensitivity = 1.0

        # Per-band sensitivity multipliers (adjustable via UI)
        self._band_sensitivity = [1.0, 1.0, 1.0, 1.0, 1.0, 1.0]

        # Current preset
        self._current_preset = "auto"

        # === AUTO-CALIBRATION STATE ===
        self._auto_calibrate_enabled = True  # Enabled by default with "auto" preset
        self._calibration_frame_count = 0
        self._calibration_warmup_frames = 180  # 3 seconds at 60fps

        # Statistics for auto-calibration
        self._calibration_energy_history = []
        self._calibration_flux_history = []
        self._calibration_beat_times = []
        self._calibration_history_size = 300  # 5 seconds of history

        # Estimated music characteristics
        self._estimated_bpm = 120.0
        self._music_variance = 0.5  # 0 = static, 1 = very dynamic
        self._last_calibration_frame = 0
        self._calibration_interval = 120  # Recalibrate every 2 seconds

        # === TIMELINE ENGINE ===
        self.timeline = None
        self.cue_executor = None
        self.show_storage = None
        self._active_effects = {}  # Active effects with end times
        self._last_entities = []  # Last frame's entities (for freeze effect)

        if HAS_TIMELINE:
            self.timeline = TimelineEngine()
            self.show_storage = ShowStorage()
            self.cue_executor = CueExecutor()

            # Wire up cue executor handlers
            self.cue_executor.set_handlers(
                pattern_handler=self._set_pattern_from_cue,
                preset_handler=self._apply_preset,
                parameter_handler=self._set_parameter_from_cue,
                effect_handler=self._trigger_effect,
            )

            # Wire up timeline callbacks
            self.timeline.set_callbacks(
                on_cue_fire=self._on_cue_fire, on_state_change=self._on_timeline_state_change
            )

            logger.info("Timeline engine initialized")

    def _set_pattern_from_cue(self, pattern_name: str):
        """Set pattern from a cue action."""
        if pattern_name in PATTERNS:
            self._pattern_name = pattern_name
            self._current_pattern = get_pattern(pattern_name, self._pattern_config)
            logger.info(f"Cue: Pattern changed to {pattern_name}")

    def _set_parameter_from_cue(self, param_name: str, value: float):
        """Set a parameter from a cue action."""
        param_map = {
            "attack": "_smooth_attack",
            "release": "_smooth_release",
            "agc_max_gain": "_agc_max_gain",
            "beat_sensitivity": "_beat_sensitivity",
        }
        if param_name in param_map:
            setattr(self, param_map[param_name], value)
            logger.info(f"Cue: Parameter {param_name} set to {value}")

    def _trigger_effect(self, effect_type: str, intensity: float, duration: int):
        """Trigger a visual effect. Intensity 0 = disable (for toggle effects)."""
        # Toggle effects (blackout, freeze): intensity 0 means turn OFF
        if intensity <= 0 and effect_type in ("blackout", "freeze"):
            if effect_type in self._active_effects:
                del self._active_effects[effect_type]
                logger.info(f"Effect disabled: {effect_type}")
                # Re-show entities when blackout ends
                if effect_type == "blackout" and self.viz_client and self.viz_client.connected:
                    asyncio.ensure_future(self.viz_client.set_visible(self.zone, True))
            return

        # For toggle effects, use a very long duration (they're disabled explicitly)
        if effect_type in ("blackout", "freeze"):
            duration = 999999999  # Effectively infinite until toggled off

        end_time = time.time() + (duration / 1000)
        self._active_effects[effect_type] = {
            "intensity": intensity,
            "end_time": end_time,
            "duration": duration,
            "start_time": time.time(),
        }
        logger.info(
            f"Effect triggered: {effect_type} (intensity={intensity}, duration={duration}ms)"
        )

        # Blackout: immediately hide all entities in Minecraft
        if effect_type == "blackout":
            if self.viz_client and self.viz_client.connected:
                asyncio.ensure_future(self.viz_client.set_visible(self.zone, False))

    def _apply_effects(self, entities: List[dict], bands: List[float], frame) -> List[dict]:
        """Apply active effects to entity output. Returns modified entities."""
        if not self._active_effects:
            return entities

        now = time.time()

        # Blackout: return empty entities (hide everything)
        if "blackout" in self._active_effects:
            return []

        # Freeze: return last frame's entities (don't update)
        if "freeze" in self._active_effects:
            if hasattr(self, "_last_entities") and self._last_entities:
                return self._last_entities
            return entities

        modified = [dict(e) for e in entities]  # Shallow copy

        for effect_type, effect in self._active_effects.items():
            intensity = effect["intensity"]
            elapsed = now - effect["start_time"]
            duration_s = effect["duration"] / 1000.0
            progress = min(1.0, elapsed / duration_s) if duration_s > 0 else 1.0

            if effect_type == "flash":
                # Flash: boost all entity scales to max, fade over duration
                flash_mult = intensity * (1.0 - progress)
                for e in modified:
                    e["scale"] = min(1.0, e.get("scale", 0.5) + flash_mult * 0.5)
                    e["y"] = min(1.0, e.get("y", 0) + flash_mult * 0.2)

            elif effect_type == "strobe":
                # Strobe: rapid on/off toggling (8Hz)
                strobe_on = int(elapsed * 8) % 2 == 0
                if not strobe_on:
                    for e in modified:
                        e["scale"] = 0.01  # Nearly invisible

            elif effect_type == "pulse":
                # Pulse: rhythmic scale oscillation
                pulse_val = math.sin(elapsed * math.pi * 4) * intensity
                for e in modified:
                    base_scale = e.get("scale", 0.5)
                    e["scale"] = max(0.05, base_scale * (1.0 + pulse_val * 0.5))

            elif effect_type == "wave":
                # Wave: ripple through entities based on position
                for i, e in enumerate(modified):
                    phase = (i / max(1, len(modified))) * math.pi * 2
                    wave_val = math.sin(elapsed * 3.0 + phase) * intensity
                    e["y"] = max(0, min(1.0, e.get("y", 0) + wave_val * 0.3))

            elif effect_type == "spiral":
                # Spiral: rotate entities in a spiral pattern
                for i, e in enumerate(modified):
                    angle = elapsed * 2.0 + (i / max(1, len(modified))) * math.pi * 2
                    radius = 0.3 * intensity * (1.0 - progress * 0.5)
                    e["x"] = max(0, min(1.0, 0.5 + math.cos(angle) * radius))
                    e["z"] = max(0, min(1.0, 0.5 + math.sin(angle) * radius))

            elif effect_type == "explode":
                # Explode: push all entities outward from center, then fade
                explode_force = intensity * (1.0 - progress)
                for e in modified:
                    dx = e.get("x", 0.5) - 0.5
                    dy = e.get("y", 0.5) - 0.5
                    dz = e.get("z", 0.5) - 0.5
                    dist = max(0.1, (dx * dx + dy * dy + dz * dz) ** 0.5)
                    force = explode_force / dist * 0.3
                    e["x"] = max(0, min(1.0, e.get("x", 0.5) + dx * force))
                    e["y"] = max(0, min(1.0, e.get("y", 0.5) + dy * force))
                    e["z"] = max(0, min(1.0, e.get("z", 0.5) + dz * force))
                    e["scale"] = max(0.05, e.get("scale", 0.5) * (1.0 + explode_force * 0.5))

        return modified

    def _on_cue_fire(self, cue):
        """Called when a cue fires."""
        if self.cue_executor:
            self.cue_executor.execute(cue)

    def _on_timeline_state_change(self, state):
        """Called when timeline state changes."""
        logger.info(f"Timeline state: {state.value}")

    def _apply_preset(self, preset_name: str):
        """Apply a preset configuration."""
        if preset_name not in PRESETS:
            logger.warning(f"Unknown preset: {preset_name}")
            return

        preset = PRESETS[preset_name]
        logger.info(f"Applying preset: {preset_name}")

        # Apply all preset settings
        self._smooth_attack = preset["attack"]
        self._smooth_release = preset["release"]
        self.capture._beat_threshold = preset["beat_threshold"]
        self._agc_max_gain = preset["agc_max_gain"]
        self._beat_sensitivity = preset["beat_sensitivity"]
        self._band_sensitivity = preset["band_sensitivity"].copy()

        # Apply bass weight for beat detection
        if "bass_weight" in preset:
            self.capture._bass_weight = preset["bass_weight"]

        # Store current preset name
        self._current_preset = preset_name

        logger.info(f"  Attack: {self._smooth_attack}, Release: {self._smooth_release}")
        logger.info(
            f"  Beat threshold: {self.capture._beat_threshold}, Bass weight: {self.capture._bass_weight}"
        )

        # Enable/disable auto-calibration based on preset
        self._auto_calibrate_enabled = preset.get("auto_calibrate", False)
        if self._auto_calibrate_enabled:
            logger.info("  Auto-calibration: ENABLED")

    def _auto_calibrate(self, energy: float, is_beat: bool):
        """
        Auto-calibration system that adapts to music characteristics.
        Called every frame when auto_calibrate is enabled.
        Optimized with NumPy for fast statistics.
        """
        self._calibration_frame_count += 1

        # Update calibration history
        self._calibration_energy_history.append(energy)
        if len(self._calibration_energy_history) > self._calibration_history_size:
            self._calibration_energy_history.pop(0)

        # Track beat times for tempo estimation
        if is_beat:
            self._calibration_beat_times.append(self._calibration_frame_count)
            # Keep only recent beats
            cutoff = self._calibration_frame_count - self._calibration_history_size
            self._calibration_beat_times = [t for t in self._calibration_beat_times if t > cutoff]

        # Don't calibrate during warmup
        if self._calibration_frame_count < self._calibration_warmup_frames:
            return

        # Only recalibrate periodically
        frames_since_calibration = self._calibration_frame_count - self._last_calibration_frame
        if frames_since_calibration < self._calibration_interval:
            return

        self._last_calibration_frame = self._calibration_frame_count

        # Need enough data
        if len(self._calibration_energy_history) < 60:
            return

        # === ESTIMATE MUSIC CHARACTERISTICS (NumPy optimized) ===

        # 1. Energy statistics - NumPy is 10-50x faster
        energy_arr = np.array(self._calibration_energy_history)
        if len(energy_arr) == 0:
            return
        energy_mean = float(np.mean(energy_arr))
        energy_stdev = float(np.std(energy_arr)) if len(energy_arr) > 1 else 0.1
        energy_max = float(np.max(energy_arr))

        # Variance ratio: high = dynamic music, low = static
        self._music_variance = min(1.0, energy_stdev / max(0.01, energy_mean) * 2)

        # 2. Tempo estimation from beat intervals
        if len(self._calibration_beat_times) >= 4:
            beat_arr = np.array(self._calibration_beat_times)
            intervals = np.diff(beat_arr)

            # Filter to valid range: 40-240 BPM = 15-90 frames at 60fps
            valid_intervals = intervals[(intervals >= 15) & (intervals <= 90)]

            if len(valid_intervals) > 0:
                avg_interval_frames = np.median(valid_intervals)
                # Convert frames to BPM (at 60fps)
                # Only update BPM if FFT analyzer isn't providing a confident estimate
                # (FFT BPM is set before _auto_calibrate is called)
                auto_cal_bpm = 60 * 60 / avg_interval_frames
                # Use auto-cal BPM only as fallback when FFT BPM hasn't converged yet
                if not self._using_fft or self._estimated_bpm == 120.0:
                    self._estimated_bpm = auto_cal_bpm

        # === APPLY AUTO-ADJUSTMENTS ===

        # 3. Adjust attack/release based on tempo
        if self._estimated_bpm > 150:
            # Fast music (EDM, drum & bass): fast attack, quick release
            target_attack = 0.6
            target_release = 0.12
        elif self._estimated_bpm > 110:
            # Medium tempo (house, pop): balanced
            target_attack = 0.4
            target_release = 0.08
        else:
            # Slow music (chill, ambient): slow attack, smooth release
            target_attack = 0.25
            target_release = 0.05

        # Smooth transition to new values
        self._smooth_attack += (target_attack - self._smooth_attack) * 0.1
        self._smooth_release += (target_release - self._smooth_release) * 0.1

        # 4. Adjust beat threshold based on music variance
        # High variance = need higher threshold to avoid false positives
        # Low variance = can use lower threshold for more sensitivity
        target_threshold = 1.1 + self._music_variance * 0.5  # Range: 1.1 to 1.6

        self.capture._beat_threshold += (target_threshold - self.capture._beat_threshold) * 0.15

        # 5. Adjust AGC based on overall energy levels
        if energy_max < 0.3:
            # Quiet music: boost more
            target_agc = min(12.0, self._agc_max_gain + 1)
        elif energy_max > 0.8:
            # Loud music: reduce boost
            target_agc = max(4.0, self._agc_max_gain - 1)
        else:
            target_agc = 8.0

        self._agc_max_gain += (target_agc - self._agc_max_gain) * 0.1

        # Log calibration results periodically
        if self._calibration_frame_count % 300 == 0:  # Every 5 seconds
            logger.debug(
                f"Auto-cal: BPMÃ¢â€°Ë†{self._estimated_bpm:.0f}, var={self._music_variance:.2f}, "
                f"thresh={self.capture._beat_threshold:.2f}, attack={self._smooth_attack:.2f}"
            )

    def _apply_audio_setting(self, setting: str, value: float):
        """Apply an audio reactivity setting from the UI."""
        if setting == "attack":
            # Attack: how fast bands respond to increases (0.1 to 0.9)
            self._smooth_attack = max(0.1, min(0.9, value))
            logger.info(f"Attack set to: {self._smooth_attack:.2f}")

        elif setting == "release":
            # Release: how fast bands decay (0.02 to 0.3)
            self._smooth_release = max(0.02, min(0.3, value))
            logger.info(f"Release set to: {self._smooth_release:.2f}")

        elif setting == "agc_max_gain":
            # Max AGC gain boost (1x to 12x)
            self._agc_max_gain = max(1.0, min(12.0, value))
            logger.info(f"AGC max gain set to: {self._agc_max_gain:.1f}x")

        elif setting == "beat_sensitivity":
            # Beat response multiplier (0.5x to 2x)
            self._beat_sensitivity = max(0.5, min(2.0, value))
            logger.info(f"Beat sensitivity set to: {self._beat_sensitivity:.2f}x")

        elif setting == "beat_threshold":
            # Beat detection threshold (0.8x to 2.0x) - higher = fewer beats detected
            self.capture._beat_threshold = max(0.8, min(2.0, value))
            logger.info(f"Beat threshold set to: {self.capture._beat_threshold:.2f}x")

    async def _handle_broadcast_client(self, websocket):
        """Handle a browser preview WebSocket connection."""
        self._broadcast_clients.add(websocket)
        logger.info(f"Browser preview connected. Clients: {len(self._broadcast_clients)}")

        # Send available patterns on connect
        await websocket.send(
            json.dumps(
                {"type": "patterns", "patterns": list_patterns(), "current": self._pattern_name}
            )
        )

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get("type")

                    if msg_type == "ping":
                        await websocket.send(json.dumps({"type": "pong"}))

                    elif msg_type == "set_pattern":
                        # Switch visualization pattern
                        pattern_name = data.get("pattern", "spectrum")
                        if pattern_name in PATTERNS:
                            self._pattern_name = pattern_name
                            self._current_pattern = get_pattern(pattern_name, self._pattern_config)
                            logger.info(f"Switched to pattern: {pattern_name}")
                            # Broadcast pattern change to all clients
                            await self._broadcast_pattern_change()

                    elif msg_type == "set_block_count":
                        # Change entity count dynamically
                        count = data.get("count", 16)
                        count = max(8, min(64, count))  # Clamp between 8 and 64
                        self.entity_count = count
                        self._pattern_config = PatternConfig(entity_count=count)
                        self._current_pattern = get_pattern(
                            self._pattern_name, self._pattern_config
                        )
                        logger.info(f"Set block count to: {count}")
                        # Also resize Minecraft entity pool
                        if self.viz_client and self.viz_client.connected:
                            try:
                                await self.viz_client.init_pool(self.zone, count)
                            except Exception as e:
                                logger.warning(f"Failed to resize MC pool: {e}")

                    elif msg_type in (
                        "set_render_mode",
                        "set_zone_config",
                        "set_renderer_backend",
                        "get_renderer_capabilities",
                        "renderer_capabilities",
                        "set_particle_viz_config",
                        "set_particle_config",
                        "set_particle_effect",
                        "set_hologram_config",
                        "set_entity_glow",
                        "set_entity_brightness",
                    ):
                        # Forward zone/rendering messages to Minecraft
                        if self.viz_client and self.viz_client.connected:
                            try:
                                # Add zone field if missing (for particle effect toggles)
                                if "zone" not in data:
                                    data["zone"] = self.zone
                                response = await self.viz_client.send(data)
                                if response:
                                    await websocket.send(json.dumps(response))
                            except Exception as e:
                                logger.warning(f"Failed to forward {msg_type} to MC: {e}")

                    elif msg_type == "set_audio_setting":
                        # Update audio reactivity settings
                        setting = data.get("setting")
                        value = data.get("value")
                        self._apply_audio_setting(setting, value)

                    elif msg_type == "set_band_sensitivity":
                        # Update per-band sensitivity (5 bands)
                        band = data.get("band", 0)
                        sensitivity = data.get("sensitivity", 1.0)
                        if 0 <= band < 5:
                            self._band_sensitivity[band] = max(0.0, min(2.0, sensitivity))
                            band_names = ["Bass", "Low", "Mid", "High", "Air"]
                            logger.info(
                                f"{band_names[band]} sensitivity: {self._band_sensitivity[band]:.0%}"
                            )

                    elif msg_type == "set_preset":
                        # Apply preset configuration
                        preset_name = data.get("preset", "auto")
                        self._apply_preset(preset_name)
                        # Broadcast preset change to all clients
                        await self._broadcast_preset_change(preset_name)

                    elif msg_type == "get_patterns":
                        await websocket.send(
                            json.dumps(
                                {
                                    "type": "patterns",
                                    "patterns": list_patterns(),
                                    "current": self._pattern_name,
                                }
                            )
                        )

                    elif msg_type == "get_state":
                        # Send full state snapshot
                        await websocket.send(
                            json.dumps(
                                {
                                    "type": "state_snapshot",
                                    "pattern": self._pattern_name,
                                    "preset": self._current_preset,
                                    "patterns": list_patterns(),
                                    "timeline": self.timeline.get_status()
                                    if self.timeline
                                    else None,
                                    "settings": {
                                        "attack": self._smooth_attack,
                                        "release": self._smooth_release,
                                        "agc_max_gain": self._agc_max_gain,
                                        "beat_sensitivity": self._beat_sensitivity,
                                        "beat_threshold": self.capture._beat_threshold,
                                        "band_sensitivity": self._band_sensitivity,
                                    },
                                }
                            )
                        )

                    # === TIMELINE MESSAGES ===
                    elif msg_type == "timeline_play":
                        if self.timeline:
                            self.timeline.play()
                            await self._broadcast_timeline_status()

                    elif msg_type == "timeline_pause":
                        if self.timeline:
                            self.timeline.pause()
                            await self._broadcast_timeline_status()

                    elif msg_type == "timeline_stop":
                        if self.timeline:
                            self.timeline.stop()
                            await self._broadcast_timeline_status()

                    elif msg_type == "timeline_seek":
                        if self.timeline:
                            position = data.get("position", 0)
                            self.timeline.seek(position)
                            await self._broadcast_timeline_status()

                    elif msg_type == "load_show":
                        if self.timeline and self.show_storage:
                            show_id = data.get("show_id")
                            show_data = data.get("show")
                            if show_data:
                                # Load from provided data
                                show = Show.from_dict(show_data)
                            elif show_id:
                                # Load from storage
                                show = self.show_storage.load(show_id)
                            else:
                                show = None

                            if show:
                                self.timeline.load_show(show)
                                await websocket.send(
                                    json.dumps({"type": "show_loaded", "show": show.to_dict()})
                                )
                            await self._broadcast_timeline_status()

                    elif msg_type == "save_show":
                        if self.timeline and self.show_storage and self.timeline.show:
                            filepath = self.show_storage.save(self.timeline.show)
                            await websocket.send(
                                json.dumps({"type": "show_saved", "filepath": filepath})
                            )

                    elif msg_type == "list_shows":
                        if self.show_storage:
                            shows = self.show_storage.list_shows()
                            await websocket.send(json.dumps({"type": "show_list", "shows": shows}))

                    elif msg_type == "new_show":
                        if self.timeline:
                            name = data.get("name", "New Show")
                            duration = data.get("duration", 180000)
                            bpm = data.get("bpm", 128.0)
                            show = Show(name=name, duration=duration, bpm=bpm)
                            self.timeline.load_show(show)
                            await websocket.send(
                                json.dumps({"type": "show_loaded", "show": show.to_dict()})
                            )
                            await self._broadcast_timeline_status()

                    elif msg_type == "create_demo_show":
                        if self.timeline and self.show_storage:
                            show = self.show_storage.create_demo_show()
                            self.timeline.load_show(show)
                            await websocket.send(
                                json.dumps({"type": "show_loaded", "show": show.to_dict()})
                            )
                            await self._broadcast_timeline_status()

                    elif msg_type == "fire_cue":
                        if self.timeline:
                            cue_id = data.get("cue_id")
                            if cue_id:
                                self.timeline.fire_cue(cue_id)

                    elif msg_type == "arm_cue":
                        if self.timeline:
                            cue_id = data.get("cue_id")
                            armed = data.get("armed", True)
                            if cue_id:
                                self.timeline.arm_cue(cue_id, armed)

                    elif msg_type == "trigger_effect":
                        # Instant effect trigger from UI
                        effect_type = data.get("effect", "flash")
                        intensity = data.get("intensity", 1.0)
                        duration = data.get("duration", 500)
                        self._trigger_effect(effect_type, intensity, duration)

                    elif msg_type == "add_cue":
                        if self.timeline and self.timeline.show:
                            cue_data = data.get("cue", {})
                            track_type = cue_data.get("track", "patterns")
                            track = self.timeline.show.get_track(track_type)
                            if track:
                                cue = Cue.from_dict(cue_data)
                                track.add_cue(cue)
                                await websocket.send(
                                    json.dumps({"type": "cue_added", "cue": cue.to_dict()})
                                )

                    elif msg_type == "update_cue":
                        if self.timeline and self.timeline.show:
                            cue_id = data.get("cue_id")
                            updates = data.get("updates", {})
                            for track in self.timeline.show.tracks:
                                for cue in track.cues:
                                    if cue.id == cue_id:
                                        # Apply updates
                                        if "start_time" in updates:
                                            cue.start_time = updates["start_time"]
                                        if "duration" in updates:
                                            cue.duration = updates["duration"]
                                        if "name" in updates:
                                            cue.name = updates["name"]
                                        await websocket.send(
                                            json.dumps(
                                                {"type": "cue_updated", "cue": cue.to_dict()}
                                            )
                                        )
                                        break

                    elif msg_type == "delete_cue":
                        if self.timeline and self.timeline.show:
                            cue_id = data.get("cue_id")
                            for track in self.timeline.show.tracks:
                                if track.remove_cue(cue_id):
                                    await websocket.send(
                                        json.dumps({"type": "cue_deleted", "cue_id": cue_id})
                                    )
                                    break

                except json.JSONDecodeError:
                    pass
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self._broadcast_clients.discard(websocket)
            logger.info(f"Browser preview disconnected. Clients: {len(self._broadcast_clients)}")

    async def _broadcast_pattern_change(self):
        """Broadcast pattern change to all connected clients."""
        message = json.dumps(
            {"type": "pattern_changed", "pattern": self._pattern_name, "patterns": list_patterns()}
        )
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                self._broadcast_clients.discard(client)

    async def _broadcast_preset_change(self, preset_name: str):
        """Broadcast preset change to all connected clients."""
        preset = PRESETS.get(preset_name, PRESETS["auto"])
        message = json.dumps(
            {
                "type": "preset_changed",
                "preset": preset_name,
                "settings": {
                    "attack": preset["attack"],
                    "release": preset["release"],
                    "beat_threshold": preset["beat_threshold"],
                    "agc_max_gain": preset["agc_max_gain"],
                    "beat_sensitivity": preset["beat_sensitivity"],
                    "band_sensitivity": preset["band_sensitivity"],
                },
            }
        )
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                self._broadcast_clients.discard(client)

    async def _broadcast_timeline_status(self):
        """Broadcast timeline status to all connected clients."""
        if not self._broadcast_clients or not self.timeline:
            return

        message = json.dumps({"type": "timeline_status", **self.timeline.get_status()})
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                self._broadcast_clients.discard(client)

    async def _broadcast_state(
        self, entities: List[dict], bands: List[float], frame: AppAudioFrame
    ):
        """Broadcast visualization state to all connected browser previews."""
        if not self._broadcast_clients:
            return

        # Get latency stats from FFT analyzer
        latency_ms = 0.0
        if self.fft_analyzer and hasattr(self.fft_analyzer, "latency_ms"):
            latency_ms = self.fft_analyzer.latency_ms

        message = json.dumps(
            {
                "type": "state",
                "entities": entities,
                "bands": bands,
                "amplitude": frame.peak,
                "is_beat": frame.is_beat,
                "beat_intensity": frame.beat_intensity,
                "frame": self._frame_count,
                "pattern": self._pattern_name,
                "low_latency": self.capture.low_latency
                if hasattr(self.capture, "low_latency")
                else False,
                "latency_ms": round(latency_ms, 1),
                "bpm": round(self._estimated_bpm, 1),
            }
        )

        # Send to all clients
        for client in list(self._broadcast_clients):
            try:
                await client.send(message)
            except Exception:
                self._broadcast_clients.discard(client)

    async def _start_broadcast_server(self):
        """Start WebSocket server for browser previews."""
        if not HAS_WEBSOCKETS:
            logger.warning("websockets not installed, browser preview disabled")
            return

        try:
            server = await ws_serve(self._handle_broadcast_client, "0.0.0.0", self.broadcast_port)
            logger.info(f"Browser preview server at ws://localhost:{self.broadcast_port}")
            return server
        except Exception as e:
            logger.error(f"Failed to start broadcast server: {e}")
            return None

    async def connect_minecraft(self) -> bool:
        """Connect to Minecraft server."""
        self.viz_client = VizClient(self.minecraft_host, self.minecraft_port)

        if not await self.viz_client.connect():
            logger.error(
                f"Failed to connect to Minecraft at {self.minecraft_host}:{self.minecraft_port}"
            )
            return False

        logger.info(f"Connected to Minecraft at {self.minecraft_host}:{self.minecraft_port}")

        # Check zone
        zones = await self.viz_client.get_zones()
        zone_names = [z["name"] for z in zones]

        if self.zone not in zone_names:
            if zone_names:
                self.zone = zone_names[0]
                logger.info(f"Using zone: {self.zone}")
            else:
                logger.error("No zones available!")
                return False

        # Initialize entity pool
        await self.viz_client.init_pool(self.zone, self.entity_count, "SEA_LANTERN")
        await asyncio.sleep(0.5)

        return True

    def _update_agc(self, peak: float) -> float:
        """
        Auto-Gain Control: Dynamically adjust gain based on rolling history.
        Returns the gain-adjusted peak value.
        Optimized with NumPy for fast percentile calculation.
        """
        # Add to energy history
        self._energy_history.append(peak)
        if len(self._energy_history) > self._agc_history_size:
            self._energy_history.pop(0)

        if len(self._energy_history) < 10:
            # Not enough history yet - use moderate boost
            return min(1.0, peak * 2.0)

        # Calculate rolling statistics with NumPy (much faster)
        energy_arr = np.array(self._energy_history)
        if len(energy_arr) == 0:
            return min(1.0, peak * 2.0)
        float(np.max(energy_arr))
        rolling_avg = float(np.mean(energy_arr))
        rolling_p90 = float(np.percentile(energy_arr, 90))

        # Use 90th percentile as reference (ignores occasional spikes)
        reference = max(rolling_p90, rolling_avg * 1.2, 0.05)  # Floor at 0.05

        # Calculate ideal gain to reach target level
        ideal_gain = self._agc_target / reference if reference > 0 else 1.0
        ideal_gain = float(np.clip(ideal_gain, self._agc_min_gain, self._agc_max_gain))

        # Apply attack/release smoothing
        if ideal_gain > self._agc_gain:
            # Attack: increase gain quickly
            self._agc_gain += (ideal_gain - self._agc_gain) * self._agc_attack
        else:
            # Release: decrease gain slowly
            self._agc_gain += (ideal_gain - self._agc_gain) * self._agc_release

        # Apply gain and clamp
        return min(1.0, peak * self._agc_gain)

    def _generate_bands(self, peak: float, channels: List[float], is_beat: bool) -> List[float]:
        """
        Generate reactive frequency bands using modern visualization techniques:
        - Auto-Gain Control (AGC) for consistent output levels
        - Per-band rolling history for adaptive normalization
        - Temporal smoothing with attack/release
        - Logarithmic-inspired scaling
        - Variance-based beat response
        Optimized with NumPy for fast statistics.
        """
        import random

        # === 1. AUTO-GAIN CONTROL ===
        # Normalize input based on rolling history
        agc_peak = self._update_agc(peak)

        # === 2. TRANSIENT DETECTION ===
        self._peak_delta = peak - self._last_peak
        self._last_peak = peak

        # Track transient history for variance-based response
        self._transient_history.append(abs(self._peak_delta))
        if len(self._transient_history) > self._transient_history_size:
            self._transient_history.pop(0)

        transient_energy = sum(self._transient_history) / max(1, len(self._transient_history))

        # === 3. STEREO ANALYSIS ===
        stereo_spread = abs(channels[0] - channels[1]) if len(channels) >= 2 else 0

        bands = []

        # 5 bands: bass, low-mid, mid, high-mid, high
        for i in range(5):
            # === 4. PHASE MODULATION (organic movement) ===
            self._band_phases[i] += self._band_speeds[i] * 0.016
            phase_value = math.sin(self._band_phases[i])  # -1 to 1

            # === 5. BASE ENERGY CALCULATION ===
            # Different frequency bands respond differently to the signal
            if i == 0:
                # Bass (includes kick): sustained, powerful
                base_energy = agc_peak * 1.0
                transient_mult = 3.0  # Less transient sensitive
            elif i < 3:
                # Low-mid and mid: balanced
                base_energy = agc_peak * 0.9
                transient_mult = 6.0  # More transient sensitive
            else:
                # High-mid and high: sparkly, transient-heavy
                base_energy = agc_peak * 0.75
                transient_mult = 10.0  # Very transient sensitive

            # Add transient energy
            base_energy += transient_energy * transient_mult

            # === 6. PHASE MODULATION ===
            phase_influence = 0.15 + (i * 0.05)  # 0.15 to 0.4
            modulated_energy = base_energy + phase_value * phase_influence * agc_peak

            # === 7. PER-BAND HISTORY & ADAPTIVE NORMALIZATION ===
            self._band_histories[i].append(modulated_energy)
            if len(self._band_histories[i]) > self._band_history_size:
                self._band_histories[i].pop(0)

            # Normalize band based on its own recent history (NumPy optimized)
            if len(self._band_histories[i]) >= 5:
                band_arr = np.array(self._band_histories[i])
                if len(band_arr) > 0:
                    band_max = float(np.max(band_arr))
                    band_avg = float(np.mean(band_arr))
                    # Use average of max and current for smoother normalization
                    norm_reference = max(band_max * 0.7 + band_avg * 0.3, 0.1)
                    normalized = modulated_energy / norm_reference
                else:
                    normalized = modulated_energy
            else:
                normalized = modulated_energy

            # === 8. ENERGY DECAY (peak hold with decay) ===
            decay = self._band_decay[i]
            self._band_energy[i] = max(normalized, self._band_energy[i] * decay)

            # === 9. ORGANIC DRIFT (subtle random walk) ===
            drift_speed = 0.08 + (i * 0.03)
            self._band_drift[i] += random.uniform(-drift_speed, drift_speed) * 0.016
            self._band_drift[i] *= 0.95  # Dampen drift
            self._band_drift[i] = max(-0.15, min(0.15, self._band_drift[i]))

            # === 10. BEAT RESPONSE ===
            beat_boost = 0.0
            if is_beat:
                # Variance-based beat intensity (NumPy optimized)
                if len(self._energy_history) > 5:
                    recent_energy = (
                        np.array(self._energy_history[-15:])
                        if len(self._energy_history) >= 15
                        else np.array(self._energy_history)
                    )
                    if len(recent_energy) > 1:
                        variance = float(np.var(recent_energy))
                    else:
                        variance = 0.01
                    # Adaptive threshold from research: threshold = -15 * var + 1.55
                    beat_multiplier = max(0.5, min(1.5, 1.0 + variance * 5))
                else:
                    beat_multiplier = 1.0

                # Apply user-adjustable beat sensitivity
                beat_multiplier *= self._beat_sensitivity

                # 5-band beat response: bass, low-mid, mid, high-mid, high
                if i == 0:  # Bass: massive boom (combined kick)
                    beat_boost = 0.5 * beat_multiplier
                elif i == 1:  # Low-mid: solid hit
                    beat_boost = 0.38 * beat_multiplier
                elif i == 2:  # Mid: clear flash
                    beat_boost = 0.28 * beat_multiplier
                elif i == 3:  # High-mid: sparkle
                    beat_boost = 0.2 * beat_multiplier + random.uniform(0, 0.1)
                else:  # High: shimmer
                    beat_boost = 0.15 * beat_multiplier + random.uniform(0, 0.12)

            # === 11. STEREO VARIATION ===
            stereo_var = stereo_spread * (0.15 if i % 2 == 0 else 0.2)

            # === 12. COMBINE ALL FACTORS ===
            target = self._band_energy[i] + self._band_drift[i] + beat_boost + stereo_var

            # === 13. TEMPORAL SMOOTHING (EMA with attack/release) ===
            # Attack fast (react to increases), release slow (smooth decay)
            if target > self._smoothed_bands[i]:
                smooth_factor = self._smooth_attack
            else:
                smooth_factor = self._smooth_release

            self._smoothed_bands[i] += (target - self._smoothed_bands[i]) * smooth_factor

            # === 14. SPRING PHYSICS FOR FINAL OUTPUT ===
            spring = 50.0 + (i * 10)  # 50 to 100
            damping = 6.0 + (i * 0.5)
            dt = 0.016

            displacement = self._smoothed_bands[i] - self._prev_bands[i]
            force = spring * displacement - damping * self._band_velocities[i]

            self._band_velocities[i] += force * dt
            new_value = self._prev_bands[i] + self._band_velocities[i] * dt

            # === 15. APPLY PER-BAND SENSITIVITY ===
            new_value *= self._band_sensitivity[i]

            # === 16. FINAL CLAMPING (prevent stuck at ceiling) ===
            # Use soft ceiling to prevent constant max
            if new_value > 0.92:
                new_value = 0.92 + (new_value - 0.92) * 0.3  # Compress above 92%
            new_value = max(0, min(0.98, new_value))  # Hard limit at 98%

            self._prev_bands[i] = new_value
            bands.append(new_value)

        return bands

    async def run(self):
        """Run the capture agent."""
        # Start HTTP server for frontend in background thread (unless disabled)
        if self.http_port > 0:
            frontend_dir = Path(__file__).parent.parent / "preview_tool" / "frontend"
            if frontend_dir.exists():
                http_thread = threading.Thread(
                    target=run_http_server, args=(self.http_port, str(frontend_dir)), daemon=True
                )
                http_thread.start()
                logger.info(f"HTTP server at http://localhost:{self.http_port}")
            else:
                logger.warning(f"Frontend directory not found: {frontend_dir}")

        # Start broadcast server for browser previews
        await self._start_broadcast_server()

        # Find the app's audio session
        logger.info(f"Looking for '{self.app_name}' audio session...")

        if not self.capture.find_session():
            logger.info("\nAvailable audio sessions:")
            sessions = self.capture.list_sessions()
            for s in sessions:
                logger.info(f"  - {s['name']} (PID: {s['pid']})")

            if not sessions:
                logger.error("No audio sessions found. Make sure the app is playing audio.")
            return

        self._running = True

        logger.info("Starting capture... Press Ctrl+C to stop")
        logger.info(f"Open http://localhost:{self.http_port} in your browser for visualization")

        try:
            while self._running:
                self._frame_count += 1

                # Get audio frame from pycaw (peak levels + synthetic beat detection)
                frame = self.capture.get_frame()

                # Try to get FFT data for real frequency analysis
                fft_result = None
                if self.fft_analyzer is not None:
                    fft_result = self.fft_analyzer.analyze(
                        synthetic_peak=frame.peak,
                        synthetic_bands=None,  # Will generate if needed
                    )
                    # Update using_fft status
                    self._using_fft = self.fft_analyzer.using_fft

                # === AUBIO BEAT DETECTION (if available) ===
                # Aubio provides production-grade tempo tracking
                aubio_beat = False
                aubio_bpm = 0.0
                if self._use_aubio and self._aubio_detector and self._using_fft:
                    # Get audio samples from FFT analyzer's buffer if available
                    if (
                        hasattr(self.fft_analyzer, "fft_analyzer")
                        and self.fft_analyzer.fft_analyzer
                    ):
                        fft_inner = self.fft_analyzer.fft_analyzer
                        if hasattr(fft_inner, "_sample_buffer") and fft_inner._buffer_pos >= 512:
                            # Use the sample buffer from FFT analyzer
                            samples = fft_inner._sample_buffer[:512].copy()
                            aubio_beat, aubio_bpm, aubio_conf = self._aubio_detector.process(
                                samples
                            )

                            # Use aubio's BPM estimate (more accurate)
                            if aubio_bpm > 30 and aubio_bpm < 250:
                                self._estimated_bpm = aubio_bpm
                                self.capture._tempo_confidence = aubio_conf

                            # Enhance beat detection with aubio
                            if aubio_beat and not frame.is_beat:
                                frame.is_beat = True
                                frame.beat_intensity = max(frame.beat_intensity, 0.85)

                # Use FFT analyzer's BPM estimate when aubio is not available
                if fft_result is not None and self._using_fft:
                    # Only use FFT BPM if aubio didn't provide one and confidence > 0.1
                    # (Low threshold because we want to show BPM early, even if not fully stable)
                    if (aubio_bpm <= 30 or aubio_bpm >= 250) and fft_result.bpm_confidence > 0.1:
                        if 40 <= fft_result.estimated_bpm <= 240:
                            self._estimated_bpm = fft_result.estimated_bpm
                            self.capture._tempo_confidence = fft_result.bpm_confidence

                # Use FFT bands if available, otherwise synthetic
                if fft_result is not None and self._using_fft:
                    bands = fft_result.bands

                    # Enhance beat detection with FFT onset info
                    if fft_result.kick_onset and not frame.is_beat:
                        # FFT detected a kick that pycaw missed
                        frame.is_beat = True
                        frame.beat_intensity = max(frame.beat_intensity, 0.8)
                else:
                    # Fall back to synthetic bands
                    bands = self._generate_bands(frame.peak, frame.channels, frame.is_beat)

                # Auto-calibrate if enabled (adjusts parameters based on music)
                if self._auto_calibrate_enabled:
                    self._auto_calibrate(frame.peak, frame.is_beat)

                # Update timeline engine
                if self.timeline:
                    self.timeline.update()
                    # Notify timeline of beats for beat-triggered cues
                    if frame.is_beat:
                        self.timeline.on_beat(frame.beat_intensity)

                # Clean up expired effects
                now = time.time()
                expired = [k for k, v in self._active_effects.items() if now >= v["end_time"]]
                for k in expired:
                    # Re-show entities when blackout ends
                    if k == "blackout" and self.viz_client and self.viz_client.connected:
                        asyncio.ensure_future(self.viz_client.set_visible(self.zone, True))
                    del self._active_effects[k]

                # Calculate entity positions ONCE (single source of truth)
                entities = self._calculate_entities(bands, frame)

                # Apply active effects (blackout, freeze, strobe, etc.)
                entities = self._apply_effects(entities, bands, frame)
                self._last_entities = entities

                # Update spectrograph
                if self.spectrograph:
                    # Update stats for display
                    self.spectrograph.set_stats(
                        preset=self._current_preset,
                        bpm=self._estimated_bpm,
                        variance=self._music_variance,
                        attack=self._smooth_attack,
                        release=self._smooth_release,
                        threshold=self.capture._beat_threshold,
                        clients=len(self._broadcast_clients),
                        using_fft=self._using_fft,
                    )
                    self.spectrograph.display(
                        bands=bands,
                        amplitude=frame.peak,
                        is_beat=frame.is_beat,
                        beat_intensity=frame.beat_intensity,
                    )

                # Send to Minecraft (tick-aligned or immediate)
                if self.viz_client and self.viz_client.connected:
                    if self.tick_aligned:
                        # Only send at Minecraft tick boundaries (20 TPS)
                        current_time = time.time()
                        time_since_last_send = current_time - self._last_mc_send_time

                        if time_since_last_send >= self._mc_tick_interval:
                            # Predict beat for next tick using BPM
                            predicted_frame = self._predict_beat_state(frame, bands)
                            await self._update_minecraft(entities, bands, predicted_frame)
                            self._last_mc_send_time = current_time
                    else:
                        await self._update_minecraft(entities, bands, frame)

                # Send same entities to browser previews
                await self._broadcast_state(entities, bands, frame)

                # Tick-aligned mode: sleep to align with MC ticks, otherwise 60 FPS
                if self.tick_aligned:
                    # Calculate time until next tick
                    elapsed = time.time() - self._last_mc_send_time
                    sleep_time = max(
                        0.001, self._mc_tick_interval - elapsed - 0.005
                    )  # Wake 5ms early
                    await asyncio.sleep(sleep_time)
                else:
                    await asyncio.sleep(0.016)  # ~60 FPS

        except Exception as e:
            logger.error(f"Capture error: {e}")
            raise

    def _predict_beat_state(self, frame: AppAudioFrame, bands: List[float]) -> AppAudioFrame:
        """
        Predict beat state for the next Minecraft tick using BPM.

        Since Minecraft only updates at 20 TPS (50ms), we can predict if a beat
        will occur in the next tick and send the beat state early so it arrives
        on time.
        """
        current_time = time.time()

        # Calculate beat interval from BPM
        beat_interval = 60.0 / max(60, self._estimated_bpm)  # seconds per beat

        # Update beat phase (0-1 position in beat cycle)
        if frame.is_beat:
            self._beat_phase = 0.0
            self._next_predicted_beat = current_time + beat_interval
        else:
            # Advance phase based on time
            time_since_beat = current_time - (self._next_predicted_beat - beat_interval)
            self._beat_phase = min(1.0, time_since_beat / beat_interval)

        # Predict if a beat will occur within the next tick (50ms)
        time_to_next_beat = self._next_predicted_beat - current_time
        predict_beat = 0 < time_to_next_beat <= self._mc_tick_interval

        # If we predict a beat, boost the intensity
        if predict_beat and not frame.is_beat:
            # Create modified frame with predicted beat
            return AppAudioFrame(
                peak=frame.peak,
                bands=frame.bands,
                is_beat=True,
                beat_intensity=max(
                    0.7, frame.beat_intensity
                ),  # Predicted beats get decent intensity
                raw_amplitude=frame.raw_amplitude,
                low_frequency=frame.low_frequency,
                high_frequency=frame.high_frequency,
                spectral_flux=frame.spectral_flux,
            )

        return frame

    def _calculate_entities(self, bands: List[float], frame: AppAudioFrame) -> List[dict]:
        """Calculate entity positions using the current pattern."""
        # Create audio state for pattern
        audio_state = AudioState(
            bands=bands,
            amplitude=frame.peak,
            is_beat=frame.is_beat,
            beat_intensity=frame.beat_intensity,
            frame=self._frame_count,
        )

        # Use pattern to calculate entity positions
        return self._current_pattern.calculate_entities(audio_state)

    async def _update_minecraft(
        self, entities: List[dict], bands: List[float], frame: AppAudioFrame
    ):
        """Send pre-calculated entities to Minecraft."""
        try:
            particles = []
            if frame.is_beat and frame.beat_intensity > 0.2:
                particles.append(
                    {
                        "particle": "NOTE",
                        "x": 0.5,
                        "y": 0.5,
                        "z": 0.5,
                        "count": int(20 * frame.beat_intensity),
                    }
                )

            # Include audio data for redstone sensors
            audio_data = {
                "bands": bands,
                "amplitude": frame.peak,
                "is_beat": frame.is_beat,
                "beat_intensity": frame.beat_intensity,
            }

            # Debug: log first audio send
            if not hasattr(self, "_audio_send_logged"):
                self._audio_send_logged = True
                logger.info(
                    f"Sending to MC with audio: bands[1]={bands[1]:.3f}, beat={frame.is_beat}"
                )

            await self.viz_client.batch_update_fast(
                self.zone, entities, particles, audio=audio_data
            )

        except Exception as e:
            logger.error(f"Minecraft update error: {e}")

    def stop(self):
        """Stop the capture agent."""
        self._running = False

    async def cleanup(self):
        """Clean up resources."""
        if self.viz_client and self.viz_client.connected:
            await self.viz_client.set_visible(self.zone, False)
            await self.viz_client.disconnect()

        if self.fft_analyzer:
            self.fft_analyzer.stop()

        if self.spectrograph:
            self.spectrograph.clear()


async def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="AudioViz Per-App Capture - Captures audio from a specific application"
    )
    parser.add_argument(
        "--app", type=str, default="spotify", help="Application name to capture (default: spotify)"
    )
    parser.add_argument(
        "--host",
        type=str,
        default="192.168.208.1",
        help="Minecraft server IP (default: 192.168.208.1)",
    )
    parser.add_argument("--port", type=int, default=8765, help="WebSocket port (default: 8765)")
    parser.add_argument(
        "--zone", type=str, default="main", help="Visualization zone name (default: main)"
    )
    parser.add_argument(
        "--entities", type=int, default=16, help="Number of visualization entities (default: 16)"
    )
    parser.add_argument(
        "--no-spectrograph", action="store_true", help="Disable terminal spectrograph"
    )
    parser.add_argument(
        "--compact", action="store_true", help="Use compact single-line spectrograph instead of TUI"
    )
    parser.add_argument(
        "--broadcast-port",
        type=int,
        default=8766,
        help="WebSocket port for browser preview (default: 8766)",
    )
    parser.add_argument(
        "--http-port", type=int, default=8080, help="HTTP port for web interface (default: 8080)"
    )
    parser.add_argument(
        "--no-http",
        action="store_true",
        help="Disable built-in HTTP server (use external dev server)",
    )
    parser.add_argument("--list", action="store_true", help="List active audio sessions and exit")
    parser.add_argument(
        "--no-minecraft",
        action="store_true",
        help="Run without Minecraft connection (spectrograph only)",
    )
    parser.add_argument(
        "--vscode",
        action="store_true",
        help="VS Code terminal compatibility mode (auto-detected usually)",
    )
    parser.add_argument(
        "--no-fft", action="store_true", help="Disable FFT analysis (use synthetic bands only)"
    )
    parser.add_argument(
        "--low-latency",
        action="store_true",
        help="Use low-latency FFT mode (~25ms total, smaller buffers)",
    )
    parser.add_argument(
        "--ultra-low-latency",
        action="store_true",
        help="Ultra-low-latency mode (~15ms, WASAPI exclusive, reduced bass)",
    )
    parser.add_argument(
        "--tick-aligned",
        action="store_true",
        help="Align updates to Minecraft 20 TPS with beat prediction",
    )
    parser.add_argument(
        "--list-audio", action="store_true", help="List available audio devices and exit"
    )

    args = parser.parse_args()

    # Check for pycaw
    try:
        import importlib.util

        if importlib.util.find_spec("pycaw") is None:
            raise ImportError("pycaw not found")
    except ImportError:
        logger.error("pycaw not installed. Run: pip install pycaw comtypes")
        sys.exit(1)

    # List audio devices mode
    if args.list_audio:
        if HAS_FFT:
            from audio_processor.fft_analyzer import list_audio_devices

            list_audio_devices()
        else:
            print("FFT not available. Install sounddevice: pip install sounddevice")
        sys.exit(0)

    # List mode
    if args.list:
        print("\nActive audio sessions:")
        print("-" * 40)
        capture = AppAudioCapture("")
        sessions = capture.list_sessions()
        if sessions:
            for s in sessions:
                print(f"  {s['name']:30} (PID: {s['pid']})")
        else:
            print("  No active audio sessions found.")
            print("  Make sure an application is playing audio.")
        print("-" * 40)
        sys.exit(0)

    # Create agent
    agent = AppCaptureAgent(
        app_name=args.app,
        minecraft_host=args.host,
        minecraft_port=args.port,
        zone=args.zone,
        entity_count=args.entities,
        show_spectrograph=not args.no_spectrograph,
        compact_spectrograph=args.compact,
        broadcast_port=args.broadcast_port,
        http_port=0 if args.no_http else args.http_port,
        vscode_mode=args.vscode,
        use_fft=not args.no_fft,
        low_latency=args.low_latency,
        ultra_low_latency=getattr(args, "ultra_low_latency", False),
        tick_aligned=args.tick_aligned,
    )

    # Signal handler
    def signal_handler(sig, frame):
        agent.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    # Connect to Minecraft (optional)
    if not args.no_minecraft:
        logger.info(f"Connecting to Minecraft at {args.host}:{args.port}...")
        if not await agent.connect_minecraft():
            logger.warning("Continuing without Minecraft connection...")

    # Run
    try:
        await agent.run()
    finally:
        await agent.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
