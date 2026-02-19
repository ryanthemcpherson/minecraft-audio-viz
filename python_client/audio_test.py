"""
Audio visualization test - simulates audio input to test the pipeline.
No Discord required - uses generated audio patterns.
"""

import asyncio
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import click
from viz_client import VizClient

from audio_processor.processor import AudioFrame, AudioProcessor


class AudioVisualizer:
    """Connects audio processor to Minecraft visualization."""

    def __init__(self, viz_client: VizClient, zone: str, entity_count: int = 16):
        self.viz_client = viz_client
        self.zone = zone
        self.entity_count = entity_count
        self.grid_size = int(np.ceil(np.sqrt(entity_count)))

        self.processor = AudioProcessor(
            sample_rate=48000, channels=2, smoothing=0.15, beat_sensitivity=1.2
        )

        self._update_queue = asyncio.Queue()
        self._running = False

    async def start(self):
        """Start the visualizer."""
        # Initialize entity pool
        await self.viz_client.init_pool(self.zone, self.entity_count, "GLOWSTONE")
        await asyncio.sleep(0.5)

        self._running = True

        # Start update task
        asyncio.create_task(self._update_loop())

        self.processor.add_callback(self._on_frame)

    async def stop(self):
        """Stop the visualizer."""
        self._running = False
        self.processor._callbacks.clear()
        await self.viz_client.set_visible(self.zone, False)

    def _on_frame(self, frame: AudioFrame):
        """Called for each processed audio frame."""
        if self._running:
            try:
                self._update_queue.put_nowait(frame)
            except asyncio.QueueFull:
                pass  # Drop frame if queue is full

    async def _update_loop(self):
        """Process update queue and send to Minecraft."""
        while self._running:
            try:
                frame = await asyncio.wait_for(self._update_queue.get(), timeout=0.1)
                await self._send_update(frame)
            except asyncio.TimeoutError:
                continue
            except Exception as e:
                print(f"Update error: {e}")

    async def _send_update(self, frame: AudioFrame):
        """Send visualization update to Minecraft."""
        entities = []

        for i in range(self.entity_count):
            # Grid position
            grid_x = i % self.grid_size
            grid_z = i // self.grid_size

            x = (grid_x + 0.5) / self.grid_size
            z = (grid_z + 0.5) / self.grid_size

            # Map to frequency band
            band_idx = i % len(frame.bands)
            band_value = frame.bands[band_idx]

            # Height based on frequency
            y = band_value * 0.7

            # Scale with amplitude
            scale = 0.2 + band_value * 0.5

            # Boost on beat
            if frame.is_beat:
                y = min(1.0, y * 1.3)
                scale = min(1.0, scale * 1.4)

            entities.append(
                {"id": f"block_{i}", "x": x, "y": y, "z": z, "scale": scale, "visible": True}
            )

        # Particles on beat
        particles = []
        if frame.is_beat and frame.beat_intensity > 0.2:
            particles.append(
                {
                    "particle": "FLAME",
                    "x": 0.5,
                    "y": 0.3 + frame.beat_intensity * 0.5,
                    "z": 0.5,
                    "count": int(15 * frame.beat_intensity),
                }
            )

        await self.viz_client.batch_update(self.zone, entities, particles)

    def process_audio(self, pcm_data: bytes):
        """Process PCM audio data."""
        self.processor.process_pcm(pcm_data)


