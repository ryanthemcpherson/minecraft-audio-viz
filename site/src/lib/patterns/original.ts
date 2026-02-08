/**
 * Original 8 Visualization Patterns
 * Faithful TypeScript port of audio_processor/patterns.py (lines 127-841)
 */

import {
  type EntityData,
  type AudioState,
  type PatternConfig,
  VisualizationPattern,
  clamp,
  SeededRandom,
} from "./base";

// ---------------------------------------------------------------------------
// 1. StackedTower
// ---------------------------------------------------------------------------

export class StackedTower extends VisualizationPattern {
  static patternName = "Stacked Tower";
  static description = "Spiraling vertical tower - blocks orbit and bounce";

  private _rotation = 0.0;
  private _bounceWave: number[] = [];

  constructor(config?: Partial<PatternConfig>) {
    super(config);
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const center = 0.5;
    const n = this.config.entityCount;

    // Ensure bounce wave array matches entity count
    while (this._bounceWave.length < n) {
      this._bounceWave.push(0.0);
    }

    // Rotation speed based on energy
    this._rotation += (0.5 + audio.amplitude * 2.0) * 0.016;

    // Trigger bounce wave on beat
    if (audio.isBeat) {
      this._bounceWave[0] = 1.0;
    }

    // Propagate bounce wave upward
    for (let i = Math.min(n, this._bounceWave.length) - 1; i > 0; i--) {
      this._bounceWave[i] =
        this._bounceWave[i] * 0.9 + this._bounceWave[i - 1] * 0.15;
    }
    this._bounceWave[0] *= 0.85;

    for (let i = 0; i < n; i++) {
      // Vertical position - scale to fit within bounds
      const normalizedI = i / Math.max(1, n - 1); // 0 to 1
      const baseY = 0.1 + normalizedI * 0.6; // 0.1 to 0.7 base range

      // Add audio-reactive spread
      const spread = audio.amplitude * 0.15;
      let y = baseY + spread * normalizedI;

      // Spiral around center - more turns with more blocks
      const turns = 2 + n / 16;
      const angle = this._rotation + normalizedI * Math.PI * 2 * turns;
      const orbitRadius = 0.08 + audio.bands[i % 5] * 0.2;

      const x = center + Math.cos(angle) * orbitRadius;
      const z = center + Math.sin(angle) * orbitRadius;

      // Bounce effect
      const bounce =
        i < this._bounceWave.length ? this._bounceWave[i] : 0;
      y += bounce * 0.15;

      // Scale based on frequency band
      const bandIdx = i % 5;
      let scale = this.config.baseScale + audio.bands[bandIdx] * 0.6;

      if (audio.isBeat) {
        scale *= 1.5;
        y += 0.05;
      }

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y, 0.05, 0.95),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, scale),
        band: bandIdx,
        visible: true,
      });
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 2. ExpandingSphere
// ---------------------------------------------------------------------------

export class ExpandingSphere extends VisualizationPattern {
  static patternName = "Expanding Sphere";
  static description = "3D sphere that breathes and pulses";

  private _spherePoints: [number, number, number][] = [];
  private _rotationY = 0.0;
  private _rotationX = 0.0;
  private _breath = 0.0;

  constructor(config?: Partial<PatternConfig>) {
    super(config);
  }

  private _generateSpherePoints(n: number): [number, number, number][] {
    const points: [number, number, number][] = [];
    const phi = Math.PI * (3.0 - Math.sqrt(5.0)); // Golden angle

    for (let i = 0; i < n; i++) {
      const y = 1 - (i / (n - 1)) * 2; // y goes from 1 to -1
      const radius = Math.sqrt(1 - y * y);
      const theta = phi * i;

      const x = Math.cos(theta) * radius;
      const z = Math.sin(theta) * radius;
      points.push([x, y, z]);
    }

    return points;
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const n = this.config.entityCount;

    // Initialize sphere points
    if (this._spherePoints.length !== n) {
      this._spherePoints = this._generateSpherePoints(n);
    }

    // Rotation
    this._rotationY += 0.3 * 0.016;
    this._rotationX += 0.1 * 0.016;

    // Breathing effect
    let targetBreath = audio.bands[0] * 0.5 + audio.bands[1] * 0.3;
    if (audio.isBeat) {
      targetBreath += 0.3;
    }
    this._breath += (targetBreath - this._breath) * 0.15;

    const entities: EntityData[] = [];
    const center = 0.5;
    const baseRadius = 0.15 + this._breath * 0.2;

    for (let i = 0; i < n; i++) {
      const [px, py, pz] = this._spherePoints[i];

      // Apply Y rotation
      const cosY = Math.cos(this._rotationY);
      const sinY = Math.sin(this._rotationY);
      const rx = px * cosY - pz * sinY;
      const rz = px * sinY + pz * cosY;

      // Apply X rotation
      const cosX = Math.cos(this._rotationX);
      const sinX = Math.sin(this._rotationX);
      const ry = py * cosX - rz * sinX;
      const rz2 = py * sinX + rz * cosX;

      // Scale by radius and center
      const bandIdx = i % 5;
      const radius = baseRadius + audio.bands[bandIdx] * 0.1;

      const x = center + rx * radius;
      const y = center + ry * radius;
      const z = center + rz2 * radius;

      let scale = this.config.baseScale + audio.bands[bandIdx] * 0.4;
      if (audio.isBeat) {
        scale *= 1.4;
      }

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, scale),
        band: bandIdx,
        visible: true,
      });
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 3. DNAHelix
// ---------------------------------------------------------------------------

