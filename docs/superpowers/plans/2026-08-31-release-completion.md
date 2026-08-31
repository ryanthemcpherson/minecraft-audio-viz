# MCAV 1.2.0 Release Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a signed, independently verified MCAV 1.2.0 release candidate whose Paper experience installs from one plugin JAR and whose complete artifact set has passed clean-install, upgrade, rollback, end-to-end, security, and minimum-host performance gates.

**Architecture:** Extend the managed-runtime builder from the VJ plan into native-platform release builders driven by one immutable lock and one product-version contract. Release workflows build each platform on its native runner, assemble already-tested artifacts without rebuilding them, sign at a protected boundary, and submit the assembled candidate to an independent verifier. Real Paper and Windows clean-machine harnesses exercise the artifacts exactly as operators receive them. Public publication remains a distinct, manually authorized action after the candidate evidence is complete.

**Tech Stack:** GitHub Actions, Python 3.12 release tooling in WSL, Java 21/Paper 1.21.11, Maven, Gradle, Rust/Tauri 2, Windows NSIS/WiX, Ed25519, GPG, Authenticode, CycloneDX, GitHub artifact attestations, Docker/VM resource controls, pytest.

**Spec:** `docs/superpowers/specs/2026-08-31-self-installing-paper-release-design.md`

## Global Constraints

- Complete the trust/store, Paper-supervision, and VJ-ingress plans first and consume their contracts unchanged.
- The unified product version is `1.2.0`; release candidates use `1.2.0-rc.N` without changing protocol or runtime API versions.
- Build Linux AMD64, Linux ARM64, and Windows AMD64 runtime archives on matching native runners. Do not publish a cross-labeled archive.
- Build once, test the exact digests, sign those same bytes, and assemble the candidate without rebuilding.
- Production runtime signing, GPG, and Windows Authenticode private credentials exist only in protected release environments. Test keys cannot satisfy production key IDs.
- Missing credentials, unavailable runners, missing evidence, failed scanners, failed attestations, unsigned binaries, or artifact drift fail the candidate.
- The automatic DJ updater remains disabled in 1.2.0. The signed NSIS and MSI installers are explicit install, upgrade, and rollback packages.
- The current Pterodactyl bundle remains buildable and documented. Docker publication remains quarantined.
- Phase 0 release controls change only after every gate in this plan passes for one exact candidate commit.
- Do not delete administrator data, the legacy Pterodactyl package, containment tests, or quarantine evidence.
- Preserve pre-existing user work, including untracked admin-panel modules. If a listed target already contains user work, merge around it and stage only release-owned changes.
- Public tag creation or release publication requires a final explicit user instruction. Completion of this plan produces a publication-ready signed candidate and draft metadata.
- Use WSL-native Python in `.release-venv`; never install Python packages with Windows Python.
- Run `git status` and `git diff` before every task commit and stage only that task's files.

## File Structure

### Version and artifact contract

- `release/product-version.json` — canonical product, runtime API, protocol, Minecraft, Paper, and Java compatibility values.
- `release/artifacts-v1.json` — closed inventory, exact filenames, signing requirements, SBOM names, and supported platforms.
- `scripts/release/verify_versions.py` — validates ecosystem metadata and artifact names against the contract.
- `scripts/release/test_verify_versions.py` — mismatch, prerelease, and filename contract tests.
- `package.json`, `vj_server/pyproject.toml`, `minecraft_plugin/pom.xml`, `minecraft_mod/build.gradle`, `dj_client/package.json`, `dj_client/src-tauri/Cargo.toml`, and `dj_client/src-tauri/tauri.conf.json` — converge on `1.2.0`.

### Runtime release builders

- `deploy/runtime/runtime-lock.json` — pinned standalone Python distributions, wheels, builder tools, URLs, digests, licenses, and target tags.
- `deploy/runtime/build_release_runtime.py` — native target builder with deterministic file manifest and archive normalization.
- `deploy/runtime/verify_release_runtime.py` — offline archive layout, file digest, executable, import, and smoke verifier.
- `deploy/runtime/test_release_builder.py` — lock, determinism, target, tampering, and offline verification tests.
- `deploy/runtime/invoke-locked-python.ps1` — Windows bootstrap that verifies and invokes the standalone Python declared by the runtime lock without installing Python or packages on Windows.
- `deploy/runtime/build-managed-runtime.sh` and `deploy/runtime/build-managed-runtime.ps1` — thin native entrypoints into the release builder.
- `deploy/pterodactyl/build-runtime.sh` and `deploy/pterodactyl/runtime-lock.json` — consume the shared pins without changing legacy layout.

### Signing, SBOM, and candidate assembly

- `release/keys/runtime-prod-1.pub.der.b64` — production Ed25519 public key embedded into the release plugin.
- `scripts/release/check_signing_environment.py` — validates key IDs, public/private correspondence, GPG identity, Authenticode identity, and protected release inputs without logging secrets.
- `scripts/release/assemble_candidate.py` — validates the inventory, copies exact artifacts, writes canonical manifest/checksums, requests signatures, and emits candidate metadata.
- `scripts/release/verify_candidate.py` — independent offline signature, checksum, archive-member, SBOM, attestation-subject, and provenance verifier.
- `scripts/release/test_candidate_tools.py` — fixture-key and tamper matrix.
- `scripts/release/generate_sboms.sh` and `scripts/release/generate_sboms.ps1` — CycloneDX generation for Java, Python runtime, Rust/Tauri, Fabric, and web assets.
- `release/policy/release-policy.json` — production key IDs, required evidence, licenses, signer identities, and allowed artifact set.

