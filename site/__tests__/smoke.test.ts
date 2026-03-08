import { describe, it, expect } from "vitest";
import { DEFAULT_CONFIG } from "../src/lib/patterns/base";
import type { AudioState, PatternConfig, EntityData } from "../src/lib/patterns/base";

describe("PatternConfig defaults", () => {
  it("has expected default values", () => {
    expect(DEFAULT_CONFIG.entityCount).toBe(32);
    expect(DEFAULT_CONFIG.zoneSize).toBe(10);
    expect(DEFAULT_CONFIG.beatBoost).toBe(1.5);
    expect(DEFAULT_CONFIG.baseScale).toBe(0.2);
    expect(DEFAULT_CONFIG.maxScale).toBe(1.0);
  });

  it("all numeric values are positive", () => {
    for (const [key, value] of Object.entries(DEFAULT_CONFIG)) {
      expect(value, `${key} should be positive`).toBeGreaterThan(0);
    }
  });

  it("maxScale >= baseScale", () => {
    expect(DEFAULT_CONFIG.maxScale).toBeGreaterThanOrEqual(DEFAULT_CONFIG.baseScale);
  });
});

describe("EntityData shape", () => {
  it("can construct a valid EntityData object", () => {
    const entity: EntityData = {
      id: "block_0",
      x: 0.5,
      y: 0.5,
      z: 0.5,
      scale: 0.2,
      band: 0,
      visible: true,
    };

    expect(entity.id).toBe("block_0");
    expect(entity.x).toBe(0.5);
    expect(entity.y).toBe(0.5);
    expect(entity.z).toBe(0.5);
    expect(entity.scale).toBe(0.2);
    expect(entity.band).toBe(0);
    expect(entity.visible).toBe(true);
  });

  it("supports all 5 frequency bands (0-4)", () => {
    for (let band = 0; band < 5; band++) {
      const entity: EntityData = {
        id: `block_${band}`,
        x: 0,
        y: 0,
        z: 0,
        scale: 0.2,
        band,
        visible: true,
      };
      expect(entity.band).toBe(band);
    }
  });
});

describe("AudioState shape", () => {
  it("can construct a valid AudioState object", () => {
    const state: AudioState = {
      bands: [0.8, 0.6, 0.5, 0.3, 0.2],
      amplitude: 0.48,
      isBeat: true,
      beatIntensity: 0.9,
      beatPhase: 0.02,
      bpm: 128,
      frame: 120,
    };

    expect(state.bands).toHaveLength(5);
    expect(state.amplitude).toBe(0.48);
    expect(state.isBeat).toBe(true);
    expect(state.beatIntensity).toBe(0.9);
    expect(state.bpm).toBe(128);
    expect(state.frame).toBe(120);
  });
});
