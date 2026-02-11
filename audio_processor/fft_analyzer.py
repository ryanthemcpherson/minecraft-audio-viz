"""
Real-time FFT analyzer with WASAPI loopback capture.
Provides true frequency band analysis and per-band onset detection.

Supports two capture backends:
1. pyaudiowpatch - True WASAPI loopback (recommended, no virtual cable needed)
2. sounddevice - Fallback using input devices (requires Stereo Mix or virtual cable)
"""

import logging
import queue
import threading
import time
from collections import deque
from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np
from scipy.signal import lfilter

# Try to import ring buffer
try:
    from audio_processor.ringbuffer import BufferStats, SPSCRingBuffer

    HAS_RINGBUFFER = True
except ImportError:
    HAS_RINGBUFFER = False
    SPSCRingBuffer = None
    BufferStats = None

logger = logging.getLogger(__name__)

# Optional high-performance FFT libraries
# Priority: spectrograms (Rust/FFTW) > pyfftw (FFTW) > numpy (fallback)

# Try spectrograms library (Rust backend with FFTW)
try:
    import spectrograms as sg

    HAS_SPECTROGRAMS = True
except ImportError:
    HAS_SPECTROGRAMS = False
    sg = None

# Try pyfftw (FFTW backend with reusable plans)
try:
    import pyfftw

    HAS_PYFFTW = True
except ImportError:
    HAS_PYFFTW = False
    pyfftw = None


@dataclass
class FFTResult:
    """Result from FFT analysis."""

    bands: List[float]  # 5 frequency bands (0-1 normalized)
    raw_bands: List[float]  # Raw band magnitudes (not normalized)
    peak: float  # Overall peak level
    spectral_flux: float  # Full spectrum change rate
    band_flux: List[float]  # Per-band spectral flux
    onsets: List[bool]  # Per-band onset detection
    kick_onset: bool  # Detected kick drum (bass band)
    snare_onset: bool  # Detected snare
    hihat_onset: bool  # Detected hi-hat
    timestamp: float  # When this was captured
    estimated_bpm: float = 120.0  # Estimated tempo in BPM
    bpm_confidence: float = 0.0  # Confidence in BPM estimate (0-1)
    # Bass lane (ultra-fast detection, ~1ms latency)
    instant_bass: float = 0.0  # Bass lane energy (0-1)
    instant_kick_onset: bool = False  # Fast kick detection from bass lane


class BassLane:
    """
    Ultra-fast bass detection lane using IIR filters.

    Processes raw audio samples BEFORE FFT buffering to achieve ~1ms latency
    for kick detection, compared to ~15-20ms for FFT-based detection.

    Features:
    - Single-pole IIR lowpass at configurable cutoff (default 120Hz)
    - Envelope follower with fast attack (1ms), slow release (50ms)
    - Onset detection on positive envelope slope

    Usage:
        bass_lane = BassLane(sample_rate=44100, cutoff_hz=120.0)
        energy, is_onset, strength = bass_lane.process_samples(audio, timestamp)
    """

    def __init__(
        self,
        sample_rate: int = 44100,
        cutoff_hz: float = 120.0,
        attack_ms: float = 1.0,
        release_ms: float = 50.0,
        onset_threshold: float = 0.15,
    ):
        """
        Initialize bass lane processor.

        Args:
            sample_rate: Audio sample rate
            cutoff_hz: Lowpass cutoff frequency for bass isolation
            attack_ms: Envelope attack time in milliseconds
            release_ms: Envelope release time in milliseconds
            onset_threshold: Minimum envelope delta to trigger onset
        """
        self.sample_rate = sample_rate
        self.cutoff_hz = cutoff_hz

        # Calculate IIR filter coefficient for lowpass
        # Single-pole IIR: y[n] = alpha * x[n] + (1 - alpha) * y[n-1]
        # alpha = 1 - exp(-2*pi*fc/fs) for single-pole lowpass
        rc = 1.0 / (2.0 * np.pi * cutoff_hz)
        dt = 1.0 / sample_rate
        self._lp_alpha = dt / (rc + dt)

        # Envelope follower coefficients
        # attack: how fast envelope rises to match signal
        # release: how fast envelope decays when signal drops
        self._attack_coef = 1.0 - np.exp(-1.0 / (sample_rate * attack_ms / 1000.0))
        self._release_coef = 1.0 - np.exp(-1.0 / (sample_rate * release_ms / 1000.0))

        # State variables
        self._lp_state = 0.0  # Lowpass filter state
        self._envelope = 0.0  # Current envelope value
        self._prev_envelope = 0.0  # Previous envelope (for slope detection)
        self._peak_envelope = 0.0  # Peak tracking for normalization

        # Onset detection state
        self._onset_threshold = onset_threshold
        self._last_onset_time = 0.0
        self._min_onset_interval = 0.15  # 150ms = max 400 BPM (safety)

        # Normalization with AGC
        self._max_envelope_history: deque = deque(maxlen=300)  # ~5 sec history

        logger.debug(
            f"BassLane initialized: cutoff={cutoff_hz}Hz, "
            f"attack={attack_ms}ms, release={release_ms}ms"
        )

    def process_samples(self, samples: np.ndarray, timestamp: float) -> Tuple[float, bool, float]:
        """
        Process audio samples through bass lane.

        Args:
            samples: Audio samples (mono or stereo, will be converted to mono)
            timestamp: Current timestamp

        Returns:
            Tuple of (bass_energy, is_onset, onset_strength)
            - bass_energy: Normalized bass energy (0-1)
            - is_onset: True if kick onset detected
            - onset_strength: Strength of onset (0-1)
        """
        # Convert to mono if stereo
        if samples.ndim > 1:
            mono = np.mean(samples, axis=1 if samples.shape[1] <= 2 else 0)
        else:
            mono = samples

        # Process each sample through lowpass and envelope follower
        # Using vectorized operations where possible for performance
        n_samples = len(mono)
        if n_samples == 0:
            return 0.0, False, 0.0

        # Vectorized lowpass filter (IIR single-pole) using scipy.signal.lfilter
        # Filter: y[n] = alpha * x[n] + (1-alpha) * y[n-1]
        # In transfer function form: b = [alpha], a = [1, -(1-alpha)]
        alpha = self._lp_alpha
        b_coef = np.array([alpha], dtype=np.float64)
        a_coef = np.array([1.0, -(1.0 - alpha)], dtype=np.float64)
        zi = np.array([self._lp_state * (1.0 - alpha)], dtype=np.float64)
        lp_out, zf = lfilter(b_coef, a_coef, mono.astype(np.float64), zi=zi)
        lp_out = lp_out.astype(np.float32)
        self._lp_state = float(lp_out[-1])

        # Rectify (take absolute value for envelope)
        rectified = np.abs(lp_out)

        # Envelope follower (attack/release) - vectorized
        # Process in a single pass using numpy where possible
        # The envelope follower is inherently sequential (each sample depends on previous),
        # but we can still use a tight loop with scalar ops that avoids Python object overhead
        envelope = self._envelope
        attack_c = self._attack_coef
        release_c = self._release_coef
        for i in range(n_samples):
            r = float(rectified[i])
            if r > envelope:
                envelope += attack_c * (r - envelope)
            else:
                envelope += release_c * (r - envelope)

        # Store previous envelope for slope detection
        prev_envelope = self._prev_envelope
        self._prev_envelope = self._envelope
        self._envelope = envelope

        # Update peak tracking for normalization
        if envelope > self._peak_envelope:
            self._peak_envelope = envelope
        self._max_envelope_history.append(envelope)

        # Adaptive normalization using recent peak
        if len(self._max_envelope_history) > 30:
            recent_max = max(self._max_envelope_history)
            # Decay peak slowly toward recent max
            self._peak_envelope = max(self._peak_envelope * 0.999, recent_max * 1.1)

        # Normalize to 0-1
        norm_envelope = envelope / (self._peak_envelope + 1e-6)
        norm_envelope = min(1.0, norm_envelope)

        # Onset detection: positive slope above threshold
        envelope_delta = envelope - prev_envelope
        is_onset = False
        onset_strength = 0.0

        if envelope_delta > self._onset_threshold * self._peak_envelope:
            # Check minimum interval since last onset
            if timestamp - self._last_onset_time > self._min_onset_interval:
                is_onset = True
                onset_strength = min(1.0, envelope_delta / (self._peak_envelope * 0.5 + 1e-6))
                self._last_onset_time = timestamp

        return norm_envelope, is_onset, onset_strength

    def reset(self):
        """Reset filter state."""
        self._lp_state = 0.0
        self._envelope = 0.0
        self._prev_envelope = 0.0
        self._peak_envelope = 0.0
        self._max_envelope_history.clear()

    @property
    def current_envelope(self) -> float:
        """Get current envelope value."""
        return self._envelope

    @property
    def normalized_envelope(self) -> float:
        """Get normalized envelope (0-1)."""
        return min(1.0, self._envelope / (self._peak_envelope + 1e-6))


