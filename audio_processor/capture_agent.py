"""
Host Audio Capture Agent
Captures system audio on the DJ's machine and sends visualization data
to the Minecraft server.

Usage:
    python -m audio_processor.capture_agent --host <server-ip> --port 8765

Requirements:
    pip install sounddevice numpy scipy websockets

On Windows, this uses WASAPI loopback to capture all system audio.
"""

import argparse
import asyncio
import logging
import math
import os
import signal
import sys
from typing import Optional

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from audio_processor.processor import AudioFrame, AudioProcessor
from audio_processor.spectrograph import SpectrographConfig, TerminalSpectrograph
from python_client.viz_client import VizClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("capture_agent")


class CaptureAgent:
    """
    Captures system audio and sends visualization data to Minecraft.

    Architecture:
    1. sounddevice captures system audio via WASAPI loopback
    2. AudioProcessor performs FFT and beat detection
    3. TerminalSpectrograph displays frequency bands
    4. VizClient sends entity updates to Minecraft
    """

    def __init__(
        self,
        minecraft_host: str = "localhost",
        minecraft_port: int = 8765,
        zone: str = "main",
        entity_count: int = 16,
        show_spectrograph: bool = True,
    ):
        """
        Initialize the capture agent.

        Args:
            minecraft_host: Minecraft server IP
            minecraft_port: WebSocket port
            zone: Visualization zone name
            entity_count: Number of visualization entities
            show_spectrograph: Whether to show terminal spectrograph
        """
        self.minecraft_host = minecraft_host
        self.minecraft_port = minecraft_port
        self.zone = zone
        self.entity_count = entity_count
        self.show_spectrograph = show_spectrograph

        # Components
        self.viz_client: Optional[VizClient] = None
        self.audio_processor = AudioProcessor(
            sample_rate=48000, channels=2, smoothing=0.2, beat_sensitivity=1.3
        )

        if show_spectrograph:
            config = SpectrographConfig(bar_width=30, use_colors=True, clear_screen=True)
            self.spectrograph = TerminalSpectrograph(config)
        else:
            self.spectrograph = None

        # State
        self._running = False
        self._stream = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._pending_frames: asyncio.Queue = None

    async def connect_minecraft(self) -> bool:
        """Connect to Minecraft server."""
        self.viz_client = VizClient(self.minecraft_host, self.minecraft_port)

        if not await self.viz_client.connect():
            logger.error(
                f"Failed to connect to Minecraft at {self.minecraft_host}:{self.minecraft_port}"
            )
            return False

        logger.info(f"Connected to Minecraft at {self.minecraft_host}:{self.minecraft_port}")

        # Check if zone exists
        zones = await self.viz_client.get_zones()
        zone_names = [z["name"] for z in zones]

        if self.zone not in zone_names:
            logger.warning(f"Zone '{self.zone}' not found. Available zones: {zone_names}")
            if zone_names:
                self.zone = zone_names[0]
                logger.info(f"Using zone: {self.zone}")
            else:
                logger.error("No zones available!")
                return False

        # Initialize entity pool
        logger.info(f"Initializing {self.entity_count} entities in zone '{self.zone}'...")
        await self.viz_client.init_pool(self.zone, self.entity_count, "SEA_LANTERN")
        await asyncio.sleep(0.5)

        return True

    def _find_loopback_device(self):
        """Find the WASAPI loopback device for system audio capture."""
        import sounddevice as sd

        devices = sd.query_devices()

        # On Windows, look for WASAPI loopback devices
        loopback_device = None

        for i, device in enumerate(devices):
            name = device["name"].lower()

            # Check for loopback indicator
            if "loopback" in name:
                loopback_device = i
                logger.info(f"Found loopback device: {device['name']}")
                break

            # Track default output for fallback
            if device["max_input_channels"] > 0:
                if "stereo mix" in name or "what u hear" in name:
                    loopback_device = i
                    logger.info(f"Found stereo mix device: {device['name']}")
                    break

        if loopback_device is None:
            # Try to find any device that can capture desktop audio
            logger.warning("No loopback device found. Listing available input devices:")
            for i, device in enumerate(devices):
                if device["max_input_channels"] > 0:
                    logger.info(
                        f"  [{i}] {device['name']} (inputs: {device['max_input_channels']})"
                    )

            logger.info("\nTips for Windows:")
            logger.info("1. Install 'sounddevice' with loopback support")
            logger.info("2. Enable 'Stereo Mix' in Sound settings")
            logger.info("3. Use --device <id> to specify a device")

        return loopback_device

    def _audio_callback(self, indata, frames, time_info, status):
        """Called by sounddevice for each audio block."""
        if status:
            logger.warning(f"Audio status: {status}")

        if not self._running:
            return

        # Convert float32 audio to int16 PCM
        # sounddevice provides float32 audio in range [-1, 1]
        pcm_data = (indata * 32767).astype("int16").tobytes()

        # Process audio
        frame = self.audio_processor.process_pcm(pcm_data)

        if frame and self._pending_frames:
            try:
                self._pending_frames.put_nowait(frame)
            except asyncio.QueueFull:
                pass  # Drop frame if queue is full

    async def _process_frames(self):
        """Process audio frames and update visualization."""
        while self._running:
            try:
                frame = await asyncio.wait_for(self._pending_frames.get(), timeout=0.1)

                # Update spectrograph
                if self.spectrograph:
                    self.spectrograph.display(
                        bands=frame.bands,
                        amplitude=frame.amplitude,
                        is_beat=frame.is_beat,
                        beat_intensity=frame.beat_intensity,
                    )

                # Update Minecraft
                if self.viz_client and self.viz_client.connected:
                    await self._update_minecraft(frame)

            except asyncio.TimeoutError:
                continue
            except Exception as e:
                logger.error(f"Frame processing error: {e}")

    async def _update_minecraft(self, frame: AudioFrame):
        """Send visualization update to Minecraft."""
        try:
            entities = []
            grid_size = int(math.ceil(math.sqrt(self.entity_count)))

            for i in range(self.entity_count):
                grid_x = i % grid_size
                grid_z = i // grid_size

                # Position in zone (0-1 range)
                x = (grid_x + 0.5) / grid_size
                z = (grid_z + 0.5) / grid_size

                # Height based on frequency band
                band_index = i % len(frame.bands)
                band_value = frame.bands[band_index]

                y = band_value * 0.8
                scale = 0.3 + band_value * 0.4

                # Boost on beats
                if frame.is_beat:
                    scale = min(1.0, scale * 1.3)
                    y = min(1.0, y + 0.1)

                entities.append(
                    {"id": f"block_{i}", "x": x, "y": y, "z": z, "scale": scale, "visible": True}
                )

            # Add particles on beats
            particles = []
            if frame.is_beat and frame.beat_intensity > 0.3:
                particles.append(
                    {
                        "particle": "NOTE",
                        "x": 0.5,
                        "y": 0.5,
                        "z": 0.5,
                        "count": int(15 * frame.beat_intensity),
                    }
                )

            await self.viz_client.batch_update(self.zone, entities, particles)

        except Exception as e:
            logger.error(f"Minecraft update error: {e}")

    async def run(self, device: Optional[int] = None):
        """
        Start capturing and processing audio.

        Args:
            device: Audio device index (None for auto-detect loopback)
        """
        import numpy as np
        import sounddevice as sd

        self._running = True
        self._loop = asyncio.get_event_loop()
        self._pending_frames = asyncio.Queue(maxsize=10)

        # Find audio device
        if device is None:
            device = self._find_loopback_device()

        if device is None:
            # Use default input as fallback
            logger.warning("Using default input device. For system audio, specify --device")
            device_info = sd.query_devices(kind="input")
        else:
            device_info = sd.query_devices(device)

        logger.info(f"Using audio device: {device_info['name']}")

        # Configure audio stream
        sample_rate = 48000
        channels = 2
        blocksize = 960  # 20ms at 48kHz

        try:
            # Start audio stream
            self._stream = sd.InputStream(
                device=device,
                samplerate=sample_rate,
                channels=channels,
                dtype=np.float32,
                blocksize=blocksize,
                callback=self._audio_callback,
            )

            logger.info("Starting audio capture...")
            self._stream.start()

            # Start frame processor
            processor_task = asyncio.create_task(self._process_frames())

            # Wait for stop signal
            while self._running:
                await asyncio.sleep(0.1)

            # Cleanup
            processor_task.cancel()
            try:
                await processor_task
            except asyncio.CancelledError:
                pass

        except Exception as e:
            logger.error(f"Audio stream error: {e}")
            raise

        finally:
            if self._stream:
                self._stream.stop()
                self._stream.close()

    def stop(self):
        """Stop the capture agent."""
        logger.info("Stopping capture agent...")
        self._running = False

        if self.viz_client:
            # Schedule disconnect
            asyncio.create_task(self._cleanup())

    async def _cleanup(self):
        """Clean up resources."""
        if self.viz_client and self.viz_client.connected:
            await self.viz_client.set_visible(self.zone, False)
            await self.viz_client.disconnect()

        if self.spectrograph:
            self.spectrograph.clear()


