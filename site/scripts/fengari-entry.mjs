/**
 * ESM entry point for esbuild pre-bundling of fengari.
 *
 * We import only the safe modules (no os/debug/io/package libs)
 * and re-export them as named exports. esbuild stubs Node.js
 * built-ins so the resulting bundle is fully browser-safe.
 */
import core from "fengari/src/fengaricore.js";
import lua from "fengari/src/lua.js";
import lauxlib from "fengari/src/lauxlib.js";
import lbaselib from "fengari/src/lbaselib.js";
import lmathlib from "fengari/src/lmathlib.js";
import lstrlib from "fengari/src/lstrlib.js";
import ltablib from "fengari/src/ltablib.js";
import lcorolib from "fengari/src/lcorolib.js";
import lutf8lib from "fengari/src/lutf8lib.js";

export const to_luastring = core.to_luastring;
export { lua, lauxlib };
export const luaopen_base = lbaselib.luaopen_base;
export const luaopen_math = lmathlib.luaopen_math;
export const luaopen_string = lstrlib.luaopen_string;
export const luaopen_table = ltablib.luaopen_table;
export const luaopen_coroutine = lcorolib.luaopen_coroutine;
export const luaopen_utf8 = lutf8lib.luaopen_utf8;
