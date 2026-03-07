import { describe, it, expect } from "vitest";
import { generateAudioState } from "../audioSim";

describe("generateAudioState", () => {
  it("returns an AudioState with all required fields", () => {
    const state = generateAudioState(1.0);
    expect(state).toHaveProperty("bands");
    expect(state).toHaveProperty("amplitude");
    expect(state).toHaveProperty("isBeat");
    expect(state).toHaveProperty("beatIntensity");
    expect(state).toHaveProperty("beatPhase");
    expect(state).toHaveProperty("bpm");
    expect(state).toHaveProperty("frame");
  });

  it("returns exactly 5 frequency bands", () => {
    const state = generateAudioState(0.5);
    expect(state.bands).toHaveLength(5);
  });

  it("clamps all band values between 0 and 1", () => {
    // Test across a range of time values
    for (let t = 0; t < 10; t += 0.37) {
      const state = generateAudioState(t);
      for (const band of state.bands) {
        expect(band).toBeGreaterThanOrEqual(0);
        expect(band).toBeLessThanOrEqual(1);
      }
    }
  });

  it("computes amplitude as mean of bands", () => {
    const state = generateAudioState(2.0);
    const expectedAmplitude = state.bands.reduce((a, b) => a + b, 0) / 5;
    expect(state.amplitude).toBeCloseTo(expectedAmplitude, 10);
  });

  it("always reports 128 BPM", () => {
    expect(generateAudioState(0).bpm).toBe(128);
    expect(generateAudioState(5.5).bpm).toBe(128);
  });

  it("computes frame from time", () => {
    const state = generateAudioState(2.0);
    expect(state.frame).toBe(Math.floor(2.0 * 60));
  });

  it("applies phase offset to shift the pattern", () => {
    const noOffset = generateAudioState(1.0, 0);
    const withOffset = generateAudioState(1.0, 0.5);
    // With a phase offset the bands should differ
    expect(noOffset.bands).not.toEqual(withOffset.bands);
  });

  it("caches results for same time and phase within a frame", () => {
    const a = generateAudioState(3.0, 0);
    const b = generateAudioState(3.0, 0);
    expect(a).toBe(b); // Same reference (cached)
  });

  it("beatIntensity is 0 when isBeat is false", () => {
    // Sample many time values; when isBeat is false, beatIntensity must be 0
    for (let t = 0; t < 10; t += 0.13) {
      const state = generateAudioState(t);
      if (!state.isBeat) {
        expect(state.beatIntensity).toBe(0);
      }
    }
  });
});
