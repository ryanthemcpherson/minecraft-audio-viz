/**
 * Pattern Engine - Base Types
 * Direct TypeScript port of audio_processor/patterns.py base classes
 */

export interface EntityData {
  id: string;
  x: number;
  y: number;
  z: number;
  scale: number;
  band: number;
  visible: boolean;
}

export interface AudioState {
  bands: number[]; // 5 frequency bands (bass, low-mid, mid, high-mid, high)
  amplitude: number; // Overall amplitude 0-1
  isBeat: boolean; // Beat detected this frame
  beatIntensity: number; // Beat strength 0-1
  frame: number; // Frame counter
}

export interface PatternConfig {
  entityCount: number;
  zoneSize: number;
  beatBoost: number;
  baseScale: number;
  maxScale: number;
}

export const DEFAULT_CONFIG: PatternConfig = {
  entityCount: 32,
  zoneSize: 10,
  beatBoost: 1.5,
  baseScale: 0.2,
  maxScale: 1.0,
};

export function clamp(value: number, min = 0, max = 1): number {
  return Math.max(min, Math.min(max, value));
}

export function fibonacciSphere(n: number): [number, number, number][] {
  const points: [number, number, number][] = [];
  const phi = Math.PI * (3.0 - Math.sqrt(5.0));
  for (let i = 0; i < n; i++) {
    const y = n > 1 ? 1 - (i / (n - 1)) * 2 : 0;
    const radius = Math.sqrt(1 - y * y);
    const theta = phi * i;
    points.push([Math.cos(theta) * radius, y, Math.sin(theta) * radius]);
  }
  return points;
}

// Seeded pseudo-random for deterministic patterns
export class SeededRandom {
  private seed: number;
  constructor(seed: number) {
    this.seed = seed;
  }
  next(): number {
    this.seed = (this.seed * 16807 + 0) % 2147483647;
    return this.seed / 2147483647;
  }
  uniform(min: number, max: number): number {
    return min + this.next() * (max - min);
  }
  randint(min: number, max: number): number {
    return Math.floor(this.uniform(min, max + 1));
  }
}

export abstract class VisualizationPattern {
  static patternName: string = "Base";
  static description: string = "Base pattern";

  config: PatternConfig;
  protected _time: number = 0;
  protected _beatAccumulator: number = 0;

  constructor(config?: Partial<PatternConfig>) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  abstract calculateEntities(audio: AudioState): EntityData[];

  update(dt: number = 0.016): void {
    this._time += dt;
    this._beatAccumulator *= 0.9;
  }
}
