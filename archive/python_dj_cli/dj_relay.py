"""
DJ Relay - Sends audio data to a VJ server for multi-DJ visualization.

This module allows the audio processor to run in "DJ mode" where it sends
FFT-analyzed audio data to a central VJ server instead of controlling
Minecraft directly.

Usage:
    python -m audio_processor.app_capture --dj-relay \\
        --vj-server ws://vj-server.example.com:9000 \\
        --dj-name "DJ Alice" --dj-key "secret123"
"""

import asyncio
import json
import logging
import random
import time
from dataclasses import dataclass
from typing import TYPE_CHECKING, Callable, List, Optional

if TYPE_CHECKING:
    from audio_processor.patterns import PatternConfig, VisualizationPattern
    from python_client.viz_client import VizClient

try:
    import websockets

    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False

logger = logging.getLogger("dj_relay")


@dataclass
class DJRelayConfig:
    """Configuration for DJ relay mode."""

    vj_server_url: str = "ws://localhost:9000"
    dj_id: str = "dj_1"
    dj_name: str = "DJ 1"
    dj_key: str = ""
    reconnect_interval: float = 5.0
    heartbeat_interval: float = 2.0
    target_fps: float = 20.0
    max_connect_attempts: int = 3  # Number of retry attempts for initial connection
    # Direct mode - send visualization directly to Minecraft
    direct_mode: bool = False
    minecraft_host: Optional[str] = None  # None = get from VJ server auth response
    minecraft_port: int = 8765
    zone: str = "main"
    entity_count: int = 16


