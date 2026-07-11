# Phase 0 Containment Verification

Date: 2026-07-10 (America/New_York)

Branch: `fix/phase-0-containment`

Verified implementation HEAD before this report: `ddebe60b35d74563ee111d59fe7f10a31638f109`

## Result

**PASS.** Every blocking Phase 0 test, lint, SAST, dependency-audit, build, containment, and negative-security gate completed successfully from committed HEAD. The linked worktree was clean after generated build residue was removed. The original workspace retained its pre-existing untracked user work.

The verified suites contain 1,896 passing tests:

- VJ server: 396
- Community bot: 15
- Coordinator: 250
- Site: 86
- DJ Rust client: 48
- Protocol schema contract: 5
- Phase 0 release/Compose containment: 5
- Paper plugin: 870
- Fabric mod: 221

This result does **not** authorize public DJ-client, updater, or Docker distribution. Those release paths remain deliberately quarantined.

## Verified Commit Ledger

### Task 1 — Remove remote Lua execution

- `080b9f0 fix(security): remove remote Lua execution path`

### Task 2 — Contain the VJ static server

- `7be0688 fix(security): contain VJ static file server`
- `f489dee fix(security): close static server edge cases`
- `7dda98e fix(security): harden static rejection handling`
- `b900605 fix(security): normalize Windows device basenames`

### Task 3 — Enforce single-use OAuth state

- `593fc23 fix(security): require single-use OAuth state`

### Task 4 — Authenticate the community webhook

- `e449505 fix(security): require community webhook secret`

### Task 5 — Fail closed on metrics authentication

- `f7ef597 fix(security): fail closed on metrics authentication`
- `8641dc8 test(security): cover metrics auth edge cases`

### Task 6 — Authenticate and redact the Minecraft relay

- `7ba913c fix(security): authenticate Minecraft relay connections`
- `a1aa42b fix(security): redact relay transport errors`

### Task 7 — Secure the Paper WebSocket listener

- `0ecc3fe fix(security): secure Paper WebSocket listener`
- `6d2e09a fix(security): make Paper auth admission atomic`
- `03de4bb fix(security): serialize Paper connection lifecycle`

### Task 8 — Secure the Fabric WebSocket listener

- `859e118 fix(security): secure Fabric WebSocket listener`
- `625e419 fix(security): close Fabric lifecycle races`
- `8240732 fix(security): guard Fabric queued work`
- `6aa9c06 fix(security): isolate Fabric queue parsing`

### Task 9 — Quarantine the legacy updater

- `991d4c0 fix(security): disable legacy DJ client updater`
- `c255a68 fix(security): finish updater quarantine`

### Task 10 — Quarantine unsupported distribution

- `0d873f6 chore(release): quarantine unsupported v1 distribution`
- `6f3a0e4 test(security): verify Compose quarantine behavior`

### Task 11 — Close verification-discovered gaps

- `b144391 fix(security): enforce Lua sandbox limits`
- `c3607fc chore(security): scope Bandit scan inputs`
- `27ec88e chore(deps): update audited Rust dependencies`
- `6edc690 chore(deps): update audited web toolchains`
- `ddebe60 chore(git): recognize CRLF line endings`

## Toolchain

|Tool|Verified version|
|-|-|
|Git|2.53.0.windows.2|
|Node.js (project default)|20.20.1|
|Node.js (Worker verification)|24.14.0; CI audit jobs require 22|
|npm|10.8.2|
|Python package venvs|3.12.13 under WSL for VJ, community bot, and coordinator|
|uv|0.10.11|
|Lupa|2.8, with LuaJIT 2.1 and PUC Lua 5.5 probes|
|Cargo|1.93.0|
|rustc|1.93.0|
|cargo-audit|0.22.1|
|Tauri CLI|2.10.1|
|Java|Temurin 21.0.10+7 LTS|
|Maven Wrapper|3.9.6|
|Gradle Wrapper|9.3.1|
|Docker Desktop CLI|29.1.3|
|Docker Compose|2.40.3-desktop.1|
|Wrangler|4.102.0|

## Gate Evidence

All commands below were run from the clean linked worktree unless a component directory is stated. Every listed exit code is the observed final-run result.

### Python suites and linters

- `cd vj_server && .venv/bin/python -m pytest -q && .venv/bin/python -m ruff check .` under WSL — exit `0`; 396 passed, 55.01% coverage, three upstream WebSockets deprecation warnings.
- `cd community_bot && .venv/bin/python -m pytest -q && .venv/bin/python -m ruff check .` under WSL — exit `0`; 15 passed, one upstream `audioop` deprecation warning.
- `cd coordinator && .venv/bin/python -m pytest -q && .venv/bin/python -m ruff check app tests` under WSL — exit `0`; 250 passed, 63.67% coverage against a 60% threshold.

