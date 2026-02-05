"""
Lock-Free Single-Producer Single-Consumer (SPSC) Ring Buffer.

Optimized for low-latency audio processing with minimal GC pressure.
Uses pre-allocated numpy arrays and atomic index operations via Python GIL.

Usage:
    buffer = SPSCRingBuffer(capacity=16, max_chunk_size=2048)

    # Producer thread
    buffer.try_write(timestamp, audio_data)

    # Consumer thread
    result = buffer.try_read()
    if result is not None:
        timestamp, audio_data = result
"""

import numpy as np
from dataclasses import dataclass
from typing import Optional, Tuple
import threading


@dataclass
class BufferStats:
    """Statistics for ring buffer operations."""
    writes: int = 0
    reads: int = 0
    overruns: int = 0      # Writes dropped due to full buffer
    underruns: int = 0     # Read attempts on empty buffer
    capacity: int = 0
    current_fill: int = 0

    def reset(self):
        """Reset all counters."""
        self.writes = 0
        self.reads = 0
        self.overruns = 0
        self.underruns = 0


class SPSCRingBuffer:
    """
    Single-Producer Single-Consumer lock-free ring buffer for audio data.

    Thread-safe for one producer and one consumer thread without locks.
    Uses pre-allocated numpy arrays to avoid GC during audio processing.

    Memory Layout:
    - Fixed-size slots for audio chunks
    - Separate timestamp array
    - Separate size array (actual samples per slot)

    Attributes:
        capacity: Number of audio chunks the buffer can hold
        max_chunk_size: Maximum samples per chunk (stereo = frames * 2)
    """

    def __init__(self, capacity: int = 16, max_chunk_size: int = 4096, channels: int = 2):
        """
        Initialize the ring buffer.

        Args:
            capacity: Number of audio chunk slots (power of 2 recommended)
            max_chunk_size: Maximum frames per chunk
            channels: Audio channels (default 2 for stereo)
        """
        self.capacity = capacity
        self.max_chunk_size = max_chunk_size
        self.channels = channels

        # Pre-allocate storage arrays (avoid allocations in hot path)
        # Shape: (capacity, max_frames, channels) for easy stereo handling
        self._data = np.zeros((capacity, max_chunk_size, channels), dtype=np.float32)
        self._timestamps = np.zeros(capacity, dtype=np.float64)
        self._sizes = np.zeros(capacity, dtype=np.int32)  # Actual frame count per slot

        # Atomic indices (GIL makes int operations atomic in CPython)
        # Using separate variables instead of a shared structure for cache efficiency
        self._write_idx = 0  # Next slot to write
        self._read_idx = 0   # Next slot to read

        # Statistics (thread-safe via GIL for simple increments)
        self._stats = BufferStats(capacity=capacity)

    def try_write(self, timestamp: float, data: np.ndarray) -> bool:
        """
        Attempt to write audio data to the buffer.

        Non-blocking. Returns False if buffer is full (overrun).

        Args:
            timestamp: Capture timestamp (QPC or time.time())
            data: Audio data as numpy array, shape (frames,) for mono or (frames, channels) for stereo

        Returns:
            True if write succeeded, False if buffer full
        """
        # Calculate next write position
        next_write = (self._write_idx + 1) % self.capacity

        # Check if buffer is full (write would catch up to read)
        if next_write == self._read_idx:
            self._stats.overruns += 1
            return False

        # Get current write slot
        slot = self._write_idx

        # Handle different input shapes
        if data.ndim == 1:
            # Mono: reshape to (frames, 1) then broadcast
            frames = len(data)
            if frames > self.max_chunk_size:
                frames = self.max_chunk_size
            self._data[slot, :frames, 0] = data[:frames]
            self._data[slot, :frames, 1] = data[:frames]  # Duplicate to stereo
        else:
            # Stereo or multi-channel
            frames = min(data.shape[0], self.max_chunk_size)
            channels = min(data.shape[1], self.channels)
            self._data[slot, :frames, :channels] = data[:frames, :channels]

        self._timestamps[slot] = timestamp
        self._sizes[slot] = frames

        # Memory barrier not strictly needed due to GIL, but makes intent clear
        # Update write index AFTER data is written (release semantics)
        self._write_idx = next_write
        self._stats.writes += 1

        return True

    def try_read(self) -> Optional[Tuple[float, np.ndarray]]:
        """
        Attempt to read audio data from the buffer.

        Non-blocking. Returns None if buffer is empty (underrun).

        Returns:
            Tuple of (timestamp, audio_data) or None if empty.
            Audio data is a view into the buffer - copy if needed for long-term storage.
        """
        # Check if buffer is empty
        if self._read_idx == self._write_idx:
            self._stats.underruns += 1
            return None

        # Get current read slot
        slot = self._read_idx

        # Read data (returns a view for zero-copy)
        frames = self._sizes[slot]
        timestamp = self._timestamps[slot]
        data = self._data[slot, :frames, :].copy()  # Copy to allow slot reuse

        # Update read index AFTER data is read (acquire semantics)
        self._read_idx = (self._read_idx + 1) % self.capacity
        self._stats.reads += 1

        return (timestamp, data)

    def try_read_view(self) -> Optional[Tuple[float, np.ndarray, int]]:
        """
        Read without copying - returns view into buffer.

        WARNING: Caller must finish using the view before the buffer wraps around.
        Returns (timestamp, data_view, slot_index) - call release_slot(slot_index) when done.

        Returns:
            Tuple of (timestamp, audio_data_view, slot_index) or None if empty.
        """
        if self._read_idx == self._write_idx:
            self._stats.underruns += 1
            return None

        slot = self._read_idx
        frames = self._sizes[slot]
        timestamp = self._timestamps[slot]
        data_view = self._data[slot, :frames, :]

        return (timestamp, data_view, slot)

    def release_slot(self, slot: int):
        """Release a slot after using try_read_view."""
        if slot == self._read_idx:
            self._read_idx = (self._read_idx + 1) % self.capacity
            self._stats.reads += 1

    def peek(self) -> Optional[Tuple[float, np.ndarray]]:
        """
        Peek at next data without removing from buffer.

        Returns:
            Tuple of (timestamp, audio_data_copy) or None if empty.
        """
        if self._read_idx == self._write_idx:
            return None

        slot = self._read_idx
        frames = self._sizes[slot]
        timestamp = self._timestamps[slot]
        data = self._data[slot, :frames, :].copy()

        return (timestamp, data)

    def clear(self):
        """Clear the buffer (reset indices)."""
        self._read_idx = self._write_idx

    @property
    def available(self) -> int:
        """Number of chunks available to read."""
        write_idx = self._write_idx
        read_idx = self._read_idx
        if write_idx >= read_idx:
            return write_idx - read_idx
        return self.capacity - read_idx + write_idx

    @property
    def free_slots(self) -> int:
        """Number of free slots available for writing."""
        return self.capacity - 1 - self.available  # -1 to distinguish full from empty

    @property
    def is_empty(self) -> bool:
        """Check if buffer is empty."""
        return self._read_idx == self._write_idx

    @property
    def is_full(self) -> bool:
        """Check if buffer is full."""
        return (self._write_idx + 1) % self.capacity == self._read_idx

    @property
    def stats(self) -> BufferStats:
        """Get buffer statistics."""
        self._stats.current_fill = self.available
        return self._stats

    def reset_stats(self):
        """Reset statistics counters."""
        self._stats.reset()


