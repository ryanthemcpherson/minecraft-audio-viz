"""
Tests for the lock-free SPSC ring buffer.
"""

import threading
import time

import numpy as np
import pytest
from audio_processor.ringbuffer import (
    CHUNK_FLAG_BEAT,
    AudioChunkBuffer,
    SPSCRingBuffer,
)


class TestSPSCRingBuffer:
    """Tests for the basic SPSC ring buffer."""

    def test_create_buffer(self):
        """Test buffer creation with default parameters."""
        buffer = SPSCRingBuffer()
        assert buffer.capacity == 16
        assert buffer.max_chunk_size == 4096
        assert buffer.channels == 2
        assert buffer.is_empty
        assert not buffer.is_full

    def test_create_buffer_custom(self):
        """Test buffer creation with custom parameters."""
        buffer = SPSCRingBuffer(capacity=8, max_chunk_size=1024, channels=1)
        assert buffer.capacity == 8
        assert buffer.max_chunk_size == 1024
        assert buffer.channels == 1

    def test_write_read_single(self):
        """Test writing and reading a single chunk."""
        buffer = SPSCRingBuffer(capacity=4, max_chunk_size=128)

        # Write data
        data = np.random.randn(64, 2).astype(np.float32)
        timestamp = time.time()
        assert buffer.try_write(timestamp, data)

        # Read data
        result = buffer.try_read()
        assert result is not None
        ts, audio = result
        assert ts == timestamp
        assert audio.shape == (64, 2)
        np.testing.assert_array_almost_equal(audio, data, decimal=5)

    def test_write_read_multiple(self):
        """Test writing and reading multiple chunks."""
        buffer = SPSCRingBuffer(capacity=4, max_chunk_size=128)

        # Write 3 chunks
        for i in range(3):
            data = np.full((32, 2), float(i), dtype=np.float32)
            buffer.try_write(float(i), data)

        assert buffer.available == 3

        # Read and verify
        for i in range(3):
            result = buffer.try_read()
            assert result is not None
            ts, audio = result
            assert ts == float(i)
            assert np.all(audio == float(i))

        assert buffer.is_empty

    def test_overrun(self):
        """Test buffer overrun (full buffer)."""
        buffer = SPSCRingBuffer(capacity=4, max_chunk_size=64)

        # Fill buffer (capacity - 1 = 3 items before full)
        for i in range(3):
            data = np.zeros((32, 2), dtype=np.float32)
            assert buffer.try_write(float(i), data)

        # Next write should fail (overrun)
        data = np.zeros((32, 2), dtype=np.float32)
        assert not buffer.try_write(0.0, data)

        # Check stats
        assert buffer.stats.overruns == 1

    def test_underrun(self):
        """Test buffer underrun (empty buffer)."""
        buffer = SPSCRingBuffer(capacity=4)

        # Read from empty buffer
        result = buffer.try_read()
        assert result is None
        assert buffer.stats.underruns == 1

    def test_mono_input(self):
        """Test writing mono data (should be duplicated to stereo)."""
        buffer = SPSCRingBuffer(capacity=4, max_chunk_size=128, channels=2)

        # Write mono data
        mono_data = np.ones(64, dtype=np.float32) * 0.5
        buffer.try_write(0.0, mono_data)

        # Read and verify stereo
        result = buffer.try_read()
        assert result is not None
        ts, audio = result
        assert audio.shape == (64, 2)
        assert np.all(audio[:, 0] == 0.5)
        assert np.all(audio[:, 1] == 0.5)

    def test_peek(self):
        """Test peeking without consuming."""
        buffer = SPSCRingBuffer(capacity=4, max_chunk_size=64)

        data = np.ones((32, 2), dtype=np.float32)
        buffer.try_write(1.0, data)

        # Peek should return data
        result = buffer.peek()
        assert result is not None
        assert buffer.available == 1  # Still available

        # Read should also return data
        result = buffer.try_read()
        assert result is not None
        assert buffer.is_empty

    def test_clear(self):
        """Test clearing the buffer."""
        buffer = SPSCRingBuffer(capacity=4, max_chunk_size=64)

        # Add some data
        for i in range(3):
            buffer.try_write(float(i), np.zeros((32, 2), dtype=np.float32))

        assert buffer.available == 3

        # Clear
        buffer.clear()
        assert buffer.is_empty
        assert buffer.available == 0

    def test_stats(self):
        """Test statistics tracking."""
        buffer = SPSCRingBuffer(capacity=4, max_chunk_size=64)

        # Initial stats
        stats = buffer.stats
        assert stats.writes == 0
        assert stats.reads == 0
        assert stats.capacity == 4

        # Write and read
        buffer.try_write(0.0, np.zeros((32, 2), dtype=np.float32))
        buffer.try_read()

        stats = buffer.stats
        assert stats.writes == 1
        assert stats.reads == 1

        # Reset stats
        buffer.reset_stats()
        stats = buffer.stats
        assert stats.writes == 0
        assert stats.reads == 0


