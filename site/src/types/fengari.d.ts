// Type declarations for fengari (Lua VM compiled to JS)
// Fengari has no official TypeScript types; these are hand-written based on usage.

/** Opaque Lua state handle — do not construct directly. */
type LuaState = { readonly __brand: "LuaState" };

/** C function callable from Lua. Returns the number of results pushed onto the stack. */
type LuaCFunction = (L: LuaState) => number;

// Individual fengari source modules (avoids lualib.js which pulls in Node.js deps)

declare module "fengari/src/fengaricore.js" {
  export function to_luastring(s: string): Uint8Array;
  export function to_jsstring(s: Uint8Array): string;
}

declare module "fengari/src/lua.js" {
  export function lua_close(L: LuaState): void;
  export function lua_pcall(L: LuaState, nargs: number, nresults: number, errfunc: number): number;
  export function lua_pop(L: LuaState, n: number): void;
  export function lua_gettop(L: LuaState): number;
  export function lua_settop(L: LuaState, idx: number): void;
  export function lua_getglobal(L: LuaState, name: Uint8Array): number;
  export function lua_createtable(L: LuaState, narr: number, nrec: number): void;
  export function lua_settable(L: LuaState, idx: number): void;
  export function lua_rawseti(L: LuaState, idx: number, n: number): void;
  export function lua_rawgeti(L: LuaState, idx: number, n: number): number;
  export function lua_rawlen(L: LuaState, idx: number): number;
  export function lua_pushnumber(L: LuaState, n: number): void;
  export function lua_pushboolean(L: LuaState, b: boolean): void;
  export function lua_pushstring(L: LuaState, s: Uint8Array): void;
  export function lua_tonumber(L: LuaState, idx: number): number;
  export function lua_tostring(L: LuaState, idx: number): Uint8Array | string;
  export function lua_toboolean(L: LuaState, idx: number): boolean;
  export function lua_type(L: LuaState, idx: number): number;
  export function lua_getfield(L: LuaState, idx: number, k: Uint8Array): number;

  export const LUA_OK: number;
  export const LUA_TNUMBER: number;
  export const LUA_TBOOLEAN: number;
  export const LUA_TSTRING: number;
  export const LUA_TTABLE: number;
  export const LUA_TNIL: number;
  export const LUA_TFUNCTION: number;
  export const LUA_REGISTRYINDEX: number;
}

declare module "fengari/src/lauxlib.js" {
  export function luaL_newstate(): LuaState;
  export function luaL_dostring(L: LuaState, s: Uint8Array): number;
  export function luaL_ref(L: LuaState, idx: number): number;
  export function luaL_unref(L: LuaState, idx: number, ref: number): void;
  export function luaL_requiref(L: LuaState, name: Uint8Array, func: LuaCFunction, global: number): void;

  export const LUA_REGISTRYINDEX: number;
}

declare module "fengari/src/lbaselib.js" {
  export function luaopen_base(L: LuaState): number;
}

declare module "fengari/src/lmathlib.js" {
  export function luaopen_math(L: LuaState): number;
}

declare module "fengari/src/lstrlib.js" {
  export function luaopen_string(L: LuaState): number;
}

declare module "fengari/src/ltablib.js" {
  export function luaopen_table(L: LuaState): number;
}

declare module "fengari/src/lcorolib.js" {
  export function luaopen_coroutine(L: LuaState): number;
}

declare module "fengari/src/lutf8lib.js" {
  export function luaopen_utf8(L: LuaState): number;
}
