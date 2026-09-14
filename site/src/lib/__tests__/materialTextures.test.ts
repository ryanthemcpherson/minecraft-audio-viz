import { describe, it, expect } from "vitest";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { MATERIAL_TEXTURE_FILES, BAND_BLOCKS, hasMaterialTexture } from "../blockTextures";
import { normalizeMaterialName } from "../patterns/luaAdapter";

const TEXTURE_DIR = join(__dirname, "..", "..", "..", "public", "textures", "block");

describe("material texture map", () => {
  it("uses Bukkit-style keys and has an extracted PNG for every entry", () => {
    const entries = Object.entries(MATERIAL_TEXTURE_FILES);
    expect(entries.length).toBeGreaterThan(20);
    for (const [material, file] of entries) {
      expect(material).toMatch(/^[A-Z][A-Z0-9_]*$/);
      expect(existsSync(join(TEXTURE_DIR, `${file}.png`)), `${file}.png missing for ${material}`).toBe(true);
    }
  });

  it("covers every band block", () => {
    for (const block of BAND_BLOCKS) {
      expect(hasMaterialTexture(block.toUpperCase())).toBe(true);
    }
  });

  it("covers the materials the bundled Lua patterns emit", () => {
    for (const material of [
      "GOLD_BLOCK",
      "GLOWSTONE",
      "IRON_BLOCK",
      "NETHER_BRICKS",
      "REDSTONE_BLOCK",
      "BLACKSTONE",
      "SEA_LANTERN",
      "BONE_BLOCK",
      "RED_MUSHROOM_BLOCK",
      "MUSHROOM_STEM",
      "END_ROD",
      "CRYING_OBSIDIAN",
      "AMETHYST_BLOCK",
    ]) {
      expect(hasMaterialTexture(material), material).toBe(true);
    }
  });

  it("rejects unknown materials", () => {
    expect(hasMaterialTexture("NOT_A_BLOCK")).toBe(false);
    expect(hasMaterialTexture("_comment")).toBe(false);
  });
});

describe("normalizeMaterialName", () => {
  it("uppercases and strips the minecraft namespace", () => {
    expect(normalizeMaterialName("minecraft:orange_concrete")).toBe("ORANGE_CONCRETE");
    expect(normalizeMaterialName("Orange Concrete")).toBe("ORANGE_CONCRETE");
    expect(normalizeMaterialName("  gold-block ")).toBe("GOLD_BLOCK");
  });

  it("returns undefined for empty input", () => {
    expect(normalizeMaterialName("")).toBeUndefined();
    expect(normalizeMaterialName("   ")).toBeUndefined();
  });
});