export class DNAHelix extends VisualizationPattern {
  static patternName = "DNA Helix";
  static description = "Double helix spiral - rotates and stretches";

  private _rotation = 0.0;
  private _stretch = 1.0;

  constructor(config?: Partial<PatternConfig>) {
    super(config);
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    // Rotation speed based on energy
    let speed = 1.0 + audio.amplitude * 2.0;
    if (audio.isBeat) {
      speed *= 1.5;
    }
    this._rotation += speed * 0.016;

    // Stretch based on bass
    const targetStretch =
      0.8 + audio.bands[0] * 0.6 + audio.bands[1] * 0.4;
    this._stretch += (targetStretch - this._stretch) * 0.1;

    // Helix parameters
    const radius = 0.15 + audio.amplitude * 0.1;
    for (let i = 0; i < n; i++) {
      // Alternate between two helixes
      const helix = i % 2;
      const idx = Math.floor(i / 2);

      // Position along helix
      const t = (idx / (n / 2)) * Math.PI * 3; // 3 full turns
      const angle =
        t + this._rotation + helix * Math.PI; // Offset second helix by 180 degrees

      // Helix coordinates
      let x = center + Math.cos(angle) * radius;
      let z = center + Math.sin(angle) * radius;
      const y = 0.1 + (idx / (n / 2)) * 0.8; // Spread vertically

      // Pulse radius with band
      const bandIdx = idx % 5;
      const pulse = audio.bands[bandIdx] * 0.05;
      x += Math.cos(angle) * pulse;
      z += Math.sin(angle) * pulse;

      let scale = this.config.baseScale + audio.bands[bandIdx] * 0.4;
      if (audio.isBeat) {
        scale *= 1.3;
      }

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, scale),
        band: bandIdx,
        visible: true,
      });
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 4. Supernova
// ---------------------------------------------------------------------------

export class Supernova extends VisualizationPattern {
  static patternName = "Supernova";
  static description = "Explosive burst on beats - 3D shockwave";

  private _rng = new SeededRandom(42);
  private _positions: [number, number, number][] = []; // [radius, theta, phi]
  private _velocities: number[] = [];
  private _targetRadius = 0.05;

  constructor(config?: Partial<PatternConfig>) {
    super(config);
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const n = this.config.entityCount;

    // Initialize random directions
    if (this._positions.length !== n) {
      this._positions = [];
      this._velocities = [];
      for (let i = 0; i < n; i++) {
        const theta = this._rng.uniform(0, Math.PI * 2);
        const phi = this._rng.uniform(0, Math.PI);
        this._positions.push([0.05, theta, phi]);
        this._velocities.push(0.0);
      }
    }

    // Beat triggers explosion
    if (audio.isBeat && audio.beatIntensity > 0.3) {
      for (let i = 0; i < n; i++) {
        this._velocities[i] = 0.8 + this._rng.uniform(0, 0.4);
        // Randomize direction slightly
        this._positions[i][1] += this._rng.uniform(-0.3, 0.3);
        this._positions[i][2] += this._rng.uniform(-0.2, 0.2);
      }
    }

    const entities: EntityData[] = [];
    const center = 0.5;

    for (let i = 0; i < n; i++) {
      // Update position
      this._positions[i][0] += this._velocities[i] * 0.016;
      this._velocities[i] *= 0.96; // Drag

      // Gravity back to center
      if (this._positions[i][0] > 0.05) {
        this._velocities[i] -= 0.02;
      }

      // Clamp radius
      this._positions[i][0] = Math.max(
        0.02,
        Math.min(0.45, this._positions[i][0])
      );

      // Spherical to cartesian
      const r = this._positions[i][0];
      const theta = this._positions[i][1];
      const phi = this._positions[i][2];

      const x = center + r * Math.sin(phi) * Math.cos(theta);
      const y = center + r * Math.cos(phi);
      const z = center + r * Math.sin(phi) * Math.sin(theta);

      const bandIdx = i % 5;
      const scale =
        this.config.baseScale +
        audio.bands[bandIdx] * 0.3 +
        Math.abs(this._velocities[i]) * 0.5;

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, scale),
        band: bandIdx,
        visible: true,
      });
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 5. FloatingPlatforms
// ---------------------------------------------------------------------------

