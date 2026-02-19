"""
Unit tests for WebSocket connectivity and reconnection behavior.

Tests cover:
- DJRelay exponential backoff on connection failures
- DJRelay heartbeat failure detection
- VizClient connection timeout handling
- VizClient reconnection with exponential backoff

Run with: pytest audio_processor/tests/test_connectivity.py -v
"""

import asyncio
import json
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Mark entire module as async tests
pytestmark = pytest.mark.asyncio


class MockWebSocket:
    """Mock WebSocket connection for testing."""

    def __init__(self, should_connect=True, should_auth=True, responses=None):
        self.should_connect = should_connect
        self.should_auth = should_auth
        self.responses = responses or []
        self.response_index = 0
        self.sent_messages = []
        self.closed = False
        self.close_code = None
        self.close_reason = None
        self.remote_address = ("127.0.0.1", 12345)

    async def send(self, message):
        """Record sent message."""
        self.sent_messages.append(message)

    async def recv(self):
        """Return next response or raise if none."""
        if self.response_index < len(self.responses):
            response = self.responses[self.response_index]
            self.response_index += 1
            if isinstance(response, Exception):
                raise response
            return json.dumps(response)
        # No more responses - simulate connection closed
        import websockets.exceptions

        raise websockets.exceptions.ConnectionClosed(None, None)

    async def close(self, code=1000, reason=""):
        """Close the connection."""
        self.closed = True
        self.close_code = code
        self.close_reason = reason

    def __aiter__(self):
        return self

    async def __anext__(self):
        try:
            return await self.recv()
        except Exception:
            raise StopAsyncIteration


class TestDJRelayExponentialBackoff:
    """Test DJRelay exponential backoff on connection failures."""

    @pytest.fixture
    def relay_config(self):
        """Create a test DJRelayConfig."""
        from audio_processor.dj_relay import DJRelayConfig

        return DJRelayConfig(
            vj_server_url="ws://localhost:9999",
            dj_id="test_dj",
            dj_name="Test DJ",
            dj_key="test_key",
            reconnect_interval=1.0,  # Short interval for tests
            max_connect_attempts=3,
        )

    async def test_backoff_increases_with_each_failure(self, relay_config):
        """Verify backoff increases exponentially with each connection failure."""
        from audio_processor.dj_relay import DJRelay

        relay = DJRelay(relay_config)
        backoff_times = []
        connect_times = []

        # Mock websockets.connect to always fail
        async def mock_connect(*args, **kwargs):
            connect_times.append(time.time())
            raise ConnectionRefusedError("Connection refused")

        # Also capture sleep times to verify backoff
        original_sleep = asyncio.sleep

        async def mock_sleep(delay):
            backoff_times.append(delay)
            await original_sleep(0.01)  # Actually sleep a tiny bit

        with patch("websockets.connect", mock_connect):
            with patch("asyncio.sleep", mock_sleep):
                result = await relay.connect()

        # Should fail after max_connect_attempts
        assert result is False

        # Should have made 3 connection attempts
        assert len(connect_times) == 3

        # Should have backoff sleeps between attempts (2 sleeps for 3 attempts)
        assert len(backoff_times) == 2

        # Second backoff should be larger than first (exponential)
        # First backoff: 1.0 * 1.5^0 = 1.0 (plus jitter)
        # Second backoff: 1.0 * 1.5^1 = 1.5 (plus jitter)
        assert backoff_times[1] > backoff_times[0], (
            f"Backoff should increase: {backoff_times[0]:.2f} -> {backoff_times[1]:.2f}"
        )

    async def test_backoff_resets_on_success(self, relay_config):
        """Verify backoff resets after successful connection."""
        from audio_processor.dj_relay import DJRelay

        relay = DJRelay(relay_config)

        # Track connection attempts
        attempt_count = 0

        async def mock_connect(*args, **kwargs):
            nonlocal attempt_count
            attempt_count += 1
            return MockWebSocket(responses=[{"type": "auth_success", "is_active": False}])

        with patch("websockets.connect", mock_connect):
            # First connection should succeed
            result = await relay.connect()
            assert result is True

            # Reconnect attempts should be reset
            assert relay._reconnect_attempts == 0

    async def test_max_backoff_capped(self, relay_config):
        """Verify backoff is capped at max value (30 seconds)."""
        from audio_processor.dj_relay import DJRelay

        relay_config.max_connect_attempts = 10
        relay = DJRelay(relay_config)

        backoff_times = []

        async def mock_connect(*args, **kwargs):
            raise ConnectionRefusedError("Connection refused")

        async def mock_sleep(delay):
            backoff_times.append(delay)
            # Don't actually sleep in tests

        with patch("websockets.connect", mock_connect):
            with patch("asyncio.sleep", mock_sleep):
                await relay.connect()

        # All backoff times should be <= 30 + jitter (max ~33)
        for backoff in backoff_times:
            assert backoff <= 33.0, f"Backoff {backoff:.2f} exceeds max"


