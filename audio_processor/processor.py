"""
Real-time audio processor for visualization.
Performs FFT analysis and extracts frequency bands for visualization.
"""

import time
from dataclasses import dataclass
from typing import Callable, List, Optional

import numpy as np
from scipy.fft import rfft, rfftfreq


@dataclass
class AudioFrame:
    """Processed audio frame with visualization data."""

    timestamp: float

    # Frequency bands (normalized 0-1) - 5-band system
    bands: List[float]  # [bass, low_mid, mid, high_mid, high]

    # Overall metrics
    amplitude: float  # RMS amplitude (0-1)
    peak: float  # Peak amplitude (0-1)

    # Beat detection
    is_beat: bool  # True if beat detected this frame
    beat_intensity: float  # Strength of beat (0-1)

    # Raw spectrum for advanced visualizations
    spectrum: Optional[np.ndarray] = None


class AudioProcessor:
    """
    Real-time audio processor that converts PCM audio to visualization data.

    Designed for Discord voice audio:
    - Sample rate: 48000 Hz
    - Channels: 2 (stereo)
    - Sample format: 16-bit signed PCM
    - Frame size: 20ms (960 samples per channel)
    """

    # Frequency band ranges (Hz) - 5-band system (matches fft_analyzer.py)
    # Sub-bass removed: 1024-sample FFT at 48kHz can't accurately detect <43Hz
    BAND_RANGES = [
        (40, 250),  # Bass (kick drums, bass guitar, toms)
        (250, 500),  # Low-mid (snare body, vocals)
        (500, 2000),  # Mid (vocals, instruments)
        (2000, 6000),  # High-mid (presence, snare crack)
        (6000, 20000),  # High (hi-hats, cymbals, air)
    ]

    def __init__(
        self,
        sample_rate: int = 48000,
        channels: int = 2,
        smoothing: float = 0.3,
        beat_sensitivity: float = 1.5,
    ):
        """
        Initialize the audio processor.

        Args:
            sample_rate: Audio sample rate in Hz
            channels: Number of audio channels
            smoothing: Smoothing factor for band values (0-1, higher = smoother)
            beat_sensitivity: Beat detection threshold multiplier
        """
        self.sample_rate = sample_rate
        self.channels = channels
        self.smoothing = smoothing
        self.beat_sensitivity = beat_sensitivity

        # State for smoothing
        self._prev_bands = np.zeros(len(self.BAND_RANGES))
        self._prev_amplitude = 0.0

        # Beat detection state
        self._energy_history = []
        self._history_size = 43  # ~860ms at 20ms frames
        self._last_beat_time = 0
        self._min_beat_interval = 0.1  # 100ms minimum between beats

        # Audio buffer for larger FFT windows
        self._audio_buffer = np.array([], dtype=np.float32)
        self._fft_size = 2048  # Larger FFT for better frequency resolution

        # Pre-compute frequency bins for each band
        self._setup_frequency_bins()

        # Callbacks
        self._callbacks: List[Callable[[AudioFrame], None]] = []

    def _setup_frequency_bins(self):
        """Pre-compute which FFT bins correspond to each frequency band."""
        freqs = rfftfreq(self._fft_size, 1.0 / self.sample_rate)
        self._band_bins = []

        for low, high in self.BAND_RANGES:
            bins = np.where((freqs >= low) & (freqs < high))[0]
            self._band_bins.append(bins if len(bins) > 0 else np.array([0]))

    def add_callback(self, callback: Callable[[AudioFrame], None]):
        """Add a callback to be called for each processed frame."""
        self._callbacks.append(callback)

    def remove_callback(self, callback: Callable[[AudioFrame], None]):
        """Remove a callback."""
        if callback in self._callbacks:
            self._callbacks.remove(callback)

    def process_pcm(self, pcm_data: bytes) -> Optional[AudioFrame]:
        """
        Process raw PCM audio data.

        Args:
            pcm_data: Raw 16-bit signed PCM audio bytes

        Returns:
            AudioFrame with visualization data, or None if buffer not full
        """
        # Convert bytes to numpy array
        samples = np.frombuffer(pcm_data, dtype=np.int16).astype(np.float32)

        # Normalize to -1 to 1 range
        samples = samples / 32768.0

        # Convert stereo to mono by averaging channels
        if self.channels == 2:
            samples = (samples[0::2] + samples[1::2]) / 2

        # Add to buffer
        self._audio_buffer = np.concatenate([self._audio_buffer, samples])

        # Process when we have enough samples
        if len(self._audio_buffer) >= self._fft_size:
            frame = self._process_buffer()

            # Keep overlap for smoother analysis
            self._audio_buffer = self._audio_buffer[len(samples) :]

            # Call callbacks
            for callback in self._callbacks:
                try:
                    callback(frame)
                except Exception as e:
                    print(f"Callback error: {e}")

            return frame

        return None

    def _process_buffer(self) -> AudioFrame:
        """Process the audio buffer and return visualization data."""
        timestamp = time.time()

        # Get the most recent samples for FFT
        samples = self._audio_buffer[-self._fft_size :]

        # Apply Hanning window to reduce spectral leakage
        windowed = samples * np.hanning(len(samples))

        # Compute FFT
        fft_result = rfft(windowed)
        magnitudes = np.abs(fft_result)

        # Normalize magnitudes
        magnitudes = magnitudes / (self._fft_size / 2)

        # Extract frequency bands
        raw_bands = []
        for bins in self._band_bins:
            if len(bins) > 0:
                band_energy = np.mean(magnitudes[bins])
            else:
                band_energy = 0.0
            raw_bands.append(band_energy)

        raw_bands = np.array(raw_bands)

        # Apply logarithmic scaling for better visual response
        raw_bands = np.log10(raw_bands * 10 + 1) / 2

        # Clamp to 0-1
        raw_bands = np.clip(raw_bands, 0, 1)

        # Apply smoothing
        bands = self._prev_bands * self.smoothing + raw_bands * (1 - self.smoothing)
        self._prev_bands = bands

        # Calculate amplitude (RMS)
        rms = np.sqrt(np.mean(samples**2))
        amplitude = min(1.0, rms * 3)  # Scale up for visibility
        amplitude = self._prev_amplitude * self.smoothing + amplitude * (1 - self.smoothing)
        self._prev_amplitude = amplitude

        # Peak amplitude
        peak = min(1.0, np.max(np.abs(samples)) * 1.5)

        # Beat detection
        is_beat, beat_intensity = self._detect_beat(samples, bands)

        return AudioFrame(
            timestamp=timestamp,
            bands=bands.tolist(),
            amplitude=amplitude,
            peak=peak,
            is_beat=is_beat,
            beat_intensity=beat_intensity,
            spectrum=magnitudes[:256] if len(magnitudes) >= 256 else magnitudes,
        )

    def _detect_beat(self, samples: np.ndarray, bands: np.ndarray) -> tuple[bool, float]:
        """
        Detect beats using energy-based algorithm.

        Compares current energy to recent average energy.
        """
        # Calculate current energy (focus on bass frequencies)
        bass_energy = bands[0]  # Bass band
        current_energy = bass_energy

        # Add to history
        self._energy_history.append(current_energy)
        if len(self._energy_history) > self._history_size:
            self._energy_history.pop(0)

        # Need enough history for comparison
        if len(self._energy_history) < self._history_size // 2:
            return False, 0.0

        # Calculate average and variance
        avg_energy = np.mean(self._energy_history)
        variance = np.var(self._energy_history)

        # Dynamic threshold based on variance
        threshold = avg_energy + self.beat_sensitivity * max(0.1, np.sqrt(variance))

        # Check if current energy exceeds threshold
        current_time = time.time()
        time_since_last = current_time - self._last_beat_time

        if current_energy > threshold and time_since_last > self._min_beat_interval:
            self._last_beat_time = current_time
            intensity = min(1.0, (current_energy - avg_energy) / max(0.1, avg_energy))
            return True, intensity

        return False, 0.0

    def reset(self):
        """Reset processor state."""
        self._prev_bands = np.zeros(len(self.BAND_RANGES))
        self._prev_amplitude = 0.0
        self._energy_history = []
        self._audio_buffer = np.array([], dtype=np.float32)
        self._last_beat_time = 0


# Convenience function for testing
def process_audio_file(filepath: str, callback: Callable[[AudioFrame], None]):
    """Process an audio file for testing (requires scipy)."""
    from scipy.io import wavfile

    sample_rate, data = wavfile.read(filepath)
    processor = AudioProcessor(sample_rate=sample_rate)
    processor.add_callback(callback)

    # Process in 20ms chunks
    chunk_size = int(sample_rate * 0.02) * 2  # stereo

    for i in range(0, len(data), chunk_size):
        chunk = data[i : i + chunk_size]
        if len(chunk) == chunk_size:
            processor.process_pcm(chunk.tobytes())