### Real-system verification

- `deploy/paper/paper-lock.json` — supported Paper build URL, SHA-256, Java distribution, and platform image digests.
- `scripts/release/paper_matrix.py` — clean install, restart, upgrade, rollback, conflict, crash, shutdown, and retention harness.
- `scripts/release/test_paper_matrix.py` — harness-state, timeout, failure-injection, and evidence tests.
- `scripts/release/e2e_release.py` — packaged DJ protocol through managed VJ into real Paper, admin, and preview.
- `scripts/release/test_e2e_release.py` — deterministic source, JSON/SBE, browser, and negative-path harness tests.
- `scripts/release/performance_gate.py` — statistics, warm-up, resource, latency, allocation, and threshold evaluator.
- `scripts/release/test_performance_gate.py` — percentile, slope, sample-count, threshold, and incomplete-run tests.
- `scripts/release/windows_dj_matrix.ps1` — signed NSIS/MSI install, upgrade, rollback, launch, stream, and uninstall harness.
- `dj_client/src-tauri/src/audio/test_source.rs` — deterministic release-only audio source enabled by a compile-time test feature.

### Workflows and evidence

- `.github/workflows/release.yml` — exact-commit orchestrator that creates a draft candidate only after all required jobs pass.
- `.github/workflows/release-runtime.yml` — reusable native matrix for the three managed runtime archives.
- `.github/workflows/release-paper-matrix.yml` — reusable real-Paper clean-install/upgrade/rollback matrix.
- `.github/workflows/release-performance.yml` — protected constrained-host performance job.
- `.github/workflows/release-dj-client.yml` — signed Windows NSIS/MSI build and clean-machine matrix.
- `.github/workflows/release-verify.yml` — independently downloads and verifies candidate bytes.
- `.github/workflows/ci.yml` and `.github/workflows/security.yml` — include new tests, lock files, scanners, and secret checks.
- `.github/rulesets/phase0-release-tags.json` — stays fail-closed until Task 8 and then describes the verified `v*` release path with no general bypass.
- `docs/superpowers/reports/2026-08-31-mcav-1.2.0-rc-verification.md` — exact commit, digest, command, environment, metric, and gate ledger.

### Operator documentation

- `docs/deployment/PAPER.md` — five-step install, setup, host panel, TLS, commands, upgrade, rollback, uninstall, and recovery.
- `docs/deployment/PTERODACTYL.md` — reversible migration and retained legacy path.
- `docs/RELEASE_VERIFICATION.md` — GPG, Ed25519, checksum, SBOM, and attestation verification.
- `docs/CONNECTIVITY.md` — one-port public TLS topology and loopback-only services.
- `README.md` — production Paper quick start and separated source-development path.
- `SECURITY.md` — trust keys, credential boundaries, reporting, rotation, and artifact verification.
- `CHANGELOG.md` — 1.2.0 operator-visible changes, compatibility, migration, and known limits.

### Task 1: Freeze the 1.2.0 version and artifact contract

**Files:**
- Create: `release/product-version.json`
- Create: `release/artifacts-v1.json`
- Create: `scripts/release/verify_versions.py`
- Create: `scripts/release/test_verify_versions.py`
- Modify: `package.json`
- Modify: `package-lock.json`
- Modify: `vj_server/pyproject.toml`
- Modify: `minecraft_plugin/pom.xml`
- Modify: `minecraft_mod/build.gradle`
- Modify: `dj_client/package.json`
- Modify: `dj_client/package-lock.json`
- Modify: `dj_client/src-tauri/Cargo.toml`
- Modify: `dj_client/src-tauri/Cargo.lock`
- Modify: `dj_client/src-tauri/tauri.conf.json`

**Interfaces:**
- Consumes: one product version and explicit independent runtime API/protocol versions.
- Produces: exact release filenames and a verifier used by local and CI release gates.

- [ ] **Step 1: Write failing version-contract tests**

Cover exact stable and RC forms, all seven ecosystem locations, unchanged runtime/protocol API versions, Minecraft/Paper/Java compatibility, duplicate artifact names, undeclared artifacts, and expected filenames:

```text
AudioViz-1.2.0.jar
mcav-vj-runtime-1.2.0-linux-x86_64.zip
mcav-vj-runtime-1.2.0-linux-aarch64.zip
mcav-vj-runtime-1.2.0-windows-x86_64.zip
mcav-runtime-manifest-v1.json
mcav-runtime-manifest-v1.json.sig
MCAV-DJ-Client-1.2.0-setup.exe
MCAV-DJ-Client-1.2.0.msi
audioviz-fabric-1.2.0.jar
SHA256SUMS.txt
SHA256SUMS.txt.asc
```

- [ ] **Step 2: Run the tests and confirm the contract is absent**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_verify_versions.py -q'
```

Expected: FAIL because the contract and verifier do not exist.

- [ ] **Step 3: Implement strict contract parsing and version convergence**

Reject unknown fields, missing ecosystems, version drift, invalid prerelease forms, and release names not declared by `artifacts-v1.json`. Update lockfiles only through their package managers. Keep `createUpdaterArtifacts` false and add an assertion for it.

- [ ] **Step 4: Run component metadata and build checks**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_verify_versions.py -q; .release-venv/bin/python scripts/release/verify_versions.py --version 1.2.0'
npm ci
npm run build
npm --prefix dj_client ci
npm --prefix dj_client run build
Set-Location minecraft_plugin
.\mvnw.cmd -q verify
Set-Location ..\minecraft_mod
.\gradlew.bat build
Set-Location ..\dj_client\src-tauri
cargo test
```

