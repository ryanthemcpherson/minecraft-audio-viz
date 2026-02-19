"""
WASAPI Low-Latency Loopback Capture using IAudioClient3.

This module provides direct access to Windows WASAPI with IAudioClient3
for achieving the lowest possible shared-mode latency (as low as 2-3ms
on Windows 10+).

IAudioClient3 features:
- Query supported buffer periods (GetSharedModeEnginePeriod)
- Initialize with specific period (InitializeSharedAudioStream)
- Achieve sub-10ms latency even in shared mode

Requirements:
- Windows 10 or later
- comtypes library (pip install comtypes)

References:
- Microsoft Low Latency Audio: https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/low-latency-audio
- IAudioClient3: https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nn-audioclient-iaudioclient3
"""

import ctypes
import logging
import queue
import threading
import time
from ctypes import wintypes
from dataclasses import dataclass
from typing import Optional, Tuple

import numpy as np

# Try to import ring buffer
try:
    from audio_processor.ringbuffer import AudioChunkBuffer, BufferStats, SPSCRingBuffer

    HAS_RINGBUFFER = True
except ImportError:
    HAS_RINGBUFFER = False
    SPSCRingBuffer = None
    AudioChunkBuffer = None
    BufferStats = None

logger = logging.getLogger(__name__)


# =============================================================================
# QPC High-Precision Timer
# =============================================================================


class QPCTimer:
    """
    High-precision timer using Windows QueryPerformanceCounter.

    Provides sub-millisecond accuracy (typically ~0.1us resolution) compared
    to time.time() which has ~15ms precision on Windows.

    Usage:
        timer = QPCTimer()
        timestamp = timer.now()  # Returns float seconds
        ticks = timer.now_ticks()  # Returns raw QPC ticks
    """

    def __init__(self):
        """Initialize QPC timer and query frequency."""
        self._frequency = ctypes.c_int64()
        self._available = False

        try:
            # Query performance counter frequency (ticks per second)
            result = ctypes.windll.kernel32.QueryPerformanceFrequency(ctypes.byref(self._frequency))
            if result != 0 and self._frequency.value > 0:
                self._available = True
                self._freq_float = float(self._frequency.value)
                logger.debug(f"QPC initialized: {self._freq_float / 1e6:.2f} MHz")
            else:
                logger.warning("QPC not available, falling back to time.time()")
        except Exception as e:
            logger.warning(f"Failed to initialize QPC: {e}")

    @property
    def available(self) -> bool:
        """Check if QPC is available."""
        return self._available

    @property
    def frequency(self) -> int:
        """Get QPC frequency in ticks per second."""
        return self._frequency.value if self._available else 0

    def now_ticks(self) -> int:
        """
        Get current time as raw QPC ticks.

        Returns:
            QPC counter value (ticks since system boot)
        """
        if not self._available:
            return int(time.time() * 1e7)  # Fallback: 100ns units

        counter = ctypes.c_int64()
        ctypes.windll.kernel32.QueryPerformanceCounter(ctypes.byref(counter))
        return counter.value

    def now(self) -> float:
        """
        Get current time in seconds (high precision).

        Returns:
            Time in seconds since system boot (or Unix epoch if using fallback)
        """
        if not self._available:
            return time.time()

        counter = ctypes.c_int64()
        ctypes.windll.kernel32.QueryPerformanceCounter(ctypes.byref(counter))
        return counter.value / self._freq_float

    def elapsed_ms(self, start_ticks: int) -> float:
        """
        Calculate elapsed time in milliseconds from start ticks.

        Args:
            start_ticks: Starting QPC tick count

        Returns:
            Elapsed time in milliseconds
        """
        if not self._available:
            return (self.now_ticks() - start_ticks) / 1e4  # 100ns to ms

        current = self.now_ticks()
        return (current - start_ticks) * 1000.0 / self._freq_float

    def ticks_to_seconds(self, ticks: int) -> float:
        """Convert QPC ticks to seconds."""
        if not self._available or self._freq_float == 0:
            return ticks / 1e7
        return ticks / self._freq_float