class TestAudioChunkBuffer:
    """Tests for the extended audio chunk buffer."""

    def test_create_with_qpc(self):
        """Test creation with QPC frequency."""
        buffer = AudioChunkBuffer(capacity=8, qpc_frequency=10000000)
        assert buffer.qpc_frequency == 10000000

    def test_write_read_with_flags(self):
        """Test writing and reading with flags."""
        buffer = AudioChunkBuffer(capacity=4, max_chunk_size=64)

        # Write with beat flag
        data = np.random.randn(32, 2).astype(np.float32)
        buffer.write_chunk(1.0, data, flags=CHUNK_FLAG_BEAT)

        # Read and verify
        result = buffer.read_chunk()
        assert result is not None
        ts, audio, frame_num, flags = result
        assert ts == 1.0
        assert frame_num == 0
        assert flags == CHUNK_FLAG_BEAT

    def test_frame_numbers(self):
        """Test frame number sequencing."""
        buffer = AudioChunkBuffer(capacity=8, max_chunk_size=64)

        # Write multiple chunks
        for i in range(5):
            buffer.write_chunk(float(i), np.zeros((32, 2), dtype=np.float32))

        # Verify frame numbers
        for expected_frame in range(5):
            result = buffer.read_chunk()
            assert result is not None
            ts, audio, frame_num, flags = result
            assert frame_num == expected_frame

    def test_timestamp_conversion(self):
        """Test QPC timestamp to seconds conversion."""
        buffer = AudioChunkBuffer(qpc_frequency=10000000)  # 10 MHz

        # 10 million ticks = 1 second
        seconds = buffer.timestamp_to_seconds(10000000)
        assert seconds == 1.0

        # 5 million ticks = 0.5 seconds
        seconds = buffer.timestamp_to_seconds(5000000)
        assert seconds == 0.5


class TestConcurrency:
    """Tests for concurrent access (producer-consumer)."""

    def test_producer_consumer(self):
        """Test single producer, single consumer scenario."""
        buffer = SPSCRingBuffer(capacity=16, max_chunk_size=256)
        num_chunks = 100
        produced = []
        consumed = []
        done = threading.Event()

        def producer():
            for i in range(num_chunks):
                data = np.full((64, 2), float(i), dtype=np.float32)
                while not buffer.try_write(float(i), data):
                    time.sleep(0.0001)  # Brief yield on full
                produced.append(i)
            done.set()

        def consumer():
            while not done.is_set() or not buffer.is_empty:
                result = buffer.try_read()
                if result:
                    ts, audio = result
                    consumed.append(int(ts))
                else:
                    time.sleep(0.0001)  # Brief yield on empty

        # Start threads
        prod_thread = threading.Thread(target=producer)
        cons_thread = threading.Thread(target=consumer)

        prod_thread.start()
        cons_thread.start()

        prod_thread.join(timeout=5.0)
        cons_thread.join(timeout=5.0)

        # Verify
        assert len(produced) == num_chunks
        assert len(consumed) == num_chunks
        assert produced == consumed  # Order preserved

    def test_high_throughput(self):
        """Test high-throughput scenario."""
        buffer = SPSCRingBuffer(capacity=32, max_chunk_size=512)
        num_chunks = 1000
        done = threading.Event()
        write_count = [0]
        read_count = [0]

        def producer():
            data = np.zeros((128, 2), dtype=np.float32)
            for i in range(num_chunks):
                if buffer.try_write(float(i), data):
                    write_count[0] += 1
            done.set()

        def consumer():
            while not done.is_set() or not buffer.is_empty:
                if buffer.try_read():
                    read_count[0] += 1

        prod_thread = threading.Thread(target=producer)
        cons_thread = threading.Thread(target=consumer)

        start = time.time()
        prod_thread.start()
        cons_thread.start()

        prod_thread.join(timeout=5.0)
        cons_thread.join(timeout=5.0)
        elapsed = time.time() - start

        # Should complete quickly
        assert elapsed < 2.0

        # Check stats
        stats = buffer.stats
        # All chunks should be accounted for (some may have been dropped)
        assert stats.writes + stats.overruns == num_chunks


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
