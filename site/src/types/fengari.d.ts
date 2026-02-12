/* eslint-disable @typescript-eslint/no-explicit-any */

// Individual fengari source modules (avoids lualib.js which pulls in Node.js deps)

declare module "fengari/src/fengaricore.js" {
  export function to_luastring(s: string): Uint8Array;
  export function to_jsstring(s: Uint8Array): string;
}

declare module "fengari/src/lua.js" {
  export function lua_close(L: any): void;
  export function lua_pcall(L: any, nargs: number, nresults: number, errfunc: number): number;
  export function lua_pop(L: any, n: number): void;
  export function lua_gettop(L: any): number;
  export function lua_settop(L: any, idx: number): void;
  export function lua_getglobal(L: any, name: Uint8Array): number;
  export function lua_createtable(L: any, narr: number, nrec: number): void;
  export function lua_settable(L: any, idx: number): void;
  export function lua_rawseti(L: any, idx: number, n: number): void;
  export function lua_rawgeti(L: any, idx: number, n: number): number;
  export function lua_rawlen(L: any, idx: number): number;
  export function lua_pushnumber(L: any, n: number): void;
  export function lua_pushboolean(L: any, b: boolean): void;
  export function lua_pushstring(L: any, s: Uint8Array): void;
  export function lua_tonumber(L: any, idx: number): number;
  export function lua_tostring(L: any, idx: number): Uint8Array | string;
  export function lua_toboolean(L: any, idx: number): boolean;
  export function lua_type(L: any, idx: number): number;
  export function lua_getfield(L: any, idx: number, k: Uint8Array): number;

  export const LUA_OK: number;
  export const LUA_TNUMBER: number;
  export const LUA_TBOOLEAN: number;
  export const LUA_TSTRING: number;
  export const LUA_TTABLE: number;
  export const LUA_TNIL: number;
  export const LUA_TFUNCTION: number;
}

declare module "fengari/src/lauxlib.js" {
  export function luaL_newstate(): any;
  export function luaL_dostring(L: any, s: Uint8Array): number;
  export function luaL_ref(L: any, idx: number): number;
  export function luaL_unref(L: any, idx: number, ref: number): void;
  export function luaL_requiref(L: any, name: Uint8Array, func: any, global: number): void;

  export const LUA_REGISTRYINDEX: number;
}

declare module "fengari/src/lbaselib.js" {
  export function luaopen_base(L: any): number;
}

declare module "fengari/src/lmathlib.js" {
  export function luaopen_math(L: any): number;
}

declare module "fengari/src/lstrlib.js" {
  export function luaopen_string(L: any): number;
}

declare module "fengari/src/ltablib.js" {
  export function luaopen_table(L: any): number;
}

declare module "fengari/src/lcorolib.js" {
  export function luaopen_coroutine(L: any): number;
}

declare module "fengari/src/lutf8lib.js" {
  export function luaopen_utf8(L: any): number;
}
