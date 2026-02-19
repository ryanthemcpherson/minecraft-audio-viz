"""
AudioViz Python Client
Connects to Minecraft plugin via WebSocket to control visualizations.
"""

import asyncio
import json
import logging
import time
from typing import Any, Callable, Optional

import websockets
from websockets.client import WebSocketClientProtocol

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class VizClient:
    """WebSocket client for AudioViz Minecraft plugin."""

    def __init__(
        self,
        host: str = "localhost",
        port: int = 8765,
        connect_timeout: float = 10.0,
        auto_reconnect: bool = False,
        max_reconnect_attempts: int = 10,
        enable_heartbeat: bool = False,  # Enable background heartbeat (requires receive loop)
    ):
        self.host = host
        self.port = port
        self.uri = f"ws://{host}:{port}"
        self.ws: Optional[WebSocketClientProtocol] = None
        self._connected = False
        self._message_handlers: dict[str, Callable] = {}

        # Connection timeout
        self.connect_timeout = connect_timeout

        # Reconnection settings
        self.auto_reconnect = auto_reconnect
        self.max_reconnect_attempts = max_reconnect_attempts
        self._reconnect_attempts = 0

        # Heartbeat settings
        self._enable_heartbeat = enable_heartbeat
        self._heartbeat_task: Optional[asyncio.Task] = None
        self._receive_task: Optional[asyncio.Task] = None
        self._last_pong: float = 0

        # Response queue for request/response pattern (only used when heartbeat enabled)
        self._pending_responses: asyncio.Queue = asyncio.Queue()
        self._use_receive_loop: bool = False  # Set to True when receive loop is started

    async def connect(self) -> bool:
        """Connect to the AudioViz WebSocket server."""
        try:
            self.ws = await asyncio.wait_for(
                websockets.connect(self.uri), timeout=self.connect_timeout
            )
            self._connected = True

            # Wait for welcome message with timeout
            response = await asyncio.wait_for(self.ws.recv(), timeout=self.connect_timeout)
            data = json.loads(response)
            logger.info(f"Connected to AudioViz: {data.get('message', 'OK')}")

            # Reset reconnection counter on successful connection
            self._reconnect_attempts = 0

            # Start background tasks only if heartbeat is enabled
            if self._enable_heartbeat:
                self._last_pong = time.time()
                self._use_receive_loop = True
                self._receive_task = asyncio.create_task(self._receive_loop())
                self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())

            return True

        except asyncio.TimeoutError:
            logger.error(f"Connection timed out to {self.uri}")
            return False

        except Exception as e:
            logger.error(f"Failed to connect: {e}")
            return False

    async def disconnect(self):
        """Disconnect from the server."""
        # Stop background tasks
        self.stop_heartbeat()
        self._stop_receive_loop()

        if self.ws:
            await self.ws.close()
            self._connected = False
            logger.info("Disconnected from AudioViz")

    async def _heartbeat_loop(self):
        """Background task that sends heartbeat pings and monitors pong responses."""
        try:
            while self._connected and self.ws:
                await asyncio.sleep(10)
                if not self._connected:
                    break

                # Check if we've received a pong recently (within 30 seconds)
                if self._last_pong > 0 and (time.time() - self._last_pong) > 30:
                    logger.warning("Pong timeout detected - no response for 30+ seconds")
                    self._connected = False
                    if self.auto_reconnect:
                        asyncio.create_task(self.reconnect())
                    break

                # Send heartbeat ping
                try:
                    if self.ws:
                        await self.ws.send(json.dumps({"type": "ping"}))
                except Exception as e:
                    logger.warning(f"Heartbeat ping failed: {e}")
                    self._connected = False
                    if self.auto_reconnect:
                        asyncio.create_task(self.reconnect())
                    break
        except asyncio.CancelledError:
            pass  # Task cancelled during shutdown

    async def _receive_loop(self):
        """Background task that receives and routes incoming messages."""
        try:
            while self._connected and self.ws:
                try:
                    message = await self.ws.recv()
                    data = json.loads(message)
                    msg_type = data.get("type")

                    # Handle ping messages - respond with pong
                    if msg_type == "ping":
                        try:
                            await self.ws.send(json.dumps({"type": "pong"}))
                        except Exception as e:
                            logger.warning(f"Failed to send pong: {e}")
                        continue

                    # Handle pong messages for heartbeat
                    if msg_type == "pong":
                        self._last_pong = time.time()
                        continue

                    # Queue response messages for send() to pick up
                    # (any message that's a response to a request)
                    if msg_type in (
                        "zones",
                        "zone",
                        "pool_initialized",
                        "batch_updated",
                        "entity_updated",
                        "visibility_updated",
                        "zone_cleaned",
                        "error",
                        "connected",
                    ):
                        await self._pending_responses.put(data)
                        continue

                    # Route to registered handlers
                    if msg_type in self._message_handlers:
                        handler = self._message_handlers[msg_type]
                        try:
                            if asyncio.iscoroutinefunction(handler):
                                await handler(data)
                            else:
                                handler(data)
                        except Exception as e:
                            logger.error(f"Handler error for {msg_type}: {e}")

                except websockets.exceptions.ConnectionClosed:
                    logger.warning("Connection closed by server")
                    self._connected = False
                    if self.auto_reconnect:
                        asyncio.create_task(self.reconnect())
                    break
                except json.JSONDecodeError:
                    logger.warning("Received invalid JSON message")
                except Exception as e:
                    if self._connected:
                        logger.error(f"Receive loop error: {e}")
                        # If we get recv conflicts, connection is broken - trigger reconnect
                        if "recv" in str(e) and "already running" in str(e):
                            self._connected = False
                            if self.auto_reconnect:
                                asyncio.create_task(self.reconnect())
                            break
        except asyncio.CancelledError:
            pass  # Task cancelled during shutdown

    def on(self, message_type: str, callback: Callable[[dict], Any]):
        """Register a handler for a specific message type.

        Args:
            message_type: The type of message to handle (e.g., "audio_data", "zone_update")
            callback: Function to call when message is received. Can be sync or async.
        """
        self._message_handlers[message_type] = callback

    def start_heartbeat(self):
        """Start the heartbeat task manually."""
        if self._heartbeat_task is None or self._heartbeat_task.done():
            self._last_pong = time.time()
            self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())

    def stop_heartbeat(self):
        """Stop the heartbeat task."""
        if self._heartbeat_task and not self._heartbeat_task.done():
            self._heartbeat_task.cancel()
            self._heartbeat_task = None

    def _stop_receive_loop(self):
        """Stop the receive loop task."""
        if self._receive_task and not self._receive_task.done():
            self._receive_task.cancel()
            self._receive_task = None
        self._use_receive_loop = False
        # Clear pending responses queue
        while not self._pending_responses.empty():
            try:
                self._pending_responses.get_nowait()
            except asyncio.QueueEmpty:
                break

    async def reconnect(self) -> bool:
        """Attempt to reconnect with exponential backoff.

        Returns:
            True if reconnection succeeded, False if all attempts exhausted.
        """
        # Stop existing tasks first
        self.stop_heartbeat()
        self._stop_receive_loop()

        if self.ws:
            try:
                await self.ws.close()
            except Exception:
                pass
            self.ws = None

        base_delay = 1.0
        max_delay = 30.0

        while self._reconnect_attempts < self.max_reconnect_attempts:
            self._reconnect_attempts += 1
            delay = min(base_delay * (1.5 ** (self._reconnect_attempts - 1)), max_delay)

            logger.info(
                f"Reconnecting in {delay:.1f}s "
                f"(attempt {self._reconnect_attempts}/{self.max_reconnect_attempts})"
            )
            await asyncio.sleep(delay)

            if await self.connect():
                logger.info("Reconnection successful")
                return True

        logger.error(f"Reconnection failed after {self.max_reconnect_attempts} attempts")
        return False

    async def send(self, message: dict) -> Optional[dict]:
        """Send a message and wait for response."""
        if not self.ws or not self._connected:
            logger.error("Not connected")
            return None

        try:
            await self.ws.send(json.dumps(message))

            # If receive loop is running, get response from queue
            if self._use_receive_loop:
                try:
                    response = await asyncio.wait_for(
                        self._pending_responses.get(), timeout=self.connect_timeout
                    )
                    return response
                except asyncio.TimeoutError:
                    logger.error("Timeout waiting for response")
                    return None
            else:
                # Fallback for when receive loop isn't running
                response = await self.ws.recv()
                return json.loads(response)

        except websockets.exceptions.ConnectionClosed as e:
            logger.error(f"Connection closed: {e}")
            self._connected = False
            return None
        except Exception as e:
            logger.error(f"Send error: {e}")
            self._connected = False
            return None

    async def ping(self) -> bool:
        """Ping the server."""
        response = await self.send({"type": "ping"})
        return response is not None and response.get("type") == "pong"

    async def get_zones(self) -> list[dict]:
        """Get all visualization zones."""
        response = await self.send({"type": "get_zones"})
        if response and response.get("type") == "zones":
            return response.get("zones", [])
        return []

    async def get_zone(self, zone_name: str) -> Optional[dict]:
        """Get details for a specific zone."""
        response = await self.send({"type": "get_zone", "zone": zone_name})
        if response and response.get("type") == "zone":
            return response.get("zone")
        return None

    async def init_pool(self, zone_name: str, count: int = 16, material: str = "GLOWSTONE") -> bool:
        """Initialize entity pool for a zone."""
        response = await self.send(
            {"type": "init_pool", "zone": zone_name, "count": count, "material": material}
        )
        return response is not None and response.get("type") == "pool_initialized"

    async def batch_update(
        self, zone_name: str, entities: list[dict], particles: list[dict] = None
    ) -> bool:
        """
        Send batch entity updates (waits for response).
        For real-time visualization, use batch_update_fast() instead.
        """
        message = {"type": "batch_update", "zone": zone_name, "entities": entities}
        if particles:
            message["particles"] = particles

        response = await self.send(message)
        return response is not None and response.get("type") == "batch_updated"

    async def batch_update_fast(
        self, zone_name: str, entities: list[dict], particles: list[dict] = None, audio: dict = None
    ):
        """
        Fire-and-forget batch update for real-time visualization.
        Does not wait for response - much faster for high frame rates.

        Args:
            zone_name: Target zone
            entities: List of entity updates
            particles: Optional particle updates
            audio: Optional audio data dict with keys:
                   bands (list[float]), amplitude (float),
                   is_beat (bool), beat_intensity (float)
        """
        if not self.ws or not self._connected:
            return

        message = {"type": "batch_update", "zone": zone_name, "entities": entities}
        if particles:
            message["particles"] = particles
        if audio:
            # Include audio data for redstone sensors
            message["bands"] = audio.get("bands", [0.0] * 5)
            message["amplitude"] = audio.get("amplitude", 0.0)
            message["is_beat"] = audio.get("is_beat", False)
            message["beat_intensity"] = audio.get("beat_intensity", 0.0)
            # Debug: log first time we send audio
            if not hasattr(self, "_audio_logged"):
                self._audio_logged = True
                logger.info(f"Sending audio data: bands={message['bands'][:2]}...")

        try:
            await self.ws.send(json.dumps(message))
        except websockets.exceptions.ConnectionClosed:
            # Connection lost - mark as disconnected for caller to handle
            self._connected = False
        except Exception as e:
            # Log unexpected errors but don't spam
            if not hasattr(self, "_last_fast_error") or (time.time() - self._last_fast_error) > 5:
                logger.warning(f"Fast update error: {e}")
                self._last_fast_error = time.time()
            self._connected = False

    async def update_entity(
        self,
        zone_name: str,
        entity_id: str,
        x: float = None,
        y: float = None,
        z: float = None,
        scale: float = None,
        visible: bool = None,
        text: str = None,
        material: str = None,
    ) -> bool:
        """Update a single entity."""
        message = {"type": "update_entity", "zone": zone_name, "id": entity_id}

        if x is not None:
            message["x"] = x
        if y is not None:
            message["y"] = y
        if z is not None:
            message["z"] = z
        if scale is not None:
            message["scale"] = scale
        if visible is not None:
            message["visible"] = visible
        if text is not None:
            message["text"] = text
        if material is not None:
            message["material"] = material

        response = await self.send(message)
        return response is not None and response.get("type") == "entity_updated"

    async def set_visible(
        self, zone_name: str, visible: bool, entity_ids: list[str] = None
    ) -> bool:
        """Set visibility for entities in a zone."""
        message = {"type": "set_visible", "zone": zone_name, "visible": visible}
        if entity_ids:
            message["entities"] = entity_ids

        response = await self.send(message)
        return response is not None and response.get("type") == "visibility_updated"

    async def cleanup_zone(self, zone_name: str) -> bool:
        """Remove all entities from a zone."""
        response = await self.send({"type": "cleanup_zone", "zone": zone_name})
        return response is not None and response.get("type") == "zone_cleaned"

    @property
    def connected(self) -> bool:
        return self._connected


# Convenience functions for sync usage
def create_client(host: str = "localhost", port: int = 8765) -> VizClient:
    """Create a new VizClient instance."""
    return VizClient(host, port)


async def main():
    """Test the client connection."""
    client = VizClient()

    if not await client.connect():
        return

    # Test ping
    if await client.ping():
        logger.info("Ping successful!")

    # Get zones
    zones = await client.get_zones()
    logger.info(f"Found {len(zones)} zone(s)")
    for zone in zones:
        logger.info(f"  - {zone['name']}")

    await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
