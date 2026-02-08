"""
Visualization Patterns for AudioViz

Each pattern creates unique 3D formations with dynamic movement.
Designed for electronic music visualization.
"""

import math
import random
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Dict, List, Tuple

# ============================================================================
# Utility Functions
# ============================================================================


def lerp(a: float, b: float, t: float) -> float:
    """Linear interpolation between a and b by factor t."""
    return a + (b - a) * t


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    """Smooth interpolation using cubic Hermite curve."""
    t = clamp((x - edge0) / (edge1 - edge0))
    return t * t * (3.0 - 2.0 * t)


def clamp(value: float, min_val: float = 0.0, max_val: float = 1.0) -> float:
    """Clamp value between min and max."""
    return max(min_val, min(max_val, value))


def rotate_point_3d(
    x: float, y: float, z: float, rx: float, ry: float, rz: float
) -> Tuple[float, float, float]:
    """Rotate a 3D point by angles rx, ry, rz (in radians)."""
    # Rotate around X axis
    cos_x, sin_x = math.cos(rx), math.sin(rx)
    y1 = y * cos_x - z * sin_x
    z1 = y * sin_x + z * cos_x

    # Rotate around Y axis
    cos_y, sin_y = math.cos(ry), math.sin(ry)
    x1 = x * cos_y + z1 * sin_y
    z2 = -x * sin_y + z1 * cos_y

    # Rotate around Z axis
    cos_z, sin_z = math.cos(rz), math.sin(rz)
    x2 = x1 * cos_z - y1 * sin_z
    y2 = x1 * sin_z + y1 * cos_z

    return x2, y2, z2


def fibonacci_sphere(n: int) -> List[Tuple[float, float, float]]:
    """Generate n points evenly distributed on a unit sphere using Fibonacci spiral."""
    points = []
    phi = math.pi * (3.0 - math.sqrt(5.0))  # Golden angle

    for i in range(n):
        y = 1 - (i / float(n - 1)) * 2 if n > 1 else 0  # y goes from 1 to -1
        radius = math.sqrt(1 - y * y)
        theta = phi * i

        x = math.cos(theta) * radius
        z = math.sin(theta) * radius
        points.append((x, y, z))

    return points


def simple_noise(x: float, y: float, z: float, seed: int = 0) -> float:
    """Simple pseudo-random noise function for smooth animation."""
    # Hash-like function for deterministic noise
    n = int(x * 73 + y * 179 + z * 283 + seed * 397)
    n = (n << 13) ^ n
    return 1.0 - ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7FFFFFFF) / 1073741824.0


# ============================================================================
# Data Classes
# ============================================================================


@dataclass
class PatternConfig:
    """Configuration for visualization patterns."""

    entity_count: int = 16
    zone_size: float = 10.0
    beat_boost: float = 1.5
    base_scale: float = 0.2
    max_scale: float = 1.0


@dataclass
class AudioState:
    """Current audio state for pattern calculation."""

    bands: List[float]  # 5 frequency bands (bass, low-mid, mid, high-mid, high)
    amplitude: float  # Overall amplitude 0-1
    is_beat: bool  # Beat detected this frame
    beat_intensity: float  # Beat strength 0-1
    frame: int  # Frame counter


class VisualizationPattern(ABC):
    """Base class for visualization patterns."""

    name: str = "Base"
    description: str = "Base pattern"

    def __init__(self, config: PatternConfig = None):
        self.config = config or PatternConfig()
        self._time = 0.0
        self._beat_accumulator = 0.0

    @abstractmethod
    def calculate_entities(self, audio: AudioState) -> List[Dict[str, Any]]:
        """Calculate entity positions based on audio state."""
        pass

    def update(self, dt: float = 0.016):
        """Update internal time."""
        self._time += dt
        self._beat_accumulator *= 0.9


