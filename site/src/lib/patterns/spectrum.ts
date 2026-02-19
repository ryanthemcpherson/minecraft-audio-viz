/**
 * Spectrum Analyzer Patterns
 * Classic spectrum visualizations (bars, tubes, circle).
 * Ported from audio_processor/patterns.py
 */

import {
  type AudioState,
  type EntityData,
  VisualizationPattern,
  clamp,
} from "./base";

// ============================================================================
// SpectrumBars
// ============================================================================
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

    for (let i = 0; i < 5; i++) {
      const target = audio.bands[i];
      this._smoothHeights[i] += (target - this._smoothHeights[i]) * 0.3;

      if (this._smoothHeights[i] > this._peakHeights[i]) {
        this._peakHeights[i] = this._smoothHeights[i];
        this._peakFall[i] = 0;
      } else {
        this._peakFall[i] += 0.02;
        this._peakHeights[i] -= this._peakFall[i] * 0.016;
      }
      this._peakHeights[i] = Math.max(0, this._peakHeights[i]);
    }

    const blocksPerBar = Math.floor(n / 5);
    const barSpacing = 0.16;
    const startX = center - 2.0 * barSpacing;

    let entityIdx = 0;

    for (let bar = 0; bar < 5; bar++) {
      const barX = startX + bar * barSpacing;
      const barHeight = this._smoothHeights[bar];

      for (let j = 0; j < blocksPerBar; j++) {
        if (entityIdx >= n) break;

        const blockYNorm = (j + 0.5) / blocksPerBar;
        const maxHeight = 0.7;
        const blockY = 0.1 + blockYNorm * maxHeight;
        const visible = blockYNorm <= barHeight;

        let scale = this.config.baseScale;
        if (visible) {
          const topProximity = Math.max(
            0,
            1.0 - Math.abs(blockYNorm - barHeight) * 3
          );
          scale += topProximity * 0.3;
        }
        if (audio.isBeat) scale *= 1.2;

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

// ============================================================================
// SpectrumTubes
// ============================================================================
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

    for (let i = 0; i < 5; i++) {
      const target = audio.bands[i];
      this._smoothHeights[i] += (target - this._smoothHeights[i]) * 0.25;
      if (audio.isBeat) this._pulse[i] = 0.5;
      this._pulse[i] *= 0.9;
    }

    const tubeSpacing = 0.15;
    const startX = center - 2.0 * tubeSpacing;

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

        const ringYNorm = (ring + 0.5) / ringsPerTube;
        const maxHeight = 0.65;
        const ringY = 0.1 + ringYNorm * maxHeight;
        const visible = ringYNorm <= tubeHeight + 0.1;
        const currentRadius = tubeRadius * (1.0 + ringYNorm * 0.2);

        for (let p = 0; p < pointsPerRing; p++) {
          if (entityIdx >= n) break;

          const angle =
            this._rotation +
            (p / pointsPerRing) * Math.PI * 2 +
            tube * 0.5;

          const x = tubeX + Math.cos(angle) * currentRadius;
          const z = center + Math.sin(angle) * currentRadius;

          let scale = this.config.baseScale * 0.7;
          if (visible) {
            if (ringYNorm > tubeHeight - 0.15) scale *= 1.5;
            scale += audio.bands[tube] * 0.3;
          }
          if (audio.isBeat) scale *= 1.15;

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

// ============================================================================
// SpectrumCircle
// ============================================================================
export class SpectrumCircle extends VisualizationPattern {
  static patternName = "Spectrum Circle";
  static description = "Radial frequency bars in a circle";

  private _smoothHeights = [0, 0, 0, 0, 0];
  private _rotation = 0;

  calculateEntities(audio: AudioState): EntityData[] {
    const entities: EntityData[] = [];
    const n = this.config.entityCount;
    const center = 0.5;

    this._rotation += (0.1 + audio.amplitude * 0.2) * 0.016;

    for (let i = 0; i < 5; i++) {
      const target = audio.bands[i];
      this._smoothHeights[i] += (target - this._smoothHeights[i]) * 0.3;
    }

    const mirroredHeights = [
      ...this._smoothHeights,
      ...this._smoothHeights.slice().reverse(),
    ];

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

        const barPos = (j + 0.5) / pointsPerSegment;
        const visible = barPos <= segHeight + 0.1;

        const r = baseRadius + barPos * maxBarLength;
        const x = center + Math.cos(segAngle) * r;
        const z = center + Math.sin(segAngle) * r;
        const y = center;

        const bandIdx = seg % 5;
        let scale = this.config.baseScale;
        if (visible) {
          if (barPos > segHeight - 0.2) scale *= 1.3;
          scale += audio.bands[bandIdx] * 0.2;
        }
        if (audio.isBeat) scale *= 1.2;

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
