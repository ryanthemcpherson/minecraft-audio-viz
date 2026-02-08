"""
Custom audio sink for capturing Discord voice channel audio.
"""

import discord
from discord.sinks import Sink, Filters
import asyncio
from typing import Optional, Callable
import time


class VisualizerSink(Sink):
    """
    Custom Discord audio sink that forwards audio data to processors.

    Discord sends audio as:
    - 48kHz sample rate
    - 16-bit signed PCM
    - Stereo (2 channels)
    - 20ms frames (960 samples per channel = 3840 bytes per frame)
    """

    def __init__(self, *, filters=None):
        super().__init__(filters=filters)
        self._callbacks: list[Callable[[bytes, int], None]] = []
        self._running = True

    def add_callback(self, callback: Callable[[bytes, int], None]):
        """
        Add a callback for audio data.

        Callback receives (pcm_bytes, user_id) for each audio frame.
        """
        self._callbacks.append(callback)

    def remove_callback(self, callback: Callable[[bytes, int], None]):
        """Remove a callback."""
        if callback in self._callbacks:
            self._callbacks.remove(callback)

    def write(self, data: bytes, user: int):
        """
        Called by Discord with audio data.

        Args:
            data: Raw PCM audio bytes
            user: Discord user ID
        """
        if not self._running:
            return

        for callback in self._callbacks:
            try:
                callback(data, user)
            except Exception as e:
                print(f"Audio sink callback error: {e}")

    def cleanup(self):
        """Called when recording stops."""
        self._running = False
        self._callbacks.clear()

    @Filters.container
    def filter_users(self, data, user):
        """Filter to only process specific users if needed."""
        return data


class MixedAudioSink(Sink):
    """
    Audio sink that mixes all users' audio together.
    Better for visualization since we care about the overall audio.

    Performs proper PCM mixing by summing samples from all users within
    each 20ms frame window and applying soft clipping to prevent distortion.
    """

    # Discord audio format constants
    SAMPLE_RATE = 48000
    CHANNELS = 2
    SAMPLE_WIDTH = 2  # 16-bit = 2 bytes
    FRAME_DURATION_MS = 20
    FRAME_SAMPLES = int(SAMPLE_RATE * FRAME_DURATION_MS / 1000)  # 960 samples per channel
    FRAME_BYTES = FRAME_SAMPLES * CHANNELS * SAMPLE_WIDTH  # 3840 bytes

    def __init__(self, *, filters=None, max_users: int = 10):
        super().__init__(filters=filters)
        self._callbacks: list[Callable[[bytes], None]] = []
        self._running = True
        self._max_users = max_users

        # Per-user accumulation buffers for current frame window
        # Maps user_id -> numpy array of int32 samples (to prevent overflow during mixing)
        self._user_buffers: dict[int, 'np.ndarray'] = {}

        # Mixed output buffer (int32 to allow headroom during summation)
        self._mix_accumulator = None

        # Frame timing
        self._last_flush = time.time()
        self._flush_interval = self.FRAME_DURATION_MS / 1000.0  # 20ms
        self._frame_count = 0

    def add_callback(self, callback: Callable[[bytes], None]):
        """Add a callback for mixed audio data."""
        self._callbacks.append(callback)

    def remove_callback(self, callback: Callable[[bytes], None]):
        """Remove a callback."""
        if callback in self._callbacks:
            self._callbacks.remove(callback)

    def write(self, data: bytes, user: int):
        """
        Called by Discord with audio data from each user.

        Accumulates audio from all active users and mixes them together.
        When the frame window elapses, the mixed audio is flushed to callbacks.

        Args:
            data: Raw 16-bit signed PCM audio bytes (stereo, 48kHz)
            user: Discord user ID
        """
        if not self._running:
            return

        try:
            import numpy as np

            # Convert incoming PCM bytes to int32 numpy array (extra headroom for mixing)
            samples = np.frombuffer(data, dtype=np.int16).astype(np.int32)

            # Initialize mix accumulator on first write
            if self._mix_accumulator is None:
                self._mix_accumulator = np.zeros(len(samples), dtype=np.int32)

            # Accumulate this user's audio into the mix
            target_len = len(self._mix_accumulator)
            if len(samples) >= target_len:
                self._mix_accumulator += samples[:target_len]
            else:
                self._mix_accumulator[:len(samples)] += samples

            self._frame_count += 1

            # Flush mixed audio at frame boundaries
            now = time.time()
            if now - self._last_flush >= self._flush_interval:
                self._flush_mixed_audio()
                self._last_flush = now

        except ImportError:
            # Fallback without numpy: just forward raw audio
            for callback in self._callbacks:
                try:
                    callback(data)
                except Exception as e:
                    print(f"Audio sink callback error: {e}")
        except Exception as e:
            print(f"Audio mixing error: {e}")

    def _flush_mixed_audio(self):
        """Flush the mixed audio buffer to all callbacks."""
        if self._mix_accumulator is None or not self._callbacks:
            return

        try:
            import numpy as np

            mixed = self._mix_accumulator

            # Soft clipping to prevent distortion while preserving dynamics
            # Uses tanh-based soft clip that compresses peaks smoothly
            max_val = np.max(np.abs(mixed))
            if max_val > 32767:
                # Apply soft clipping: tanh compression scaled to int16 range
                normalized = mixed.astype(np.float64) / max_val
                clipped = np.tanh(normalized * 1.5) * 32767
                mixed = clipped.astype(np.int16)
            else:
                mixed = mixed.astype(np.int16)

            # Send mixed audio to callbacks
            mixed_bytes = mixed.tobytes()
            for callback in self._callbacks:
                try:
                    callback(mixed_bytes)
                except Exception as e:
                    print(f"Audio sink callback error: {e}")

            # Reset accumulator for next frame
            self._mix_accumulator.fill(0)
            self._frame_count = 0

        except Exception as e:
            print(f"Audio flush error: {e}")

    def cleanup(self):
        """Called when recording stops."""
        # Flush any remaining audio
        if self._mix_accumulator is not None and self._frame_count > 0:
            self._flush_mixed_audio()
        self._running = False
        self._callbacks.clear()
        self._mix_accumulator = None
        self._user_buffers.clear()
