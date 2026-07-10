import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(testDirectory, "..");
const repositoryRoot = path.resolve(clientRoot, "..");
const read = (relativePath) => fs.readFileSync(path.join(clientRoot, relativePath), "utf8");
const readRepositoryFile = (relativePath) =>
  fs.readFileSync(path.join(repositoryRoot, relativePath), "utf8");

const composeEnvironment = {
  ...process.env,
  COMPOSE_PROFILES: "",
  MCAV_USER_JWT_SECRET: "phase0-containment-test",
  POSTGRES_USER: "phase0",
  POSTGRES_PASSWORD: "phase0",
  POSTGRES_DB: "phase0",
};

/**
 * @param {string} composeFile
 * @param {string[]} args
 */
const runCompose = (composeFile, args) => {
  const commandArgs = ["compose", "--ansi", "never", "-f", composeFile, ...args];
  const result = spawnSync("docker", commandArgs, {
    cwd: repositoryRoot,
    encoding: "utf8",
    env: composeEnvironment,
  });

  if (result.error) {
    assert.fail(
      `Unable to execute Docker Compose for ${composeFile}. ` +
        `Install Docker Compose v2 and ensure the Docker daemon is available. ${result.error.message}`,
    );
  }

  return {
    command: `docker ${commandArgs.join(" ")}`,
    status: result.status,
    output: [result.stdout, result.stderr].filter(Boolean).join("\n").trim(),
    stdout: result.stdout,
  };
};

/**
 * @param {string} composeFile
 */
const readComposeModel = (composeFile) => {
  const result = runCompose(composeFile, ["config", "--no-interpolate", "--format", "json"]);
  assert.equal(
    result.status,
    0,
    `${result.command} failed with status ${result.status}:\n${result.output}`,
  );

  try {
    return JSON.parse(result.stdout);
  } catch (error) {
    assert.fail(`${result.command} returned invalid JSON: ${error.message}\n${result.output}`);
  }
};

/**
 * @param {Record<string, {container_name?: string}>} services
 * @param {string} output
 */
const plannedServiceNames = (services, output) =>
  Object.entries(services)
    .filter(([serviceName, service]) => {
      assert.equal(
        typeof service.container_name,
        "string",
        `${serviceName} needs container_name for deterministic dry-run assertions`,
      );
      return output.includes(`Container ${service.container_name} `);
    })
    .map(([serviceName]) => serviceName)
    .sort();

/**
 * @param {string} label
 * @param {Record<string, {container_name?: string}>} services
 * @param {{command: string, status: number | null, output: string}} result
 * @param {string[]} expectedServices
 */
const assertSuccessfulComposePlan = (label, services, result, expectedServices) => {
  assert.equal(
    result.status,
    0,
    `${label} failed (${result.command}, status ${result.status}):\n${result.output}`,
  );
  assert.deepEqual(
    plannedServiceNames(services, result.output),
    [...expectedServices].sort(),
    `${label} selected the wrong services:\n${result.output}`,
  );
};

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Return a YAML block beginning at an exact indentation level.
 *
 * @param {string} source
 * @param {string} key
 * @param {number} indentation
 * @returns {string}
 */
const yamlBlock = (source, key, indentation) => {
  const lines = source.replace(/\r/g, "").split("\n");
  const prefix = " ".repeat(indentation);
  const start = lines.findIndex((line) => line === `${prefix}${key}:`);

  assert.notEqual(start, -1, `Missing YAML block: ${key}`);

  let end = start + 1;
  while (
    end < lines.length &&
    (lines[end].trim() === "" || lines[end].length - lines[end].trimStart().length > indentation)
  ) {
    end += 1;
  }

  return lines.slice(start, end).join("\n");
};

/**
 * @param {string} workflow
 * @param {string} jobName
 * @param {string[]} expectedNeeds
 */
const assertSummaryRequiresEveryNeedToSucceed = (workflow, jobName, expectedNeeds) => {
  const job = yamlBlock(workflow, jobName, 2);
  const needsMatch = job.match(/^\s+needs:\s*\[([^\]]+)]\s*$/m);

  assert.ok(needsMatch, `${jobName} must declare its required jobs inline`);
  assert.match(job, /^\s+if:\s*always\(\)\s*$/m);
  assert.match(job, /\bexit 1\b/);

  const needs = needsMatch[1].split(",").map((need) => need.trim());
  assert.deepEqual(needs, expectedNeeds, `${jobName} must retain every required dependency`);
  for (const need of needs) {
    const resultExpression = escapeRegExp(`\${{ needs.${need}.result }}`);
    assert.match(
      job,
      new RegExp(`"${resultExpression}"\\s*!=\\s*"success"`),
      `${jobName} must reject every non-success result from ${need}`,
    );
  }
};

/**
 * @param {string} workflow
 * @param {string} stepName
 * @returns {string}
 */
