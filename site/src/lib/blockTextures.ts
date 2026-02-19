/**
 * Procedural 16×16 Minecraft-style block textures.
 * Generated via Canvas and cached — no image files needed.
 * All textures use NearestFilter for the crispy pixel-art look.
 */

import * as THREE from "three";

// ── Seeded PRNG (mulberry32) ─────────────────────────────────────
function mulberry32(seed: number): () => number {
  let s = seed | 0;
  return () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function clamp(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v;
}

// ── Texture factory ──────────────────────────────────────────────
function makeTexture(fill: (data: Uint8ClampedArray) => void): THREE.CanvasTexture {
  const canvas = document.createElement("canvas");
  canvas.width = 16;
  canvas.height = 16;
  const ctx = canvas.getContext("2d")!;
  const img = ctx.createImageData(16, 16);
  fill(img.data);
  ctx.putImageData(img, 0, 0);

  const tex = new THREE.CanvasTexture(canvas);
  tex.magFilter = THREE.NearestFilter;
  tex.minFilter = THREE.NearestFilter;
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

function setPixel(
  data: Uint8ClampedArray,
  x: number,
  y: number,
  r: number,
  g: number,
  b: number,
) {
  const i = (y * 16 + x) * 4;
  data[i] = r;
  data[i + 1] = g;
  data[i + 2] = b;
  data[i + 3] = 255;
}

// ── Cache ────────────────────────────────────────────────────────
const cache: Record<string, THREE.CanvasTexture> = {};

/**
 * Grayscale Voronoi-cell texture (glowstone-like).
 * Designed to be tinted by per-instance band colors.
 * Bright cell centers, dark cracks between cells.
 */
export function getVizBlockTexture(): THREE.CanvasTexture {
  if (cache.viz) return cache.viz;

  const rand = mulberry32(42);
  const cells: [number, number][] = [];
  for (let i = 0; i < 8; i++) cells.push([rand() * 16, rand() * 16]);

  cache.viz = makeTexture((data) => {
    const noiseRand = mulberry32(999);
    for (let y = 0; y < 16; y++) {
      for (let x = 0; x < 16; x++) {
        // Voronoi distance (wrapping at edges for seamless tiling)
        let minDist = 99;
        for (const [cx, cy] of cells) {
          const dx = Math.min(Math.abs(x + 0.5 - cx), 16 - Math.abs(x + 0.5 - cx));
          const dy = Math.min(Math.abs(y + 0.5 - cy), 16 - Math.abs(y + 0.5 - cy));
          minDist = Math.min(minDist, Math.sqrt(dx * dx + dy * dy));
        }
        const bright = clamp(1.0 - minDist * 0.15, 0.3, 1.0);
        const noise = (noiseRand() - 0.5) * 0.07;
        const v = Math.round(clamp((bright + noise) * 255, 40, 255));
        setPixel(data, x, y, v, v, v);
      }
    }
  });

  return cache.viz;
}

/** Grass block top — random greens like Minecraft's grass. */
export function getGrassTopTexture(): THREE.CanvasTexture {
  if (cache.grass) return cache.grass;

  const palette: [number, number, number][] = [
    [89, 140, 48],
    [76, 124, 38],
    [107, 155, 62],
    [84, 127, 45],
    [98, 144, 54],
    [70, 114, 32],
  ];

  cache.grass = makeTexture((data) => {
    const rand = mulberry32(123);
    for (let y = 0; y < 16; y++) {
      for (let x = 0; x < 16; x++) {
        const [r, g, b] = palette[Math.floor(rand() * palette.length)];
        const v = Math.floor((rand() - 0.5) * 14);
        setPixel(
          data, x, y,
          clamp(r + v, 0, 255),
          clamp(g + v, 0, 255),
          clamp(b + v, 0, 255),
        );
      }
    }
  });

  return cache.grass;
}

/** Stone block — gray tones with darker speckles. */
export function getStoneTexture(): THREE.CanvasTexture {
  if (cache.stone) return cache.stone;

  cache.stone = makeTexture((data) => {
    const rand = mulberry32(456);
    for (let y = 0; y < 16; y++) {
      for (let x = 0; x < 16; x++) {
        const base = 115 + Math.floor((rand() - 0.5) * 40);
        const speckle = rand() < 0.12 ? -25 : 0;
        const v = clamp(base + speckle, 70, 160);
        setPixel(data, x, y, v, v, v);
      }
    }
  });

  return cache.stone;
}

/** Oak planks — brown with horizontal grain lines. */
export function getOakPlanksTexture(): THREE.CanvasTexture {
  if (cache.oak) return cache.oak;

  cache.oak = makeTexture((data) => {
    const rand = mulberry32(789);
    for (let y = 0; y < 16; y++) {
      const plankRow = Math.floor(y / 4);
      const plankBase = plankRow % 2 === 0 ? 160 : 145;
      const isSeam = y % 4 === 0;
      for (let x = 0; x < 16; x++) {
        const grain = Math.floor((rand() - 0.5) * 18);
        const seamDark = isSeam ? -20 : 0;
        const base = plankBase + grain + seamDark;
        const r = clamp(base, 80, 200);
        const g = clamp(Math.floor(base * 0.72), 60, 145);
        const b = clamp(Math.floor(base * 0.45), 35, 90);
        setPixel(data, x, y, r, g, b);
      }
    }
  });

  return cache.oak;
}
