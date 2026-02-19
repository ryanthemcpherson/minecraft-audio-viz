#!/usr/bin/env python3
"""
Audio Simulator for MCAV Demo
Sends simulated DJ audio frames to the VJ server for demonstration purposes.
"""

import asyncio
import json
import math
import random
import time

try:
    import websockets
except ImportError:
    print("ERROR: websockets not installed. Run: pip install websockets")
    exit(1)


class AudioSimulator:
    """Simulates realistic audio data with beat detection and tempo."""

    def __init__(self, bpm: int = 128, intensity: float = 0.7):
        self.bpm = bpm
        self.intensity = intensity
        self.time = 0.0
        self.beat_phase = 0.0
        self.seq = 0

    def generate_frame(self, dt: float = 0.0465) -> dict:
        """Generate a simulated audio frame.

        Args:
            dt: Time delta in seconds (default ~46.5ms for 21.5 FPS)
        """
        self.time += dt
        self.beat_phase += dt * (self.bpm / 60.0)

        # Wrap beat phase to [0, 1]
        if self.beat_phase >= 1.0:
            self.beat_phase -= 1.0

        # Beat detection: trigger on phase wrap
        beat = self.beat_phase < (dt * (self.bpm / 60.0))

        # Generate 5 frequency bands with musical variation
        # Bass follows kick pattern (4/4 time)
        kick_phase = (self.time * self.bpm / 60.0) % 4.0
        is_kick = kick_phase < 0.1

        bass_base = 0.6 if is_kick else 0.2
        bass = bass_base + random.uniform(-0.1, 0.1) * self.intensity

        # Low-mid follows bass with slight delay
        low_mid = bass * 0.7 + random.uniform(-0.05, 0.05) * self.intensity

        # Mid varies with melody (simulate chord changes every 4 beats)
        chord_phase = (self.time * self.bpm / 60.0 / 4.0) % 1.0
        mid = 0.4 + 0.3 * math.sin(chord_phase * math.pi * 2) * self.intensity
        mid += random.uniform(-0.05, 0.05)

        # High-mid follows melody with hi-hat pattern
        hihat_phase = (self.time * self.bpm / 60.0 * 2) % 1.0
        high_mid = 0.3 + 0.2 * (1.0 if hihat_phase < 0.05 else 0.0)
        high_mid += random.uniform(-0.03, 0.03) * self.intensity

        # High (air) subtle variation
        high = 0.2 + 0.1 * math.sin(self.time * 3) * self.intensity
        high += random.uniform(-0.02, 0.02)

        # Clamp all bands to [0, 1]
        bands = [
            max(0.0, min(1.0, bass)),
            max(0.0, min(1.0, low_mid)),
            max(0.0, min(1.0, mid)),
            max(0.0, min(1.0, high_mid)),
            max(0.0, min(1.0, high)),
        ]

        # Calculate peak (max of all bands)
        peak = max(bands)

        # Integrated bass (smoothed bass intensity)
        i_bass = bass * 0.8

        # Beat intensity
        beat_i = 1.0 if beat else 0.0

        self.seq += 1

        return {
            "type": "dj_audio_frame",
            "v": "1.0.0",
            "bands": bands,
            "peak": peak,
            "beat": beat,
            "beat_i": beat_i,
            "bpm": float(self.bpm),
            "tempo_conf": 0.95,
            "beat_phase": self.beat_phase,
            "seq": self.seq,
            "i_bass": i_bass,
            "i_kick": is_kick,
            "ts": int(time.time() * 1000),
        }


async def run_simulator(
    host: str = "localhost",
    port: int = 9000,
    bpm: int = 128,
    intensity: float = 0.7,
    fps: float = 21.5,
):
    """Run the audio simulator, connecting to VJ server.

    Args:
        host: VJ server host
        port: VJ server DJ port (default 9000)
        bpm: Beats per minute
        intensity: Audio intensity (0.0-1.0)
        fps: Frames per second to send
    """
    uri = f"ws://{host}:{port}"
    print("MCAV Audio Simulator")
    print(f"Connecting to {uri}...")
    print(f"BPM: {bpm}, Intensity: {intensity:.1f}, FPS: {fps:.1f}")
    print()

    simulator = AudioSimulator(bpm=bpm, intensity=intensity)
    frame_delay = 1.0 / fps

    retry_delay = 1.0
    max_retry_delay = 30.0

    while True:
        try:
            async with websockets.connect(uri) as websocket:
                print(f"✓ Connected to VJ server at {uri}")
                retry_delay = 1.0  # Reset retry delay on successful connect

                # Send connect message (for no-auth mode, any name works)
                connect_msg = {
                    "type": "dj_connect",
                    "dj_name": "demo",
                    "connect_code": "DEMO-DEMO-DEMO",
                }
                await websocket.send(json.dumps(connect_msg))

                # Wait for ack
                response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                msg = json.loads(response)

                if msg.get("type") == "dj_connected":
                    print(f"✓ Authenticated as DJ '{msg.get('dj_name', 'demo')}'")
                    print(f"Streaming audio at {fps:.1f} FPS...")
                    print()
                else:
                    print(f"Unexpected response: {msg}")
                    continue

                # Stream audio frames
                frame_count = 0
                start_time = time.time()

                while True:
                    frame = simulator.generate_frame(dt=frame_delay)
                    await websocket.send(json.dumps(frame))

                    frame_count += 1
                    if frame_count % 100 == 0:
                        elapsed = time.time() - start_time
                        actual_fps = frame_count / elapsed
                        print(
                            f"Sent {frame_count} frames | "
                            f"BPM: {bpm} | "
                            f"Beat Phase: {simulator.beat_phase:.2f} | "
                            f"FPS: {actual_fps:.1f}"
                        )

                    await asyncio.sleep(frame_delay)

        except websockets.exceptions.ConnectionClosed:
            print(f"✗ Connection closed. Reconnecting in {retry_delay:.1f}s...")
            await asyncio.sleep(retry_delay)
            retry_delay = min(retry_delay * 2, max_retry_delay)

        except asyncio.TimeoutError:
            print(f"✗ Connection timeout. Retrying in {retry_delay:.1f}s...")
            await asyncio.sleep(retry_delay)
            retry_delay = min(retry_delay * 2, max_retry_delay)

        except ConnectionRefusedError:
            print(f"✗ Connection refused. Is VJ server running? Retrying in {retry_delay:.1f}s...")
            await asyncio.sleep(retry_delay)
            retry_delay = min(retry_delay * 2, max_retry_delay)

        except KeyboardInterrupt:
            print("\n\nShutting down simulator...")
            break

        except Exception as e:
            print(f"✗ Error: {e}. Retrying in {retry_delay:.1f}s...")
            await asyncio.sleep(retry_delay)
            retry_delay = min(retry_delay * 2, max_retry_delay)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="MCAV Audio Simulator")
    parser.add_argument("--host", default="localhost", help="VJ server host")
    parser.add_argument("--port", type=int, default=9000, help="VJ server DJ port")
    parser.add_argument("--bpm", type=int, default=128, help="Beats per minute")
    parser.add_argument("--intensity", type=float, default=0.7, help="Audio intensity (0.0-1.0)")
    parser.add_argument("--fps", type=float, default=21.5, help="Frames per second")

    args = parser.parse_args()

    try:
        asyncio.run(
            run_simulator(
                host=args.host,
                port=args.port,
                bpm=args.bpm,
                intensity=args.intensity,
                fps=args.fps,
            )
        )
    except KeyboardInterrupt:
        print("\nExiting...")