class AudioChunkBuffer(SPSCRingBuffer):
    """
    Specialized ring buffer for audio chunks with extended metadata.

    Adds support for:
    - QPC timestamp frequency storage
    - Frame sequence numbers
    - Optional flags per chunk
    """

    def __init__(self, capacity: int = 16, max_chunk_size: int = 4096,
                 channels: int = 2, qpc_frequency: int = 0):
        """
        Initialize audio chunk buffer.

        Args:
            capacity: Number of audio chunk slots
            max_chunk_size: Maximum frames per chunk
            channels: Audio channels
            qpc_frequency: QPC timer frequency (0 if not using QPC)
        """
        super().__init__(capacity, max_chunk_size, channels)

        # Extended metadata
        self.qpc_frequency = qpc_frequency
        self._frame_numbers = np.zeros(capacity, dtype=np.uint64)
        self._flags = np.zeros(capacity, dtype=np.uint32)

        # Frame counter
        self._next_frame_number = 0

    def write_chunk(self, timestamp: float, data: np.ndarray,
                    flags: int = 0) -> bool:
        """
        Write audio chunk with extended metadata.

        Args:
            timestamp: Capture timestamp
            data: Audio data
            flags: Optional flags (e.g., beat detected, silence)

        Returns:
            True if write succeeded
        """
        next_write = (self._write_idx + 1) % self.capacity
        if next_write == self._read_idx:
            self._stats.overruns += 1
            return False

        slot = self._write_idx

        # Write data
        if data.ndim == 1:
            frames = min(len(data), self.max_chunk_size)
            self._data[slot, :frames, 0] = data[:frames]
            self._data[slot, :frames, 1] = data[:frames]
        else:
            frames = min(data.shape[0], self.max_chunk_size)
            channels = min(data.shape[1], self.channels)
            self._data[slot, :frames, :channels] = data[:frames, :channels]

        self._timestamps[slot] = timestamp
        self._sizes[slot] = frames
        self._frame_numbers[slot] = self._next_frame_number
        self._flags[slot] = flags

        self._next_frame_number += 1
        self._write_idx = next_write
        self._stats.writes += 1

        return True

    def read_chunk(self) -> Optional[Tuple[float, np.ndarray, int, int]]:
        """
        Read audio chunk with extended metadata.

        Returns:
            Tuple of (timestamp, audio_data, frame_number, flags) or None if empty.
        """
        if self._read_idx == self._write_idx:
            self._stats.underruns += 1
            return None

        slot = self._read_idx
        frames = self._sizes[slot]
        timestamp = self._timestamps[slot]
        data = self._data[slot, :frames, :].copy()
        frame_number = int(self._frame_numbers[slot])
        flags = int(self._flags[slot])

        self._read_idx = (self._read_idx + 1) % self.capacity
        self._stats.reads += 1

        return (timestamp, data, frame_number, flags)

    def timestamp_to_seconds(self, qpc_timestamp: float) -> float:
        """Convert QPC timestamp to seconds (if using QPC)."""
        if self.qpc_frequency > 0:
            return qpc_timestamp / self.qpc_frequency
        return qpc_timestamp


