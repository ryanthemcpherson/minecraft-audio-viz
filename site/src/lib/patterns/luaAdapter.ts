/**
 * Lua Pattern Adapter
 * Executes Lua pattern scripts in the browser via fengari.
 *
 * Fengari is loaded as an IIFE from public/fengari-browser.js (built by
 * scripts/bundle-fengari.mjs). This bypasses Turbopack's module transformation
 * which mangles fengari's internal Lua compiler state.
 */

/* eslint-disable @typescript-eslint/no-explicit-any */

import type { EntityData, AudioState, PatternConfig } from "./base";
import { DEFAULT_CONFIG } from "./base";

// --- Fengari globals (populated by script load) ---
let lua: any = null;
let lauxlib: any = null;
let to_luastring: (s: string) => Uint8Array;
let luaopen_base: any = null;
let luaopen_math: any = null;
let luaopen_string: any = null;
let luaopen_table: any = null;
let luaopen_coroutine: any = null;
let luaopen_utf8: any = null;
let luaL_requiref: any = null;

let fengariReady = false;
let loadPromise: Promise<void> | null = null;

function bindGlobals(): void {
  const f = (window as any).__fengari;
  if (!f) throw new Error("__fengari global not found after script load");
  lua = f.lua;
  lauxlib = f.lauxlib;
  to_luastring = f.to_luastring;
  luaopen_base = f.luaopen_base;
  luaopen_math = f.luaopen_math;
  luaopen_string = f.luaopen_string;
  luaopen_table = f.luaopen_table;
  luaopen_coroutine = f.luaopen_coroutine;
  luaopen_utf8 = f.luaopen_utf8;
  luaL_requiref = lauxlib.luaL_requiref;
  // Patch LUA_REGISTRYINDEX onto lauxlib so both old and new code works
  // (fengari's lauxlib doesn't export it; only lua does)
  lauxlib.LUA_REGISTRYINDEX = lua.LUA_REGISTRYINDEX;
  fengariReady = true;
}

export function ensureFengari(): Promise<void> {
  if (fengariReady) return Promise.resolve();
  if (loadPromise) return loadPromise;
  if (typeof window === "undefined" || typeof document === "undefined") {
    return Promise.resolve();
  }

  // Already loaded by another script tag (e.g. test harness)
  if ((window as any).__fengari) {
    bindGlobals();
    return Promise.resolve();
  }

  loadPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "/fengari-browser.js";
    script.onload = () => {
      try {
        bindGlobals();
        resolve();
      } catch (e) {
        reject(e);
      }
    };
    script.onerror = () => reject(new Error("Failed to load fengari-browser.js"));
    document.head.appendChild(script);
  });

  return loadPromise;
}

export function isFengariReady(): boolean {
  return fengariReady;
}

/**
 * Open only the safe standard libraries (no os, debug, io, package).
 * Replicates linit.js's luaL_openlibs but without the Node.js-dependent libs.
 */
function openSafeLibs(L: any): void {
  const libs: [string, any][] = [
    ["_G", luaopen_base],
    ["coroutine", luaopen_coroutine],
    ["table", luaopen_table],
    ["string", luaopen_string],
    ["utf8", luaopen_utf8],
    ["math", luaopen_math],
  ];
  for (const [name, opener] of libs) {
    luaL_requiref(L, to_luastring(name), opener, 1);
    lua.lua_pop(L, 1);
  }
}

// Pre-encoded field name cache to avoid repeated to_luastring calls
let fieldNames: Record<string, Uint8Array> | null = null;

function getFieldNames(): Record<string, Uint8Array> {
  if (fieldNames) return fieldNames;
  fieldNames = {
    bands: to_luastring("bands"),
    amplitude: to_luastring("amplitude"),
    peak: to_luastring("peak"),
    is_beat: to_luastring("is_beat"),
    beat: to_luastring("beat"),
    beat_intensity: to_luastring("beat_intensity"),
    beat_phase: to_luastring("beat_phase"),
    bpm: to_luastring("bpm"),
    frame: to_luastring("frame"),
    entity_count: to_luastring("entity_count"),
    zone_size: to_luastring("zone_size"),
    beat_boost: to_luastring("beat_boost"),
    base_scale: to_luastring("base_scale"),
    max_scale: to_luastring("max_scale"),
    id: to_luastring("id"),
    x: to_luastring("x"),
    y: to_luastring("y"),
    z: to_luastring("z"),
    scale: to_luastring("scale"),
    band: to_luastring("band"),
    visible: to_luastring("visible"),
    calculate: to_luastring("calculate"),
  };
  return fieldNames;
}

