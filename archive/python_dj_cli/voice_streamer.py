"""
Voice Audio Streamer - Streams raw PCM audio for Simple Voice Chat integration.

Accepts raw audio buffers from the capture pipeline, resamples to 48kHz mono,
chunks into 960-sample (20ms) frames, and provides them as base64-encoded bytes
for transmission via WebSocket.

Output format: 48kHz, mono, 16-bit signed PCM (int16), little-endian
Frame size: 960 samples = 1920 bytes = 20ms of audio

Supports optional Opus encoding via opuslib for ~12x bandwidth reduction.
"""

import base64
import logging
import threading
from collections import deque
from typing import List

import numpy as np

# Optional Opus encoding for bandwidth reduction (~12x smaller than raw PCM)
try:
    import opuslib

    HAS_OPUS = True
except ImportError:
    HAS_OPUS = False

logger = logging.getLogger("voice_streamer")

# Output format constants
VOICE_SAMPLE_RATE = 48000
VOICE_FRAME_SAMPLES = 960  # 20ms at 48kHz
VOICE_FRAME_BYTES = VOICE_FRAME_SAMPLES * 2  # 1920 bytes (int16 = 2 bytes per sample)
MAX_BUFFER_FRAMES = 50  # ~1 second of buffered audio


class VoiceStreamer:
    """
    Streams raw PCM audio frames for Minecraft Simple Voice Chat.

    Accepts float32 audio from the WASAPI capture pipeline, resamples to 48kHz mono,
    converts to int16 PCM, and chunks into 960-sample frames (20ms each).

    Thread-safe: audio is fed from the WASAPI callback thread, frames are consumed
    from the async event loop thread.
    """

    def __init__(self, source_rate: int = 48000, source_channels: int = 2):
        """
        Initialize the voice streamer.

        Args:
            source_rate: Sample rate of incoming audio (will resample if != 48000)
            source_channels: Number of channels in incoming audio (will downmix if > 1)
        """
        self._source_rate = source_rate
        self._source_channels = source_channels

        # Enable/disable state
        self._enabled = False

        # Voice config state (received from admin panel)
        self._channel_type = "static"  # "static" or "locational"
        self._distance = 100.0
        self._zone = "main"

        # Frame buffer (thread-safe via lock)
        self._lock = threading.Lock()
        self._pending_frames: deque = deque(maxlen=MAX_BUFFER_FRAMES)

        # Residual sample buffer for chunking (mono float32 at 48kHz)
        self._residual = np.zeros(0, dtype=np.float32)

        # Sequence counter
        self._seq = 0

        # Opus encoder (optional, ~12x bandwidth reduction)
        if HAS_OPUS:
            self._opus_encoder = opuslib.Encoder(48000, 1, opuslib.APPLICATION_AUDIO)
            self._codec = "opus"
        else:
            self._opus_encoder = None
            self._codec = "pcm"

        logger.info(
            f"VoiceStreamer initialized: source={source_rate}Hz/{source_channels}ch "
            f"-> output={VOICE_SAMPLE_RATE}Hz/mono/int16, codec={self._codec}"
        )

    @property
    def enabled(self) -> bool:
        """Whether voice streaming is currently enabled."""
        return self._enabled

    @enabled.setter
    def enabled(self, value: bool):
        """Enable or disable voice streaming."""
        if value != self._enabled:
            self._enabled = value
            if value:
                logger.info("Voice streaming enabled")
            else:
                logger.info("Voice streaming disabled")
                # Clear buffer when disabling
                with self._lock:
                    self._pending_frames.clear()
                    self._residual = np.zeros(0, dtype=np.float32)

    @property
    def source_rate(self) -> int:
        """Current source sample rate."""
        return self._source_rate

    @source_rate.setter
    def source_rate(self, value: int):
        """Update source sample rate (e.g., when device changes)."""
        if value != self._source_rate:
            self._source_rate = value
            logger.info(f"VoiceStreamer source rate updated: {value}Hz")

    @property
    def source_channels(self) -> int:
        """Current source channel count."""
        return self._source_channels

    @source_channels.setter
    def source_channels(self, value: int):
        """Update source channel count."""
        if value != self._source_channels:
            self._source_channels = value
            logger.info(f"VoiceStreamer source channels updated: {value}")

    @property
    def seq(self) -> int:
        """Current sequence number."""
        return self._seq

    @property
    def channel_type(self) -> str:
        """Voice channel type ('static' or 'locational')."""
        return self._channel_type

    @property
    def distance(self) -> float:
        """Voice channel distance."""
        return self._distance

    @property
    def zone(self) -> str:
        """Voice zone name."""
        return self._zone

    def apply_voice_config(self, config: dict):
        """
        Apply voice configuration from admin panel.

        Args:
            config: Dict with keys: enabled, channel_type, distance, zone
        """
        if "enabled" in config:
            self.enabled = bool(config["enabled"])
        if "channel_type" in config:
            ct = config["channel_type"]
            if ct in ("static", "locational"):
                self._channel_type = ct
        if "distance" in config:
            self._distance = max(1.0, min(1000.0, float(config["distance"])))
        if "zone" in config:
            self._zone = str(config["zone"])

        logger.info(
            f"Voice config updated: enabled={self._enabled}, "
            f"type={self._channel_type}, distance={self._distance}, zone={self._zone}"
        )

    def feed_audio(self, audio: np.ndarray):
        """
        Feed raw audio samples into the streamer.

        Called from the audio capture callback thread. The audio is resampled,
        downmixed, converted to int16, and chunked into 960-sample frames.

        Args:
            audio: Raw audio as numpy array. Shape: (samples,) for mono
                   or (samples, channels) for multi-channel. dtype: float32, range [-1, 1].
        """
        if not self._enabled:
            return

        if audio.size == 0:
            return

        try:
            # === 1. DOWNMIX TO MONO ===
            if audio.ndim > 1 and audio.shape[1] > 1:
                mono = np.mean(audio, axis=1, dtype=np.float32)
            elif audio.ndim > 1:
                mono = audio[:, 0].astype(np.float32)
            else:
                mono = audio.astype(np.float32)

            # === 2. RESAMPLE TO 48kHz ===
            if self._source_rate != VOICE_SAMPLE_RATE:
                ratio = VOICE_SAMPLE_RATE / self._source_rate
                num_samples = int(len(mono) * ratio)
                if num_samples > 0:
                    mono = np.interp(
                        np.linspace(0, len(mono) - 1, num_samples),
                        np.arange(len(mono)),
                        mono,
                    ).astype(np.float32)
                else:
                    return

            # === 3. APPEND TO RESIDUAL AND CHUNK ===
            with self._lock:
                # Concatenate with leftover samples from previous call
                if self._residual.size > 0:
                    mono = np.concatenate([self._residual, mono])

                # Extract complete frames
                num_complete_frames = len(mono) // VOICE_FRAME_SAMPLES
                if num_complete_frames > 0:
                    # Process all complete frames
                    for i in range(num_complete_frames):
                        start = i * VOICE_FRAME_SAMPLES
                        end = start + VOICE_FRAME_SAMPLES
                        frame_float = mono[start:end]

                        # === 4. CONVERT FLOAT32 [-1,1] TO INT16 (little-endian) ===
                        # Clip to prevent overflow, then scale
                        frame_int16 = np.clip(frame_float, -1.0, 1.0)
                        frame_int16 = (frame_int16 * 32767).astype(np.int16)
                        frame_bytes = frame_int16.astype("<i2").tobytes()  # Explicit little-endian

                        # Add to pending frames (deque maxlen handles overflow)
                        self._pending_frames.append(frame_bytes)

                    # Save remaining samples as residual
                    consumed = num_complete_frames * VOICE_FRAME_SAMPLES
                    self._residual = mono[consumed:].copy()
                else:
                    # Not enough samples yet, save all as residual
                    self._residual = mono.copy()

        except Exception as e:
            logger.debug(f"VoiceStreamer feed error: {e}")

    def get_frames(self) -> List[bytes]:
        """
        Get all pending audio frames as raw PCM bytes.

        Each frame is 1920 bytes (960 int16 samples at 48kHz mono).
        Frames are already suitable for base64 encoding.

        Returns:
            List of raw PCM byte buffers. Empty list if no frames available.
        """
        if not self._enabled:
            return []

        with self._lock:
            if not self._pending_frames:
                return []

            frames = list(self._pending_frames)
            self._pending_frames.clear()

        return frames

    def get_frames_base64(self) -> List[dict]:
        """
        Get all pending frames as base64-encoded voice_audio messages.

        If Opus is available, frames are encoded with Opus before base64 (~12x smaller).
        Otherwise, raw PCM bytes are base64-encoded directly.

        Returns:
            List of dicts ready to be JSON-serialized and sent via WebSocket.
            Each dict has: {"type": "voice_audio", "data": "<base64>", "seq": N, "codec": "opus"|"pcm"}
        """
        raw_frames = self.get_frames()
        if not raw_frames:
            return []

        messages = []
        for frame_bytes in raw_frames:
            self._seq += 1

            # Encode with Opus if available, otherwise send raw PCM
            if self._opus_encoder is not None:
                try:
                    encoded_bytes = self._opus_encoder.encode(frame_bytes, VOICE_FRAME_SAMPLES)
                except Exception:
                    # Fallback to raw PCM on encode error
                    encoded_bytes = frame_bytes
            else:
                encoded_bytes = frame_bytes

            messages.append(
                {
                    "type": "voice_audio",
                    "data": base64.b64encode(encoded_bytes).decode("ascii"),
                    "seq": self._seq,
                    "codec": self._codec,
                }
            )

        return messages

    @property
    def buffered_frame_count(self) -> int:
        """Number of frames currently buffered."""
        with self._lock:
            return len(self._pending_frames)

    @property
    def buffer_duration_ms(self) -> float:
        """Duration of buffered audio in milliseconds."""
        return self.buffered_frame_count * 20.0
