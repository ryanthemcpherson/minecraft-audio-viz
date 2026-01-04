"""
Real-time FFT analyzer with WASAPI loopback capture.
Provides true frequency band analysis and per-band onset detection.

Supports two capture backends:
1. pyaudiowpatch - True WASAPI loopback (recommended, no virtual cable needed)
2. sounddevice - Fallback using input devices (requires Stereo Mix or virtual cable)
"""

import numpy as np
import threading
import queue
import time
import logging
from collections import deque
from typing import Optional, List, Tuple
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class FFTResult:
    """Result from FFT analysis."""
    bands: List[float]          # 6 frequency bands (0-1 normalized)
    raw_bands: List[float]      # Raw band magnitudes (not normalized)
    peak: float                 # Overall peak level
    spectral_flux: float        # Full spectrum change rate
    band_flux: List[float]      # Per-band spectral flux
    onsets: List[bool]          # Per-band onset detection
    kick_onset: bool            # Detected kick drum
    snare_onset: bool           # Detected snare
    hihat_onset: bool           # Detected hi-hat
    timestamp: float            # When this was captured


class FFTAnalyzer:
    """
    Real-time FFT analyzer using WASAPI loopback capture.

    Captures system audio output and performs frequency analysis
    for accurate visualization and beat detection.
    """

    # Frequency band definitions (Hz) - matches our 6-band system
    BAND_RANGES = [
        (20, 60),      # Sub-bass (kick drums)
        (60, 250),     # Bass (bass guitar, toms)
        (250, 500),    # Low-mid (snare body, vocals)
        (500, 2000),   # Mid (vocals, instruments)
        (2000, 6000),  # High-mid (presence, snare crack)
        (6000, 20000), # High (hi-hats, cymbals, air)
    ]

    def __init__(self,
                 sample_rate: int = 44100,
                 fft_size: int = 2048,
                 hop_size: int = 512,
                 device: Optional[str] = None,
                 low_latency: bool = False):
        """
        Initialize FFT analyzer.

        Args:
            sample_rate: Audio sample rate (44100 or 48000 typical)
            fft_size: FFT window size (larger = better frequency resolution, more latency)
            hop_size: Samples between FFT frames (smaller = more responsive)
            device: Audio device name (None = auto-detect loopback)
            low_latency: If True, use smaller buffers for lower latency (~20ms vs ~45ms)
        """
        # Low latency mode uses smaller FFT (trades bass resolution for speed)
        if low_latency:
            fft_size = 1024   # ~21ms at 48kHz (vs ~43ms)
            hop_size = 256    # ~5ms updates (vs ~11ms)

        self.sample_rate = sample_rate
        self.fft_size = fft_size
        self.hop_size = hop_size
        self.device = device
        self.low_latency = low_latency

        # Latency tracking
        self._latency_samples: deque = deque(maxlen=60)  # Last 60 measurements
        self._audio_timestamp = 0.0  # When audio chunk arrived

        # Pre-compute FFT window (Hanning for smooth spectral analysis)
        self.window = np.hanning(fft_size).astype(np.float32)

        # Frequency bins for each band (pre-computed)
        self.band_bins = self._compute_band_bins()

        # Pre-allocate arrays for performance
        self._prev_spectrum = np.zeros(fft_size // 2 + 1, dtype=np.float32)
        self._spectrum = np.zeros(fft_size // 2 + 1, dtype=np.float32)
        self._raw_bands = np.zeros(6, dtype=np.float32)
        self._normalized_bands = np.zeros(6, dtype=np.float32)
        self._smoothed_bands = np.zeros(6, dtype=np.float32)  # Output with attack/release
        self._band_flux = np.zeros(6, dtype=np.float32)
        self._onsets = [False] * 6

        # Smoothing parameters (attack/release like synthetic system)
        self._band_attack = 0.4    # How fast bands rise (0-1, higher = faster)
        self._band_release = 0.08  # How fast bands fall (0-1, higher = faster)
        self._noise_floor = 0.02   # Ignore signals below this threshold

        # Per-band history for normalization (AGC per band) - using deque for O(1) ops
        self._band_history_size = 180  # ~3 seconds at 60fps (longer for stability)
        self._band_histories: List[deque] = [deque(maxlen=self._band_history_size) for _ in range(6)]
        self._band_max = np.full(6, 0.1, dtype=np.float32)  # Start with reasonable default

        # Onset detection state - using deque for O(1) popleft
        self._onset_threshold = 1.5  # Flux must be 1.5x average to trigger
        self._flux_history_size = 30  # ~0.5 second history
        self._flux_histories: List[deque] = [deque(maxlen=self._flux_history_size) for _ in range(6)]
        self._min_onset_interval = 0.08  # 80ms minimum between onsets (same band)
        self._last_onset_time = np.zeros(6)

        # Audio capture state
        self._stream = None
        # Smaller queue in low-latency mode to avoid buffering delay
        queue_size = 5 if low_latency else 15
        self._audio_queue: queue.Queue = queue.Queue(maxsize=queue_size)
        self._running = False
        self._capture_thread: Optional[threading.Thread] = None
        self._backend = None  # 'pyaudiowpatch' or 'sounddevice'

        # Buffer for accumulating samples (pre-allocated)
        self._sample_buffer = np.zeros(fft_size, dtype=np.float32)
        self._buffer_pos = 0

        # Check available backends
        self._pyaudio_available = self._check_pyaudiowpatch()
        self._sd_available = self._check_sounddevice()

    def _check_pyaudiowpatch(self) -> bool:
        """Check if pyaudiowpatch is available."""
        try:
            import pyaudiowpatch as pyaudio
            return True
        except ImportError:
            return False

    def _check_sounddevice(self) -> bool:
        """Check if sounddevice library is available."""
        try:
            import sounddevice as sd
            return True
        except ImportError:
            return False

    def _compute_band_bins(self) -> List[Tuple[int, int]]:
        """Compute FFT bin indices for each frequency band."""
        freq_per_bin = self.sample_rate / self.fft_size
        band_bins = []

        for low_hz, high_hz in self.BAND_RANGES:
            low_bin = max(1, int(low_hz / freq_per_bin))
            high_bin = min(self.fft_size // 2, int(high_hz / freq_per_bin))
            band_bins.append((low_bin, high_bin))

        return band_bins

    def find_loopback_device_pyaudio(self):
        """Find WASAPI loopback device using pyaudiowpatch."""
        try:
            import pyaudiowpatch as pyaudio

            p = pyaudio.PyAudio()

            # Find WASAPI loopback devices
            wasapi_info = None
            for i in range(p.get_host_api_count()):
                info = p.get_host_api_info_by_index(i)
                if info['name'].lower() == 'windows wasapi':
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
                if dev.get('isLoopbackDevice', False):
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
                name = dev['name'].lower()
                if ('loopback' in name or
                    'stereo mix' in name or
                    'what u hear' in name or
                    'cable output' in name or
                    'vb-audio' in name):
                    if dev['max_input_channels'] > 0:
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
                if device['maxInputChannels'] >= 2:
                    audio = audio.reshape(-1, 2)
                try:
                    self._audio_queue.put_nowait((capture_time, audio))
                except queue.Full:
                    # In low-latency mode, drop oldest and add new
                    if self.low_latency:
                        try:
                            self._audio_queue.get_nowait()
                            self._audio_queue.put_nowait((capture_time, audio))
                        except:
                            pass
                return (None, pyaudio.paContinue)

            # Open stream
            self._stream = p.open(
                format=pyaudio.paFloat32,
                channels=device['maxInputChannels'],
                rate=int(device['defaultSampleRate']),
                frames_per_buffer=self.hop_size,
                input=True,
                input_device_index=device['index'],
                stream_callback=audio_callback
            )

            # Update sample rate to match device
            actual_rate = int(device['defaultSampleRate'])
            if actual_rate != self.sample_rate:
                logger.info(f"Adjusting sample rate: {self.sample_rate} -> {actual_rate}")
                self.sample_rate = actual_rate
                self.band_bins = self._compute_band_bins()

            self._stream.start_stream()
            self._running = True
            self._backend = 'pyaudiowpatch'

            logger.info(f"WASAPI loopback capture started: {device['name']} @ {actual_rate}Hz")
            return True

        except Exception as e:
            logger.error(f"Failed to start pyaudiowpatch capture: {e}")
            if hasattr(self, '_pyaudio') and self._pyaudio:
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
                try:
                    self._audio_queue.put_nowait((capture_time, indata.copy()))
                except queue.Full:
                    if self.low_latency:
                        try:
                            self._audio_queue.get_nowait()
                            self._audio_queue.put_nowait((capture_time, indata.copy()))
                        except:
                            pass

            self._stream = sd.InputStream(
                device=device_id,
                channels=2,
                samplerate=self.sample_rate,
                blocksize=self.hop_size,
                callback=audio_callback,
                dtype=np.float32
            )
            self._stream.start()
            self._running = True
            self._backend = 'sounddevice'

            logger.info(f"Sounddevice capture started (device: {device_id})")
            return True

        except Exception as e:
            logger.error(f"Failed to start sounddevice capture: {e}")
            return False

    def stop_capture(self):
        """Stop audio capture."""
        self._running = False

        if self._stream is not None:
            try:
                if self._backend == 'pyaudiowpatch':
                    self._stream.stop_stream()
                    self._stream.close()
                    if hasattr(self, '_pyaudio') and self._pyaudio:
                        self._pyaudio.terminate()
                else:
                    self._stream.stop()
                    self._stream.close()
            except Exception as e:
                logger.debug(f"Error stopping stream: {e}")
            self._stream = None

    def _process_audio_chunk(self, audio: np.ndarray) -> Optional[np.ndarray]:
        """
        Process incoming audio chunk, accumulate samples for FFT.
        Returns mono audio buffer when enough samples accumulated.
        """
        # Convert stereo to mono (optimized)
        if audio.ndim > 1:
            mono = np.mean(audio, axis=1, dtype=np.float32)
        else:
            mono = audio.astype(np.float32)

        # Add to buffer
        samples_to_add = min(len(mono), self.fft_size - self._buffer_pos)
        self._sample_buffer[self._buffer_pos:self._buffer_pos + samples_to_add] = mono[:samples_to_add]
        self._buffer_pos += samples_to_add

        # Check if we have enough for FFT
        if self._buffer_pos >= self.fft_size:
            result = self._sample_buffer.copy()
            # Shift buffer by hop_size (use numpy for speed)
            np.copyto(
                self._sample_buffer[:self.fft_size - self.hop_size],
                self._sample_buffer[self.hop_size:]
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

        # Get audio from queue (now includes timestamp)
        try:
            capture_time, audio = self._audio_queue.get_nowait()
        except queue.Empty:
            return None

        # Track the audio timestamp for latency measurement
        self._audio_timestamp = capture_time

        # Accumulate samples
        buffer = self._process_audio_chunk(audio)
        if buffer is None:
            return None

        # Measure processing latency (time from audio capture to now)
        process_time = time.time()
        latency_ms = (process_time - self._audio_timestamp) * 1000
        self._latency_samples.append(latency_ms)

        # Apply window and compute FFT (in-place where possible)
        windowed = buffer * self.window
        self._spectrum[:] = np.abs(np.fft.rfft(windowed))

        # Compute peak level
        peak = float(np.max(np.abs(buffer)))

        # Apply noise floor - if peak is too low, zero everything
        if peak < self._noise_floor:
            # Silence - decay bands smoothly to zero
            for i in range(6):
                self._smoothed_bands[i] *= (1.0 - self._band_release)
            self._raw_bands[:] = 0
            self._normalized_bands[:] = 0
        else:
            # Extract band magnitudes (vectorized)
            for i, (low_bin, high_bin) in enumerate(self.band_bins):
                self._raw_bands[i] = np.mean(self._spectrum[low_bin:high_bin])

            # Update band histories and compute max (using deque)
            # Only update when we have actual signal
            for i in range(6):
                mag = self._raw_bands[i]
                if mag > self._noise_floor * 10:  # Only track meaningful values
                    self._band_histories[i].append(mag)

                # Compute band_max from history (95th percentile for stability)
                if len(self._band_histories[i]) > 20:
                    sorted_hist = sorted(self._band_histories[i])
                    idx = int(len(sorted_hist) * 0.95)
                    self._band_max[i] = sorted_hist[idx] * 1.1 + 0.001
                elif len(self._band_histories[i]) > 5:
                    self._band_max[i] = max(self._band_histories[i]) * 1.2 + 0.001

            # Normalize bands (vectorized)
            np.minimum(1.0, self._raw_bands / self._band_max, out=self._normalized_bands)

            # Apply attack/release smoothing to each band
            for i in range(6):
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
        spectral_flux = float(np.mean(flux_diff))

        # Compute per-band flux
        for i, (low_bin, high_bin) in enumerate(self.band_bins):
            self._band_flux[i] = np.mean(flux_diff[low_bin:high_bin])

        # Save current spectrum for next frame
        np.copyto(self._prev_spectrum, self._spectrum)

        # Onset detection per band
        current_time = time.time()

        for i in range(6):
            flux_val = self._band_flux[i]
            self._flux_histories[i].append(flux_val)

            # Check for onset
            self._onsets[i] = False
            if len(self._flux_histories[i]) > 5:
                # Compute average excluding current value
                hist_list = list(self._flux_histories[i])
                avg_flux = np.mean(hist_list[:-1]) if len(hist_list) > 1 else 0
                threshold = avg_flux * self._onset_threshold + 0.001

                # Check if flux exceeds threshold and enough time since last onset
                if (flux_val > threshold and
                    current_time - self._last_onset_time[i] > self._min_onset_interval):
                    self._onsets[i] = True
                    self._last_onset_time[i] = current_time

        # Detect specific instruments
        kick_onset = self._onsets[0]  # Sub-bass onset = kick
        snare_onset = self._onsets[2] and self._onsets[4]  # Low-mid + high-mid = snare
        hihat_onset = self._onsets[5] and not self._onsets[0]  # High only (no sub-bass) = hi-hat

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
            timestamp=current_time
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
    def latency_ms(self) -> float:
        """Get average processing latency in milliseconds."""
        if len(self._latency_samples) == 0:
            return 0.0
        return sum(self._latency_samples) / len(self._latency_samples)

    @property
    def latency_stats(self) -> dict:
        """Get detailed latency statistics."""
        if len(self._latency_samples) == 0:
            return {"avg": 0, "min": 0, "max": 0, "samples": 0}
        samples = list(self._latency_samples)
        return {
            "avg": sum(samples) / len(samples),
            "min": min(samples),
            "max": max(samples),
            "samples": len(samples),
            "fft_latency_ms": (self.fft_size / self.sample_rate) * 1000,
            "hop_interval_ms": (self.hop_size / self.sample_rate) * 1000,
        }


class HybridAnalyzer:
    """
    Hybrid analyzer that uses real FFT when available,
    falls back to synthetic analysis otherwise.
    """

    def __init__(self, sample_rate: int = 44100, low_latency: bool = False):
        self.fft_analyzer: Optional[FFTAnalyzer] = None
        self.sample_rate = sample_rate
        self.low_latency = low_latency
        self._use_fft = False
        self._last_result: Optional[FFTResult] = None
        self._backend = None

        # Try to initialize FFT analyzer
        self._try_init_fft()

    def _try_init_fft(self):
        """Try to initialize the FFT analyzer."""
        try:
            self.fft_analyzer = FFTAnalyzer(
                sample_rate=self.sample_rate,
                low_latency=self.low_latency
            )
            if self.fft_analyzer.is_available:
                if self.fft_analyzer.start_capture():
                    self._use_fft = True
                    self._backend = self.fft_analyzer.backend
                    latency_info = f"~{self.fft_analyzer.latency_stats.get('fft_latency_ms', 0):.0f}ms" if self.fft_analyzer.latency_stats else ""
                    mode = "LOW-LATENCY" if self.low_latency else "NORMAL"
                    logger.info(f"Hybrid analyzer: Using {self._backend} [{mode}] {latency_info}")
                else:
                    logger.warning("Hybrid analyzer: Capture failed, using synthetic")
            else:
                logger.info("Hybrid analyzer: No backend available, using synthetic")
        except Exception as e:
            logger.warning(f"Hybrid analyzer: Failed to init FFT ({e}), using synthetic")
            self.fft_analyzer = None

    def analyze(self, synthetic_peak: float = 0.0,
                synthetic_bands: Optional[List[float]] = None) -> FFTResult:
        """
        Analyze audio, preferring real FFT over synthetic.
        """
        # Try real FFT first
        if self._use_fft and self.fft_analyzer is not None:
            result = self.fft_analyzer.analyze()
            if result is not None:
                self._last_result = result
                return result

        # Fall back to synthetic
        if synthetic_bands is None:
            synthetic_bands = [synthetic_peak * 0.8] * 6

        return FFTResult(
            bands=synthetic_bands,
            raw_bands=synthetic_bands,
            peak=synthetic_peak,
            spectral_flux=0.0,
            band_flux=[0.0] * 6,
            onsets=[False] * 6,
            kick_onset=False,
            snare_onset=False,
            hihat_onset=False,
            timestamp=time.time()
        )

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
            print(f"    Rate: {int(default_loopback['defaultSampleRate'])}Hz, "
                  f"Channels: {default_loopback['maxInputChannels']}")
        except:
            print("  (No default loopback found)")

        print("\n  All devices:")
        for i in range(p.get_device_count()):
            dev = p.get_device_info_by_index(i)
            loopback = " [LOOPBACK]" if dev.get('isLoopbackDevice', False) else ""
            if dev['maxInputChannels'] > 0:
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
            if dev['max_input_channels'] > 0:
                name = dev['name'][:45]
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
                    char = "█" if onset else "▓"
                    filled = int(band * 10)
                    bars += f"{char * filled:10} "

                kick = "K" if result.kick_onset else " "
                snare = "S" if result.snare_onset else " "
                hihat = "H" if result.hihat_onset else " "

                print(f"\r{bars} [{kick}{snare}{hihat}] peak:{result.peak:.2f}", end="")

            time.sleep(1/60)

    except KeyboardInterrupt:
        print("\nStopping...")
    finally:
        analyzer.stop_capture()