Expected: PASS; `rg -n 'createUpdaterArtifacts.*true|tauri-plugin-updater|danger_accept_invalid' dj_client` returns no match.

- [ ] **Step 5: Commit the version contract**

```powershell
git status --short
git diff -- release scripts/release package.json package-lock.json vj_server/pyproject.toml minecraft_plugin/pom.xml minecraft_mod/build.gradle dj_client/package.json dj_client/package-lock.json dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock dj_client/src-tauri/tauri.conf.json
git add -- release/product-version.json release/artifacts-v1.json scripts/release/verify_versions.py scripts/release/test_verify_versions.py package.json package-lock.json vj_server/pyproject.toml minecraft_plugin/pom.xml minecraft_mod/build.gradle dj_client/package.json dj_client/package-lock.json dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock dj_client/src-tauri/tauri.conf.json
git commit -m "chore(release): converge MCAV 1.2.0 versions"
```

### Task 2: Build deterministic native runtime archives

**Files:**
- Modify: `deploy/runtime/runtime-lock.json`
- Create: `deploy/runtime/build_release_runtime.py`
- Create: `deploy/runtime/verify_release_runtime.py`
- Create: `deploy/runtime/test_release_builder.py`
- Create: `deploy/runtime/invoke-locked-python.ps1`
- Modify: `deploy/runtime/build-managed-runtime.sh`
- Modify: `deploy/runtime/build-managed-runtime.ps1`
- Modify: `deploy/pterodactyl/build-runtime.sh`
- Modify: `deploy/pterodactyl/runtime-lock.json`
- Modify: `deploy/pterodactyl/test-build-release.sh`

**Interfaces:**
- Consumes: the shared immutable runtime lock and target platform.
- Produces: normalized archives containing the launcher, standalone Python, locked wheels, VJ code, Lua patterns, admin/preview assets, licenses, and signed-manifest `files.json` inputs.

- [ ] **Step 1: Extend builder tests before release-builder code**

Cover unknown lock fields, URL/hash/size requirements, wheel completeness, platform-tag mismatch, native-machine enforcement, undeclared files, symlinks, executable flags, normalized paths/timestamps, two-build digest equality, tampering, import smoke, one-port startup, and Pterodactyl layout compatibility.

- [ ] **Step 2: Confirm native release builds are unavailable**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest deploy/runtime/test_release_builder.py -q'
```

Expected: FAIL because release-native build and verification entrypoints are absent.

- [ ] **Step 3: Implement the native builder and verifier**

Require the running machine to match the requested target. Download every toolchain and wheel to a bounded cache, verify SHA-256 before use, install offline with `--require-hashes --no-deps`, copy an allowlisted product file set, compile bytecode, remove caches/tests/build records, generate sorted `files.json`, normalize ZIP timestamps to `SOURCE_DATE_EPOCH`, and reject output that changes between two clean builds on the same runner. The Windows bootstrap invokes only the verified standalone Python from the lock and never calls `py`, `python`, `pip`, or a machine-installed interpreter.

The Windows archive carries `.exe` launchers and explicit executable metadata in `files.json`; Linux archives carry normalized mode bits. Each archive starts under an empty temporary state directory and passes managed readiness, TLS health, shutdown, and no-orphan smoke checks.

- [ ] **Step 4: Build and verify on all native platforms**

Linux AMD64 runner:

```bash
python deploy/runtime/build_release_runtime.py --target linux-x86_64 --version 1.2.0 --output dist/runtime
python deploy/runtime/verify_release_runtime.py dist/runtime/mcav-vj-runtime-1.2.0-linux-x86_64.zip --target linux-x86_64
python -m pytest deploy/runtime/test_release_builder.py -q
```

Linux ARM64 runner uses the same commands with `linux-aarch64`. Windows AMD64 runner uses:

```powershell
.\deploy\runtime\invoke-locked-python.ps1 deploy/runtime/build_release_runtime.py --target windows-x86_64 --version 1.2.0 --output dist/runtime
.\deploy\runtime\invoke-locked-python.ps1 deploy/runtime/verify_release_runtime.py dist/runtime/mcav-vj-runtime-1.2.0-windows-x86_64.zip --target windows-x86_64
.\deploy\runtime\invoke-locked-python.ps1 -m pytest deploy/runtime/test_release_builder.py -q
```

Expected: each runner emits exactly one archive and a matching unpacked file inventory; two clean builds on that runner have equal SHA-256.

- [ ] **Step 5: Verify Pterodactyl regression compatibility**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; bash deploy/pterodactyl/test-build-release.sh'
```

Expected: PASS with the legacy entrypoint and layout unchanged.

- [ ] **Step 6: Commit runtime release builders**

```powershell
git status --short
git diff -- deploy/runtime deploy/pterodactyl
git add -- deploy/runtime/runtime-lock.json deploy/runtime/build_release_runtime.py deploy/runtime/verify_release_runtime.py deploy/runtime/test_release_builder.py deploy/runtime/invoke-locked-python.ps1 deploy/runtime/build-managed-runtime.sh deploy/runtime/build-managed-runtime.ps1 deploy/pterodactyl/build-runtime.sh deploy/pterodactyl/runtime-lock.json deploy/pterodactyl/test-build-release.sh
git commit -m "build(runtime): produce verified native archives"
```

### Task 3: Enforce production signing, SBOM, and provenance policy

