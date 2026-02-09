/**
 * Organic / Nature + Spectrum Patterns
 * Ported from audio_processor/patterns.py
 *
 * Aurora, OceanWaves, Fireflies, SpectrumBars, SpectrumTubes, SpectrumCircle
 */

import {
  VisualizationPattern,
  type AudioState,
  type EntityData,
  clamp,
  SeededRandom,
} from "./base";

// ---------------------------------------------------------------------------
// Aurora
// ---------------------------------------------------------------------------

export class Aurora extends VisualizationPattern {
  static patternName = "Aurora";
  static description = "Northern lights curtains - flowing waves";

  private _waveTime = 0;
  private _rippleOrigins: number[][] = []; // [x, time]
  private _colorOffset = 0;
  private _rng = new SeededRandom(42);

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    this._waveTime += 0.016;

    // Colour sweep
    this._colorOffset += audio.amplitude * 0.02 + 0.005;

    // Beat triggers new ripple
    if (audio.isBeat) {
      const rippleX = this._rng.next();
      this._rippleOrigins.push([rippleX, 0.0]);
    }

    // Update ripples
    const newRipples: number[][] = [];
    for (const ripple of this._rippleOrigins) {
      ripple[1] += 0.03 + audio.amplitude * 0.02;
      if (ripple[1] < 2.0) {
        newRipples.push(ripple);
      }
    }
    this._rippleOrigins = newRipples;

    // 3 curtain layers with different depths
    const numLayers = 3;
    const pointsPerLayer = Math.floor(n / numLayers);