export class LuaPatternInstance {
  private L: any = null;
  private _calculateRef: number = -1;
  readonly config: PatternConfig;
  private _ready = false;

  constructor(
    libSource: string,
    patternSource: string,
    config?: Partial<PatternConfig>,
  ) {
    this.config = { ...DEFAULT_CONFIG, ...config };

    if (!fengariReady) return;
    this._init(libSource, patternSource);
  }

  private _init(libSource: string, patternSource: string): void {
    try {
      const L = lauxlib.luaL_newstate();
      openSafeLibs(L);
      this.L = L;

      // Execute lib.lua
      const libResult = lauxlib.luaL_dostring(L, to_luastring(libSource));
      if (libResult !== lua.LUA_OK) {
        const err = this._popString();
        console.error("Lua lib.lua error:", err);
        return;
      }

      // Execute pattern source
      const patResult = lauxlib.luaL_dostring(L, to_luastring(patternSource));
      if (patResult !== lua.LUA_OK) {
        const err = this._popString();
        console.error("Lua pattern load error:", err);
        return;
      }

      // Get reference to calculate function
      const f = getFieldNames();
      lua.lua_getglobal(L, f.calculate);
      if (lua.lua_type(L, -1) !== lua.LUA_TFUNCTION) {
        console.error("Lua pattern missing calculate() function");
        lua.lua_pop(L, 1);
        return;
      }
      this._calculateRef = lauxlib.luaL_ref(L, lua.LUA_REGISTRYINDEX);
      this._ready = true;
    } catch (e) {
      console.error("LuaPattern init error:", e);
    }
  }

  private _popString(): string {
    if (!this.L) return "";
    const idx = lua.lua_gettop(this.L);
    if (idx < 1) return "";
    const t = lua.lua_type(this.L, -1);
    if (t === lua.LUA_TSTRING) {
      const raw = lua.lua_tostring(this.L, -1);
      lua.lua_pop(this.L, 1);
      if (raw instanceof Uint8Array) {
        return new TextDecoder().decode(raw);
      }
      return String(raw);
    }
    lua.lua_pop(this.L, 1);
    return "(non-string error)";
  }

  get ready(): boolean {
    return this._ready;
  }

  update(_: number = 0.016): void {
    // Lua patterns manage their own time via dt parameter to calculate()
  }

