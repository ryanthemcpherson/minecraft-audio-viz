import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(testDirectory, "..");
const read = (relativePath) => fs.readFileSync(path.join(clientRoot, relativePath), "utf8");

test("legacy updater is absent from the active runtime", () => {
  const packageJson = JSON.parse(read("package.json"));
  const cargoToml = read("src-tauri/Cargo.toml");
  const tauriConfig = JSON.parse(read("src-tauri/tauri.conf.json"));
  const capabilities = JSON.parse(read("src-tauri/capabilities/default.json"));
  const rustRuntime = read("src-tauri/src/lib.rs");
  const app = read("src/App.tsx");

  assert.equal(packageJson.dependencies["@tauri-apps/plugin-updater"], undefined);
  assert.doesNotMatch(cargoToml, /tauri-plugin-updater/);
  assert.equal(tauriConfig.bundle.createUpdaterArtifacts, false);
  assert.equal(tauriConfig.plugins?.updater, undefined);
  assert.equal(capabilities.permissions.some((value) => value.startsWith("updater:")), false);
  assert.doesNotMatch(rustRuntime, /tauri_plugin_updater/);
  assert.doesNotMatch(app, /useAutoUpdate|update-banner/);
});
