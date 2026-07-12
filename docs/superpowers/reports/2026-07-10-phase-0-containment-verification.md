# Phase 0 Containment Verification

Date: 2026-07-12 (America/New_York)

Branch: `fix/phase-0-containment`

Verified implementation HEAD before this report: `79f44eaa9501cbcd32a6c26139571e9ed3a092d1`

## Result

**PASS for Phase 0 containment.** Every listed Phase 0 test, lint, SAST, non-Java package audit, build, containment, and negative-security gate completed successfully from committed HEAD. The Paper and Fabric OWASP scanners are required and fail closed, but local unauthenticated NVD bootstrap failed before either scanner could produce a vulnerability report. This report therefore makes no vulnerability-clean claim for Java dependencies. The linked worktree was clean after generated build residue was restored. The original workspace retained its pre-existing untracked user work.

The verified suites contain 2,090 passing tests:

- VJ server: 438
- Community bot: 15
- Coordinator: 252
- Site: 103
- DJ Rust client: 48
- Protocol schema contract: 5
- Phase 0 release/Compose containment: 14
- Paper plugin: 947
- Fabric mod: 268

This result does **not** authorize new public DJ-client, updater, container, Paper, or Fabric distribution. Anonymous DJ artifacts and the public GHCR package were removed, while the DJ, Docker, Paper, and Fabric publisher workflows remain deliberately disabled.

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

### Task 12 — Close whole-branch review findings

- `d33d6a8 fix(security): contain static index resolution`
- `67633a3 fix(security): cap Lua pattern memory`
- `3aa9bbf fix(security): harden Minecraft relay failures`
- `0ac9ad5 fix(protocol): declare relay route on code approval`
- `7936628 fix(security): consume terminal OAuth state`
- `65935ac fix(security): enforce final containment policies`
- `0b85467 fix(security): close Paper lifecycle races`
- `6713b38 fix(security): contain Lua pattern bridge`
- `49138cf fix(security): consume direct OAuth callbacks`
- `608f296 fix(security): harden Minecraft WebSocket frames`
- `9466c07 fix(perf): bound Minecraft parser queues`
- `73995a3 fix(lifecycle): own Paper WebSocket startup`
- `bdf0c03 fix(lifecycle): drain VJ renderer transport`

### Task 13 — Require release and dependency gates

- `0105a62 fix(ci): require containment release gates`
- `44fa140 fix(security): scan Java dependencies`

### Task 14 — Quarantine historical release workflows

- `e4e8104 fix(release): quarantine historical tag workflows`
- `dcd18fa fix(release): canonicalize tag ruleset`

### Task 15 — Close final PR review findings

- `462856a fix(coordinator): reject malformed metrics credentials`
- `2524c32 fix(dj-client): satisfy Linux Clippy`
- `9f0b77b fix(security): close final containment review gaps`

### Task 16 — Scope Java audits to distributed components

- `32d0158 fix(ci): audit shipped Java dependencies`

### Task 17 — Stabilize the Paper frame-limit transport assertion

- `79f44ea test(paper): stabilize frame-limit close assertion`

## Toolchain

|Tool|Verified version|
|-|-|
|Git|2.53.0.windows.2|
|Node.js (project default)|20.20.1|
|Node.js (Worker verification)|22.16.0; Worker and CI audit jobs require 22 or newer|
|npm|10.8.2 with project-default Node; 10.9.2 with Worker Node 22|
|Python package venvs|3.12.13 under WSL for VJ, community bot, and coordinator|
|uv|0.10.11|
|Lupa|2.8 with PUC Lua 5.5 runtime probes and a hard 16 MiB allocator limit|
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

- `cd vj_server && .venv/bin/python -m pytest -q && .venv/bin/python -m ruff check . && .venv/bin/python -m ruff format --check .` under WSL — exit `0`; 438 passed, 58.89% coverage, 37 files already formatted, and three upstream WebSockets deprecation warnings.
- `cd community_bot && .venv/bin/python -m pytest -q && .venv/bin/python -m ruff check . && .venv/bin/python -m ruff format --check .` under WSL — exit `0`; 15 passed, 11 files already formatted, and one upstream `audioop` deprecation warning.
- `cd coordinator && .venv/bin/python -m pytest -q && .venv/bin/python -m ruff check . && .venv/bin/python -m ruff format --check .` under WSL — exit `0`; 252 passed, 63.67% coverage against a 60% threshold, and 98 files already formatted.
- `cd community_bot && .venv/bin/python -m pip check` under WSL — exit `0`; the editable `0.1.0` package resolves to this worktree with no broken requirements.

