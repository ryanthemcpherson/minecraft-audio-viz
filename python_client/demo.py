"""
AudioViz Demo Script
Demonstrates various visualization patterns by controlling entities in Minecraft.
"""

import asyncio
import math
import time

import click
import numpy as np
from viz_client import VizClient


class DemoAnimations:
    """Collection of demo animation patterns."""

    def __init__(self, client: VizClient, zone_name: str, entity_count: int):
        self.client = client
        self.zone_name = zone_name
        self.entity_count = entity_count
        self.grid_size = int(math.ceil(math.sqrt(entity_count)))

    def _entity_grid_position(self, index: int) -> tuple[float, float]:
        """Get x, z position for entity in grid (0-1 normalized)."""
        grid_x = index % self.grid_size
        grid_z = index // self.grid_size
        return (grid_x / self.grid_size, grid_z / self.grid_size)

    async def wave_animation(self, duration: float = 5.0, speed: float = 2.0):
        """Sine wave pattern moving across the grid."""
        start_time = time.time()

        while time.time() - start_time < duration:
            t = time.time() - start_time
            entities = []

            for i in range(self.entity_count):
                x, z = self._entity_grid_position(i)

                # Wave based on x position
                wave = math.sin(t * speed + x * 6) * 0.5 + 0.5
                y = wave * 0.8  # Max height 80% of zone

                entities.append(
                    {
                        "id": f"block_{i}",
                        "x": x,
                        "y": y,
                        "z": z,
                        "scale": 0.4 + wave * 0.3,  # Scale with height
                        "visible": True,
                    }
                )

            await self.client.batch_update(self.zone_name, entities)
            await asyncio.sleep(0.05)  # 20 FPS

    async def pulse_animation(self, duration: float = 5.0, speed: float = 3.0):
        """All entities pulse together."""
        start_time = time.time()

        while time.time() - start_time < duration:
            t = time.time() - start_time
            pulse = math.sin(t * speed) * 0.5 + 0.5
            entities = []

            for i in range(self.entity_count):
                x, z = self._entity_grid_position(i)

                entities.append(
                    {
                        "id": f"block_{i}",
                        "x": x,
                        "y": pulse * 0.5,
                        "z": z,
                        "scale": 0.3 + pulse * 0.5,
                        "visible": True,
                    }
                )

            await self.client.batch_update(self.zone_name, entities)
            await asyncio.sleep(0.05)

    async def ripple_animation(self, duration: float = 5.0, speed: float = 3.0):
        """Ripple emanating from center."""
        start_time = time.time()

        while time.time() - start_time < duration:
            t = time.time() - start_time
            entities = []

            for i in range(self.entity_count):
                x, z = self._entity_grid_position(i)

                # Distance from center
                dx = x - 0.5
                dz = z - 0.5
                dist = math.sqrt(dx * dx + dz * dz)

                # Ripple wave
                ripple = math.sin(t * speed - dist * 10) * 0.5 + 0.5
                y = ripple * 0.6

                entities.append(
                    {
                        "id": f"block_{i}",
                        "x": x,
                        "y": y,
                        "z": z,
                        "scale": 0.3 + ripple * 0.4,
                        "visible": True,
                    }
                )

            await self.client.batch_update(self.zone_name, entities)
            await asyncio.sleep(0.05)

    async def spectrum_bars(self, duration: float = 5.0):
        """Simulated audio spectrum bars."""
        start_time = time.time()

        while time.time() - start_time < duration:
            t = time.time() - start_time
            entities = []

            # Only use first row as spectrum bars
            bar_count = min(self.grid_size, self.entity_count)

            for i in range(bar_count):
                # Simulate different frequency bands
                freq = 1 + i * 0.5
                amplitude = abs(math.sin(t * freq)) * 0.7 + 0.1

                # Add some randomness for realism
                amplitude += np.random.uniform(-0.05, 0.05)
                amplitude = max(0.1, min(1.0, amplitude))

                x = i / bar_count
                amplitude * 0.8

                entities.append(
                    {
                        "id": f"block_{i}",
                        "x": x,
                        "y": 0,  # Base at bottom
                        "z": 0.5,
                        "scale": 0.5,
                        "visible": True,
                    }
                )

            await self.client.batch_update(self.zone_name, entities)
            await asyncio.sleep(0.05)

    async def rotating_tower(self, duration: float = 5.0, speed: float = 1.0):
        """Entities arranged in rotating helix."""
        start_time = time.time()

        while time.time() - start_time < duration:
            t = time.time() - start_time
            entities = []

            for i in range(self.entity_count):
                # Vertical position
                y_norm = i / self.entity_count

                # Spiral around center
                angle = y_norm * math.pi * 4 + t * speed
                radius = 0.3

                x = 0.5 + math.cos(angle) * radius
                z = 0.5 + math.sin(angle) * radius
                y = y_norm * 0.8

                entities.append(
                    {"id": f"block_{i}", "x": x, "y": y, "z": z, "scale": 0.3, "visible": True}
                )

            await self.client.batch_update(self.zone_name, entities)
            await asyncio.sleep(0.05)

    async def random_bounce(self, duration: float = 5.0):
        """Entities randomly bounce up and down."""
        # Initialize random phases and speeds for each entity
        phases = np.random.uniform(0, math.pi * 2, self.entity_count)
        speeds = np.random.uniform(2, 5, self.entity_count)

        start_time = time.time()

        while time.time() - start_time < duration:
            t = time.time() - start_time
            entities = []

            for i in range(self.entity_count):
                x, z = self._entity_grid_position(i)

                # Bouncy motion
                bounce = abs(math.sin(t * speeds[i] + phases[i]))
                y = bounce * 0.6

                entities.append(
                    {
                        "id": f"block_{i}",
                        "x": x,
                        "y": y,
                        "z": z,
                        "scale": 0.3 + bounce * 0.2,
                        "visible": True,
                    }
                )

            await self.client.batch_update(self.zone_name, entities)
            await asyncio.sleep(0.05)


