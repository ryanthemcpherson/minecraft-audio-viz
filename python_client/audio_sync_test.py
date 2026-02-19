"""
Audio sync test - generates a WAV file and visualizes it in sync.

Usage:
1. Run this script - it creates a WAV file and starts visualizing
2. When prompted, play the WAV file on Windows
3. Watch the Minecraft visualization sync to the audio!
"""

import asyncio
import os
import sys
import time

import numpy as np
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import click
from audio_processor.processor import AudioFrame, AudioProcessor
from viz_client import VizClient


def generate_test_audio(filepath: str, duration: float = 30.0, bpm: float = 120.0):
    """Generate a test audio file with clear beats and frequencies."""
    sample_rate = 48000
    t = np.linspace(0, duration, int(sample_rate * duration))
    beat_interval = 60.0 / bpm

    print(f"Generating {duration}s audio at {bpm} BPM...")

    # Initialize output
    audio = np.zeros_like(t)

    # Bass drone (bass band: 40-250Hz)
    audio += np.sin(2 * np.pi * 55 * t) * 0.2  # Low bass A1
    audio += np.sin(2 * np.pi * 110 * t) * 0.15  # Bass A2

    # Mid melody (simple arpeggio pattern)
    for i, freq in enumerate([220, 277, 330, 277]):  # A3, C#4, E4, C#4
        # Each note plays for 1 beat, repeating
        beat_num = (t / beat_interval).astype(int) % 4
        mask = beat_num == i
        envelope = np.exp(-((t % beat_interval) * 8)) * mask
        audio += np.sin(2 * np.pi * freq * t) * envelope * 0.2

    # High shimmer
    audio += np.sin(2 * np.pi * 880 * t) * 0.05 * (1 + np.sin(t * 2))
    audio += np.sin(2 * np.pi * 1760 * t) * 0.03 * (1 + np.sin(t * 3))

    # Kick drum on every beat
    for beat_time in np.arange(0, duration, beat_interval):
        beat_start = int(beat_time * sample_rate)
        beat_end = min(beat_start + int(0.15 * sample_rate), len(audio))
        if beat_end > beat_start:
            kick_t = np.linspace(0, 0.15, beat_end - beat_start)
            # Kick: pitch drops from 150Hz to 50Hz with fast decay
            kick_freq = 150 * np.exp(-kick_t * 20) + 50
            kick = np.sin(2 * np.pi * kick_freq * kick_t) * np.exp(-kick_t * 15) * 0.6
            audio[beat_start:beat_end] += kick

    # Snare on beats 2 and 4
    snare_times = np.arange(beat_interval, duration, beat_interval * 2)
    for snare_time in snare_times:
        snare_start = int(snare_time * sample_rate)
        snare_end = min(snare_start + int(0.1 * sample_rate), len(audio))
        if snare_end > snare_start:
            snare_t = np.linspace(0, 0.1, snare_end - snare_start)
            # Snare: noise + tone
            snare = np.random.randn(len(snare_t)) * np.exp(-snare_t * 30) * 0.15
            snare += np.sin(2 * np.pi * 200 * snare_t) * np.exp(-snare_t * 25) * 0.2
            audio[snare_start:snare_end] += snare

    # Hi-hat on every 8th note
    hihat_interval = beat_interval / 2
    for hihat_time in np.arange(0, duration, hihat_interval):
        hh_start = int(hihat_time * sample_rate)
        hh_end = min(hh_start + int(0.05 * sample_rate), len(audio))
        if hh_end > hh_start:
            hh_t = np.linspace(0, 0.05, hh_end - hh_start)
            hihat = np.random.randn(len(hh_t)) * np.exp(-hh_t * 50) * 0.08
            audio[hh_start:hh_end] += hihat

    # Normalize
    audio = audio / np.max(np.abs(audio)) * 0.8

    # Convert to 16-bit stereo
    audio_int = (audio * 32767).astype(np.int16)
    stereo = np.column_stack([audio_int, audio_int])

    # Save WAV
    wavfile.write(filepath, sample_rate, stereo)
    print(f"Saved: {filepath}")

    return duration