**Files:**
- Create: `release/keys/runtime-prod-1.pub.der.b64`
- Create: `release/policy/release-policy.json`
- Create: `scripts/release/check_signing_environment.py`
- Create: `scripts/release/assemble_candidate.py`
- Create: `scripts/release/verify_candidate.py`
- Create: `scripts/release/test_candidate_tools.py`
- Create: `scripts/release/generate_sboms.sh`
- Create: `scripts/release/generate_sboms.ps1`
- Modify: `minecraft_plugin/pom.xml`
- Modify: `minecraft_plugin/src/main/resources/runtime/release-descriptor.properties`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: exact tested artifact digests plus protected `MCAV_RUNTIME_ED25519_PRIVATE_KEY_B64`, `MCAV_GPG_PRIVATE_KEY_B64`, `MCAV_GPG_PASSPHRASE`, `MCAV_WINDOWS_SIGNING_PFX_B64`, and `MCAV_WINDOWS_SIGNING_PFX_PASSWORD` secrets.
- Produces: production-signed runtime manifest, detached GPG checksum signature, Authenticode-verifiable installers, per-artifact CycloneDX SBOMs, provenance subjects, and an offline-verifiable candidate directory.

- [ ] **Step 1: Write failing policy and tamper tests**

Use fixture credentials only in tests. Cover production key-ID rejection of fixture keys, public/private mismatch, missing secrets, wrong GPG fingerprint, wrong Authenticode subject/thumbprint, extra/missing artifact, duplicate name, checksum tamper, manifest-byte tamper, archive-member tamper, SBOM subject mismatch, attestation-subject mismatch, unlisted license, secret-shaped output, and a successful fully signed fixture candidate.

- [ ] **Step 2: Confirm no production candidate can be assembled**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_candidate_tools.py -q'
```

Expected: FAIL because policy, production key, and candidate tools are absent.

- [ ] **Step 3: Provision the runtime public key without exposing the private key**

Pause for the repository owner to authorize a private-key custody location outside the repository or provision the protected release secret directly. Import only the Base64 X.509 SubjectPublicKeyInfo bytes into `release/keys/runtime-prod-1.pub.der.b64`. Add ignore rules for `*.pk8`, `*.pem`, `*.pfx`, secret exports, local candidate credentials, and signing scratch directories. Verify the release descriptor embeds the exact public key and key ID `runtime-prod-1` under the Maven release profile.

- [ ] **Step 4: Implement fail-closed assembly and independent verification**

Assembly accepts an input directory plus expected digest ledger, refuses to execute a build, verifies all names and bytes, emits canonical manifest/checksums, invokes signer adapters with inherited secret descriptors, and redacts process output. Verification uses only committed public identities and downloaded candidate bytes. It checks every archive member against `files.json`, maps each artifact to one SBOM and provenance subject, and rejects anything not declared in `release/artifacts-v1.json`.

- [ ] **Step 5: Generate and validate component SBOMs**

Generate CycloneDX JSON for the shaded Paper JAR, Fabric JAR, each Python runtime archive, each DJ installer payload, and bundled web assets. Normalize component names/versions, include license evidence, and fail on missing or ambiguous top-level components.

- [ ] **Step 6: Run signing-policy checks with fixture credentials**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_candidate_tools.py -q; .release-venv/bin/python scripts/release/verify_candidate.py --policy release/policy/release-policy.json --candidate build/fixture-candidate --fixture-mode'
Set-Location minecraft_plugin
.\mvnw.cmd -Prelease -q verify
```

Expected: PASS. A production-mode call with fixture keys exits nonzero with reason `SIGNING_KEY_NOT_PRODUCTION`.

- [ ] **Step 7: Commit the trust and assembly policy**

```powershell
git status --short
git diff -- release scripts/release minecraft_plugin/pom.xml minecraft_plugin/src/main/resources/runtime/release-descriptor.properties .gitignore
git add -- release/keys/runtime-prod-1.pub.der.b64 release/policy/release-policy.json scripts/release/check_signing_environment.py scripts/release/assemble_candidate.py scripts/release/verify_candidate.py scripts/release/test_candidate_tools.py scripts/release/generate_sboms.sh scripts/release/generate_sboms.ps1 minecraft_plugin/pom.xml minecraft_plugin/src/main/resources/runtime/release-descriptor.properties .gitignore
git commit -m "feat(release): enforce signed candidate policy"
```

### Task 4: Prove clean install, upgrade, rollback, and lifecycle on real Paper

**Files:**
- Create: `deploy/paper/paper-lock.json`
- Create: `scripts/release/paper_matrix.py`
- Create: `scripts/release/test_paper_matrix.py`
- Create: `scripts/release/fixtures/paper-matrix/README.md`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/runtime/PaperRuntimeManagerTest.java`
- Modify: `minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeSupervisorTest.java`

**Interfaces:**
- Consumes: one release plugin JAR, two signed runtime versions from a local TLS release origin, supported Paper/Java lock, and platform fault controls.
- Produces: machine-readable evidence for every install and lifecycle gate with logs, digests, state snapshots, process trees, and secret-redaction assertions.

- [ ] **Step 1: Write failing harness tests**

Cover unique workspaces, Paper EULA fixture, bounded startup/shutdown, local signed HTTPS origin, setup completion, exact state snapshots, network-off mode, truncated/corrupt/hostile artifacts, port occupation, process crash/hang, forced JVM exit, process-tree checks, state retention, and evidence completeness. Harness tests must fail if a scenario is skipped or reports only textual success.

- [ ] **Step 2: Confirm the real-Paper matrix is absent**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_paper_matrix.py -q'
```

Expected: FAIL because the Paper lock and harness do not exist.

- [ ] **Step 3: Implement isolated real-Paper scenarios**

