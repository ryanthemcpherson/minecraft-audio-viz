/**
 * Epic Visualization Patterns
 * Port of audio_processor/patterns.py lines 844-2069
 * 8 patterns: Mushroom, Skull, SacredGeometry, Vortex, Pyramid, GalaxySpiral, LaserArray, WormholePortal
 */

import {
  type EntityData,
  type AudioState,
  type PatternConfig,
  VisualizationPattern,
  clamp,
} from "./base";

// ---------------------------------------------------------------------------
// 1. Mushroom
// ---------------------------------------------------------------------------

export class Mushroom extends VisualizationPattern {
  static patternName = "Mushroom";
  static description = "Psychedelic toadstool with spots, gills, and spores";

  private _rotation = 0;
  private _pulse = 0;
  private _glow = 0;
  private _breathe = 0;
  private _breatheDir = 1;
  private _wobble = 0;
  private _sporeTime = 0;
  private _grow = 1;
  private _spotAngles: number[];
  private _spotPhis: number[];

  constructor(config?: Partial<PatternConfig>) {
    super(config);
    this._spotAngles = Array.from({ length: 7 }, (_, i) => i * 2.39996);
    this._spotPhis = Array.from({ length: 7 }, (_, i) => 0.3 + (i % 3) * 0.2);
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    // Rotation - faster with amplitude
    this._rotation += (0.2 + audio.amplitude * 0.4) * 0.016;

    // Breathing animation
    this._breathe += 0.02 * this._breatheDir;
    if (this._breathe > 1.0) this._breatheDir = -1;
    else if (this._breathe < 0.0) this._breatheDir = 1;

    // Wobble on beat
    if (audio.isBeat) {
      this._pulse = 1.0;
      this._glow = 1.0;
      this._wobble = 0.15 * audio.amplitude;
      this._grow = 1.15;
    }
    this._pulse *= 0.92;
    this._glow *= 0.93;
    this._wobble *= 0.9;
    this._grow = 1.0 + (this._grow - 1.0) * 0.95;

    this._sporeTime += 0.016;

    const wobbleX = Math.sin(this._sporeTime * 2) * this._wobble;
    const wobbleZ = Math.cos(this._sporeTime * 2.5) * this._wobble * 0.7;

    // Allocate entities: 20% stem, 45% cap, 15% gills, 10% spots, 10% spores
    const stemCount = Math.max(6, Math.floor(n / 5));
    const capCount = Math.max(10, Math.floor(n * 0.45));
    const gillCount = Math.max(6, Math.floor(n * 0.15));
    const spotCount = Math.max(5, Math.floor(n / 10));
    const sporeCount = n - stemCount - capCount - gillCount - spotCount;

    let entityIdx = 0;
    const breatheScale = 1.0 + this._breathe * 0.05;

    // === STEM ===
    const stemRadius = 0.06 * breatheScale + this._pulse * 0.015;
    const stemHeight = 0.34 * this._grow;
    const goldenAngle = 2.39996323;

    for (let i = 0; i < stemCount; i++) {
      const angleBase = i * goldenAngle;
      const baseT = i / stemCount;
      const heightVariation =
        Math.sin(angleBase * 3) * 0.04 + Math.cos(angleBase * 5) * 0.02;
      const yT = clamp(baseT + heightVariation);
      const ringY = 0.06 + yT * stemHeight;

      const taper = 1.0 - yT * 0.3 + Math.sin(yT * Math.PI) * 0.2;
      const radiusVariation = 1.0 + Math.sin(i * 1.7) * 0.1;
      const currentRadius = stemRadius * taper * radiusVariation;

      const twist = yT * Math.PI * 0.6;
      const angle = this._rotation + twist + angleBase;

      const x = center + Math.cos(angle) * currentRadius + wobbleX * yT;
      const z = center + Math.sin(angle) * currentRadius + wobbleZ * yT;
      const y = ringY;

      const bandIdx = 1 + (i % 2);
      const bScale =
        this.config.baseScale * (0.6 + yT * 0.3) +
        audio.bands[bandIdx] * 0.2;

      entities.push({
        id: `block_${entityIdx}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, bScale),
        band: bandIdx,
        visible: true,
      });
      entityIdx++;
    }

    // === CAP ===
    const capBaseY = 0.08 + stemHeight;
    const capRadius =
      (0.22 + this._pulse * 0.06 + audio.bands[0] * 0.04) *
      breatheScale *
      this._grow;
    const layers = Math.max(4, Math.floor(Math.sqrt(capCount)));
    let pointsPlaced = 0;

    for (let layer = 0; layer < layers; layer++) {
      if (pointsPlaced >= capCount) break;

      const layerT = layer / Math.max(1, layers - 1);
      const phi = layerT * (Math.PI * 0.55);
      const layerRadius = capRadius * Math.sin(phi);
      const heightFactor = Math.cos(phi) * 0.5 + (1 - layerT) * 0.15;
      const layerY = capBaseY + capRadius * heightFactor;

      const pointsThisLayer = Math.max(4, Math.floor(6 + layer * 4));

      for (let j = 0; j < pointsThisLayer; j++) {
        if (pointsPlaced >= capCount) break;

        const angle =
          this._rotation * 0.4 + (j / pointsThisLayer) * Math.PI * 2;
        const x = center + Math.cos(angle) * layerRadius + wobbleX;
        const zp = center + Math.sin(angle) * layerRadius + wobbleZ;
        const y = layerY;

        const bandIdx = 0;
        const scale =
          this.config.baseScale * 1.1 +
          audio.bands[bandIdx] * 0.4 +
          this._glow * 0.25;

        entities.push({
          id: `block_${entityIdx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(zp),
          scale: Math.min(this.config.maxScale, scale),
          band: bandIdx,
          visible: true,
        });
        entityIdx++;
        pointsPlaced++;
      }
    }

    // === GILLS ===
    const gillY = capBaseY - 0.02;
    const numGillLines = Math.max(4, Math.floor(gillCount / 3));
    const pointsPerGill = Math.floor(gillCount / numGillLines);

    for (let g = 0; g < numGillLines; g++) {
      const gillAngle =
        this._rotation * 0.4 + (g / numGillLines) * Math.PI * 2;

      for (let p = 0; p < pointsPerGill; p++) {
        if (entityIdx >= stemCount + capCount + gillCount) break;

        const t = (p + 1) / (pointsPerGill + 1);
        const r = stemRadius + t * (capRadius * 0.85 - stemRadius);

        const x = center + Math.cos(gillAngle) * r + wobbleX;
        const zp = center + Math.sin(gillAngle) * r + wobbleZ;
        const y = gillY - t * 0.03;

        const bandIdx = 3;
        const scale =
          this.config.baseScale * 0.5 + audio.bands[bandIdx] * 0.2;

        entities.push({
          id: `block_${entityIdx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(zp),
          scale: Math.min(this.config.maxScale, scale),
          band: bandIdx,
          visible: true,
        });
        entityIdx++;
      }
    }

    // === SPOTS ===
    const spotLimit = Math.min(spotCount, this._spotAngles.length);
    for (let s = 0; s < spotLimit; s++) {
      const spotPhi = this._spotPhis[s] * (Math.PI * 0.4);
      const spotR = capRadius * 0.85 * Math.sin(spotPhi);
      const spotY =
        capBaseY + capRadius * Math.cos(spotPhi) * 0.5 + 0.02;

      const spotAngle = this._rotation * 0.4 + this._spotAngles[s];
      const x = center + Math.cos(spotAngle) * spotR + wobbleX;
      const zp = center + Math.sin(spotAngle) * spotR + wobbleZ;
      const y = spotY;

      const bandIdx = 4; // Python used 5 but only 5 bands (0-4), mapping to high
      const scale =
        this.config.baseScale * 0.9 +
        this._glow * 0.4 +
        audio.bands[4] * 0.3;

      entities.push({
        id: `block_${entityIdx}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(zp),
        scale: Math.min(this.config.maxScale, scale),
        band: bandIdx,
        visible: true,
      });
      entityIdx++;
    }

    // === SPORES ===
    for (let sp = 0; sp < sporeCount; sp++) {
      const phase = sp * 1.618 + this._sporeTime;
      const sporeLife = (phase % 3.0) / 3.0;

      const sporeAngle = phase * 2.0 + sp;
      const sporeR =
        0.05 + sporeLife * 0.15 + Math.sin(phase * 3) * 0.03;
      const sporeY = capBaseY + 0.1 + sporeLife * 0.4;

      const x = center + Math.cos(sporeAngle) * sporeR + wobbleX * 0.5;
      const zp = center + Math.sin(sporeAngle) * sporeR + wobbleZ * 0.5;
      const y = sporeY;

      const bandIdx = 4;
      const fade = Math.sin(sporeLife * Math.PI);
      const scale =
        this.config.baseScale * 0.3 * fade +
        audio.bands[bandIdx] * 0.15 * fade;

      entities.push({
        id: `block_${entityIdx}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(zp),
        scale: Math.min(this.config.maxScale, Math.max(0.01, scale)),
        band: bandIdx,
        visible: fade > 0.1,
      });
      entityIdx++;
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 2. Skull
// ---------------------------------------------------------------------------

type SkullPartType =
  | "jaw"
  | "face"
  | "nose"
  | "cheek"
  | "eye"
  | "eye_inner"
  | "brow"
  | "temple"
  | "cranium"
  | "teeth_upper"
  | "teeth_lower";

interface SkullPoint {
  part: SkullPartType;
  x: number;
  yNorm: number;
  z: number;
}

export class Skull extends VisualizationPattern {
  static patternName = "Skull";
  static description = "Clean anatomical skull with animated jaw and glowing eyes";

  private _rotation = 0;
  private _jawOpen = 0;
  private _eyeGlow = 0;
  private _breathe = 0;
  private _breatheDir = 1;
  private _headBob = 0;
  private _skullTime = 0;
  private _skullPoints: SkullPoint[] | null = null;
  private _beatIntensity = 0;

  private _skullSlice(yNorm: number): [number, number, SkullPartType][] {
    const points: [number, number, SkullPartType][] = [];

    if (yNorm < 0.15) {
      const t = yNorm / 0.15;
      const width = 0.08 + t * 0.04;
      const depth = 0.06 + t * 0.02;
      for (let angle = -140; angle <= 140; angle += 20) {
        const rad = (angle * Math.PI) / 180;
        const x = Math.sin(rad) * width;
        const z = -Math.cos(rad) * depth - 0.04;
        points.push([x, z, "jaw"]);
      }
    } else if (yNorm < 0.25) {
      const t = (yNorm - 0.15) / 0.1;
      const width = 0.12 + t * 0.02;
      const depth = 0.08 + t * 0.01;
      for (let angle = -130; angle <= 130; angle += 15) {
        const rad = (angle * Math.PI) / 180;
        const x = Math.sin(rad) * width;
        const z = -Math.cos(rad) * depth - 0.02;
        points.push([x, z, "jaw"]);
      }
    } else if (yNorm < 0.35) {
      const t = (yNorm - 0.25) / 0.1;
      const width = 0.14 + t * 0.01;
      const depth = 0.1;
      for (let angle = -100; angle <= 100; angle += 12) {
        const rad = (angle * Math.PI) / 180;
        const x = Math.sin(rad) * width;
        const z = -Math.cos(rad) * depth;
        points.push([x, z, "face"]);
      }
      for (const side of [-1, 1]) {
        for (let d = 0; d < 3; d++) {
          const x = side * width;
          const z = -depth + 0.02 + d * 0.04;
          points.push([x, z, "face"]);
        }
      }
    } else if (yNorm < 0.45) {
      const t = (yNorm - 0.35) / 0.1;
      const noseWidth = 0.03 * (1 - t * 0.5);
      for (const nx of [-noseWidth, 0, noseWidth]) {
        points.push([nx, -0.11, "nose"]);
      }
      for (const side of [-1, 1]) {
        points.push([side * 0.14, -0.08, "cheek"]);
        points.push([side * 0.16, -0.04, "cheek"]);
        points.push([side * 0.15, 0.0, "face"]);
      }
    } else if (yNorm < 0.55) {
      for (const side of [-1, 1]) {
        const eyeCx = side * 0.065;
        const eyeCz = -0.08;
        for (let angle = 0; angle < 360; angle += 30) {
          const rad = (angle * Math.PI) / 180;
          const ex = eyeCx + Math.cos(rad) * 0.04;
          const ez = eyeCz + Math.sin(rad) * 0.025;
          points.push([ex, ez, "eye"]);
        }
      }
      points.push([0, -0.1, "nose"]);
      for (const side of [-1, 1]) {
        points.push([side * 0.15, -0.05, "face"]);
        points.push([side * 0.16, 0.0, "face"]);
      }
    } else if (yNorm < 0.65) {
      for (let bx = -12; bx <= 12; bx += 3) {
        const x = bx * 0.01;
        const z = -0.1 - Math.abs(x) * 0.3;
        points.push([x, z, "brow"]);
      }
      for (const side of [-1, 1]) {
        points.push([side * 0.15, -0.03, "temple"]);
        points.push([side * 0.14, 0.02, "cranium"]);
      }
    } else if (yNorm < 0.8) {
      const t = (yNorm - 0.65) / 0.15;
      const width = 0.14 - t * 0.02;
      for (let angle = -160; angle <= 160; angle += 15) {
        const rad = (angle * Math.PI) / 180;
        const x = Math.sin(rad) * width;
        const z = -Math.cos(rad) * 0.12 * (1 - t * 0.3);
        points.push([x, z, "cranium"]);
      }
    } else {
      const t = (yNorm - 0.8) / 0.2;
      const radius = 0.12 * (1 - t * 0.7);
      if (radius > 0.01) {
        for (let angle = 0; angle < 360; angle += 25) {
          const rad = (angle * Math.PI) / 180;
          const x = Math.cos(rad) * radius;
          const z = Math.sin(rad) * radius * 0.9;
          points.push([x, z, "cranium"]);
        }
      } else {
        points.push([0, 0, "cranium"]);
      }
    }

    return points;
  }

  private _generateSkullPoints(n: number): SkullPoint[] {
    const allPoints: SkullPoint[] = [];

    const numSlices = Math.max(20, Math.floor(n / 8));

    for (let i = 0; i < numSlices; i++) {
      const yNorm = i / (numSlices - 1);
      const slicePoints = this._skullSlice(yNorm);

      for (const [x, z, partType] of slicePoints) {
        allPoints.push({ part: partType, x, yNorm, z });
      }
    }

    // Extra eye detail
    for (const side of [-1, 1]) {
      const eyeCx = side * 0.065;
      const eyeCy = 0.5;
      for (let i = 0; i < 8; i++) {
        const angle = (i * Math.PI * 2) / 8;
        const x = eyeCx + Math.cos(angle) * 0.025;
        const z = -0.08 + Math.sin(angle) * 0.015;
        allPoints.push({ part: "eye_inner", x, yNorm: eyeCy, z });
      }
    }

    // Extra teeth detail
    for (let i = 0; i < 10; i++) {
      const t = i / 9;
      const x = -0.07 + t * 0.14;
      allPoints.push({ part: "teeth_upper", x, yNorm: 0.3, z: -0.095 });
      allPoints.push({ part: "teeth_lower", x, yNorm: 0.22, z: -0.09 });
    }

    // Scale to fit n points
    if (allPoints.length > n) {
      const step = allPoints.length / n;
      const selected: SkullPoint[] = [];
      for (let i = 0; i < n; i++) {
        const idx = Math.floor(i * step);
        if (idx < allPoints.length) {
          selected.push(allPoints[idx]);
        }
      }
      return selected;
    } else {
      const result = [...allPoints];
      let idx = 0;
      while (result.length < n) {
        const orig = allPoints[idx % allPoints.length];
        result.push({
          part: orig.part,
          x: orig.x + ((idx * 0.001) % 0.01),
          yNorm: orig.yNorm,
          z: orig.z + ((idx * 0.001) % 0.01),
        });
        idx++;
      }
      return result.slice(0, n);
    }
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    if (
      this._skullPoints === null ||
      this._skullPoints.length !== n
    ) {
      this._skullPoints = this._generateSkullPoints(n);
    }

    this._skullTime += 0.016;
    this._rotation = this._skullTime * 0.12;

    // Breathing
    this._breathe += 0.012 * this._breatheDir;
    if (this._breathe > 1.0) this._breatheDir = -1;
    else if (this._breathe < 0.0) this._breatheDir = 1;

    // Beat response
    if (audio.isBeat) {
      this._headBob = 0.025 * audio.amplitude;
      this._beatIntensity = 1.0;
      this._eyeGlow = 1.0;
    }
    this._headBob *= 0.9;
    this._beatIntensity *= 0.92;
    this._eyeGlow *= 0.85;

    // Jaw opens with bass
    let targetJaw = audio.bands[0] * 0.08 + audio.bands[1] * 0.04;
    if (audio.isBeat) targetJaw += 0.06;
    this._jawOpen += (targetJaw - this._jawOpen) * 0.25;

    const breatheScale = 1.0 + this._breathe * 0.02;
    const skullScale = 0.55;

    const cosR = Math.cos(this._rotation);
    const sinR = Math.sin(this._rotation);

    for (let i = 0; i < this._skullPoints.length; i++) {
      const point = this._skullPoints[i];
      const partType = point.part;
      const pyNorm = point.yNorm;

      const px = point.x * skullScale * breatheScale;
      const pz = point.z * skullScale * breatheScale;
      let py = pyNorm * 0.45 * skullScale;

      // Jaw movement
      if (partType === "jaw" && pyNorm < 0.25) {
        py -= this._jawOpen * ((0.25 - pyNorm) / 0.25);
      } else if (partType === "teeth_lower") {
        py -= this._jawOpen * 0.8;
      }

      // Rotate around Y axis
      const rx = px * cosR - pz * sinR;
      const rz = px * sinR + pz * cosR;

      const x = center + rx;
      const y = 0.25 + py + this._headBob;
      const z = center + rz;

      let bandIdx = i % 5;
      let baseScaleVal = this.config.baseScale * 0.9;

      if (partType === "eye" || partType === "eye_inner") {
        baseScaleVal *= 1.1;
        baseScaleVal += this._eyeGlow * 0.5 + audio.bands[4] * 0.3;
        bandIdx = 4;
      } else if (partType === "jaw") {
        baseScaleVal += audio.bands[0] * 0.3;
        bandIdx = 0;
      } else if (
        partType === "teeth_upper" ||
        partType === "teeth_lower"
      ) {
        baseScaleVal *= 0.7;
        baseScaleVal += this._beatIntensity * 0.25;
        bandIdx = 4; // Python used 5 but only 5 bands (0-4)
      } else if (partType === "brow") {
        baseScaleVal *= 1.05;
        baseScaleVal += audio.bands[2] * 0.2;
      } else if (partType === "cranium") {
        baseScaleVal += audio.bands[bandIdx % 3] * 0.2;
      } else if (partType === "nose") {
        baseScaleVal *= 0.85;
      }

      // Global beat pulse
      if (audio.isBeat) {
        baseScaleVal *= 1.1;
      }

      entities.push({
        id: `block_${i}`,
        x: clamp(x),
        y: clamp(y),
        z: clamp(z),
        scale: Math.min(this.config.maxScale, baseScaleVal),
        band: bandIdx,
        visible: true,
      });
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 3. SacredGeometry
// ---------------------------------------------------------------------------

export class SacredGeometry extends VisualizationPattern {
  static patternName = "Sacred Geometry";
  static description = "Morphing platonic solids - icosahedron";

  private static readonly PHI = (1 + Math.sqrt(5)) / 2;

  private _rotationX = 0;
  private _rotationY = 0;
  private _rotationZ = 0;
  private _morph = 0;
  private _pulse = 0;

  private _icosahedronVertices(): [number, number, number][] {
    const phi = SacredGeometry.PHI;
    const verts: [number, number, number][] = [];

    for (const x of [-1, 1]) {
      for (const y of [-phi, phi]) {
        verts.push([x, y, 0]);
      }
    }
    for (const y of [-1, 1]) {
      for (const z of [-phi, phi]) {
        verts.push([0, y, z]);
      }
    }
    for (const x of [-phi, phi]) {
      for (const z of [-1, 1]) {
        verts.push([x, 0, z]);
      }
    }

    return verts;
  }

  private _icosahedronEdgeMidpoints(
    vertices: [number, number, number][]
  ): [number, number, number][] {
    const edges: [number, number, number][] = [];
    const threshold = 2.1;

    for (let i = 0; i < vertices.length; i++) {
      for (let j = i + 1; j < vertices.length; j++) {
        const v1 = vertices[i];
        const v2 = vertices[j];
        const dist = Math.sqrt(
          (v1[0] - v2[0]) ** 2 +
            (v1[1] - v2[1]) ** 2 +
            (v1[2] - v2[2]) ** 2
        );
        if (dist < threshold) {
          edges.push([
            (v1[0] + v2[0]) / 2,
            (v1[1] + v2[1]) / 2,
            (v1[2] + v2[2]) / 2,
          ]);
        }
      }
    }
    return edges;
  }

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    const vertices = this._icosahedronVertices();
    const edges = this._icosahedronEdgeMidpoints(vertices);
    const allPoints = [...vertices, ...edges];

    // Rotation
    const speedMult = 1.0 + audio.amplitude;
    this._rotationY += 0.4 * speedMult * 0.016;
    this._rotationX += 0.25 * speedMult * 0.016;
    this._rotationZ += 0.15 * speedMult * 0.016;

    if (audio.isBeat) this._pulse = 1.0;
    this._pulse *= 0.9;

    const bScale = 0.12 + audio.bands[0] * 0.05 + this._pulse * 0.04;

    const pointsToUse =
      n <= allPoints.length ? allPoints.slice(0, n) : allPoints;

    const cosY = Math.cos(this._rotationY);
    const sinY = Math.sin(this._rotationY);
    const cosX = Math.cos(this._rotationX);
    const sinX = Math.sin(this._rotationX);
    const cosZ = Math.cos(this._rotationZ);
    const sinZ = Math.sin(this._rotationZ);

    for (let i = 0; i < Math.min(n, pointsToUse.length); i++) {
      let [px, py, pz] = pointsToUse[i];

      // Normalize
      const mag = Math.sqrt(px * px + py * py + pz * pz);
      if (mag > 0) {
        px /= mag;
        py /= mag;
        pz /= mag;
      }

      // Y rotation
      const rx = px * cosY - pz * sinY;
      const rz = px * sinY + pz * cosY;
      // X rotation
      const ry = py * cosX - rz * sinX;
      const rz2 = py * sinX + rz * cosX;
      // Z rotation
      const rx2 = rx * cosZ - ry * sinZ;
      const ry2 = rx * sinZ + ry * cosZ;

      const radius = bScale * (1.0 + this._pulse * 0.3);
      const x = center + rx2 * radius;
      const y = center + ry2 * radius;
      const z = center + rz2 * radius;

      const bandIdx = i % 5;
      let scale = this.config.baseScale + audio.bands[bandIdx] * 0.4;
      if (audio.isBeat) scale *= 1.3;

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

    // Fill remaining with inner layer
    for (let i = pointsToUse.length; i < n; i++) {
      const scaleFactor = 0.5;
      const pointIdx = i % pointsToUse.length;
      let [px, py, pz] = pointsToUse[pointIdx];

      const mag = Math.sqrt(px * px + py * py + pz * pz);
      if (mag > 0) {
        px = (px / mag) * scaleFactor;
        py = (py / mag) * scaleFactor;
        pz = (pz / mag) * scaleFactor;
      }

      const rx = px * cosY - pz * sinY;
      const rz = px * sinY + pz * cosY;
      const ry = py * cosX - rz * sinX;
      const rz2 = py * sinX + rz * cosX;
      const rx2 = rx * cosZ - ry * sinZ;
      const ry2 = rx * sinZ + ry * cosZ;

      const radius = bScale * (1.0 + this._pulse * 0.3);
      const x = center + rx2 * radius;
      const y = center + ry2 * radius;
      const z = center + rz2 * radius;

      const bandIdx = i % 5;
      const scale =
        this.config.baseScale * 0.7 + audio.bands[bandIdx] * 0.3;

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
// 4. Vortex
// ---------------------------------------------------------------------------

export class Vortex extends VisualizationPattern {
  static patternName = "Vortex";
  static description = "Swirling tunnel - spiral into infinity";

  private _rotation = 0;
  private _zOffset = 0;
  private _intensity = 0;

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    let speed = 2.0 + audio.amplitude * 3.0;
    if (audio.isBeat) {
      speed *= 1.5;
      this._intensity = 1.0;
    }
    this._rotation += speed * 0.016;
    this._intensity *= 0.95;

    this._zOffset += (0.5 + audio.bands[0] * 0.5) * 0.016;
    if (this._zOffset > 1.0) this._zOffset -= 1.0;

    const rings = Math.max(4, Math.floor(n / 8));
    const perRing = Math.floor(n / rings);

    for (let ring = 0; ring < rings; ring++) {
      const ringZ = ((ring / rings + this._zOffset) % 1.0);
      const depth = ringZ;

      const baseRadius = 0.35 - depth * 0.25;
      const pulseRadius =
        baseRadius + this._intensity * 0.1 * (1 - depth);

      const ringRotation = this._rotation * (1.0 + ring * 0.2);

      for (let j = 0; j < perRing; j++) {
        const idx = ring * perRing + j;
        if (idx >= n) break;

        const angle = ringRotation + (j / perRing) * Math.PI * 2;

        const wobble =
          Math.sin(angle * 3 + this._rotation * 2) * 0.02;
        let radius = pulseRadius + wobble;

        const bandIdx = j % 5;
        radius += audio.bands[bandIdx] * 0.05 * (1 - depth);

        const x = center + Math.cos(angle) * radius;
        const zPos = center + Math.sin(angle) * radius;
        const y = 0.1 + depth * 0.8;

        let scale =
          this.config.baseScale * (1.5 - depth) +
          audio.bands[bandIdx] * 0.3;
        if (audio.isBeat) scale *= 1.2;

        entities.push({
          id: `block_${idx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(zPos),
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
// 5. Pyramid
// ---------------------------------------------------------------------------

export class Pyramid extends VisualizationPattern {
  static patternName = "Pyramid";
  static description = "Egyptian pyramid - inverts on drops";

  private _rotation = 0;
  private _invert = 0;
  private _hover = 0;

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    this._rotation += (0.3 + audio.amplitude * 0.3) * 0.016;

    if (audio.isBeat && audio.beatIntensity > 0.6) {
      this._invert = 1.0 - this._invert;
    }

    const targetHover = audio.bands[0] * 0.1 + audio.bands[1] * 0.05;
    this._hover += (targetHover - this._hover) * 0.1;

    const layerCount = Math.max(3, Math.floor(Math.sqrt(n)));

    let entityIdx = 0;
    for (let layer = 0; layer < layerCount; layer++) {
      let layerNorm =
        layerCount > 1 ? layer / (layerCount - 1) : 0;
      if (this._invert > 0.5) layerNorm = 1.0 - layerNorm;

      const layerSize = 1.0 - layerNorm * 0.9;
      const layerY = 0.1 + layerNorm * 0.7 + this._hover;

      const sidePoints = Math.max(
        1,
        Math.floor(Math.sqrt(n / layerCount) * layerSize)
      );

      for (let i = 0; i < sidePoints; i++) {
        for (let j = 0; j < sidePoints; j++) {
          if (entityIdx >= n) break;

          const localX =
            (i / Math.max(1, sidePoints - 1) - 0.5) * layerSize * 0.4;
          const localZ =
            (j / Math.max(1, sidePoints - 1) - 0.5) * layerSize * 0.4;

          const cosR = Math.cos(this._rotation);
          const sinR = Math.sin(this._rotation);
          const rx = localX * cosR - localZ * sinR;
          const rz = localX * sinR + localZ * cosR;

          const x = center + rx;
          const z = center + rz;
          const y = layerY;

          const bandIdx = entityIdx % 5;
          let scale =
            this.config.baseScale + audio.bands[bandIdx] * 0.4;

          if (layerNorm > 0.8) {
            scale += audio.amplitude * 0.3;
          }
          if (audio.isBeat) scale *= 1.25;

          entities.push({
            id: `block_${entityIdx}`,
            x: clamp(x),
            y: clamp(y),
            z: clamp(z),
            scale: Math.min(this.config.maxScale, scale),
            band: bandIdx,
            visible: true,
          });
          entityIdx++;
        }
        if (entityIdx >= n) break;
      }
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// 6. GalaxySpiral
// ---------------------------------------------------------------------------

export class GalaxySpiral extends VisualizationPattern {
  static patternName = "Galaxy";
  static description = "Spiral galaxy - cosmic visualization";

  private _rotation = 0;
  private _armTwist = 0;
  private _corePulse = 0;

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    this._rotation += (0.2 + audio.amplitude * 0.3) * 0.016;
    this._armTwist = 2.0 + audio.bands[4] * 2.0 + audio.bands[4] * 1.0;

    if (audio.isBeat) this._corePulse = 1.0;
    this._corePulse *= 0.9;

    const coreCount = Math.floor(n / 5);
    const armCount = n - coreCount;
    const numArms = 2;

    // === CORE ===
    for (let i = 0; i < coreCount; i++) {
      const seedAngle = ((i * 137.5) * Math.PI) / 180;
      const seedRadius =
        Math.pow(i / coreCount, 0.5) * 0.08;

      const angle = seedAngle + this._rotation * 2;
      const radius = seedRadius * (1.0 + this._corePulse * 0.3);

      const x = center + Math.cos(angle) * radius;
      const z = center + Math.sin(angle) * radius;
      const y = center + Math.sin(seedAngle * 3) * 0.02;

      const bandIdx = i % 5;
      const scale =
        this.config.baseScale * 1.2 +
        audio.bands[bandIdx] * 0.3 +
        this._corePulse * 0.2;

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

    // === SPIRAL ARMS ===
    const perArm = Math.floor(armCount / numArms);

    for (let arm = 0; arm < numArms; arm++) {
      const armOffset = arm * Math.PI;

      for (let j = 0; j < perArm; j++) {
        const idx = coreCount + arm * perArm + j;
        if (idx >= n) break;

        const t = j / perArm;
        let radius = 0.08 + t * 0.3;

        const spiralAngle =
          armOffset + this._rotation + t * Math.PI * this._armTwist;

        const scatter = Math.sin(j * 0.5) * 0.02;
        radius += scatter;

        const x = center + Math.cos(spiralAngle) * radius;
        const z = center + Math.sin(spiralAngle) * radius;
        const y = center + Math.sin(spiralAngle * 2) * 0.03 * t;

        const bandIdx = j % 5;
        const bassReact = (1 - t) * audio.bands[0] * 0.3;
        const highReact = t * audio.bands[4] * 0.3;
        let scale = this.config.baseScale + bassReact + highReact;
        if (audio.isBeat) scale *= 1.15;

        entities.push({
          id: `block_${idx}`,
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
// 7. LaserArray
// ---------------------------------------------------------------------------

export class LaserArray extends VisualizationPattern {
  static patternName = "Laser Array";
  static description = "Laser beams shooting from center";

  private _rotation = 0;
  private _beamLengths: number[] = new Array(8).fill(0);
  private _flash = 0;

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    this._rotation += (0.5 + audio.amplitude * 2.0) * 0.016;

    if (audio.isBeat) this._flash = 1.0;
    this._flash *= 0.85;

    const numBeams = Math.min(8, Math.max(4, Math.floor(n / 8)));
    const pointsPerBeam = Math.floor(n / numBeams);

    for (let beam = 0; beam < numBeams; beam++) {
      const beamAngle =
        this._rotation + (beam / numBeams) * Math.PI * 2;

      const bandIdx = beam % 5;
      let targetLength = 0.1 + audio.bands[bandIdx] * 0.35;
      if (audio.isBeat) targetLength += 0.1;

      this._beamLengths[beam] +=
        (targetLength - this._beamLengths[beam]) * 0.3;
      const beamLength = this._beamLengths[beam];

      const tilt = ((beam % 3) - 1) * 0.3;

      for (let j = 0; j < pointsPerBeam; j++) {
        const idx = beam * pointsPerBeam + j;
        if (idx >= n) break;

        const t = (j + 1) / pointsPerBeam;
        const distance = t * beamLength;

        const x =
          center +
          Math.cos(beamAngle) * distance * Math.cos(tilt);
        const z =
          center +
          Math.sin(beamAngle) * distance * Math.cos(tilt);
        const y = center + distance * Math.sin(tilt);

        const thickness = 1.0 - t * 0.5;
        const scale =
          this.config.baseScale * thickness + this._flash * 0.2;

        entities.push({
          id: `block_${idx}`,
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
// 8. WormholePortal
// ---------------------------------------------------------------------------

export class WormholePortal extends VisualizationPattern {
  static patternName = "Wormhole Portal";
  static description = "Infinite tunnel - rings fly toward you";

  private _rotation = 0;
  private _tunnelOffset = 0;
  private _flash = 0;
  private _pulse = 0;

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    // Rotation speed driven by energy
    const energy =
      audio.bands.reduce((a, b) => a + b, 0) / audio.bands.length;
    this._rotation += (1.0 + energy * 2.0) * 0.016;

    // Tunnel movement speed
    const speed =
      0.3 + audio.bands[0] * 0.4 + audio.amplitude * 0.3;
    this._tunnelOffset += speed * 0.016;
    if (this._tunnelOffset > 1.0) this._tunnelOffset -= 1.0;

    // Beat flash
    if (audio.isBeat) {
      this._flash = 1.0;
      this._pulse = 0.3;
    }
    this._flash *= 0.85;
    this._pulse *= 0.9;

    const numRings = Math.max(6, Math.floor(n / 10));
    const pointsPerRing = Math.floor(n / numRings);

    for (let ring = 0; ring < numRings; ring++) {
      const ringDepth =
        ((ring / numRings + this._tunnelOffset) % 1.0);

      // Perspective: close rings larger, far rings smaller
      const perspective = 1.0 - ringDepth * 0.8;
      let ringRadius = 0.05 + perspective * 0.35;

      // Breathing with amplitude
      ringRadius += audio.amplitude * 0.05 * perspective;

      const ringRotation =
        this._rotation * (1.0 + ringDepth * 0.5);

      const baseY = 0.1 + ringDepth * 0.8;

      for (let j = 0; j < pointsPerRing; j++) {
        const idx = ring * pointsPerRing + j;
        if (idx >= n) break;

        let angle =
          ringRotation + (j / pointsPerRing) * Math.PI * 2;

        // Spiral twist increases with depth
        const twist = ringDepth * Math.PI * 0.5;
        angle += twist;

        let x = center + Math.cos(angle) * ringRadius;
        let z = center + Math.sin(angle) * ringRadius;
        const y = baseY;

        // Band-reactive radius wobble
        const bandIdx = j % 5;
        const wobble = audio.bands[bandIdx] * 0.03 * perspective;
        x += Math.cos(angle) * wobble;
        z += Math.sin(angle) * wobble;

        // Scale - larger when close
        let scale = this.config.baseScale * perspective;
        scale += audio.bands[bandIdx] * 0.3 * perspective;

        // Flash effect on close rings
        if (ringDepth < 0.3) {
          scale += this._flash * 0.4 * (0.3 - ringDepth);
        }
        if (audio.isBeat) scale *= 1.2;

        entities.push({
          id: `block_${idx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(z),
          scale: Math.min(this.config.maxScale, Math.max(0.05, scale)),
          band: bandIdx,
          visible: true,
        });
      }
    }

    this.update();
    return entities;
  }
}