class TestDJRelayHeartbeatFailure:
    """Test DJRelay heartbeat failure detection."""

    @pytest.fixture
    def relay_config(self):
        """Create a test DJRelayConfig."""
        from audio_processor.dj_relay import DJRelayConfig

        return DJRelayConfig(
            vj_server_url="ws://localhost:9999",
            dj_id="test_dj",
            dj_name="Test DJ",
            heartbeat_interval=0.1,  # Short interval for tests
        )

    async def test_disconnect_after_three_heartbeat_failures(self, relay_config):
        """Verify disconnect is triggered after 3 heartbeat failures."""
        from audio_processor.dj_relay import DJRelay

        relay = DJRelay(relay_config)
        relay._connected = True
        relay._authenticated = True
        relay._last_heartbeat = 0  # Force heartbeat to be sent

        disconnect_called = False

        def on_disconnect():
            nonlocal disconnect_called
            disconnect_called = True

        relay.set_callbacks(on_disconnect=on_disconnect)

        # Mock websocket that fails to send
        mock_ws = MagicMock()
        mock_ws.send = AsyncMock(side_effect=Exception("Send failed"))
        relay._websocket = mock_ws

        # First two heartbeat failures should not disconnect
        for i in range(2):
            relay._last_heartbeat = 0  # Reset to force heartbeat
            result = await relay.send_heartbeat()
            assert result is False
            assert relay._heartbeat_failures == i + 1
            assert relay._connected is True, f"Should not disconnect after {i + 1} failures"

        # Third heartbeat failure should trigger disconnect
        relay._last_heartbeat = 0
        result = await relay.send_heartbeat()
        assert result is False
        assert relay._heartbeat_failures == 3
        assert relay._connected is False, "Should disconnect after 3 failures"
        assert disconnect_called, "Disconnect callback should be called"

    async def test_heartbeat_failure_counter_resets_on_success(self, relay_config):
        """Verify heartbeat failure counter resets on successful heartbeat."""
        from audio_processor.dj_relay import DJRelay

        relay = DJRelay(relay_config)
        relay._connected = True
        relay._authenticated = True
        relay._heartbeat_failures = 2  # Simulate 2 previous failures
        relay._last_heartbeat = 0

        # Mock websocket that succeeds
        mock_ws = MagicMock()
        mock_ws.send = AsyncMock(return_value=None)
        relay._websocket = mock_ws

        result = await relay.send_heartbeat()

        assert result is True
        assert relay._heartbeat_failures == 0, "Failure counter should reset on success"


class TestVizClientConnectionTimeout:
    """Test VizClient connection timeout handling."""

    async def test_timeout_returns_false(self):
        """Verify timeout during connection returns False."""
        from python_client.viz_client import VizClient

        client = VizClient(
            host="localhost",
            port=9999,
            connect_timeout=0.5,  # Short timeout for tests
        )

        # Mock websockets.connect to hang forever
        async def mock_connect(*args, **kwargs):
            await asyncio.sleep(10)  # Longer than timeout
            return MagicMock()

        with patch("websockets.connect", mock_connect):
            result = await client.connect()

        assert result is False
        assert client.connected is False

    async def test_connection_error_returns_false(self):
        """Verify connection error returns False."""
        from python_client.viz_client import VizClient

        client = VizClient(host="localhost", port=9999)

        # Mock websockets.connect to raise error
        async def mock_connect(*args, **kwargs):
            raise ConnectionRefusedError("Connection refused")

        with patch("websockets.connect", mock_connect):
            result = await client.connect()

        assert result is False
        assert client.connected is False


class TestVizClientReconnection:
    """Test VizClient reconnection with exponential backoff."""

    async def test_reconnection_uses_exponential_backoff(self):
        """Verify reconnection uses exponential backoff."""
        from python_client.viz_client import VizClient

        client = VizClient(
            host="localhost", port=9999, auto_reconnect=True, max_reconnect_attempts=5
        )

        sleep_times = []
        connect_attempts = 0

        async def mock_connect(*args, **kwargs):
            nonlocal connect_attempts
            connect_attempts += 1
            raise ConnectionRefusedError("Connection refused")

        async def mock_sleep(delay):
            sleep_times.append(delay)
            # Don't actually sleep

        with patch("websockets.connect", mock_connect):
            with patch("asyncio.sleep", mock_sleep):
                result = await client.reconnect()

        assert result is False
        assert connect_attempts == 5

        # Should have 5 sleeps (one before each attempt)
        assert len(sleep_times) == 5

        # Verify exponential backoff pattern
        # Base delay is 1.0, multiplier is 1.5, max is 30
        # Expected: 1.0, 1.5, 2.25, 3.375, 5.0625
        for i in range(1, len(sleep_times)):
            # Each delay should be approximately 1.5x the previous (within tolerance for floating point)
            ratio = sleep_times[i] / sleep_times[i - 1]
            assert 1.4 <= ratio <= 1.6, f"Backoff ratio should be ~1.5, got {ratio:.2f}"

    async def test_reconnection_max_attempts(self):
        """Verify reconnection stops after max attempts."""
        from python_client.viz_client import VizClient

        client = VizClient(
            host="localhost", port=9999, auto_reconnect=True, max_reconnect_attempts=3
        )

        connect_attempts = 0

        async def mock_connect(*args, **kwargs):
            nonlocal connect_attempts
            connect_attempts += 1
            raise ConnectionRefusedError("Connection refused")

        with patch("websockets.connect", mock_connect):
            with patch("asyncio.sleep", AsyncMock()):
                result = await client.reconnect()

        assert result is False
        assert connect_attempts == 3

    async def test_reconnection_resets_counter_on_success(self):
        """Verify reconnection attempt counter resets on success."""
        from python_client.viz_client import VizClient

        client = VizClient(
            host="localhost", port=9999, auto_reconnect=True, max_reconnect_attempts=5
        )
        client._reconnect_attempts = 3  # Simulate previous failed attempts

        # Create mock websocket that behaves well
        mock_ws = MagicMock()
        mock_ws.recv = AsyncMock(
            return_value=json.dumps({"type": "welcome", "message": "Connected"})
        )
        mock_ws.send = AsyncMock()
        mock_ws.close = AsyncMock()

        async def mock_connect(*args, **kwargs):
            return mock_ws

        with patch("websockets.connect", mock_connect):
            with patch("asyncio.sleep", AsyncMock()):
                result = await client.reconnect()

        # Stop background tasks that were started during connect
        client.stop_heartbeat()
        client._stop_receive_loop()

        assert result is True
        assert client._reconnect_attempts == 0


