// Browser shim for Node.js `os` module.
// Fengari's luaconf.js requires os.platform() at module evaluation time
// to choose between browser/win32/unix code paths. Returning 'linux'
// ensures it takes the Unix path (the safest fallback in a browser).
module.exports = {
  platform: function() { return 'linux'; },
  arch: function() { return 'x64'; },
  endianness: function() { return 'LE'; },
};