For every target, create a new bounded temporary workspace, verify the locked Paper JAR before launch, place only `AudioViz-1.2.0.jar` in `plugins/`, allocate loopback renderer/public ports, and expose the release fixture through TLS. Never copy a source tree or Python interpreter into the Paper workspace.

Run these release-blocking scenarios: clean install, authenticated onboarding, second boot with byte-stable identity and zero artifact requests, offline restart, compatible upgrade with state preservation, failed-candidate automatic rollback, manual offline rollback, public-port conflict, renderer-port conflict, five-crash circuit, manual retry/circuit recovery, graceful Paper shutdown, forced JVM termination with no surviving runtime, plugin disable, and uninstall with administrator data retained.

- [ ] **Step 4: Execute the native platform matrix**

```bash
python scripts/release/paper_matrix.py --platform linux-x86_64 --paper-lock deploy/paper/paper-lock.json --plugin dist/AudioViz-1.2.0.jar --runtime-dir dist/runtime --evidence build/evidence/paper-linux-x86_64.json
```

Repeat on Linux ARM64 with `linux-aarch64`. On Windows AMD64:

```powershell
.\deploy\runtime\invoke-locked-python.ps1 scripts/release/paper_matrix.py --platform windows-x86_64 --paper-lock deploy/paper/paper-lock.json --plugin dist/AudioViz-1.2.0.jar --runtime-dir dist/runtime --evidence build/evidence/paper-windows-x86_64.json
```

Expected: every scenario is `pass`, every process-tree assertion reports zero surviving children, and secret scans report zero findings.

- [ ] **Step 5: Commit the Paper release matrix**

```powershell
git status --short
git diff -- deploy/paper scripts/release minecraft_plugin/src/test/java/com/audioviz/runtime
git add -- deploy/paper/paper-lock.json scripts/release/paper_matrix.py scripts/release/test_paper_matrix.py scripts/release/fixtures/paper-matrix/README.md minecraft_plugin/src/test/java/com/audioviz/runtime/PaperRuntimeManagerTest.java minecraft_plugin/src/test/java/com/audioviz/runtime/supervisor/RuntimeSupervisorTest.java
git commit -m "test(release): gate real Paper lifecycle"
```

### Task 5: Gate packaged end-to-end behavior and minimum-host performance

**Files:**
- Create: `scripts/release/e2e_release.py`
- Create: `scripts/release/test_e2e_release.py`
- Create: `scripts/release/performance_gate.py`
- Create: `scripts/release/test_performance_gate.py`
- Create: `configs/release/standard-160-entity-show.json`
- Modify: `dj_client/src-tauri/src/audio/mod.rs`
- Create: `dj_client/src-tauri/src/audio/test_source.rs`
- Modify: `dj_client/src-tauri/Cargo.toml`
- Modify: `dj_client/src-tauri/Cargo.lock`
- Modify: `vj_server/metrics.py`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeDiagnostics.java`

**Interfaces:**
- Consumes: assembled unsigned candidate bytes, deterministic 48 kHz test audio, real Paper, packaged VJ, and a 4-vCPU/8-GiB constrained host.
- Produces: protocol/state assertions plus raw and evaluated latency, TPS, memory, CPU, allocation, payload, queue, reconnect, and load-shedding evidence.

- [ ] **Step 1: Write failing deterministic end-to-end and statistics tests**

Cover generated audio frame values, connect-code expiry/use, certificate fingerprint pinning, VJ bands/beats, negotiated SBE, forced JSON fallback, zone application in the same drain, preview state, authenticated admin pattern change, disconnect, and absence of secret leakage.

Statistics tests cover p50/p95/p99/max, warm-up exclusion, monotonic timestamps, at least 500 frames per protocol, missing samples, Paper TPS threshold, VJ loop thresholds, RSS ceiling, least-squares memory slope, plugin allocation count, payload ratio, and threshold-edge behavior.

- [ ] **Step 2: Confirm the release E2E and performance gates are absent**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_e2e_release.py scripts/release/test_performance_gate.py -q'
```

Expected: FAIL because harnesses and deterministic packaged audio do not exist.

- [ ] **Step 3: Add compile-time deterministic DJ audio**

Add Cargo feature `release-test-source` that feeds a fixed seeded 48 kHz PCM sequence through the production FFT/beat/protocol path. Exclude the feature from normal release binaries and make `verify_candidate.py` reject production DJ executables containing the test-source marker.

- [ ] **Step 4: Implement packaged end-to-end assertions**

Start real Paper with the release plugin, install the packaged runtime through the signed local origin, finish onboarding, issue one certificate-bound connect code, launch the DJ protocol binary, and assert all stages through Minecraft and browser clients. Execute once with SBE negotiation and once with forced JSON compatibility.

- [ ] **Step 5: Implement and run the constrained-host gate**

Limit the complete Paper/VJ workload to 4 vCPU and 8 GiB. After warm-up, run `standard-160-entity-show.json` for 30 minutes with one admin and one preview client. Require:

- Paper TPS at least 19.5;
- VJ main-loop work at most 40 ms p95 and 50 ms p99;
- VJ peak RSS at most 1.5 GiB;
- fitted RSS growth below 1 MiB/minute over the final 20 minutes;
- 256-entity SBE decode/validation/publication at most 1 ms p95;
- plugin main-thread render at most 3 ms p95;
- SBE payload at most 25 percent of equivalent JSON;
- zero per-entity steady-state binary decode allocations; and
- no extra scheduler tick between pre-drain frame availability and application.

