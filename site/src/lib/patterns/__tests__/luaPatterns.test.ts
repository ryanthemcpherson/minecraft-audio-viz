/**
 * Runs every bundled Lua pattern through the browser adapter using the
 * fengari package directly (the site loads the same code from
 * public/fengari-browser.js). Guards against a pattern that loads but throws
 * at runtime, which the gallery would otherwise show as an empty stage.
 */
import { describe, it, expect, beforeAll, vi } from "vitest";
import * as fengari from "fengari";
import { PATTERNS, LIB_LUA } from "../generated";
import { ensureFengari, LuaPatternInstance } from "../luaAdapter";
import { generateAudioState } from "../../audioSim";

beforeAll(async () => {
  // Same shape scripts/bundle-fengari.mjs exposes as window.__fengari.
  // Objects are copied because the adapter patches LUA_REGISTRYINDEX onto lauxlib
  // and ESM namespace objects are frozen.
  const { lua, lauxlib, lualib, to_luastring } = fengari;
  (window as unknown as { __fengari: unknown }).__fengari = {
    lua: { ...lua },
    lauxlib: { ...lauxlib },
    to_luastring,
    luaopen_base: lualib.luaopen_base,
    luaopen_math: lualib.luaopen_math,
    luaopen_string: lualib.luaopen_string,
    luaopen_table: lualib.luaopen_table,
    luaopen_coroutine: lualib.luaopen_coroutine,
    luaopen_utf8: lualib.luaopen_utf8,
  };
  await ensureFengari();
});

describe("bundled Lua patterns", () => {
  it("bundle is not empty", () => {
    expect(PATTERNS.length).toBeGreaterThan(0);
  });

  for (const def of PATTERNS) {
    it(`${def.id} loads and returns entities`, () => {
      const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
      const pattern = new LuaPatternInstance(LIB_LUA, def.source, {
        entityCount: def.startBlocks ?? undefined,
      });
      expect(pattern.ready, `${def.id} failed to initialize`).toBe(true);

      // Simulate a couple of seconds so beat-gated patterns get a chance to emit.
      let visible = 0;
      let lastError: string | null = null;
      for (let frame = 0; frame < 120; frame++) {
        const audio = generateAudioState(frame / 60, 0);
        const entities = pattern.calculateEntities(audio, 1 / 60);
        lastError = pattern.lastError;
        visible = Math.max(visible, entities.filter((e) => e.visible).length);
      }
      pattern.dispose();
      errorSpy.mockRestore();

      expect(lastError, `${def.id} raised a Lua error`).toBeNull();
      expect(visible, `${def.id} never produced a visible entity`).toBeGreaterThan(0);
    });
  }
});