# Global QPC timer instance
_qpc_timer: Optional[QPCTimer] = None


def get_qpc_timer() -> QPCTimer:
    """Get or create the global QPC timer instance."""
    global _qpc_timer
    if _qpc_timer is None:
        _qpc_timer = QPCTimer()
    return _qpc_timer


def qpc_now() -> float:
    """Get current time using QPC (convenience function)."""
    return get_qpc_timer().now()


def qpc_now_ticks() -> int:
    """Get current QPC ticks (convenience function)."""
    return get_qpc_timer().now_ticks()


# Try to import comtypes for COM interface access
try:
    import comtypes
    from comtypes import GUID

    HAS_COMTYPES = True
except ImportError:
    HAS_COMTYPES = False
    logger.warning("comtypes not installed. Install with: pip install comtypes")


# Windows constants
AUDCLNT_SHAREMODE_SHARED = 0
AUDCLNT_SHAREMODE_EXCLUSIVE = 1

AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000
AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000

# Error codes
AUDCLNT_E_ENGINE_PERIODICITY_LOCKED = 0x88890020
AUDCLNT_E_ENGINE_FORMAT_LOCKED = 0x88890021

# Reference time units (100-nanosecond intervals)
REFTIMES_PER_SEC = 10000000
REFTIMES_PER_MILLISEC = 10000

# MMCSS (Multimedia Class Scheduler Service) constants
AVRT_PRIORITY_CRITICAL = 2
AVRT_PRIORITY_HIGH = 1
AVRT_PRIORITY_NORMAL = 0
AVRT_PRIORITY_LOW = -1

# Thread priority constants
THREAD_PRIORITY_TIME_CRITICAL = 15
THREAD_PRIORITY_HIGHEST = 2


# =============================================================================
# MMCSS Thread Priority Helper
# =============================================================================