class StackedTower(VisualizationPattern):
    """
    Vertical tower of blocks that spiral and spread with audio.
    Blocks rotate around central axis and bounce on beats.
    """

    name = "Stacked Tower"
    description = "Spiraling vertical tower - blocks orbit and bounce"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._bounce_wave = []

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        center = 0.5
        n = self.config.entity_count

        # Ensure bounce wave array matches entity count
        while len(self._bounce_wave) < n:
            self._bounce_wave.append(0.0)

        # Rotation speed based on energy
        self._rotation += (0.5 + audio.amplitude * 2.0) * 0.016

        # Trigger bounce wave on beat
        if audio.is_beat:
            self._bounce_wave[0] = 1.0

        # Propagate bounce wave upward
        for i in range(min(n, len(self._bounce_wave)) - 1, 0, -1):
            self._bounce_wave[i] = self._bounce_wave[i] * 0.9 + self._bounce_wave[i - 1] * 0.15
        self._bounce_wave[0] *= 0.85

        for i in range(n):
            # Vertical position - scale to fit within bounds
            # Use normalized position (0-1) then scale to usable range
            normalized_i = i / max(1, n - 1)  # 0 to 1
            base_y = 0.1 + normalized_i * 0.6  # 0.1 to 0.7 base range

            # Add audio-reactive spread
            spread = audio.amplitude * 0.15
            y = base_y + spread * normalized_i

            # Spiral around center - more turns with more blocks
            turns = 2 + n / 16  # More blocks = more spiral turns
            angle = self._rotation + normalized_i * math.pi * 2 * turns
            orbit_radius = 0.08 + audio.bands[i % 5] * 0.2

            x = center + math.cos(angle) * orbit_radius
            z = center + math.sin(angle) * orbit_radius

            # Bounce effect
            bounce = self._bounce_wave[i] if i < len(self._bounce_wave) else 0
            y += bounce * 0.15

            # Scale based on frequency band
            band_idx = i % 5
            scale = self.config.base_scale + audio.bands[band_idx] * 0.6

            if audio.is_beat:
                scale *= 1.5
                y += 0.05

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0.05, min(0.95, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


class ExpandingSphere(VisualizationPattern):
    """
    Blocks arranged on sphere surface that breathes with bass.
    Uses fibonacci sphere distribution for even spacing.
    """

    name = "Expanding Sphere"
    description = "3D sphere that breathes and pulses"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._sphere_points = []
        self._rotation_y = 0.0
        self._rotation_x = 0.0
        self._breath = 0.0

    def _generate_sphere_points(self, n: int) -> List[tuple]:
        """Fibonacci sphere for even distribution."""
        points = []
        phi = math.pi * (3.0 - math.sqrt(5.0))  # Golden angle

        for i in range(n):
            y = 1 - (i / float(n - 1)) * 2  # y goes from 1 to -1
            radius = math.sqrt(1 - y * y)
            theta = phi * i

            x = math.cos(theta) * radius
            z = math.sin(theta) * radius
            points.append((x, y, z))

        return points

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count

        # Initialize sphere points
        if len(self._sphere_points) != n:
            self._sphere_points = self._generate_sphere_points(n)

        # Rotation
        self._rotation_y += 0.3 * 0.016
        self._rotation_x += 0.1 * 0.016

        # Breathing effect
        target_breath = audio.bands[0] * 0.5 + audio.bands[1] * 0.3
        if audio.is_beat:
            target_breath += 0.3
        self._breath += (target_breath - self._breath) * 0.15

        entities = []
        center = 0.5
        base_radius = 0.15 + self._breath * 0.2

        for i in range(n):
            px, py, pz = self._sphere_points[i]

            # Apply Y rotation
            cos_y = math.cos(self._rotation_y)
            sin_y = math.sin(self._rotation_y)
            rx = px * cos_y - pz * sin_y
            rz = px * sin_y + pz * cos_y

            # Apply X rotation
            cos_x = math.cos(self._rotation_x)
            sin_x = math.sin(self._rotation_x)
            ry = py * cos_x - rz * sin_x
            rz2 = py * sin_x + rz * cos_x

            # Scale by radius and center
            band_idx = i % 5
            radius = base_radius + audio.bands[band_idx] * 0.1

            x = center + rx * radius
            y = center + ry * radius
            z = center + rz2 * radius

            scale = self.config.base_scale + audio.bands[band_idx] * 0.4
            if audio.is_beat:
                scale *= 1.4

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


class DNAHelix(VisualizationPattern):
    """
    Double helix that rotates and stretches with audio.
    Two intertwined spirals rising vertically.
    """

    name = "DNA Helix"
    description = "Double helix spiral - rotates and stretches"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._stretch = 1.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Rotation speed based on energy
        speed = 1.0 + audio.amplitude * 2.0
        if audio.is_beat:
            speed *= 1.5
        self._rotation += speed * 0.016

        # Stretch based on bass
        target_stretch = 0.8 + audio.bands[0] * 0.6 + audio.bands[1] * 0.4
        self._stretch += (target_stretch - self._stretch) * 0.1

        # Helix parameters
        radius = 0.15 + audio.amplitude * 0.1
        0.08 * self._stretch

        for i in range(n):
            # Alternate between two helixes
            helix = i % 2
            idx = i // 2

            # Position along helix
            t = idx / (n / 2) * math.pi * 3  # 3 full turns
            angle = t + self._rotation + (helix * math.pi)  # Offset second helix by 180 degrees

            # Helix coordinates
            x = center + math.cos(angle) * radius
            z = center + math.sin(angle) * radius
            y = 0.1 + (idx / (n / 2)) * 0.8  # Spread vertically

            # Pulse radius with band
            band_idx = idx % 5
            pulse = audio.bands[band_idx] * 0.05
            x += math.cos(angle) * pulse
            z += math.sin(angle) * pulse

            scale = self.config.base_scale + audio.bands[band_idx] * 0.4
            if audio.is_beat:
                scale *= 1.3

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


class Supernova(VisualizationPattern):
    """
    Blocks explode outward on beats, slowly drift back to center.
    3D explosion in all directions.
    """

    name = "Supernova"
    description = "Explosive burst on beats - 3D shockwave"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._positions = []  # (r, theta, phi) spherical coords
        self._velocities = []
        self._target_radius = 0.05

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count

        # Initialize random directions
        if len(self._positions) != n:
            self._positions = []
            self._velocities = []
            for i in range(n):
                theta = random.uniform(0, math.pi * 2)
                phi = random.uniform(0, math.pi)
                self._positions.append([0.05, theta, phi])  # [radius, theta, phi]
                self._velocities.append(0.0)

        # Beat triggers explosion
        if audio.is_beat and audio.beat_intensity > 0.3:
            for i in range(n):
                self._velocities[i] = 0.8 + random.uniform(0, 0.4)
                # Randomize direction slightly
                self._positions[i][1] += random.uniform(-0.3, 0.3)
                self._positions[i][2] += random.uniform(-0.2, 0.2)

        entities = []
        center = 0.5

        for i in range(n):
            # Update position
            self._positions[i][0] += self._velocities[i] * 0.016
            self._velocities[i] *= 0.96  # Drag

            # Gravity back to center
            if self._positions[i][0] > 0.05:
                self._velocities[i] -= 0.02

            # Clamp radius
            self._positions[i][0] = max(0.02, min(0.45, self._positions[i][0]))

            # Spherical to cartesian
            r = self._positions[i][0]
            theta = self._positions[i][1]
            phi = self._positions[i][2]

            x = center + r * math.sin(phi) * math.cos(theta)
            y = center + r * math.cos(phi)
            z = center + r * math.sin(phi) * math.sin(theta)

            band_idx = i % 5
            scale = (
                self.config.base_scale
                + audio.bands[band_idx] * 0.3
                + abs(self._velocities[i]) * 0.5
            )

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


class FloatingPlatforms(VisualizationPattern):
    """
    6 platforms (one per frequency band) floating at different heights.
    Each platform rotates and bounces independently.
    """

    name = "Floating Platforms"
    description = "6 levitating platforms - one per frequency"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._platform_y = [0.2, 0.35, 0.5, 0.65, 0.75]
        self._bounce = [0.0] * 5

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        self._rotation += 0.5 * 0.016

        # Update platform heights based on bands
        for band in range(5):
            target_y = 0.15 + band * 0.14 + audio.bands[band] * 0.15
            self._platform_y[band] += (target_y - self._platform_y[band]) * 0.1

            # Bounce on beat
            if audio.is_beat and band < 3:
                self._bounce[band] = 0.15
            self._bounce[band] *= 0.9

        # Distribute entities across 5 platforms
        entities_per_platform = max(1, n // 5)

        for band in range(5):
            platform_angle = self._rotation + band * (math.pi * 2 / 5)
            platform_radius = 0.2 + audio.bands[band] * 0.1

            for j in range(entities_per_platform):
                entity_idx = band * entities_per_platform + j
                if entity_idx >= n:
                    break

                # Spread blocks within platform
                offset_angle = (j / entities_per_platform) * math.pi * 0.5 - math.pi * 0.25
                angle = platform_angle + offset_angle

                # Position
                spread = 0.03 + audio.bands[band] * 0.02
                x = center + math.cos(angle) * (platform_radius + j * spread * 0.3)
                z = center + math.sin(angle) * (platform_radius + j * spread * 0.3)
                y = self._platform_y[band] + self._bounce[band]

                scale = self.config.base_scale + audio.bands[band] * 0.5

                entities.append(
                    {
                        "id": f"block_{entity_idx}",
                        "x": max(0, min(1, x)),
                        "y": max(0, min(1, y)),
                        "z": max(0, min(1, z)),
                        "scale": min(self.config.max_scale, scale),
                        "band": band,
                        "visible": True,
                    }
                )

        self.update()
        return entities


class AtomModel(VisualizationPattern):
    """
    Central nucleus with electrons orbiting on multiple planes.
    3 orbital planes at different angles.
    """

    name = "Atom Model"
    description = "Nucleus + electrons on 3D orbital planes"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._orbit_angles = []
        self._nucleus_pulse = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Ensure orbit angles array matches entity count
        while len(self._orbit_angles) < n:
            self._orbit_angles.append(random.uniform(0, math.pi * 2))

        # Nucleus pulse
        if audio.is_beat:
            self._nucleus_pulse = 1.0
        self._nucleus_pulse *= 0.9

        # Nucleus: first 4 blocks clustered at center
        nucleus_count = min(4, n)
        nucleus_spread = 0.03 + self._nucleus_pulse * 0.05 + audio.bands[0] * 0.03

        for i in range(nucleus_count):
            angle = (i / nucleus_count) * math.pi * 2
            x = center + math.cos(angle) * nucleus_spread
            z = center + math.sin(angle) * nucleus_spread
            y = center + math.sin(angle * 2) * nucleus_spread * 0.5

            scale = self.config.base_scale + audio.bands[0] * 0.4 + self._nucleus_pulse * 0.3

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": 0,
                    "visible": True,
                }
            )

        # Electrons: remaining blocks on 3 orbital planes
        electron_count = n - nucleus_count
        electrons_per_orbit = max(1, electron_count // 3)

        orbit_tilts = [(0, 0), (math.pi / 3, 0), (0, math.pi / 3)]  # (tilt_x, tilt_z)
        orbit_speeds = [1.0, 1.3, 0.8]

        for orbit in range(3):
            tilt_x, tilt_z = orbit_tilts[orbit]
            speed = orbit_speeds[orbit] * (1 + audio.amplitude)

            if audio.is_beat:
                speed *= 1.5

            for j in range(electrons_per_orbit):
                entity_idx = nucleus_count + orbit * electrons_per_orbit + j
                if entity_idx >= n:
                    break

                # Update orbit angle
                self._orbit_angles[entity_idx] += speed * 0.016

                angle = self._orbit_angles[entity_idx] + (j / electrons_per_orbit) * math.pi * 2
                radius = 0.2 + orbit * 0.08

                # Base position on XZ plane
                px = math.cos(angle) * radius
                py = 0
                pz = math.sin(angle) * radius

                # Apply tilts
                # Tilt around X axis
                cos_tx = math.cos(tilt_x)
                sin_tx = math.sin(tilt_x)
                py2 = py * cos_tx - pz * sin_tx
                pz2 = py * sin_tx + pz * cos_tx

                # Tilt around Z axis
                cos_tz = math.cos(tilt_z)
                sin_tz = math.sin(tilt_z)
                px2 = px * cos_tz - py2 * sin_tz
                py3 = px * sin_tz + py2 * cos_tz

                x = center + px2
                y = center + py3
                z = center + pz2

                band_idx = (orbit + 2) % 5
                scale = self.config.base_scale + audio.bands[band_idx] * 0.3

                entities.append(
                    {
                        "id": f"block_{entity_idx}",
                        "x": max(0, min(1, x)),
                        "y": max(0, min(1, y)),
                        "z": max(0, min(1, z)),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True,
                    }
                )

        self.update()
        return entities


class Fountain(VisualizationPattern):
    """
    Blocks shoot up from center and arc back down.
    Continuous fountain effect with beat bursts.
    """

    name = "Fountain"
    description = "Upward spray with gravity arcs"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._particles = []  # [(x, y, z, vx, vy, vz, age, spin)]

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count

        # Initialize particles
        if len(self._particles) != n:
            self._particles = []
            for i in range(n):
                self._particles.append(self._spawn_particle(audio, i))

        entities = []
        gravity = 0.015
        drag = 0.985

        for i in range(n):
            p = self._particles[i]
            x, y, z, vx, vy, vz, age, spin = p

            # Update physics
            vy -= gravity
            age += 0.016

            # Add gentle swirl and drag for a more fluid fountain
            swirl = 0.02 + audio.bands[2] * 0.05
            vx += -(z - 0.5) * swirl
            vz += (x - 0.5) * swirl

            spin_speed = (0.6 + audio.bands[3] * 1.2) * spin
            cos_s = math.cos(spin_speed * 0.016)
            sin_s = math.sin(spin_speed * 0.016)
            vx, vz = (vx * cos_s - vz * sin_s, vx * sin_s + vz * cos_s)

            vx *= drag
            vz *= drag
            x += vx * 0.016 * 60
            y += vy * 0.016 * 60
            z += vz * 0.016 * 60

            # Respawn if below ground or on beat
            if y < 0 or (audio.is_beat and random.random() < 0.3):
                x, y, z, vx, vy, vz, age, spin = self._spawn_particle(audio, i)

            self._particles[i] = (x, y, z, vx, vy, vz, age, spin)

            band_idx = i % 5
            scale = self.config.base_scale + audio.bands[band_idx] * 0.4

            # Scale based on height (bigger at peak)
            height_scale = 1.0 + (y - 0.5) * 0.5 if y > 0.5 else 1.0
            scale *= height_scale
            scale += min(0.3, age * 0.1)

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities

    def _spawn_particle(self, audio: AudioState, idx: int) -> tuple:
        """Spawn a new fountain particle."""
        center = 0.5

        # Random upward velocity
        speed = 0.015 + audio.amplitude * 0.02
        if audio.is_beat:
            speed *= 1.5

        angle = random.uniform(0, math.pi * 2)
        spread = 0.003 + audio.bands[idx % 5] * 0.003

        vx = math.cos(angle) * spread
        vz = math.sin(angle) * spread
        vy = speed + random.uniform(0, 0.008)

        spawn_radius = 0.02 + audio.bands[1] * 0.03
        x = center + math.cos(angle) * spawn_radius
        z = center + math.sin(angle) * spawn_radius
        spin = random.uniform(-1.0, 1.0)

        return (x, 0.05, z, vx, vy, vz, 0.0, spin)


class BreathingCube(VisualizationPattern):
    """
    Blocks at vertices of a rotating cube that breathes.
    Expands and contracts with rhythm.
    """

    name = "Breathing Cube"
    description = "Rotating cube vertices - expands with beats"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation_x = 0.0
        self._rotation_y = 0.0
        self._rotation_z = 0.0
        self._breath = 0.5
        self._points = []

    def _generate_cube_points(self, n: int) -> List[tuple]:
        """Generate points on cube surface for any count."""
        points = []

        # Always include 8 vertices
        vertices = [
            (-1, -1, -1),
            (-1, -1, 1),
            (-1, 1, -1),
            (-1, 1, 1),
            (1, -1, -1),
            (1, -1, 1),
            (1, 1, -1),
            (1, 1, 1),
        ]
        points.extend(vertices)

        if n <= 8:
            return points[:n]

        # Add edge midpoints (12 edges)
        edge_mids = [
            (0, -1, -1),
            (0, 1, -1),
            (0, -1, 1),
            (0, 1, 1),  # X edges
            (-1, 0, -1),
            (1, 0, -1),
            (-1, 0, 1),
            (1, 0, 1),  # Y edges
            (-1, -1, 0),
            (1, -1, 0),
            (-1, 1, 0),
            (1, 1, 0),  # Z edges
        ]
        points.extend(edge_mids)

        if n <= 20:
            return points[:n]

        # Add face centers (6 faces)
        face_centers = [
            (0, 0, -1),
            (0, 0, 1),  # Front/Back
            (-1, 0, 0),
            (1, 0, 0),  # Left/Right
            (0, -1, 0),
            (0, 1, 0),  # Bottom/Top
        ]
        points.extend(face_centers)

        # If we need more, add subdivided points on faces
        while len(points) < n:
            # Add random points on cube surface
            face = random.randint(0, 5)
            u, v = random.uniform(-0.8, 0.8), random.uniform(-0.8, 0.8)
            if face == 0:
                points.append((u, v, -1))
            elif face == 1:
                points.append((u, v, 1))
            elif face == 2:
                points.append((-1, u, v))
            elif face == 3:
                points.append((1, u, v))
            elif face == 4:
                points.append((u, -1, v))
            else:
                points.append((u, 1, v))

        return points[:n]

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Generate points if needed
        if len(self._points) != n:
            self._points = self._generate_cube_points(n)

        # Rotation speeds - more reactive
        self._rotation_y += (0.5 + audio.amplitude * 1.5) * 0.016
        self._rotation_x += (0.2 + audio.bands[2] * 0.5) * 0.016
        self._rotation_z += (0.1 + audio.bands[4] * 0.3) * 0.016

        # Breathing - more dramatic
        target_breath = 0.15 + audio.bands[0] * 0.15 + audio.bands[1] * 0.1
        if audio.is_beat:
            target_breath += 0.15
        self._breath += (target_breath - self._breath) * 0.2

        for i in range(n):
            px, py, pz = self._points[i]

            # Apply rotations
            # Y rotation
            cos_y = math.cos(self._rotation_y)
            sin_y = math.sin(self._rotation_y)
            rx = px * cos_y - pz * sin_y
            rz = px * sin_y + pz * cos_y

            # X rotation
            cos_x = math.cos(self._rotation_x)
            sin_x = math.sin(self._rotation_x)
            ry = py * cos_x - rz * sin_x
            rz2 = py * sin_x + rz * cos_x

            # Z rotation
            cos_z = math.cos(self._rotation_z)
            sin_z = math.sin(self._rotation_z)
            rx2 = rx * cos_z - ry * sin_z
            ry2 = rx * sin_z + ry * cos_z

            # Scale by breath and center
            x = center + rx2 * self._breath
            y = center + ry2 * self._breath
            z = center + rz2 * self._breath

            band_idx = i % 5
            scale = self.config.base_scale + audio.bands[band_idx] * 0.4

            if audio.is_beat:
                scale *= 1.4

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


class Mushroom(VisualizationPattern):
    """
    Psychedelic Amanita mushroom - classic toadstool with spots.
    Features breathing animation, gills, spiraling stem, and floating spores.
    """

    name = "Mushroom"
    description = "Psychedelic toadstool with spots, gills, and spores"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._pulse = 0.0
        self._glow = 0.0
        self._breathe = 0.0
        self._breathe_dir = 1
        self._wobble = 0.0
        self._spore_time = 0.0
        self._grow = 1.0
        self._cap_tilt = 0.0
        self._stem_sway = 0.0
        # Pre-generate spot positions (consistent each frame)
        self._spot_angles = [i * 2.39996 for i in range(7)]  # Golden angle spacing
        self._spot_phis = [0.3 + (i % 3) * 0.2 for i in range(7)]

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Rotation - faster with amplitude
        self._rotation += (0.2 + audio.amplitude * 0.4) * 0.016

        # Breathing animation (slow oscillation)
        self._breathe += 0.02 * self._breathe_dir
        if self._breathe > 1.0:
            self._breathe_dir = -1
        elif self._breathe < 0.0:
            self._breathe_dir = 1

        # Wobble on beat
        if audio.is_beat:
            self._pulse = 1.0
            self._glow = 1.0
            self._wobble = 0.15 * audio.amplitude
            self._grow = 1.15
            self._cap_tilt = 0.08 + audio.amplitude * 0.08
        self._pulse *= 0.92
        self._glow *= 0.93
        self._wobble *= 0.9
        self._grow = 1.0 + (self._grow - 1.0) * 0.95
        self._cap_tilt *= 0.92

        self._spore_time += 0.016

        # Calculate wobble offset
        wobble_x = math.sin(self._spore_time * 2) * self._wobble
        wobble_z = math.cos(self._spore_time * 2.5) * self._wobble * 0.7
        self._stem_sway = math.sin(self._spore_time * 1.1) * 0.03 + audio.bands[1] * 0.04
        cap_offset_x = math.sin(self._rotation * 0.8) * self._cap_tilt + self._stem_sway * 0.6
        cap_offset_z = math.cos(self._rotation * 0.6) * self._cap_tilt * 0.8 + self._stem_sway * 0.3

        # Allocate entities: 20% stem, 42% cap, 15% gills, 10% spots, 7% rim, rest spores
        stem_count = max(6, n // 5)
        cap_count = max(10, int(n * 0.42))
        gill_count = max(6, int(n * 0.15))
        spot_count = max(5, n // 10)
        rim_count = max(4, n // 14)
        spore_count = n - stem_count - cap_count - gill_count - spot_count - rim_count
        if spore_count < 0:
            cap_count = max(6, cap_count + spore_count)
            spore_count = 0

        entity_idx = 0
        breathe_scale = 1.0 + self._breathe * 0.05

        # === STEM (organic with varied heights) ===
        stem_radius = 0.06 * breathe_scale + self._pulse * 0.015
        stem_height = 0.34 * self._grow

        # Place stem blocks with varied heights for organic look
        for i in range(stem_count):
            # Use golden angle for even distribution around cylinder
            golden_angle = 2.39996323  # Golden angle in radians
            angle_base = i * golden_angle

            # Vertical position with variation - not flat rings
            # Base position spreads blocks along full stem height
            base_t = i / stem_count
            # Add sinusoidal variation based on angle for organic staggering
            height_variation = math.sin(angle_base * 3) * 0.04 + math.cos(angle_base * 5) * 0.02
            y_t = base_t + height_variation
            y_t = max(0, min(1, y_t))  # Clamp

            ring_y = 0.06 + y_t * stem_height

            # Radius varies with height: bulge at base, taper at top
            taper = 1.0 - y_t * 0.3 + math.sin(y_t * math.pi) * 0.2
            # Also vary radius slightly per block for texture
            radius_variation = 1.0 + math.sin(i * 1.7) * 0.1
            current_radius = stem_radius * taper * radius_variation

            # Spiral twist increases with height
            twist = y_t * math.pi * 0.6
            angle = self._rotation + twist + angle_base

            sway_amount = self._stem_sway * (0.2 + y_t * 0.8)
            x = center + math.cos(angle) * current_radius + wobble_x * y_t + sway_amount
            z = center + math.sin(angle) * current_radius + wobble_z * y_t + sway_amount * 0.6
            y = ring_y

            # Vary band assignment for color variation in stem
            band_idx = 1 + (i % 2)  # Alternate between bands 1 and 2
            base_scale = self.config.base_scale * (
                0.6 + y_t * 0.3
            )  # Smaller at base, larger at top
            scale = base_scale + audio.bands[band_idx] * 0.2

            entities.append(
                {
                    "id": f"block_{entity_idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )
            entity_idx += 1

        # === CAP (dome with classic toadstool shape) ===
        cap_base_y = 0.08 + stem_height
        cap_radius = (
            (0.22 + self._pulse * 0.06 + audio.bands[0] * 0.04) * breathe_scale * self._grow
        )
        layers = max(4, int(math.sqrt(cap_count)))
        points_placed = 0

        for layer in range(layers):
            if points_placed >= cap_count:
                break

            layer_t = layer / max(1, layers - 1)
            # Classic toadstool: flatter on top, curves down at edges
            phi = layer_t * (math.pi * 0.55)  # Just past hemisphere
            layer_radius = cap_radius * math.sin(phi)
            # Flatten top, curve down at edge
            height_factor = math.cos(phi) * 0.5 + (1 - layer_t) * 0.15
            ripple = math.sin(self._rotation * 1.8 + layer * 0.8) * audio.bands[2] * 0.025
            layer_y = cap_base_y + cap_radius * height_factor + ripple

            points_this_layer = max(4, int(6 + layer * 4))

            for j in range(points_this_layer):
                if points_placed >= cap_count:
                    break

                angle = self._rotation * 0.4 + (j / points_this_layer) * math.pi * 2
                x = center + math.cos(angle) * layer_radius + wobble_x + cap_offset_x
                z = center + math.sin(angle) * layer_radius + wobble_z + cap_offset_z
                y = layer_y

                band_idx = 0  # Cap uses bass
                scale = (
                    self.config.base_scale * 1.1 + audio.bands[band_idx] * 0.4 + self._glow * 0.25
                )

                entities.append(
                    {
                        "id": f"block_{entity_idx}",
                        "x": max(0, min(1, x)),
                        "y": max(0, min(1, y)),
                        "z": max(0, min(1, z)),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True,
                    }
                )
                entity_idx += 1
                points_placed += 1

        # === RIM (lip around cap edge) ===
        rim_radius = cap_radius * 0.92
        rim_drop = 0.02 + audio.bands[2] * 0.03
        for r in range(rim_count):
            angle = self._rotation * 0.5 + (r / rim_count) * math.pi * 2
            lip_wave = math.sin(angle * 2 + self._rotation) * 0.01
            x = center + math.cos(angle) * rim_radius + cap_offset_x
            z = center + math.sin(angle) * rim_radius + cap_offset_z
            y = cap_base_y + cap_radius * 0.12 - rim_drop + lip_wave

            band_idx = 2
            scale = self.config.base_scale * 0.8 + audio.bands[band_idx] * 0.25 + self._glow * 0.2

            entities.append(
                {
                    "id": f"block_{entity_idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )
            entity_idx += 1

        # === GILLS (radial lines under cap) ===
        gill_y = cap_base_y - 0.02 - audio.bands[1] * 0.015
        num_gill_lines = max(4, gill_count // 3)
        points_per_gill = gill_count // num_gill_lines

        for g in range(num_gill_lines):
            gill_angle = self._rotation * 0.4 + (g / num_gill_lines) * math.pi * 2

            for p in range(points_per_gill):
                if entity_idx >= stem_count + cap_count + gill_count:
                    break

                # Gills extend from stem to cap edge
                t = (p + 1) / (points_per_gill + 1)
                r = stem_radius + t * (cap_radius * 0.85 - stem_radius)

                x = center + math.cos(gill_angle) * r + wobble_x + cap_offset_x * 0.4
                z = center + math.sin(gill_angle) * r + wobble_z + cap_offset_z * 0.4
                y = gill_y - t * (0.03 + audio.bands[1] * 0.02)  # Slight droop

                band_idx = 3  # Gills use mid-high
                scale = self.config.base_scale * 0.5 + audio.bands[band_idx] * 0.22

                entities.append(
                    {
                        "id": f"block_{entity_idx}",
                        "x": max(0, min(1, x)),
                        "y": max(0, min(1, y)),
                        "z": max(0, min(1, z)),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True,
                    }
                )
                entity_idx += 1

        # === SPOTS (white dots on cap - classic Amanita) ===
        for s in range(min(spot_count, len(self._spot_angles))):
            spot_phi = self._spot_phis[s] * (math.pi * 0.4)
            spot_r = cap_radius * 0.85 * math.sin(spot_phi)
            spot_y = cap_base_y + cap_radius * math.cos(spot_phi) * 0.5 + 0.02

            spot_angle = self._rotation * 0.4 + self._spot_angles[s]
            x = center + math.cos(spot_angle) * spot_r + wobble_x + cap_offset_x
            z = center + math.sin(spot_angle) * spot_r + wobble_z + cap_offset_z
            y = spot_y

            band_idx = 5  # Spots use high freq
            scale = self.config.base_scale * 0.9 + self._glow * 0.4 + audio.bands[4] * 0.3

            entities.append(
                {
                    "id": f"block_{entity_idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )
            entity_idx += 1

        # === SPORES (floating particles rising up) ===
        for sp in range(spore_count):
            # Each spore has unique phase
            phase = sp * 1.618 + self._spore_time
            spore_life = (phase % 3.0) / 3.0  # 0-1 lifecycle

            # Spiral upward from cap
            spore_angle = phase * 2.0 + sp
            spore_r = 0.05 + spore_life * 0.15 + math.sin(phase * 3) * 0.03
            spore_y = cap_base_y + 0.1 + spore_life * 0.4 + audio.bands[4] * 0.05

            x = center + math.cos(spore_angle) * spore_r + wobble_x * 0.5 + cap_offset_x * 0.6
            z = center + math.sin(spore_angle) * spore_r + wobble_z * 0.5 + cap_offset_z * 0.6
            y = spore_y

            band_idx = 4  # Spores use high-mid
            # Fade in then out
            fade = math.sin(spore_life * math.pi)
            scale = self.config.base_scale * 0.3 * fade + audio.bands[band_idx] * 0.15 * fade

            entities.append(
                {
                    "id": f"block_{entity_idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, max(0.01, scale)),
                    "band": band_idx,
                    "visible": fade > 0.1,
                }
            )
            entity_idx += 1

        self.update()
        return entities


class Skull(VisualizationPattern):
    """
    Clean 3D skull using slice-based construction.
    Uses horizontal cross-sections at different Y levels for accurate anatomy.
    Features animated jaw, glowing eyes, and beat reactivity.
    """

    name = "Skull"
    description = "Clean anatomical skull with animated jaw and glowing eyes"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._jaw_open = 0.0
        self._eye_glow = 0.0
        self._breathe = 0.0
        self._breathe_dir = 1
        self._head_bob = 0.0
        self._time = 0.0
        self._skull_points = None
        self._beat_intensity = 0.0

    def _skull_slice(self, y_norm: float, front_only: bool = False) -> List[tuple]:
        """
        Get skull cross-section outline at normalized Y position (0=bottom, 1=top).
        Returns list of (x, z, part_type) tuples defining the outline.
        """
        points = []

        # Skull proportions (normalized coordinates, will be scaled)
        # Front is -Z direction

        if y_norm < 0.15:
            # Bottom of jaw / chin area
            t = y_norm / 0.15
            width = 0.08 + t * 0.04
            depth = 0.06 + t * 0.02
            # Horseshoe shape
            for angle in range(-140, 141, 20):
                rad = math.radians(angle)
                x = math.sin(rad) * width
                z = -math.cos(rad) * depth - 0.04
                points.append((x, z, "jaw"))

        elif y_norm < 0.25:
            # Lower jaw with teeth
            t = (y_norm - 0.15) / 0.10
            width = 0.12 + t * 0.02
            depth = 0.08 + t * 0.01
            for angle in range(-130, 131, 15):
                rad = math.radians(angle)
                x = math.sin(rad) * width
                z = -math.cos(rad) * depth - 0.02
                points.append((x, z, "jaw"))

        elif y_norm < 0.35:
            # Upper jaw / maxilla with teeth
            t = (y_norm - 0.25) / 0.10
            width = 0.14 + t * 0.01
            depth = 0.10
            # Front arc (teeth area)
            for angle in range(-100, 101, 12):
                rad = math.radians(angle)
                x = math.sin(rad) * width
                z = -math.cos(rad) * depth
                points.append((x, z, "face"))
            # Sides connecting to back
            if not front_only:
                for side in [-1, 1]:
                    for d in range(3):
                        x = side * width
                        z = -depth + 0.02 + d * 0.04
                        points.append((x, z, "face"))

        elif y_norm < 0.45:
            # Nose cavity and cheekbones
            t = (y_norm - 0.35) / 0.10
            width = 0.15
            # Nose hole (center front)
            nose_width = 0.03 * (1 - t * 0.5)
            for nx in [-nose_width, 0, nose_width]:
                points.append((nx, -0.11, "nose"))
            # Cheekbones (sides)
            for side in [-1, 1]:
                points.append((side * 0.14, -0.08, "cheek"))
                points.append((side * 0.16, -0.04, "cheek"))
                points.append((side * 0.15, 0.0, "face"))

        elif y_norm < 0.55:
            # Eye sockets
            t = (y_norm - 0.45) / 0.10
            # Eye socket outlines
            for side in [-1, 1]:
                eye_cx = side * 0.065
                eye_cz = -0.08
                # Oval socket
                for angle in range(0, 360, 30):
                    rad = math.radians(angle)
                    ex = eye_cx + math.cos(rad) * 0.04
                    ez = eye_cz + math.sin(rad) * 0.025
                    points.append((ex, ez, "eye"))
            # Bridge of nose between eyes
            points.append((0, -0.10, "nose"))
            # Outer face contour
            for side in [-1, 1]:
                points.append((side * 0.15, -0.05, "face"))
                points.append((side * 0.16, 0.0, "face"))

        elif y_norm < 0.65:
            # Brow ridge and upper eye sockets
            t = (y_norm - 0.55) / 0.10
            # Prominent brow ridge
            for bx in range(-12, 13, 3):
                x = bx * 0.01
                z = -0.10 - abs(x) * 0.3  # Curved forward
                points.append((x, z, "brow"))
            # Temple sides
            for side in [-1, 1]:
                points.append((side * 0.15, -0.03, "temple"))
                points.append((side * 0.14, 0.02, "cranium"))

        elif y_norm < 0.80:
            # Forehead and temporal region
            t = (y_norm - 0.65) / 0.15
            width = 0.14 - t * 0.02
            # Rounded forehead
            for angle in range(-160, 161, 15):
                rad = math.radians(angle)
                x = math.sin(rad) * width
                z = -math.cos(rad) * 0.12 * (1 - t * 0.3)
                points.append((x, z, "cranium"))

        else:
            # Top of skull (dome)
            t = (y_norm - 0.80) / 0.20
            radius = 0.12 * (1 - t * 0.7)
            if radius > 0.01:
                for angle in range(0, 360, 25):
                    rad = math.radians(angle)
                    x = math.cos(rad) * radius
                    z = math.sin(rad) * radius * 0.9
                    points.append((x, z, "cranium"))
            else:
                points.append((0, 0, "cranium"))

        return points

    def _generate_skull_points(self, n: int) -> List[tuple]:
        """Generate skull points using slice-based construction."""
        all_points = []

        # Use more slices for cleaner shape - at least 20 slices
        num_slices = max(20, n // 8)

        # Generate skull shell points
        for i in range(num_slices):
            y_norm = i / (num_slices - 1)
            slice_points = self._skull_slice(y_norm)

            for x, z, part_type in slice_points:
                all_points.append((part_type, x, y_norm, z))

        # Add extra density to key features
        # Extra eye detail
        for side in [-1, 1]:
            eye_cx, eye_cy = side * 0.065, 0.50
            for i in range(8):
                angle = i * math.pi * 2 / 8
                x = eye_cx + math.cos(angle) * 0.025
                z = -0.08 + math.sin(angle) * 0.015
                all_points.append(("eye_inner", x, eye_cy, z))

        # Extra teeth detail
        for i in range(10):
            t = i / 9
            x = -0.07 + t * 0.14
            all_points.append(("teeth_upper", x, 0.30, -0.095))
            all_points.append(("teeth_lower", x, 0.22, -0.09))

        # Scale to fit n points - take evenly distributed subset if too many
        if len(all_points) > n:
            step = len(all_points) / n
            selected = []
            for i in range(n):
                idx = int(i * step)
                if idx < len(all_points):
                    selected.append(all_points[idx])
            return selected
        else:
            # Duplicate points if we need more
            result = all_points[:]
            idx = 0
            while len(result) < n:
                # Add slight variation to duplicates
                orig = all_points[idx % len(all_points)]
                varied = (
                    orig[0],
                    orig[1] + (idx * 0.001) % 0.01,
                    orig[2],
                    orig[3] + (idx * 0.001) % 0.01,
                )
                result.append(varied)
                idx += 1
            return result[:n]

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Generate points if needed
        if self._skull_points is None or len(self._skull_points) != n:
            self._skull_points = self._generate_skull_points(n)

        self._time += 0.016

        # Slow menacing rotation
        self._rotation = self._time * 0.12

        # Breathing (subtle scale pulse)
        self._breathe += 0.012 * self._breathe_dir
        if self._breathe > 1.0:
            self._breathe_dir = -1
        elif self._breathe < 0.0:
            self._breathe_dir = 1

        # Beat response
        if audio.is_beat:
            self._head_bob = 0.025 * audio.amplitude
            self._beat_intensity = 1.0
            self._eye_glow = 1.0
        self._head_bob *= 0.9
        self._beat_intensity *= 0.92
        self._eye_glow *= 0.85

        # Jaw opens with bass
        target_jaw = audio.bands[0] * 0.08 + audio.bands[1] * 0.04
        if audio.is_beat:
            target_jaw += 0.06
        self._jaw_open += (target_jaw - self._jaw_open) * 0.25

        breathe_scale = 1.0 + self._breathe * 0.02
        skull_scale = 0.56 + audio.bands[1] * 0.05 + self._beat_intensity * 0.02

        yaw = self._rotation + math.sin(self._time * 0.35) * 0.05
        cos_r = math.cos(yaw)
        sin_r = math.sin(yaw)
        tilt_x = math.sin(self._time * 0.6) * 0.08 + self._beat_intensity * 0.12
        tilt_z = math.cos(self._time * 0.5) * 0.05 + audio.bands[3] * 0.05
        cos_tx, sin_tx = math.cos(tilt_x), math.sin(tilt_x)
        cos_tz, sin_tz = math.cos(tilt_z), math.sin(tilt_z)

        for i, point in enumerate(self._skull_points):
            part_type, px, py_norm, pz = point[0], point[1], point[2], point[3]

            # Scale and position
            px = px * skull_scale * breathe_scale
            pz = pz * skull_scale * breathe_scale
            py = py_norm * 0.45 * skull_scale  # Vertical scale

            # Apply jaw movement (only to jaw and lower teeth)
            if part_type == "jaw" and py_norm < 0.25:
                py -= self._jaw_open * (0.25 - py_norm) / 0.25
                pz -= self._jaw_open * 0.12
            elif part_type == "teeth_lower":
                py -= self._jaw_open * 0.8
                pz -= self._jaw_open * 0.1
            elif part_type in ("eye", "eye_inner"):
                pz += 0.02
            elif part_type in ("cranium", "temple"):
                pz *= 1.08
            elif part_type in ("face", "cheek"):
                pz *= 0.96

            # Subtle tilt for dramatic movement
            ty = py * cos_tx - pz * sin_tx
            tz = py * sin_tx + pz * cos_tx
            tx = px * cos_tz - ty * sin_tz
            ty2 = px * sin_tz + ty * cos_tz

            # Rotate around Y axis
            rx = tx * cos_r - tz * sin_r
            rz = tx * sin_r + tz * cos_r

            x = center + rx
            y = 0.25 + ty2 + self._head_bob
            z = center + rz

            # Scale based on part type
            band_idx = i % 5
            base_scale = self.config.base_scale * 0.9

            if part_type in ("eye", "eye_inner"):
                base_scale *= 1.1
                base_scale += self._eye_glow * 0.5 + audio.bands[4] * 0.45
                band_idx = 4  # Eyes use high frequency color
            elif part_type == "jaw":
                base_scale += audio.bands[0] * 0.3
                band_idx = 0
            elif part_type in ("teeth_upper", "teeth_lower"):
                base_scale *= 0.7
                base_scale += self._beat_intensity * 0.25
                band_idx = 5
            elif part_type == "brow":
                base_scale *= 1.05
                base_scale += audio.bands[2] * 0.2
            elif part_type == "cranium":
                base_scale += audio.bands[band_idx % 3] * 0.2
            elif part_type == "nose":
                base_scale *= 0.85

            # Global beat pulse
            if audio.is_beat:
                base_scale *= 1.1

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, base_scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


class SacredGeometry(VisualizationPattern):
    """
    Rotating icosahedron with recursive patterns.
    Sacred geometry that morphs between platonic solids.
    """

    name = "Sacred Geometry"
    description = "Morphing platonic solids - icosahedron"

    PHI = (1 + math.sqrt(5)) / 2  # Golden ratio

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation_x = 0.0
        self._rotation_y = 0.0
        self._rotation_z = 0.0
        self._morph = 0.0
        self._pulse = 0.0

    def _icosahedron_vertices(self) -> List[tuple]:
        """12 vertices of icosahedron using golden ratio."""
        phi = self.PHI
        vertices = []

        # (±1, ±φ, 0)
        for x in [-1, 1]:
            for y in [-phi, phi]:
                vertices.append((x, y, 0))

        # (0, ±1, ±φ)
        for y in [-1, 1]:
            for z in [-phi, phi]:
                vertices.append((0, y, z))

        # (±φ, 0, ±1)
        for x in [-phi, phi]:
            for z in [-1, 1]:
                vertices.append((x, 0, z))

        return vertices

    def _icosahedron_edge_midpoints(self, vertices: List[tuple]) -> List[tuple]:
        """30 edge midpoints of icosahedron."""
        # Icosahedron edges connect vertices within distance ~2
        edges = []
        threshold = 2.1

        for i, v1 in enumerate(vertices):
            for j, v2 in enumerate(vertices):
                if i < j:
                    dist = math.sqrt(sum((a - b) ** 2 for a, b in zip(v1, v2)))
                    if dist < threshold:
                        mid = tuple((a + b) / 2 for a, b in zip(v1, v2))
                        edges.append(mid)

        return edges

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Generate points
        vertices = self._icosahedron_vertices()
        edges = self._icosahedron_edge_midpoints(vertices)
        all_points = vertices + edges

        # Rotation - all three axes
        speed_mult = 1.0 + audio.amplitude
        self._rotation_y += (0.4 * speed_mult) * 0.016
        self._rotation_x += (0.25 * speed_mult) * 0.016
        self._rotation_z += (0.15 * speed_mult) * 0.016

        # Pulse on beat
        if audio.is_beat:
            self._pulse = 1.0
        self._pulse *= 0.9

        # Scale based on bass
        base_scale = 0.12 + audio.bands[0] * 0.05 + self._pulse * 0.04

        # Use as many points as we have entities
        points_to_use = all_points[:n] if n <= len(all_points) else all_points

        for i in range(min(n, len(points_to_use))):
            px, py, pz = points_to_use[i]

            # Normalize
            mag = math.sqrt(px * px + py * py + pz * pz)
            if mag > 0:
                px, py, pz = px / mag, py / mag, pz / mag

            # Apply rotations
            # Y rotation
            cos_y, sin_y = math.cos(self._rotation_y), math.sin(self._rotation_y)
            rx = px * cos_y - pz * sin_y
            rz = px * sin_y + pz * cos_y

            # X rotation
            cos_x, sin_x = math.cos(self._rotation_x), math.sin(self._rotation_x)
            ry = py * cos_x - rz * sin_x
            rz2 = py * sin_x + rz * cos_x

            # Z rotation
            cos_z, sin_z = math.cos(self._rotation_z), math.sin(self._rotation_z)
            rx2 = rx * cos_z - ry * sin_z
            ry2 = rx * sin_z + ry * cos_z

            # Position
            radius = base_scale * (1.0 + self._pulse * 0.3)
            x = center + rx2 * radius
            y = center + ry2 * radius
            z = center + rz2 * radius

            band_idx = i % 5
            scale = self.config.base_scale + audio.bands[band_idx] * 0.4

            if audio.is_beat:
                scale *= 1.3

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        # Fill remaining with additional recursive points
        for i in range(len(points_to_use), n):
            # Create inner layer
            scale_factor = 0.5
            point_idx = i % len(points_to_use)
            px, py, pz = points_to_use[point_idx]

            mag = math.sqrt(px * px + py * py + pz * pz)
            if mag > 0:
                px, py, pz = (
                    px / mag * scale_factor,
                    py / mag * scale_factor,
                    pz / mag * scale_factor,
                )

            # Apply same rotations
            cos_y, sin_y = math.cos(self._rotation_y), math.sin(self._rotation_y)
            rx = px * cos_y - pz * sin_y
            rz = px * sin_y + pz * cos_y

            cos_x, sin_x = math.cos(self._rotation_x), math.sin(self._rotation_x)
            ry = py * cos_x - rz * sin_x
            rz2 = py * sin_x + rz * cos_x

            cos_z, sin_z = math.cos(self._rotation_z), math.sin(self._rotation_z)
            rx2 = rx * cos_z - ry * sin_z
            ry2 = rx * sin_z + ry * cos_z

            radius = base_scale * (1.0 + self._pulse * 0.3)
            x = center + rx2 * radius
            y = center + ry2 * radius
            z = center + rz2 * radius

            band_idx = i % 5
            scale = self.config.base_scale * 0.7 + audio.bands[band_idx] * 0.3

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


class Vortex(VisualizationPattern):
    """
    Swirling tunnel/vortex effect.
    Classic VJ visual - entities spiral into infinity.
    """

    name = "Vortex"
    description = "Swirling tunnel - spiral into infinity"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._z_offset = 0.0
        self._intensity = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Faster rotation with energy
        speed = 2.4 + audio.amplitude * 3.4
        if audio.is_beat:
            speed *= 1.5
            self._intensity = 1.0
        self._rotation += speed * 0.016
        self._intensity *= 0.95

        # Z movement (flying through tunnel)
        self._z_offset += (0.55 + audio.bands[0] * 0.65) * 0.016
        if self._z_offset > 1.0:
            self._z_offset -= 1.0

        # Rings of entities forming tunnel
        rings = max(5, n // 8)
        per_ring = n // rings

        for ring in range(rings):
            # Depth (z position) - closer rings are larger
            ring_z = (ring / rings + self._z_offset) % 1.0
            depth = ring_z  # 0 = close, 1 = far

            # Ring radius - smaller as it goes further
            base_radius = 0.36 - depth * 0.26
            pulse_radius = base_radius + self._intensity * 0.12 * (1 - depth)

            # Ring rotation - different speeds for each ring
            ring_rotation = self._rotation * (1.0 + ring * 0.2)

            for j in range(per_ring):
                idx = ring * per_ring + j
                if idx >= n:
                    break

                angle = ring_rotation + (j / per_ring) * math.pi * 2

                # Wobble the radius per-entity for organic feel
                wobble = math.sin(angle * 3 + self._rotation * 2) * 0.02
                radius = pulse_radius + wobble

                # Audio reactivity per band
                band_idx = j % 5
                radius += audio.bands[band_idx] * 0.05 * (1 - depth)

                x = center + math.cos(angle) * radius
                z_pos = center + math.sin(angle) * radius
                wave = math.sin(self._rotation + depth * math.pi * 2) * 0.04
                y = 0.12 + depth * 0.78 + wave  # Map depth to y position

                # Scale - larger when close
                scale = self.config.base_scale * (1.55 - depth) + audio.bands[band_idx] * 0.35

                if audio.is_beat:
                    scale *= 1.2

                entities.append(
                    {
                        "id": f"block_{idx}",
                        "x": max(0, min(1, x)),
                        "y": max(0, min(1, y)),
                        "z": max(0, min(1, z_pos)),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True,
                    }
                )

        self.update()
        return entities


class Pyramid(VisualizationPattern):
    """
    Egyptian pyramid that can invert on drops.
    Majestic rotating pyramid with layers.
    """

    name = "Pyramid"
    description = "Egyptian pyramid - inverts on drops"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._invert = 0.0  # 0 = normal, 1 = inverted
        self._hover = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Slow majestic rotation
        self._rotation += (0.3 + audio.amplitude * 0.35) * 0.016

        # Invert on strong beats
        if audio.is_beat and audio.beat_intensity > 0.6:
            self._invert = 1.0 - self._invert  # Toggle

        # Hover with bass
        target_hover = audio.bands[0] * 0.1 + audio.bands[1] * 0.05
        self._hover += (target_hover - self._hover) * 0.1

        # Pyramid layers (more entities at base, fewer at top)
        layers = max(3, int(math.sqrt(n)))

        entity_idx = 0
        for layer in range(layers):
            # Normalized layer position (0 = base, 1 = apex)
            layer_norm = layer / (layers - 1) if layers > 1 else 0

            # Inversion lerp
            if self._invert > 0.5:
                layer_norm = 1.0 - layer_norm

            # Layer properties
            layer_size = 1.0 - layer_norm * 0.9  # Size shrinks toward apex
            layer_warp = math.sin(self._rotation + layer * 0.6) * audio.bands[2] * 0.08
            layer_y = 0.1 + layer_norm * 0.7 + self._hover + layer_warp * 0.1

            # Points per layer (square arrangement)
            side_points = max(1, int(math.sqrt(n / layers) * layer_size))

            for i in range(side_points):
                for j in range(side_points):
                    if entity_idx >= n:
                        break

                    # Position within layer
                    local_x = (i / max(1, side_points - 1) - 0.5) * layer_size * 0.4
                    local_z = (j / max(1, side_points - 1) - 0.5) * layer_size * 0.4

                    # Rotate
                    cos_r = math.cos(self._rotation)
                    sin_r = math.sin(self._rotation)
                    rx = local_x * cos_r - local_z * sin_r
                    rz = local_x * sin_r + local_z * cos_r

                    x = center + rx
                    z = center + rz
                    y = layer_y

                    band_idx = entity_idx % 5
                    scale = self.config.base_scale + audio.bands[band_idx] * 0.4

                    # Highlight edges with highs for definition
                    if i in (0, side_points - 1) or j in (0, side_points - 1):
                        scale += audio.bands[4] * 0.25

                    # Apex glows more
                    if layer_norm > 0.8:
                        scale += audio.amplitude * 0.3

                    if audio.is_beat:
                        scale *= 1.25

                    entities.append(
                        {
                            "id": f"block_{entity_idx}",
                            "x": max(0, min(1, x)),
                            "y": max(0, min(1, y)),
                            "z": max(0, min(1, z)),
                            "scale": min(self.config.max_scale, scale),
                            "band": band_idx,
                            "visible": True,
                        }
                    )
                    entity_idx += 1

                if entity_idx >= n:
                    break

        self.update()
        return entities


class GalaxySpiral(VisualizationPattern):
    """
    Spiral galaxy with orbiting stars.
    Majestic cosmic visualization.
    """

    name = "Galaxy"
    description = "Spiral galaxy - cosmic visualization"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._arm_twist = 0.0
        self._core_pulse = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Galaxy rotation
        self._rotation += (0.2 + audio.amplitude * 0.3) * 0.016

        # Arm twist increases with highs
        self._arm_twist = 2.3 + audio.bands[4] * 2.2 + audio.bands[3] * 0.8

        # Core pulse on beat
        if audio.is_beat:
            self._core_pulse = 1.0
        self._core_pulse *= 0.9

        # Core entities (dense center) - 20%
        core_count = n // 5

        # Spiral arm entities - 80%
        arm_count = n - core_count
        num_arms = 3 if (audio.bands[3] + audio.bands[4]) > 1.0 else 2  # React to highs

        # === CORE ===
        for i in range(core_count):
            # Random but consistent distribution in center
            seed_angle = (i * 137.5) * math.pi / 180  # Golden angle
            seed_radius = (i / core_count) ** 0.5 * 0.08  # Square root for uniform disk
            seed_radius += audio.bands[2] * 0.02  # Pulsing halo

            angle = seed_angle + self._rotation * 2
            radius = seed_radius * (1.0 + self._core_pulse * 0.3)

            x = center + math.cos(angle) * radius
            z = center + math.sin(angle) * radius
            y = center + math.sin(seed_angle * 3) * 0.02  # Slight thickness

            band_idx = i % 5
            scale = (
                self.config.base_scale * 1.2 + audio.bands[band_idx] * 0.3 + self._core_pulse * 0.2
            )

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        # === SPIRAL ARMS ===
        per_arm = arm_count // num_arms

        for arm in range(num_arms):
            arm_offset = arm * math.pi  # 180 degrees apart

            for j in range(per_arm):
                idx = core_count + arm * per_arm + j
                if idx >= n:
                    break

                # Position along arm (0 = center, 1 = outer)
                t = j / per_arm

                # Logarithmic spiral
                radius = 0.08 + t * 0.32

                # Spiral angle
                spiral_angle = arm_offset + self._rotation + t * math.pi * self._arm_twist

                # Add some scatter for natural look
                scatter = math.sin(j * 0.5) * 0.02
                radius += scatter + audio.bands[1] * 0.02 * (1 - t)

                x = center + math.cos(spiral_angle) * radius
                z = center + math.sin(spiral_angle) * radius

                # Slight vertical variation
                y = center + math.sin(spiral_angle * 2) * 0.04 * t

                band_idx = j % 5
                # Outer stars react more to highs, inner to bass
                bass_react = (1 - t) * audio.bands[0] * 0.3
                high_react = t * audio.bands[4] * 0.3
                scale = self.config.base_scale + bass_react + high_react
                scale += self._core_pulse * 0.15 * (1 - t)

                if audio.is_beat:
                    scale *= 1.15

                entities.append(
                    {
                        "id": f"block_{idx}",
                        "x": max(0, min(1, x)),
                        "y": max(0, min(1, y)),
                        "z": max(0, min(1, z)),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True,
                    }
                )

        self.update()
        return entities


class LaserArray(VisualizationPattern):
    """
    Multiple laser beams shooting from center.
    Classic EDM festival effect.
    """

    name = "Laser Array"
    description = "Laser beams shooting from center"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._beam_lengths = [0.0] * 8
        self._flash = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Rotation speed based on energy
        self._rotation += (0.5 + audio.amplitude * 2.0) * 0.016

        # Flash on beat
        if audio.is_beat:
            self._flash = 1.0
        self._flash *= 0.85

        # Number of beams
        num_beams = min(8, max(4, n // 8))
        points_per_beam = n // num_beams

        for beam in range(num_beams):
            # Beam angle
            beam_angle = self._rotation + (beam / num_beams) * math.pi * 2

            # Beam target length based on frequency band
            band_idx = beam % 5
            target_length = 0.1 + audio.bands[band_idx] * 0.35
            if audio.is_beat:
                target_length += 0.1

            # Smooth beam extension
            self._beam_lengths[beam] += (target_length - self._beam_lengths[beam]) * 0.3
            beam_length = self._beam_lengths[beam]

            # Beam tilt (elevation angle) - some beams go up, some horizontal
            tilt = (beam % 3 - 1) * 0.3  # -0.3, 0, 0.3

            for j in range(points_per_beam):
                idx = beam * points_per_beam + j
                if idx >= n:
                    break

                # Position along beam
                t = (j + 1) / points_per_beam  # 0 to 1, start from center
                distance = t * beam_length

                # 3D position
                x = center + math.cos(beam_angle) * distance * math.cos(tilt)
                z = center + math.sin(beam_angle) * distance * math.cos(tilt)
                y = center + distance * math.sin(tilt)

                # Scale - thinner at ends
                thickness = 1.0 - t * 0.5
                scale = self.config.base_scale * thickness + self._flash * 0.2

                entities.append(
                    {
                        "id": f"block_{idx}",
                        "x": max(0, min(1, x)),
                        "y": max(0, min(1, y)),
                        "z": max(0, min(1, z)),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True,
                    }
                )

        self.update()
        return entities


# ============================================================================
# Category 3: Cosmic/Space (Flow & Energy Focus)
# ============================================================================


class WormholePortal(VisualizationPattern):
    """
    Infinite tunnel with depth layers flying toward viewer.
    Perspective scaling creates depth illusion. Ring breathing with amplitude,
    spiral rotation with energy. Beat creates entrance flash on close rings.
    """

    name = "Wormhole Portal"
    description = "Infinite tunnel - rings fly toward you"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._tunnel_offset = 0.0
        self._flash = 0.0
        self._pulse = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Rotation speed driven by energy
        energy = sum(audio.bands) / 6.0
        self._rotation += (1.1 + energy * 2.2) * 0.016

        # Tunnel movement speed (flying through)
        speed = 0.32 + audio.bands[0] * 0.45 + audio.amplitude * 0.35
        self._tunnel_offset += speed * 0.016
        if self._tunnel_offset > 1.0:
            self._tunnel_offset -= 1.0

        # Beat flash
        if audio.is_beat:
            self._flash = 1.0
            self._pulse = 0.3
        self._flash *= 0.85
        self._pulse *= 0.9

        # Create rings at different depths
        num_rings = max(6, n // 10)
        points_per_ring = n // num_rings

        for ring in range(num_rings):
            # Depth cycles through with tunnel offset
            ring_depth = ((ring / num_rings) + self._tunnel_offset) % 1.0

            # Perspective: close rings are larger, far rings are smaller
            perspective = 1.0 - ring_depth * 0.8
            ring_radius = 0.05 + perspective * 0.36

            # Breathing with amplitude
            ring_radius += audio.amplitude * 0.05 * perspective
            ring_radius += math.sin(self._rotation + ring_depth * math.pi * 3) * 0.02 * perspective

            # Ring rotation - different speeds for each depth
            ring_rotation = self._rotation * (1.0 + ring_depth * 0.5)

            # Y position maps to depth (close = low y, far = high y for upward tunnel)
            base_y = 0.1 + ring_depth * 0.8
            base_y += math.sin(self._rotation * 1.3 + ring_depth * math.pi * 2) * 0.03

            for j in range(points_per_ring):
                idx = ring * points_per_ring + j
                if idx >= n:
                    break

                angle = ring_rotation + (j / points_per_ring) * math.pi * 2

                # Spiral twist increases with depth
                twist = ring_depth * math.pi * 0.5
                angle += twist

                x = center + math.cos(angle) * ring_radius
                z = center + math.sin(angle) * ring_radius
                y = base_y

                # Band-reactive radius wobble
                band_idx = j % 5
                wobble = audio.bands[band_idx] * 0.03 * perspective
                x += math.cos(angle) * wobble
                z += math.sin(angle) * wobble

                # Scale - larger when close, affected by flash
                scale = self.config.base_scale * perspective
                scale += audio.bands[band_idx] * 0.35 * perspective

                # Flash effect on close rings
                if ring_depth < 0.3:
                    scale += self._flash * 0.4 * (0.3 - ring_depth)

                if audio.is_beat:
                    scale *= 1.2

                entities.append(
                    {
                        "id": f"block_{idx}",
                        "x": clamp(x),
                        "y": clamp(y),
                        "z": clamp(z),
                        "scale": min(self.config.max_scale, max(0.05, scale)),
                        "band": band_idx,
                        "visible": True,
                    }
                )

        self.update()
        return entities


class BlackHole(VisualizationPattern):
    """
    Accretion disk with Keplerian orbital dynamics (v ∝ 1/√r).
    Particles spiral inward, bass controls accretion rate.
    Relativistic jets pulse on beat. Central void warps light.
    """

    name = "Black Hole"
    description = "Accretion disk with jets - gravity visualization"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._particles = []  # (r, theta, dr, layer)
        self._jet_intensity = 0.0
        self._rotation = 0.0
        self._warp = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count
        center = 0.5

        # Allocate: 75% disk, 25% jets
        disk_count = int(n * 0.75)
        jet_count = n - disk_count

        # Initialize particles
        if len(self._particles) != disk_count:
            self._particles = []
            for i in range(disk_count):
                r = 0.1 + random.random() * 0.3
                theta = random.random() * math.pi * 2
                dr = 0.0  # inward velocity
                layer = random.randint(0, 2)  # vertical layer
                self._particles.append([r, theta, dr, layer])

        # Jet intensity on beat
        if audio.is_beat:
            self._jet_intensity = 1.0
            self._warp = 0.5
        self._jet_intensity *= 0.92
        self._warp *= 0.95

        # Accretion rate from bass
        accretion_rate = 0.001 + audio.bands[0] * 0.003 + audio.bands[1] * 0.002

        self._rotation += 0.5 * 0.016

        entities = []

        # === ACCRETION DISK ===
        for i in range(disk_count):
            r, theta, dr, layer = self._particles[i]

            # Keplerian velocity: v ∝ 1/√r (faster when closer)
            orbital_speed = 0.5 / math.sqrt(max(0.05, r))
            theta += orbital_speed * 0.016

            # Spiral inward (accretion)
            dr = -accretion_rate * (1.0 + random.random() * 0.5)
            r += dr

            # Respawn if too close to center
            if r < 0.05:
                r = 0.35 + random.random() * 0.1
                theta = random.random() * math.pi * 2
                layer = random.randint(0, 2)

            self._particles[i] = [r, theta, dr, layer]

            # Position
            x = center + math.cos(theta) * r
            z = center + math.sin(theta) * r

            # Thin disk with slight vertical variation
            layer_offset = (layer - 1) * 0.02
            y = center + layer_offset + math.sin(theta * 4) * 0.01

            # Warp effect near center (light bending)
            if r < 0.15:
                warp_strength = (0.15 - r) / 0.15 * self._warp
                x = center + (x - center) * (1.0 - warp_strength * 0.3)
                z = center + (z - center) * (1.0 - warp_strength * 0.3)

            band_idx = i % 5
            # Inner disk hotter (higher frequencies)
            if r < 0.15:
                band_idx = 3 + (i % 2)  # high-mid and high bands
            elif r < 0.25:
                band_idx = 2 + (i % 2)  # mid and high-mid bands

            scale = self.config.base_scale * (0.5 + (0.4 - r))  # Larger near center
            scale += audio.bands[band_idx] * 0.2

            if audio.is_beat and r < 0.2:
                scale *= 1.4

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": clamp(x),
                    "y": clamp(y),
                    "z": clamp(z),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        # === RELATIVISTIC JETS ===
        jet_height = 0.3 + self._jet_intensity * 0.15 + audio.bands[0] * 0.1
        points_per_jet = jet_count // 2

        for jet in range(2):  # Top and bottom jets
            direction = 1 if jet == 0 else -1

            for j in range(points_per_jet):
                idx = disk_count + jet * points_per_jet + j
                if idx >= n:
                    break

                # Position along jet
                t = (j + 1) / points_per_jet
                jet_r = 0.02 + t * 0.04  # Slight cone shape
                jet_y = center + direction * (0.05 + t * jet_height)

                # Spiral in jet
                jet_angle = self._rotation * 3 + t * math.pi * 2 + jet * math.pi
                x = center + math.cos(jet_angle) * jet_r
                z = center + math.sin(jet_angle) * jet_r
                y = jet_y

                band_idx = 3 + (j % 2)  # Jets are hot = high-mid and high bands
                scale = self.config.base_scale * (1.0 - t * 0.5) * (0.5 + self._jet_intensity)
                scale += audio.bands[4] * 0.3

                entities.append(
                    {
                        "id": f"block_{idx}",
                        "x": clamp(x),
                        "y": clamp(y),
                        "z": clamp(z),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": self._jet_intensity > 0.1 or audio.bands[0] > 0.3,
                    }
                )

        self.update()
        return entities


class Nebula(VisualizationPattern):
    """
    Volumetric gas cloud with smooth noise-based drift.
    Spherical particle distribution with density gradients.
    Color gradient mapped by spatial position.
    Amplitude controls expansion, beat triggers internal star flashes.
    """

    name = "Nebula"
    description = "Cosmic gas cloud with drifting particles"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._particles = []  # (x, y, z, phase)
        self._expansion = 1.0
        self._flash_particles = set()
        self._drift_time = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count
        center = 0.5

        # Initialize particles in spherical distribution
        if len(self._particles) != n:
            self._particles = []
            points = fibonacci_sphere(n)
            for i, (x, y, z) in enumerate(points):
                # Randomize radius for volume (not just surface)
                r = 0.3 + random.random() * 0.7  # 0.3 to 1.0 of max
                phase = random.random() * math.pi * 2
                self._particles.append([x * r, y * r, z * r, phase])

        self._drift_time += 0.016

        # Expansion with amplitude
        target_expansion = 0.8 + audio.amplitude * 0.4
        self._expansion += (target_expansion - self._expansion) * 0.1

        # Beat triggers star flashes (random subset)
        if audio.is_beat:
            self._flash_particles = set(random.sample(range(n), min(n // 4, 20)))
        else:
            # Decay flash set
            if random.random() < 0.3:
                self._flash_particles = set()

        entities = []
        base_radius = 0.25 * self._expansion

        for i in range(n):
            px, py, pz, phase = self._particles[i]

            # Smooth drifting motion using noise-like movement
            drift_x = math.sin(self._drift_time * 0.3 + phase) * 0.02
            drift_y = math.cos(self._drift_time * 0.2 + phase * 1.3) * 0.02
            drift_z = math.sin(self._drift_time * 0.25 + phase * 0.7) * 0.02

            # Update particle position with drift
            self._particles[i][0] += drift_x * 0.1
            self._particles[i][1] += drift_y * 0.1
            self._particles[i][2] += drift_z * 0.1

            # Keep within bounds (soft boundary)
            dist = math.sqrt(px * px + py * py + pz * pz)
            if dist > 1.2:
                # Pull back toward center
                self._particles[i][0] *= 0.99
                self._particles[i][1] *= 0.99
                self._particles[i][2] *= 0.99

            # World position
            x = center + px * base_radius + drift_x
            y = center + py * base_radius + drift_y
            z = center + pz * base_radius + drift_z

            # Band based on position (creates color gradients)
            # Higher Y = higher frequency colors
            normalized_y = (py + 1) / 2  # 0 to 1
            band_idx = int(normalized_y * 4.9)
            band_idx = max(0, min(4, band_idx))

            # Scale based on density (denser near center)
            density_scale = 1.0 - dist * 0.3
            scale = self.config.base_scale * density_scale
            scale += audio.bands[band_idx] * 0.25

            # Flash effect for "star birth"
            if i in self._flash_particles:
                scale *= 2.0
                band_idx = 5  # Bright white/high freq

            if audio.is_beat:
                scale *= 1.15

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": clamp(x),
                    "y": clamp(y),
                    "z": clamp(z),
                    "scale": min(self.config.max_scale, max(0.05, scale)),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


# ============================================================================
# Category 2: Organic/Nature (Beat Focus)
# ============================================================================


class Aurora(VisualizationPattern):
    """
    Northern lights curtains with flowing wave motion.
    2-3 curtain layers with depth parallax.
    Beat triggers ripple waves that propagate through curtains.
    Color bands sweep horizontally.
    """

    name = "Aurora"
    description = "Northern lights curtains - flowing waves"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._wave_time = 0.0
        self._ripple_origins = []  # (x, time) for beat-triggered ripples
        self._color_offset = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        self._wave_time += 0.016

        # Color sweep
        self._color_offset += audio.amplitude * 0.02 + 0.005

        # Beat triggers new ripple
        if audio.is_beat:
            ripple_x = random.random()  # Random horizontal position
            self._ripple_origins.append([ripple_x, 0.0])

        # Update ripples
        new_ripples = []
        for ripple in self._ripple_origins:
            ripple[1] += 0.03 + audio.amplitude * 0.02  # Ripple expands
            if ripple[1] < 2.0:  # Keep ripple alive for ~2 units
                new_ripples.append(ripple)
        self._ripple_origins = new_ripples

        # 3 curtain layers with different depths
        num_layers = 3
        points_per_layer = n // num_layers

        for layer in range(num_layers):
            layer_depth = 0.3 + layer * 0.2  # Z position for parallax
            layer_speed = 1.0 + layer * 0.3  # Different wave speeds

            for j in range(points_per_layer):
                idx = layer * points_per_layer + j
                if idx >= n:
                    break

                # Horizontal position across curtain
                x_norm = j / max(1, points_per_layer - 1)
                x = 0.1 + x_norm * 0.8

                # Flowing wave motion (multiple sine waves)
                wave1 = math.sin(x_norm * math.pi * 3 + self._wave_time * layer_speed) * 0.1
                wave2 = math.sin(x_norm * math.pi * 5 - self._wave_time * 0.7 * layer_speed) * 0.05
                wave3 = math.sin(x_norm * math.pi * 2 + self._wave_time * 1.3) * 0.08

                # Combine waves
                wave_offset = wave1 + wave2 + wave3

                # Add ripple effects from beats
                ripple_offset = 0
                for ripple_x, ripple_time in self._ripple_origins:
                    dist = abs(x_norm - ripple_x)
                    if dist < ripple_time and ripple_time - dist < 0.5:
                        ripple_strength = (0.5 - (ripple_time - dist)) * 2
                        ripple_offset += (
                            math.sin((ripple_time - dist) * math.pi * 4) * 0.1 * ripple_strength
                        )

                # Y position (curtains hang from top)
                base_y = 0.7 - layer * 0.1
                y = base_y + wave_offset + ripple_offset

                # Height variation based on audio
                y += audio.bands[layer * 2] * 0.15

                z = center + (layer_depth - 0.5)

                # Color band based on horizontal position + sweep
                color_pos = (x_norm + self._color_offset) % 1.0
                band_idx = int(color_pos * 4.9)
                band_idx = max(0, min(4, band_idx))

                # Scale pulses with corresponding band
                scale = self.config.base_scale + audio.bands[band_idx] * 0.35
                scale += ripple_offset * 2  # Ripples make particles larger

                if audio.is_beat:
                    scale *= 1.25

                entities.append(
                    {
                        "id": f"block_{idx}",
                        "x": clamp(x),
                        "y": clamp(y),
                        "z": clamp(z),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True,
                    }
                )

        self.update()
        return entities


class OceanWaves(VisualizationPattern):
    """
    Grid-based water surface with wave interference.
    Multiple overlapping sine waves (bass = large waves, highs = shimmer).
    Beat creates splash at random position with expanding ripple rings.
    Rings fade over 3 seconds.
    """

    name = "Ocean Waves"
    description = "Water surface with splashes and ripples"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._wave_time = 0.0
        self._splashes = []  # [(x, z, time, intensity)]

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        self._wave_time += 0.016

        # Beat triggers splash
        if audio.is_beat:
            splash_x = 0.2 + random.random() * 0.6
            splash_z = 0.2 + random.random() * 0.6
            self._splashes.append([splash_x, splash_z, 0.0, audio.beat_intensity])

        # Update splashes (fade over 3 seconds)
        new_splashes = []
        for splash in self._splashes:
            splash[2] += 0.016  # time
            if splash[2] < 3.0:
                new_splashes.append(splash)
        self._splashes = new_splashes

        # Create grid
        grid_size = int(math.sqrt(n))
        if grid_size < 2:
            grid_size = 2

        for i in range(grid_size):
            for j in range(grid_size):
                idx = i * grid_size + j
                if idx >= n:
                    break

                # Grid position
                x_norm = i / (grid_size - 1)
                z_norm = j / (grid_size - 1)

                x = 0.1 + x_norm * 0.8
                z = 0.1 + z_norm * 0.8

                # === Wave interference ===
                # Large waves (bass)
                wave_bass = math.sin(x_norm * math.pi * 2 + self._wave_time * 0.5) * 0.08
                wave_bass += math.sin(z_norm * math.pi * 1.5 + self._wave_time * 0.3) * 0.06
                wave_bass *= 0.5 + audio.bands[0] * 1.5

                # Medium waves (mids)
                wave_mid = math.sin(x_norm * math.pi * 4 + self._wave_time * 1.2) * 0.04
                wave_mid += math.sin(z_norm * math.pi * 3 - self._wave_time * 0.8) * 0.03
                wave_mid *= 0.3 + audio.bands[2] + audio.bands[3]

                # Small shimmer (highs)
                wave_high = math.sin(x_norm * math.pi * 8 + self._wave_time * 3) * 0.015
                wave_high += math.sin(z_norm * math.pi * 7 - self._wave_time * 2.5) * 0.01
                wave_high *= 0.2 + audio.bands[4] * 2 + audio.bands[4] * 2

                # Combine waves
                y = center + wave_bass + wave_mid + wave_high

                # === Ripple rings from splashes ===
                ripple_height = 0
                for sx, sz, t, intensity in self._splashes:
                    dist = math.sqrt((x - sx) ** 2 + (z - sz) ** 2)
                    ripple_radius = t * 0.3  # Expands over time
                    ripple_width = 0.08

                    # Check if point is near ripple ring
                    if abs(dist - ripple_radius) < ripple_width:
                        # Ripple strength fades over time
                        fade = 1.0 - (t / 3.0)
                        ring_strength = 1.0 - abs(dist - ripple_radius) / ripple_width
                        ripple_height += (
                            math.sin(dist * 20 - t * 10) * 0.1 * intensity * fade * ring_strength
                        )

                y += ripple_height

                # Band based on wave activity
                wave_activity = abs(wave_bass) + abs(wave_mid) + abs(wave_high)
                band_idx = int(wave_activity * 10) % 5

                scale = self.config.base_scale + wave_activity * 2
                scale += ripple_height * 3  # Splashes make bigger particles

                if audio.is_beat:
                    scale *= 1.2

                entities.append(
                    {
                        "id": f"block_{idx}",
                        "x": clamp(x),
                        "y": clamp(y),
                        "z": clamp(z),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True,
                    }
                )

        self.update()
        return entities


class Fireflies(VisualizationPattern):
    """
    Swarm of drifting lights with organic motion.
    4 flash groups for synchronized cascade effect.
    Beat triggers group 1, others cascade with 100ms delays.
    Individual glow cycles + group flash overlay.
    """

    name = "Fireflies"
    description = "Swarm of synchronized flashing lights"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._fireflies = []  # [(x, y, z, vx, vy, vz, glow_phase, group)]
        self._group_flash = [0.0, 0.0, 0.0, 0.0]  # Flash intensity per group
        self._cascade_timer = 0.0
        self._cascade_active = False
        self._drift_time = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count
        center = 0.5

        # Initialize fireflies
        if len(self._fireflies) != n:
            self._fireflies = []
            for i in range(n):
                x = center + (random.random() - 0.5) * 0.6
                y = 0.2 + random.random() * 0.6
                z = center + (random.random() - 0.5) * 0.6
                vx = (random.random() - 0.5) * 0.01
                vy = (random.random() - 0.5) * 0.01
                vz = (random.random() - 0.5) * 0.01
                glow_phase = random.random() * math.pi * 2
                group = i % 4  # Assign to one of 4 groups
                self._fireflies.append([x, y, z, vx, vy, vz, glow_phase, group])

        self._drift_time += 0.016

        # Beat triggers cascade
        if audio.is_beat:
            self._group_flash[0] = 1.0
            self._cascade_active = True
            self._cascade_timer = 0.0

        # Cascade to other groups with delays (100ms = 0.1s)
        if self._cascade_active:
            self._cascade_timer += 0.016
            if self._cascade_timer > 0.1 and self._group_flash[1] < 0.5:
                self._group_flash[1] = 1.0
            if self._cascade_timer > 0.2 and self._group_flash[2] < 0.5:
                self._group_flash[2] = 1.0
            if self._cascade_timer > 0.3 and self._group_flash[3] < 0.5:
                self._group_flash[3] = 1.0
            if self._cascade_timer > 0.5:
                self._cascade_active = False

        # Decay group flashes
        for g in range(4):
            self._group_flash[g] *= 0.92

        entities = []

        for i in range(n):
            ff = self._fireflies[i]
            x, y, z, vx, vy, vz, glow_phase, group = ff

            # Organic drifting motion
            # Perlin-like smooth random walk
            t = self._drift_time + i * 0.1
            ax = math.sin(t * 0.5 + i) * 0.0002
            ay = math.sin(t * 0.3 + i * 1.3) * 0.0001
            az = math.cos(t * 0.4 + i * 0.7) * 0.0002

            # Update velocity with acceleration
            vx = vx * 0.98 + ax
            vy = vy * 0.98 + ay
            vz = vz * 0.98 + az

            # Clamp velocity
            max_v = 0.015
            vx = clamp(vx, -max_v, max_v)
            vy = clamp(vy, -max_v, max_v)
            vz = clamp(vz, -max_v, max_v)

            # Update position
            x += vx
            y += vy
            z += vz

            # Soft boundaries - turn around near edges
            if x < 0.15 or x > 0.85:
                vx *= -0.5
            if y < 0.15 or y > 0.85:
                vy *= -0.5
            if z < 0.15 or z > 0.85:
                vz *= -0.5

            x = clamp(x, 0.1, 0.9)
            y = clamp(y, 0.1, 0.9)
            z = clamp(z, 0.1, 0.9)

            # Update glow phase
            glow_phase += 0.05 + random.random() * 0.02

            # Store updated values
            self._fireflies[i] = [x, y, z, vx, vy, vz, glow_phase, group]

            # Individual glow cycle
            individual_glow = (math.sin(glow_phase) + 1) * 0.3  # 0 to 0.6

            # Group flash overlay
            group_glow = self._group_flash[int(group)]

            # Combined glow
            total_glow = individual_glow + group_glow

            # Audio reactivity - fireflies respond to amplitude
            total_glow += audio.amplitude * 0.2

            # Band based on group
            band_idx = int(group) + 1  # Groups 0-3 map to bands 1-4

            scale = self.config.base_scale * 0.5 + total_glow * 0.6
            scale += audio.bands[band_idx] * 0.2

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": x,
                    "y": y,
                    "z": z,
                    "scale": min(self.config.max_scale, max(0.05, scale)),
                    "band": band_idx,
                    "visible": total_glow > 0.15,  # Only visible when glowing enough
                }
            )

        self.update()
        return entities


# ============================================================================
# Category 1: Geometric/Abstract (Frequency Mapping Focus)
# ============================================================================


class Mandala(VisualizationPattern):
    """
    5 concentric rings, each mapped to a frequency band.
    Counter-rotating layers with golden angle petal distribution.
    Inner rings (bass) rotate slower, outer rings (highs) faster.
    Beat triggers global pulse and petal intensity boost.
    """

    name = "Mandala"
    description = "Sacred geometry rings - frequency mapped"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._ring_rotations = [0.0] * 5
        self._pulse = 0.0
        self._petal_boost = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Beat pulse
        if audio.is_beat:
            self._pulse = 1.0
            self._petal_boost = 1.0
        self._pulse *= 0.9
        self._petal_boost *= 0.85

        # Golden angle for petal distribution
        golden_angle = math.pi * (3.0 - math.sqrt(5.0))

        # Distribute entities across 5 rings
        points_per_ring = n // 5
        entity_idx = 0

        for ring in range(5):
            # Ring properties
            base_radius = 0.08 + ring * 0.07

            # Radius pulses with corresponding frequency band
            radius = base_radius + audio.bands[ring] * 0.04 + self._pulse * 0.02

            # Rotation speed: inner slower, outer faster
            # Also alternate direction for counter-rotation
            direction = 1 if ring % 2 == 0 else -1
            speed = (0.2 + ring * 0.18) * direction
            speed *= 1.0 + audio.bands[ring] * 0.5  # Speed up with band intensity

            self._ring_rotations[ring] += speed * 0.016

            # Points on this ring
            for j in range(points_per_ring):
                if entity_idx >= n:
                    break

                # Golden angle distribution for petal pattern
                angle = self._ring_rotations[ring] + j * golden_angle

                x = center + math.cos(angle) * radius
                z = center + math.sin(angle) * radius

                # Y varies slightly by position for 3D depth
                y = center + math.sin(angle * 2) * 0.02 * (1 + ring * 0.3)

                # Scale based on band intensity
                scale = self.config.base_scale + audio.bands[ring] * 0.5
                scale += self._petal_boost * 0.3 * (1.0 - ring / 5)  # Inner rings boost more

                if audio.is_beat:
                    scale *= 1.2

                entities.append(
                    {
                        "id": f"block_{entity_idx}",
                        "x": clamp(x),
                        "y": clamp(y),
                        "z": clamp(z),
                        "scale": min(self.config.max_scale, scale),
                        "band": ring,  # Each ring uses its corresponding band
                        "visible": True,
                    }
                )
                entity_idx += 1

        self.update()
        return entities


class Tesseract(VisualizationPattern):
    """
    4D hypercube projected to 3D, rotating through 4th dimension.
    16 vertices + optional 32 edge midpoints.
    Inner cube (w=-1) responds to bass, outer cube (w=+1) to highs.
    Three 4D rotation planes (XW, YW, ZW) driven by different bands.
    """

    name = "Tesseract"
    description = "4D hypercube rotating through dimensions"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation_xw = 0.0
        self._rotation_yw = 0.0
        self._rotation_zw = 0.0
        self._rotation_xy = 0.0
        self._pulse = 0.0
        self._vertices = None
        self._edges = None

    def _generate_tesseract(self):
        """Generate 4D hypercube vertices."""
        # 16 vertices of tesseract (all combinations of ±1 in 4D)
        vertices = []
        for x in [-1, 1]:
            for y in [-1, 1]:
                for z in [-1, 1]:
                    for w in [-1, 1]:
                        vertices.append([x, y, z, w])

        # 32 edges connect vertices that differ in exactly one coordinate
        edges = []
        for i, v1 in enumerate(vertices):
            for j, v2 in enumerate(vertices):
                if i < j:
                    diff = sum(1 for a, b in zip(v1, v2) if a != b)
                    if diff == 1:
                        # Store midpoint
                        mid = [(a + b) / 2 for a, b in zip(v1, v2)]
                        edges.append(mid)

        return vertices, edges

    def _rotate_4d(self, point, rxw, ryw, rzw, rxy):
        """Apply 4D rotations to a point."""
        x, y, z, w = point

        # XW rotation (bass)
        cos_xw, sin_xw = math.cos(rxw), math.sin(rxw)
        x1 = x * cos_xw - w * sin_xw
        w1 = x * sin_xw + w * cos_xw

        # YW rotation (mids)
        cos_yw, sin_yw = math.cos(ryw), math.sin(ryw)
        y1 = y * cos_yw - w1 * sin_yw
        w2 = y * sin_yw + w1 * cos_yw

        # ZW rotation (highs)
        cos_zw, sin_zw = math.cos(rzw), math.sin(rzw)
        z1 = z * cos_zw - w2 * sin_zw
        w3 = z * sin_zw + w2 * cos_zw

        # XY rotation (visual spin)
        cos_xy, sin_xy = math.cos(rxy), math.sin(rxy)
        x2 = x1 * cos_xy - y1 * sin_xy
        y2 = x1 * sin_xy + y1 * cos_xy

        return [x2, y2, z1, w3]

    def _project_4d_to_3d(self, point, distance=2.0):
        """Project 4D point to 3D using perspective projection."""
        x, y, z, w = point
        # Perspective divide based on w distance
        scale = distance / (distance - w * 0.5)
        return [x * scale, y * scale, z * scale, w]

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count

        # Generate geometry
        if self._vertices is None:
            self._vertices, self._edges = self._generate_tesseract()

        # Rotation speeds driven by frequency bands
        # Bass drives XW (deepest dimension shift)
        self._rotation_xw += (0.2 + audio.bands[0] * 0.8) * 0.016
        # Mids drive YW
        self._rotation_yw += (0.15 + audio.bands[2] * 0.6) * 0.016
        # Highs drive ZW
        self._rotation_zw += (0.1 + audio.bands[4] * 0.5 + audio.bands[4] * 0.3) * 0.016
        # General rotation for visual interest
        self._rotation_xy += 0.3 * 0.016

        # Beat pulse
        if audio.is_beat:
            self._pulse = 1.0
        self._pulse *= 0.9

        entities = []
        center = 0.5
        base_scale = 0.15 + self._pulse * 0.05

        # Combine vertices and edge midpoints
        all_points = self._vertices + self._edges
        points_to_use = min(n, len(all_points))

        for i in range(points_to_use):
            point = all_points[i]

            # Apply 4D rotations
            rotated = self._rotate_4d(
                point, self._rotation_xw, self._rotation_yw, self._rotation_zw, self._rotation_xy
            )

            # Project to 3D
            projected = self._project_4d_to_3d(rotated)
            px, py, pz, pw = projected

            # Position in world
            x = center + px * base_scale
            y = center + py * base_scale
            z = center + pz * base_scale

            # Band based on w coordinate (4th dimension position)
            # w=-1 (inner cube) = bass, w=+1 (outer cube) = highs
            w_normalized = (pw + 1) / 2  # 0 to 1
            band_idx = int(w_normalized * 4.9)
            band_idx = clamp(band_idx, 0, 4)

            # Scale based on w (outer cube bigger when highs are strong)
            w_scale = 0.7 + w_normalized * 0.6
            scale = self.config.base_scale * w_scale
            scale += audio.bands[int(band_idx)] * 0.4

            if audio.is_beat:
                scale *= 1.3

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": clamp(x),
                    "y": clamp(y),
                    "z": clamp(z),
                    "scale": min(self.config.max_scale, scale),
                    "band": int(band_idx),
                    "visible": True,
                }
            )

        # Fill remaining with inner vertices at smaller scale
        for i in range(points_to_use, n):
            point_idx = i % len(self._vertices)
            point = self._vertices[point_idx]

            rotated = self._rotate_4d(
                point,
                self._rotation_xw * 0.5,  # Slower rotation for inner layer
                self._rotation_yw * 0.5,
                self._rotation_zw * 0.5,
                self._rotation_xy,
            )
            projected = self._project_4d_to_3d(rotated)
            px, py, pz, pw = projected

            inner_scale = base_scale * 0.5

            x = center + px * inner_scale
            y = center + py * inner_scale
            z = center + pz * inner_scale

            band_idx = i % 5
            scale = self.config.base_scale * 0.6 + audio.bands[band_idx] * 0.2

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": clamp(x),
                    "y": clamp(y),
                    "z": clamp(z),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True,
                }
            )

        self.update()
        return entities


class CrystalGrowth(VisualizationPattern):
    """
    L-system inspired fractal crystal with recursive branching.
    Main trunk grows with bass, branches with mids, tips sparkle with highs.
    Branch probability and angle spread controlled by audio.
    Beat triggers growth spurts.
    """

    name = "Crystal Growth"
    description = "Fractal crystal with recursive branching"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._growth = 0.5  # Overall growth factor 0-1
        self._sparkle_phase = 0.0
        self._branch_points = None
        self._growth_spurt = 0.0
        self._rotation = 0.0
        self._sway = 0.0

    def _generate_crystal_structure(self, depth: int = 3) -> List[tuple]:
        """
        Generate crystal branch structure recursively.
        Returns list of (x, y, z, depth, branch_id) tuples.
        """
        points = []

        def add_branch(start_x, start_y, start_z, direction, length, current_depth, branch_id):
            if current_depth > depth or length < 0.02:
                return

            # Direction: (dx, dy, dz) normalized
            dx, dy, dz = direction

            # Add points along this branch
            segments = max(2, int(length * 10))
            for i in range(segments + 1):
                t = i / segments
                x = start_x + dx * length * t
                y = start_y + dy * length * t
                z = start_z + dz * length * t
                points.append((x, y, z, current_depth, branch_id))

            # End point of this branch
            end_x = start_x + dx * length
            end_y = start_y + dy * length
            end_z = start_z + dz * length

            # Spawn child branches
            if current_depth < depth:
                # Number of branches decreases with depth
                num_children = max(1, 4 - current_depth)

                for child in range(num_children):
                    # Angle spread for child branches
                    child_angle = (child / num_children) * math.pi * 2
                    spread = 0.3 + current_depth * 0.15  # More spread at higher depths

                    # New direction (rotate around main direction)
                    # Simple approximation: bend outward
                    new_dx = dx + math.cos(child_angle) * spread
                    new_dy = dy * 0.8  # Maintain mostly upward
                    new_dz = dz + math.sin(child_angle) * spread

                    # Normalize
                    mag = math.sqrt(new_dx**2 + new_dy**2 + new_dz**2)
                    if mag > 0:
                        new_dx, new_dy, new_dz = new_dx / mag, new_dy / mag, new_dz / mag

                    # Child branches are shorter
                    child_length = length * 0.6

                    add_branch(
                        end_x,
                        end_y,
                        end_z,
                        (new_dx, new_dy, new_dz),
                        child_length,
                        current_depth + 1,
                        f"{branch_id}_{child}",
                    )

        # Start with main trunk going up
        add_branch(0, 0, 0, (0, 1, 0), 0.4, 0, "trunk")

        # Add some root branches going down/out
        for i in range(3):
            angle = i * math.pi * 2 / 3
            add_branch(
                0, 0, 0, (math.cos(angle) * 0.3, -0.3, math.sin(angle) * 0.3), 0.15, 2, f"root_{i}"
            )

        return points

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count

        # Generate crystal structure if needed
        if self._branch_points is None:
            self._branch_points = self._generate_crystal_structure()

        self._sparkle_phase += 0.1
        self._rotation += (0.15 + audio.amplitude * 0.3) * 0.016
        self._sway = math.sin(self._sparkle_phase * 0.5) * (0.05 + audio.bands[2] * 0.08)

        # Growth responds to bass
        target_growth = 0.5 + audio.bands[0] * 0.3 + audio.bands[1] * 0.2
        self._growth += (target_growth - self._growth) * 0.05

        # Beat triggers growth spurt
        if audio.is_beat:
            self._growth_spurt = 0.35
        self._growth_spurt *= 0.9

        effective_growth = self._growth + self._growth_spurt

        entities = []
        center = 0.5

        # Sample points from structure
        points_to_use = min(n, len(self._branch_points))
        step = len(self._branch_points) / points_to_use if points_to_use > 0 else 1

        for i in range(points_to_use):
            idx = int(i * step)
            if idx >= len(self._branch_points):
                idx = len(self._branch_points) - 1

            px, py, pz, depth, branch_id = self._branch_points[idx]

            # Scale by growth (deeper branches appear later)
            depth_growth = max(0, effective_growth - depth * 0.2)
            if depth_growth <= 0:
                # Branch hasn't grown yet
                continue

            # Position
            # Rotate around Y for a more faceted shimmer
            cos_r = math.cos(self._rotation)
            sin_r = math.sin(self._rotation)
            rx = px * cos_r - pz * sin_r
            rz = px * sin_r + pz * cos_r

            x = center + rx * depth_growth + self._sway * py
            y = 0.2 + py * depth_growth * 0.6  # Grow upward from base
            z = center + rz * depth_growth + self._sway * pz * 0.3

            # Band based on depth
            # Trunk (depth 0) = bass, tips (depth 3) = highs
            band_idx = min(4, depth * 2)

            # Scale based on depth and audio
            base_scale = self.config.base_scale * (1.0 - depth * 0.15)
            scale = base_scale + audio.bands[band_idx] * 0.3

            # Tips sparkle with highs
            if depth >= 2:
                sparkle = math.sin(self._sparkle_phase + i * 0.5) * 0.5 + 0.5
                scale += sparkle * audio.bands[4] * 0.45 + audio.bands[4] * 0.3

            if audio.is_beat:
                scale *= 1.2 + (depth * 0.1)  # Tips react more

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": clamp(x),
                    "y": clamp(y),
                    "z": clamp(z),
                    "scale": min(self.config.max_scale, max(0.05, scale)),
                    "band": int(band_idx),
                    "visible": depth_growth > 0.1,
                }
            )

        # Fill remaining entities with extra tip points
        for i in range(len(entities), n):
            # Add sparkly tip particles
            angle = i * 2.39996  # Golden angle
            radius = 0.1 + (i % 5) * 0.03
            tip_y = 0.2 + effective_growth * 0.6 * 0.8

            x = center + math.cos(angle) * radius * effective_growth
            y = tip_y + math.sin(i * 0.7) * 0.05
            z = center + math.sin(angle) * radius * effective_growth

            # Apply subtle rotation/sway to tips
            cos_r = math.cos(self._rotation + i * 0.01)
            sin_r = math.sin(self._rotation + i * 0.01)
            tx = x - center
            tz = z - center
            x = center + tx * cos_r - tz * sin_r + self._sway * 0.2
            z = center + tx * sin_r + tz * cos_r + self._sway * 0.1

            sparkle = math.sin(self._sparkle_phase + i) * 0.5 + 0.5
            scale = self.config.base_scale * 0.5 * sparkle
            scale += audio.bands[4] * 0.3

            entities.append(
                {
                    "id": f"block_{i}",
                    "x": clamp(x),
                    "y": clamp(y),
                    "z": clamp(z),
                    "scale": min(self.config.max_scale, max(0.03, scale)),
                    "band": 5,
                    "visible": sparkle > 0.3,
                }
            )

        self.update()
        return entities


# ============================================================================
# Classic Spectrum Patterns
# ============================================================================


class SpectrumBars(VisualizationPattern):
    """
    Classic spectrum analyzer with vertical bars.
    Each bar represents a frequency band, height = intensity.
    Simple, clean, and immediately readable.
    """

    name = "Spectrum Bars"
    description = "Classic vertical frequency bars"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._smooth_heights = [0.0] * 5
        self._peak_heights = [0.0] * 5
        self._peak_fall = [0.0] * 5

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Smooth the band values
        for i in range(5):
            target = audio.bands[i]
            self._smooth_heights[i] += (target - self._smooth_heights[i]) * 0.3

            # Peak hold with fall
            if self._smooth_heights[i] > self._peak_heights[i]:
                self._peak_heights[i] = self._smooth_heights[i]
                self._peak_fall[i] = 0.0
            else:
                self._peak_fall[i] += 0.02
                self._peak_heights[i] -= self._peak_fall[i] * 0.016

            self._peak_heights[i] = max(0, self._peak_heights[i])

        # Distribute entities across 5 bars
        blocks_per_bar = n // 5
        bar_spacing = 0.16
        start_x = center - (2.0 * bar_spacing)

        entity_idx = 0

        for bar in range(5):
            bar_x = start_x + bar * bar_spacing
            bar_height = self._smooth_heights[bar]

            # Stack blocks vertically for this bar
            for j in range(blocks_per_bar):
                if entity_idx >= n:
                    break

                # Normalized position in bar (0 = bottom, 1 = top of max)
                block_y_norm = (j + 0.5) / blocks_per_bar

                # Only show blocks up to current height
                max_height = 0.7
                block_y = 0.1 + block_y_norm * max_height

                # Visibility based on bar height
                visible = block_y_norm <= bar_height

                # Scale - slightly larger when at the top of the current level
                scale = self.config.base_scale
                if visible:
                    # Blocks near the top of current height are brighter
                    top_proximity = 1.0 - abs(block_y_norm - bar_height) * 3
                    top_proximity = max(0, top_proximity)
                    scale += top_proximity * 0.3

                if audio.is_beat:
                    scale *= 1.2

                # Slight depth variation per bar
                z = center + (bar - 2.5) * 0.02

                entities.append(
                    {
                        "id": f"block_{entity_idx}",
                        "x": clamp(bar_x),
                        "y": clamp(block_y),
                        "z": clamp(z),
                        "scale": min(self.config.max_scale, scale) if visible else 0.01,
                        "band": bar,
                        "visible": visible,
                    }
                )
                entity_idx += 1

        self.update()
        return entities


class SpectrumTubes(VisualizationPattern):
    """
    Cylindrical tubes rising from the ground for each frequency band.
    Tubes are made of stacked rings that pulse and glow.
    More dimensional than flat bars.
    """

    name = "Spectrum Tubes"
    description = "3D cylindrical frequency tubes"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._smooth_heights = [0.0] * 5
        self._rotation = 0.0
        self._pulse = [0.0] * 5

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        self._rotation += 0.3 * 0.016

        # Smooth the band values and track pulses
        for i in range(5):
            target = audio.bands[i]
            self._smooth_heights[i] += (target - self._smooth_heights[i]) * 0.25

            # Pulse on beat
            if audio.is_beat:
                self._pulse[i] = 0.5
            self._pulse[i] *= 0.9

        # Layout: 5 tubes in a row (or arc)
        tube_spacing = 0.15
        start_x = center - (2.0 * tube_spacing)

        # Points per tube
        points_per_tube = n // 5
        rings_per_tube = max(3, points_per_tube // 4)
        points_per_ring = points_per_tube // rings_per_tube

        entity_idx = 0

        for tube in range(5):
            tube_x = start_x + tube * tube_spacing
            tube_height = self._smooth_heights[tube]
            tube_radius = 0.03 + self._pulse[tube] * 0.02

            for ring in range(rings_per_tube):
                if entity_idx >= n:
                    break

                # Ring Y position
                ring_y_norm = (ring + 0.5) / rings_per_tube
                max_height = 0.65
                ring_y = 0.1 + ring_y_norm * max_height

                # Only show rings up to current height
                visible = ring_y_norm <= tube_height + 0.1

                # Ring radius varies slightly
                current_radius = tube_radius * (1.0 + ring_y_norm * 0.2)

                for p in range(points_per_ring):
                    if entity_idx >= n:
                        break

                    # Angle around ring
                    angle = self._rotation + (p / points_per_ring) * math.pi * 2
                    angle += tube * 0.5  # Offset per tube

                    # Position
                    x = tube_x + math.cos(angle) * current_radius
                    z = center + math.sin(angle) * current_radius

                    # Scale based on visibility and position
                    scale = self.config.base_scale * 0.7
                    if visible:
                        # Glow near top
                        if ring_y_norm > tube_height - 0.15:
                            scale *= 1.5
                        scale += audio.bands[tube] * 0.3

                    if audio.is_beat:
                        scale *= 1.15

                    entities.append(
                        {
                            "id": f"block_{entity_idx}",
                            "x": clamp(x),
                            "y": clamp(ring_y),
                            "z": clamp(z),
                            "scale": min(self.config.max_scale, scale) if visible else 0.01,
                            "band": tube,
                            "visible": visible,
                        }
                    )
                    entity_idx += 1

        self.update()
        return entities


class SpectrumCircle(VisualizationPattern):
    """
    Circular spectrum analyzer - bars radiate outward from center.
    Classic DJ visualization style.
    """

    name = "Spectrum Circle"
    description = "Radial frequency bars in a circle"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._smooth_heights = [0.0] * 5
        self._rotation = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Slow rotation
        self._rotation += (0.1 + audio.amplitude * 0.2) * 0.016

        # Smooth the band values
        for i in range(5):
            target = audio.bands[i]
            self._smooth_heights[i] += (target - self._smooth_heights[i]) * 0.3

        # Mirror the 5 bands to create symmetry (10 segments)
        mirrored_heights = self._smooth_heights + self._smooth_heights[::-1]

        # Bars around a circle
        num_segments = 10
        points_per_segment = n // num_segments
        base_radius = 0.15
        max_bar_length = 0.25

        entity_idx = 0

        for seg in range(num_segments):
            seg_angle = self._rotation + (seg / num_segments) * math.pi * 2
            seg_height = mirrored_heights[seg % 10]

            for j in range(points_per_segment):
                if entity_idx >= n:
                    break

                # Position along the bar (from center outward)
                bar_pos = (j + 0.5) / points_per_segment
                visible = bar_pos <= seg_height + 0.1

                # Radius from center
                r = base_radius + bar_pos * max_bar_length

                x = center + math.cos(seg_angle) * r
                z = center + math.sin(seg_angle) * r
                y = center  # Flat on horizontal plane

                # Scale
                band_idx = seg % 5
                scale = self.config.base_scale
                if visible:
                    # Brighter at the end
                    if bar_pos > seg_height - 0.2:
                        scale *= 1.3
                    scale += audio.bands[band_idx] * 0.2

                if audio.is_beat:
                    scale *= 1.2

                entities.append(
                    {
                        "id": f"block_{entity_idx}",
                        "x": clamp(x),
                        "y": clamp(y),
                        "z": clamp(z),
                        "scale": min(self.config.max_scale, scale) if visible else 0.01,
                        "band": band_idx,
                        "visible": visible,
                    }
                )
                entity_idx += 1

        self.update()
        return entities


# ============================================================================
# Pattern Registry
# ============================================================================

# Pattern registry - keep old names for backwards compatibility
PATTERNS = {
    # Original patterns
    "spectrum": StackedTower,
    "ring": ExpandingSphere,
    "wave": DNAHelix,
    "explode": Supernova,
    "columns": FloatingPlatforms,
    "orbit": AtomModel,
    "matrix": Fountain,
    "heartbeat": BreathingCube,
    # Epic patterns v1
    "mushroom": Mushroom,
    "skull": Skull,
    "sacred": SacredGeometry,
    "vortex": Vortex,
    "pyramid": Pyramid,
    "galaxy": GalaxySpiral,
    "laser": LaserArray,
    # New patterns v2 - Geometric/Abstract (frequency mapping)
    "mandala": Mandala,
    "tesseract": Tesseract,
    "crystal": CrystalGrowth,
    # New patterns v2 - Cosmic/Space (flow & energy)
    "blackhole": BlackHole,
    "nebula": Nebula,
    "wormhole": WormholePortal,
    # New patterns v2 - Organic/Nature (beat focus)
    "aurora": Aurora,
    "ocean": OceanWaves,
    "fireflies": Fireflies,
    # Classic spectrum analyzers
    "bars": SpectrumBars,
    "tubes": SpectrumTubes,
    "circle": SpectrumCircle,
}


def get_pattern(name: str, config: PatternConfig = None) -> VisualizationPattern:
    """Get a pattern by name."""
    pattern_class = PATTERNS.get(name.lower(), StackedTower)
    return pattern_class(config)


def list_patterns() -> List[Dict[str, str]]:
    """List all available patterns."""
    return [
        {"id": key, "name": cls.name, "description": cls.description}
        for key, cls in PATTERNS.items()
    ]
