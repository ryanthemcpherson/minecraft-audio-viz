import { describe, it, expect } from "vitest";
import { multiplyTint, compositeOverlay, desaturate, GRASS_TINT } from "../blockTextures";

function rgba(...pixels: number[][]): Uint8ClampedArray {
  return new Uint8ClampedArray(pixels.flat());
}

describe("desaturate", () => {
  it("replaces RGB with luminance and keeps alpha", () => {
    const data = rgba([255, 0, 0, 200], [0, 255, 0, 255], [0, 0, 255, 10]);
    desaturate(data);
    expect([...data.slice(0, 4)]).toEqual([76, 76, 76, 200]);
    expect([...data.slice(4, 8)]).toEqual([150, 150, 150, 255]);
    expect([...data.slice(8, 12)]).toEqual([29, 29, 29, 10]);
  });

  it("leaves gray pixels unchanged", () => {
    const data = rgba([120, 120, 120, 255]);
    desaturate(data);
    expect([...data]).toEqual([120, 120, 120, 255]);
  });
});

describe("multiplyTint", () => {
  it("scales RGB by the tint and leaves alpha alone", () => {
    const data = rgba([255, 255, 255, 200], [128, 128, 128, 255]);
    multiplyTint(data, [0x79, 0xc0, 0x5a]);
    expect([...data.slice(0, 4)]).toEqual([0x79, 0xc0, 0x5a, 200]);
    expect([...data.slice(4, 8)]).toEqual([61, 96, 45, 255]);
  });

  it("is a no-op for a white tint", () => {
    const data = rgba([10, 20, 30, 40]);
    multiplyTint(data, [255, 255, 255]);
    expect([...data]).toEqual([10, 20, 30, 40]);
  });

  it("exposes a green plains tint", () => {
    const [r, g, b] = GRASS_TINT;
    expect(g).toBeGreaterThan(r);
    expect(g).toBeGreaterThan(b);
  });
});

describe("compositeOverlay", () => {
  it("replaces base pixels where the overlay is opaque", () => {
    const base = rgba([100, 100, 100, 255]);
    const overlay = rgba([0, 200, 0, 255]);
    compositeOverlay(base, overlay);
    expect([...base]).toEqual([0, 200, 0, 255]);
  });

  it("leaves base pixels untouched where the overlay is transparent", () => {
    const base = rgba([100, 110, 120, 255]);
    const overlay = rgba([0, 200, 0, 0]);
    compositeOverlay(base, overlay);
    expect([...base]).toEqual([100, 110, 120, 255]);
  });

  it("blends by overlay alpha", () => {
    const base = rgba([0, 0, 0, 255]);
    const overlay = rgba([200, 100, 50, 128]);
    compositeOverlay(base, overlay);
    const a = 128 / 255;
    expect(base[0]).toBe(Math.round(200 * a));
    expect(base[1]).toBe(Math.round(100 * a));
    expect(base[2]).toBe(Math.round(50 * a));
    expect(base[3]).toBe(255);
  });

  it("rejects mismatched sizes", () => {
    expect(() => compositeOverlay(rgba([0, 0, 0, 0]), rgba([0, 0, 0, 0], [0, 0, 0, 0]))).toThrow();
  });
});
