/**
 * Cosmic / Geometric Patterns
 * Ported from audio_processor/patterns.py
 *
 * BlackHole, Nebula, Mandala, Tesseract, CrystalGrowth
 */

import {
  VisualizationPattern,
  type AudioState,
  type EntityData,
  clamp,
  fibonacciSphere,
  SeededRandom,
} from "./base";

// ---------------------------------------------------------------------------
// BlackHole
// ---------------------------------------------------------------------------

export class BlackHole extends VisualizationPattern {
  static patternName = "Black Hole";
  static description = "Accretion disk with jets - gravity visualization";

  private _particles: number[][] = []; // [r, theta, dr, layer]
  private _jetIntensity = 0;
  private _rotation = 0;
  private _warp = 0;
  private _rng = new SeededRandom(42);

  calculateEntities(audio: AudioState): EntityData[] {
    const n = this.config.entityCount;
    const center = 0.5;

    // Allocate: 75% disk, 25% jets
    const diskCount = Math.floor(n * 0.75);
    const jetCount = n - diskCount;

    // Initialise particles
    if (this._particles.length !== diskCount) {
      this._particles = [];
      for (let i = 0; i < diskCount; i++) {
        const r = 0.1 + this._rng.next() * 0.3;
        const theta = this._rng.next() * Math.PI * 2;
        const dr = 0;
        const layer = this._rng.randint(0, 2);
        this._particles.push([r, theta, dr, layer]);
      }
    }

    // Jet intensity on beat
    if (audio.isBeat) {
      this._jetIntensity = 1.0;
      this._warp = 0.5;
    }
    this._jetIntensity *= 0.92;
    this._warp *= 0.95;

    // Accretion rate from bass
    const accretionRate =
      0.001 + audio.bands[0] * 0.003 + audio.bands[1] * 0.002;

    this._rotation += 0.5 * 0.016;

    const entities: EntityData[] = [];

    // === ACCRETION DISK ===
    for (let i = 0; i < diskCount; i++) {
      let [r, theta, , layer] = this._particles[i];

      // Keplerian velocity: v proportional to 1/sqrt(r)
      const orbitalSpeed = 0.5 / Math.sqrt(Math.max(0.05, r));
      theta += orbitalSpeed * 0.016;

      // Spiral inward (accretion)
      const dr = -accretionRate * (1.0 + this._rng.next() * 0.5);
      r += dr;

      // Respawn if too close to center
      if (r < 0.05) {
        r = 0.35 + this._rng.next() * 0.1;
        theta = this._rng.next() * Math.PI * 2;
        layer = this._rng.randint(0, 2);
      }

      this._particles[i] = [r, theta, dr, layer];

      // Position
      let x = center + Math.cos(theta) * r;
      let z = center + Math.sin(theta) * r;

      // Thin disk with slight vertical variation
      const layerOffset = (layer - 1) * 0.02;
      const y = center + layerOffset + Math.sin(theta * 4) * 0.01;

      // Warp effect near center (light bending)
      if (r < 0.15) {
        const warpStrength = ((0.15 - r) / 0.15) * this._warp;
        x = center + (x - center) * (1.0 - warpStrength * 0.3);
        z = center + (z - center) * (1.0 - warpStrength * 0.3);
      }

      let bandIdx = i % 5;
      // Inner disk hotter (higher frequencies)
      if (r < 0.15) {
        bandIdx = 3 + (i % 2);
      } else if (r < 0.25) {
        bandIdx = 2 + (i % 2);
      }

      let scale = this.config.baseScale * (0.5 + (0.4 - r));
      scale += audio.bands[bandIdx] * 0.2;

      if (audio.isBeat && r < 0.2) {
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

    // === RELATIVISTIC JETS ===
    const jetHeight =
      0.3 + this._jetIntensity * 0.15 + audio.bands[0] * 0.1;
    const pointsPerJet = Math.floor(jetCount / 2);

    for (let jet = 0; jet < 2; jet++) {
      const direction = jet === 0 ? 1 : -1;

      for (let j = 0; j < pointsPerJet; j++) {
        const idx = diskCount + jet * pointsPerJet + j;
        if (idx >= n) break;

        // Position along jet
        const t = (j + 1) / pointsPerJet;
        const jetR = 0.02 + t * 0.04; // Slight cone shape
        const jetY = center + direction * (0.05 + t * jetHeight);

        // Spiral in jet
        const jetAngle =
          this._rotation * 3 + t * Math.PI * 2 + jet * Math.PI;
        const x = center + Math.cos(jetAngle) * jetR;
        const z = center + Math.sin(jetAngle) * jetR;
        const y = jetY;

        const bandIdx = 3 + (j % 2);
        let scale =
          this.config.baseScale *
          (1.0 - t * 0.5) *
          (0.5 + this._jetIntensity);
        scale += audio.bands[4] * 0.3;

        entities.push({
          id: `block_${idx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(z),
          scale: Math.min(this.config.maxScale, scale),
          band: bandIdx,
          visible: this._jetIntensity > 0.1 || audio.bands[0] > 0.3,
        });
      }
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// Nebula
// ---------------------------------------------------------------------------

export class Nebula extends VisualizationPattern {
  static patternName = "Nebula";
  static description = "Cosmic gas cloud with drifting particles";

  private _particles: number[][] = []; // [x, y, z, phase]
  private _expansion = 1.0;
  private _flashParticles: Set<number> = new Set();
  private _driftTime = 0;
  private _rng = new SeededRandom(42);

  calculateEntities(audio: AudioState): EntityData[] {
    const n = this.config.entityCount;
    const center = 0.5;

    // Initialise particles in spherical distribution
    if (this._particles.length !== n) {
      this._particles = [];
      const points = fibonacciSphere(n);
      for (let i = 0; i < n; i++) {
        const [fx, fy, fz] = points[i];
        const r = 0.3 + this._rng.next() * 0.7;
        const phase = this._rng.next() * Math.PI * 2;
        this._particles.push([fx * r, fy * r, fz * r, phase]);
      }
    }

    this._driftTime += 0.016;

    // Expansion with amplitude
    const targetExpansion = 0.8 + audio.amplitude * 0.4;
    this._expansion += (targetExpansion - this._expansion) * 0.1;

    // Beat triggers star flashes (random subset)
    if (audio.isBeat) {
      this._flashParticles = new Set<number>();
      const count = Math.min(Math.floor(n / 4), 20);
      // deterministic selection using rng
      for (let k = 0; k < count; k++) {
        this._flashParticles.add(this._rng.randint(0, n - 1));
      }
    } else {
      if (this._rng.next() < 0.3) {
        this._flashParticles = new Set();
      }
    }

    const entities: EntityData[] = [];
    const baseRadius = 0.25 * this._expansion;

    for (let i = 0; i < n; i++) {
      const [px, py, pz, phase] = this._particles[i];

      // Smooth drifting motion
      const driftX = Math.sin(this._driftTime * 0.3 + phase) * 0.02;
      const driftY = Math.cos(this._driftTime * 0.2 + phase * 1.3) * 0.02;
      const driftZ = Math.sin(this._driftTime * 0.25 + phase * 0.7) * 0.02;

      // Update particle position with drift
      this._particles[i][0] += driftX * 0.1;
      this._particles[i][1] += driftY * 0.1;
      this._particles[i][2] += driftZ * 0.1;

      // Keep within bounds (soft boundary)
      const dist = Math.sqrt(px * px + py * py + pz * pz);
      if (dist > 1.2) {
        this._particles[i][0] *= 0.99;
        this._particles[i][1] *= 0.99;
        this._particles[i][2] *= 0.99;
      }

      // World position
      const x = center + px * baseRadius + driftX;
      const y = center + py * baseRadius + driftY;
      const z = center + pz * baseRadius + driftZ;

      // Band based on position (higher Y = higher frequency)
      const normalizedY = (py + 1) / 2;
      let bandIdx = Math.floor(normalizedY * 4.9);
      bandIdx = Math.max(0, Math.min(4, bandIdx));

      // Scale based on density (denser near center)
      const densityScale = 1.0 - dist * 0.3;
      let scale = this.config.baseScale * densityScale;
      scale += audio.bands[bandIdx] * 0.25;

      // Flash effect for "star birth"
      if (this._flashParticles.has(i)) {
        scale *= 2.0;
        bandIdx = 5;
      }

      if (audio.isBeat) {
        scale *= 1.15;
      }

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, Math.max(0.05, scale)),
        band: bandIdx,
        visible: true,
      });
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// Mandala
// ---------------------------------------------------------------------------

export class Mandala extends VisualizationPattern {
  static patternName = "Mandala";
  static description = "Sacred geometry rings - frequency mapped";

  private _ringRotations = [0, 0, 0, 0, 0];
  private _pulse = 0;
  private _petalBoost = 0;

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    // Beat pulse
    if (audio.isBeat) {
      this._pulse = 1.0;
      this._petalBoost = 1.0;
    }
    this._pulse *= 0.9;
    this._petalBoost *= 0.85;

    // Golden angle for petal distribution
    const goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));

    // Distribute entities across 5 rings
    const pointsPerRing = Math.floor(n / 5);
    let entityIdx = 0;

    for (let ring = 0; ring < 5; ring++) {
      // Ring properties
      const baseRadius = 0.08 + ring * 0.07;

      // Radius pulses with corresponding frequency band
      const radius =
        baseRadius + audio.bands[ring] * 0.04 + this._pulse * 0.02;

      // Rotation speed: inner slower, outer faster; alternate direction
      const direction = ring % 2 === 0 ? 1 : -1;
      let speed = (0.2 + ring * 0.18) * direction;
      speed *= 1.0 + audio.bands[ring] * 0.5;

      this._ringRotations[ring] += speed * 0.016;

      // Points on this ring
      for (let j = 0; j < pointsPerRing; j++) {
        if (entityIdx >= n) break;

        // Golden angle distribution for petal pattern
        const angle = this._ringRotations[ring] + j * goldenAngle;

        const x = center + Math.cos(angle) * radius;
        const z = center + Math.sin(angle) * radius;

        // Y varies slightly by position for 3D depth
        const y = center + Math.sin(angle * 2) * 0.02 * (1 + ring * 0.3);

        // Scale based on band intensity
        let scale = this.config.baseScale + audio.bands[ring] * 0.5;
        scale += this._petalBoost * 0.3 * (1.0 - ring / 5);

        if (audio.isBeat) {
          scale *= 1.2;
        }

        entities.push({
          id: `block_${entityIdx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(z),
          scale: Math.min(this.config.maxScale, scale),
          band: ring,
          visible: true,
        });
        entityIdx++;
      }
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// Tesseract
// ---------------------------------------------------------------------------

export class Tesseract extends VisualizationPattern {
  static patternName = "Tesseract";
  static description = "4D hypercube rotating through dimensions";

  private _rotationXw = 0;
  private _rotationYw = 0;
  private _rotationZw = 0;
  private _rotationXy = 0;
  private _pulse = 0;
  private _vertices: number[][] | null = null;
  private _edges: number[][] | null = null;

  private _generateTesseract(): { vertices: number[][]; edges: number[][] } {
    // 16 vertices of tesseract (all combinations of +/-1 in 4D)
    const vertices: number[][] = [];
    for (const x of [-1, 1]) {
      for (const y of [-1, 1]) {
        for (const z of [-1, 1]) {
          for (const w of [-1, 1]) {
            vertices.push([x, y, z, w]);
          }
        }
      }
    }

    // 32 edges connect vertices that differ in exactly one coordinate
    const edges: number[][] = [];
    for (let i = 0; i < vertices.length; i++) {
      for (let j = i + 1; j < vertices.length; j++) {
        let diff = 0;
        for (let k = 0; k < 4; k++) {
          if (vertices[i][k] !== vertices[j][k]) diff++;
        }
        if (diff === 1) {
          // Store midpoint
          const mid = [
            (vertices[i][0] + vertices[j][0]) / 2,
            (vertices[i][1] + vertices[j][1]) / 2,
            (vertices[i][2] + vertices[j][2]) / 2,
            (vertices[i][3] + vertices[j][3]) / 2,
          ];
          edges.push(mid);
        }
      }
    }

    return { vertices, edges };
  }

  private _rotate4d(
    point: number[],
    rxw: number,
    ryw: number,
    rzw: number,
    rxy: number
  ): number[] {
    const [x, y, z, w] = point;

    // XW rotation (bass)
    const cosXw = Math.cos(rxw),
      sinXw = Math.sin(rxw);
    const x1 = x * cosXw - w * sinXw;
    const w1 = x * sinXw + w * cosXw;

    // YW rotation (mids)
    const cosYw = Math.cos(ryw),
      sinYw = Math.sin(ryw);
    const y1 = y * cosYw - w1 * sinYw;
    const w2 = y * sinYw + w1 * cosYw;

    // ZW rotation (highs)
    const cosZw = Math.cos(rzw),
      sinZw = Math.sin(rzw);
    const z1 = z * cosZw - w2 * sinZw;
    const w3 = z * sinZw + w2 * cosZw;

    // XY rotation (visual spin)
    const cosXy = Math.cos(rxy),
      sinXy = Math.sin(rxy);
    const x2 = x1 * cosXy - y1 * sinXy;
    const y2 = x1 * sinXy + y1 * cosXy;

    return [x2, y2, z1, w3];
  }

  private _project4dTo3d(
    point: number[],
    distance = 2.0
  ): number[] {
    const [x, y, z, w] = point;
    const s = distance / (distance - w * 0.5);
    return [x * s, y * s, z * s, w];
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const n = this.config.entityCount;

    // Generate geometry
    if (this._vertices === null || this._edges === null) {
      const geom = this._generateTesseract();
      this._vertices = geom.vertices;
      this._edges = geom.edges;
    }

    // Rotation speeds driven by frequency bands
    this._rotationXw += (0.2 + audio.bands[0] * 0.8) * 0.016;
    this._rotationYw += (0.15 + audio.bands[2] * 0.6) * 0.016;
    this._rotationZw +=
      (0.1 + audio.bands[4] * 0.5 + audio.bands[4] * 0.3) * 0.016;
    this._rotationXy += 0.3 * 0.016;

    // Beat pulse
    if (audio.isBeat) {
      this._pulse = 1.0;
    }
    this._pulse *= 0.9;

    const entities: EntityData[] = [];
    const center = 0.5;
    const baseScaleLocal = 0.15 + this._pulse * 0.05;

    // Combine vertices and edge midpoints
    const allPoints = [...this._vertices, ...this._edges];
    const pointsToUse = Math.min(n, allPoints.length);

    for (let i = 0; i < pointsToUse; i++) {
      const point = allPoints[i];

      // Apply 4D rotations
      const rotated = this._rotate4d(
        point,
        this._rotationXw,
        this._rotationYw,
        this._rotationZw,
        this._rotationXy
      );

      // Project to 3D
      const projected = this._project4dTo3d(rotated);
      const [px, py, pz, pw] = projected;

      // Position in world
      const x = center + px * baseScaleLocal;
      const y = center + py * baseScaleLocal;
      const z = center + pz * baseScaleLocal;

      // Band based on w coordinate (4th dimension position)
      const wNormalized = (pw + 1) / 2;
      let bandIdx = Math.floor(wNormalized * 4.9);
      bandIdx = clamp(bandIdx, 0, 4);

      // Scale based on w (outer cube bigger when highs are strong)
      const wScale = 0.7 + wNormalized * 0.6;
      let scale = this.config.baseScale * wScale;
      scale += audio.bands[bandIdx] * 0.4;

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

    // Fill remaining with inner vertices at smaller scale
    for (let i = pointsToUse; i < n; i++) {
      const pointIdx = i % this._vertices.length;
      const point = this._vertices[pointIdx];

      const rotated = this._rotate4d(
        point,
        this._rotationXw * 0.5,
        this._rotationYw * 0.5,
        this._rotationZw * 0.5,
        this._rotationXy
      );
      const projected = this._project4dTo3d(rotated);
      const [px, py, pz] = projected;

      const innerScale = baseScaleLocal * 0.5;

      const x = center + px * innerScale;
      const y = center + py * innerScale;
      const z = center + pz * innerScale;

      const bandIdx = i % 5;
      const scale = this.config.baseScale * 0.6 + audio.bands[bandIdx] * 0.2;

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
// CrystalGrowth
// ---------------------------------------------------------------------------

type BranchPoint = [number, number, number, number, string]; // x, y, z, depth, branchId

export class CrystalGrowth extends VisualizationPattern {
  static patternName = "Crystal Growth";
  static description = "Fractal crystal with recursive branching";

  private _growth = 0.5;
  private _sparklePhase = 0;
  private _branchPoints: BranchPoint[] | null = null;
  private _growthSpurt = 0;

  private _generateCrystalStructure(maxDepth = 3): BranchPoint[] {
    const points: BranchPoint[] = [];

    const addBranch = (
      startX: number,
      startY: number,
      startZ: number,
      direction: [number, number, number],
      length: number,
      currentDepth: number,
      branchId: string
    ): void => {
      if (currentDepth > maxDepth || length < 0.02) return;

      const [dx, dy, dz] = direction;

      // Add points along this branch
      const segments = Math.max(2, Math.floor(length * 10));
      for (let i = 0; i <= segments; i++) {
        const t = i / segments;
        const x = startX + dx * length * t;
        const y = startY + dy * length * t;
        const z = startZ + dz * length * t;
        points.push([x, y, z, currentDepth, branchId]);
      }

      // End point of this branch
      const endX = startX + dx * length;
      const endY = startY + dy * length;
      const endZ = startZ + dz * length;

      // Spawn child branches
      if (currentDepth < maxDepth) {
        const numChildren = Math.max(1, 4 - currentDepth);

        for (let child = 0; child < numChildren; child++) {
          const childAngle = (child / numChildren) * Math.PI * 2;
          const spread = 0.3 + currentDepth * 0.15;

          // New direction (bend outward)
          let newDx = dx + Math.cos(childAngle) * spread;
          let newDy = dy * 0.8;
          let newDz = dz + Math.sin(childAngle) * spread;

          // Normalise
          const mag = Math.sqrt(
            newDx * newDx + newDy * newDy + newDz * newDz
          );
          if (mag > 0) {
            newDx /= mag;
            newDy /= mag;
            newDz /= mag;
          }

          const childLength = length * 0.6;

          addBranch(
            endX,
            endY,
            endZ,
            [newDx, newDy, newDz],
            childLength,
            currentDepth + 1,
            `${branchId}_${child}`
          );
        }
      }
    };

    // Start with main trunk going up
    addBranch(0, 0, 0, [0, 1, 0], 0.4, 0, "trunk");

    // Add some root branches going down/out
    for (let i = 0; i < 3; i++) {
      const angle = (i * Math.PI * 2) / 3;
      addBranch(
        0,
        0,
        0,
        [Math.cos(angle) * 0.3, -0.3, Math.sin(angle) * 0.3],
        0.15,
        2,
        `root_${i}`
      );
    }

    return points;
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const n = this.config.entityCount;

    // Generate crystal structure if needed
    if (this._branchPoints === null) {
      this._branchPoints = this._generateCrystalStructure();
    }

    this._sparklePhase += 0.1;

    // Growth responds to bass
    const targetGrowth =
      0.5 + audio.bands[0] * 0.3 + audio.bands[1] * 0.2;
    this._growth += (targetGrowth - this._growth) * 0.05;

    // Beat triggers growth spurt
    if (audio.isBeat) {
      this._growthSpurt = 0.3;
    }
    this._growthSpurt *= 0.9;

    const effectiveGrowth = this._growth + this._growthSpurt;

    const entities: EntityData[] = [];
    const center = 0.5;

    // Sample points from structure
    const pointsToUse = Math.min(n, this._branchPoints.length);
    const step =
      pointsToUse > 0 ? this._branchPoints.length / pointsToUse : 1;

    for (let i = 0; i < pointsToUse; i++) {
      let idx = Math.floor(i * step);
      if (idx >= this._branchPoints.length) {
        idx = this._branchPoints.length - 1;
      }

      const [px, py, pz, depth] = this._branchPoints[idx];

      // Scale by growth (deeper branches appear later)
      const depthGrowth = Math.max(0, effectiveGrowth - depth * 0.2);
      if (depthGrowth <= 0) continue;

      // Position
      const x = center + px * depthGrowth;
      const y = 0.2 + py * depthGrowth * 0.6;
      const z = center + pz * depthGrowth;

      // Band based on depth
      const bandIdx = Math.min(4, depth * 2);

      // Scale based on depth and audio
      const bScale = this.config.baseScale * (1.0 - depth * 0.15);
      let scale = bScale + audio.bands[bandIdx] * 0.3;

      // Tips sparkle with highs
      if (depth >= 2) {
        const sparkle =
          Math.sin(this._sparklePhase + i * 0.5) * 0.5 + 0.5;
        scale += sparkle * audio.bands[4] * 0.4 + audio.bands[4] * 0.3;
      }

      if (audio.isBeat) {
        scale *= 1.2 + depth * 0.1;
      }

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, Math.max(0.05, scale)),
        band: bandIdx,
        visible: depthGrowth > 0.1,
      });
    }

    // Fill remaining entities with extra tip points
    for (let i = entities.length; i < n; i++) {
      const angle = i * 2.39996; // Golden angle
      const radius = 0.1 + (i % 5) * 0.03;
      const tipY = 0.2 + effectiveGrowth * 0.6 * 0.8;

      const x = center + Math.cos(angle) * radius * effectiveGrowth;
      const y = tipY + Math.sin(i * 0.7) * 0.05;
      const z = center + Math.sin(angle) * radius * effectiveGrowth;

      const sparkle =
        Math.sin(this._sparklePhase + i) * 0.5 + 0.5;
      let scale = this.config.baseScale * 0.5 * sparkle;
      scale += audio.bands[4] * 0.3;

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, Math.max(0.03, scale)),
        band: 5,
        visible: sparkle > 0.3,
      });
    }

    this.update();
    return entities;
  }
}
