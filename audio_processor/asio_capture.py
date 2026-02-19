"""
ASIO Audio Capture - Ultra-low latency audio capture using ASIO.

ASIO (Audio Stream Input/Output) bypasses the Windows audio stack entirely,
achieving latencies as low as 1-3ms. This module provides ASIO capture
for systems that have ASIO drivers installed.

Requirements:
- sounddevice with ASIO support (pip install sounddevice, NOT conda)
- ASIO driver installed (ASIO4ALL, or native driver from audio interface)
- Optional: VB-Audio ASIO Bridge for capturing system audio via ASIO

References:
- python-sounddevice: https://python-sounddevice.readthedocs.io/
- python-rtmixer: https://github.com/spatialaudio/python-rtmixer
- ASIO4ALL: https://www.asio4all.org/
"""

import logging
import queue
import time
from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np

logger = logging.getLogger(__name__)


@dataclass
class AsioDeviceInfo:
    """Information about an ASIO device."""

    index: int
    name: str
    input_channels: int
    output_channels: int
    default_samplerate: float
    default_low_latency: float
    default_high_latency: float
    is_asio: bool


def list_asio_devices() -> List[AsioDeviceInfo]:
    """
    List available ASIO devices.

    Returns:
        List of AsioDeviceInfo for each ASIO device found
    """
    devices = []

    try:
        import sounddevice as sd

        all_devices = sd.query_devices()
        hostapis = sd.query_hostapis()

        # Find ASIO host API
        asio_api_idx = None
        for i, api in enumerate(hostapis):
            if "asio" in api["name"].lower():
                asio_api_idx = i
                break

        if asio_api_idx is None:
            logger.warning("ASIO host API not found. Is sounddevice built with ASIO support?")
            return devices

        # Get devices for ASIO API
        for i, dev in enumerate(all_devices):
            if dev["hostapi"] == asio_api_idx:
                devices.append(
                    AsioDeviceInfo(
                        index=i,
                        name=dev["name"],
                        input_channels=dev["max_input_channels"],
                        output_channels=dev["max_output_channels"],
                        default_samplerate=dev["default_samplerate"],
                        default_low_latency=dev["default_low_input_latency"],
                        default_high_latency=dev["default_high_input_latency"],
                        is_asio=True,
                    )
                )

    except ImportError:
        logger.error("sounddevice not installed")
    except Exception as e:
        logger.error(f"Error listing ASIO devices: {e}")

    return devices


def check_asio_available() -> Tuple[bool, str]:
    """
    Check if ASIO is available.

    Returns:
        Tuple of (is_available, message)
    """
    try:
        import sounddevice as sd

        hostapis = sd.query_hostapis()
        for api in hostapis:
            if "asio" in api["name"].lower():
                devices = list_asio_devices()
                if devices:
                    return True, f"ASIO available with {len(devices)} device(s)"
                else:
                    return False, "ASIO API found but no devices available"

        return False, "ASIO host API not found (sounddevice may not be built with ASIO)"

    except ImportError:
        return False, "sounddevice not installed"
    except Exception as e:
        return False, f"Error checking ASIO: {e}"


