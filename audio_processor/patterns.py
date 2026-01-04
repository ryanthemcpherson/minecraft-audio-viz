"""
Visualization Patterns for AudioViz

Each pattern creates unique 3D formations with dynamic movement.
Designed for electronic music visualization.
"""

import math
import random
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Dict, Any


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
    bands: List[float]  # 6 frequency bands
    amplitude: float    # Overall amplitude 0-1
    is_beat: bool       # Beat detected this frame
    beat_intensity: float  # Beat strength 0-1
    frame: int          # Frame counter


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
            self._bounce_wave[i] = self._bounce_wave[i] * 0.9 + self._bounce_wave[i-1] * 0.15
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
            orbit_radius = 0.08 + audio.bands[i % 6] * 0.2

            x = center + math.cos(angle) * orbit_radius
            z = center + math.sin(angle) * orbit_radius

            # Bounce effect
            bounce = self._bounce_wave[i] if i < len(self._bounce_wave) else 0
            y += bounce * 0.15

            # Scale based on frequency band
            band_idx = i % 6
            scale = self.config.base_scale + audio.bands[band_idx] * 0.6

            if audio.is_beat:
                scale *= 1.5
                y += 0.05

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0.05, min(0.95, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

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
            band_idx = i % 6
            radius = base_radius + audio.bands[band_idx] * 0.1

            x = center + rx * radius
            y = center + ry * radius
            z = center + rz2 * radius

            scale = self.config.base_scale + audio.bands[band_idx] * 0.4
            if audio.is_beat:
                scale *= 1.4

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

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
        pitch = 0.08 * self._stretch

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
            band_idx = idx % 6
            pulse = audio.bands[band_idx] * 0.05
            x += math.cos(angle) * pulse
            z += math.sin(angle) * pulse

            scale = self.config.base_scale + audio.bands[band_idx] * 0.4
            if audio.is_beat:
                scale *= 1.3

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

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

            band_idx = i % 6
            scale = self.config.base_scale + audio.bands[band_idx] * 0.3 + abs(self._velocities[i]) * 0.5

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

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
        self._platform_y = [0.2, 0.35, 0.5, 0.65, 0.75, 0.85]
        self._bounce = [0.0] * 6

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        self._rotation += 0.5 * 0.016

        # Update platform heights based on bands
        for band in range(6):
            target_y = 0.15 + band * 0.12 + audio.bands[band] * 0.15
            self._platform_y[band] += (target_y - self._platform_y[band]) * 0.1

            # Bounce on beat
            if audio.is_beat and band < 3:
                self._bounce[band] = 0.15
            self._bounce[band] *= 0.9

        # Distribute entities across 6 platforms
        entities_per_platform = max(1, n // 6)

        for band in range(6):
            platform_angle = self._rotation + band * (math.pi / 3)
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

                entities.append({
                    "id": f"block_{entity_idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band,
                    "visible": True
                })

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

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": 0,
                "visible": True
            })

        # Electrons: remaining blocks on 3 orbital planes
        electron_count = n - nucleus_count
        electrons_per_orbit = max(1, electron_count // 3)

        orbit_tilts = [(0, 0), (math.pi/3, 0), (0, math.pi/3)]  # (tilt_x, tilt_z)
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

                band_idx = (orbit + 2) % 6
                scale = self.config.base_scale + audio.bands[band_idx] * 0.3

                entities.append({
                    "id": f"block_{entity_idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True
                })

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
        self._particles = []  # [(x, y, z, vx, vy, vz)]

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        n = self.config.entity_count

        # Initialize particles
        if len(self._particles) != n:
            self._particles = []
            for i in range(n):
                self._particles.append(self._spawn_particle(audio, i))

        entities = []
        center = 0.5
        gravity = 0.015

        for i in range(n):
            p = self._particles[i]
            x, y, z, vx, vy, vz = p

            # Update physics
            vy -= gravity
            x += vx * 0.016 * 60
            y += vy * 0.016 * 60
            z += vz * 0.016 * 60

            # Respawn if below ground or on beat
            if y < 0 or (audio.is_beat and random.random() < 0.3):
                x, y, z, vx, vy, vz = self._spawn_particle(audio, i)

            self._particles[i] = (x, y, z, vx, vy, vz)

            band_idx = i % 6
            scale = self.config.base_scale + audio.bands[band_idx] * 0.4

            # Scale based on height (bigger at peak)
            height_scale = 1.0 + (y - 0.5) * 0.5 if y > 0.5 else 1.0
            scale *= height_scale

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

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
        spread = 0.003 + audio.bands[idx % 6] * 0.002

        vx = math.cos(angle) * spread
        vz = math.sin(angle) * spread
        vy = speed + random.uniform(0, 0.005)

        return (center, 0.05, center, vx, vy, vz)


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
            (-1, -1, -1), (-1, -1, 1), (-1, 1, -1), (-1, 1, 1),
            (1, -1, -1), (1, -1, 1), (1, 1, -1), (1, 1, 1),
        ]
        points.extend(vertices)

        if n <= 8:
            return points[:n]

        # Add edge midpoints (12 edges)
        edge_mids = [
            (0, -1, -1), (0, 1, -1), (0, -1, 1), (0, 1, 1),  # X edges
            (-1, 0, -1), (1, 0, -1), (-1, 0, 1), (1, 0, 1),  # Y edges
            (-1, -1, 0), (1, -1, 0), (-1, 1, 0), (1, 1, 0),  # Z edges
        ]
        points.extend(edge_mids)

        if n <= 20:
            return points[:n]

        # Add face centers (6 faces)
        face_centers = [
            (0, 0, -1), (0, 0, 1),   # Front/Back
            (-1, 0, 0), (1, 0, 0),   # Left/Right
            (0, -1, 0), (0, 1, 0),   # Bottom/Top
        ]
        points.extend(face_centers)

        # If we need more, add subdivided points on faces
        while len(points) < n:
            # Add random points on cube surface
            face = random.randint(0, 5)
            u, v = random.uniform(-0.8, 0.8), random.uniform(-0.8, 0.8)
            if face == 0: points.append((u, v, -1))
            elif face == 1: points.append((u, v, 1))
            elif face == 2: points.append((-1, u, v))
            elif face == 3: points.append((1, u, v))
            elif face == 4: points.append((u, -1, v))
            else: points.append((u, 1, v))

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

            band_idx = i % 6
            scale = self.config.base_scale + audio.bands[band_idx] * 0.4

            if audio.is_beat:
                scale *= 1.4

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

        self.update()
        return entities


