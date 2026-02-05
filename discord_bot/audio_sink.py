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
    """

    def __init__(self, *, filters=None):
        super().__init__(filters=filters)
        self._callbacks: list[Callable[[bytes], None]] = []
        self._running = True

        # Buffer for mixing
        self._mix_buffer = bytearray(3840)  # 20ms stereo frame
        self._buffer_samples = 0
        self._last_flush = time.time()

    def add_callback(self, callback: Callable[[bytes], None]):
        """Add a callback for mixed audio data."""
        self._callbacks.append(callback)

    def write(self, data: bytes, user: int):
        """Called by Discord with audio data from each user."""
        if not self._running:
            return

        # For now, just forward the first user's audio
        # TODO: Implement proper audio mixing
        for callback in self._callbacks:
            try:
                callback(data)
            except Exception as e:
                print(f"Audio sink callback error: {e}")

    def cleanup(self):
        """Called when recording stops."""
        self._running = False
        self._callbacks.clear()