# Chunk flags
CHUNK_FLAG_NONE = 0x00
CHUNK_FLAG_BEAT = 0x01
CHUNK_FLAG_SILENCE = 0x02
CHUNK_FLAG_CLIPPING = 0x04


def create_audio_buffer(capacity: int = 16, max_frames: int = 2048,
                        channels: int = 2, use_extended: bool = False,
                        qpc_frequency: int = 0) -> SPSCRingBuffer:
    """
    Factory function to create appropriate ring buffer.

    Args:
        capacity: Number of chunk slots
        max_frames: Maximum frames per chunk
        channels: Audio channels
        use_extended: If True, use AudioChunkBuffer with metadata
        qpc_frequency: QPC frequency (0 to use time.time() timestamps)

    Returns:
        SPSCRingBuffer or AudioChunkBuffer instance
    """
    if use_extended:
        return AudioChunkBuffer(capacity, max_frames, channels, qpc_frequency)
    return SPSCRingBuffer(capacity, max_frames, channels)


if __name__ == "__main__":
    # Simple test
    import time

    print("Testing SPSCRingBuffer...")
    buffer = SPSCRingBuffer(capacity=4, max_chunk_size=256)

    # Test writes
    for i in range(5):
        data = np.random.randn(128, 2).astype(np.float32)
        success = buffer.try_write(time.time(), data)
        print(f"Write {i}: {'OK' if success else 'OVERRUN'}, available={buffer.available}")

    # Test reads
    for i in range(5):
        result = buffer.try_read()
        if result:
            ts, data = result
            print(f"Read {i}: shape={data.shape}, ts={ts:.6f}")
        else:
            print(f"Read {i}: UNDERRUN")

    print(f"\nStats: {buffer.stats}")

    # Test AudioChunkBuffer
    print("\nTesting AudioChunkBuffer...")
    chunk_buffer = AudioChunkBuffer(capacity=4, max_chunk_size=256, qpc_frequency=10000000)

    for i in range(3):
        data = np.random.randn(128, 2).astype(np.float32)
        chunk_buffer.write_chunk(time.time(), data, flags=CHUNK_FLAG_BEAT if i == 1 else 0)

    while not chunk_buffer.is_empty:
        result = chunk_buffer.read_chunk()
        if result:
            ts, data, frame_num, flags = result
            beat = " [BEAT]" if flags & CHUNK_FLAG_BEAT else ""
            print(f"Chunk {frame_num}: shape={data.shape}{beat}")

    print(f"Stats: {chunk_buffer.stats}")