async def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="AudioViz Host Capture Agent - Captures system audio for Minecraft visualization"
    )
    parser.add_argument(
        "--host",
        type=str,
        default="192.168.208.1",
        help="Minecraft server IP (default: 192.168.208.1)",
    )
    parser.add_argument("--port", type=int, default=8765, help="WebSocket port (default: 8765)")
    parser.add_argument(
        "--zone", type=str, default="main", help="Visualization zone name (default: main)"
    )
    parser.add_argument(
        "--entities", type=int, default=16, help="Number of visualization entities (default: 16)"
    )
    parser.add_argument(
        "--device",
        type=int,
        default=None,
        help="Audio device index (default: auto-detect loopback)",
    )
    parser.add_argument(
        "--no-spectrograph", action="store_true", help="Disable terminal spectrograph"
    )
    parser.add_argument(
        "--list-devices", action="store_true", help="List available audio devices and exit"
    )

    args = parser.parse_args()

    # Check for sounddevice
    try:
        import sounddevice as sd
    except ImportError:
        logger.error("sounddevice not installed. Run: pip install sounddevice")
        sys.exit(1)

    # List devices mode
    if args.list_devices:
        print("\nAvailable audio devices:")
        print("-" * 60)
        devices = sd.query_devices()
        for i, device in enumerate(devices):
            dtype = ""
            if device["max_input_channels"] > 0:
                dtype += "IN"
            if device["max_output_channels"] > 0:
                dtype += "/OUT" if dtype else "OUT"
            print(f"[{i:2d}] {device['name']}")
            print(f"     {dtype} - {int(device['default_samplerate'])} Hz")
        print("-" * 60)
        print("\nFor system audio capture on Windows, look for:")
        print("  - 'Stereo Mix' or 'What U Hear'")
        print("  - WASAPI loopback devices")
        print("\nUse --device <id> to specify a device")
        sys.exit(0)

    # Create agent
    agent = CaptureAgent(
        minecraft_host=args.host,
        minecraft_port=args.port,
        zone=args.zone,
        entity_count=args.entities,
        show_spectrograph=not args.no_spectrograph,
    )

    # Setup signal handlers
    def signal_handler(sig, frame):
        agent.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    # Connect to Minecraft
    logger.info(f"Connecting to Minecraft at {args.host}:{args.port}...")
    if not await agent.connect_minecraft():
        logger.error("Failed to connect to Minecraft. Make sure the server is running.")
        sys.exit(1)

    # Run capture
    try:
        await agent.run(device=args.device)
    except Exception as e:
        logger.error(f"Capture error: {e}")
        sys.exit(1)
    finally:
        await agent._cleanup()


if __name__ == "__main__":
    asyncio.run(main())
