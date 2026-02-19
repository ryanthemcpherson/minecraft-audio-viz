/**
 * Entry point for esbuild pre-bundling of fengari.
 *
 * We require only the safe modules (no os/debug/io/package libs)
 * and re-export them as a flat namespace. esbuild stubs Node.js
 * built-ins so the resulting bundle is fully browser-safe.
 */
const core = require("fengari/src/fengaricore.js");
const lua = require("fengari/src/lua.js");
const lauxlib = require("fengari/src/lauxlib.js");
const lbaselib = require("fengari/src/lbaselib.js");
const lmathlib = require("fengari/src/lmathlib.js");
const lstrlib = require("fengari/src/lstrlib.js");
const ltablib = require("fengari/src/ltablib.js");
const lcorolib = require("fengari/src/lcorolib.js");
const lutf8lib = require("fengari/src/lutf8lib.js");

exports.to_luastring = core.to_luastring;
exports.lua = lua;
exports.lauxlib = lauxlib;
exports.luaopen_base = lbaselib.luaopen_base;
exports.luaopen_math = lmathlib.luaopen_math;
exports.luaopen_string = lstrlib.luaopen_string;
exports.luaopen_table = ltablib.luaopen_table;
exports.luaopen_coroutine = lcorolib.luaopen_coroutine;
exports.luaopen_utf8 = lutf8lib.luaopen_utf8;
