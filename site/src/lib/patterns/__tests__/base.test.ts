import { describe, it, expect } from "vitest";
import { DEFAULT_CONFIG } from "../base";
import type { EntityData, AudioState, PatternConfig } from "../base";

describe("DEFAULT_CONFIG", () => {
  it("has all required PatternConfig fields", () => {
    const keys: (keyof PatternConfig)[] = [
      "entityCount",
      "zoneSize",
      "beatBoost",
      "baseScale",
      "maxScale",
    ];
    for (const key of keys) {
      expect(DEFAULT_CONFIG).toHaveProperty(key);
      expect(typeof DEFAULT_CONFIG[key]).toBe("number");
    }
  });

  it("entityCount is a positive integer", () => {
    expect(DEFAULT_CONFIG.entityCount).toBeGreaterThan(0);
    expect(Number.isInteger(DEFAULT_CONFIG.entityCount)).toBe(true);
  });

  it("zoneSize is positive", () => {
    expect(DEFAULT_CONFIG.zoneSize).toBeGreaterThan(0);
  });

  it("beatBoost is >= 1 (amplifies, not attenuates)", () => {
    expect(DEFAULT_CONFIG.beatBoost).toBeGreaterThanOrEqual(1);
  });

  it("baseScale is between 0 and maxScale", () => {
    expect(DEFAULT_CONFIG.baseScale).toBeGreaterThan(0);
    expect(DEFAULT_CONFIG.baseScale).toBeLessThanOrEqual(DEFAULT_CONFIG.maxScale);
  });

  it("maxScale is positive", () => {
    expect(DEFAULT_CONFIG.maxScale).toBeGreaterThan(0);
  });

  it("is a plain object (not frozen or sealed — can be spread)", () => {
    const copy = { ...DEFAULT_CONFIG, entityCount: 64 };
    expect(copy.entityCount).toBe(64);
    // Original is unchanged
    expect(DEFAULT_CONFIG.entityCount).toBe(32);
  });
});

describe("EntityData type contracts", () => {
  function makeEntity(overrides: Partial<EntityData> = {}): EntityData {
    return {
      id: "block_0",
      x: 0.5,
      y: 0.5,
      z: 0.5,
      scale: 0.2,
      band: 0,
      visible: true,
      ...overrides,
    };
  }

  it("produces valid entity with default values", () => {
    const entity = makeEntity();
    expect(entity.id).toBe("block_0");
    expect(entity.visible).toBe(true);
  });

  it("allows entities to be placed at origin (0,0,0)", () => {
    const entity = makeEntity({ x: 0, y: 0, z: 0 });
    expect(entity.x).toBe(0);
    expect(entity.y).toBe(0);
    expect(entity.z).toBe(0);
  });

  it("allows entities to be placed at max normalized coords (1,1,1)", () => {
    const entity = makeEntity({ x: 1, y: 1, z: 1 });
    expect(entity.x).toBe(1);
    expect(entity.y).toBe(1);
    expect(entity.z).toBe(1);
  });

  it("supports invisible entities", () => {
    const entity = makeEntity({ visible: false });
    expect(entity.visible).toBe(false);
  });

  it("can build an entity array matching DEFAULT_CONFIG.entityCount", () => {
    const entities: EntityData[] = [];
    for (let i = 0; i < DEFAULT_CONFIG.entityCount; i++) {
      entities.push(
        makeEntity({
          id: `block_${i}`,
          band: i % 5,
          x: i / DEFAULT_CONFIG.entityCount,
        })
      );
    }
    expect(entities).toHaveLength(DEFAULT_CONFIG.entityCount);
    expect(entities[0].id).toBe("block_0");
    expect(entities[entities.length - 1].id).toBe(`block_${DEFAULT_CONFIG.entityCount - 1}`);
  });
});

describe("AudioState type contracts", () => {
  function makeAudioState(overrides: Partial<AudioState> = {}): AudioState {
    return {
      bands: [0.8, 0.6, 0.5, 0.3, 0.2],
      amplitude: 0.48,
      isBeat: false,
      beatIntensity: 0,
      beatPhase: 0.5,
      bpm: 128,
      frame: 0,
      ...overrides,
    };
  }

  it("bands has exactly 5 elements (one per frequency band)", () => {
    const state = makeAudioState();
    expect(state.bands).toHaveLength(5);
  });

  it("amplitude can be computed from bands", () => {
    const bands = [1.0, 0.8, 0.6, 0.4, 0.2];
    const expectedAmplitude = bands.reduce((a, b) => a + b, 0) / 5;
    const state = makeAudioState({ bands, amplitude: expectedAmplitude });
    expect(state.amplitude).toBeCloseTo(0.6);
  });

  it("beatIntensity is 0 when isBeat is false", () => {
    const state = makeAudioState({ isBeat: false, beatIntensity: 0 });
    expect(state.beatIntensity).toBe(0);
  });

  it("beatPhase ranges from 0 to 1", () => {
    for (const phase of [0, 0.25, 0.5, 0.75, 1.0]) {
      const state = makeAudioState({ beatPhase: phase });
      expect(state.beatPhase).toBeGreaterThanOrEqual(0);
      expect(state.beatPhase).toBeLessThanOrEqual(1);
    }
  });

  it("frame is a non-negative integer", () => {
    const state = makeAudioState({ frame: 120 });
    expect(state.frame).toBeGreaterThanOrEqual(0);
    expect(Number.isInteger(state.frame)).toBe(true);
  });
});