```bash
python scripts/release/e2e_release.py --candidate build/candidate --paper-lock deploy/paper/paper-lock.json --evidence build/evidence/e2e.json
python scripts/release/performance_gate.py run --candidate build/candidate --cpus 4 --memory 8g --duration 30m --show configs/release/standard-160-entity-show.json --raw build/evidence/performance-raw.json
python scripts/release/performance_gate.py evaluate --raw build/evidence/performance-raw.json --report build/evidence/performance.json
```

Expected: PASS with raw samples retained. A killed or incomplete run fails rather than reusing older evidence.

- [ ] **Step 6: Run component regressions**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/vj_server; .venv/bin/python -m pytest tests -q'
Set-Location minecraft_plugin
.\mvnw.cmd -q verify
Set-Location ..\dj_client\src-tauri
cargo fmt --check
cargo test
```

Expected: PASS.

- [ ] **Step 7: Commit E2E and performance gates**

```powershell
git status --short
git diff -- scripts/release configs/release dj_client/src-tauri vj_server/metrics.py minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeDiagnostics.java
git add -- scripts/release/e2e_release.py scripts/release/test_e2e_release.py scripts/release/performance_gate.py scripts/release/test_performance_gate.py configs/release/standard-160-entity-show.json dj_client/src-tauri/src/audio/mod.rs dj_client/src-tauri/src/audio/test_source.rs dj_client/src-tauri/Cargo.toml dj_client/src-tauri/Cargo.lock vj_server/metrics.py minecraft_plugin/src/main/java/com/audioviz/runtime/RuntimeDiagnostics.java
git commit -m "test(release): gate packaged performance and E2E"
```

### Task 6: Build and test signed Windows DJ installers

**Files:**
- Modify: `.github/workflows/release-dj-client.yml`
- Create: `scripts/release/windows_dj_matrix.ps1`
- Create: `scripts/release/Test-WindowsDjMatrix.ps1`
- Modify: `dj_client/src-tauri/tauri.conf.json`
- Modify: `dj_client/tests/phase0-containment.test.mjs`
- Modify: `release/policy/release-policy.json`

**Interfaces:**
- Consumes: exact candidate commit and protected Authenticode credential.
- Produces: signed NSIS EXE and WiX MSI plus clean-Windows install/upgrade/rollback/uninstall and deterministic-stream evidence.

- [ ] **Step 1: Extend containment and PowerShell harness tests**

Require `createUpdaterArtifacts` false, no updater dependency/endpoint/key, both NSIS and MSI targets, expected publisher/product/version, RFC 3161 timestamping, signature verification before execution, clean install, launch, pinned server trust, deterministic stream, disconnect, explicit upgrade, explicit older-package rollback, uninstall, and zero retained connect codes. Tests reject unsigned packages and signatures whose subject/thumbprint differs from policy.

- [ ] **Step 2: Confirm the DJ publisher remains quarantined**

```powershell
npm --prefix dj_client run test:containment
.\scripts\release\Test-WindowsDjMatrix.ps1
```

Expected: FAIL for release behavior because the workflow contains only `MCAV_PHASE0_RELEASE_DISABLED`.

- [ ] **Step 3: Replace the fail-only workflow with signed build and clean-machine gates**

Use a Windows runner to build `x86_64-pc-windows-msvc` once, create both installers, Authenticode-sign each executable payload and outer installer with SHA-256 plus RFC 3161 timestamp, verify signatures with `Get-AuthenticodeSignature` and `signtool verify /pa /all`, and upload by digest. `Test-WindowsDjMatrix.ps1` uses self-contained assertion functions and no gallery modules. Run the matrix on a fresh Windows test image before the workflow exposes artifacts to the release orchestrator.

Do not enable Tauri updater artifacts or add an update endpoint. Keep `workflow_dispatch` limited to candidate SHA/version inputs until the unified release orchestrator calls the reusable workflow.

- [ ] **Step 4: Run local non-signing tests and protected signing smoke**

```powershell
npm --prefix dj_client run test:containment
.\scripts\release\Test-WindowsDjMatrix.ps1
npm --prefix dj_client run build
npm --prefix dj_client test
```

Protected runner:

```powershell
.\scripts\release\windows_dj_matrix.ps1 -NsisPath dist\MCAV-DJ-Client-1.2.0-setup.exe -MsiPath dist\MCAV-DJ-Client-1.2.0.msi -ServerCandidate build\candidate -EvidencePath build\evidence\windows-dj.json
```

Expected: PASS with both signatures valid at verification time and every lifecycle scenario recorded.

- [ ] **Step 5: Commit signed DJ distribution gates**

```powershell
git status --short
git diff -- .github/workflows/release-dj-client.yml scripts/release dj_client/src-tauri/tauri.conf.json dj_client/tests/phase0-containment.test.mjs release/policy/release-policy.json
git add -- .github/workflows/release-dj-client.yml scripts/release/windows_dj_matrix.ps1 scripts/release/Test-WindowsDjMatrix.ps1 dj_client/src-tauri/tauri.conf.json dj_client/tests/phase0-containment.test.mjs release/policy/release-policy.json
git commit -m "build(dj): gate signed Windows installers"
```

### Task 7: Publish operator installation, migration, recovery, and verification docs

**Files:**
- Create: `docs/deployment/PAPER.md`
- Modify: `docs/deployment/PTERODACTYL.md`
- Create: `docs/RELEASE_VERIFICATION.md`
- Modify: `docs/CONNECTIVITY.md`
- Modify: `README.md`
- Modify: `SECURITY.md`
- Modify: `CHANGELOG.md`
- Create: `scripts/release/test_release_docs.py`

**Interfaces:**
- Consumes: final commands, configuration keys, reason codes, trust identities, compatibility, and artifact names.
- Produces: one fresh-admin path requiring no Python, shell script, Docker, source checkout, or modified Paper command.

- [ ] **Step 1: Write failing documentation contract tests**

Require the five canonical install steps, one public port, Java/Paper/OS/CPU/memory/disk requirements, first-run fingerprint flow, reverse proxy/certificate guidance, `status`, `runtime check`, `runtime retry`, `runtime rollback`, diagnostics, offline behavior, retention, Pterodactyl copy-only migration, automatic/manual rollback, emergency recovery, uninstall retention, load shedding, signature/SBOM/attestation verification, DJ updater-disabled disclosure, Docker quarantine, and every production reason code.

Reject the old source-build VJ path as the primary README installation path and reject claims that Fabric self-installs the runtime.

- [ ] **Step 2: Confirm the operator path is incomplete**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_release_docs.py -q'
```