  calculateEntities(audio: AudioState, dt: number = 0.016): EntityData[] {
    if (!this._ready || !this.L) return [];

    const L = this.L;
    const f = getFieldNames();

    try {
      // Push calculate function from registry
      lua.lua_rawgeti(L, lua.LUA_REGISTRYINDEX, this._calculateRef);

      // Push audio table
      lua.lua_createtable(L, 0, 9);

      // audio.bands (1-indexed Lua table)
      lua.lua_pushstring(L, f.bands);
      lua.lua_createtable(L, 5, 0);
      for (let i = 0; i < audio.bands.length; i++) {
        lua.lua_pushnumber(L, audio.bands[i]);
        lua.lua_rawseti(L, -2, i + 1);
      }
      lua.lua_settable(L, -3);

      // audio.amplitude
      lua.lua_pushstring(L, f.amplitude);
      lua.lua_pushnumber(L, audio.amplitude);
      lua.lua_settable(L, -3);

      // audio.peak (alias)
      lua.lua_pushstring(L, f.peak);
      lua.lua_pushnumber(L, audio.amplitude);
      lua.lua_settable(L, -3);

      // audio.is_beat
      lua.lua_pushstring(L, f.is_beat);
      lua.lua_pushboolean(L, audio.isBeat);
      lua.lua_settable(L, -3);

      // audio.beat (alias)
      lua.lua_pushstring(L, f.beat);
      lua.lua_pushboolean(L, audio.isBeat);
      lua.lua_settable(L, -3);

      // audio.beat_intensity
      lua.lua_pushstring(L, f.beat_intensity);
      lua.lua_pushnumber(L, audio.beatIntensity);
      lua.lua_settable(L, -3);

      // audio.beat_phase
      lua.lua_pushstring(L, f.beat_phase);
      lua.lua_pushnumber(L, audio.beatPhase);
      lua.lua_settable(L, -3);

      // audio.bpm
      lua.lua_pushstring(L, f.bpm);
      lua.lua_pushnumber(L, audio.bpm);
      lua.lua_settable(L, -3);

      // audio.frame
      lua.lua_pushstring(L, f.frame);
      lua.lua_pushnumber(L, audio.frame);
      lua.lua_settable(L, -3);

      // Push config table
      lua.lua_createtable(L, 0, 5);

      lua.lua_pushstring(L, f.entity_count);
      lua.lua_pushnumber(L, this.config.entityCount);
      lua.lua_settable(L, -3);

      lua.lua_pushstring(L, f.zone_size);
      lua.lua_pushnumber(L, this.config.zoneSize);
      lua.lua_settable(L, -3);

      lua.lua_pushstring(L, f.beat_boost);
      lua.lua_pushnumber(L, this.config.beatBoost);
      lua.lua_settable(L, -3);

      lua.lua_pushstring(L, f.base_scale);
      lua.lua_pushnumber(L, this.config.baseScale);
      lua.lua_settable(L, -3);

      lua.lua_pushstring(L, f.max_scale);
      lua.lua_pushnumber(L, this.config.maxScale);
      lua.lua_settable(L, -3);

      // Push dt (actual delta time from animation loop)
      lua.lua_pushnumber(L, dt);

      // Call calculate(audio, config, dt)
      const status = lua.lua_pcall(L, 3, 1, 0);
      if (status !== lua.LUA_OK) {
        this._popString(); // discard error
        return [];
      }

      // Read result table
      const entities = this._readEntities(L, f);
      lua.lua_pop(L, 1);

      return entities;
    } catch {
      // Reset Lua stack on error
      lua.lua_settop(L, 0);
      return [];
    }
  }

  private _readEntities(L: any, f: Record<string, Uint8Array>): EntityData[] {
    const entities: EntityData[] = [];

    if (lua.lua_type(L, -1) !== lua.LUA_TTABLE) return entities;

    const len = lua.lua_rawlen(L, -1);

    for (let i = 1; i <= len; i++) {
      lua.lua_rawgeti(L, -1, i);

      if (lua.lua_type(L, -1) === lua.LUA_TTABLE) {
        // Read id field
        lua.lua_getfield(L, -1, f.id);
        let id = `block_${i - 1}`;
        if (lua.lua_type(L, -1) === lua.LUA_TSTRING) {
          const raw = lua.lua_tostring(L, -1);
          if (raw instanceof Uint8Array) {
            id = new TextDecoder().decode(raw);
          } else {
            id = String(raw);
          }
        }
        lua.lua_pop(L, 1);

        // Read numeric fields with defaults
        const x = this._numField(L, f.x) ?? 0.5;
        const y = this._numField(L, f.y) ?? 0.5;
        const z = this._numField(L, f.z) ?? 0.5;
        const scale = this._numField(L, f.scale) ?? 0.2;
        const band = this._numField(L, f.band) ?? 0;

        // Read visible field
        lua.lua_getfield(L, -1, f.visible);
        const visible = lua.lua_type(L, -1) === lua.LUA_TNIL
          ? true
          : lua.lua_toboolean(L, -1);
        lua.lua_pop(L, 1);

        entities.push({ id, x, y, z, scale, band, visible });
      }

      lua.lua_pop(L, 1);
    }

    return entities;
  }

  private _numField(L: any, key: Uint8Array): number | undefined {
    lua.lua_getfield(L, -1, key);
    const val = lua.lua_type(L, -1) === lua.LUA_TNUMBER
      ? lua.lua_tonumber(L, -1)
      : undefined;
    lua.lua_pop(L, 1);
    return val;
  }

  dispose(): void {
    if (this.L) {
      if (this._calculateRef >= 0) {
        lauxlib.luaL_unref(this.L, lua.LUA_REGISTRYINDEX, this._calculateRef);
      }
      lua.lua_close(this.L);
      this.L = null;
      this._ready = false;
    }
  }
}