    for (let layer = 0; layer < numLayers; layer++) {
      const layerDepth = 0.3 + layer * 0.2;
      const layerSpeed = 1.0 + layer * 0.3;

      for (let j = 0; j < pointsPerLayer; j++) {
        const idx = layer * pointsPerLayer + j;
        if (idx >= n) break;

        // Horizontal position across curtain
        const xNorm = j / Math.max(1, pointsPerLayer - 1);
        const x = 0.1 + xNorm * 0.8;

        // Flowing wave motion (multiple sine waves)
        const wave1 =
          Math.sin(xNorm * Math.PI * 3 + this._waveTime * layerSpeed) *
          0.1;
        const wave2 =
          Math.sin(
            xNorm * Math.PI * 5 - this._waveTime * 0.7 * layerSpeed
          ) * 0.05;
        const wave3 =
          Math.sin(xNorm * Math.PI * 2 + this._waveTime * 1.3) * 0.08;

        // Combine waves
        const waveOffset = wave1 + wave2 + wave3;

        // Add ripple effects from beats
        let rippleOffset = 0;
        for (const [rippleX, rippleTime] of this._rippleOrigins) {
          const dist = Math.abs(xNorm - rippleX);
          if (dist < rippleTime && rippleTime - dist < 0.5) {
            const rippleStrength = (0.5 - (rippleTime - dist)) * 2;
            rippleOffset +=
              Math.sin((rippleTime - dist) * Math.PI * 4) *
              0.1 *
              rippleStrength;
          }
        }

        // Y position (curtains hang from top)
        const baseY = 0.7 - layer * 0.1;
        let y = baseY + waveOffset + rippleOffset;

        // Height variation based on audio
        y += audio.bands[layer * 2] * 0.15;

        const z = center + (layerDepth - 0.5);

        // Colour band based on horizontal position + sweep
        const colorPos = (xNorm + this._colorOffset) % 1.0;
        let bandIdx = Math.floor(colorPos * 4.9);
        bandIdx = Math.max(0, Math.min(4, bandIdx));

        // Scale pulses with corresponding band
        let scale = this.config.baseScale + audio.bands[bandIdx] * 0.35;
        scale += rippleOffset * 2;

        if (audio.isBeat) {
          scale *= 1.25;
        }

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
// OceanWaves
// ---------------------------------------------------------------------------

export class OceanWaves extends VisualizationPattern {
  static patternName = "Ocean Waves";
  static description = "Water surface with splashes and ripples";

  private _waveTime = 0;
  private _splashes: number[][] = []; // [x, z, time, intensity]
  private _rng = new SeededRandom(42);

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    this._waveTime += 0.016;

    // Beat triggers splash
    if (audio.isBeat) {
      const splashX = 0.2 + this._rng.next() * 0.6;
      const splashZ = 0.2 + this._rng.next() * 0.6;
      this._splashes.push([splashX, splashZ, 0.0, audio.beatIntensity]);
    }

    // Update splashes (fade over 3 seconds)
    const newSplashes: number[][] = [];
    for (const splash of this._splashes) {
      splash[2] += 0.016;
      if (splash[2] < 3.0) {
        newSplashes.push(splash);
      }
    }
    this._splashes = newSplashes;

    // Create grid
    let gridSize = Math.floor(Math.sqrt(n));
    if (gridSize < 2) gridSize = 2;

    for (let i = 0; i < gridSize; i++) {
      for (let j = 0; j < gridSize; j++) {
        const idx = i * gridSize + j;
        if (idx >= n) break;

        // Grid position
        const xNorm = i / (gridSize - 1);
        const zNorm = j / (gridSize - 1);

        const x = 0.1 + xNorm * 0.8;
        const z = 0.1 + zNorm * 0.8;

        // === Wave interference ===
        // Large waves (bass)
        let waveBass =
          Math.sin(xNorm * Math.PI * 2 + this._waveTime * 0.5) * 0.08;
        waveBass +=
          Math.sin(zNorm * Math.PI * 1.5 + this._waveTime * 0.3) * 0.06;
        waveBass *= 0.5 + audio.bands[0] * 1.5;

        // Medium waves (mids)
        let waveMid =
          Math.sin(xNorm * Math.PI * 4 + this._waveTime * 1.2) * 0.04;
        waveMid +=
          Math.sin(zNorm * Math.PI * 3 - this._waveTime * 0.8) * 0.03;
        waveMid *= 0.3 + audio.bands[2] + audio.bands[3];

        // Small shimmer (highs)
        let waveHigh =
          Math.sin(xNorm * Math.PI * 8 + this._waveTime * 3) * 0.015;
        waveHigh +=
          Math.sin(zNorm * Math.PI * 7 - this._waveTime * 2.5) * 0.01;
        waveHigh *= 0.2 + audio.bands[4] * 2 + audio.bands[4] * 2;

        // Combine waves
        let y = center + waveBass + waveMid + waveHigh;

        // === Ripple rings from splashes ===
        let rippleHeight = 0;
        for (const [sx, sz, t, intensity] of this._splashes) {
          const dist = Math.sqrt((x - sx) ** 2 + (z - sz) ** 2);
          const rippleRadius = t * 0.3;
          const rippleWidth = 0.08;

          if (Math.abs(dist - rippleRadius) < rippleWidth) {
            const fade = 1.0 - t / 3.0;
            const ringStrength =
              1.0 - Math.abs(dist - rippleRadius) / rippleWidth;
            rippleHeight +=
              Math.sin(dist * 20 - t * 10) *
              0.1 *
              intensity *
              fade *
              ringStrength;
          }
        }

        y += rippleHeight;

        // Band based on wave activity
        const waveActivity =
          Math.abs(waveBass) + Math.abs(waveMid) + Math.abs(waveHigh);
        const bandIdx = Math.floor(waveActivity * 10) % 5;

        let scale = this.config.baseScale + waveActivity * 2;
        scale += rippleHeight * 3;

        if (audio.isBeat) {
          scale *= 1.2;
        }

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
// Fireflies
// ---------------------------------------------------------------------------

export class Fireflies extends VisualizationPattern {
  static patternName = "Fireflies";
  static description = "Swarm of synchronized flashing lights";

  // [x, y, z, vx, vy, vz, glowPhase, group]
  private _fireflies: number[][] = [];
  private _groupFlash = [0, 0, 0, 0];
  private _cascadeTimer = 0;
  private _cascadeActive = false;
  private _driftTime = 0;
  private _rng = new SeededRandom(42);

  calculateEntities(audio: AudioState): EntityData[] {
    const n = this.config.entityCount;
    const center = 0.5;

    // Initialise fireflies
    if (this._fireflies.length !== n) {
      this._fireflies = [];
      for (let i = 0; i < n; i++) {
        const x = center + (this._rng.next() - 0.5) * 0.6;
        const y = 0.2 + this._rng.next() * 0.6;
        const z = center + (this._rng.next() - 0.5) * 0.6;
        const vx = (this._rng.next() - 0.5) * 0.01;
        const vy = (this._rng.next() - 0.5) * 0.01;
        const vz = (this._rng.next() - 0.5) * 0.01;
        const glowPhase = this._rng.next() * Math.PI * 2;
        const group = i % 4;
        this._fireflies.push([x, y, z, vx, vy, vz, glowPhase, group]);
      }
    }

    this._driftTime += 0.016;

    // Beat triggers cascade
    if (audio.isBeat) {
      this._groupFlash[0] = 1.0;
      this._cascadeActive = true;
      this._cascadeTimer = 0;
    }

    // Cascade to other groups with delays (100ms = 0.1s)
    if (this._cascadeActive) {
      this._cascadeTimer += 0.016;
      if (this._cascadeTimer > 0.1 && this._groupFlash[1] < 0.5) {
        this._groupFlash[1] = 1.0;
      }
      if (this._cascadeTimer > 0.2 && this._groupFlash[2] < 0.5) {
        this._groupFlash[2] = 1.0;
      }
      if (this._cascadeTimer > 0.3 && this._groupFlash[3] < 0.5) {
        this._groupFlash[3] = 1.0;
      }
      if (this._cascadeTimer > 0.5) {
        this._cascadeActive = false;
      }
    }

    // Decay group flashes
    for (let g = 0; g < 4; g++) {
      this._groupFlash[g] *= 0.92;
    }

    const entities: EntityData[] = [];

    for (let i = 0; i < n; i++) {
      const ff = this._fireflies[i];
      let [x, y, z, vx, vy, vz, glowPhase] = ff;
      const group = ff[7];

      // Organic drifting motion (Perlin-like smooth random walk)
      const t = this._driftTime + i * 0.1;
      const ax = Math.sin(t * 0.5 + i) * 0.0002;
      const ay = Math.sin(t * 0.3 + i * 1.3) * 0.0001;
      const az = Math.cos(t * 0.4 + i * 0.7) * 0.0002;

      // Update velocity with acceleration
      vx = vx * 0.98 + ax;
      vy = vy * 0.98 + ay;
      vz = vz * 0.98 + az;

      // Clamp velocity
      const maxV = 0.015;
      vx = clamp(vx, -maxV, maxV);
      vy = clamp(vy, -maxV, maxV);
      vz = clamp(vz, -maxV, maxV);

      // Update position
      x += vx;
      y += vy;
      z += vz;

      // Soft boundaries - turn around near edges
      if (x < 0.15 || x > 0.85) vx *= -0.5;
      if (y < 0.15 || y > 0.85) vy *= -0.5;
      if (z < 0.15 || z > 0.85) vz *= -0.5;

      x = clamp(x, 0.1, 0.9);
      y = clamp(y, 0.1, 0.9);
      z = clamp(z, 0.1, 0.9);

      // Update glow phase
      glowPhase += 0.05 + this._rng.next() * 0.02;

      // Store updated values
      this._fireflies[i] = [x, y, z, vx, vy, vz, glowPhase, group];

      // Individual glow cycle
      const individualGlow = (Math.sin(glowPhase) + 1) * 0.3; // 0 to 0.6

      // Group flash overlay
      const groupGlow = this._groupFlash[Math.floor(group)];

      // Combined glow
      let totalGlow = individualGlow + groupGlow;

      // Audio reactivity
      totalGlow += audio.amplitude * 0.2;

      // Band based on group
      const bandIdx = Math.floor(group) + 1; // Groups 0-3 map to bands 1-4

      let scale = this.config.baseScale * 0.5 + totalGlow * 0.6;
      scale += audio.bands[bandIdx] * 0.2;

      entities.push({
        id: `block_${i}`,
        x,
        y,
        z,
        scale: Math.min(this.config.maxScale, Math.max(0.05, scale)),
        band: bandIdx,
        visible: totalGlow > 0.15,
      });
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// SpectrumBars
// ---------------------------------------------------------------------------

export class SpectrumBars extends VisualizationPattern {
  static patternName = "Spectrum Bars";
  static description = "Classic vertical frequency bars";

  private _smoothHeights = [0, 0, 0, 0, 0];
  private _peakHeights = [0, 0, 0, 0, 0];
  private _peakFall = [0, 0, 0, 0, 0];

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    // Smooth the band values
    for (let i = 0; i < 5; i++) {
      const target = audio.bands[i];
      this._smoothHeights[i] +=
        (target - this._smoothHeights[i]) * 0.3;

      // Peak hold with fall
      if (this._smoothHeights[i] > this._peakHeights[i]) {
        this._peakHeights[i] = this._smoothHeights[i];
        this._peakFall[i] = 0;
      } else {
        this._peakFall[i] += 0.02;
        this._peakHeights[i] -= this._peakFall[i] * 0.016;
      }

      this._peakHeights[i] = Math.max(0, this._peakHeights[i]);
    }

    // Distribute entities across 5 bars
    const blocksPerBar = Math.floor(n / 5);
    const barSpacing = 0.16;
    const startX = center - 2.0 * barSpacing;

    let entityIdx = 0;

    for (let bar = 0; bar < 5; bar++) {
      const barX = startX + bar * barSpacing;
      const barHeight = this._smoothHeights[bar];

      // Stack blocks vertically for this bar
      for (let j = 0; j < blocksPerBar; j++) {
        if (entityIdx >= n) break;

        // Normalised position in bar (0 = bottom, 1 = top of max)
        const blockYNorm = (j + 0.5) / blocksPerBar;

        // Only show blocks up to current height
        const maxHeight = 0.7;
        const blockY = 0.1 + blockYNorm * maxHeight;

        // Visibility based on bar height
        const visible = blockYNorm <= barHeight;

        // Scale
        let scale = this.config.baseScale;
        if (visible) {
          // Blocks near the top of current height are brighter
          let topProximity = 1.0 - Math.abs(blockYNorm - barHeight) * 3;
          topProximity = Math.max(0, topProximity);
          scale += topProximity * 0.3;
        }

        if (audio.isBeat) {
          scale *= 1.2;
        }

        // Slight depth variation per bar
        const z = center + (bar - 2.5) * 0.02;

        entities.push({
          id: `block_${entityIdx}`,
          x: clamp(barX),
          y: clamp(blockY),
          z: clamp(z),
          scale: visible
            ? Math.min(this.config.maxScale, scale)
            : 0.01,
          band: bar,
          visible,
        });
        entityIdx++;
      }
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// SpectrumTubes
// ---------------------------------------------------------------------------

export class SpectrumTubes extends VisualizationPattern {
  static patternName = "Spectrum Tubes";
  static description = "3D cylindrical frequency tubes";

  private _smoothHeights = [0, 0, 0, 0, 0];
  private _rotation = 0;
  private _pulse = [0, 0, 0, 0, 0];

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    this._rotation += 0.3 * 0.016;

    // Smooth the band values and track pulses
    for (let i = 0; i < 5; i++) {
      const target = audio.bands[i];
      this._smoothHeights[i] +=
        (target - this._smoothHeights[i]) * 0.25;

      // Pulse on beat
      if (audio.isBeat) {
        this._pulse[i] = 0.5;
      }
      this._pulse[i] *= 0.9;
    }

    // Layout: 5 tubes in a row
    const tubeSpacing = 0.15;
    const startX = center - 2.0 * tubeSpacing;

    // Points per tube
    const pointsPerTube = Math.floor(n / 5);
    const ringsPerTube = Math.max(3, Math.floor(pointsPerTube / 4));
    const pointsPerRing = Math.floor(pointsPerTube / ringsPerTube);

    let entityIdx = 0;

    for (let tube = 0; tube < 5; tube++) {
      const tubeX = startX + tube * tubeSpacing;
      const tubeHeight = this._smoothHeights[tube];
      const tubeRadius = 0.03 + this._pulse[tube] * 0.02;

      for (let ring = 0; ring < ringsPerTube; ring++) {
        if (entityIdx >= n) break;

        // Ring Y position
        const ringYNorm = (ring + 0.5) / ringsPerTube;
        const maxHeight = 0.65;
        const ringY = 0.1 + ringYNorm * maxHeight;

        // Only show rings up to current height
        const visible = ringYNorm <= tubeHeight + 0.1;

        // Ring radius varies slightly
        const currentRadius = tubeRadius * (1.0 + ringYNorm * 0.2);

        for (let p = 0; p < pointsPerRing; p++) {
          if (entityIdx >= n) break;

          // Angle around ring
          let angle =
            this._rotation +
            (p / pointsPerRing) * Math.PI * 2;
          angle += tube * 0.5; // Offset per tube

          // Position
          const x = tubeX + Math.cos(angle) * currentRadius;
          const z = center + Math.sin(angle) * currentRadius;

          // Scale based on visibility and position
          let scale = this.config.baseScale * 0.7;
          if (visible) {
            // Glow near top
            if (ringYNorm > tubeHeight - 0.15) {
              scale *= 1.5;
            }
            scale += audio.bands[tube] * 0.3;
          }

          if (audio.isBeat) {
            scale *= 1.15;
          }

          entities.push({
            id: `block_${entityIdx}`,
            x: clamp(x),
            y: clamp(ringY),
            z: clamp(z),
            scale: visible
              ? Math.min(this.config.maxScale, scale)
              : 0.01,
            band: tube,
            visible,
          });
          entityIdx++;
        }
      }
    }

    this.update();
    return entities;
  }
}

// ---------------------------------------------------------------------------
// SpectrumCircle
// ---------------------------------------------------------------------------

export class SpectrumCircle extends VisualizationPattern {
  static patternName = "Spectrum Circle";
  static description = "Radial frequency bars in a circle";

  private _smoothHeights = [0, 0, 0, 0, 0];
  private _rotation = 0;

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    // Slow rotation
    this._rotation += (0.1 + audio.amplitude * 0.2) * 0.016;

    // Smooth the band values
    for (let i = 0; i < 5; i++) {
      const target = audio.bands[i];
      this._smoothHeights[i] +=
        (target - this._smoothHeights[i]) * 0.3;
    }

    // Mirror the 5 bands to create symmetry (10 segments)
    const mirroredHeights = [
      ...this._smoothHeights,
      ...this._smoothHeights.slice().reverse(),
    ];

    // Bars around a circle
    const numSegments = 10;
    const pointsPerSegment = Math.floor(n / numSegments);
    const baseRadius = 0.15;
    const maxBarLength = 0.25;

    let entityIdx = 0;

    for (let seg = 0; seg < numSegments; seg++) {
      const segAngle =
        this._rotation + (seg / numSegments) * Math.PI * 2;
      const segHeight = mirroredHeights[seg % 10];

      for (let j = 0; j < pointsPerSegment; j++) {
        if (entityIdx >= n) break;

        // Position along the bar (from center outward)
        const barPos = (j + 0.5) / pointsPerSegment;
        const visible = barPos <= segHeight + 0.1;

        // Radius from center
        const r = baseRadius + barPos * maxBarLength;

        const x = center + Math.cos(segAngle) * r;
        const z = center + Math.sin(segAngle) * r;
        const y = center; // Flat on horizontal plane

        // Scale
        const bandIdx = seg % 5;
        let scale = this.config.baseScale;
        if (visible) {
          // Brighter at the end
          if (barPos > segHeight - 0.2) {
            scale *= 1.3;
          }
          scale += audio.bands[bandIdx] * 0.2;
        }

        if (audio.isBeat) {
          scale *= 1.2;
        }

        entities.push({
          id: `block_${entityIdx}`,
          x: clamp(x),
          y: clamp(y),
          z: clamp(z),
          scale: visible
            ? Math.min(this.config.maxScale, scale)
            : 0.01,
          band: bandIdx,
          visible,
        });
        entityIdx++;
      }
    }

    this.update();
    return entities;
  }
}
