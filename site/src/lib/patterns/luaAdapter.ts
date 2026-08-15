/**
 * Lua Pattern Adapter
 * Executes Lua pattern scripts in the browser via fengari.
 *
 * Fengari is loaded as an IIFE from public/fengari-browser.js (built by
 * scripts/bundle-fengari.mjs). This bypasses Turbopack's module transformation
 * which mangles fengari's internal Lua compiler state.
 */

import type { EntityData, AudioState, PatternConfig } from "./base";
import { DEFAULT_CONFIG } from "./base";

// --- Branded opaque type matching fengari.d.ts ---
type LuaState = { readonly __brand: "LuaState" };
type LuaCFunction = (L: LuaState) => number;

// --- Interfaces for the fengari runtime API loaded from the global IIFE ---

interface FengariLuaApi {
  lua_close(L: LuaState): void;
  lua_pcall(L: LuaState, nargs: number, nresults: number, errfunc: number): number;
  lua_pop(L: LuaState, n: number): void;
  lua_gettop(L: LuaState): number;
  lua_settop(L: LuaState, idx: number): void;
  lua_getglobal(L: LuaState, name: Uint8Array): number;
  lua_createtable(L: LuaState, narr: number, nrec: number): void;
  lua_settable(L: LuaState, idx: number): void;
  lua_rawseti(L: LuaState, idx: number, n: number): void;
  lua_rawgeti(L: LuaState, idx: number, n: number): number;
  lua_rawlen(L: LuaState, idx: number): number;
  lua_pushnumber(L: LuaState, n: number): void;
  lua_pushboolean(L: LuaState, b: boolean): void;
  lua_pushstring(L: LuaState, s: Uint8Array): void;
  lua_tonumber(L: LuaState, idx: number): number;
  lua_tostring(L: LuaState, idx: number): Uint8Array | string;
  lua_toboolean(L: LuaState, idx: number): boolean;
  lua_type(L: LuaState, idx: number): number;
  lua_getfield(L: LuaState, idx: number, k: Uint8Array): number;
  readonly LUA_OK: number;
  readonly LUA_TNUMBER: number;
  readonly LUA_TBOOLEAN: number;
  readonly LUA_TSTRING: number;
  readonly LUA_TTABLE: number;
  readonly LUA_TNIL: number;
  readonly LUA_TFUNCTION: number;
  readonly LUA_REGISTRYINDEX: number;
}

interface FengariAuxlibApi {
  luaL_newstate(): LuaState;
  luaL_dostring(L: LuaState, s: Uint8Array): number;
  luaL_ref(L: LuaState, idx: number): number;
  luaL_unref(L: LuaState, idx: number, ref: number): void;
  luaL_requiref(L: LuaState, name: Uint8Array, func: LuaCFunction, global: number): void;
  LUA_REGISTRYINDEX: number;
}

interface FengariGlobal {
  lua: FengariLuaApi;
  lauxlib: FengariAuxlibApi;
  to_luastring: (s: string) => Uint8Array;
  luaopen_base: LuaCFunction;
  luaopen_math: LuaCFunction;
  luaopen_string: LuaCFunction;
  luaopen_table: LuaCFunction;
  luaopen_coroutine: LuaCFunction;
  luaopen_utf8: LuaCFunction;
}

// --- Fengari globals (populated by script load) ---
let lua: FengariLuaApi | null = null;
let lauxlib: FengariAuxlibApi | null = null;
let to_luastring: (s: string) => Uint8Array;
let luaopen_base: LuaCFunction | null = null;
let luaopen_math: LuaCFunction | null = null;
let luaopen_string: LuaCFunction | null = null;
let luaopen_table: LuaCFunction | null = null;
let luaopen_coroutine: LuaCFunction | null = null;
let luaopen_utf8: LuaCFunction | null = null;
let luaL_requiref: FengariAuxlibApi["luaL_requiref"] | null = null;

let fengariReady = false;
let loadPromise: Promise<void> | null = null;