### SAST and dependency audits

- `cd vj_server && .venv/bin/python -m bandit -r . -c ../pyproject.toml` under WSL — exit `0`; 9,176 project lines scanned, no issues, with one configured disabled-check finding suppressed.
- `cd vj_server && .venv/bin/python -m pip_audit --local` under WSL — exit `0`; 47 dependencies audited with no known vulnerabilities.
- `cd community_bot && .venv/bin/python -m pip_audit --local` under WSL — exit `0`; 45 dependencies audited with no known vulnerabilities.
- `cd coordinator && .venv/bin/python -m pip_audit --local` under WSL — exit `0`; 74 dependencies audited with no known vulnerabilities.
- Each first-party editable Python package was skipped by `pip-audit` because it is not published on PyPI.
- `npm audit --audit-level=high` — exit `0`; zero vulnerabilities.
- `npm --prefix dj_client audit --audit-level=high` — exit `0`; zero vulnerabilities.
- `npm --prefix site audit --audit-level=high` — exit `0`; zero vulnerabilities.
- `npm --prefix worker audit --audit-level=high` — exit `0`; zero vulnerabilities.
- `cargo audit --json` from `dj_client/src-tauri` — exit `0`; zero vulnerabilities, 18 visible unmaintained warnings, and two visible unsound warnings. No advisory ignore list or warning suppression is active.
- `JAVA_HOME=Temurin-21; cd minecraft_plugin && .\mvnw.cmd dependency:tree -B` — exit `0`.
- `JAVA_HOME=Temurin-21; cd minecraft_mod && .\gradlew.bat dependencies --configuration runtimeClasspath` — exit `0`.
- Paper and Fabric OWASP Dependency-Check 12.2.2 gates are pinned in both primary CI and the security workflow with CVSS `0`, `failOnError`, hosted suppressions disabled, JSON/SARIF artifacts, rolling NVD caches, and optional `NVD_API_KEY` acceleration. Paper excludes host-provided APIs; Fabric scans Loom's resolvable `includeInternal` configuration, which contains exactly the three JARs nested in the distributed mod.
- Local Paper and Fabric Dependency-Check executions both failed closed during unauthenticated NVD bootstrap after API retry exhaustion. Neither produced a completed vulnerability report, so Java dependency vulnerability status remains unverified locally rather than clean.

Audit remediation performed during this gate:

- Root, DJ, site, and Worker npm workspaces were upgraded to advisory-free dependency sets.
- Worker audit jobs were moved to Node 22 because the patched Wrangler/Miniflare toolchain requires Node 22 or newer.
- Rust `plist` moved from 1.8.0 to 1.10.0 and `quick-xml` from 0.38.4 to 0.41.0, resolving `RUSTSEC-2026-0194` and `RUSTSEC-2026-0195`.
- Reachable patch releases for `anyhow` and `rand` were also applied.

### Web, protocol, native-client, and Worker gates

