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

// ── Real Minecraft textures ──────────────────────────────────────
//
// The files under public/textures/block/ are extracted from the game jar by
// scripts/extract-mc-textures.mjs. Grass textures ship grayscale in the game
// and get a biome tint at render time, so we tint them here the same way.

/**
 * Concrete block per frequency band (bass, low-mid, mid, high-mid, high),
 * matching the materials the Lua patterns and the in-game renderer use.
 */
export const BAND_BLOCKS = [
  "orange_concrete",
  "yellow_concrete",
  "lime_concrete",
  "light_blue_concrete",
  "magenta_concrete",
] as const;

/** Approximate concrete colors, used for the procedural fallback. */
const BAND_BLOCK_COLORS: ReadonlyArray<readonly [number, number, number]> = [
  [224, 97, 1],
  [241, 175, 21],
  [94, 169, 25],
  [35, 137, 198],
  [169, 48, 159],
];

/** Set of textures the previews need, keyed by role. */
export interface MinecraftBlockTextures {
  /** One texture per band, in BAND_BLOCKS order. */
  bands: THREE.Texture[];
  grassTop: THREE.Texture;
  grassSide: THREE.Texture;
  dirt: THREE.Texture;
  stone: THREE.Texture;
}

/** Flat concrete-like stand-in for one band. */
function getProceduralBandTexture(band: number): THREE.CanvasTexture {
  const key = `band${band}`;
  if (cache[key]) return cache[key];
  const [r, g, b] = BAND_BLOCK_COLORS[band] ?? BAND_BLOCK_COLORS[0];
  cache[key] = makeTexture((data) => {
    const rand = mulberry32(1000 + band);
    for (let y = 0; y < 16; y++) {
      for (let x = 0; x < 16; x++) {
        const v = Math.floor((rand() - 0.5) * 12);
        setPixel(data, x, y, clamp(r + v, 0, 255), clamp(g + v, 0, 255), clamp(b + v, 0, 255));
      }
    }
  });
  return cache[key];
}

/** Plains biome grass color (the game's default look). */
export const GRASS_TINT: readonly [number, number, number] = [0x79, 0xc0, 0x5a];

/** Multiply RGB channels by a tint in place; alpha is left alone. */
export function multiplyTint(
  data: Uint8ClampedArray,
  tint: readonly [number, number, number],
): void {
  const [tr, tg, tb] = tint;
  for (let i = 0; i < data.length; i += 4) {
    data[i] = Math.round((data[i] * tr) / 255);
    data[i + 1] = Math.round((data[i + 1] * tg) / 255);
    data[i + 2] = Math.round((data[i + 2] * tb) / 255);
  }
}

/**
 * Convert RGB to luminance in place (Rec. 601 weights), keeping alpha. Used
 * on glowstone so per-band tints come out as true colors instead of being
 * multiplied through the texture's natural yellow.
 */
export function desaturate(data: Uint8ClampedArray): void {
  for (let i = 0; i < data.length; i += 4) {
    const luma = Math.round(0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]);
    data[i] = luma;
    data[i + 1] = luma;
    data[i + 2] = luma;
  }
}

/** Alpha-composite `overlay` onto `base` in place (both RGBA, same size). */
export function compositeOverlay(base: Uint8ClampedArray, overlay: Uint8ClampedArray): void {
  if (base.length !== overlay.length) {
    throw new Error(`compositeOverlay: size mismatch (${base.length} vs ${overlay.length})`);
  }
  for (let i = 0; i < base.length; i += 4) {
    const a = overlay[i + 3] / 255;
    if (a === 0) continue;
    base[i] = Math.round(overlay[i] * a + base[i] * (1 - a));
    base[i + 1] = Math.round(overlay[i + 1] * a + base[i + 1] * (1 - a));
    base[i + 2] = Math.round(overlay[i + 2] * a + base[i + 2] * (1 - a));
    base[i + 3] = Math.max(base[i + 3], overlay[i + 3]);
  }
}

function loadImageData(url: string): Promise<ImageData> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement("canvas");
      canvas.width = img.naturalWidth;
      canvas.height = img.naturalHeight;
      const ctx = canvas.getContext("2d");
      if (!ctx) {
        reject(new Error("2D canvas context unavailable"));
        return;
      }
      ctx.drawImage(img, 0, 0);
      resolve(ctx.getImageData(0, 0, canvas.width, canvas.height));
    };
    img.onerror = () => reject(new Error(`Failed to load texture ${url}`));
    img.src = url;
  });
}

function textureFromImageData(image: ImageData): THREE.CanvasTexture {
  const canvas = document.createElement("canvas");
  canvas.width = image.width;
  canvas.height = image.height;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("2D canvas context unavailable");
  ctx.putImageData(image, 0, 0);
  const tex = new THREE.CanvasTexture(canvas);
  tex.magFilter = THREE.NearestFilter;
  tex.minFilter = THREE.NearestFilter;
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

/** Procedural stand-ins used until the real textures load (and in tests). */
export function getProceduralTextures(): MinecraftBlockTextures {
  return {
    bands: BAND_BLOCKS.map((_, i) => getProceduralBandTexture(i)),
    grassTop: getGrassTopTexture(),
    grassSide: getGrassTopTexture(),
    dirt: getStoneTexture(),
    stone: getStoneTexture(),
  };
}

let loadedTextures: MinecraftBlockTextures | null = null;
let loadPromise: Promise<MinecraftBlockTextures> | null = null;

/** Real textures if they have finished loading, else null. */
export function getLoadedMinecraftTextures(): MinecraftBlockTextures | null {
  return loadedTextures;
}

/**
 * Load and tint the extracted game textures. Cached across the page; a
 * failure rejects so callers keep the procedural fallback and log it.
 */
export function loadMinecraftTextures(basePath = "/textures/block"): Promise<MinecraftBlockTextures> {
  if (loadedTextures) return Promise.resolve(loadedTextures);
  if (loadPromise) return loadPromise;

  loadPromise = (async () => {
    const [grassTop, grassSide, grassOverlay, dirt, stone, ...bandImages] = await Promise.all(
      [
        "grass_block_top",
        "grass_block_side",
        "grass_block_side_overlay",
        "dirt",
        "stone",
        ...BAND_BLOCKS,
      ].map((name) => loadImageData(`${basePath}/${name}.png`)),
    );

    multiplyTint(grassTop.data, GRASS_TINT);
    multiplyTint(grassOverlay.data, GRASS_TINT);
    compositeOverlay(grassSide.data, grassOverlay.data);

    loadedTextures = {
      bands: bandImages.map(textureFromImageData),
      grassTop: textureFromImageData(grassTop),
      grassSide: textureFromImageData(grassSide),
      dirt: textureFromImageData(dirt),
      stone: textureFromImageData(stone),
    };
    return loadedTextures;
  })().catch((error: unknown) => {
    loadPromise = null;
    throw error;
  });

  return loadPromise;
}
