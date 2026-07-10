import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(testDirectory, "..");
const read = (relativePath) => fs.readFileSync(path.join(clientRoot, relativePath), "utf8");

/**
 * @param {unknown} permission
 * @returns {boolean}
 */
const isUpdaterPermission = (permission) => {
  if (typeof permission === "string") {
    return permission.startsWith("updater:");
  }

  return (
    typeof permission === "object" &&
    permission !== null &&
    "identifier" in permission &&
    typeof permission.identifier === "string" &&
    permission.identifier.startsWith("updater:")
  );
};

test("updater permission detection handles strings and scoped objects", () => {
  assert.equal(isUpdaterPermission("updater:default"), true);
  assert.equal(isUpdaterPermission({ identifier: "updater:allow-check", allow: [] }), true);
  assert.equal(isUpdaterPermission({ identifier: "shell:allow-open", allow: [] }), false);
  assert.equal(isUpdaterPermission(null), false);
});

test("legacy updater is absent from the active runtime", () => {
  const packageJson = JSON.parse(read("package.json"));
  const packageLock = read("package-lock.json");
  const cargoToml = read("src-tauri/Cargo.toml");
  const cargoLock = read("src-tauri/Cargo.lock");
  const tauriConfig = JSON.parse(read("src-tauri/tauri.conf.json"));
  const capabilities = JSON.parse(read("src-tauri/capabilities/default.json"));
  const rustRuntime = read("src-tauri/src/lib.rs");
  const app = read("src/App.tsx");
  const appStyles = read("src/styles/app.css");
  const updaterTombstone = read("src/hooks/useAutoUpdate.ts");
  const generatedSchemaPaths = [
    "src-tauri/gen/schemas/acl-manifests.json",
    "src-tauri/gen/schemas/capabilities.json",
    "src-tauri/gen/schemas/desktop-schema.json",
    "src-tauri/gen/schemas/windows-schema.json",
  ];

  assert.equal(packageJson.dependencies["@tauri-apps/plugin-updater"], undefined);
  assert.equal(packageJson.scripts["tauri:build:signed"], undefined);
  assert.doesNotMatch(packageLock, /@tauri-apps\/plugin-updater/);
  assert.doesNotMatch(cargoToml, /tauri-plugin-updater/);
  assert.doesNotMatch(cargoLock, /tauri-plugin-updater/);
  assert.equal(tauriConfig.bundle.createUpdaterArtifacts, false);
  assert.equal(tauriConfig.plugins?.updater, undefined);
  assert.equal(capabilities.permissions.some(isUpdaterPermission), false);
  assert.doesNotMatch(rustRuntime, /tauri_plugin_updater/);
  assert.doesNotMatch(app, /useAutoUpdate|update[-\s]+banner|btn-dismiss/i);
  assert.doesNotMatch(appStyles, /update[-\s]+banner|\.btn-dismiss\b/i);

  for (const schemaPath of generatedSchemaPaths) {
    assert.doesNotMatch(read(schemaPath), /\bupdater\b/i, `${schemaPath} contains updater ACL data`);
  }

  assert.match(
    updaterTombstone,
    /export function useAutoUpdate\(\): null \{\s*return null;\s*\}/,
  );
  assert.doesNotMatch(updaterTombstone, /^\s*import\b|\b(?:import|require)\s*\(/m);
  assert.doesNotMatch(updaterTombstone, /\b(?:check|fetch|request|invoke)\s*\(/);
  assert.doesNotMatch(updaterTombstone, /\b(?:setInterval|setTimeout)\s*\(/);
  assert.doesNotMatch(updaterTombstone, /\b(?:downloadAndInstall|download|install)\s*\(/);
});