- `npm --prefix site test` — exit `0`; 103 passed.
- `npm --prefix site run lint` — exit `0`; zero errors and 26 existing warnings.
- `npm --prefix site run build` — exit `0`; Next.js 16.2.10 compiled and generated 19 routes.
- `node --test protocol/tests/phase0-schemas.test.mjs` — exit `0`; 5 passed.
- `npm --prefix dj_client run test:containment` — exit `0`; 14 passed, including required CI/release provenance, historical-tag quarantine, publisher least privilege, Java scan policy, rendered Compose configuration, and actual dry-run service-plan assertions.
- `npm --prefix dj_client run build` — exit `0`; TypeScript and Vite 7.3.6 production build passed.
- `cargo fmt --manifest-path dj_client/src-tauri/Cargo.toml -- --check` — exit `0`.
- `cargo test --manifest-path dj_client/src-tauri/Cargo.toml --locked` — exit `0`; 48 passed.
- `cargo clippy --manifest-path dj_client/src-tauri/Cargo.toml --locked -- -D warnings` — exit `0`.
- `npm --prefix dj_client run tauri -- build --no-bundle --no-sign` — exit `0`; unsigned, unbundled executable built without an updater endpoint.
- `npm run build` — exit `0`; root admin/preview production assets built with Vite 8.1.4.
- `cd worker && npx tsc --noEmit` using Node 22.16.0 — exit `0`.
- `cd worker && npx wrangler deploy --dry-run` using Node 22.16.0 — exit `0`; Wrangler 4.102.0 compiled a 7.25 KiB upload without uploading or changing remote state.

### Minecraft artifact gates

- `JAVA_HOME=Temurin-21; cd minecraft_plugin && .\mvnw.cmd clean test package -B` — exit `0`; 947 test-case results, zero failures/errors/skips, shaded Paper JAR built.
- `JAVA_HOME=Temurin-21; cd minecraft_mod && .\gradlew.bat clean test build --no-daemon` — exit `0`; 268 passed, zero failures/errors/skips, remapped Fabric JAR built.
- `JAVA_HOME=Temurin-21; cd minecraft_plugin && .\mvnw.cmd -Dtest=VizWebSocketServerFrameLimitTest test -B` plus 10 consecutive quiet stress iterations — exit `0` throughout; the server-side limit reason remained `1009` for all four payload shapes while the peer accepted only `1009` or the transport-equivalent synthetic `1006`.

### Negative security assertions

The following searches intentionally return `1`, meaning no forbidden pattern was found:

- `rg -n "pending_pattern_scripts|route\.pattern_scripts|pub mod patterns|mlua|tauri_plugin_updater|plugin-updater|tauri-plugin-updater|releases/latest/download/latest.json" vj_server/dj_manager.py protocol/schemas/messages/stream-route.schema.json dj_client/src dj_client/package.json dj_client/package-lock.json dj_client/src-tauri --glob '!patterns.rs' --glob '!**/target/**'` — exit `1`.
- `rg -n "if \(storedState && storedState !== state\)" site/src` — exit `1`.
- `rg -n "secret != bot.config.webhook_secret|settings.metrics_token is None.*return" community_bot coordinator` — exit `1`.
- `rg -n "continue-on-error|\|\| true" .github/workflows/ci.yml .github/workflows/security.yml .github/workflows/release.yml .github/workflows/release-dj-client.yml .github/workflows/docker.yml` — exit `1`.

Additional repository assertions:

- `git diff --check` — exit `0`.
- `git diff cf901958672a6041d309dac1dd281c7e819e485b...HEAD --check` — exit `0`; repository whitespace attributes correctly recognize CRLF line endings while retaining trailing-whitespace detection.
- `git status --short` — exit `0` with no output after build-residue cleanup; immediately before the documentation commit, its only output was the amended plan and this verification report.
- YAML parsing of `ci.yml`, `security.yml`, `release.yml`, `release-dj-client.yml`, `release-plugin.yml`, `release-mod.yml`, `docker.yml`, and `dj-client-ci.yml` — all valid.
- `.github/rulesets/phase0-release-tags.json` and `.github/rulesets/paper-fabric-release-tags.json` parsed as JSON and match active repository rulesets `18824190` and `18833547`: tag targets, active enforcement, zero bypass actors, `v*`/`dj-v*` and `plugin-v*`/`mod-v*` coverage, plus creation/update/deletion/non-fast-forward restrictions.
- GitHub workflow IDs `229324789` (`docker.yml`), `234725199` (`release-dj-client.yml`), `239555358` (`release-mod.yml`), and `239555359` (`release-plugin.yml`) report `disabled_manually`; the generic combined workflow `229324792` (`release.yml`) remains active but cannot receive a `v*` tag while the external quarantine is active.
- DJ releases `286994513` (`dj-v1.0.0`) and `291202382` (`dj-v1.1.0`) are drafts with their assets retained for recovery; anonymous updater-metadata URLs return `404`.
- The public GHCR package was deleted and remains restorable for 30 days; its package page returns `404` and anonymous token acquisition returns `403`.
- The 130 historical Docker/DJ/combined release runs are no longer rerunnable: their newest run is from 2026-03-31, beyond GitHub's [documented 30-day rerun window](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs) as of this report.
- The original workspace status still contained only its known pre-existing untracked user work; no original-workspace path was staged or committed.