function bindGlobals(): void {
  const f = (window as unknown as { __fengari: FengariGlobal }).__fengari;
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
  if ((window as unknown as { __fengari?: FengariGlobal }).__fengari) {
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
      } catch (e: unknown) {
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
function openSafeLibs(L: LuaState): void {
  const libs: [string, LuaCFunction][] = [
    ["_G", luaopen_base!],
    ["coroutine", luaopen_coroutine!],
    ["table", luaopen_table!],
    ["string", luaopen_string!],
    ["utf8", luaopen_utf8!],
    ["math", luaopen_math!],
  ];
  for (const [name, opener] of libs) {
    luaL_requiref!(L, to_luastring(name), opener, 1);
    lua!.lua_pop(L, 1);
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
  private L: LuaState | null = null;
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
      const L = lauxlib!.luaL_newstate();
      openSafeLibs(L);
      this.L = L;

      // Execute lib.lua
      const libResult = lauxlib!.luaL_dostring(L, to_luastring(libSource));
      if (libResult !== lua!.LUA_OK) {
        const err = this._popString();
        console.error("Lua lib.lua error:", err);
        return;
      }

      // Execute pattern source
      const patResult = lauxlib!.luaL_dostring(L, to_luastring(patternSource));
      if (patResult !== lua!.LUA_OK) {
        const err = this._popString();
        console.error("Lua pattern load error:", err);
        return;
      }

      // Get reference to calculate function
      const f = getFieldNames();
      lua!.lua_getglobal(L, f.calculate);
      if (lua!.lua_type(L, -1) !== lua!.LUA_TFUNCTION) {
        console.error("Lua pattern missing calculate() function");
        lua!.lua_pop(L, 1);
        return;
      }
      this._calculateRef = lauxlib!.luaL_ref(L, lua!.LUA_REGISTRYINDEX);
      this._ready = true;
    } catch (e: unknown) {
      console.error("LuaPattern init error:", e);
    }
  }

  private _popString(): string {
    if (!this.L) return "";
    const idx = lua!.lua_gettop(this.L);
    if (idx < 1) return "";
    const t = lua!.lua_type(this.L, -1);
    if (t === lua!.LUA_TSTRING) {
      const raw = lua!.lua_tostring(this.L, -1);
      lua!.lua_pop(this.L, 1);
      if (raw instanceof Uint8Array) {
        return new TextDecoder().decode(raw);
      }
      return String(raw);
    }
    lua!.lua_pop(this.L, 1);
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
      lua!.lua_rawgeti(L, lua!.LUA_REGISTRYINDEX, this._calculateRef);

      // Push audio table
      lua!.lua_createtable(L, 0, 9);

      // audio.bands (1-indexed Lua table)
      lua!.lua_pushstring(L, f.bands);
      lua!.lua_createtable(L, 5, 0);
      for (let i = 0; i < audio.bands.length; i++) {
        lua!.lua_pushnumber(L, audio.bands[i]);
        lua!.lua_rawseti(L, -2, i + 1);
      }
      lua!.lua_settable(L, -3);

      // audio.amplitude
      lua!.lua_pushstring(L, f.amplitude);
      lua!.lua_pushnumber(L, audio.amplitude);
      lua!.lua_settable(L, -3);

      // audio.peak (alias)
      lua!.lua_pushstring(L, f.peak);
      lua!.lua_pushnumber(L, audio.amplitude);
      lua!.lua_settable(L, -3);

      // audio.is_beat
      lua!.lua_pushstring(L, f.is_beat);
      lua!.lua_pushboolean(L, audio.isBeat);
      lua!.lua_settable(L, -3);

      // audio.beat (alias)
      lua!.lua_pushstring(L, f.beat);
      lua!.lua_pushboolean(L, audio.isBeat);
      lua!.lua_settable(L, -3);

      // audio.beat_intensity
      lua!.lua_pushstring(L, f.beat_intensity);
      lua!.lua_pushnumber(L, audio.beatIntensity);
      lua!.lua_settable(L, -3);

      // audio.beat_phase
      lua!.lua_pushstring(L, f.beat_phase);
      lua!.lua_pushnumber(L, audio.beatPhase);
      lua!.lua_settable(L, -3);

      // audio.bpm
      lua!.lua_pushstring(L, f.bpm);
      lua!.lua_pushnumber(L, audio.bpm);
      lua!.lua_settable(L, -3);

      // audio.frame
      lua!.lua_pushstring(L, f.frame);
      lua!.lua_pushnumber(L, audio.frame);
      lua!.lua_settable(L, -3);

      // Push config table
      lua!.lua_createtable(L, 0, 5);

      lua!.lua_pushstring(L, f.entity_count);
      lua!.lua_pushnumber(L, this.config.entityCount);
      lua!.lua_settable(L, -3);

      lua!.lua_pushstring(L, f.zone_size);
      lua!.lua_pushnumber(L, this.config.zoneSize);
      lua!.lua_settable(L, -3);

      lua!.lua_pushstring(L, f.beat_boost);
      lua!.lua_pushnumber(L, this.config.beatBoost);
      lua!.lua_settable(L, -3);

      lua!.lua_pushstring(L, f.base_scale);
      lua!.lua_pushnumber(L, this.config.baseScale);
      lua!.lua_settable(L, -3);

      lua!.lua_pushstring(L, f.max_scale);
      lua!.lua_pushnumber(L, this.config.maxScale);
      lua!.lua_settable(L, -3);

      // Push dt (actual delta time from animation loop)
      lua!.lua_pushnumber(L, dt);

      // Call calculate(audio, config, dt)
      const status = lua!.lua_pcall(L, 3, 1, 0);
      if (status !== lua!.LUA_OK) {
        this._popString(); // discard error
        return [];
      }

      // Read result table
      const entities = this._readEntities(L, f);
      lua!.lua_pop(L, 1);

      return entities;
    } catch {
      // Reset Lua stack on error
      lua!.lua_settop(L, 0);
      return [];
    }
  }

  private _readEntities(L: LuaState, f: Record<string, Uint8Array>): EntityData[] {
    const entities: EntityData[] = [];

    if (lua!.lua_type(L, -1) !== lua!.LUA_TTABLE) return entities;

    const len = lua!.lua_rawlen(L, -1);

    for (let i = 1; i <= len; i++) {
      lua!.lua_rawgeti(L, -1, i);

      if (lua!.lua_type(L, -1) === lua!.LUA_TTABLE) {
        // Read id field
        lua!.lua_getfield(L, -1, f.id);
        let id = `block_${i - 1}`;
        if (lua!.lua_type(L, -1) === lua!.LUA_TSTRING) {
          const raw = lua!.lua_tostring(L, -1);
          if (raw instanceof Uint8Array) {
            id = new TextDecoder().decode(raw);
          } else {
            id = String(raw);
          }
        }
        lua!.lua_pop(L, 1);

        // Read numeric fields with defaults
        const x = this._numField(L, f.x) ?? 0.5;
        const y = this._numField(L, f.y) ?? 0.5;
        const z = this._numField(L, f.z) ?? 0.5;
        const scale = this._numField(L, f.scale) ?? 0.2;
        const band = this._numField(L, f.band) ?? 0;

        // Read visible field
        lua!.lua_getfield(L, -1, f.visible);
        const visible = lua!.lua_type(L, -1) === lua!.LUA_TNIL
          ? true
          : lua!.lua_toboolean(L, -1);
        lua!.lua_pop(L, 1);

        entities.push({ id, x, y, z, scale, band, visible });
      }

      lua!.lua_pop(L, 1);
    }

    return entities;
  }

  private _numField(L: LuaState, key: Uint8Array): number | undefined {
    lua!.lua_getfield(L, -1, key);
    const val = lua!.lua_type(L, -1) === lua!.LUA_TNUMBER
      ? lua!.lua_tonumber(L, -1)
      : undefined;
    lua!.lua_pop(L, 1);
    return val;
  }

  dispose(): void {
    if (this.L) {
      if (this._calculateRef >= 0) {
        lauxlib!.luaL_unref(this.L, lua!.LUA_REGISTRYINDEX, this._calculateRef);
      }
      lua!.lua_close(this.L);
      this.L = null;
      this._ready = false;
    }
  }
}