class DJRelay:
    """
    Client for sending audio data to a VJ server.

    Handles:
    - WebSocket connection to VJ server
    - DJ authentication
    - Sending audio frames at high frequency
    - Reconnection on disconnect
    - Heartbeat for connection health
    """

    def __init__(self, config: DJRelayConfig):
        self.config = config
        self._websocket = None
        self._connected = False
        self._authenticated = False
        self._is_active = False  # Whether this DJ is currently live
        self._running = False

        self._seq = 0
        self._frames_sent = 0
        self._last_heartbeat = 0
        self._reconnect_attempts = 0
        self._heartbeat_failures = 0
        self._last_heartbeat_rtt_ms = 0.0
        self._receiver_task: Optional[asyncio.Task] = None  # Background receive task

        # Direct mode - Minecraft connection and pattern engine
        self.viz_client: Optional["VizClient"] = None
        self.pattern: Optional["VisualizationPattern"] = None
        self.pattern_config: Optional["PatternConfig"] = None
        self._pattern_name: str = "spectrum"
        self._mc_connected: bool = False  # Track Minecraft connection status
        self._zone: str = config.zone
        self._entity_count: int = config.entity_count
        # Runtime routing policy controlled by VJ server.
        # relay: send only to VJ
        # dual: send to VJ + direct publish to Minecraft
        self._route_mode: str = "dual" if config.direct_mode else "relay"

        # Callbacks
        self._on_status_change: Optional[Callable[[bool], None]] = None
        self._on_disconnect: Optional[Callable[[], None]] = None
        self._on_pattern_change: Optional[Callable[[str], None]] = None

    @property
    def connected(self) -> bool:
        return self._connected and self._authenticated

    @property
    def is_active(self) -> bool:
        return self._is_active

    @property
    def route_mode(self) -> str:
        return self._route_mode

    def should_publish_direct(self) -> bool:
        """Whether this DJ should publish visualization directly to Minecraft."""
        return (
            self._route_mode == "dual"
            and self._is_active
            and self.viz_client is not None
            and self._mc_connected
            and self.pattern is not None
        )

    def set_callbacks(
        self,
        on_status_change: Optional[Callable[[bool], None]] = None,
        on_disconnect: Optional[Callable[[], None]] = None,
        on_pattern_change: Optional[Callable[[str], None]] = None,
    ):
        """Set callback functions."""
        self._on_status_change = on_status_change
        self._on_disconnect = on_disconnect
        self._on_pattern_change = on_pattern_change

    async def connect(self) -> bool:
        """Connect and authenticate with VJ server.

        Includes retry logic with exponential backoff between attempts.
        Returns False only after all max_connect_attempts are exhausted.
        """
        if not HAS_WEBSOCKETS:
            logger.error("websockets not installed")
            return False

        max_attempts = self.config.max_connect_attempts

        for attempt in range(1, max_attempts + 1):
            try:
                logger.info(
                    f"Connecting to VJ server: {self.config.vj_server_url} (attempt {attempt}/{max_attempts})"
                )
                self._websocket = await asyncio.wait_for(
                    websockets.connect(self.config.vj_server_url), timeout=10.0
                )
                self._connected = True

                # Send authentication
                auth_msg = {
                    "type": "dj_auth",
                    "dj_id": self.config.dj_id,
                    "dj_name": self.config.dj_name,
                    "dj_key": self.config.dj_key,
                    "direct_mode": self.config.direct_mode,
                }
                await self._websocket.send(json.dumps(auth_msg))

                # Wait for auth response
                response = await asyncio.wait_for(self._websocket.recv(), timeout=5.0)
                data = json.loads(response)

                if data.get("type") == "auth_success":
                    self._authenticated = True
                    self._is_active = data.get("is_active", False)
                    if data.get("route_mode") in ("relay", "dual"):
                        self._route_mode = data.get("route_mode")
                    logger.info(f"Authenticated as {self.config.dj_name}")
                    if self._is_active:
                        logger.info("You are now LIVE!")

                    # Handle clock synchronization request from server
                    # This corrects for clock skew between DJ and server
                    try:
                        sync_msg = await asyncio.wait_for(self._websocket.recv(), timeout=5.0)
                        sync_data = json.loads(sync_msg)
                        if sync_data.get("type") == "clock_sync_request":
                            t2 = time.time()  # DJ time when received
                            # Small delay for processing
                            t3 = time.time()  # DJ time when sending response
                            await self._websocket.send(
                                json.dumps(
                                    {
                                        "type": "clock_sync_response",
                                        "server_time": sync_data.get("server_time"),
                                        "dj_recv_time": t2,
                                        "dj_send_time": t3,
                                    }
                                )
                            )
                            logger.debug("Clock sync completed")
                    except asyncio.TimeoutError:
                        logger.debug("No clock sync request received (older server)")
                    except Exception as e:
                        logger.debug(f"Clock sync failed: {e}")

                    # Handle direct mode - connect to Minecraft.
                    # Legacy servers don't send stream_route, so keep config-driven behavior.
                    if self._route_mode == "dual" or self.config.direct_mode:
                        await self._setup_direct_mode(data)

                    return True
                else:
                    logger.error(f"Authentication failed: {data}")
                    await self._websocket.close()
                    self._connected = False
                    # Auth failure is not retryable - credentials are wrong
                    return False

            except asyncio.TimeoutError:
                logger.warning(f"Connection attempt {attempt}/{max_attempts} timed out")
                self._connected = False
            except Exception as e:
                logger.warning(f"Connection attempt {attempt}/{max_attempts} failed: {e}")
                self._connected = False

            # If not the last attempt, wait with exponential backoff before retrying
            if attempt < max_attempts:
                backoff = min(self.config.reconnect_interval * (1.5 ** (attempt - 1)), 30.0)
                backoff += random.uniform(0, backoff * 0.1)  # Add jitter
                logger.info(f"Waiting {backoff:.1f}s before retry...")
                await asyncio.sleep(backoff)

        logger.error(f"Failed to connect after {max_attempts} attempts")
        return False

    async def _setup_direct_mode(self, auth_data: dict):
        """Set up direct Minecraft connection after VJ server auth."""
        # Get Minecraft host from auth response or config
        mc_host = auth_data.get("minecraft_host", self.config.minecraft_host)
        mc_port = auth_data.get("minecraft_port", self.config.minecraft_port)

        if not mc_host:
            logger.warning("Direct mode: No Minecraft host specified, using localhost")
            mc_host = "localhost"

        # Get zone and entity info from auth response
        self._zone = auth_data.get("zone", self.config.zone)
        self._entity_count = auth_data.get("entity_count", self.config.entity_count)

        # Initialize pattern from VJ server's current pattern
        pattern_name = auth_data.get("current_pattern", "spectrum")
        self._init_pattern(pattern_name)

        # If already connected directly, just refresh zone/pool config.
        if self.viz_client and self._mc_connected and self.viz_client.connected:
            try:
                await self.viz_client.init_pool(self._zone, self._entity_count, "SEA_LANTERN")
            except Exception as e:
                logger.debug(f"Direct mode: pool refresh failed: {e}")
            return

        # Connect to Minecraft
        logger.info(f"Direct mode: Connecting to Minecraft at {mc_host}:{mc_port}")
        try:
            from python_client.viz_client import VizClient

            self.viz_client = VizClient(mc_host, mc_port)
            if await self.viz_client.connect():
                self._mc_connected = True
                logger.info(f"Direct mode: Connected to Minecraft, zone={self._zone}")

                # Initialize entity pool
                await self.viz_client.init_pool(self._zone, self._entity_count, "SEA_LANTERN")
                await asyncio.sleep(0.3)
            else:
                logger.error(f"Direct mode: Failed to connect to Minecraft at {mc_host}:{mc_port}")
                self._mc_connected = False
        except Exception as e:
            logger.error(f"Direct mode: Minecraft connection error: {e}")
            self._mc_connected = False

    def _init_pattern(self, pattern_name: str):
        """Initialize visualization pattern."""
        try:
            from audio_processor.patterns import PatternConfig, get_pattern

            self.pattern_config = PatternConfig(entity_count=self._entity_count)
            self.pattern = get_pattern(pattern_name, self.pattern_config)
            self._pattern_name = pattern_name
            logger.info(f"Direct mode: Using pattern '{pattern_name}'")
        except Exception as e:
            logger.error(f"Failed to initialize pattern: {e}")

    def _set_pattern(self, pattern_name: str, config_data: dict = None):
        """Update current pattern (called when VJ server broadcasts pattern change)."""
        try:
            from audio_processor.patterns import PatternConfig, get_pattern

            if config_data:
                self.pattern_config = PatternConfig(
                    entity_count=config_data.get("entity_count", self._entity_count),
                    zone_size=config_data.get("zone_size", 10.0),
                    beat_boost=config_data.get("beat_boost", 1.5),
                    base_scale=config_data.get("base_scale", 0.2),
                    max_scale=config_data.get("max_scale", 1.0),
                )
            else:
                self.pattern_config = PatternConfig(entity_count=self._entity_count)

            self.pattern = get_pattern(pattern_name, self.pattern_config)
            self._pattern_name = pattern_name
            logger.info(f"Pattern changed to: {pattern_name}")

            if self._on_pattern_change:
                self._on_pattern_change(pattern_name)
        except Exception as e:
            logger.error(f"Failed to set pattern: {e}")

    async def _handle_config_sync(self, data: dict):
        """Handle configuration sync from VJ server (entity count, zone changes)."""
        new_entity_count = data.get("entity_count")
        new_zone = data.get("zone")
        reinit_pool = False

        if new_entity_count and new_entity_count != self._entity_count:
            self._entity_count = new_entity_count
            reinit_pool = True
            logger.info(f"Entity count updated to: {new_entity_count}")

        if new_zone and new_zone != self._zone:
            self._zone = new_zone
            reinit_pool = True
            logger.info(f"Zone updated to: {new_zone}")

        # Re-initialize Minecraft entity pool if needed
        if reinit_pool and self.viz_client and self._mc_connected:
            try:
                await self.viz_client.init_pool(self._zone, self._entity_count, "SEA_LANTERN")
                logger.info(
                    f"Re-initialized entity pool: zone={self._zone}, count={self._entity_count}"
                )
            except Exception as e:
                logger.error(f"Failed to re-initialize entity pool: {e}")

        # Update pattern config if entity count changed
        if new_entity_count and self.pattern_config:
            self._init_pattern(self._pattern_name)

    async def _handle_stream_route(self, data: dict):
        """Handle runtime stream routing policy from VJ server."""
        new_mode = data.get("route_mode")
        if new_mode not in ("relay", "dual"):
            return

        previous = self._route_mode
        self._route_mode = new_mode
        if previous != new_mode:
            logger.info(f"Stream route updated: {previous} -> {new_mode}")

        # Keep active state in sync if provided in the same message
        if "is_active" in data:
            self._is_active = bool(data.get("is_active"))

        # In dual mode, ensure direct MC path is ready.
        if new_mode == "dual":
            await self._setup_direct_mode(data)
        else:
            # Relay mode: close direct path to reduce resource usage.
            if self.viz_client:
                try:
                    if self._mc_connected:
                        await self.viz_client.set_visible(self._zone, False)
                except Exception:
                    pass
                try:
                    await self.viz_client.disconnect()
                except Exception:
                    pass
                self.viz_client = None
            self._mc_connected = False

    async def disconnect(self):
        """Gracefully disconnect from VJ server."""
        if self._websocket and self._connected:
            try:
                # Send going offline message
                await self._websocket.send(json.dumps({"type": "going_offline"}))
                await self._websocket.close()
            except Exception as e:
                logger.debug(f"Error during disconnect: {e}")

        self._connected = False
        self._authenticated = False
        self._websocket = None

    async def send_frame(
        self,
        bands: List[float],
        peak: float,
        is_beat: bool,
        beat_intensity: float,
        bpm: float = 120.0,
        instant_bass: float = 0.0,
        instant_kick: bool = False,
    ) -> bool:
        """
        Send an audio frame to the VJ server.

        Args:
            bands: 5-band frequency values (0-1)
            peak: Overall peak amplitude (0-1)
            is_beat: Whether a beat was detected this frame
            beat_intensity: Beat strength (0-1)
            bpm: Estimated BPM
            instant_bass: Bass lane energy (0-1) for instant response
            instant_kick: Bass lane kick detection (~1ms latency)

        Returns:
            True if sent successfully
        """
        if not self.connected:
            return False

        self._seq += 1
        self._frames_sent += 1

        frame = {
            "type": "dj_audio_frame",
            "seq": self._seq,
            "ts": time.time(),
            "bands": bands,
            "peak": peak,
            "beat": is_beat,
            "beat_i": beat_intensity,
            "bpm": bpm,
            "i_bass": instant_bass,  # Bass lane energy (instant)
            "i_kick": instant_kick,  # Bass lane kick detection (instant)
        }

        try:
            await self._websocket.send(json.dumps(frame))
            return True
        except Exception as e:
            logger.error(f"Failed to send frame: {e}")
            self._connected = False
            if self._on_disconnect:
                self._on_disconnect()
            return False

    async def send_heartbeat(self) -> bool:
        """Send heartbeat to server."""
        if not self.connected:
            return False

        now = time.time()
        if now - self._last_heartbeat < self.config.heartbeat_interval:
            return True

        self._last_heartbeat = now

        # Update Minecraft connection status
        if self.viz_client:
            self._mc_connected = self.viz_client.connected

        try:
            heartbeat = {"type": "dj_heartbeat", "ts": now}
            # Include Minecraft connection status when dual routing is enabled
            if self._route_mode == "dual":
                heartbeat["mc_connected"] = self._mc_connected

            await self._websocket.send(json.dumps(heartbeat))
            # Reset heartbeat failures on success
            self._heartbeat_failures = 0
            return True
        except Exception as e:
            logger.debug(f"Failed to send heartbeat: {e}")
            self._heartbeat_failures += 1
            if self._heartbeat_failures >= 3:
                logger.warning(
                    f"Heartbeat failures exceeded threshold ({self._heartbeat_failures}), triggering reconnection"
                )
                self._connected = False
                if self._on_disconnect:
                    self._on_disconnect()
            return False

    async def receive_messages(self):
        """Background task to receive messages from VJ server."""
        if not self._websocket:
            return

        try:
            async for message in self._websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get("type")

                    if msg_type == "status_update":
                        was_active = self._is_active
                        self._is_active = data.get("is_active", False)

                        if was_active != self._is_active:
                            status = "LIVE" if self._is_active else "standby"
                            logger.info(f"Status: {status}")
                            if self._on_status_change:
                                self._on_status_change(self._is_active)

                    elif msg_type == "heartbeat_ack":
                        # RTT ping derived from echoed heartbeat timestamp.
                        echo_ts = data.get("echo_ts")
                        if isinstance(echo_ts, (int, float)):
                            rtt_ms = (time.time() - float(echo_ts)) * 1000.0
                            rtt_ms = max(0.0, min(rtt_ms, 60000.0))
                            if self._last_heartbeat_rtt_ms > 0:
                                self._last_heartbeat_rtt_ms = (
                                    self._last_heartbeat_rtt_ms * 0.8 + rtt_ms * 0.2
                                )
                            else:
                                self._last_heartbeat_rtt_ms = rtt_ms

                    elif msg_type == "pattern_sync":
                        # VJ server is broadcasting a pattern change
                        pattern_name = data.get("pattern", "spectrum")
                        pattern_config = data.get("config")
                        self._set_pattern(pattern_name, pattern_config)

                    elif msg_type == "config_sync":
                        # VJ server is updating configuration (entity count, zone, etc.)
                        await self._handle_config_sync(data)
                    elif msg_type == "stream_route":
                        # VJ server controls relay vs dual-publish behavior.
                        await self._handle_stream_route(data)

                except json.JSONDecodeError:
                    pass

        except websockets.exceptions.ConnectionClosed:
            self._connected = False
            logger.warning("Disconnected from VJ server")
            if self._on_disconnect:
                self._on_disconnect()

    async def run_with_reconnect(self, frame_callback: Callable):
        """
        Run relay with automatic reconnection.

        Args:
            frame_callback: Async function that returns (bands, peak, is_beat, beat_intensity, bpm)
        """
        self._running = True
        frame_interval = 1.0 / max(1.0, float(self.config.target_fps))

        while self._running:
            # Connect
            if not self.connected:
                if await self.connect():
                    # Reset reconnect attempts on successful connection
                    self._reconnect_attempts = 0
                    # Cancel old receiver task before starting a new one
                    if self._receiver_task and not self._receiver_task.done():
                        self._receiver_task.cancel()
                    # Start receiving messages in background with error logging
                    self._receiver_task = asyncio.create_task(self.receive_messages())
                    self._receiver_task.add_done_callback(self._on_receiver_done)
                else:
                    # Calculate exponential backoff with jitter
                    backoff = min(
                        self.config.reconnect_interval * (1.5**self._reconnect_attempts), 30.0
                    )
                    backoff += random.uniform(
                        0, backoff * 0.1
                    )  # Add jitter to prevent thundering herd
                    self._reconnect_attempts += 1
                    logger.info(
                        f"Reconnect attempt {self._reconnect_attempts} failed, waiting {backoff:.1f}s before retry"
                    )
                    await asyncio.sleep(backoff)
                    continue

            # Send frames
            try:
                bands, peak, is_beat, beat_intensity, bpm = await frame_callback()
                await self.send_frame(bands, peak, is_beat, beat_intensity, bpm)
                await self.send_heartbeat()
                await asyncio.sleep(frame_interval)

            except Exception as e:
                logger.error(f"Frame callback error: {e}")
                await asyncio.sleep(frame_interval)

    def _on_receiver_done(self, task: asyncio.Task):
        """Callback for when the receiver task completes (error logging)."""
        try:
            exc = task.exception()
        except asyncio.CancelledError:
            return
        if exc is not None:
            logger.error(f"Receiver task failed with error: {exc}")

    def stop(self):
        """Stop the relay."""
        self._running = False
        if self._receiver_task and not self._receiver_task.done():
            self._receiver_task.cancel()


