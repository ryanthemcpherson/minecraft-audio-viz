/**
 * Pre-bundle fengari for browser use via esbuild.
 *
 * Stubs all Node.js built-ins (os, fs, path, child_process, readline-sync)
 * so the bundle can run in the browser without Turbopack trying to resolve them.
 */
import { build } from "esbuild";
import { fileURLToPath } from "url";
import path from "path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outfile = path.resolve(__dirname, "../src/lib/patterns/fengari-browser.js");

/** Plugin that stubs Node.js modules fengari tries to require */
const nodeStubPlugin = {
  name: "node-stub",
  setup(build) {
    const stubs = [
      "os", "fs", "path", "child_process", "readline-sync",
      "crypto", "buffer", "stream", "util", "net", "tty",
    ];
    const filter = new RegExp(`^(${stubs.join("|")})$`);
    build.onResolve({ filter }, (args) => ({
      path: args.path,
      namespace: "node-stub",
    }));
    build.onLoad({ filter: /.*/, namespace: "node-stub" }, (args) => {
      // os needs platform() for fengari's luaconf.js
      if (args.path === "os") {
        return {
          contents: `module.exports = {
            platform: function() { return "linux"; },
            arch: function() { return "x64"; },
            endianness: function() { return "LE"; },
            tmpdir: function() { return "/tmp"; },
          };`,
          loader: "js",
        };
      }
      // Everything else gets an empty stub
      return { contents: "module.exports = {};", loader: "js" };
    });
  },
};

await build({
  entryPoints: [path.resolve(__dirname, "fengari-entry.cjs")],
  bundle: true,
  format: "esm",
  outfile,
  platform: "browser",
  target: "es2020",
  plugins: [nodeStubPlugin],
  // Don't minify — easier to debug; size is small anyway
  minify: false,
  logLevel: "info",
});

console.log(`✓ fengari-browser.js written to ${outfile}`);