class Mushroom(VisualizationPattern):
    """
    Psychedelic mushroom - dome cap with stem.
    Classic festival visual that pulses and glows.
    """

    name = "Mushroom"
    description = "Psychedelic mushroom - pulses and glows"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._pulse = 0.0
        self._glow = 0.0

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Rotation and pulse
        self._rotation += (0.3 + audio.amplitude * 0.5) * 0.016

        # Pulse on beat
        if audio.is_beat:
            self._pulse = 1.0
            self._glow = 1.0
        self._pulse *= 0.92
        self._glow *= 0.95

        # Allocate entities: 30% stem, 70% cap
        stem_count = max(4, n // 3)
        cap_count = n - stem_count

        # === STEM (cylinder) ===
        stem_radius = 0.06 + self._pulse * 0.02
        stem_height = 0.35
        rings = max(2, stem_count // 6)
        per_ring = stem_count // rings

        for ring in range(rings):
            ring_y = 0.1 + (ring / rings) * stem_height
            ring_radius = stem_radius * (1.0 - ring * 0.1)  # Taper slightly

            for j in range(per_ring):
                idx = ring * per_ring + j
                if idx >= stem_count:
                    break

                angle = self._rotation + (j / per_ring) * math.pi * 2
                x = center + math.cos(angle) * ring_radius
                z = center + math.sin(angle) * ring_radius
                y = ring_y

                band_idx = idx % 6
                scale = self.config.base_scale * 0.8 + audio.bands[band_idx] * 0.2

                entities.append({
                    "id": f"block_{idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True
                })

        # === CAP (dome/hemisphere) ===
        cap_base_y = 0.1 + stem_height
        cap_radius = 0.2 + self._pulse * 0.08 + audio.bands[0] * 0.05
        layers = max(3, int(math.sqrt(cap_count)))

        cap_idx = 0
        for layer in range(layers):
            # Phi angle from 0 (top) to pi/2 (equator)
            phi = (layer / (layers - 1)) * (math.pi / 2) if layers > 1 else 0
            layer_radius = cap_radius * math.sin(phi)
            layer_y = cap_base_y + cap_radius * math.cos(phi) * 0.6  # Flatten dome

            # More points on outer layers
            points_this_layer = max(3, int((layer + 1) * cap_count / (layers * (layers + 1) / 2)))

            for j in range(points_this_layer):
                if cap_idx >= cap_count:
                    break

                angle = self._rotation * 0.5 + (j / points_this_layer) * math.pi * 2
                x = center + math.cos(angle) * layer_radius
                z = center + math.sin(angle) * layer_radius
                y = layer_y

                entity_idx = stem_count + cap_idx
                band_idx = cap_idx % 6

                # Cap blocks are larger and more reactive
                scale = self.config.base_scale + audio.bands[band_idx] * 0.5 + self._glow * 0.3

                entities.append({
                    "id": f"block_{entity_idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True
                })
                cap_idx += 1

        self.update()
        return entities


class Skull(VisualizationPattern):
    """
    3D skull formation - cranium, jaw, eye sockets.
    Epic for drops and dark sections of tracks.
    """

    name = "Skull"
    description = "3D skull - jaw moves with bass"

    def __init__(self, config: PatternConfig = None):
        super().__init__(config)
        self._rotation = 0.0
        self._jaw_open = 0.0
        self._eye_glow = 0.0
        self._skull_points = None

    def _generate_skull_points(self, n: int) -> List[tuple]:
        """Generate skull formation points."""
        points = []

        # Cranium (sphere, upper portion) - 50% of points
        cranium_count = n // 2
        phi = math.pi * (3.0 - math.sqrt(5.0))  # Golden angle

        for i in range(cranium_count):
            y = 0.3 + (1 - (i / float(cranium_count - 1)) * 2) * 0.25  # Upper hemisphere
            y = max(0.3, y)  # Keep in upper region
            radius = math.sqrt(max(0, 1 - ((y - 0.55) / 0.25) ** 2)) * 0.2
            theta = phi * i

            x = math.cos(theta) * radius
            z = math.sin(theta) * radius
            points.append(('cranium', x, y, z))

        # Jaw (lower arc) - 20% of points
        jaw_count = n // 5
        for i in range(jaw_count):
            angle = -math.pi * 0.4 + (i / jaw_count) * math.pi * 0.8
            x = math.sin(angle) * 0.15
            z = -math.cos(angle) * 0.08 - 0.05
            y = 0.15
            points.append(('jaw', x, y, z))

        # Eye sockets (2 small clusters) - 15% each
        eye_count = n // 7
        for eye_side in [-1, 1]:
            for i in range(eye_count):
                angle = (i / eye_count) * math.pi * 2
                radius = 0.03
                x = eye_side * 0.08 + math.cos(angle) * radius
                y = 0.45 + math.sin(angle) * radius * 0.5
                z = -0.12
                points.append(('eye', x, y, z))

        # Teeth (bottom row)
        teeth_count = min(8, n // 10)
        for i in range(teeth_count):
            x = -0.1 + (i / teeth_count) * 0.2
            y = 0.18
            z = -0.1
            points.append(('teeth', x, y, z))

        return points[:n]

    def calculate_entities(self, audio: AudioState) -> List[Dict]:
        entities = []
        n = self.config.entity_count
        center = 0.5

        # Generate points if needed
        if self._skull_points is None or len(self._skull_points) != n:
            self._skull_points = self._generate_skull_points(n)

        # Slow menacing rotation
        self._rotation += 0.2 * 0.016

        # Jaw opens with bass
        target_jaw = audio.bands[0] * 0.15 + audio.bands[1] * 0.1
        if audio.is_beat:
            target_jaw += 0.1
        self._jaw_open += (target_jaw - self._jaw_open) * 0.15

        # Eye glow on beat
        if audio.is_beat:
            self._eye_glow = 1.0
        self._eye_glow *= 0.9

        cos_r = math.cos(self._rotation)
        sin_r = math.sin(self._rotation)

        for i, point in enumerate(self._skull_points):
            part_type, px, py, pz = point

            # Apply jaw movement
            if part_type == 'jaw':
                py -= self._jaw_open
            elif part_type == 'teeth':
                py -= self._jaw_open * 0.5

            # Rotate around Y axis
            rx = px * cos_r - pz * sin_r
            rz = px * sin_r + pz * cos_r

            x = center + rx
            y = center + py - 0.1  # Lower the skull a bit
            z = center + rz

            band_idx = i % 6
            scale = self.config.base_scale

            if part_type == 'eye':
                scale += self._eye_glow * 0.5 + audio.bands[4] * 0.3
            elif part_type == 'cranium':
                scale += audio.bands[band_idx] * 0.3
            elif part_type == 'jaw':
                scale += audio.bands[0] * 0.4

            if audio.is_beat:
                scale *= 1.2

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

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
            mag = math.sqrt(px*px + py*py + pz*pz)
            if mag > 0:
                px, py, pz = px/mag, py/mag, pz/mag

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

            band_idx = i % 6
            scale = self.config.base_scale + audio.bands[band_idx] * 0.4

            if audio.is_beat:
                scale *= 1.3

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

        # Fill remaining with additional recursive points
        for i in range(len(points_to_use), n):
            # Create inner layer
            scale_factor = 0.5
            point_idx = i % len(points_to_use)
            px, py, pz = points_to_use[point_idx]

            mag = math.sqrt(px*px + py*py + pz*pz)
            if mag > 0:
                px, py, pz = px/mag * scale_factor, py/mag * scale_factor, pz/mag * scale_factor

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

            band_idx = i % 6
            scale = self.config.base_scale * 0.7 + audio.bands[band_idx] * 0.3

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

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
        speed = 2.0 + audio.amplitude * 3.0
        if audio.is_beat:
            speed *= 1.5
            self._intensity = 1.0
        self._rotation += speed * 0.016
        self._intensity *= 0.95

        # Z movement (flying through tunnel)
        self._z_offset += (0.5 + audio.bands[0] * 0.5) * 0.016
        if self._z_offset > 1.0:
            self._z_offset -= 1.0

        # Rings of entities forming tunnel
        rings = max(4, n // 8)
        per_ring = n // rings

        for ring in range(rings):
            # Depth (z position) - closer rings are larger
            ring_z = (ring / rings + self._z_offset) % 1.0
            depth = ring_z  # 0 = close, 1 = far

            # Ring radius - smaller as it goes further
            base_radius = 0.35 - depth * 0.25
            pulse_radius = base_radius + self._intensity * 0.1 * (1 - depth)

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
                band_idx = j % 6
                radius += audio.bands[band_idx] * 0.05 * (1 - depth)

                x = center + math.cos(angle) * radius
                z_pos = center + math.sin(angle) * radius
                y = 0.1 + depth * 0.8  # Map depth to y position

                # Scale - larger when close
                scale = self.config.base_scale * (1.5 - depth) + audio.bands[band_idx] * 0.3

                if audio.is_beat:
                    scale *= 1.2

                entities.append({
                    "id": f"block_{idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z_pos)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True
                })

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
        self._rotation += (0.3 + audio.amplitude * 0.3) * 0.016

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
            layer_y = 0.1 + layer_norm * 0.7 + self._hover

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

                    band_idx = entity_idx % 6
                    scale = self.config.base_scale + audio.bands[band_idx] * 0.4

                    # Apex glows more
                    if layer_norm > 0.8:
                        scale += audio.amplitude * 0.3

                    if audio.is_beat:
                        scale *= 1.25

                    entities.append({
                        "id": f"block_{entity_idx}",
                        "x": max(0, min(1, x)),
                        "y": max(0, min(1, y)),
                        "z": max(0, min(1, z)),
                        "scale": min(self.config.max_scale, scale),
                        "band": band_idx,
                        "visible": True
                    })
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
        self._arm_twist = 2.0 + audio.bands[4] * 2.0 + audio.bands[5] * 1.0

        # Core pulse on beat
        if audio.is_beat:
            self._core_pulse = 1.0
        self._core_pulse *= 0.9

        # Core entities (dense center) - 20%
        core_count = n // 5

        # Spiral arm entities - 80%
        arm_count = n - core_count
        num_arms = 2  # Classic spiral galaxy

        # === CORE ===
        for i in range(core_count):
            # Random but consistent distribution in center
            seed_angle = (i * 137.5) * math.pi / 180  # Golden angle
            seed_radius = (i / core_count) ** 0.5 * 0.08  # Square root for uniform disk

            angle = seed_angle + self._rotation * 2
            radius = seed_radius * (1.0 + self._core_pulse * 0.3)

            x = center + math.cos(angle) * radius
            z = center + math.sin(angle) * radius
            y = center + math.sin(seed_angle * 3) * 0.02  # Slight thickness

            band_idx = i % 6
            scale = self.config.base_scale * 1.2 + audio.bands[band_idx] * 0.3 + self._core_pulse * 0.2

            entities.append({
                "id": f"block_{i}",
                "x": max(0, min(1, x)),
                "y": max(0, min(1, y)),
                "z": max(0, min(1, z)),
                "scale": min(self.config.max_scale, scale),
                "band": band_idx,
                "visible": True
            })

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
                radius = 0.08 + t * 0.3

                # Spiral angle
                spiral_angle = arm_offset + self._rotation + t * math.pi * self._arm_twist

                # Add some scatter for natural look
                scatter = math.sin(j * 0.5) * 0.02
                radius += scatter

                x = center + math.cos(spiral_angle) * radius
                z = center + math.sin(spiral_angle) * radius

                # Slight vertical variation
                y = center + math.sin(spiral_angle * 2) * 0.03 * t

                band_idx = j % 6
                # Outer stars react more to highs, inner to bass
                bass_react = (1 - t) * audio.bands[0] * 0.3
                high_react = t * audio.bands[4] * 0.3
                scale = self.config.base_scale + bass_react + high_react

                if audio.is_beat:
                    scale *= 1.15

                entities.append({
                    "id": f"block_{idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True
                })

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
            band_idx = beam % 6
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

                entities.append({
                    "id": f"block_{idx}",
                    "x": max(0, min(1, x)),
                    "y": max(0, min(1, y)),
                    "z": max(0, min(1, z)),
                    "scale": min(self.config.max_scale, scale),
                    "band": band_idx,
                    "visible": True
                })

        self.update()
        return entities


# Pattern registry - keep old names for backwards compatibility
PATTERNS = {
    "spectrum": StackedTower,
    "ring": ExpandingSphere,
    "wave": DNAHelix,
    "explode": Supernova,
    "columns": FloatingPlatforms,
    "orbit": AtomModel,
    "matrix": Fountain,
    "heartbeat": BreathingCube,
    # New epic patterns
    "mushroom": Mushroom,
    "skull": Skull,
    "sacred": SacredGeometry,
    "vortex": Vortex,
    "pyramid": Pyramid,
    "galaxy": GalaxySpiral,
    "laser": LaserArray,
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
