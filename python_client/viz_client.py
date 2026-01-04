"""
AudioViz Python Client
Connects to Minecraft plugin via WebSocket to control visualizations.
"""

import asyncio
import json
import logging
from typing import Optional, Callable, Any
import websockets
from websockets.client import WebSocketClientProtocol

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class VizClient:
    """WebSocket client for AudioViz Minecraft plugin."""

    def __init__(self, host: str = "localhost", port: int = 8765):
        self.host = host
        self.port = port
        self.uri = f"ws://{host}:{port}"
        self.ws: Optional[WebSocketClientProtocol] = None
        self._connected = False
        self._message_handlers: dict[str, Callable] = {}

    async def connect(self) -> bool:
        """Connect to the AudioViz WebSocket server."""
        try:
            self.ws = await websockets.connect(self.uri)
            self._connected = True

            # Wait for welcome message
            response = await self.ws.recv()
            data = json.loads(response)
            logger.info(f"Connected to AudioViz: {data.get('message', 'OK')}")

            return True

        except Exception as e:
            logger.error(f"Failed to connect: {e}")
            return False

    async def disconnect(self):
        """Disconnect from the server."""
        if self.ws:
            await self.ws.close()
            self._connected = False
            logger.info("Disconnected from AudioViz")

    async def send(self, message: dict) -> Optional[dict]:
        """Send a message and wait for response."""
        if not self.ws or not self._connected:
            logger.error("Not connected")
            return None

        try:
            await self.ws.send(json.dumps(message))
            response = await self.ws.recv()
            return json.loads(response)

        except Exception as e:
            logger.error(f"Send error: {e}")
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

    async def init_pool(self, zone_name: str, count: int = 16,
                        material: str = "GLOWSTONE") -> bool:
        """Initialize entity pool for a zone."""
        response = await self.send({
            "type": "init_pool",
            "zone": zone_name,
            "count": count,
            "material": material
        })
        return response is not None and response.get("type") == "pool_initialized"

    async def batch_update(self, zone_name: str, entities: list[dict],
                           particles: list[dict] = None) -> bool:
        """
        Send batch entity updates (waits for response).
        For real-time visualization, use batch_update_fast() instead.
        """
        message = {
            "type": "batch_update",
            "zone": zone_name,
            "entities": entities
        }
        if particles:
            message["particles"] = particles

        response = await self.send(message)
        return response is not None and response.get("type") == "batch_updated"

    async def batch_update_fast(self, zone_name: str, entities: list[dict],
                                 particles: list[dict] = None):
        """
        Fire-and-forget batch update for real-time visualization.
        Does not wait for response - much faster for high frame rates.
        """
        if not self.ws or not self._connected:
            return

        message = {
            "type": "batch_update",
            "zone": zone_name,
            "entities": entities
        }
        if particles:
            message["particles"] = particles

        try:
            await self.ws.send(json.dumps(message))
        except Exception:
            pass  # Ignore errors for speed

    async def update_entity(self, zone_name: str, entity_id: str,
                            x: float = None, y: float = None, z: float = None,
                            scale: float = None, visible: bool = None,
                            text: str = None, material: str = None) -> bool:
        """Update a single entity."""
        message = {
            "type": "update_entity",
            "zone": zone_name,
            "id": entity_id
        }

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

    async def set_visible(self, zone_name: str, visible: bool,
                          entity_ids: list[str] = None) -> bool:
        """Set visibility for entities in a zone."""
        message = {
            "type": "set_visible",
            "zone": zone_name,
            "visible": visible
        }
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