class DJRelayAgent:
    """
    Wraps the app capture agent for DJ relay mode.

    Runs the normal audio capture and FFT analysis, but instead of
    sending to Minecraft, sends frames to the VJ server.
    """

    def __init__(self, relay: DJRelay, capture, fft_analyzer=None, spectrograph=None):
        self.relay = relay
        self.capture = capture
        self.fft_analyzer = fft_analyzer
        self.spectrograph = spectrograph

        self._frame_count = 0
        self._running = False
        self._agent_reconnect_attempts = 0  # Track reconnection attempts for backoff

        # Band generation (fallback if no FFT) - 5 bands
        self._smoothed_bands = [0.0] * 5
        self._smooth_attack = 0.35
        self._smooth_release = 0.08
        self._stable_bpm = 120.0
        self._dual_verify_vj_frames = 0
        self._dual_verify_mc_frames = 0
        self._dual_verify_last_log = time.time()

    def _stabilize_bpm(self, raw_bpm: float, confidence: float) -> float:
        """Apply octave correction + confidence-weighted smoothing."""
        if not isinstance(raw_bpm, (int, float)) or raw_bpm <= 0:
            return self._stable_bpm

        raw = float(raw_bpm)
        prev = self._stable_bpm if 40.0 <= self._stable_bpm <= 240.0 else 120.0
        candidates = [raw, raw * 2.0, raw * 0.5]
        valid = [c for c in candidates if 60.0 <= c <= 200.0]
        if not valid:
            valid = [max(60.0, min(200.0, raw))]
        chosen = min(valid, key=lambda c: abs(c - prev))

        conf = max(0.0, min(1.0, float(confidence)))
        alpha = 0.10 + conf * 0.35
        self._stable_bpm = (1.0 - alpha) * prev + alpha * chosen
        self._stable_bpm = max(60.0, min(200.0, self._stable_bpm))
        return self._stable_bpm

    async def run(self):
        """Run the DJ relay agent."""
        if not await self.relay.connect():
            logger.error("Failed to connect to VJ server")
            return

        # Start message receiver with error logging
        receiver_task = asyncio.create_task(self.relay.receive_messages())
        receiver_task.add_done_callback(self.relay._on_receiver_done)

        self._running = True
        frame_interval = 1.0 / max(1.0, float(self.relay.config.target_fps))
        mode_str = "DIRECT" if self.relay.route_mode == "dual" else "RELAY"
        logger.info(f"DJ Relay started ({mode_str} mode). Sending audio to VJ server...")

        try:
            while self._running:
                self._frame_count += 1

                # Get audio frame
                frame = self.capture.get_frame()

                # Get FFT bands if available
                instant_bass = 0.0
                instant_kick = False
                fft_result = None

                if self.fft_analyzer is not None:
                    fft_result = self.fft_analyzer.analyze(
                        synthetic_peak=frame.peak, synthetic_bands=None
                    )
                    if fft_result and self.fft_analyzer.using_fft:
                        bands = fft_result.bands
                        # Sanitize
                        bands = [
                            max(0.0, min(1.0, b))
                            if isinstance(b, (int, float)) and -1e10 < b < 1e10
                            else 0.0
                            for b in bands
                        ]
                        # Get bass lane results for instant kick detection
                        instant_bass = getattr(fft_result, "instant_bass", 0.0)
                        instant_kick = getattr(fft_result, "instant_kick_onset", False)
                    else:
                        bands = self._generate_bands(frame.peak, frame.is_beat)
                else:
                    bands = self._generate_bands(frame.peak, frame.is_beat)

                # Get BPM from FFT analyzer if available
                bpm = self._stable_bpm
                if self.fft_analyzer is not None and fft_result is not None:
                    bpm = self._stabilize_bpm(fft_result.estimated_bpm, fft_result.bpm_confidence)

                # Send to VJ server (always, for monitoring)
                success = await self.relay.send_frame(
                    bands=bands,
                    peak=frame.peak,
                    is_beat=frame.is_beat,
                    beat_intensity=frame.beat_intensity,
                    bpm=bpm,
                    instant_bass=instant_bass,
                    instant_kick=instant_kick,
                )
                if success and self.relay.route_mode == "dual" and self.relay.is_active:
                    self._dual_verify_vj_frames += 1

                if not success and not self.relay.connected:
                    # Connection lost, try to reconnect with exponential backoff
                    self._agent_reconnect_attempts += 1
                    backoff = min(
                        self.relay.config.reconnect_interval
                        * (1.5 ** (self._agent_reconnect_attempts - 1)),
                        30.0,
                    )
                    backoff += random.uniform(0, backoff * 0.1)  # Add jitter
                    logger.warning(
                        f"Connection lost, reconnect attempt {self._agent_reconnect_attempts} after {backoff:.1f}s..."
                    )
                    await asyncio.sleep(backoff)
                    if await self.relay.connect():
                        self._agent_reconnect_attempts = 0  # Reset on success
                        # Cancel old receiver task before creating new one
                        if receiver_task and not receiver_task.done():
                            receiver_task.cancel()
                        receiver_task = asyncio.create_task(self.relay.receive_messages())
                        receiver_task.add_done_callback(self.relay._on_receiver_done)
                        logger.info("Reconnection successful")
                    continue

                # Direct mode: Send visualization directly to Minecraft (if active DJ)
                if self.relay.should_publish_direct():
                    direct_ok = await self._send_to_minecraft(
                        bands, frame.peak, frame.is_beat, frame.beat_intensity
                    )
                    if direct_ok:
                        self._dual_verify_mc_frames += 1

                now = time.time()
                if (
                    self.relay.route_mode == "dual"
                    and self.relay.is_active
                    and now - self._dual_verify_last_log >= 5.0
                ):
                    logger.info(
                        "[DUAL VERIFY] 5s window: vj_frames=%d mc_frames=%d ping=%.1fms",
                        self._dual_verify_vj_frames,
                        self._dual_verify_mc_frames,
                        self.relay._last_heartbeat_rtt_ms,
                    )
                    self._dual_verify_vj_frames = 0
                    self._dual_verify_mc_frames = 0
                    self._dual_verify_last_log = now

                # Update spectrograph
                if self.spectrograph:
                    status = "LIVE" if self.relay.is_active else "STANDBY"
                    if self.relay.route_mode == "dual":
                        status = f"DIRECT:{status}"
                    # Get latency from FFT analyzer
                    latency_ms = 0.0
                    if self.fft_analyzer is not None:
                        stats = self.fft_analyzer.latency_stats
                        if stats:
                            latency_ms = stats.get("fft_latency_ms", 0.0)
                    self.spectrograph.set_stats(
                        preset=f"DJ:{status}",
                        using_fft=self.fft_analyzer is not None and self.fft_analyzer.using_fft,
                        latency_ms=latency_ms,
                    )
                    self.spectrograph.display(
                        bands=bands,
                        amplitude=frame.peak,
                        is_beat=frame.is_beat,
                        beat_intensity=frame.beat_intensity,
                    )

                await asyncio.sleep(frame_interval)

        except Exception as e:
            logger.error(f"DJ Relay error: {e}")
            raise
        finally:
            receiver_task.cancel()
            await self.relay.disconnect()
            # Clean up Minecraft connection in direct mode
            if self.relay.viz_client:
                try:
                    await self.relay.viz_client.set_visible(self.relay._zone, False)
                    await self.relay.viz_client.disconnect()
                except Exception:
                    pass

    async def _send_to_minecraft(
        self, bands: List[float], peak: float, is_beat: bool, beat_intensity: float
    ) -> bool:
        """Send visualization directly to Minecraft (direct mode only)."""
        try:
            from audio_processor.patterns import AudioState

            # Create audio state for pattern calculation
            audio_state = AudioState(
                bands=bands,
                amplitude=peak,
                is_beat=is_beat,
                beat_intensity=beat_intensity,
                frame=self._frame_count,
            )

            # Calculate entities using the pattern
            entities = self.relay.pattern.calculate_entities(audio_state)

            # Prepare particles for beats
            particles = []
            if is_beat and beat_intensity > 0.2:
                particles.append(
                    {
                        "particle": "NOTE",
                        "x": 0.5,
                        "y": 0.5,
                        "z": 0.5,
                        "count": int(20 * beat_intensity),
                    }
                )

            # Send directly to Minecraft
            await self.relay.viz_client.batch_update_fast(self.relay._zone, entities, particles)
            return True

        except Exception as e:
            logger.debug(f"Failed to send to Minecraft: {e}")
            return False

    def _generate_bands(self, peak: float, is_beat: bool) -> List[float]:
        """Generate simple synthetic bands when FFT unavailable."""
        # 5-band system: bass, low-mid, mid, high-mid, high
        targets = [
            peak * 0.9,  # Bass (includes kick)
            peak * 0.7,  # Low-mid
            peak * 0.6,  # Mid
            peak * 0.5,  # High-mid
            peak * 0.4,  # High/Air
        ]

        if is_beat:
            targets[0] *= 1.3  # Boost bass on beat

        for i in range(5):
            if targets[i] > self._smoothed_bands[i]:
                self._smoothed_bands[i] += (
                    targets[i] - self._smoothed_bands[i]
                ) * self._smooth_attack
            else:
                self._smoothed_bands[i] += (
                    targets[i] - self._smoothed_bands[i]
                ) * self._smooth_release

            # Add slight variation
            self._smoothed_bands[i] += random.uniform(-0.02, 0.02)
            self._smoothed_bands[i] = max(0, min(1, self._smoothed_bands[i]))

        return list(self._smoothed_bands)

    def stop(self):
        """Stop the agent."""
        self._running = False
