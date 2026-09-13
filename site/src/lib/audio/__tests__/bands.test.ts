import { describe, it, expect } from "vitest";
import { computeBandLevels, BandSmoother, BeatTracker, BAND_COUNT, BAND_RANGES_HZ } from "../bands";

const SAMPLE_RATE = 48000;
const FFT_SIZE = 2048;
const BIN_HZ = SAMPLE_RATE / FFT_SIZE;

function spectrumWithTone(hz: number, magnitude = 255): Uint8Array {
  const data = new Uint8Array(FFT_SIZE / 2);
  data[Math.round(hz / BIN_HZ)] = magnitude;
  return data;
}

describe("computeBandLevels", () => {
  it("returns zeros for silence", () => {
    const levels = computeBandLevels(new Uint8Array(FFT_SIZE / 2), SAMPLE_RATE, FFT_SIZE);
    expect(levels.length).toBe(BAND_COUNT);
    expect([...levels].every((v) => v === 0)).toBe(true);
  });

  it("assigns a tone to the correct band only", () => {
    for (let b = 0; b < BAND_COUNT; b++) {
      const [lo, hi] = BAND_RANGES_HZ[b];
      const levels = computeBandLevels(spectrumWithTone((lo + hi) / 2), SAMPLE_RATE, FFT_SIZE);
      for (let other = 0; other < BAND_COUNT; other++) {
        if (other === b) expect(levels[other]).toBeGreaterThan(0);
        else expect(levels[other]).toBe(0);
      }
    }
  });

  it("saturates at 1 when every bin in a band is full scale", () => {
    const data = new Uint8Array(FFT_SIZE / 2).fill(255);
    const levels = computeBandLevels(data, SAMPLE_RATE, FFT_SIZE);
    for (const v of levels) expect(v).toBeCloseTo(1, 5);
  });

  it("rejects invalid analyser geometry", () => {
    expect(() => computeBandLevels(new Uint8Array(8), 0, FFT_SIZE)).toThrow();
  });
});

describe("BandSmoother", () => {
  it("rises quickly and falls slowly", () => {
    const s = new BandSmoother({ attack: 0.5, release: 0.1 });
    const up = s.update([1, 0, 0, 0, 0]);
    expect(up[0]).toBeCloseTo(0.5, 5);
    const down = s.update([0, 0, 0, 0, 0]);
    expect(down[0]).toBeCloseTo(0.45, 5);
  });

  it("normalizes quiet input using an adaptive ceiling", () => {
    const s = new BandSmoother({ attack: 1, release: 1, minCeiling: 0.1 });
    // A steady quiet signal above the ceiling floor reads as full scale.
    const out = s.update([0.2, 0.2, 0.2, 0.2, 0.2]);
    for (const v of out) expect(v).toBeCloseTo(1, 5);
  });

  it("does not amplify silence into noise", () => {
    const s = new BandSmoother({ attack: 1, release: 1, minCeiling: 0.1 });
    const out = s.update([0.01, 0, 0, 0, 0]);
    expect(out[0]).toBeCloseTo(0.1, 5);
  });
});

describe("BeatTracker", () => {
  function runSteadyBeats(bpm: number, seconds: number, fps = 60) {
    const tracker = new BeatTracker();
    const beatInterval = 60 / bpm;
    let beats = 0;
    let last: ReturnType<BeatTracker["update"]> | null = null;
    for (let frame = 0; frame < seconds * fps; frame++) {
      const t = frame / fps;
      const phase = (t % beatInterval) / beatInterval;
      // Sharp kick at the start of every beat, quiet otherwise.
      const bass = phase < 0.05 ? 1 : 0.15;
      last = tracker.update(bass, t);
      if (last.isBeat) beats++;
    }
    return { tracker, beats, last: last! };
  }

  it("detects roughly one beat per interval and estimates tempo", () => {
    const { beats, last } = runSteadyBeats(120, 8);
    // 8 s at 120 BPM = 16 beats; allow warm-up to swallow a couple.
    expect(beats).toBeGreaterThanOrEqual(13);
    expect(beats).toBeLessThanOrEqual(16);
    expect(last.bpm).toBeGreaterThanOrEqual(118);
    expect(last.bpm).toBeLessThanOrEqual(122);
  });

  it("does not fire on a constant level", () => {
    const tracker = new BeatTracker();
    let beats = 0;
    for (let frame = 0; frame < 300; frame++) {
      if (tracker.update(0.8, frame / 60).isBeat) beats++;
    }
    expect(beats).toBe(0);
  });

  it("respects the minimum interval between beats", () => {
    const tracker = new BeatTracker({ minIntervalSec: 0.5 });
    for (let frame = 0; frame < 60; frame++) tracker.update(0.1, frame / 60);
    const first = tracker.update(1, 1.0);
    const tooSoon = tracker.update(1, 1.1);
    expect(first.isBeat).toBe(true);
    expect(tooSoon.isBeat).toBe(false);
  });

  it("reports beat phase advancing between beats", () => {
    const { tracker } = runSteadyBeats(120, 6);
    const a = tracker.update(0.1, 6.1).beatPhase;
    const b = tracker.update(0.1, 6.3).beatPhase;
    expect(b).toBeGreaterThan(a);
    expect(b).toBeLessThanOrEqual(1);
  });
});
