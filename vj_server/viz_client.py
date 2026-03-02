"""
AudioViz Python Client
Connects to Minecraft plugin via WebSocket to control visualizations.
"""

import asyncio
import logging
import time
from typing import Any, Callable, Optional

import msgspec
import msgspec.json as mjson
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

        # Correlation-ID-based request/response matching.
        # Each send() assigns a unique _seq, which the MC mod echoes back.
        # The receive loop matches responses to pending futures by _seq.
        self._pending_futures: dict[int, asyncio.Future] = {}
        self._seq: int = 0
        self._use_receive_loop: bool = False

        # Fallback queue for responses without a seq (e.g. welcome message)
        self._unmatched_responses: asyncio.Queue = asyncio.Queue(maxsize=50)

        # Server type reported by Minecraft (paper/fabric), set from welcome message
        self.server_type: Optional[str] = None

        # Logging flags for batch_update_fast
        self._audio_logged: bool = False
        self._last_fast_error: float = 0.0
        self._last_fire_and_forget_log: float = 0.0
        self._fire_and_forget_errors: dict[str, int] = {
            "voice_audio": 0,
            "bitmap_frame": 0,
        }

    def _encode(self, data: dict) -> str:
        """Encode dict to JSON string for WebSocket text frames.

        msgspec.json.encode() returns bytes, which websockets sends as binary
        frames.  Java's org.java_websocket only handles text frames in
        onMessage(String), so binary frames are silently dropped.  This helper
        ensures we always send text frames.
        """
        return mjson.encode(data).decode()

    def _record_fire_and_forget_error(self, channel: str, error: Exception):
        """Track fire-and-forget send failures with low-rate logs."""
        self._fire_and_forget_errors[channel] = self._fire_and_forget_errors.get(channel, 0) + 1
        now = time.time()
        if self._last_fire_and_forget_log == 0.0 or (now - self._last_fire_and_forget_log) > 10:
            logger.warning(
                "Fire-and-forget send failures: voice_audio=%d bitmap_frame=%d (latest %s error: %s)",
                self._fire_and_forget_errors.get("voice_audio", 0),
                self._fire_and_forget_errors.get("bitmap_frame", 0),
                channel,
                error,
            )
            self._last_fire_and_forget_log = now

    async def connect(self) -> bool:
        """Connect to the AudioViz WebSocket server."""
        try:
            # Use open_timeout instead of asyncio.wait_for — the latter wraps
            # the awaitable in a Task which breaks websockets 14+'s internal
            # reader setup, causing recv() to block forever.
            self.ws = await websockets.connect(
                self.uri,
                open_timeout=self.connect_timeout,
                max_size=10 * 1024 * 1024,  # 10MB — stage block scans can be large
            )
            self._connected = True

            # Reset reconnection counter on successful connection
            self._reconnect_attempts = 0
            # Track successful liveness baseline even before heartbeat starts.
            self._last_pong = time.time()

            # Start background tasks BEFORE reading welcome message.
            # In websockets 14+, only one recv() coroutine can run at a time.
            # If we call recv() here and then start the receive loop, the
            # receive loop's recv() fails with "already running". Instead,
            # let the receive loop be the sole recv() consumer.
            if self._enable_heartbeat:
                self._use_receive_loop = True
                self._receive_task = asyncio.create_task(self._receive_loop())
                self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())

                # Read welcome message from unmatched queue (has no seq)
                try:
                    welcome = await asyncio.wait_for(
                        self._unmatched_responses.get(), timeout=self.connect_timeout
                    )
                    self.server_type = welcome.get("server_type")
                    logger.info(
                        f"Connected to AudioViz: {welcome.get('message', 'OK')} (server_type={self.server_type})"
                    )
                except asyncio.TimeoutError:
                    logger.warning("No welcome message received, but connection is up")
            else:
                # Without heartbeat, read welcome directly (no receive loop conflict)
                response = await self.ws.recv()
                data = mjson.decode(response)
                self.server_type = data.get("server_type")
                logger.info(
                    f"Connected to AudioViz: {data.get('message', 'OK')} (server_type={self.server_type})"
                )

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
                        await self.ws.send(self._encode({"type": "ping"}))
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
                    data = mjson.decode(message)
                    msg_type = data.get("type")

                    # Handle ping messages - respond with pong
                    if msg_type == "ping":
                        self._last_pong = time.time()  # Ping proves connection alive
                        try:
                            await self.ws.send(self._encode({"type": "pong"}))
                        except Exception as e:
                            logger.warning(f"Failed to send pong: {e}")
                        continue

                    # Handle pong messages for heartbeat
                    if msg_type == "pong":
                        self._last_pong = time.time()
                        continue

                    # Discard fire-and-forget acknowledgments that flood the channel
                    if msg_type in ("batch_updated", "ok"):
                        continue

                    # Route responses to pending futures by correlation ID (_seq).
                    # If the response has a _seq that matches a pending future, deliver it.
                    # Otherwise, put it on the unmatched queue (e.g. welcome message).
                    resp_seq = data.get("_seq")
                    if resp_seq is not None and resp_seq in self._pending_futures:
                        future = self._pending_futures.pop(resp_seq)
                        if not future.done():
                            future.set_result(data)
                        continue

                    # No matching seq — queue as unmatched (welcome, unsolicited messages)
                    if msg_type not in self._message_handlers:
                        try:
                            self._unmatched_responses.put_nowait(data)
                        except asyncio.QueueFull:
                            try:
                                self._unmatched_responses.get_nowait()
                            except asyncio.QueueEmpty:
                                pass
                            self._unmatched_responses.put_nowait(data)

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
                    self._cancel_pending_futures("connection closed")
                    if self.auto_reconnect:
                        asyncio.create_task(self.reconnect())
                    break
                except msgspec.DecodeError:
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

    def _cancel_pending_futures(self, reason: str):
        """Cancel all pending request futures (e.g. on disconnect)."""
        for seq, future in list(self._pending_futures.items()):
            if not future.done():
                future.cancel()
        self._pending_futures.clear()

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
            self._use_receive_loop = True
            self._last_pong = time.time()
            if self._receive_task is None or self._receive_task.done():
                self._receive_task = asyncio.create_task(self._receive_loop())
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
        # Clear unmatched responses queue
        while not self._unmatched_responses.empty():
            try:
                self._unmatched_responses.get_nowait()
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
        """Send a message and wait for response using correlation IDs.

        Each request is tagged with a unique _seq. The MC mod echoes it back,
        and the receive loop delivers the response to the matching future.
        Concurrent callers each get their own response — no FIFO races.
        """
        if not self.ws or not self._connected:
            logger.error("Not connected")
            return None

        try:
            if self._use_receive_loop:
                # Assign correlation ID
                self._seq += 1
                seq = self._seq
                message["_seq"] = seq

                # Create future for this request
                loop = asyncio.get_running_loop()
                future: asyncio.Future = loop.create_future()
                self._pending_futures[seq] = future

                try:
                    await self.ws.send(self._encode(message))
                    response = await asyncio.wait_for(future, timeout=self.connect_timeout)
                    return response
                except asyncio.TimeoutError:
                    logger.error(
                        "Timeout waiting for response (seq=%d, type=%s)",
                        seq,
                        message.get("type", "?"),
                    )
                    return None
                finally:
                    self._pending_futures.pop(seq, None)
            else:
                await self.ws.send(self._encode(message))
                response = await self.ws.recv()
                return mjson.decode(response)

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
            if not self._audio_logged:
                self._audio_logged = True
                logger.info(f"Sending audio data: bands={message['bands'][:2]}...")

        try:
            await self.ws.send(self._encode(message))
        except websockets.exceptions.ConnectionClosed:
            # Connection lost - mark as disconnected for caller to handle
            self._connected = False
        except Exception as e:
            # Log unexpected errors but don't spam
            if self._last_fast_error == 0.0 or (time.time() - self._last_fast_error) > 5:
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

    async def send_voice_frame(self, pcm_base64: str, seq: int):
        """Send a voice audio frame to the Minecraft plugin.

        Args:
            pcm_base64: Base64-encoded PCM audio (960 int16 samples at 48kHz mono)
            seq: Sequence number for ordering
        """
        if not self.ws or not self._connected:
            return
        try:
            await self.ws.send(
                self._encode(
                    {
                        "type": "voice_audio",
                        "data": pcm_base64,
                        "seq": seq,
                    }
                )
            )
        except Exception as e:
            self._record_fire_and_forget_error("voice_audio", e)

    async def send_voice_config(self, config: dict) -> Optional[dict]:
        """Send voice configuration and return voice_status response.

        Args:
            config: Dict with keys: enabled, channel_type, distance, zone

        Returns:
            Response dict from Minecraft plugin (typically voice_status), or None.
        """
        if not self.ws or not self._connected:
            return None
        message = {
            "type": "voice_config",
            "enabled": config.get("enabled", False),
            "channel_type": config.get("channel_type", "static"),
            "distance": config.get("distance", 100.0),
            "zone": config.get("zone", "main"),
        }
        return await self.send(message)

    async def scan_stage_blocks(self, stage: str) -> Optional[dict]:
        """Scan all non-air blocks around a stage's zones.

        Args:
            stage: Stage name to scan blocks for.

        Returns:
            Response dict with palette + blocks data, or None.
        """
        response = await self.send({"type": "scan_stage_blocks", "stage": stage})
        if response and response.get("type") == "stage_blocks":
            return response
        return None

    async def query_zone_status(self) -> Optional[dict]:
        """Query Minecraft plugin for actual per-zone state (entity counts, bitmap status).

        Returns:
            Response dict with per-zone status, or None on failure.
        """
        response = await self.send({"type": "query_zone_status"})
        if response and response.get("type") == "zone_status_report":
            return response
        return None

    # ========== Bitmap Rendering Methods ==========

    async def init_bitmap(
        self, zone_name: str, width: int = 32, height: int = 16, pattern: str = "bmp_spectrum"
    ) -> Optional[dict]:
        """Initialize a bitmap (LED wall) display in a zone.

        Spawns a grid of TextDisplay entities that act as pixels.
        Each pixel's background color is updated to create 2D visuals.

        Args:
            zone_name: Zone to initialize bitmap in.
            width: Grid width in pixels (2-128).
            height: Grid height in pixels (2-64).
            pattern: Initial pattern ID (e.g. 'bmp_spectrum', 'bmp_plasma').

        Returns:
            Response dict with grid info, or None on failure.
        """
        response = await self.send(
            {
                "type": "init_bitmap",
                "zone": zone_name,
                "width": width,
                "height": height,
                "pattern": pattern,
            }
        )
        if response and response.get("type") == "bitmap_initialized":
            logger.info(f"Bitmap initialized: {zone_name} ({width}x{height}, pattern={pattern})")
            return response
        return None

    async def send_bitmap_frame(
        self, zone_name: str, pixels: list[int], brightness: list[int] | None = None
    ) -> bool:
        """Push a raw ARGB pixel array to a bitmap zone.

        This is the low-level frame push for custom VJ server rendering.
        Each pixel is a 32-bit ARGB integer (e.g. 0xFFFF0000 = opaque red).

        For high-frequency updates, consider using send_bitmap_frame_fast()
        which uses base64 encoding for smaller payloads.

        Args:
            zone_name: Target bitmap zone.
            pixels: List of ARGB integers, length must match width*height.
            brightness: Optional per-pixel brightness (0-15), one value per pixel.

        Returns:
            True if accepted by the plugin.
        """
        msg = {
            "type": "bitmap_frame",
            "zone": zone_name,
            "pixel_array": pixels,
        }
        if brightness is not None:
            import base64

            msg["brightness"] = base64.b64encode(bytes(brightness)).decode("ascii")
        response = await self.send(msg)
        return response is not None and response.get("type") == "ok"

    async def send_bitmap_frame_fast(
        self, zone_name: str, pixels: list[int], brightness: list[int] | None = None
    ) -> None:
        """Push a bitmap frame using base64 encoding (fire-and-forget, lower latency).

        Encodes the pixel array as little-endian base64 for compact transport.
        Does not wait for a response — designed for 20 TPS frame streaming.

        Args:
            zone_name: Target bitmap zone.
            pixels: List of ARGB integers.
            brightness: Optional per-pixel brightness (0-15), one value per pixel.
        """
        if not self.ws or not self._connected:
            return
        import base64
        import struct

        raw = struct.pack(f"<{len(pixels)}I", *pixels)
        b64 = base64.b64encode(raw).decode("ascii")
        msg = {
            "type": "bitmap_frame",
            "zone": zone_name,
            "pixels": b64,
        }
        if brightness is not None:
            msg["brightness"] = base64.b64encode(bytes(brightness)).decode("ascii")
        try:
            await self.ws.send(self._encode(msg))
        except Exception as e:
            self._record_fire_and_forget_error("bitmap_frame", e)

    async def set_bitmap_pattern(self, zone_name: str, pattern_id: str) -> Optional[dict]:
        """Switch the active bitmap pattern for a zone.

        Args:
            zone_name: Target bitmap zone.
            pattern_id: Pattern to activate (e.g. 'bmp_plasma', 'bmp_waveform').

        Returns:
            Response dict, or None on failure.
        """
        response = await self.send(
            {
                "type": "set_bitmap_pattern",
                "zone": zone_name,
                "pattern": pattern_id,
            }
        )
        if response and response.get("type") == "bitmap_pattern_set":
            logger.info(f"Bitmap pattern set: {zone_name} -> {pattern_id}")
            return response
        return None

    async def get_bitmap_patterns(self) -> list[dict]:
        """Get all available bitmap pattern IDs and descriptions.

        Returns:
            List of pattern dicts with 'id', 'name', 'description'.
        """
        response = await self.send({"type": "get_bitmap_patterns"})
        if response and response.get("type") == "bitmap_patterns":
            return response.get("patterns", [])
        return []

    async def teardown_bitmap(self, zone_name: str) -> bool:
        """Teardown a bitmap (LED wall) display in a zone.

        Deactivates the bitmap pattern and despawns all TextDisplay entities.

        Args:
            zone_name: Zone to teardown bitmap in.

        Returns:
            True if teardown succeeded.
        """
        response = await self.send({"type": "teardown_bitmap", "zone": zone_name})
        if response and response.get("type") == "bitmap_teardown":
            logger.info(f"Bitmap teardown: {zone_name}")
            return True
        return False

    async def get_bitmap_status(self, zone_name: str) -> Optional[dict]:
        """Get bitmap rendering status for a zone.

        Returns:
            Status dict with active, width, height, pattern info. None on failure.
        """
        return await self.send(
            {
                "type": "get_bitmap_status",
                "zone": zone_name,
            }
        )

    # ========== Bitmap Transitions ==========

    async def bitmap_transition(
        self,
        zone_name: str,
        pattern_id: str,
        transition: str = "crossfade",
        duration_ticks: int = 20,
    ) -> Optional[dict]:
        """Switch pattern with a smooth transition.

        Args:
            zone_name: Target zone.
            pattern_id: New pattern to transition to.
            transition: Transition type (crossfade, dissolve, wipe_left, wipe_right,
                        wipe_up, wipe_down, iris_open, iris_close).
            duration_ticks: Transition duration in server ticks (20 = 1 second).
        """
        return await self.send(
            {
                "type": "bitmap_transition",
                "zone": zone_name,
                "pattern": pattern_id,
                "transition": transition,
                "duration_ticks": duration_ticks,
            }
        )

    async def get_bitmap_transitions(self) -> list[str]:
        """Get list of available transition IDs."""
        resp = await self.send({"type": "get_bitmap_transitions"})
        return resp.get("transitions", []) if resp else []

    # ========== Text / Marquee ==========

    async def bitmap_marquee(
        self, zone_name: str, text: str, color: int = 0xFFFFFFFF, speed: float = 1.5
    ) -> Optional[dict]:
        """Queue a scrolling marquee message on the LED wall.

        Args:
            zone_name: Target zone (must have bmp_marquee pattern active).
            text: Message to scroll.
            color: ARGB text color.
            speed: Scroll speed in pixels per tick (0.5-5.0).
        """
        return await self.send(
            {
                "type": "bitmap_marquee",
                "zone": zone_name,
                "text": text,
                "color": color,
                "speed": speed,
            }
        )

    async def bitmap_track_display(self, zone_name: str, artist: str, title: str) -> Optional[dict]:
        """Show "Now Playing" artist/title overlay with fade animation.

        Args:
            zone_name: Target zone (must have bmp_track_display active or
                       will be composited as overlay).
            artist: Artist name.
            title: Track title.
        """
        return await self.send(
            {
                "type": "bitmap_track_display",
                "zone": zone_name,
                "artist": artist,
                "title": title,
            }
        )

    async def bitmap_countdown(
        self, zone_name: str, seconds: int = 10, action: str = "start"
    ) -> Optional[dict]:
        """Start/stop a countdown timer on the LED wall.

        Args:
            zone_name: Target zone.
            seconds: Countdown duration (for action="start").
            action: "start" or "stop".
        """
        return await self.send(
            {
                "type": "bitmap_countdown",
                "zone": zone_name,
                "seconds": seconds,
                "action": action,
            }
        )

    async def bitmap_chat(self, zone_name: str, player_name: str, message: str) -> Optional[dict]:
        """Push a chat message to the LED wall (usually called from server-side events).

        Args:
            zone_name: Target zone with bmp_chat_wall pattern.
            player_name: Sender's name.
            message: Chat message text.
        """
        return await self.send(
            {
                "type": "bitmap_chat",
                "zone": zone_name,
                "player": player_name,
                "message": message,
            }
        )

    # ========== VJ Effects ==========

    async def bitmap_effects(self, action: str, **kwargs) -> Optional[dict]:
        """Control VJ performance effects.

        Args:
            action: Effect command. One of:
                "strobe"     - kwargs: enabled=bool, divisor=int, color=int
                "freeze"     - kwargs: enabled=bool, zone=str
                "brightness" - kwargs: level=float (0.0-1.0)
                "blackout"   - kwargs: enabled=bool
                "wash"       - kwargs: color=int, opacity=float
                "clear_wash" - no kwargs
                "beat_flash" - kwargs: enabled=bool
                "reset"      - no kwargs
        """
        msg = {"type": "bitmap_effects", "action": action}
        msg.update(kwargs)
        return await self.send(msg)

    async def bitmap_strobe(
        self, enabled: bool = True, divisor: int = 1, color: int = 0xFFFFFFFF
    ) -> Optional[dict]:
        """Enable/disable beat-synced strobe flash."""
        return await self.bitmap_effects("strobe", enabled=enabled, divisor=divisor, color=color)

    async def bitmap_freeze(self, zone_name: str, enabled: bool = True) -> Optional[dict]:
        """Freeze/unfreeze the current frame."""
        return await self.bitmap_effects("freeze", zone=zone_name, enabled=enabled)

    async def bitmap_brightness(self, level: float) -> Optional[dict]:
        """Set global brightness (0.0 = blackout, 1.0 = full)."""
        return await self.bitmap_effects("brightness", level=level)

    async def bitmap_blackout(self, enabled: bool = True) -> Optional[dict]:
        """Instant blackout toggle."""
        return await self.bitmap_effects("blackout", enabled=enabled)

    async def bitmap_wash(self, color: int, opacity: float = 0.3) -> Optional[dict]:
        """Apply a color wash overlay."""
        return await self.bitmap_effects("wash", color=color, opacity=opacity)

    # ========== Palettes ==========

    async def bitmap_palette(self, palette_id: str) -> Optional[dict]:
        """Set the active color palette for bitmap rendering.

        Args:
            palette_id: One of: spectrum, warm, cool, neon, mono, lava,
                        ocean, sunset, forest, cyberpunk, or "none" to disable.
        """
        return await self.send(
            {
                "type": "bitmap_palette",
                "palette": palette_id,
            }
        )

    async def get_bitmap_palettes(self) -> list[dict]:
        """Get list of available palettes."""
        resp = await self.send({"type": "get_bitmap_palettes"})
        return resp.get("palettes", []) if resp else []

    # ========== Layer Compositing ==========

    async def bitmap_layer(self, zone_name: str, action: str, **kwargs) -> Optional[dict]:
        """Control layer compositing for a zone.

        Args:
            zone_name: Target zone.
            action: "set_blend" (mode=str, opacity=float),
                    "set_overlay_pattern" (pattern=str),
                    "clear_overlay".
        """
        msg = {"type": "bitmap_layer", "zone": zone_name, "action": action}
        msg.update(kwargs)
        return await self.send(msg)

    # ========== Fireworks ==========

    async def bitmap_firework(
        self, zone_name: str, x: float = 0.5, y: float = 0.3
    ) -> Optional[dict]:
        """Spawn a firework burst at normalized coordinates.

        Args:
            zone_name: Target zone with bmp_fireworks pattern.
            x: Horizontal position (0.0-1.0, left-right).
            y: Vertical position (0.0-1.0, top-bottom).
        """
        return await self.send(
            {
                "type": "bitmap_firework",
                "zone": zone_name,
                "x": x,
                "y": y,
            }
        )

    # ========== Image Display ==========

    async def bitmap_image(self, zone_name: str, action: str, **kwargs) -> Optional[dict]:
        """Control image display pattern.

        Args:
            zone_name: Target zone with bmp_image pattern.
            action: "load" (path=str), "set_mode" (mode=str),
                    "clear".
        """
        msg = {"type": "bitmap_image", "zone": zone_name, "action": action}
        msg.update(kwargs)
        return await self.send(msg)

    # ========== Composition ==========

    async def bitmap_composition(self, action: str, **kwargs) -> Optional[dict]:
        """Control multi-zone composition.

        Args:
            action: "set_sync_mode" (mode=str: INDEPENDENT/PALETTE_SYNC/BEAT_SYNC/MIRROR),
                    "set_shared_palette" (palette=str),
                    "flash_all" (color=int).
        """
        msg = {"type": "bitmap_composition", "action": action}
        msg.update(kwargs)
        return await self.send(msg)

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