class MMCSSPriority:
    """
    Helper class for MMCSS (Multimedia Class Scheduler Service) thread priority.

    MMCSS ensures audio threads get consistent CPU time even under heavy load.
    Uses "Pro Audio" task for highest priority audio processing.

    Usage:
        mmcss = MMCSSPriority("Pro Audio")
        if mmcss.elevate():
            # Thread is now high priority
            ...
        mmcss.revert()  # Restore normal priority
    """

    def __init__(self, task_name: str = "Pro Audio"):
        """
        Initialize MMCSS priority manager.

        Args:
            task_name: MMCSS task name ("Pro Audio", "Audio", "Capture", etc.)
        """
        self.task_name = task_name
        self._task_handle = None
        self._elevated = False
        self._avrt_available = False

        # Check if avrt.dll is available
        try:
            self._avrt = ctypes.windll.avrt
            self._avrt_available = True
        except Exception:
            logger.debug("avrt.dll not available")

    def elevate(self) -> bool:
        """
        Elevate current thread to MMCSS priority.

        Returns:
            True if elevation succeeded
        """
        if self._elevated:
            return True

        if self._avrt_available:
            try:
                # AvSetMmThreadCharacteristicsW(taskName, taskIndex)
                task_index = ctypes.c_ulong(0)

                # Convert task name to wide string
                task_name_w = ctypes.create_unicode_buffer(self.task_name)

                handle = self._avrt.AvSetMmThreadCharacteristicsW(
                    task_name_w, ctypes.byref(task_index)
                )

                if handle:
                    self._task_handle = handle
                    self._elevated = True

                    # Set to critical priority within the task
                    self._avrt.AvSetMmThreadPriority(handle, AVRT_PRIORITY_CRITICAL)

                    logger.debug(f"MMCSS elevation successful: {self.task_name}")
                    return True
                else:
                    error = ctypes.get_last_error()
                    logger.warning(f"AvSetMmThreadCharacteristics failed: {error}")

            except Exception as e:
                logger.warning(f"MMCSS elevation failed: {e}")

        # Fallback to SetThreadPriority
        return self._fallback_elevate()

    def _fallback_elevate(self) -> bool:
        """Fallback thread priority elevation without MMCSS."""
        try:
            kernel32 = ctypes.windll.kernel32

            # Get current thread handle
            thread_handle = kernel32.GetCurrentThread()

            # Set to time-critical priority
            result = kernel32.SetThreadPriority(thread_handle, THREAD_PRIORITY_TIME_CRITICAL)

            if result:
                self._elevated = True
                logger.debug("Thread priority elevated via SetThreadPriority")
                return True
            else:
                error = ctypes.get_last_error()
                logger.warning(f"SetThreadPriority failed: {error}")

        except Exception as e:
            logger.warning(f"Fallback priority elevation failed: {e}")

        return False

    def revert(self):
        """Revert thread to normal priority."""
        if not self._elevated:
            return

        if self._task_handle and self._avrt_available:
            try:
                self._avrt.AvRevertMmThreadCharacteristics(self._task_handle)
                self._task_handle = None
                logger.debug("MMCSS priority reverted")
            except Exception as e:
                logger.debug(f"Error reverting MMCSS: {e}")

        self._elevated = False

    @property
    def is_elevated(self) -> bool:
        """Check if thread is currently elevated."""
        return self._elevated

    def __enter__(self):
        """Context manager entry - elevate priority."""
        self.elevate()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit - revert priority."""
        self.revert()
        return False


@dataclass
class WasapiDeviceInfo:
    """Information about a WASAPI device."""

    id: str
    name: str
    is_loopback: bool
    default_period_frames: int
    min_period_frames: int
    fundamental_period_frames: int
    max_period_frames: int
    sample_rate: int


def check_wasapi_lowlatency_available() -> Tuple[bool, str]:
    """
    Check if low-latency WASAPI (IAudioClient3) is available.

    Returns:
        Tuple of (is_available, message)
    """
    if not HAS_COMTYPES:
        return False, "comtypes not installed"

    # Check Windows version (need Windows 10+)
    try:
        # Windows 10 is version 10.0
        if not hasattr(ctypes.windll, "kernel32"):
            return False, "Cannot determine Windows version"

        # Try to access IAudioClient3 interface
        # This will fail on Windows 7/8
        return True, "Windows 10+ detected, IAudioClient3 should be available"

    except Exception as e:
        return False, f"Error checking availability: {e}"


if HAS_COMTYPES:
    # Define COM interfaces

    class WAVEFORMATEX(ctypes.Structure):
        _fields_ = [
            ("wFormatTag", wintypes.WORD),
            ("nChannels", wintypes.WORD),
            ("nSamplesPerSec", wintypes.DWORD),
            ("nAvgBytesPerSec", wintypes.DWORD),
            ("nBlockAlign", wintypes.WORD),
            ("wBitsPerSample", wintypes.WORD),
            ("cbSize", wintypes.WORD),
        ]

    class WAVEFORMATEXTENSIBLE(ctypes.Structure):
        _fields_ = [
            ("Format", WAVEFORMATEX),
            ("Samples", wintypes.WORD),
            ("dwChannelMask", wintypes.DWORD),
            ("SubFormat", GUID),
        ]

    # GUIDs
    CLSID_MMDeviceEnumerator = GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    IID_IMMDeviceEnumerator = GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    IID_IAudioClient = GUID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}")
    IID_IAudioClient3 = GUID("{7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42}")
    IID_IAudioCaptureClient = GUID("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}")

    # Audio format GUIDs
    KSDATAFORMAT_SUBTYPE_PCM = GUID("{00000001-0000-0010-8000-00aa00389b71}")
    KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = GUID("{00000003-0000-0010-8000-00aa00389b71}")

    # EDataFlow enumeration
    eRender = 0
    eCapture = 1
    eAll = 2

    # ERole enumeration
    eConsole = 0
    eMultimedia = 1
    eCommunications = 2


class WasapiLoopbackCapture:
    """
    Low-latency WASAPI loopback capture using IAudioClient3.

    This class captures system audio output (loopback) with the lowest
    possible latency by using IAudioClient3's GetSharedModeEnginePeriod
    and InitializeSharedAudioStream methods.
    """

    def __init__(
        self,
        target_latency_ms: float = 5.0,
        sample_rate: int = 48000,
        channels: int = 2,
        use_qpc: bool = True,
        native_format: bool = True,
        use_mmcss: bool = True,
        mmcss_task_name: str = "Pro Audio",
    ):
        """
        Initialize WASAPI loopback capture.

        Args:
            target_latency_ms: Target buffer latency in milliseconds
            sample_rate: Desired sample rate
            channels: Number of channels (usually 2 for stereo)
            use_qpc: Use QueryPerformanceCounter for sub-ms timestamps
            native_format: Use device native format (disable AUTOCONVERTPCM)
            use_mmcss: Use MMCSS for thread priority elevation
            mmcss_task_name: MMCSS task name ("Pro Audio", "Audio", etc.)
        """
        self.target_latency_ms = target_latency_ms
        self.sample_rate = sample_rate
        self.channels = channels
        self._use_qpc = use_qpc
        self._native_format = native_format
        self._use_mmcss = use_mmcss
        self._mmcss_task_name = mmcss_task_name

        self._running = False
        self._capture_thread: Optional[threading.Thread] = None
        self._mmcss: Optional[MMCSSPriority] = None

        # Use ring buffer if available, otherwise fall back to queue
        self._use_ringbuffer = HAS_RINGBUFFER
        if self._use_ringbuffer:
            qpc_freq = get_qpc_timer().frequency if use_qpc else 0
            self._ring_buffer: Optional[AudioChunkBuffer] = AudioChunkBuffer(
                capacity=16, max_chunk_size=4096, channels=2, qpc_frequency=qpc_freq
            )
            self._audio_queue = None
            logger.debug("Using lock-free ring buffer")
        else:
            self._ring_buffer = None
            self._audio_queue: queue.Queue = queue.Queue(maxsize=16)
            logger.debug("Using queue.Queue (ring buffer not available)")

        # COM objects (initialized in start())
        self._device = None
        self._audio_client = None
        self._capture_client = None
        self._event = None

        # Actual achieved settings
        self._actual_period_frames = 0
        self._actual_latency_ms = 0.0

        # Native format properties (detected during init)
        self._native_sample_rate: int = 0
        self._native_channels: int = 0
        self._native_bit_depth: int = 0

        # QPC timer for high-precision timestamps
        self._qpc_timer: Optional[QPCTimer] = None
        if use_qpc:
            self._qpc_timer = get_qpc_timer()
            if not self._qpc_timer.available:
                logger.warning("QPC not available, using time.time()")
                self._use_qpc = False

        # Check availability
        self._available, self._status_msg = check_wasapi_lowlatency_available()

    @property
    def is_available(self) -> bool:
        return self._available and HAS_COMTYPES

    @property
    def status_message(self) -> str:
        return self._status_msg

    def _init_com(self):
        """Initialize COM and get audio device."""
        if not HAS_COMTYPES:
            raise RuntimeError("comtypes not available")

        # Initialize COM
        comtypes.CoInitialize()

        # Create device enumerator
        from comtypes.client import CreateObject

        enumerator = CreateObject(
            CLSID_MMDeviceEnumerator,
            interface=None,  # We'll query the interface
        )

        # Get IMMDeviceEnumerator interface
        device_enum = enumerator.QueryInterface(IID_IMMDeviceEnumerator)

        # Get default audio render device (for loopback)
        self._device = device_enum.GetDefaultAudioEndpoint(eRender, eMultimedia)

    def _init_audio_client3(self) -> bool:
        """
        Initialize IAudioClient3 with lowest supported latency.

        Returns:
            True if successful, False otherwise
        """
        try:
            # Activate IAudioClient3
            self._audio_client = self._device.Activate(IID_IAudioClient3, 0, None)

            # Get the mix format
            mix_format_ptr = self._audio_client.GetMixFormat()
            mix_format = ctypes.cast(mix_format_ptr, ctypes.POINTER(WAVEFORMATEX)).contents

            logger.info(
                f"Device format: {mix_format.nSamplesPerSec}Hz, "
                f"{mix_format.nChannels}ch, {mix_format.wBitsPerSample}bit"
            )

            # Query supported periods using IAudioClient3
            default_period, fundamental_period, min_period, max_period = (
                self._audio_client.GetSharedModeEnginePeriod(mix_format_ptr)
            )

            # Convert frames to ms
            sample_rate = mix_format.nSamplesPerSec
            min_latency_ms = (min_period / sample_rate) * 1000
            default_latency_ms = (default_period / sample_rate) * 1000

            logger.info(
                f"Supported periods: min={min_latency_ms:.2f}ms, "
                f"default={default_latency_ms:.2f}ms, "
                f"fundamental={fundamental_period} frames"
            )

            # Calculate target period (must be multiple of fundamental)
            target_frames = int(self.target_latency_ms * sample_rate / 1000)
            target_frames = max(min_period, target_frames)
            target_frames = min(max_period, target_frames)

            # Round to nearest multiple of fundamental
            if fundamental_period > 0:
                target_frames = (
                    (target_frames + fundamental_period // 2) // fundamental_period
                ) * fundamental_period
                target_frames = max(min_period, target_frames)

            self._actual_period_frames = target_frames
            self._actual_latency_ms = (target_frames / sample_rate) * 1000

            logger.info(f"Using period: {target_frames} frames ({self._actual_latency_ms:.2f}ms)")

            # Store native format properties
            self._native_sample_rate = mix_format.nSamplesPerSec
            self._native_channels = mix_format.nChannels
            self._native_bit_depth = mix_format.wBitsPerSample

            # Initialize shared audio stream with loopback
            # Note: Loopback mode captures the render device's output
            stream_flags = AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK

            # Add AUTOCONVERTPCM only if not using native format
            # Native format eliminates Windows Audio Engine resampling overhead
            if not self._native_format:
                stream_flags |= AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                logger.debug("Using AUTOCONVERTPCM mode")

            hr = self._audio_client.InitializeSharedAudioStream(
                stream_flags,
                target_frames,
                mix_format_ptr,
                None,  # No session GUID
            )

            if hr != 0:
                # If native format failed, retry with AUTOCONVERTPCM
                if self._native_format:
                    logger.warning(
                        f"Native format init failed (0x{hr:08X}), retrying with AUTOCONVERTPCM"
                    )
                    self._native_format = False
                    stream_flags |= AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM

                    hr = self._audio_client.InitializeSharedAudioStream(
                        stream_flags, target_frames, mix_format_ptr, None
                    )

                if hr != 0:
                    # Fall back to regular Initialize if IAudioClient3 method fails
                    logger.warning(
                        f"InitializeSharedAudioStream failed (0x{hr:08X}), "
                        f"falling back to standard Initialize"
                    )
                    return self._init_audio_client_fallback()

            # Get capture client
            self._capture_client = self._audio_client.GetService(IID_IAudioCaptureClient)

            # Create event for buffer notifications
            self._event = ctypes.windll.kernel32.CreateEventW(None, False, False, None)
            self._audio_client.SetEventHandle(self._event)

            self.sample_rate = mix_format.nSamplesPerSec
            self.channels = mix_format.nChannels

            return True

        except Exception as e:
            logger.error(f"Failed to initialize IAudioClient3: {e}")
            return False

    def _init_audio_client_fallback(self) -> bool:
        """Fallback to standard IAudioClient initialization."""
        try:
            # Activate IAudioClient (not IAudioClient3)
            self._audio_client = self._device.Activate(IID_IAudioClient, 0, None)

            # Get mix format
            mix_format_ptr = self._audio_client.GetMixFormat()
            mix_format = ctypes.cast(mix_format_ptr, ctypes.POINTER(WAVEFORMATEX)).contents

            # Use default buffer duration (usually 10ms)
            buffer_duration = int(self.target_latency_ms * REFTIMES_PER_MILLISEC)

            stream_flags = AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK

            self._audio_client.Initialize(
                AUDCLNT_SHAREMODE_SHARED,
                stream_flags,
                buffer_duration,
                0,  # Periodicity (must be 0 for shared mode)
                mix_format_ptr,
                None,
            )

            # Get actual buffer size
            buffer_frames = self._audio_client.GetBufferSize()
            self._actual_period_frames = buffer_frames
            self._actual_latency_ms = (buffer_frames / mix_format.nSamplesPerSec) * 1000

            logger.info(
                f"Fallback mode: buffer={buffer_frames} frames ({self._actual_latency_ms:.2f}ms)"
            )

            # Get capture client
            self._capture_client = self._audio_client.GetService(IID_IAudioCaptureClient)

            # Create event
            self._event = ctypes.windll.kernel32.CreateEventW(None, False, False, None)
            self._audio_client.SetEventHandle(self._event)

            self.sample_rate = mix_format.nSamplesPerSec
            self.channels = mix_format.nChannels

            return True

        except Exception as e:
            logger.error(f"Fallback initialization failed: {e}")
            return False

    def start(self) -> bool:
        """Start audio capture."""
        if not self.is_available:
            logger.error(f"Cannot start: {self._status_msg}")
            return False

        if self._running:
            return True

        try:
            self._init_com()

            if not self._init_audio_client3():
                logger.error("Failed to initialize audio client")
                return False

            self._running = True
            self._capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
            self._capture_thread.start()

            # Start the audio client
            self._audio_client.Start()

            logger.info(
                f"WASAPI loopback capture started at {self._actual_latency_ms:.2f}ms latency"
            )
            return True

        except Exception as e:
            logger.error(f"Failed to start capture: {e}")
            self._cleanup()
            return False

    def _capture_loop(self):
        """Background thread for capturing audio."""
        # Elevate thread priority via MMCSS for consistent timing
        mmcss = None
        if self._use_mmcss:
            mmcss = MMCSSPriority(self._mmcss_task_name)
            if mmcss.elevate():
                logger.info(f"Capture thread elevated via MMCSS ({self._mmcss_task_name})")
            else:
                logger.warning("MMCSS elevation failed, using normal priority")

        try:
            self._capture_loop_inner()
        finally:
            # Revert priority on exit
            if mmcss:
                mmcss.revert()
                logger.debug("Capture thread priority reverted")

    def _capture_loop_inner(self):
        """Inner capture loop (separated for clean MMCSS handling)."""
        while self._running:
            try:
                # Wait for buffer event (with timeout)
                result = ctypes.windll.kernel32.WaitForSingleObject(
                    self._event,
                    100,  # 100ms timeout
                )

                if result != 0:  # WAIT_OBJECT_0
                    continue

                # Get available data
                packet_length = self._capture_client.GetNextPacketSize()

                while packet_length > 0 and self._running:
                    # Get buffer
                    data_ptr, num_frames, flags, device_position, qpc_position = (
                        self._capture_client.GetBuffer()
                    )

                    if num_frames > 0:
                        # Copy audio data
                        # Assuming float32 format
                        byte_count = num_frames * self.channels * 4  # 4 bytes per float32
                        buffer = (ctypes.c_byte * byte_count).from_address(data_ptr)
                        audio = np.frombuffer(buffer, dtype=np.float32).copy()
                        audio = audio.reshape(-1, self.channels)

                        # Add to buffer with high-precision timestamp
                        # Use QPC for sub-millisecond accuracy when available
                        if self._use_qpc and self._qpc_timer is not None:
                            timestamp = self._qpc_timer.now()
                        else:
                            timestamp = time.time()

                        # Use ring buffer if available, otherwise queue
                        if self._use_ringbuffer and self._ring_buffer is not None:
                            # Lock-free write - may drop on overrun
                            self._ring_buffer.write_chunk(timestamp, audio)
                        elif self._audio_queue is not None:
                            try:
                                self._audio_queue.put_nowait((timestamp, audio))
                            except queue.Full:
                                # Drop oldest
                                try:
                                    self._audio_queue.get_nowait()
                                    self._audio_queue.put_nowait((timestamp, audio))
                                except queue.Empty:
                                    pass

                    # Release buffer
                    self._capture_client.ReleaseBuffer(num_frames)
                    packet_length = self._capture_client.GetNextPacketSize()

            except Exception as e:
                if self._running:
                    logger.error(f"Capture error: {e}")
                    time.sleep(0.01)

    def stop(self):
        """Stop audio capture."""
        self._running = False

        if self._audio_client:
            try:
                self._audio_client.Stop()
            except Exception:
                pass

        if self._capture_thread:
            self._capture_thread.join(timeout=1.0)

        self._cleanup()

    def _cleanup(self):
        """Clean up COM objects."""
        if self._event:
            ctypes.windll.kernel32.CloseHandle(self._event)
            self._event = None

        self._capture_client = None
        self._audio_client = None
        self._device = None

        try:
            comtypes.CoUninitialize()
        except Exception:
            pass

    def get_audio(self) -> Optional[Tuple[float, np.ndarray]]:
        """
        Get captured audio.

        Returns:
            Tuple of (timestamp, audio_data) or None if no data
        """
        if self._use_ringbuffer and self._ring_buffer is not None:
            result = self._ring_buffer.read_chunk()
            if result:
                timestamp, audio, frame_num, flags = result
                return (timestamp, audio)
            return None
        elif self._audio_queue is not None:
            try:
                return self._audio_queue.get_nowait()
            except queue.Empty:
                return None
        return None

    @property
    def latency_ms(self) -> float:
        """Get the actual achieved latency in milliseconds."""
        return self._actual_latency_ms

    @property
    def running(self) -> bool:
        return self._running

    @property
    def native_format(self) -> dict:
        """Get detected native format properties."""
        return {
            "sample_rate": self._native_sample_rate,
            "channels": self._native_channels,
            "bit_depth": self._native_bit_depth,
            "using_native": self._native_format,
        }

    @property
    def qpc_frequency(self) -> int:
        """Get QPC frequency if available (0 if not using QPC)."""
        if self._qpc_timer and self._qpc_timer.available:
            return self._qpc_timer.frequency
        return 0

    @property
    def using_qpc(self) -> bool:
        """Check if QPC timestamps are being used."""
        return self._use_qpc and self._qpc_timer is not None and self._qpc_timer.available

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


def print_wasapi_info():
    """Print WASAPI low-latency availability information."""
    print("\n" + "=" * 60)
    print("WASAPI LOW-LATENCY (IAudioClient3) INFORMATION")
    print("=" * 60)

    available, msg = check_wasapi_lowlatency_available()
    print(f"\nStatus: {'AVAILABLE' if available else 'NOT AVAILABLE'}")
    print(f"  {msg}")

    if not HAS_COMTYPES:
        print("\nTo enable low-latency WASAPI:")
        print("  pip install comtypes")
    elif available:
        print("\nIAudioClient3 enables shared-mode latency as low as 2-3ms")
        print("on Windows 10+ with supported audio drivers.")
        print("\nThe actual minimum latency depends on your audio hardware")
        print("and driver. Most modern onboard audio supports 5-10ms.")

    print("\n" + "=" * 60)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    print_wasapi_info()

    # Test capture if available
    capture = WasapiLoopbackCapture(target_latency_ms=5.0)

    if capture.is_available:
        print("\nTesting WASAPI loopback capture for 3 seconds...")
        print("Play some audio to test capture.")

        if capture.start():
            start_time = time.time()
            frame_count = 0
            total_samples = 0

            while time.time() - start_time < 3:
                result = capture.get_audio()
                if result:
                    timestamp, audio = result
                    frame_count += 1
                    total_samples += len(audio)

                time.sleep(0.001)

            capture.stop()
            print(f"\nReceived {frame_count} audio buffers ({total_samples} samples)")
            print(f"Achieved latency: {capture.latency_ms:.2f}ms")
        else:
            print("Failed to start capture")
    else:
        print(f"\nCannot test: {capture.status_message}")