### SAST and dependency audits

- `cd vj_server && .venv/bin/python -m bandit -r . -c ../pyproject.toml` under WSL — exit `0`; 9,010 project lines scanned, no issues.
- `cd vj_server && .venv/bin/python -m pip_audit --local` under WSL — exit `0`; no known vulnerabilities. The editable local package itself is not published on PyPI and was reported as unauditable by name.
- `cd community_bot && .venv/bin/python -m pip_audit --local` under WSL — exit `0`; no known vulnerabilities.
- `cd coordinator && .venv/bin/python -m pip_audit --local` under WSL — exit `0`; no known vulnerabilities. The editable local package itself is not published on PyPI and was reported as unauditable by name.
- `npm audit --audit-level=high` — exit `0`; zero vulnerabilities.
- `npm --prefix dj_client audit --audit-level=high` — exit `0`; zero vulnerabilities.
- `npm --prefix site audit --audit-level=high` — exit `0`; zero vulnerabilities.
- `npm --prefix worker audit --audit-level=high` — exit `0`; zero vulnerabilities.
- `cargo audit --file dj_client/src-tauri/Cargo.lock` — exit `0`; no blocking vulnerabilities, 18 allowed unmaintained warnings, and two allowed unsound warnings.
- `JAVA_HOME=Temurin-21; cd minecraft_plugin && .\mvnw.cmd dependency:tree -B` — exit `0`.
- `JAVA_HOME=Temurin-21; cd minecraft_mod && .\gradlew.bat dependencies --configuration runtimeClasspath` — exit `0`.

Audit remediation performed during this gate:

- Root, DJ, site, and Worker npm workspaces were upgraded to advisory-free dependency sets.
- Worker audit jobs were moved to Node 22 because the patched Wrangler/Miniflare toolchain requires Node 22 or newer.
- Rust `plist` moved from 1.8.0 to 1.10.0 and `quick-xml` from 0.38.4 to 0.41.0, resolving `RUSTSEC-2026-0194` and `RUSTSEC-2026-0195`.
- Reachable patch releases for `anyhow` and `rand` were also applied.

### Web, protocol, native-client, and Worker gates

- `npm --prefix site test` — exit `0`; 86 passed.
- `npm --prefix site run lint` — exit `0`; zero errors and 26 existing warnings.
- `npm --prefix site run build` — exit `0`; Next.js 16.2.10 compiled and generated 19 routes.
- `node --test protocol/tests/phase0-schemas.test.mjs` — exit `0`; 5 passed.
- `npm --prefix dj_client run test:containment` — exit `0`; 5 passed, including rendered Compose configuration and actual dry-run service-plan assertions.
- `npm --prefix dj_client run build` — exit `0`; TypeScript and Vite 7.3.6 production build passed.
- `cargo fmt --manifest-path dj_client/src-tauri/Cargo.toml -- --check` — exit `0`.
- `cargo test --manifest-path dj_client/src-tauri/Cargo.toml --locked` — exit `0`; 48 passed.
- `cargo clippy --manifest-path dj_client/src-tauri/Cargo.toml --locked -- -D warnings` — exit `0`.
- `npm --prefix dj_client run tauri -- build --no-bundle --no-sign` — exit `0`; unsigned, unbundled executable built without an updater endpoint.
- `npm run build` — exit `0`; root admin/preview production assets built with Vite 8.1.4.
- `cd worker && tsc --noEmit` using Node 24.14.0 — exit `0`.
- `cd worker && wrangler deploy --dry-run` using Node 24.14.0 — exit `0`; Worker compiled without uploading or changing remote state.

### Minecraft artifact gates

- `JAVA_HOME=Temurin-21; cd minecraft_plugin && .\mvnw.cmd clean test package` — exit `0`; 870 passed, zero failures/errors/skips, shaded Paper JAR built.
- `JAVA_HOME=Temurin-21; cd minecraft_mod && .\gradlew.bat clean test build` — exit `0`; 221 passed, zero failures/errors/skips, remapped Fabric JAR built.

### Negative security assertions

The following searches intentionally return `1`, meaning no forbidden pattern was found:

- `rg -n "pending_pattern_scripts|route\.pattern_scripts|pub mod patterns|mlua|tauri_plugin_updater|plugin-updater|tauri-plugin-updater|releases/latest/download/latest.json" vj_server/dj_manager.py protocol/schemas/messages/stream-route.schema.json dj_client/src dj_client/package.json dj_client/package-lock.json dj_client/src-tauri --glob '!patterns.rs'` — exit `1`.
- `rg -n "if \(storedState && storedState !== state\)" site/src` — exit `1`.
- `rg -n "secret != bot.config.webhook_secret|settings.metrics_token is None.*return" community_bot coordinator` — exit `1`.
- `rg -n "continue-on-error|\|\| true" .github/workflows/ci.yml .github/workflows/security.yml .github/workflows/release.yml .github/workflows/release-dj-client.yml .github/workflows/docker.yml` — exit `1`.

