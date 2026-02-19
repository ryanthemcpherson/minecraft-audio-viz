/**
 * Pre-bundle fengari for browser use via esbuild.
 *
 * Stubs all Node.js built-ins and forces browser code paths by replacing
 * `typeof process` checks. This makes the bundle safe for Turbopack/browser.
 */
import { build } from "esbuild";
import { fileURLToPath } from "url";
import path from "path";
import fs from "fs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outfile = path.resolve(__dirname, "../public/fengari-browser.js");

/** Plugin that stubs Node.js modules fengari tries to require */
const nodeStubPlugin = {
  name: "node-stub",
  setup(b) {
    const stubs = [
      "os", "fs", "path", "child_process", "readline-sync",
      "crypto", "buffer", "stream", "util", "net", "tty",
      "tmp", "process",
    ];
    const filter = new RegExp(`^(${stubs.join("|")})$`);
    b.onResolve({ filter }, (args) => ({
      path: args.path,
      namespace: "node-stub",
    }));
    b.onLoad({ filter: /.*/, namespace: "node-stub" }, (args) => {
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

/**
 * Plugin that forces browser code paths in fengari sources.
 * Replaces `typeof process` with `"undefined"` so all Node.js
 * detection checks resolve to the browser branch.
 */
const forceBrowserPlugin = {
  name: "force-browser",
  setup(b) {
    b.onLoad({ filter: /node_modules[\\/]fengari[\\/]/ }, async (args) => {
      let contents = await fs.promises.readFile(args.path, "utf8");
      // Replace typeof process checks to force browser paths
      contents = contents.replace(/typeof\s+process/g, '"undefined"');
      return { contents, loader: "js" };
    });
  },
};

// Minimal process shim injected at the top of the IIFE so that fengari's
// direct process.env / process.stdin / process.stdout references don't throw.
const processShim = `var process = {
  env: {}, stdin: { fd: 0 }, stdout: { write: function() {} },
  stderr: { write: function() {} }, cwd: function() { return "/"; },
  uptime: function() { return 0; }, exit: function() {},
};`;

await build({
  entryPoints: [path.resolve(__dirname, "fengari-entry.mjs")],
  bundle: true,
  format: "iife",
  globalName: "__fengari",
  outfile,
  platform: "browser",
  target: "es2020",
  plugins: [forceBrowserPlugin, nodeStubPlugin],
  banner: { js: processShim },
  // Don't minify — easier to debug; size is small anyway
  minify: false,
  logLevel: "info",
});

console.log(`✓ fengari-browser.js written to ${outfile}`);