## Artifact Evidence

|Artifact|Bytes|SHA-256|
|-|-|-|
|`minecraft_plugin/target/audioviz-plugin-1.0.0-SNAPSHOT.jar`|1,136,370|`F080204D9CE6B469AB916B6C5156EB481025A00EDB0C3BEB8067164F3E50EF86`|
|`minecraft_mod/build/libs/audioviz-mod-1.0.0.jar`|1,031,988|`7AE3648BAA0659E478C47D6E5E7B672F7A494DFAB370B4708A6B66C5E3093AE1`|
|`dj_client/src-tauri/target/release/dj-client.exe`|6,739,968|`90E8A9519036F546F8CFCCBA73FFDAC2F70BE185631DB598767B9F227E8ABE52`|

The DJ executable is evidence of compile viability only. It is unsigned, unbundled, and not approved for distribution.

## Security Outcome Confirmations

- Hostile legacy `stream_route` payloads continue to deserialize for compatibility, but script fields are ignored and no source-execution path remains. Protocol and Rust containment tests cover this behavior.
- Static content and Lua pattern IDs are canonicalized beneath their configured roots; traversal, absolute paths, Windows device basenames, backslashes, and symlink escapes are rejected.
- Lua patterns run without Python attribute traversal, JIT control, coroutines, legacy loaders, or public timeout-reset controls. The trusted Python bridge captures safe packers before untrusted pattern code, bounds iteration before unpacking, and executes under a hard 16 MiB allocator limit. Timeout and infinite-loop probes pass under PUC Lua 5.5 in killable subprocesses.
- Missing, mismatched, empty, malformed, replayed, or terminal OAuth callbacks fail before any authorization-code exchange and consume state exactly once across both callback surfaces.
- Community webhook and coordinator metrics authentication fail closed when their secrets/tokens are absent or invalid.
- Minecraft relay connections authenticate before admission, declare their renderer route after code approval, derive connections from a validated loopback host/port snapshot, drain owned pending work on disconnect, and redact peer-controlled transport failures.
- Paper and Fabric WebSocket listeners accept explicit loopback binds only, regardless of shared-secret configuration. Closed auth envelopes, 256 KiB frame limits, bounded two-worker parser queues, constant-time drop-oldest backpressure, sanitized close reasons, and guarded admission/lifecycle transitions prevent unauthenticated work and unbounded growth. Parsed top-level message types determine routing, so key order, whitespace, and nested spoofing cannot bypass the bounded queues; all seven hot-path frame types remain bounded even when the legacy async flag is disabled.
- Paper owns listener startup completion and shutdown cancellation under one lifecycle lock, cancels pending main-thread futures before draining client work, and restores interruption state on timeout paths; Fabric clears equivalent worker and queue state on stop.
- Coordinator metrics authentication rejects malformed and non-ASCII bearer credentials without raising an internal error.
- The VJ deployment helper validates loopback configuration before mutation, verifies the launched process owns every configured listener, and requires renderer-connected health before reporting success.
- Primary CI requires community-bot, protocol-contract, containment, Rust audit, and both fail-closed, distribution-scoped Java dependency-scan results. Tag releases require the tagged SHA to be on `main` and exact successful CI/security workflow runs for that SHA.
- Current tag/main workflows cannot publish a DJ client, updater metadata, or Docker image during Phase 0. Active no-bypass repository rules prevent vulnerable `v*` and `dj-v*` tags from being created, moved, deleted, or force-updated against historical workflow revisions; the legacy remote DJ and Docker workflows are disabled.
- Generic combined releases are quarantined with `v*`. Paper and Fabric release workflows retain exact-main CI/security provenance checks for a future trusted publisher, but `plugin-v*` and `mod-v*` creation is currently blocked with no bypass and both workflows are disabled to prevent historical-ref execution.
- VJ and demo Compose services require the explicit `phase0-quarantined` profile and are absent from the default dry-run plan.