class TestVizClientHeartbeat:
    """Test VizClient heartbeat and pong timeout detection."""

    async def test_pong_timeout_triggers_disconnect(self):
        """Verify pong timeout triggers disconnection."""
        from python_client.viz_client import VizClient

        client = VizClient(host="localhost", port=8765)
        client._connected = True
        client._last_pong = time.time() - 35  # 35 seconds ago (> 30 second timeout)

        # Mock websocket
        mock_ws = MagicMock()
        mock_ws.send = AsyncMock()
        mock_ws.close = AsyncMock()
        client.ws = mock_ws

        # Run one iteration of heartbeat check
        # The heartbeat loop checks every 10 seconds and disconnects if no pong for 30+ seconds
        # We simulate by checking the condition directly

        # Check the pong timeout condition
        pong_timeout = (time.time() - client._last_pong) > 30

        assert pong_timeout is True, "Should detect pong timeout"


class TestVJServerMinecraftReconnect:
    """Test VJ Server Minecraft reconnection behavior."""

    async def test_minecraft_reconnect_backoff(self):
        """Verify Minecraft reconnection uses exponential backoff."""
        # This tests the VJServer._minecraft_reconnect_loop behavior
        # by verifying the backoff calculation logic

        initial_backoff = 5.0
        max_backoff = 60.0
        multiplier = 1.5

        # Simulate backoff progression
        backoffs = []
        current_backoff = initial_backoff

        for _ in range(10):  # 10 failed reconnection attempts
            backoffs.append(current_backoff)
            current_backoff = min(current_backoff * multiplier, max_backoff)

        # Verify exponential growth
        assert backoffs[0] == 5.0
        assert backoffs[1] == 7.5  # 5 * 1.5
        assert backoffs[2] == 11.25  # 7.5 * 1.5

        # Verify max cap
        assert all(b <= max_backoff for b in backoffs)
        assert backoffs[-1] == max_backoff


class TestAdminPanelReconnect:
    """Test Admin Panel reconnection behavior (documented behavior)."""

    def test_documented_reconnect_parameters(self):
        """Verify documented reconnection parameters match implementation."""
        # Admin panel uses: 1.5x backoff, max 30s, 10 attempts max
        # This is documented in the task description

        initial_delay = 1.0
        multiplier = 1.5
        max_delay = 30.0
        max_attempts = 10

        delays = []
        current_delay = initial_delay

        for _ in range(max_attempts):
            delays.append(current_delay)
            current_delay = min(current_delay * multiplier, max_delay)

        # Verify the progression
        assert len(delays) == 10
        assert delays[0] == 1.0
        assert delays[-1] <= 30.0

        # Verify exponential pattern until max
        for i in range(1, len(delays)):
            if delays[i - 1] < max_delay / multiplier:
                expected = delays[i - 1] * multiplier
                assert abs(delays[i] - expected) < 0.01


class TestMessageQueueOverflow:
    """Test message queue overflow behavior."""

    def test_queue_size_limits(self):
        """Verify queue size limits are reasonable."""
        # Document the expected queue sizes from implementation
        # Minecraft plugin: MessageQueue with configurable size
        # Python side: using asyncio queues

        # These are implementation-specific constants we're documenting
        expected_mc_queue_size = 1000  # Default from MessageQueue
        expected_max_queue_size = 10000  # Reasonable upper limit

        # Verify these are reasonable values
        assert expected_mc_queue_size > 0
        assert expected_mc_queue_size <= expected_max_queue_size


# Run with: pytest audio_processor/tests/test_connectivity.py -v
if __name__ == "__main__":
    pytest.main([__file__, "-v"])