const assertJsonAuditStepIsBlocking = (workflow, stepName) => {
  const lines = workflow.replace(/\r/g, "").split("\n");
  const start = lines.findIndex((line) => line === `      - name: ${stepName}`);
  assert.notEqual(start, -1, `Missing workflow step: ${stepName}`);

  let end = start + 1;
  while (end < lines.length && !lines[end].startsWith("      - name: ")) {
    end += 1;
  }

  const step = lines.slice(start, end).join("\n");
  assert.match(step, /\bset \+e\b/);
  assert.match(step, /\baudit_status=\$\?/);
  assert.match(step, /\bset -e\b/);
  assert.match(step, /\bexit "\$audit_status"/);
  return step;
};

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

test("unsupported DJ and Docker distribution workflows are fail closed", () => {
  const dedicatedRelease = readRepositoryFile(".github/workflows/release-dj-client.yml");
  const combinedRelease = readRepositoryFile(".github/workflows/release.yml");
  const djClientCi = readRepositoryFile(".github/workflows/dj-client-ci.yml");
  const dockerWorkflow = readRepositoryFile(".github/workflows/docker.yml");

  assert.equal(
    yamlBlock(dedicatedRelease, "on", 0).trimEnd(),
    "on:\n  workflow_dispatch:",
  );
  assert.match(dedicatedRelease, /MCAV_PHASE0_RELEASE_DISABLED/);
  assert.doesNotMatch(dedicatedRelease, /softprops\/action-gh-release/);
  assert.match(dedicatedRelease, /^permissions:\s*\n\s+contents:\s*read\s*$/m);
  assert.equal(
    (yamlBlock(dedicatedRelease, "jobs", 0).match(/^  [a-z0-9-]+:\s*$/gm) ?? []).length,
    1,
  );
  assert.match(yamlBlock(dedicatedRelease, "release-disabled", 2), /\bexit 1\b/);

  assert.doesNotMatch(combinedRelease, /build-dj-client|DJ Client|dj-client\//i);
  assert.equal((combinedRelease.match(/if-no-files-found:\s*error/g) ?? []).length, 2);
  assert.match(combinedRelease, /fail_on_unmatched_files:\s*true/);
  const releaseGate = yamlBlock(combinedRelease, "ci-gate", 2);
  assert.match(releaseGate, /for REQUIRED_CHECK in "CI Passed" "Security Summary"/);
  assert.match(releaseGate, /if \[ "\$CONCLUSION" != "success" \]/);

  assert.doesNotMatch(
    djClientCi,
    /TAURI_SIGNING_PRIVATE_KEY|createUpdaterArtifacts|Disable updater artifacts/i,
  );
  assert.match(djClientCi, /npm run test:containment/);
  assert.match(djClientCi, /docker compose version/);
  assert.ok(
    djClientCi.indexOf("docker compose version") < djClientCi.indexOf("npm run test:containment"),
  );
  assert.match(djClientCi, /Build Tauri app \(unsigned validation\)/);
  assert.match(djClientCi, /run: npm run tauri:build -- --target/);
  for (const guardedPath of [
    ".github/workflows/release-dj-client.yml",
    ".github/workflows/release.yml",
    ".github/workflows/docker.yml",
    ".github/workflows/ci.yml",
    ".github/workflows/security.yml",
    "docker-compose.yml",
    "docker-compose.demo.yml",
  ]) {
    assert.equal(
      (djClientCi.match(new RegExp(escapeRegExp(guardedPath), "g")) ?? []).length,
      2,
      `${guardedPath} must trigger containment on push and pull request`,
    );
  }
  assert.ok(
    djClientCi.indexOf("npm run test:containment") <
      djClientCi.indexOf("Build Tauri app (unsigned validation)"),
  );

  const dockerTriggers = yamlBlock(dockerWorkflow, "on", 0);
  assert.match(dockerTriggers, /^\s+workflow_dispatch:\s*$/m);
  assert.match(dockerTriggers, /^\s+pull_request:\s*$/m);
  assert.doesNotMatch(dockerTriggers, /^\s+push:\s*$/m);
  assert.deepEqual(
    [...dockerWorkflow.matchAll(/^\s+push:\s*(\S+)\s*$/gm)].map((match) => match[1]),
    ["false"],
  );
  assert.doesNotMatch(dockerWorkflow, /docker\/login-action|packages:\s*write/);
});

test("release and security gates reject masked or unsuccessful checks", () => {
  const combinedRelease = readRepositoryFile(".github/workflows/release.yml");
  const ci = readRepositoryFile(".github/workflows/ci.yml");
  const security = readRepositoryFile(".github/workflows/security.yml");

  for (const [name, workflow] of [
    ["release", combinedRelease],
    ["CI", ci],
    ["security", security],
  ]) {
    assert.doesNotMatch(workflow, /continue-on-error\s*:|\|\|\s*true/, `${name} masks failures`);
  }

  assert.doesNotMatch(ci, /npm install --package-lock-only/);
  assert.doesNotMatch(security, /npm install --package-lock-only/);
  assert.match(yamlBlock(security, "on", 0), /^\s+push:\s*\n\s+branches:\s*\[main\]\s*$/m);
  assert.equal((ci.match(/npm ci --ignore-scripts/g) ?? []).length, 4);
  assert.equal((security.match(/npm ci --ignore-scripts/g) ?? []).length, 4);

  for (const step of [
    "Run Bandit",
    "Run pip-audit",
    "Audit root package",
    "Audit dj_client package",
    "Audit site package",
    "Audit worker package",
    "Run cargo-audit",
  ]) {
    assertJsonAuditStepIsBlocking(ci, step);
  }

  for (const step of [
    "pip-audit (JSON report)",
    "Audit root package",
    "Audit dj_client package",
    "Audit site package",
    "Audit worker package",
    "cargo-audit (JSON report)",
  ]) {
    assertJsonAuditStepIsBlocking(security, step);
  }

  for (const workflow of [ci, security]) {
    for (const step of [
      "Audit dj_client package",
      "Audit site package",
      "Audit worker package",
    ]) {
      assert.match(assertJsonAuditStepIsBlocking(workflow, step), /^\s+if:\s*always\(\)\s*$/m);
    }
  }

  assertSummaryRequiresEveryNeedToSucceed(ci, "ci-passed", [
    "python-lint",
    "java-build",
    "java-plugin-build",
    "site-build",
    "coordinator-test",
    "vj-server-test",
    "dj-client-test",
    "python-sast",
    "python-audit",
    "java-audit",
    "npm-audit",
    "rust-audit",
    "license-check",
    "sbom",
    "checksums",
  ]);
  assertSummaryRequiresEveryNeedToSucceed(security, "security-summary", [
    "python-security",
    "java-security",
    "npm-security",
    "rust-security",
  ]);
  assert.match(yamlBlock(ci, "ci-passed", 2), /echo "\|-\|-\|"/);
  assert.match(yamlBlock(security, "security-summary", 2), /echo "\|-\|-\|"/);
});

test("Compose renders and plans the exact Phase 0 service quarantine", () => {
  const rootModel = readComposeModel("docker-compose.yml");
  assert.deepEqual(Object.keys(rootModel.services).sort(), ["coordinator", "postgres", "vj-server"]);
  assert.deepEqual(rootModel.services["vj-server"].profiles, ["phase0-quarantined"]);
  assert.deepEqual(rootModel.services.coordinator.profiles ?? [], []);
  assert.deepEqual(rootModel.services.postgres.profiles ?? [], []);

  const rootDefaultPlan = runCompose("docker-compose.yml", ["--dry-run", "up", "--no-build"]);
  assertSuccessfulComposePlan("root default plan", rootModel.services, rootDefaultPlan, [
    "coordinator",
    "postgres",
  ]);

  const rootQuarantinePlan = runCompose("docker-compose.yml", [
    "--dry-run",
    "--profile",
    "phase0-quarantined",
    "up",
    "--no-build",
  ]);
  assertSuccessfulComposePlan("root quarantine-profile plan", rootModel.services, rootQuarantinePlan, [
    "coordinator",
    "postgres",
    "vj-server",
  ]);

  const demoModel = readComposeModel("docker-compose.demo.yml");
  assert.deepEqual(Object.keys(demoModel.services).sort(), ["preview", "vj_server"]);
  assert.deepEqual(demoModel.services.vj_server.profiles, ["phase0-quarantined"]);
  assert.deepEqual(demoModel.services.preview.profiles, ["phase0-quarantined"]);

  const demoDefaultPlan = runCompose("docker-compose.demo.yml", [
    "--dry-run",
    "up",
    "--no-build",
  ]);
  assert.equal(
    demoDefaultPlan.status,
    1,
    `demo default plan must fail closed with status 1:\n${demoDefaultPlan.output}`,
  );
  assert.match(
    demoDefaultPlan.output,
    /(?:^|\n)no service selected(?:\n|$)/,
    `demo default plan failed for an unexpected reason:\n${demoDefaultPlan.output}`,
  );
  assert.deepEqual(plannedServiceNames(demoModel.services, demoDefaultPlan.output), []);

  const demoQuarantinePlan = runCompose("docker-compose.demo.yml", [
    "--dry-run",
    "--profile",
    "phase0-quarantined",
    "up",
    "--no-build",
  ]);
  assertSuccessfulComposePlan(
    "demo quarantine-profile plan",
    demoModel.services,
    demoQuarantinePlan,
    ["preview", "vj_server"],
  );
});