class FFTAnalyzer:
    """
    Real-time FFT analyzer using WASAPI loopback capture.

    Captures system audio output and performs frequency analysis
    for accurate visualization and beat detection.
    """

    # Frequency band definitions (Hz) - 5-band system (ultra-low-latency default)
    # Sub-bass removed since 1024 FFT can't accurately detect <43Hz
    BAND_RANGES = [
        (40, 250),  # Bass (kick drums, bass guitar, toms)
        (250, 500),  # Low-mid (snare body, vocals)
        (500, 2000),  # Mid (vocals, instruments)
        (2000, 6000),  # High-mid (presence, snare crack)
        (6000, 20000),  # High (hi-hats, cymbals, air)
    ]

    # Latency mode presets - ultra is now default for lowest latency
    LATENCY_PRESETS = {
        "normal": {
            "fft_size": 2048,  # 43ms window - legacy mode
            "hop_size": 512,  # 11ms updates
            "buffer_frames": 512,
            "queue_size": 12,
        },
        "low": {
            "fft_size": 1024,  # 21ms window - balanced
            "hop_size": 256,  # 5ms updates
            "buffer_frames": 256,
            "queue_size": 6,
        },
        "ultra": {
            "fft_size": 1024,  # 21ms window - lowest latency (DEFAULT)
            "hop_size": 128,  # 2.7ms updates
            "buffer_frames": 128,
            "queue_size": 4,
        },
    }

    def __init__(
        self,
        sample_rate: int = 44100,
        fft_size: int = 2048,
        hop_size: int = 512,
        device: Optional[str] = None,
        low_latency: bool = False,
        ultra_low_latency: bool = False,
        enable_bass_lane: bool = True,
        bass_cutoff_hz: float = 120.0,
    ):
        """
        Initialize FFT analyzer.

        Args:
            sample_rate: Audio sample rate (44100 or 48000 typical)
            fft_size: FFT window size (larger = better frequency resolution, more latency)
            hop_size: Samples between FFT frames (smaller = more responsive)
            device: Audio device name (None = auto-detect loopback)
            low_latency: If True, use smaller buffers (~25ms total vs ~45ms)
            ultra_low_latency: If True, use minimum buffers (~15ms total, reduced bass)
            enable_bass_lane: If True, use parallel bass lane for instant kick detection
            bass_cutoff_hz: Lowpass cutoff for bass lane (default 120Hz)
        """
        # Select latency preset based on explicit flags
        if ultra_low_latency:
            preset = self.LATENCY_PRESETS["ultra"]
            self._latency_mode = "ultra"
        elif low_latency:
            preset = self.LATENCY_PRESETS["low"]
            self._latency_mode = "low"
        else:
            # Default: respect constructor's fft_size/hop_size params
            preset = None
            self._latency_mode = "normal"

        if preset is not None:
            fft_size = preset["fft_size"]
            hop_size = preset["hop_size"]
            self._buffer_frames = preset["buffer_frames"]
            self._queue_size = preset["queue_size"]
        else:
            # Use caller-provided fft_size/hop_size (or constructor defaults)
            self._buffer_frames = self.LATENCY_PRESETS["normal"]["buffer_frames"]
            self._queue_size = self.LATENCY_PRESETS["normal"]["queue_size"]

        self.sample_rate = sample_rate
        self.fft_size = fft_size
        self.hop_size = hop_size
        self.device = device
        self.low_latency = low_latency or ultra_low_latency
        self.ultra_low_latency = ultra_low_latency

        # Latency tracking
        self._latency_samples: deque = deque(maxlen=60)  # Last 60 measurements
        self._audio_timestamp = 0.0  # When audio chunk arrived

        # Pre-compute FFT window (Hanning for smooth spectral analysis)
        self.window = np.hanning(fft_size).astype(np.float32)

        # Frequency bins for each band (pre-computed)
        self.band_bins = self._compute_band_bins()

        # Pre-allocate arrays for performance (5 bands: bass, low-mid, mid, high-mid, high)
        self._prev_spectrum = np.zeros(fft_size // 2 + 1, dtype=np.float32)
        self._spectrum = np.zeros(fft_size // 2 + 1, dtype=np.float32)
        self._raw_bands = np.zeros(5, dtype=np.float32)
        self._normalized_bands = np.zeros(5, dtype=np.float32)
        self._smoothed_bands = np.zeros(5, dtype=np.float32)  # Output with attack/release
        self._band_flux = np.zeros(5, dtype=np.float32)
        self._onsets = [False] * 5

        # Smoothing parameters (attack/release like synthetic system)
        self._band_attack = 0.4  # How fast bands rise (0-1, higher = faster)
        self._band_release = 0.08  # How fast bands fall (0-1, higher = faster)
        self._noise_floor = 0.02  # Ignore signals below this threshold

        # Per-band history for normalization (AGC per band) - using deque for O(1) ops
        self._band_history_size = 180  # ~3 seconds at 60fps (longer for stability)
        self._band_histories: List[deque] = [
            deque(maxlen=self._band_history_size) for _ in range(5)
        ]
        self._band_max = np.full(5, 0.1, dtype=np.float32)  # Start with reasonable default

        # Onset detection state - using deque for O(1) popleft
        self._onset_threshold = 1.5  # Flux must be 1.5x average to trigger
        self._flux_history_size = 30  # ~0.5 second history
        self._flux_histories: List[deque] = [
            deque(maxlen=self._flux_history_size) for _ in range(5)
        ]
        # Per-band minimum intervals (prevents detecting faster than realistic for each instrument)
        # Bass/kick: 200ms (max 300 BPM), low-mid: 120ms, mid: 100ms, high-mid: 80ms, high: 60ms
        self._min_onset_intervals = [0.20, 0.12, 0.10, 0.08, 0.06]
        self._last_onset_time = np.zeros(5)

        # Audio capture state
        self._stream = None
        self._running = False
        self._capture_thread: Optional[threading.Thread] = None
        self._backend = None  # 'pyaudiowpatch' or 'sounddevice'

        # Use ring buffer if available, otherwise fall back to queue
        # Queue size balances latency vs dropped frames
        # Smaller queue = lower latency but more risk of dropped frames
        self._use_ringbuffer = HAS_RINGBUFFER
        if self._use_ringbuffer:
            self._ring_buffer: Optional[SPSCRingBuffer] = SPSCRingBuffer(
                capacity=self._queue_size, max_chunk_size=4096, channels=2
            )
            self._audio_queue = None
            logger.debug("FFTAnalyzer using lock-free ring buffer")
        else:
            self._ring_buffer = None
            self._audio_queue: queue.Queue = queue.Queue(maxsize=self._queue_size)

        # Buffer for accumulating samples (pre-allocated)
        self._sample_buffer = np.zeros(fft_size, dtype=np.float32)
        self._buffer_pos = 0

        # Check available backends
        self._pyaudio_available = self._check_pyaudiowpatch()
        self._sd_available = self._check_sounddevice()

        # BPM estimation state
        self._onset_times: deque = deque(maxlen=64)  # Recent kick onset timestamps
        self._ioi_history: deque = deque(maxlen=32)  # Inter-onset intervals in seconds
        self._tempo_histogram = np.zeros(200, dtype=np.float32)  # Bins for 40-240 BPM
        self._histogram_decay = 0.995  # Slow decay for stability (~3 sec half-life)
        self._estimated_bpm = 120.0  # Current BPM estimate
        self._bpm_confidence = 0.0  # Confidence in estimate (0-1)

        # High-performance FFT support (spectrograms > pyfftw > numpy)
        self._use_spectrograms = False
        self._use_pyfftw = False
        self._sg_planner = None
        self._pyfftw_input = None
        self._pyfftw_output = None
        self._pyfftw_plan = None

        # Try spectrograms first (Rust FFT backend)
        if HAS_SPECTROGRAMS:
            try:
                self._init_spectrograms_planner()
                self._use_spectrograms = True
                logger.info(f"Using spectrograms library (Rust FFT) - fft_size={fft_size}")
            except Exception as e:
                logger.warning(f"Failed to init spectrograms: {e}")

        # Fall back to pyfftw if spectrograms unavailable
        if not self._use_spectrograms and HAS_PYFFTW:
            try:
                self._init_pyfftw_planner()
                self._use_pyfftw = True
                logger.info(f"Using pyfftw library (FFTW) - fft_size={fft_size}")
            except Exception as e:
                logger.warning(f"Failed to init pyfftw: {e}")

        # Log FFT backend (this is just the math library, not audio capture)
        if not self._use_spectrograms and not self._use_pyfftw:
            logger.debug(f"FFT math: NumPy - fft_size={fft_size}")

        # Bass lane for ultra-fast kick detection (~1ms vs ~15ms for FFT)
        self._enable_bass_lane = enable_bass_lane
        self._bass_lane: Optional[BassLane] = None
        self._instant_bass = 0.0
        self._instant_kick_onset = False

        if enable_bass_lane:
            self._bass_lane = BassLane(
                sample_rate=sample_rate, cutoff_hz=bass_cutoff_hz, attack_ms=1.0, release_ms=50.0
            )
            logger.info(f"Bass lane enabled: cutoff={bass_cutoff_hz}Hz")

    def _check_pyaudiowpatch(self) -> bool:
        """Check if pyaudiowpatch is available."""
        import importlib.util

        return importlib.util.find_spec("pyaudiowpatch") is not None

    def _check_sounddevice(self) -> bool:
        """Check if sounddevice library is available."""
        import importlib.util

        return importlib.util.find_spec("sounddevice") is not None

    def _compute_band_bins(self) -> List[Tuple[int, int]]:
        """Compute FFT bin indices for each frequency band."""
        freq_per_bin = self.sample_rate / self.fft_size
        band_bins = []

        for low_hz, high_hz in self.BAND_RANGES:
            low_bin = max(1, int(low_hz / freq_per_bin))
            high_bin = min(self.fft_size // 2, int(high_hz / freq_per_bin))
            band_bins.append((low_bin, high_bin))

        return band_bins

    def _init_spectrograms_planner(self):
        """Initialize spectrograms library planner for high-performance FFT."""
        if not HAS_SPECTROGRAMS or sg is None:
            return

        stft = sg.StftParams(
            n_fft=self.fft_size,
            hop_size=self.fft_size,  # Single-frame processing
            window=sg.WindowType.hanning,
            centre=False,
        )
        params = sg.SpectrogramParams(stft, sample_rate=self.sample_rate)
        planner = sg.SpectrogramPlanner()
        self._sg_planner = planner.linear_power_plan(params)

    def _compute_spectrum_spectrograms(self, buffer: np.ndarray) -> np.ndarray:
        """Compute power spectrum using spectrograms library (Rust backend)."""
        samples = buffer.astype(np.float64)
        # Use compute_frame for single-frame FFT (frame_idx=0)
        result = self._sg_planner.compute_frame(samples, 0)
        return np.sqrt(result).astype(np.float32)  # Power to magnitude

    def _init_pyfftw_planner(self):
        """Initialize pyfftw for high-performance FFT with reusable plans."""
        if not HAS_PYFFTW or pyfftw is None:
            return

        # Create aligned arrays for SIMD optimization
        self._pyfftw_input = pyfftw.empty_aligned(self.fft_size, dtype="float32")
        self._pyfftw_output = pyfftw.empty_aligned(self.fft_size // 2 + 1, dtype="complex64")

        # Create reusable FFT plan (the key performance gain)
        self._pyfftw_plan = pyfftw.FFTW(
            self._pyfftw_input,
            self._pyfftw_output,
            direction="FFTW_FORWARD",
            flags=["FFTW_MEASURE"],  # Optimize plan for this size
            threads=1,  # Single thread for low latency
        )

    def _compute_spectrum_pyfftw(self, buffer: np.ndarray) -> np.ndarray:
        """Compute spectrum using pyfftw (FFTW backend)."""
        # Apply window and copy to aligned input buffer
        np.multiply(buffer, self.window, out=self._pyfftw_input)
        # Execute pre-planned FFT
        self._pyfftw_plan()
        # Return magnitude spectrum
        return np.abs(self._pyfftw_output).astype(np.float32)

    def _update_bpm_from_onset(self, current_time: float):
        """Update BPM estimate when a kick onset is detected."""
        if self._onset_times:
            ioi = current_time - self._onset_times[-1]
            # Only consider IOIs in valid tempo range (250ms to 1500ms = 40-240 BPM)
            if 0.25 <= ioi <= 1.5:
                # If we have an established tempo, filter out IOIs that don't fit
                if len(self._ioi_history) >= 4 and self._bpm_confidence > 0.3:
                    expected_ioi = 60.0 / self._estimated_bpm
                    # Allow IOIs that are close to expected, or multiples (half/double)
                    ratios = [
                        ioi / expected_ioi,
                        ioi / (expected_ioi * 2),
                        ioi / (expected_ioi * 0.5),
                    ]
                    best_ratio = min(ratios, key=lambda r: abs(r - 1.0))
                    # Reject if more than 20% off from nearest multiple
                    if abs(best_ratio - 1.0) > 0.20:
                        # Still record the onset time but don't use this IOI
                        self._onset_times.append(current_time)
                        return

                self._ioi_history.append(ioi)
                self._update_tempo_histogram(ioi)

        self._onset_times.append(current_time)

    def _update_tempo_histogram(self, ioi: float):
        """Update tempo histogram with new IOI measurement."""
        # Apply decay to existing histogram
        self._tempo_histogram *= self._histogram_decay

        # Convert IOI to BPM (60 / interval_seconds)
        bpm = 60.0 / ioi

        # Add votes for this tempo AND its multiples/subdivisions
        # This helps with octave disambiguation
        for multiplier in [0.5, 1.0, 2.0]:
            candidate_bpm = bpm * multiplier
            if 40 <= candidate_bpm <= 240:
                bin_idx = int(candidate_bpm - 40)
                # Gaussian weighting centered on candidate
                for offset in range(-3, 4):
                    if 0 <= bin_idx + offset < 200:
                        weight = np.exp(-0.5 * (offset / 1.5) ** 2)
                        # Prefer 80-160 BPM range (human preference)
                        if 80 <= candidate_bpm <= 160:
                            weight *= 1.5
                        self._tempo_histogram[bin_idx + offset] += weight

        # Update BPM estimate from histogram
        self._estimated_bpm, self._bpm_confidence = self._estimate_bpm_from_histogram()

    def _estimate_bpm_from_histogram(self) -> Tuple[float, float]:
        """Extract dominant tempo from histogram."""
        # Need minimum amount of data before estimating
        if len(self._ioi_history) < 4:
            return self._estimated_bpm, 0.0  # Not enough data

        if np.max(self._tempo_histogram) < 1.0:
            return self._estimated_bpm, 0.0  # Not enough data

        # Find peak
        peak_idx = int(np.argmax(self._tempo_histogram))
        peak_bpm = float(peak_idx + 40)

        # Refine peak using weighted average around maximum (sub-bin resolution)
        start_idx = max(0, peak_idx - 2)
        end_idx = min(200, peak_idx + 3)
        weights = self._tempo_histogram[start_idx:end_idx]
        if np.sum(weights) > 0:
            indices = np.arange(start_idx, end_idx) + 40
            peak_bpm = float(np.average(indices, weights=weights))

        # Confidence based on:
        # 1. Peak prominence (peak vs mean)
        # 2. Number of IOI samples collected
        # 3. Consistency of recent IOIs
        peak_height = self._tempo_histogram[peak_idx]
        mean_height = np.mean(self._tempo_histogram)
        prominence = peak_height / (mean_height + 0.001)

        # Sample-based confidence (more samples = more confident)
        sample_conf = min(1.0, len(self._ioi_history) / 16.0)  # Full confidence at 16 samples

        # IOI consistency (standard deviation of recent IOIs)
        if len(self._ioi_history) >= 4:
            recent_iois = self._ioi_history[-8:]  # Last 8 IOIs
            ioi_std = np.std(recent_iois)
            ioi_mean = np.mean(recent_iois)
            # Coefficient of variation (lower = more consistent)
            cv = ioi_std / (ioi_mean + 0.001)
            consistency_conf = max(0.0, 1.0 - cv * 2)  # CV > 0.5 = low confidence
        else:
            consistency_conf = 0.5

        # Combined confidence
        confidence = min(1.0, (prominence / 15.0) * sample_conf * consistency_conf)

        return peak_bpm, confidence

    def _autocorrelate_ioi(self) -> Optional[float]:
        """Use autocorrelation on IOI sequence for tempo verification."""
        if len(self._ioi_history) < 8:
            return None

        iois = np.array(self._ioi_history)

        # Compute autocorrelation of IOI sequence
        # Remove mean for better correlation
        iois_centered = iois - np.mean(iois)
        n = len(iois_centered)
        autocorr = np.correlate(iois_centered, iois_centered, mode="full")[n - 1 :]

        # Normalize by zero-lag value
        if autocorr[0] > 0:
            autocorr = autocorr / autocorr[0]

        # Find first significant peak after lag 0 (looking for periodicity)
        # This indicates consistent beat intervals
        for lag in range(1, min(8, len(autocorr) - 1)):
            if lag > 0 and autocorr[lag] > autocorr[lag - 1] and autocorr[lag] > autocorr[lag + 1]:
                if autocorr[lag] > 0.3:  # Significant correlation
                    # Average IOI suggests the base tempo
                    avg_ioi = np.median(iois)
                    return 60.0 / avg_ioi

        # No clear periodicity, use median IOI
        avg_ioi = np.median(iois)
        return 60.0 / avg_ioi

    def find_loopback_device_pyaudio(self):
        """Find WASAPI loopback device using pyaudiowpatch."""
        try:
            import pyaudiowpatch as pyaudio

            p = pyaudio.PyAudio()

            # Find WASAPI loopback devices
            wasapi_info = None
            for i in range(p.get_host_api_count()):
                info = p.get_host_api_info_by_index(i)
                if info["name"].lower() == "windows wasapi":
                    wasapi_info = info
                    break

            if wasapi_info is None:
                logger.warning("WASAPI not found")
                p.terminate()
                return None, None

            # Get default output device and find its loopback
            try:
                default_output = p.get_default_wasapi_loopback()
                logger.info(f"Found WASAPI loopback: {default_output['name']}")
                return p, default_output
            except Exception as e:
                logger.warning(f"Could not get default loopback: {e}")

            # Fallback: search for loopback devices
            for i in range(p.get_device_count()):
                dev = p.get_device_info_by_index(i)
                if dev.get("isLoopbackDevice", False):
                    logger.info(f"Found loopback device: {dev['name']}")
                    return p, dev

            p.terminate()
            return None, None

        except Exception as e:
            logger.error(f"Error finding loopback device: {e}")
            return None, None

    def find_loopback_device_sounddevice(self) -> Optional[int]:
        """Find loopback device using sounddevice (fallback)."""
        try:
            import sounddevice as sd

            devices = sd.query_devices()

            # Look for loopback devices
            for i, dev in enumerate(devices):
                name = dev["name"].lower()
                if (
                    "loopback" in name
                    or "stereo mix" in name
                    or "what u hear" in name
                    or "cable output" in name
                    or "vb-audio" in name
                ):
                    if dev["max_input_channels"] > 0:
                        logger.info(f"Found loopback device: {dev['name']}")
                        return i

            logger.warning("No loopback device found for sounddevice")
            return None

        except Exception as e:
            logger.error(f"Error finding sounddevice loopback: {e}")
            return None

    def start_capture(self) -> bool:
        """Start audio capture. Tries pyaudiowpatch first, then sounddevice."""

        # Try pyaudiowpatch first (true WASAPI loopback)
        if self._pyaudio_available:
            if self._start_pyaudio_capture():
                return True

        # Fallback to sounddevice
        if self._sd_available:
            if self._start_sounddevice_capture():
                return True

        logger.error("No audio capture backend available")
        return False

    def _start_pyaudio_capture(self) -> bool:
        """Start capture using pyaudiowpatch (true WASAPI loopback)."""
        try:
            import pyaudiowpatch as pyaudio

            p, device = self.find_loopback_device_pyaudio()
            if p is None or device is None:
                return False

            self._pyaudio = p

            # Callback for audio data
            def audio_callback(in_data, frame_count, time_info, status):
                capture_time = time.time()  # Timestamp when audio arrived
                audio = np.frombuffer(in_data, dtype=np.float32)
                # Reshape to stereo if needed
                if device["maxInputChannels"] >= 2:
                    audio = audio.reshape(-1, 2)

                # Use ring buffer if available
                if self._use_ringbuffer and self._ring_buffer is not None:
                    self._ring_buffer.try_write(capture_time, audio)
                elif self._audio_queue is not None:
                    try:
                        self._audio_queue.put_nowait((capture_time, audio))
                    except queue.Full:
                        # In low-latency mode, drop oldest and add new
                        if self.low_latency:
                            try:
                                self._audio_queue.get_nowait()
                                self._audio_queue.put_nowait((capture_time, audio))
                            except queue.Empty:
                                pass  # Queue was emptied by another thread
                return (None, pyaudio.paContinue)

            # Open stream with optimized buffer size
            # Smaller buffer = lower latency but more CPU load
            buffer_size = self._buffer_frames
            logger.info(
                f"Opening WASAPI stream: buffer={buffer_size} samples "
                f"({buffer_size / device['defaultSampleRate'] * 1000:.1f}ms)"
            )

            self._stream = p.open(
                format=pyaudio.paFloat32,
                channels=device["maxInputChannels"],
                rate=int(device["defaultSampleRate"]),
                frames_per_buffer=buffer_size,
                input=True,
                input_device_index=device["index"],
                stream_callback=audio_callback,
            )

            # Update sample rate to match device
            actual_rate = int(device["defaultSampleRate"])
            if actual_rate != self.sample_rate:
                logger.info(f"Adjusting sample rate: {self.sample_rate} -> {actual_rate}")
                self.sample_rate = actual_rate
                self.band_bins = self._compute_band_bins()
                # Reinitialize spectrograms planner with new sample rate
                if HAS_SPECTROGRAMS and self._use_spectrograms:
                    try:
                        self._init_spectrograms_planner()
                    except Exception as e:
                        logger.warning(f"Failed to reinit spectrograms: {e}")
                        self._use_spectrograms = False

            self._stream.start_stream()
            self._running = True
            self._backend = "pyaudiowpatch"

            logger.info(f"WASAPI loopback capture started: {device['name']} @ {actual_rate}Hz")
            return True

        except Exception as e:
            logger.error(f"Failed to start pyaudiowpatch capture: {e}")
            if hasattr(self, "_pyaudio") and self._pyaudio:
                self._pyaudio.terminate()
            return False

    def _start_sounddevice_capture(self) -> bool:
        """Start capture using sounddevice (fallback, requires virtual cable)."""
        try:
            import sounddevice as sd

            device_id = self.find_loopback_device_sounddevice()

            def audio_callback(indata, frames, time_info, status):
                capture_time = time.time()
                if status:
                    logger.debug(f"Audio status: {status}")

                # Use ring buffer if available
                if self._use_ringbuffer and self._ring_buffer is not None:
                    self._ring_buffer.try_write(capture_time, indata.copy())
                elif self._audio_queue is not None:
                    try:
                        self._audio_queue.put_nowait((capture_time, indata.copy()))
                    except queue.Full:
                        if self.low_latency:
                            try:
                                self._audio_queue.get_nowait()
                                self._audio_queue.put_nowait((capture_time, indata.copy()))
                            except queue.Empty:
                                pass  # Queue was emptied by another thread

            # Try to use WASAPI exclusive mode for lower latency
            extra_settings = None
            latency = "low" if self.low_latency else "high"

            if self.ultra_low_latency:
                try:
                    # WasapiSettings requires sounddevice with WASAPI support
                    extra_settings = sd.WasapiSettings(exclusive=True)
                    latency = None  # Let exclusive mode determine latency
                    logger.info("Using WASAPI exclusive mode for ultra-low latency")
                except (AttributeError, TypeError):
                    logger.warning("WASAPI exclusive mode not available, using shared mode")

            self._stream = sd.InputStream(
                device=device_id,
                channels=2,
                samplerate=self.sample_rate,
                blocksize=self._buffer_frames,
                callback=audio_callback,
                dtype=np.float32,
                latency=latency,
                extra_settings=extra_settings,
            )
            self._stream.start()
            self._running = True
            self._backend = "sounddevice"

            mode = "exclusive" if extra_settings else "shared"
            logger.info(f"Sounddevice capture started ({mode} mode, device: {device_id})")
            return True

        except Exception as e:
            logger.error(f"Failed to start sounddevice capture: {e}")
            return False

    def stop_capture(self):
        """Stop audio capture."""
        self._running = False

        if self._stream is not None:
            try:
                if self._backend == "pyaudiowpatch":
                    self._stream.stop_stream()
                    self._stream.close()
                    if hasattr(self, "_pyaudio") and self._pyaudio:
                        self._pyaudio.terminate()
                else:
                    self._stream.stop()
                    self._stream.close()
            except Exception as e:
                logger.debug(f"Error stopping stream: {e}")
            self._stream = None

    def _process_audio_chunk(self, audio: np.ndarray, timestamp: float) -> Optional[np.ndarray]:
        """
        Process incoming audio chunk, accumulate samples for FFT.
        Returns mono audio buffer when enough samples accumulated.

        Also runs bass lane on raw samples for instant kick detection.
        """
        # Convert stereo to mono (optimized)
        if audio.ndim > 1:
            mono = np.mean(audio, axis=1, dtype=np.float32)
        else:
            mono = audio.astype(np.float32)

        # Process through bass lane BEFORE buffering (ultra-low latency)
        if self._bass_lane is not None:
            bass_energy, kick_onset, onset_strength = self._bass_lane.process_samples(
                mono, timestamp
            )
            self._instant_bass = bass_energy
            self._instant_kick_onset = kick_onset

        # Add to buffer
        samples_to_add = min(len(mono), self.fft_size - self._buffer_pos)
        self._sample_buffer[self._buffer_pos : self._buffer_pos + samples_to_add] = mono[
            :samples_to_add
        ]
        self._buffer_pos += samples_to_add

        # Check if we have enough for FFT
        if self._buffer_pos >= self.fft_size:
            result = self._sample_buffer.copy()
            # Shift buffer by hop_size (use numpy for speed)
            np.copyto(
                self._sample_buffer[: self.fft_size - self.hop_size],
                self._sample_buffer[self.hop_size :],
            )
            self._buffer_pos = self.fft_size - self.hop_size
            return result

        return None

    def analyze(self) -> Optional[FFTResult]:
        """
        Perform FFT analysis on captured audio.
        Returns None if no audio available.
        """
        if not self._running:
            return None

        # Get audio from buffer/queue (now includes timestamp)
        if self._use_ringbuffer and self._ring_buffer is not None:
            result = self._ring_buffer.try_read()
            if result is None:
                return None
            capture_time, audio = result
        elif self._audio_queue is not None:
            try:
                capture_time, audio = self._audio_queue.get_nowait()
            except queue.Empty:
                return None
        else:
            return None

        # Track the audio timestamp for latency measurement
        self._audio_timestamp = capture_time

        # Accumulate samples (also processes bass lane)
        buffer = self._process_audio_chunk(audio, capture_time)
        if buffer is None:
            # Even without FFT result, we may have bass lane detection
            # Return a partial result if bass lane detected a kick
            if self._instant_kick_onset and self._bass_lane is not None:
                # Return early bass-only result for immediate response
                return FFTResult(
                    bands=self._smoothed_bands.tolist(),
                    raw_bands=self._raw_bands.tolist(),
                    peak=float(np.max(np.abs(audio))) if audio.size > 0 else 0.0,
                    spectral_flux=0.0,
                    band_flux=[0.0] * 5,
                    onsets=[False] * 5,
                    kick_onset=False,  # FFT-based kick not ready
                    snare_onset=False,
                    hihat_onset=False,
                    timestamp=capture_time,
                    estimated_bpm=self._estimated_bpm,
                    bpm_confidence=self._bpm_confidence,
                    instant_bass=self._instant_bass,
                    instant_kick_onset=True,  # Bass lane detected kick!
                )
            return None

        # Measure processing latency (time from audio capture to now)
        process_time = time.time()
        latency_ms = (process_time - self._audio_timestamp) * 1000
        self._latency_samples.append(latency_ms)

        # Compute FFT spectrum (spectrograms > pyfftw > numpy)
        if self._use_spectrograms:
            # High-performance Rust FFT (window applied internally)
            self._spectrum[:] = self._compute_spectrum_spectrograms(buffer)
        elif self._use_pyfftw:
            # FFTW backend with pre-planned FFT
            self._spectrum[:] = self._compute_spectrum_pyfftw(buffer)
        else:
            # Fallback to NumPy FFT
            windowed = buffer * self.window
            self._spectrum[:] = np.abs(np.fft.rfft(windowed))

        # Compute peak level
        peak = float(np.max(np.abs(buffer)))

        # Apply noise floor - if peak is too low, zero everything
        if peak < self._noise_floor:
            # Silence - decay bands smoothly to zero
            for i in range(5):
                self._smoothed_bands[i] *= 1.0 - self._band_release
            self._raw_bands[:] = 0
            self._normalized_bands[:] = 0
        else:
            # Extract band magnitudes (vectorized)
            for i, (low_bin, high_bin) in enumerate(self.band_bins):
                if high_bin > low_bin:
                    self._raw_bands[i] = np.mean(self._spectrum[low_bin:high_bin])
                else:
                    self._raw_bands[i] = 0.0

            # Update band histories and compute max (using deque)
            # Only update when we have actual signal
            for i in range(5):
                mag = self._raw_bands[i]
                if mag > self._noise_floor * 10:  # Only track meaningful values
                    self._band_histories[i].append(mag)

                # Compute band_max from history (95th percentile for stability)
                if len(self._band_histories[i]) > 20:
                    p95 = float(np.percentile(list(self._band_histories[i]), 95))
                    self._band_max[i] = p95 * 1.1 + 0.001
                elif len(self._band_histories[i]) > 5:
                    self._band_max[i] = max(self._band_histories[i]) * 1.2 + 0.001

            # Normalize bands (vectorized)
            np.minimum(1.0, self._raw_bands / self._band_max, out=self._normalized_bands)

            # Apply attack/release smoothing to each band
            for i in range(5):
                target = self._normalized_bands[i]
                current = self._smoothed_bands[i]

                if target > current:
                    # Rising - use attack
                    self._smoothed_bands[i] = current + (target - current) * self._band_attack
                else:
                    # Falling - use release
                    self._smoothed_bands[i] = current + (target - current) * self._band_release

        # Compute spectral flux
        flux_diff = np.maximum(0, self._spectrum - self._prev_spectrum)
        spectral_flux = float(np.mean(flux_diff)) if len(flux_diff) > 0 else 0.0

        # Compute per-band flux
        for i, (low_bin, high_bin) in enumerate(self.band_bins):
            if high_bin > low_bin:
                self._band_flux[i] = np.mean(flux_diff[low_bin:high_bin])
            else:
                self._band_flux[i] = 0.0

        # Save current spectrum for next frame
        np.copyto(self._prev_spectrum, self._spectrum)

        # Onset detection per band
        current_time = time.time()

        for i in range(5):
            flux_val = self._band_flux[i]
            self._flux_histories[i].append(flux_val)

            # Check for onset
            self._onsets[i] = False
            if len(self._flux_histories[i]) > 5:
                # Compute average excluding current value
                hist = self._flux_histories[i]
                if len(hist) > 1:
                    # Sum all elements minus last, divide by count-1
                    total = sum(hist) - hist[-1]
                    avg_flux = total / (len(hist) - 1)
                else:
                    avg_flux = 0.0
                threshold = avg_flux * self._onset_threshold + 0.001

                # Check if flux exceeds threshold and enough time since last onset
                # Use per-band minimum interval (slower for kick, faster for hi-hats)
                if (
                    flux_val > threshold
                    and current_time - self._last_onset_time[i] > self._min_onset_intervals[i]
                ):
                    self._onsets[i] = True
                    self._last_onset_time[i] = current_time

        # Detect specific instruments (5-band system: bass, low-mid, mid, high-mid, high)
        kick_onset = self._onsets[0]  # Bass onset = kick
        snare_onset = self._onsets[1] and self._onsets[3]  # Low-mid + high-mid = snare
        hihat_onset = self._onsets[4] and not self._onsets[0]  # High only (no bass) = hi-hat

        # Update BPM estimation on kick onset (most reliable for tempo)
        if kick_onset:
            self._update_bpm_from_onset(current_time)

        # Capture bass lane state and reset for next frame
        instant_bass = self._instant_bass
        instant_kick_onset = self._instant_kick_onset
        self._instant_kick_onset = False  # Reset after reading

        return FFTResult(
            bands=self._smoothed_bands.tolist(),  # Use smoothed bands for display
            raw_bands=self._raw_bands.tolist(),
            peak=peak,
            spectral_flux=spectral_flux,
            band_flux=self._band_flux.tolist(),
            onsets=list(self._onsets),
            kick_onset=kick_onset,
            snare_onset=snare_onset,
            hihat_onset=hihat_onset,
            timestamp=current_time,
            estimated_bpm=self._estimated_bpm,
            bpm_confidence=self._bpm_confidence,
            instant_bass=instant_bass,
            instant_kick_onset=instant_kick_onset,
        )

    @property
    def is_available(self) -> bool:
        """Check if any FFT backend is available."""
        return self._pyaudio_available or self._sd_available

    @property
    def is_running(self) -> bool:
        """Check if capture is currently running."""
        return self._running

    @property
    def backend(self) -> Optional[str]:
        """Return the active backend name."""
        return self._backend

    @property
    def fft_backend(self) -> str:
        """Return the FFT computation backend being used."""
        if self._use_spectrograms:
            return "spectrograms (Rust)"
        elif self._use_pyfftw:
            return "pyfftw (FFTW)"
        return "numpy"

    @property
    def latency_ms(self) -> float:
        """Get average processing latency in milliseconds."""
        if len(self._latency_samples) == 0:
            return 0.0
        return sum(self._latency_samples) / len(self._latency_samples)

    @property
    def latency_stats(self) -> dict:
        """Get detailed latency statistics."""
        fft_latency = (self.fft_size / self.sample_rate) * 1000
        hop_interval = (self.hop_size / self.sample_rate) * 1000
        if len(self._latency_samples) == 0:
            return {
                "avg": 0,
                "min": 0,
                "max": 0,
                "samples": 0,
                "fft_latency_ms": fft_latency,
                "hop_interval_ms": hop_interval,
            }
        samples = list(self._latency_samples)
        return {
            "avg": sum(samples) / len(samples),
            "min": min(samples),
            "max": max(samples),
            "samples": len(samples),
            "fft_latency_ms": fft_latency,
            "hop_interval_ms": hop_interval,
        }

    @property
    def buffer_stats(self) -> Optional[dict]:
        """Get ring buffer statistics (if using ring buffer)."""
        if self._use_ringbuffer and self._ring_buffer is not None:
            stats = self._ring_buffer.stats
            return {
                "writes": stats.writes,
                "reads": stats.reads,
                "overruns": stats.overruns,
                "underruns": stats.underruns,
                "capacity": stats.capacity,
                "fill": stats.current_fill,
            }
        return None

    @property
    def using_ringbuffer(self) -> bool:
        """Check if lock-free ring buffer is being used."""
        return self._use_ringbuffer


class HybridAnalyzer:
    """
    Hybrid analyzer that uses real FFT when available,
    falls back to synthetic analysis otherwise.

    Optionally includes beat prediction for zero-latency perceived beats.
    """

    def __init__(
        self,
        sample_rate: int = 44100,
        low_latency: bool = False,
        ultra_low_latency: bool = False,
        use_beat_prediction: bool = False,
        prediction_lookahead_ms: float = 80.0,
        enable_bass_lane: bool = True,
        bass_cutoff_hz: float = 120.0,
    ):
        self.fft_analyzer: Optional[FFTAnalyzer] = None
        self.sample_rate = sample_rate
        self.low_latency = low_latency
        self.ultra_low_latency = ultra_low_latency
        self._enable_bass_lane = enable_bass_lane
        self._bass_cutoff_hz = bass_cutoff_hz
        self._use_fft = False
        self._last_result: Optional[FFTResult] = None
        self._backend = None

        # Beat prediction
        self._use_beat_prediction = use_beat_prediction
        self._beat_predictor = None
        if use_beat_prediction:
            try:
                from audio_processor.beat_predictor import PredictiveBeatSync

                self._beat_predictor = PredictiveBeatSync(lookahead_ms=prediction_lookahead_ms)
                logger.info(f"Beat prediction enabled (lookahead: {prediction_lookahead_ms}ms)")
            except Exception as e:
                logger.warning(f"Failed to initialize beat predictor: {e}")
                self._use_beat_prediction = False

        # Try to initialize FFT analyzer
        self._try_init_fft()

    def _try_init_fft(self):
        """Try to initialize the FFT analyzer."""
        try:
            self.fft_analyzer = FFTAnalyzer(
                sample_rate=self.sample_rate,
                low_latency=self.low_latency,
                ultra_low_latency=self.ultra_low_latency,
                enable_bass_lane=self._enable_bass_lane,
                bass_cutoff_hz=self._bass_cutoff_hz,
            )
            if self.fft_analyzer.is_available:
                if self.fft_analyzer.start_capture():
                    self._use_fft = True
                    self._backend = self.fft_analyzer.backend
                    latency_info = (
                        f"~{self.fft_analyzer.latency_stats.get('fft_latency_ms', 0):.0f}ms"
                        if self.fft_analyzer.latency_stats
                        else ""
                    )
                    mode = "LOW-LATENCY" if self.low_latency else "NORMAL"
                    logger.info(f"Hybrid analyzer: Using {self._backend} [{mode}] {latency_info}")
                else:
                    logger.warning("Hybrid analyzer: Capture failed, using synthetic")
            else:
                logger.info("Hybrid analyzer: No backend available, using synthetic")
        except Exception as e:
            logger.warning(f"Hybrid analyzer: Failed to init FFT ({e}), using synthetic")
            self.fft_analyzer = None

    def analyze(
        self, synthetic_peak: float = 0.0, synthetic_bands: Optional[List[float]] = None
    ) -> FFTResult:
        """
        Analyze audio, preferring real FFT over synthetic.

        If beat prediction is enabled, the kick_onset field may be True
        BEFORE the actual beat occurs, allowing for zero-latency perceived beats.
        """
        # Try real FFT first
        if self._use_fft and self.fft_analyzer is not None:
            result = self.fft_analyzer.analyze()
            if result is not None:
                # Apply beat prediction if enabled
                if self._use_beat_prediction and self._beat_predictor:
                    result = self._apply_beat_prediction(result)
                self._last_result = result
                return result

        # Fall back to synthetic
        if synthetic_bands is None:
            synthetic_bands = [synthetic_peak * 0.8] * 5

        return FFTResult(
            bands=synthetic_bands,
            raw_bands=synthetic_bands,
            peak=synthetic_peak,
            spectral_flux=0.0,
            band_flux=[0.0] * 5,
            onsets=[False] * 5,
            kick_onset=False,
            snare_onset=False,
            hihat_onset=False,
            timestamp=time.time(),
            estimated_bpm=120.0,
            bpm_confidence=0.0,
        )

    def _apply_beat_prediction(self, result: FFTResult) -> FFTResult:
        """Apply beat prediction to FFT result."""
        if not self._beat_predictor:
            return result

        # Feed actual kick onset to predictor
        self._beat_predictor.process_fft_result(
            result.kick_onset, result.band_flux[0] if result.band_flux else 0.0
        )

        # Get predicted beat state
        beat_state = self._beat_predictor.get_beat_state()

        # Override kick_onset with predicted beat (fires BEFORE actual beat)
        if beat_state["predicted_beat"]:
            # Create new result with predicted beat
            return FFTResult(
                bands=result.bands,
                raw_bands=result.raw_bands,
                peak=result.peak,
                spectral_flux=result.spectral_flux,
                band_flux=result.band_flux,
                onsets=result.onsets,
                kick_onset=True,  # Predicted beat!
                snare_onset=result.snare_onset,
                hihat_onset=result.hihat_onset,
                timestamp=result.timestamp,
                estimated_bpm=beat_state["tempo_bpm"],
                bpm_confidence=beat_state["tempo_confidence"],
            )

        # Update BPM from predictor even if no predicted beat
        return FFTResult(
            bands=result.bands,
            raw_bands=result.raw_bands,
            peak=result.peak,
            spectral_flux=result.spectral_flux,
            band_flux=result.band_flux,
            onsets=result.onsets,
            kick_onset=result.kick_onset,
            snare_onset=result.snare_onset,
            hihat_onset=result.hihat_onset,
            timestamp=result.timestamp,
            estimated_bpm=beat_state["tempo_bpm"],
            bpm_confidence=beat_state["tempo_confidence"],
        )

    @property
    def beat_predictor(self):
        """Get the beat predictor (if enabled)."""
        return self._beat_predictor

    def stop(self):
        """Stop the analyzer."""
        if self.fft_analyzer is not None:
            self.fft_analyzer.stop_capture()

    @property
    def using_fft(self) -> bool:
        """Check if currently using real FFT."""
        return self._use_fft and self._last_result is not None

    @property
    def backend(self) -> Optional[str]:
        """Return the active backend name."""
        return self._backend

    @property
    def latency_ms(self) -> float:
        """Get average processing latency in milliseconds."""
        if self.fft_analyzer:
            return self.fft_analyzer.latency_ms
        return 0.0

    @property
    def latency_stats(self) -> dict:
        """Get detailed latency statistics."""
        if self.fft_analyzer:
            return self.fft_analyzer.latency_stats
        return {"avg": 0, "min": 0, "max": 0, "samples": 0}


def list_audio_devices():
    """List all available audio devices from both backends."""
    print("\n" + "=" * 60)
    print("AUDIO DEVICES")
    print("=" * 60)

    # Try pyaudiowpatch first
    try:
        import pyaudiowpatch as pyaudio

        print("\n[pyaudiowpatch - WASAPI Loopback Support]")
        print("-" * 60)

        p = pyaudio.PyAudio()

        # Find default loopback
        try:
            default_loopback = p.get_default_wasapi_loopback()
            print(f"  DEFAULT LOOPBACK: {default_loopback['name']}")
            print(
                f"    Rate: {int(default_loopback['defaultSampleRate'])}Hz, "
                f"Channels: {default_loopback['maxInputChannels']}"
            )
        except Exception:
            print("  (No default loopback found)")

        print("\n  All devices:")
        for i in range(p.get_device_count()):
            dev = p.get_device_info_by_index(i)
            loopback = " [LOOPBACK]" if dev.get("isLoopbackDevice", False) else ""
            if dev["maxInputChannels"] > 0:
                print(f"    {i}: {dev['name'][:45]}{loopback}")

        p.terminate()

    except ImportError:
        print("\n[pyaudiowpatch not installed]")
        print("  Install with: pip install pyaudiowpatch")

    # Try sounddevice
    try:
        import sounddevice as sd

        print("\n[sounddevice - Input Devices]")
        print("-" * 60)

        devices = sd.query_devices()
        for i, dev in enumerate(devices):
            if dev["max_input_channels"] > 0:
                name = dev["name"][:45]
                print(f"    {i}: {name}")

    except ImportError:
        print("\n[sounddevice not installed]")
        print("  Install with: pip install sounddevice")

    print("\n" + "=" * 60)


if __name__ == "__main__":
    import sys

    if "--list" in sys.argv:
        list_audio_devices()
        sys.exit(0)

    logging.basicConfig(level=logging.INFO)

    print("Testing FFT Analyzer...")
    analyzer = FFTAnalyzer()

    if not analyzer.is_available:
        print("No audio backend available.")
        print("Install pyaudiowpatch (recommended): pip install pyaudiowpatch")
        print("Or install sounddevice: pip install sounddevice")
        sys.exit(1)

    if not analyzer.start_capture():
        print("Failed to start audio capture")
        sys.exit(1)

    print(f"Backend: {analyzer.backend}")
    print("Capturing audio... Press Ctrl+C to stop")
    print()

    try:
        while True:
            result = analyzer.analyze()
            if result:
                # Display bands as bars
                bars = ""
                for i, (band, onset) in enumerate(zip(result.bands, result.onsets)):
                    char = "Ã¢â€“Ë†" if onset else "Ã¢â€“â€œ"
                    filled = int(band * 10)
                    bars += f"{char * filled:10} "

                kick = "K" if result.kick_onset else " "
                snare = "S" if result.snare_onset else " "
                hihat = "H" if result.hihat_onset else " "

                # Show BPM with confidence indicator
                conf_bar = "Ã¢â€“Ë†" * int(result.bpm_confidence * 5)
                bpm_str = f"{result.estimated_bpm:5.1f}BPM [{conf_bar:5}]"

                print(f"\r{bars} [{kick}{snare}{hihat}] {bpm_str} peak:{result.peak:.2f}", end="")

            time.sleep(1 / 60)

    except KeyboardInterrupt:
        print("\nStopping...")
    finally:
        analyzer.stop_capture()