Additional repository assertions:

- `git diff --check` — exit `0`.
- `git diff main...HEAD --check` — exit `0`; repository whitespace attributes correctly recognize CRLF line endings while retaining trailing-whitespace detection.
- `git status --short` — exit `0` with no output after build-residue cleanup; immediately before the documentation commit, its only output was the untracked verification report.
- YAML parsing of `ci.yml`, `security.yml`, `release.yml`, `release-dj-client.yml`, `docker.yml`, and `dj-client-ci.yml` — all valid.
- The original workspace status still contained only its known pre-existing untracked user work; no original-workspace path was staged or committed.

## Artifact Evidence

|Artifact|Bytes|SHA-256|
|-|-|-|
|`minecraft_plugin/target/audioviz-plugin-1.0.0-SNAPSHOT.jar`|1,117,943|`C027EA29B395000CE9BA27B650C87A83C3F8E2EE4BA84E24EA4698F62B915DE9`|
|`minecraft_mod/build/libs/audioviz-mod-1.0.0.jar`|1,030,541|`C3776990F8827AF55702B2F8F8B98FB943E643F472C3616F33CF62DAD800A755`|
|`dj_client/src-tauri/target/release/dj-client.exe`|6,739,968|`E81B023A6523FF12F05310392C4038E85E5DDAA9FC5933E0A7AA0839584AB982`|

The DJ executable is evidence of compile viability only. It is unsigned, unbundled, and not approved for distribution.

## Security Outcome Confirmations

- Hostile legacy `stream_route` payloads continue to deserialize for compatibility, but script fields are ignored and no source-execution path remains. Protocol and Rust containment tests cover this behavior.
- Lua patterns run without the Lupa Python bridge, Python attribute traversal, JIT control, coroutines, legacy loaders, or public timeout-reset controls. Timeout errors and reset closures are private upvalues. True infinite-loop probes pass under both LuaJIT and PUC Lua in killable subprocesses.
- Missing, mismatched, empty, replayed, or otherwise invalid OAuth state fails before any authorization-code exchange.
- Community webhook and coordinator metrics authentication fail closed when their secrets/tokens are absent or invalid.
- Minecraft relay connections authenticate before use, and peer-controlled transport errors cannot disclose relay secrets.
- Paper and Fabric non-loopback WebSocket listeners remain offline without a configured shared secret. Admission, queue, and lifecycle transitions are serialized and guarded.
- Tag/main workflows cannot publish a DJ client, updater metadata, or Docker image during Phase 0. Combined releases are limited to Paper and Fabric artifacts.
- VJ and demo Compose services require the explicit `phase0-quarantined` profile and are absent from the default dry-run plan.

## Non-Phase-0 Limitations

These are not green public-release paths and remain work for later phases:

- Public DJ-client, updater, VJ-container, and Docker distribution remains disabled by design.
- `cargo audit` reports 20 allowed warnings: 18 unmaintained findings plus two unsound findings, `RUSTSEC-2024-0429` (`glib`) and `RUSTSEC-2026-0097` (`rand`). Blocking Rust vulnerabilities are zero, but the remaining dependency topology needs Phase 1 ownership.
- Site lint exits successfully with 26 existing warnings, including generated Fengari code and several unused imports. The root Vite build also warns about classic scripts that cannot be bundled as ES modules.
- VJ uses deprecated WebSockets compatibility APIs, and the community bot depends on Python's deprecated `audioop` through Discord.py.
- Paper emits Mockito dynamic-agent, deprecated API, and shaded-resource overlap warnings. Fabric emits deprecated API and Gradle-10 compatibility warnings.
- Maven Shade rewrites the tracked `dependency-reduced-pom.xml` during packaging; that generated residue was restored after verification. A later build cleanup should generate it under `target/` or stop tracking it.
- Worker verification requires Node 22 or newer and still uses `wrangler.toml` with compatibility date `2026-02-01`; modernization is deferred to the platform-foundation phase.
- The community-bot editable-install packaging layout remains a separate maintenance issue; its WSL test environment and dependency audit are green.

## Final Disposition

Phase 0 containment is verified and ready for branch-level review. The next authorized engineering step is a new plan-first Phase 1 foundation design covering workspace boundaries, a canonical protocol envelope, benchmark/latency harnesses, and the first Rust Show Engine skeleton.