Expected: FAIL because Paper deployment and release-verification documents do not exist.

- [ ] **Step 3: Write and cross-link exact operator procedures**

Use `AudioViz-1.2.0.jar` in examples, include signature verification before installation, describe self-signed fingerprint handling without trust bypass, and keep development commands in a separate contributor section. The Pterodactyl migration copies identity/content data after validation, never imports executables, and keeps the old directory intact for rollback.

- [ ] **Step 4: Verify docs against schemas, commands, config, and artifact policy**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_release_docs.py -q; .release-venv/bin/python scripts/release/verify_versions.py --version 1.2.0'
rg -n "pip install -e vj_server|docker compose up|automatic updater" README.md docs/deployment/PAPER.md docs/RELEASE_VERIFICATION.md
```

Expected: tests pass. Search results occur only in clearly labeled development, migration, quarantine, or updater-disabled sections.

- [ ] **Step 5: Commit release documentation**

```powershell
git status --short
git diff -- README.md SECURITY.md CHANGELOG.md docs scripts/release/test_release_docs.py
git add -- README.md SECURITY.md CHANGELOG.md docs/deployment/PAPER.md docs/deployment/PTERODACTYL.md docs/RELEASE_VERIFICATION.md docs/CONNECTIVITY.md scripts/release/test_release_docs.py
git commit -m "docs: publish self-installing Paper operations"
```

### Task 8: Build an exact-commit, fail-closed draft-release workflow

**Files:**
- Modify: `.github/workflows/release.yml`
- Create: `.github/workflows/release-runtime.yml`
- Create: `.github/workflows/release-paper-matrix.yml`
- Create: `.github/workflows/release-performance.yml`
- Create: `.github/workflows/release-verify.yml`
- Modify: `.github/workflows/release-dj-client.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/security.yml`
- Modify: `.github/rulesets/phase0-release-tags.json`
- Create: `scripts/release/test_release_workflows.py`

**Interfaces:**
- Consumes: exact protected-main candidate SHA, release candidate version, protected signing environments, and all reusable gate outputs.
- Produces: one draft GitHub release whose assets, signatures, evidence, SBOMs, and attestations match the independently verified candidate ledger.

- [ ] **Step 1: Write failing workflow-policy tests**

Parse workflow YAML structurally. Require exact-main provenance, immutable action SHAs for release-critical third-party actions, least-privilege job permissions, native runner labels, artifact digest handoff, required CI/security checks for the same SHA, protected signing environments, no rebuild after testing, no `continue-on-error`, no shell success bypass, no mutable download, no public release before independent verification, and no Docker publication.

Require all jobs: version check; Paper/Fabric build; three runtime builds; Pterodactyl regression build; server platform matrix; E2E; 30-minute performance; Windows DJ installers/matrix; audits/SAST/licenses/secrets; SBOM/attestation; signing; independent download verification; draft assembly.

- [ ] **Step 2: Confirm current workflows fail release policy**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_release_workflows.py -q'
```

Expected: FAIL because the current unified workflow publishes directly and the DJ workflow is fail-only.

- [ ] **Step 3: Implement reusable native and system-test workflows**

Each reusable workflow checks out the supplied immutable SHA, verifies it is the workflow run SHA, builds or downloads only declared artifacts, records SHA-256 outputs, and uploads evidence with nonzero retention. ARM runtime and Paper work execute on an ARM64 runner. Performance runs on a dedicated runner whose resource controls and host identity are included in evidence.

- [ ] **Step 4: Implement protected assembly and independent verification**

The orchestrator accepts `1.2.0-rc.N`, requires successful CI/security push runs for the same protected-main SHA, and invokes every reusable job. A signing job in the protected `production-release-signing` environment downloads tested artifacts by digest, signs them, generates SBOMs/attestations, and uploads the candidate ledger. A separate job with no signing secrets downloads candidate assets into a fresh workspace and runs `verify_candidate.py` plus package smokes.

Create only a draft prerelease after verification. The workflow never marks a stable release public. Stable publication remains a user-authorized repository operation against the verified candidate ledger.

- [ ] **Step 5: Replace Phase 0 tag controls only after dry-run evidence passes**

Keep the no-bypass ruleset active while testing workflows through explicit candidate dispatch. Once all dry-run jobs pass, update the checked-in ruleset description and tag scope to permit only the protected unified `v1.2.0-rc.*`/`v1.2.0` path configured in repository settings. Retain update, deletion, and non-fast-forward prohibitions, and retain `dj-v*`, `plugin-v*`, and `mod-v*` quarantine. Export and compare the live ruleset before considering this step complete.

- [ ] **Step 6: Run local workflow, containment, and security assertions**

```powershell
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python -m pytest scripts/release/test_release_workflows.py scripts/release/test_candidate_tools.py -q'
npm --prefix dj_client run test:containment
git diff --check
```

