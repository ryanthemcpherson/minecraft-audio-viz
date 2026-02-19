// Browser shim for Node.js `fs` module.
// Fengari's lauxlib.js requires fs at module evaluation time in the
// Node.js code path. This stub prevents the import from throwing.
// File I/O functions won't work, but we only use luaL_dostring (in-memory).
module.exports = {};