## Non-Phase-0 Limitations

These are not green public-release paths and remain work for later phases:

- Public DJ-client, updater, VJ-container, Docker, and new Paper/Fabric distribution remains disabled by design. Existing Paper/Fabric releases were preserved.
- Repository rulesets `18824190` and `18833547` must remain active. DJ, Paper, and Fabric release workflows must remain disabled until signed distribution uses a trusted default-branch publisher; the Docker workflow may be re-enabled only after the fail-closed branch version reaches `main`.
- Java dependency vulnerability status is not cleanly established locally: both OWASP scans failed closed while bootstrapping unauthenticated NVD data. CI has rolling caches and optional `NVD_API_KEY`, and remains blocking if feed retrieval or analysis fails.
- `cargo audit` reports 20 visible report-only warnings: 18 unmaintained findings plus two unsound findings, `RUSTSEC-2024-0429` (`glib`) and `RUSTSEC-2026-0097` (`rand`). Blocking Rust vulnerabilities are zero, but the remaining dependency topology needs Phase 1 ownership before distribution reopens.
- Renderer authentication remains bearer-secret based, but transport is restricted to loopback. Split-host deployments require an encrypted tunnel terminating on the Minecraft host's loopback listener; direct LAN and public renderer transport is rejected.
- Local Lua patterns still execute in the VJ server process. Path containment, sandboxing, instruction limits, and memory limits reduce exposure, while OS-level isolation remains future hardening.
- Site lint exits successfully with 26 existing warnings, including generated Fengari code and several unused imports. The root Vite build also warns about classic scripts that cannot be bundled as ES modules.
- VJ uses deprecated WebSockets compatibility APIs, and the community bot depends on Python's deprecated `audioop` through Discord.py.
- Paper emits Mockito dynamic-agent, deprecated API, and shaded-resource overlap warnings. Fabric emits deprecated API and Gradle-10 compatibility warnings.
- Maven Shade rewrites the tracked `dependency-reduced-pom.xml` during packaging; that generated residue was restored after verification. A later build cleanup should generate it under `target/` or stop tracking it.
- Worker verification requires Node 22 or newer and still uses `wrangler.toml` with compatibility date `2026-02-01`; modernization is deferred to the platform-foundation phase.

## PR Integration Verification

Before merge review, current `origin/main` at `78e077db8552f4dd4291b4f5186a5b27aa42735e` was integrated by merge commit `3b571fa8d481158c2db1f7b18787c513c9e698e7`. The dependency-conflict resolution preserved Phase 0's newer site and Worker toolchains while also retaining upstream TypeScript `6.0.2` and the `@cloudflare/workers-types` `^4.20260331.1` range.

The affected surfaces were reverified from clean installs after that merge:

- Root `npm audit --audit-level=high` and the Vite production build passed with zero reported vulnerabilities.
- Site `npm audit --audit-level=high`, 103 tests, lint, and the Next.js production build passed; lint retained the same 26 non-blocking warnings recorded above.
- Worker `npm audit --audit-level=high`, `tsc --noEmit`, and `wrangler deploy --dry-run` passed under Node.js `22.16.0` with Wrangler `4.102.0` and zero reported vulnerabilities.
- The upstream merge itself changed no release workflow, containment policy, runtime implementation, or public-distribution state. Final review remediation subsequently tightened the renderer transport, bounded routing, shutdown cancellation, release provenance, Java audit scope, and repository control plane described above.

## Final Disposition

Phase 0 containment is verified at implementation HEAD `79f44eaa9501cbcd32a6c26139571e9ed3a092d1`; anonymous DJ/container artifacts are withdrawn and new public distribution remains quarantined in code and in the GitHub repository control plane. The next engineering step is a plan-first Phase 1 foundation design covering workspace boundaries, a canonical protocol envelope, benchmark/latency harnesses, and the first Rust Show Engine skeleton.
