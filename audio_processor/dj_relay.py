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
import time
from typing import Optional, Callable, List
from dataclasses import dataclass

try:
    import websockets
    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False

logger = logging.getLogger('dj_relay')


@dataclass
class DJRelayConfig:
    """Configuration for DJ relay mode."""
    vj_server_url: str = "ws://localhost:9000"
    dj_id: str = "dj_1"
    dj_name: str = "DJ 1"
    dj_key: str = ""
    reconnect_interval: float = 5.0
    heartbeat_interval: float = 2.0


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

        # Callbacks
        self._on_status_change: Optional[Callable[[bool], None]] = None
        self._on_disconnect: Optional[Callable[[], None]] = None

    @property
    def connected(self) -> bool:
        return self._connected and self._authenticated

    @property
    def is_active(self) -> bool:
        return self._is_active

    def set_callbacks(
        self,
        on_status_change: Optional[Callable[[bool], None]] = None,
        on_disconnect: Optional[Callable[[], None]] = None
    ):
        """Set callback functions."""
        self._on_status_change = on_status_change
        self._on_disconnect = on_disconnect

    async def connect(self) -> bool:
        """Connect and authenticate with VJ server."""
        if not HAS_WEBSOCKETS:
            logger.error("websockets not installed")
            return False

        try:
            logger.info(f"Connecting to VJ server: {self.config.vj_server_url}")
            self._websocket = await asyncio.wait_for(
                websockets.connect(self.config.vj_server_url),
                timeout=10.0
            )
            self._connected = True

            # Send authentication
            auth_msg = {
                'type': 'dj_auth',
                'dj_id': self.config.dj_id,
                'dj_name': self.config.dj_name,
                'dj_key': self.config.dj_key
            }
            await self._websocket.send(json.dumps(auth_msg))

            # Wait for auth response
            response = await asyncio.wait_for(
                self._websocket.recv(),
                timeout=5.0
            )
            data = json.loads(response)

            if data.get('type') == 'auth_success':
                self._authenticated = True
                self._is_active = data.get('is_active', False)
                logger.info(f"Authenticated as {self.config.dj_name}")
                if self._is_active:
                    logger.info("You are now LIVE!")
                return True
            else:
                logger.error(f"Authentication failed: {data}")
                await self._websocket.close()
                self._connected = False
                return False

        except asyncio.TimeoutError:
            logger.error("Connection timed out")
            return False
        except Exception as e:
            logger.error(f"Connection failed: {e}")
            return False

    async def disconnect(self):
        """Gracefully disconnect from VJ server."""
        if self._websocket and self._connected:
            try:
                # Send going offline message
                await self._websocket.send(json.dumps({
                    'type': 'going_offline'
                }))
                await self._websocket.close()
            except:
                pass

        self._connected = False
        self._authenticated = False
        self._websocket = None

    async def send_frame(
        self,
        bands: List[float],
        peak: float,
        is_beat: bool,
        beat_intensity: float,
        bpm: float = 120.0
    ) -> bool:
        """
        Send an audio frame to the VJ server.

        Args:
            bands: 6-band frequency values (0-1)
            peak: Overall peak amplitude (0-1)
            is_beat: Whether a beat was detected this frame
            beat_intensity: Beat strength (0-1)
            bpm: Estimated BPM

        Returns:
            True if sent successfully
        """
        if not self.connected:
            return False

        self._seq += 1
        self._frames_sent += 1

        frame = {
            'type': 'dj_audio_frame',
            'seq': self._seq,
            'ts': time.time(),
            'bands': bands,
            'peak': peak,
            'beat': is_beat,
            'beat_i': beat_intensity,
            'bpm': bpm
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

        try:
            await self._websocket.send(json.dumps({
                'type': 'dj_heartbeat',
                'ts': now
            }))
            return True
        except:
            return False

    async def receive_messages(self):
        """Background task to receive messages from VJ server."""
        if not self._websocket:
            return

        try:
            async for message in self._websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get('type')

                    if msg_type == 'status_update':
                        was_active = self._is_active
                        self._is_active = data.get('is_active', False)

                        if was_active != self._is_active:
                            status = "LIVE" if self._is_active else "standby"
                            logger.info(f"Status: {status}")
                            if self._on_status_change:
                                self._on_status_change(self._is_active)

                    elif msg_type == 'heartbeat_ack':
                        # Calculate latency
                        if 'server_time' in data:
                            latency = (time.time() - data['server_time']) * 1000
                            # Could track this for display

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

        while self._running:
            # Connect
            if not self.connected:
                if await self.connect():
                    # Start receiving messages in background
                    asyncio.create_task(self.receive_messages())
                else:
                    # Wait before retry
                    await asyncio.sleep(self.config.reconnect_interval)
                    continue

            # Send frames
            try:
                bands, peak, is_beat, beat_intensity, bpm = await frame_callback()
                await self.send_frame(bands, peak, is_beat, beat_intensity, bpm)
                await self.send_heartbeat()
                await asyncio.sleep(0.016)  # ~60 FPS

            except Exception as e:
                logger.error(f"Frame callback error: {e}")
                await asyncio.sleep(0.016)

    def stop(self):
        """Stop the relay."""
        self._running = False


class DJRelayAgent:
    """
    Wraps the app capture agent for DJ relay mode.

    Runs the normal audio capture and FFT analysis, but instead of
    sending to Minecraft, sends frames to the VJ server.
    """

    def __init__(
        self,
        relay: DJRelay,
        capture,
        fft_analyzer=None,
        spectrograph=None
    ):
        self.relay = relay
        self.capture = capture
        self.fft_analyzer = fft_analyzer
        self.spectrograph = spectrograph

        self._frame_count = 0
        self._running = False

        # Band generation (fallback if no FFT)
        self._smoothed_bands = [0.0] * 6
        self._smooth_attack = 0.35
        self._smooth_release = 0.08

    async def run(self):
        """Run the DJ relay agent."""
        if not await self.relay.connect():
            logger.error("Failed to connect to VJ server")
            return

        # Start message receiver
        receiver_task = asyncio.create_task(self.relay.receive_messages())

        self._running = True
        logger.info("DJ Relay started. Sending audio to VJ server...")

        try:
            while self._running:
                self._frame_count += 1

                # Get audio frame
                frame = self.capture.get_frame()

                # Get FFT bands if available
                if self.fft_analyzer is not None:
                    fft_result = self.fft_analyzer.analyze(
                        synthetic_peak=frame.peak,
                        synthetic_bands=None
                    )
                    if fft_result and self.fft_analyzer.using_fft:
                        bands = fft_result.bands
                        # Sanitize
                        bands = [max(0.0, min(1.0, b)) if isinstance(b, (int, float)) and -1e10 < b < 1e10 else 0.0 for b in bands]
                    else:
                        bands = self._generate_bands(frame.peak, frame.is_beat)
                else:
                    bands = self._generate_bands(frame.peak, frame.is_beat)

                # Estimated BPM from calibration history
                bpm = 120.0  # Default, could track this

                # Send to VJ server
                success = await self.relay.send_frame(
                    bands=bands,
                    peak=frame.peak,
                    is_beat=frame.is_beat,
                    beat_intensity=frame.beat_intensity,
                    bpm=bpm
                )

                if not success and not self.relay.connected:
                    # Connection lost, try to reconnect
                    logger.warning("Connection lost, attempting reconnect...")
                    if await self.relay.connect():
                        receiver_task = asyncio.create_task(self.relay.receive_messages())
                    else:
                        await asyncio.sleep(5)
                        continue

                # Update spectrograph
                if self.spectrograph:
                    status = "LIVE" if self.relay.is_active else "STANDBY"
                    self.spectrograph.set_stats(
                        preset=f"DJ:{status}",
                        using_fft=self.fft_analyzer is not None and self.fft_analyzer.using_fft
                    )
                    self.spectrograph.display(
                        bands=bands,
                        amplitude=frame.peak,
                        is_beat=frame.is_beat,
                        beat_intensity=frame.beat_intensity
                    )

                await asyncio.sleep(0.016)

        except Exception as e:
            logger.error(f"DJ Relay error: {e}")
            raise
        finally:
            receiver_task.cancel()
            await self.relay.disconnect()

    def _generate_bands(self, peak: float, is_beat: bool) -> List[float]:
        """Generate simple synthetic bands when FFT unavailable."""
        import random

        targets = [
            peak * 0.9,  # Sub
            peak * 0.85,  # Bass
            peak * 0.7,  # Low
            peak * 0.6,  # Mid
            peak * 0.5,  # High
            peak * 0.4,  # Air
        ]

        if is_beat:
            targets[0] *= 1.3
            targets[1] *= 1.25

        for i in range(6):
            if targets[i] > self._smoothed_bands[i]:
                self._smoothed_bands[i] += (targets[i] - self._smoothed_bands[i]) * self._smooth_attack
            else:
                self._smoothed_bands[i] += (targets[i] - self._smoothed_bands[i]) * self._smooth_release

            # Add slight variation
            self._smoothed_bands[i] += random.uniform(-0.02, 0.02)
            self._smoothed_bands[i] = max(0, min(1, self._smoothed_bands[i]))

        return list(self._smoothed_bands)

    def stop(self):
        """Stop the agent."""
        self._running = False