export class FloatingPlatforms extends VisualizationPattern {
  static patternName = "Floating Platforms";
  static description = "6 levitating platforms - one per frequency";

  private _rotation = 0.0;
  private _platformY = [0.2, 0.35, 0.5, 0.65, 0.75];
  private _bounce = [0.0, 0.0, 0.0, 0.0, 0.0];

  constructor(config?: Partial<PatternConfig>) {
    super(config);
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    this._rotation += 0.5 * 0.016;

    // Update platform heights based on bands
    for (let band = 0; band < 5; band++) {
      const targetY = 0.15 + band * 0.14 + audio.bands[band] * 0.15;
      this._platformY[band] +=
        (targetY - this._platformY[band]) * 0.1;

      // Bounce on beat
      if (audio.isBeat && band < 3) {
        this._bounce[band] = 0.15;
      }
      this._bounce[band] *= 0.9;
    }

    // Distribute entities across 5 platforms
    const entitiesPerPlatform = Math.max(1, Math.floor(n / 5));

    for (let band = 0; band < 5; band++) {
      const platformAngle =
        this._rotation + band * ((Math.PI * 2) / 5);
      const platformRadius = 0.2 + audio.bands[band] * 0.1;

      for (let j = 0; j < entitiesPerPlatform; j++) {
        const entityIdx = band * entitiesPerPlatform + j;
        if (entityIdx >= n) {
          break;
        }

        // Spread blocks within platform
        const offsetAngle =
          (j / entitiesPerPlatform) * Math.PI * 0.5 - Math.PI * 0.25;
        const angle = platformAngle + offsetAngle;

        // Position
        const spread = 0.03 + audio.bands[band] * 0.02;
        const x =
          center +
          Math.cos(angle) * (platformRadius + j * spread * 0.3);
        const z =
          center +
          Math.sin(angle) * (platformRadius + j * spread * 0.3);
        const y = this._platformY[band] + this._bounce[band];

        const scale =
          this.config.baseScale + audio.bands[band] * 0.5;

        entities.push({
          id: `block_${entityIdx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(z),
          scale: Math.min(this.config.maxScale, scale),
          band: band,
          visible: true,
        });
      }
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 6. AtomModel
// ---------------------------------------------------------------------------

export class AtomModel extends VisualizationPattern {
  static patternName = "Atom Model";
  static description = "Nucleus + electrons on 3D orbital planes";

  private _rng = new SeededRandom(42);
  private _orbitAngles: number[] = [];
  private _nucleusPulse = 0.0;

  constructor(config?: Partial<PatternConfig>) {
    super(config);
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    // Ensure orbit angles array matches entity count
    while (this._orbitAngles.length < n) {
      this._orbitAngles.push(this._rng.uniform(0, Math.PI * 2));
    }

    // Nucleus pulse
    if (audio.isBeat) {
      this._nucleusPulse = 1.0;
    }
    this._nucleusPulse *= 0.9;

    // Nucleus: first 4 blocks clustered at center
    const nucleusCount = Math.min(4, n);
    const nucleusSpread =
      0.03 + this._nucleusPulse * 0.05 + audio.bands[0] * 0.03;

    for (let i = 0; i < nucleusCount; i++) {
      const angle = (i / nucleusCount) * Math.PI * 2;
      const x = center + Math.cos(angle) * nucleusSpread;
      const z = center + Math.sin(angle) * nucleusSpread;
      const y =
        center + Math.sin(angle * 2) * nucleusSpread * 0.5;

      const scale =
        this.config.baseScale +
        audio.bands[0] * 0.4 +
        this._nucleusPulse * 0.3;

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, scale),
        band: 0,
        visible: true,
      });
    }

    // Electrons: remaining blocks on 3 orbital planes
    const electronCount = n - nucleusCount;
    const electronsPerOrbit = Math.max(1, Math.floor(electronCount / 3));

    const orbitTilts: [number, number][] = [
      [0, 0],
      [Math.PI / 3, 0],
      [0, Math.PI / 3],
    ]; // [tiltX, tiltZ]
    const orbitSpeeds = [1.0, 1.3, 0.8];

    for (let orbit = 0; orbit < 3; orbit++) {
      const [tiltX, tiltZ] = orbitTilts[orbit];
      let speed = orbitSpeeds[orbit] * (1 + audio.amplitude);

      if (audio.isBeat) {
        speed *= 1.5;
      }

      for (let j = 0; j < electronsPerOrbit; j++) {
        const entityIdx =
          nucleusCount + orbit * electronsPerOrbit + j;
        if (entityIdx >= n) {
          break;
        }

        // Update orbit angle
        this._orbitAngles[entityIdx] += speed * 0.016;

        const angle =
          this._orbitAngles[entityIdx] +
          (j / electronsPerOrbit) * Math.PI * 2;
        const radius = 0.2 + orbit * 0.08;

        // Base position on XZ plane
        const px = Math.cos(angle) * radius;
        const py = 0;
        const pz = Math.sin(angle) * radius;

        // Apply tilts
        // Tilt around X axis
        const cosTx = Math.cos(tiltX);
        const sinTx = Math.sin(tiltX);
        const py2 = py * cosTx - pz * sinTx;
        const pz2 = py * sinTx + pz * cosTx;

        // Tilt around Z axis
        const cosTz = Math.cos(tiltZ);
        const sinTz = Math.sin(tiltZ);
        const px2 = px * cosTz - py2 * sinTz;
        const py3 = px * sinTz + py2 * cosTz;

        const x = center + px2;
        const y = center + py3;
        const z = center + pz2;

        const bandIdx = (orbit + 2) % 5;
        const scale =
          this.config.baseScale + audio.bands[bandIdx] * 0.3;

        entities.push({
          id: `block_${entityIdx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(z),
          scale: Math.min(this.config.maxScale, scale),
          band: bandIdx,
          visible: true,
        });
      }
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 7. Fountain
// ---------------------------------------------------------------------------

type FountainParticle = [number, number, number, number, number, number];
// [x, y, z, vx, vy, vz]

export class Fountain extends VisualizationPattern {
  static patternName = "Fountain";
  static description = "Upward spray with gravity arcs";

  private _rng = new SeededRandom(42);
  private _particles: FountainParticle[] = [];

  constructor(config?: Partial<PatternConfig>) {
    super(config);
  }

  private _spawnParticle(
    audio: AudioState,
    idx: number
  ): FountainParticle {
    const center = 0.5;

    // Random upward velocity
    let speed = 0.015 + audio.amplitude * 0.02;
    if (audio.isBeat) {
      speed *= 1.5;
    }

    const angle = this._rng.uniform(0, Math.PI * 2);
    const spread = 0.003 + audio.bands[idx % 5] * 0.002;

    const vx = Math.cos(angle) * spread;
    const vz = Math.sin(angle) * spread;
    const vy = speed + this._rng.uniform(0, 0.005);

    return [center, 0.05, center, vx, vy, vz];
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const n = this.config.entityCount;

    // Initialize particles
    if (this._particles.length !== n) {
      this._particles = [];
      for (let i = 0; i < n; i++) {
        this._particles.push(this._spawnParticle(audio, i));
      }
    }

    const entities: EntityData[] = [];
    const gravity = 0.015;

    for (let i = 0; i < n; i++) {
      let [x, y, z, vx, vy, vz] = this._particles[i];

      // Update physics
      vy -= gravity;
      x += vx * 0.016 * 60;
      y += vy * 0.016 * 60;
      z += vz * 0.016 * 60;

      // Respawn if below ground or on beat
      if (y < 0 || (audio.isBeat && this._rng.next() < 0.3)) {
        [x, y, z, vx, vy, vz] = this._spawnParticle(audio, i);
      }

      this._particles[i] = [x, y, z, vx, vy, vz];

      const bandIdx = i % 5;
      let scale = this.config.baseScale + audio.bands[bandIdx] * 0.4;

      // Scale based on height (bigger at peak)
      const heightScale = y > 0.5 ? 1.0 + (y - 0.5) * 0.5 : 1.0;
      scale *= heightScale;

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, scale),
        band: bandIdx,
        visible: true,
      });
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 8. BreathingCube
// ---------------------------------------------------------------------------

export class BreathingCube extends VisualizationPattern {
  static patternName = "Breathing Cube";
  static description = "Rotating cube vertices - expands with beats";

  private _rng = new SeededRandom(42);
  private _rotationX = 0.0;
  private _rotationY = 0.0;
  private _rotationZ = 0.0;
  private _breath = 0.5;
  private _points: [number, number, number][] = [];

  constructor(config?: Partial<PatternConfig>) {
    super(config);
  }

  private _generateCubePoints(n: number): [number, number, number][] {
    const points: [number, number, number][] = [];

    // Always include 8 vertices
    const vertices: [number, number, number][] = [
      [-1, -1, -1],
      [-1, -1, 1],
      [-1, 1, -1],
      [-1, 1, 1],
      [1, -1, -1],
      [1, -1, 1],
      [1, 1, -1],
      [1, 1, 1],
    ];
    points.push(...vertices);

    if (n <= 8) {
      return points.slice(0, n);
    }

    // Add edge midpoints (12 edges)
    const edgeMids: [number, number, number][] = [
      [0, -1, -1],
      [0, 1, -1],
      [0, -1, 1],
      [0, 1, 1], // X edges
      [-1, 0, -1],
      [1, 0, -1],
      [-1, 0, 1],
      [1, 0, 1], // Y edges
      [-1, -1, 0],
      [1, -1, 0],
      [-1, 1, 0],
      [1, 1, 0], // Z edges
    ];
    points.push(...edgeMids);

    if (n <= 20) {
      return points.slice(0, n);
    }

    // Add face centers (6 faces)
    const faceCenters: [number, number, number][] = [
      [0, 0, -1],
      [0, 0, 1], // Front/Back
      [-1, 0, 0],
      [1, 0, 0], // Left/Right
      [0, -1, 0],
      [0, 1, 0], // Bottom/Top
    ];
    points.push(...faceCenters);

    // If we need more, add subdivided points on faces
    while (points.length < n) {
      const face = this._rng.randint(0, 5);
      const u = this._rng.uniform(-0.8, 0.8);
      const v = this._rng.uniform(-0.8, 0.8);
      if (face === 0) points.push([u, v, -1]);
      else if (face === 1) points.push([u, v, 1]);
      else if (face === 2) points.push([-1, u, v]);
      else if (face === 3) points.push([1, u, v]);
      else if (face === 4) points.push([u, -1, v]);
      else points.push([u, 1, v]);
    }

    return points.slice(0, n);
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    // Generate points if needed
    if (this._points.length !== n) {
      this._points = this._generateCubePoints(n);
    }

    // Rotation speeds - more reactive
    this._rotationY += (0.5 + audio.amplitude * 1.5) * 0.016;
    this._rotationX += (0.2 + audio.bands[2] * 0.5) * 0.016;
    this._rotationZ += (0.1 + audio.bands[4] * 0.3) * 0.016;

    // Breathing - more dramatic
    let targetBreath =
      0.15 + audio.bands[0] * 0.15 + audio.bands[1] * 0.1;
    if (audio.isBeat) {
      targetBreath += 0.15;
    }
    this._breath += (targetBreath - this._breath) * 0.2;

    for (let i = 0; i < n; i++) {
      const [px, py, pz] = this._points[i];

      // Apply rotations
      // Y rotation
      const cosY = Math.cos(this._rotationY);
      const sinY = Math.sin(this._rotationY);
      const rx = px * cosY - pz * sinY;
      const rz = px * sinY + pz * cosY;

      // X rotation
      const cosX = Math.cos(this._rotationX);
      const sinX = Math.sin(this._rotationX);
      const ry = py * cosX - rz * sinX;
      const rz2 = py * sinX + rz * cosX;

      // Z rotation
      const cosZ = Math.cos(this._rotationZ);
      const sinZ = Math.sin(this._rotationZ);
      const rx2 = rx * cosZ - ry * sinZ;
      const ry2 = rx * sinZ + ry * cosZ;

      // Scale by breath and center
      const x = center + rx2 * this._breath;
      const y = center + ry2 * this._breath;
      const z = center + rz2 * this._breath;

      const bandIdx = i % 5;
      let scale =
        this.config.baseScale + audio.bands[bandIdx] * 0.4;

      if (audio.isBeat) {
        scale *= 1.4;
      }

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, scale),
        band: bandIdx,
        visible: true,
      });
    }

    this.update();
    return entities;
  }
}