class SyncVisualizer:
    """Visualizer that processes a WAV file in sync with playback."""

    def __init__(self, viz_client: VizClient, zone: str, entity_count: int = 25):
        self.viz_client = viz_client
        self.zone = zone
        self.entity_count = entity_count
        self.grid_size = int(np.ceil(np.sqrt(entity_count)))

        self.processor = AudioProcessor(
            sample_rate=48000, channels=2, smoothing=0.15, beat_sensitivity=1.3
        )

        self._running = False

    async def start(self):
        """Initialize visualization."""
        await self.viz_client.init_pool(self.zone, self.entity_count, "SEA_LANTERN")
        await asyncio.sleep(0.5)
        self._running = True

    async def stop(self):
        """Stop visualization."""
        self._running = False
        await self.viz_client.set_visible(self.zone, False)

    async def process_file(self, filepath: str):
        """Process a WAV file and send visualization updates."""
        print(f"Loading {filepath}...")
        sample_rate, data = wavfile.read(filepath)

        if len(data.shape) == 1:
            # Mono - duplicate to stereo
            data = np.column_stack([data, data])

        # Process in 20ms chunks
        chunk_samples = int(sample_rate * 0.02)
        chunk_samples * 2 * 2  # 2 channels, 2 bytes per sample

        total_chunks = len(data) // chunk_samples

        print(f"Processing {total_chunks} chunks...")
        print("=" * 50)
        print("NOW PLAY THE WAV FILE IN WINDOWS!")
        print(f"File: {filepath}")
        print("=" * 50)

        # Give user time to start playback
        print("\nOpen the WAV file now: C:\\Users\\Ryan\\Desktop\\audioviz_test.wav")
        print("Get ready to press PLAY when the countdown reaches 0!")
        for i in range(10, 0, -1):
            print(f"Starting in {i}...")
            await asyncio.sleep(1)

        print("GO!")
        start_time = time.time()

        for i in range(total_chunks):
            if not self._running:
                break

            chunk_start = i * chunk_samples
            chunk_end = chunk_start + chunk_samples
            chunk_data = data[chunk_start:chunk_end]

            # Convert to bytes
            pcm_bytes = chunk_data.astype(np.int16).tobytes()

            # Process audio
            frame = self.processor.process_pcm(pcm_bytes)

            if frame:
                await self._send_update(frame)

            # Maintain real-time sync
            expected_time = (i + 1) * 0.02
            actual_time = time.time() - start_time
            sleep_time = expected_time - actual_time

            if sleep_time > 0:
                await asyncio.sleep(sleep_time)

        print("Visualization complete!")

    async def _send_update(self, frame: AudioFrame):
        """Send visualization update."""
        entities = []

        for i in range(self.entity_count):
            grid_x = i % self.grid_size
            grid_z = i // self.grid_size

            x = (grid_x + 0.5) / self.grid_size
            z = (grid_z + 0.5) / self.grid_size

            # Different rows react to different frequencies
            band_idx = grid_z % len(frame.bands)
            band_value = frame.bands[band_idx]

            # Height based on frequency band
            y = band_value * 0.8

            # Scale
            scale = 0.25 + band_value * 0.5

            # Beat boost
            if frame.is_beat:
                y = min(1.0, y + 0.15)
                scale = min(1.0, scale * 1.3)

            entities.append(
                {"id": f"block_{i}", "x": x, "y": y, "z": z, "scale": scale, "visible": True}
            )

        # Particles on beat
        particles = []
        if frame.is_beat and frame.beat_intensity > 0.3:
            particles.append(
                {
                    "particle": "NOTE",
                    "x": 0.5,
                    "y": 0.5,
                    "z": 0.5,
                    "count": int(10 * frame.beat_intensity),
                }
            )

        await self.viz_client.batch_update(self.zone, entities, particles)


@click.command()
@click.option("--host", default="192.168.208.1", help="Minecraft WebSocket host")
@click.option("--port", default=8765, help="Minecraft WebSocket port")
@click.option("--zone", default="main", help="Visualization zone")
@click.option("--entities", default=25, help="Number of entities")
@click.option("--duration", "-d", default=30.0, help="Audio duration in seconds")
@click.option("--bpm", default=120.0, help="Beats per minute")
def main(host, port, zone, entities, duration, bpm):
    """Generate audio file and visualize in sync."""
    asyncio.run(run_sync_test(host, port, zone, entities, duration, bpm))


async def run_sync_test(host, port, zone, entities, duration, bpm):
    """Run the synchronized test."""
    # Generate audio file
    wav_path = "/mnt/c/Users/Ryan/Desktop/audioviz_test.wav"
    generate_test_audio(wav_path, duration, bpm)

    print("\nAudio file created at: C:\\Users\\Ryan\\Desktop\\audioviz_test.wav")

    # Connect to Minecraft
    client = VizClient(host, port)
    print(f"\nConnecting to Minecraft at {host}:{port}...")

    if not await client.connect():
        print("Failed to connect to Minecraft!")
        return

    # Check zone
    zones = await client.get_zones()
    zone_names = [z["name"] for z in zones]
    if zone not in zone_names:
        print(f"Zone '{zone}' not found. Available: {zone_names}")
        await client.disconnect()
        return

    visualizer = SyncVisualizer(client, zone, entities)
    await visualizer.start()

    try:
        await visualizer.process_file(wav_path)
    except KeyboardInterrupt:
        print("\nInterrupted")
    finally:
        await visualizer.stop()
        await client.disconnect()


if __name__ == "__main__":
    main()