class AsioCapture:
    """
    ASIO audio capture for ultra-low latency.

    This class provides audio capture using ASIO, which bypasses the
    Windows audio stack for minimal latency (typically 1-5ms).

    Note: ASIO captures from audio interfaces, not system audio loopback.
    For system audio, you need VB-Audio ASIO Bridge or similar.
    """

    def __init__(
        self,
        device: Optional[int] = None,
        sample_rate: int = 48000,
        buffer_size: int = 64,  # Very small for low latency
        channels: int = 2,
    ):
        """
        Initialize ASIO capture.

        Args:
            device: ASIO device index (None for default)
            sample_rate: Sample rate (48000 recommended for most interfaces)
            buffer_size: Buffer size in samples (64-256 for low latency)
            channels: Number of channels to capture
        """
        self.device = device
        self.sample_rate = sample_rate
        self.buffer_size = buffer_size
        self.channels = channels

        self._stream = None
        self._running = False
        self._audio_queue: queue.Queue = queue.Queue(maxsize=8)

        # Latency tracking
        self._latency_samples: list = []
        self._last_callback_time = 0.0

        # Check ASIO availability
        available, msg = check_asio_available()
        if not available:
            logger.warning(f"ASIO not available: {msg}")
        self._asio_available = available

    @property
    def is_available(self) -> bool:
        return self._asio_available

    def start(self) -> bool:
        """Start ASIO capture."""
        if not self._asio_available:
            logger.error("Cannot start: ASIO not available")
            return False

        try:
            import sounddevice as sd

            # Get ASIO settings
            try:
                asio_settings = sd.AsioSettings(channel_selectors=[0, 1])
            except AttributeError:
                logger.warning("AsioSettings not available, using default settings")
                asio_settings = None

            def audio_callback(indata, frames, time_info, status):
                capture_time = time.time()

                if status:
                    if status.input_overflow:
                        logger.debug("ASIO input overflow")

                # Track callback timing for latency measurement
                if self._last_callback_time > 0:
                    interval = (capture_time - self._last_callback_time) * 1000
                    self._latency_samples.append(interval)
                    if len(self._latency_samples) > 100:
                        self._latency_samples.pop(0)

                self._last_callback_time = capture_time

                try:
                    self._audio_queue.put_nowait((capture_time, indata.copy()))
                except queue.Full:
                    # Drop oldest, keep newest for low latency
                    try:
                        self._audio_queue.get_nowait()
                        self._audio_queue.put_nowait((capture_time, indata.copy()))
                    except queue.Empty:
                        pass

            # Calculate latency
            latency_ms = (self.buffer_size / self.sample_rate) * 1000
            logger.info(
                f"Opening ASIO stream: device={self.device}, "
                f"rate={self.sample_rate}, buffer={self.buffer_size} "
                f"({latency_ms:.2f}ms)"
            )

            self._stream = sd.InputStream(
                device=self.device,
                channels=self.channels,
                samplerate=self.sample_rate,
                blocksize=self.buffer_size,
                callback=audio_callback,
                dtype=np.float32,
                latency="low",
                extra_settings=asio_settings,
            )

            self._stream.start()
            self._running = True

            actual_latency = self._stream.latency * 1000
            logger.info(f"ASIO capture started (latency: {actual_latency:.2f}ms)")
            return True

        except Exception as e:
            logger.error(f"Failed to start ASIO capture: {e}")
            return False

    def stop(self):
        """Stop ASIO capture."""
        self._running = False
        if self._stream:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception as e:
                logger.debug(f"Error stopping ASIO stream: {e}")
            self._stream = None

    def get_audio(self) -> Optional[Tuple[float, np.ndarray]]:
        """
        Get captured audio.

        Returns:
            Tuple of (timestamp, audio_data) or None if no data available
        """
        try:
            return self._audio_queue.get_nowait()
        except queue.Empty:
            return None

    @property
    def latency_ms(self) -> float:
        """Get measured callback latency in ms."""
        if not self._latency_samples:
            return 0.0
        return sum(self._latency_samples) / len(self._latency_samples)

    @property
    def running(self) -> bool:
        return self._running


def print_asio_info():
    """Print ASIO availability and device information."""
    print("\n" + "=" * 60)
    print("ASIO AUDIO INFORMATION")
    print("=" * 60)

    available, msg = check_asio_available()
    print(f"\nASIO Status: {'AVAILABLE' if available else 'NOT AVAILABLE'}")
    print(f"  {msg}")

    if available:
        devices = list_asio_devices()
        print(f"\nASIO Devices ({len(devices)}):")
        print("-" * 60)

        for dev in devices:
            print(f"\n  [{dev.index}] {dev.name}")
            print(f"      Inputs: {dev.input_channels}, Outputs: {dev.output_channels}")
            print(f"      Sample Rate: {dev.default_samplerate:.0f} Hz")
            print(
                f"      Latency: {dev.default_low_latency * 1000:.2f}ms (low) / "
                f"{dev.default_high_latency * 1000:.2f}ms (high)"
            )

    else:
        print("\nTo enable ASIO support:")
        print("  1. Install sounddevice via pip (not conda):")
        print("     pip install sounddevice")
        print("  2. Install an ASIO driver:")
        print("     - ASIO4ALL (generic): https://www.asio4all.org/")
        print("     - Native driver from your audio interface")
        print("  3. For system audio capture, install VB-Audio ASIO Bridge:")
        print("     https://vb-audio.com/Cable/")

    print("\n" + "=" * 60)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    print_asio_info()

    # Test capture if available
    available, _ = check_asio_available()
    if available:
        print("\nTesting ASIO capture for 3 seconds...")

        capture = AsioCapture(buffer_size=128)
        if capture.start():
            start_time = time.time()
            frame_count = 0

            while time.time() - start_time < 3:
                result = capture.get_audio()
                if result:
                    timestamp, audio = result
                    frame_count += 1

                time.sleep(0.001)

            capture.stop()
            print(f"Received {frame_count} audio frames")
            print(f"Measured callback interval: {capture.latency_ms:.2f}ms")