async def generate_music_pattern(visualizer: AudioVisualizer, duration: float, bpm: float = 120):
    """Generate a music-like audio pattern."""
    sample_rate = 48000
    frame_samples = 960  # 20ms at 48kHz
    beat_interval = 60.0 / bpm

    start_time = time.time()
    last_beat = 0
    t = 0

    print(f"Playing simulated audio at {bpm} BPM for {duration} seconds...")
    print("Watch the Minecraft visualization!")

    while time.time() - start_time < duration:
        # Generate one frame
        frame_t = np.linspace(t, t + frame_samples / sample_rate, frame_samples)

        # Base frequencies (bass band: 40-250Hz)
        bass = np.sin(2 * np.pi * 60 * frame_t) * 0.3
        bass += np.sin(2 * np.pi * 120 * frame_t) * 0.2

        # Mid frequencies
        mid = np.sin(2 * np.pi * 440 * frame_t) * 0.15
        mid += np.sin(2 * np.pi * 880 * frame_t) * 0.1

        # High frequencies
        high = np.sin(2 * np.pi * 2000 * frame_t) * 0.08
        high += np.sin(2 * np.pi * 4000 * frame_t) * 0.05

        # Combine
        samples = bass + mid + high

        # Add beat (kick drum simulation)
        current_time = time.time() - start_time
        if current_time - last_beat >= beat_interval:
            last_beat = current_time
            # Kick envelope
            kick_env = np.exp(-frame_t * 50) * 0.8
            kick = np.sin(2 * np.pi * 80 * frame_t * np.exp(-frame_t * 30)) * kick_env
            samples += kick

        # Modulate amplitude for variation
        envelope = 0.5 + 0.3 * np.sin(current_time * 0.5)
        samples *= envelope

        # Convert to 16-bit stereo PCM
        samples_int = (samples * 32000).astype(np.int16)
        stereo = np.zeros(frame_samples * 2, dtype=np.int16)
        stereo[0::2] = samples_int  # Left
        stereo[1::2] = samples_int  # Right

        # Process
        visualizer.process_audio(stereo.tobytes())

        t += frame_samples / sample_rate
        await asyncio.sleep(0.018)  # Slightly less than 20ms to keep up

    print("Playback complete!")


async def generate_sweep(visualizer: AudioVisualizer, duration: float):
    """Generate a frequency sweep."""
    sample_rate = 48000
    frame_samples = 960
    start_time = time.time()
    t = 0

    print(f"Running frequency sweep for {duration} seconds...")

    while time.time() - start_time < duration:
        progress = (time.time() - start_time) / duration
        freq = 50 + progress * 8000  # Sweep from 50Hz to 8kHz

        frame_t = np.linspace(t, t + frame_samples / sample_rate, frame_samples)
        samples = np.sin(2 * np.pi * freq * frame_t) * 0.5

        samples_int = (samples * 32000).astype(np.int16)
        stereo = np.zeros(frame_samples * 2, dtype=np.int16)
        stereo[0::2] = samples_int
        stereo[1::2] = samples_int

        visualizer.process_audio(stereo.tobytes())

        t += frame_samples / sample_rate
        await asyncio.sleep(0.018)

    print("Sweep complete!")


@click.command()
@click.option("--host", default="192.168.208.1", help="Minecraft WebSocket host")
@click.option("--port", default=8765, help="Minecraft WebSocket port")
@click.option("--zone", default="main", help="Visualization zone")
@click.option("--entities", default=16, help="Number of entities")
@click.option(
    "--pattern",
    "-p",
    default="music",
    type=click.Choice(["music", "sweep"]),
    help="Audio pattern to generate",
)
@click.option("--duration", "-d", default=30.0, help="Duration in seconds")
@click.option("--bpm", default=120.0, help="Beats per minute (for music pattern)")
def main(host, port, zone, entities, pattern, duration, bpm):
    """Test audio visualization with generated audio."""
    asyncio.run(run_test(host, port, zone, entities, pattern, duration, bpm))


async def run_test(host, port, zone, entities, pattern, duration, bpm):
    """Run the audio test."""
    client = VizClient(host, port)

    print(f"Connecting to Minecraft at {host}:{port}...")
    if not await client.connect():
        print("Failed to connect!")
        return

    # Check zone
    zones = await client.get_zones()
    zone_names = [z["name"] for z in zones]
    if zone not in zone_names:
        print(f"Zone '{zone}' not found. Available: {zone_names}")
        await client.disconnect()
        return

    print(f"Using zone: {zone}")

    visualizer = AudioVisualizer(client, zone, entities)
    await visualizer.start()

    try:
        if pattern == "music":
            await generate_music_pattern(visualizer, duration, bpm)
        elif pattern == "sweep":
            await generate_sweep(visualizer, duration)
    except KeyboardInterrupt:
        print("\nInterrupted")
    finally:
        await visualizer.stop()
        await client.disconnect()


if __name__ == "__main__":
    main()
