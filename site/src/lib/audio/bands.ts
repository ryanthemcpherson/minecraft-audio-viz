/**
 * Pure DSP helpers for turning an FFT magnitude spectrum into the five MCAV
 * bands plus beat and tempo information. No Web Audio dependencies so the
 * math can be unit tested; browserAnalyzer.ts wires it to an AnalyserNode.
 */

/** Band edges in Hz, matching the DJ client (bass, low-mid, mid, high-mid, high). */
export const BAND_RANGES_HZ: ReadonlyArray<readonly [number, number]> = [
  [40, 250],
  [250, 500],
  [500, 2000],
  [2000, 6000],
  [6000, 20000],
];

export const BAND_COUNT = BAND_RANGES_HZ.length;

/**
 * Average byte magnitude (0..255 scaled to 0..1) of the FFT bins that fall in
 * each band. `freqData` is what AnalyserNode.getByteFrequencyData fills:
 * bin i covers frequency i * sampleRate / fftSize.
 */
export function computeBandLevels(
  freqData: Uint8Array,
  sampleRate: number,
  fftSize: number,
  out: Float32Array = new Float32Array(BAND_COUNT),
): Float32Array {
  if (sampleRate <= 0 || fftSize <= 0) {
    throw new Error(`Invalid analyser geometry: sampleRate=${sampleRate} fftSize=${fftSize}`);
  }
  const binHz = sampleRate / fftSize;
  const binCount = freqData.length;

  for (let b = 0; b < BAND_COUNT; b++) {
    const [lo, hi] = BAND_RANGES_HZ[b];
    let start = Math.floor(lo / binHz);
    let end = Math.ceil(hi / binHz);
    start = Math.max(0, Math.min(start, binCount - 1));
    end = Math.max(start + 1, Math.min(end, binCount));

    let sum = 0;
    for (let i = start; i < end; i++) sum += freqData[i];
    out[b] = sum / ((end - start) * 255);
  }
  return out;
}

/**
 * Per-band envelope follower with adaptive gain so quiet sources still reach
 * the top of the meter. Attack is fast and release slow, like a real meter.
 */
export class BandSmoother {
  private readonly smoothed: Float32Array;
  private readonly ceiling: Float32Array;
  readonly attack: number;
  readonly release: number;
  /** How quickly the adaptive ceiling forgets loud moments (per update). */
  readonly ceilingDecay: number;
  /** Floor for the ceiling so silence does not get amplified into noise. */
  readonly minCeiling: number;

  constructor(options: { attack?: number; release?: number; ceilingDecay?: number; minCeiling?: number } = {}) {
    this.attack = options.attack ?? 0.55;
    this.release = options.release ?? 0.14;
    this.ceilingDecay = options.ceilingDecay ?? 0.996;
    this.minCeiling = options.minCeiling ?? 0.12;
    this.smoothed = new Float32Array(BAND_COUNT);
    this.ceiling = new Float32Array(BAND_COUNT).fill(this.minCeiling);
  }

  /** Feed raw 0..1 levels; returns normalized, smoothed 0..1 levels. */
  update(raw: ArrayLike<number>, out: Float32Array = new Float32Array(BAND_COUNT)): Float32Array {
    for (let b = 0; b < BAND_COUNT; b++) {
      const value = Math.max(0, Math.min(1, raw[b] ?? 0));
      // Adaptive ceiling: jump up instantly, decay slowly.
      this.ceiling[b] = Math.max(this.minCeiling, this.ceiling[b] * this.ceilingDecay, value);
      const normalized = Math.min(1, value / this.ceiling[b]);
      const rate = normalized > this.smoothed[b] ? this.attack : this.release;
      this.smoothed[b] += (normalized - this.smoothed[b]) * rate;
      out[b] = this.smoothed[b];
    }
    return out;
  }

  reset(): void {
    this.smoothed.fill(0);
    this.ceiling.fill(this.minCeiling);
  }
}

export interface BeatInfo {
  isBeat: boolean;
  beatIntensity: number;
  /** 0..1 progress through the current beat interval. */
  beatPhase: number;
  bpm: number;
}

/**
 * Onset-based beat detector on the bass band with a tempo estimate from the
 * median of recent inter-beat intervals. Time is in seconds.
 */
export class BeatTracker {
  private readonly history: number[] = [];
  private readonly intervals: number[] = [];
  private lastBeatTime = -Infinity;
  private bpm = 0;
  readonly historySize: number;
  readonly threshold: number;
  readonly minLevel: number;
  readonly minIntervalSec: number;

  constructor(options: { historySize?: number; threshold?: number; minLevel?: number; minIntervalSec?: number } = {}) {
    this.historySize = options.historySize ?? 43; // ~0.7 s at 60 Hz
    this.threshold = options.threshold ?? 1.35;
    this.minLevel = options.minLevel ?? 0.25;
    this.minIntervalSec = options.minIntervalSec ?? 0.25; // 240 BPM ceiling
  }

  update(bass: number, time: number): BeatInfo {
    const mean =
      this.history.length > 0 ? this.history.reduce((a, b) => a + b, 0) / this.history.length : 0;

    const sinceLast = time - this.lastBeatTime;
    const isBeat =
      this.history.length >= 8 &&
      bass >= this.minLevel &&
      bass > mean * this.threshold &&
      sinceLast >= this.minIntervalSec;

    if (isBeat) {
      if (Number.isFinite(sinceLast) && sinceLast < 2) {
        this.intervals.push(sinceLast);
        if (this.intervals.length > 8) this.intervals.shift();
        this.bpm = this.estimateBpm();
      }
      this.lastBeatTime = time;
    }

    this.history.push(bass);
    if (this.history.length > this.historySize) this.history.shift();

    const interval = this.bpm > 0 ? 60 / this.bpm : 0;
    const beatPhase =
      interval > 0 && Number.isFinite(sinceLast)
        ? Math.min(1, Math.max(0, ((isBeat ? 0 : sinceLast) % interval) / interval))
        : 0;

    return {
      isBeat,
      beatIntensity: isBeat ? Math.min(1, 0.5 + bass * 0.5) : 0,
      beatPhase,
      bpm: this.bpm,
    };
  }

  private estimateBpm(): number {
    if (this.intervals.length < 3) return this.bpm;
    const sorted = [...this.intervals].sort((a, b) => a - b);
    const median = sorted[Math.floor(sorted.length / 2)];
    let bpm = 60 / median;
    // Fold into the 70..180 range that music actually lives in.
    while (bpm < 70) bpm *= 2;
    while (bpm > 180) bpm /= 2;
    return Math.round(bpm);
  }

  reset(): void {
    this.history.length = 0;
    this.intervals.length = 0;
    this.lastBeatTime = -Infinity;
    this.bpm = 0;
  }
}
