import { describe, it, expect, vi, beforeEach } from "vitest";

// Mock THREE.js to avoid WebGL dependency in tests.
// Use a class so `new THREE.CanvasTexture(canvas)` works.
vi.mock("three", () => {
  class MockCanvasTexture {
    magFilter: number | null = null;
    minFilter: number | null = null;
    colorSpace: string | null = null;
  }
  return {
    CanvasTexture: MockCanvasTexture,
    NearestFilter: 1003,
    SRGBColorSpace: "srgb",
  };
});

// Minimal canvas mock for jsdom (no native canvas support)
function createMockCanvas() {
  const imageDataStore: { data: Uint8ClampedArray; width: number; height: number }[] = [];

  return {
    width: 0,
    height: 0,
    getContext: vi.fn().mockReturnValue({
      createImageData: (w: number, h: number) => {
        const imgData = {
          data: new Uint8ClampedArray(w * h * 4),
          width: w,
          height: h,
        };
        imageDataStore.push(imgData);
        return imgData;
      },
      putImageData: vi.fn(),
    }),
    _imageDataStore: imageDataStore,
  };
}

describe("blockTextures", () => {
  let mockCanvases: ReturnType<typeof createMockCanvas>[];
  // Keep original createElement to avoid infinite recursion
  const originalCreateElement = document.createElement.bind(document);

  beforeEach(async () => {
    // Clear module cache so the internal texture cache resets
    vi.resetModules();

    mockCanvases = [];
    vi.spyOn(document, "createElement").mockImplementation((tag: string) => {
      if (tag === "canvas") {
        const mock = createMockCanvas();
        mockCanvases.push(mock);
        return mock as unknown as HTMLElement;
      }
      return originalCreateElement(tag);
    });
  });

  async function importModule() {
    return import("../blockTextures");
  }

  describe("getGrassTopTexture", () => {
    it("returns a texture object", async () => {
      const { getGrassTopTexture } = await importModule();
      const tex = getGrassTopTexture();
      expect(tex).toBeDefined();
    });

    it("creates a 16x16 canvas", async () => {
      const { getGrassTopTexture } = await importModule();
      getGrassTopTexture();
      expect(mockCanvases.length).toBeGreaterThanOrEqual(1);
      const canvas = mockCanvases[mockCanvases.length - 1];
      expect(canvas.width).toBe(16);
      expect(canvas.height).toBe(16);
    });

    it("fills all 256 pixels (16x16) with RGBA data", async () => {
      const { getGrassTopTexture } = await importModule();
      getGrassTopTexture();
      const canvas = mockCanvases[mockCanvases.length - 1];
      const imgData = canvas._imageDataStore[0];
      expect(imgData.data.length).toBe(16 * 16 * 4);

      // Every pixel should have alpha = 255 (fully opaque)
      for (let i = 0; i < 256; i++) {
        expect(imgData.data[i * 4 + 3]).toBe(255);
      }
    });

    it("produces green-toned pixels", async () => {
      const { getGrassTopTexture } = await importModule();
      getGrassTopTexture();
      const canvas = mockCanvases[mockCanvases.length - 1];
      const imgData = canvas._imageDataStore[0];

      // Green channel should dominate for grass
      let greenDominantCount = 0;
      for (let i = 0; i < 256; i++) {
        const r = imgData.data[i * 4];
        const g = imgData.data[i * 4 + 1];
        const b = imgData.data[i * 4 + 2];
        if (g > r && g > b) greenDominantCount++;
      }
      // At least 90% of pixels should be green-dominant
      expect(greenDominantCount).toBeGreaterThan(230);
    });

    it("caches the result on second call", async () => {
      const { getGrassTopTexture } = await importModule();
      const tex1 = getGrassTopTexture();
      const tex2 = getGrassTopTexture();
      expect(tex1).toBe(tex2);
    });
  });

  describe("getStoneTexture", () => {
    it("returns a texture object", async () => {
      const { getStoneTexture } = await importModule();
      const tex = getStoneTexture();
      expect(tex).toBeDefined();
    });

    it("creates a 16x16 canvas", async () => {
      const { getStoneTexture } = await importModule();
      getStoneTexture();
      const canvas = mockCanvases[mockCanvases.length - 1];
      expect(canvas.width).toBe(16);
      expect(canvas.height).toBe(16);
    });

    it("fills all pixels as opaque", async () => {
      const { getStoneTexture } = await importModule();
      getStoneTexture();
      const canvas = mockCanvases[mockCanvases.length - 1];
      const imgData = canvas._imageDataStore[0];

      for (let i = 0; i < 256; i++) {
        expect(imgData.data[i * 4 + 3]).toBe(255);
      }
    });

    it("produces grayscale pixels (r == g == b)", async () => {
      const { getStoneTexture } = await importModule();
      getStoneTexture();
      const canvas = mockCanvases[mockCanvases.length - 1];
      const imgData = canvas._imageDataStore[0];

      for (let i = 0; i < 256; i++) {
        const r = imgData.data[i * 4];
        const g = imgData.data[i * 4 + 1];
        const b = imgData.data[i * 4 + 2];
        expect(r).toBe(g);
        expect(g).toBe(b);
      }
    });

    it("produces pixel values in the expected gray range (70-160)", async () => {
      const { getStoneTexture } = await importModule();
      getStoneTexture();
      const canvas = mockCanvases[mockCanvases.length - 1];
      const imgData = canvas._imageDataStore[0];

      for (let i = 0; i < 256; i++) {
        const v = imgData.data[i * 4]; // r channel (same as g and b)
        expect(v).toBeGreaterThanOrEqual(70);
        expect(v).toBeLessThanOrEqual(160);
      }
    });

    it("caches the result on second call", async () => {
      const { getStoneTexture } = await importModule();
      const tex1 = getStoneTexture();
      const tex2 = getStoneTexture();
      expect(tex1).toBe(tex2);
    });
  });

  describe("getVizBlockTexture", () => {
    it("returns a texture object with grayscale pixels", async () => {
      const { getVizBlockTexture } = await importModule();
      const tex = getVizBlockTexture();
      expect(tex).toBeDefined();

      const canvas = mockCanvases[mockCanvases.length - 1];
      const imgData = canvas._imageDataStore[0];

      // Viz block is grayscale (r == g == b)
      for (let i = 0; i < 256; i++) {
        const r = imgData.data[i * 4];
        const g = imgData.data[i * 4 + 1];
        const b = imgData.data[i * 4 + 2];
        expect(r).toBe(g);
        expect(g).toBe(b);
      }
    });

    it("has pixel values in range 40-255 (voronoi brightness)", async () => {
      const { getVizBlockTexture } = await importModule();
      getVizBlockTexture();
      const canvas = mockCanvases[mockCanvases.length - 1];
      const imgData = canvas._imageDataStore[0];

      for (let i = 0; i < 256; i++) {
        const v = imgData.data[i * 4];
        expect(v).toBeGreaterThanOrEqual(40);
        expect(v).toBeLessThanOrEqual(255);
      }
    });
  });

  describe("getOakPlanksTexture", () => {
    it("returns a texture with brown-toned pixels (r >= g >= b)", async () => {
      const { getOakPlanksTexture } = await importModule();
      const tex = getOakPlanksTexture();
      expect(tex).toBeDefined();

      const canvas = mockCanvases[mockCanvases.length - 1];
      const imgData = canvas._imageDataStore[0];

      let brownCount = 0;
      for (let i = 0; i < 256; i++) {
        const r = imgData.data[i * 4];
        const g = imgData.data[i * 4 + 1];
        const b = imgData.data[i * 4 + 2];
        if (r >= g && g >= b) brownCount++;
      }
      // Vast majority should follow r >= g >= b pattern for brown
      expect(brownCount).toBeGreaterThan(200);
    });
  });

  describe("deterministic output", () => {
    it("produces identical pixel data across fresh imports", async () => {
      const mod1 = await importModule();
      mod1.getStoneTexture();
      const data1 = new Uint8ClampedArray(
        mockCanvases[mockCanvases.length - 1]._imageDataStore[0].data
      );

      vi.resetModules();
      mockCanvases = [];

      const mod2 = await importModule();
      mod2.getStoneTexture();
      const data2 = mockCanvases[mockCanvases.length - 1]._imageDataStore[0].data;

      expect(data1).toEqual(data2);
    });
  });
});