@click.command()
@click.option("--host", default="localhost", help="WebSocket host")
@click.option("--port", default=8765, help="WebSocket port")
@click.option("--zone", default="main", help="Zone name to use")
@click.option("--count", default=16, help="Number of entities")
@click.option(
    "--animation",
    "-a",
    default="all",
    type=click.Choice(["wave", "pulse", "ripple", "spectrum", "tower", "bounce", "all"]),
    help="Animation to run",
)
@click.option("--duration", "-d", default=5.0, help="Duration per animation")
@click.option(
    "--init-pool/--no-init-pool", default=True, help="Initialize entity pool before animations"
)
def main(
    host: str, port: int, zone: str, count: int, animation: str, duration: float, init_pool: bool
):
    """Run demo animations on AudioViz Minecraft plugin."""
    asyncio.run(run_demo(host, port, zone, count, animation, duration, init_pool))


async def run_demo(
    host: str, port: int, zone: str, count: int, animation: str, duration: float, init_pool: bool
):
    """Main demo routine."""
    client = VizClient(host, port)

    click.echo(f"Connecting to {host}:{port}...")

    if not await client.connect():
        click.echo("Failed to connect!", err=True)
        return

    # Check if zone exists
    zones = await client.get_zones()
    zone_names = [z["name"] for z in zones]

    if zone not in zone_names:
        click.echo(f"Zone '{zone}' not found. Available zones: {zone_names}")
        click.echo("Create a zone in-game first: /audioviz zone create <name>")
        await client.disconnect()
        return

    click.echo(f"Using zone: {zone}")

    # Initialize entity pool if requested
    if init_pool:
        click.echo(f"Initializing entity pool with {count} blocks...")
        if await client.init_pool(zone, count, "GLOWSTONE"):
            click.echo("Pool initialized!")
            await asyncio.sleep(1)  # Give Minecraft time to spawn entities
        else:
            click.echo("Failed to initialize pool", err=True)

    # Create animation runner
    demo = DemoAnimations(client, zone, count)

    animations = {
        "wave": ("Wave Animation", demo.wave_animation),
        "pulse": ("Pulse Animation", demo.pulse_animation),
        "ripple": ("Ripple Animation", demo.ripple_animation),
        "spectrum": ("Spectrum Bars", demo.spectrum_bars),
        "tower": ("Rotating Tower", demo.rotating_tower),
        "bounce": ("Random Bounce", demo.random_bounce),
    }

    try:
        if animation == "all":
            # Run all animations
            for name, (display_name, anim_func) in animations.items():
                click.echo(f"\nRunning: {display_name}")
                await anim_func(duration)
                await asyncio.sleep(0.5)
        else:
            # Run specific animation
            display_name, anim_func = animations[animation]
            click.echo(f"\nRunning: {display_name}")
            await anim_func(duration)

        click.echo("\nDemo complete!")

    except KeyboardInterrupt:
        click.echo("\nInterrupted")

    finally:
        # Hide all entities
        await client.set_visible(zone, False)
        await client.disconnect()


if __name__ == "__main__":
    main()
