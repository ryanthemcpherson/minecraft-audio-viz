"""Tests for VizClient WebSocket client."""

import asyncio
import json
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from python_client.viz_client import VizClient


class TestVizClientInit:
    """Tests for VizClient initialization."""

    def test_default_init(self):
        """Test default initialization values."""
        client = VizClient()
        assert client.host == "localhost"
        assert client.port == 8765
        assert client.uri == "ws://localhost:8765"
        assert client.connect_timeout == 10.0
        assert client.auto_reconnect is False
        assert client.max_reconnect_attempts == 10
        assert client._reconnect_attempts == 0
        assert client._last_pong == 0

    def test_custom_init(self):
        """Test custom initialization values."""
        client = VizClient(
            host="192.168.1.100",
            port=9000,
            connect_timeout=5.0,
            auto_reconnect=True,
            max_reconnect_attempts=5,
        )
        assert client.host == "192.168.1.100"
        assert client.port == 9000
        assert client.uri == "ws://192.168.1.100:9000"
        assert client.connect_timeout == 5.0
        assert client.auto_reconnect is True
        assert client.max_reconnect_attempts == 5


class TestVizClientConnect:
    """Tests for VizClient connection functionality."""

    @pytest.mark.asyncio
    async def test_connect_timeout(self):
        """Test connection timeout handling."""
        client = VizClient(connect_timeout=0.1)

        with patch("python_client.viz_client.websockets.connect") as mock_connect:
            # Simulate connection that takes longer than timeout
            async def slow_connect(uri):
                await asyncio.sleep(1.0)
                return MagicMock()

            mock_connect.side_effect = slow_connect

            result = await client.connect()
            assert result is False
            assert client._connected is False

    @pytest.mark.asyncio
    async def test_connect_success(self):
        """Test successful connection."""
        client = VizClient(connect_timeout=5.0)

        mock_ws = AsyncMock()
        mock_ws.recv = AsyncMock(return_value=json.dumps({"message": "Welcome"}))
        mock_ws.close = AsyncMock()

        async def mock_connect(uri):
            return mock_ws

        with patch("python_client.viz_client.websockets.connect", side_effect=mock_connect):
            result = await client.connect()
            assert result is True
            assert client._connected is True
            assert client._reconnect_attempts == 0
            assert client._last_pong > 0

            # Clean up
            await client.disconnect()


class TestVizClientHeartbeat:
    """Tests for VizClient heartbeat functionality."""

    @pytest.mark.asyncio
    async def test_start_heartbeat(self):
        """Test starting heartbeat task."""
        client = VizClient()
        client._connected = True
        client.ws = AsyncMock()

        client.start_heartbeat()
        assert client._heartbeat_task is not None
        assert client._last_pong > 0

        # Clean up
        client.stop_heartbeat()

    @pytest.mark.asyncio
    async def test_stop_heartbeat(self):
        """Test stopping heartbeat task."""
        client = VizClient()
        client._connected = True
        client.ws = AsyncMock()

        client.start_heartbeat()
        assert client._heartbeat_task is not None

        client.stop_heartbeat()
        assert client._heartbeat_task is None


class TestVizClientMessageHandlers:
    """Tests for VizClient message handler registration."""

    def test_register_handler(self):
        """Test registering a message handler."""
        client = VizClient()

        def my_handler(data):
            pass

        client.on("test_message", my_handler)
        assert "test_message" in client._message_handlers
        assert client._message_handlers["test_message"] == my_handler

    def test_register_async_handler(self):
        """Test registering an async message handler."""
        client = VizClient()

        async def my_async_handler(data):
            pass

        client.on("async_message", my_async_handler)
        assert "async_message" in client._message_handlers
        assert client._message_handlers["async_message"] == my_async_handler


class TestVizClientReconnect:
    """Tests for VizClient reconnection functionality."""

    @pytest.mark.asyncio
    async def test_reconnect_success_first_attempt(self):
        """Test successful reconnection on first attempt."""
        client = VizClient(auto_reconnect=True, max_reconnect_attempts=3)
        client._reconnect_attempts = 0

        mock_ws = AsyncMock()
        mock_ws.recv = AsyncMock(return_value=json.dumps({"message": "Welcome"}))
        mock_ws.close = AsyncMock()

        async def mock_connect(uri):
            return mock_ws

        with patch("python_client.viz_client.websockets.connect", side_effect=mock_connect):
            result = await client.reconnect()
            assert result is True
            assert client._connected is True

            # Clean up
            await client.disconnect()

    @pytest.mark.asyncio
    async def test_reconnect_exhausted_attempts(self):
        """Test reconnection failure after exhausting attempts."""
        client = VizClient(
            connect_timeout=0.01,
            auto_reconnect=True,
            max_reconnect_attempts=2
        )
        client._reconnect_attempts = 0

        with patch("python_client.viz_client.websockets.connect") as mock_connect:
            # Always fail to connect
            async def failing_connect(uri):
                await asyncio.sleep(0.1)  # Longer than timeout
                return MagicMock()

            mock_connect.side_effect = failing_connect

            result = await client.reconnect()
            assert result is False
            assert client._reconnect_attempts == 2

    @pytest.mark.asyncio
    async def test_exponential_backoff(self):
        """Test that reconnection uses exponential backoff."""
        client = VizClient(
            connect_timeout=0.01,
            auto_reconnect=True,
            max_reconnect_attempts=3
        )

        delays = []

        original_sleep = asyncio.sleep

        async def mock_sleep(delay):
            delays.append(delay)
            # Don't actually sleep in tests
            return

        with patch("python_client.viz_client.asyncio.sleep", side_effect=mock_sleep):
            with patch("python_client.viz_client.websockets.connect") as mock_connect:
                async def timeout_connect(uri):
                    raise asyncio.TimeoutError()

                mock_connect.side_effect = timeout_connect

                await client.reconnect()

        # Verify exponential backoff (1.0, 1.5, 2.25)
        assert len(delays) == 3
        assert delays[0] == pytest.approx(1.0, rel=0.01)
        assert delays[1] == pytest.approx(1.5, rel=0.01)
        assert delays[2] == pytest.approx(2.25, rel=0.01)


class TestVizClientConnectedProperty:
    """Tests for VizClient connected property."""

    def test_connected_property(self):
        """Test connected property."""
        client = VizClient()
        assert client.connected is False

        client._connected = True
        assert client.connected is True