Expected: PASS; `rg -n "continue-on-error|\|\| true|draft: false|ghcr.io" .github/workflows/release*.yml` returns no release-gate bypass or public publication.

- [ ] **Step 7: Commit release orchestration**

```powershell
git status --short
git diff -- .github/workflows .github/rulesets/phase0-release-tags.json scripts/release/test_release_workflows.py
git add -- .github/workflows/release.yml .github/workflows/release-runtime.yml .github/workflows/release-paper-matrix.yml .github/workflows/release-performance.yml .github/workflows/release-verify.yml .github/workflows/release-dj-client.yml .github/workflows/ci.yml .github/workflows/security.yml .github/rulesets/phase0-release-tags.json scripts/release/test_release_workflows.py
git commit -m "ci(release): gate signed MCAV candidates"
```

### Task 9: Assemble and audit the publication-ready candidate

**Files:**
- Create: `docs/superpowers/reports/2026-08-31-mcav-1.2.0-rc-verification.md`
- Modify: `CHANGELOG.md`
- Modify: `release/product-version.json`

**Interfaces:**
- Consumes: one exact protected-main commit and all signed candidate artifacts/evidence from that commit.
- Produces: a publication-ready `1.2.0-rc.N` candidate, immutable digest ledger, draft release metadata, and committed verification report.

- [ ] **Step 1: Run the complete local preflight from a clean release worktree**

```powershell
git status --short
wsl.exe bash -lc 'set -e; cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz; .release-venv/bin/python scripts/release/verify_versions.py --version 1.2.0; .release-venv/bin/python -m pytest scripts/release -q; cd vj_server; .venv/bin/python -m pytest tests -q'
Set-Location minecraft_plugin
.\mvnw.cmd -q verify
Set-Location ..\minecraft_mod
.\gradlew.bat build
Set-Location ..\dj_client
npm ci
npm test
npm run build
Set-Location src-tauri
cargo fmt --check
cargo test
cargo audit
```

Expected: PASS with a clean worktree except generated ignored outputs.

- [ ] **Step 2: Dispatch the release-candidate workflow for the exact main SHA**

Use candidate version `1.2.0-rc.1`. Record workflow run IDs and wait for every required job. Do not retry a failed job by substituting artifacts from another SHA; fix the cause and create a new RC number.

- [ ] **Step 3: Download and independently verify the draft candidate**

```bash
python scripts/release/verify_candidate.py --policy release/policy/release-policy.json --candidate downloaded-candidate --require-production --require-attestations
python scripts/release/paper_matrix.py --smoke-only --plugin downloaded-candidate/AudioViz-1.2.0.jar --runtime-dir downloaded-candidate
```

Expected: PASS with the ledger SHA matching every downloaded byte and every attestation subject.

- [ ] **Step 4: Audit specification coverage and evidence completeness**

The report records commit SHA, toolchain/runner identity, artifact SHA-256 values, signer identities, SBOM names, attestation subjects, Paper scenarios by platform, Windows DJ scenarios, E2E assertions, performance p50/p95/p99/max/CPU/RSS/slope/allocation/payload results, security/audit results, Pterodactyl regression, documentation checks, live ruleset comparison, and unresolved limitations.

Cross-check every acceptance criterion in the approved design. Any criterion without exact evidence keeps the candidate incomplete.

- [ ] **Step 5: Run a final code and release review**

Use `superpowers:requesting-code-review` for the complete implementation range. Resolve every release-blocking finding, rerun affected gates, and update the evidence ledger without reusing stale outputs.

- [ ] **Step 6: Commit the release evidence**

```powershell
git status --short
git diff -- docs/superpowers/reports/2026-08-31-mcav-1.2.0-rc-verification.md CHANGELOG.md release/product-version.json
git add -- docs/superpowers/reports/2026-08-31-mcav-1.2.0-rc-verification.md CHANGELOG.md release/product-version.json
git commit -m "docs(release): verify MCAV 1.2.0 candidate"
```

- [ ] **Step 7: Stop at the publication boundary**

Present the verified candidate version, commit, draft release, artifact ledger, signature identities, and evidence report to the user. Create or publish the stable tag/release only after the user explicitly authorizes public publication. If authorized, publish the already verified bytes; do not rebuild.

## Program Completion Gate

Before declaring the self-installing release ready:

- [ ] All four implementation plans have every checkbox complete.
- [ ] `git diff --check` passes and the intended release worktree is clean.
- [ ] Unit, component, property/fuzz, integration, security, audit, build, Paper, Windows, E2E, performance, Pterodactyl, signing, and independent-verification gates pass.
- [ ] The exact candidate commit is reachable from protected `main` and all evidence names that SHA.
- [ ] All declared artifacts exist once, match the ledger, carry required signatures/SBOMs/attestations, and contain no production secrets or test-source feature.
- [ ] Linux AMD64, Linux ARM64, and Windows AMD64 prove clean install, offline restart, upgrade, automatic rollback, manual rollback, lifecycle containment, and state retention.
- [ ] A fresh Paper server reaches authenticated admin/DJ/preview operation from only the plugin JAR and one assigned public port.
- [ ] The 4-vCPU/8-GiB 30-minute gate meets every approved TPS, latency, memory, allocation, and payload threshold.
- [ ] The automatic DJ updater and Docker publication remain disabled.
- [ ] Documentation, migration, rollback, emergency recovery, trust verification, and known limitations match shipped behavior.
- [ ] The independent verifier passes after downloading the candidate from the draft release.
- [ ] Public publication has not occurred without explicit user authorization.
