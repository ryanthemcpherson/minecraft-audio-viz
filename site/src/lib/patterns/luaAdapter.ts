/**
 * Lua Pattern Adapter
 * Executes Lua pattern scripts in the browser via fengari.
 *
 * We import from fengari's individual source files to avoid pulling in
 * loslib.js / ldblib.js which depend on Node.js modules (child_process, fs).
 * Only safe standard libraries (base, math, string, table, coroutine, utf8)
 * are opened.
 */

/* eslint-disable @typescript-eslint/no-explicit-any */

import type { EntityData, AudioState, PatternConfig } from "./base";
import { DEFAULT_CONFIG } from "./base";

// Lazy-loaded fengari modules
let fengariReady = false;
let lua: any;
let lauxlib: any;
let to_luastring: (s: string) => Uint8Array;

// Individual library openers (safe, no Node.js deps)
let luaopen_base: any;
let luaopen_math: any;
let luaopen_string: any;
let luaopen_table: any;
let luaopen_coroutine: any;
let luaopen_utf8: any;
let luaL_requiref: any;

let loadPromise: Promise<void> | null = null;

export function ensureFengari(): Promise<void> {
  if (fengariReady) return Promise.resolve();
  if (loadPromise) return loadPromise;

  loadPromise = Promise.all([
    import(/* webpackIgnore: true */ "fengari/src/fengaricore.js"),
    import(/* webpackIgnore: true */ "fengari/src/lua.js"),
    import(/* webpackIgnore: true */ "fengari/src/lauxlib.js"),
    import(/* webpackIgnore: true */ "fengari/src/lbaselib.js"),
    import(/* webpackIgnore: true */ "fengari/src/lmathlib.js"),
    import(/* webpackIgnore: true */ "fengari/src/lstrlib.js"),
    import(/* webpackIgnore: true */ "fengari/src/ltablib.js"),
    import(/* webpackIgnore: true */ "fengari/src/lcorolib.js"),
    import(/* webpackIgnore: true */ "fengari/src/lutf8lib.js"),
  ]).then(([core, luaMod, lauxMod, baseMod, mathMod, strMod, tabMod, coroMod, utf8Mod]) => {
    to_luastring = core.to_luastring;
    lua = luaMod;
    lauxlib = lauxMod;
    luaL_requiref = lauxMod.luaL_requiref;
    luaopen_base = baseMod.luaopen_base;
    luaopen_math = mathMod.luaopen_math;
    luaopen_string = strMod.luaopen_string;
    luaopen_table = tabMod.luaopen_table;
    luaopen_coroutine = coroMod.luaopen_coroutine;
    luaopen_utf8 = utf8Mod.luaopen_utf8;
    fengariReady = true;
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
      this._calculateRef = lauxlib.luaL_ref(L, lauxlib.LUA_REGISTRYINDEX);
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

  update(_dt: number = 0.016): void {
    // Lua patterns manage their own time via dt parameter to calculate()
  }

  calculateEntities(audio: AudioState): EntityData[] {
    if (!this._ready || !this.L) return [];

    const L = this.L;
    const f = getFieldNames();

    try {
      // Push calculate function from registry
      lua.lua_rawgeti(L, lauxlib.LUA_REGISTRYINDEX, this._calculateRef);

      // Push audio table
      lua.lua_createtable(L, 0, 7);

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

      // Push dt
      lua.lua_pushnumber(L, 0.016);

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
        lauxlib.luaL_unref(this.L, lauxlib.LUA_REGISTRYINDEX, this._calculateRef);
      }
      lua.lua_close(this.L);
      this.L = null;
      this._ready = false;
    }
  }
}
