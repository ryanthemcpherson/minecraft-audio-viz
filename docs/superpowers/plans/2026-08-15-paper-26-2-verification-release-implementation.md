# Paper 26.2 Verification and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the exact Paper 26.2 plugin on a disposable real server, measure its release gates, build it reproducibly once, attest it, and publish the identical bytes as `plugin-v1.1.0` after protected human approval.

**Architecture:** A Python harness owns a disposable Paper process and drives it through the production `VizClient`. Quick real-server checks run in CI; long performance and eight-hour soak checks run on the documented reference machine and commit their small evidence summary before candidate creation. A candidate workflow builds twice, verifies reproducibility, and stores/attests one artifact; a separate protected workflow downloads that candidate and creates the immutable tag without rebuilding.

**Tech Stack:** Paper 26.2 build 112, Java 25, Python 3.12, pytest, Maven, GitHub Actions, CycloneDX 2.9.1, `actions/attest`, GitHub CLI

**Spec:** `docs/superpowers/specs/2026-08-15-paper-26-2-release-design.md`

## Global Constraints

- Paper server test artifact is `paper-26.2-112.jar` with SHA-256 `bd3a58cf96874e5ea6643f5f6fe9b4f5bf9e34b795fa078c2f0ee8b98b2f907e`.
- Paper download URL is `https://fill-data.papermc.io/v1/objects/bd3a58cf96874e5ea6643f5f6fe9b4f5bf9e34b795fa078c2f0ee8b98b2f907e/paper-26.2-112.jar`.
- Candidate plugin artifact is `mcav-paper-1.1.0.jar` and tag is `plugin-v1.1.0`.
- Candidate and publication run from exact current `main`; publication never rebuilds.
- Java 25 is used for Paper/plugin jobs; Fabric jobs remain on Java 21 and remain quarantined.
- Real-server tests bind to loopback and never log the generated secret.
- The eight-hour soak is not shortened for final release evidence.
- No public compatibility claim is made for 1.21, Spigot, Purpur, Fabric, or Bedrock.
- Every action reference is a full 40-character SHA.

---

### Task 1: Build the disposable Paper 26.2 process harness

**Files:**
- Create: `scripts/release/paper_26_2_manifest.json`
- Create: `scripts/release/paper_harness.py`
- Create: `scripts/release/tests/test_paper_harness.py`

**Interfaces:**
- Consumes: Java 25 executable, pinned Paper server JAR, built plugin JAR
- Produces: `PaperServer` context manager with `start()`, `wait_for_log()`, `command()`, `stop()`, `restart()`, `pid`, and captured sanitized logs

- [ ] **Step 1: Add the immutable server manifest**

  ```json
  {
    "project": "paper",
    "minecraftVersion": "26.2",
    "build": 112,
    "channel": "STABLE",
    "file": "paper-26.2-112.jar",
    "sha256": "bd3a58cf96874e5ea6643f5f6fe9b4f5bf9e34b795fa078c2f0ee8b98b2f907e",
    "url": "https://fill-data.papermc.io/v1/objects/bd3a58cf96874e5ea6643f5f6fe9b4f5bf9e34b795fa078c2f0ee8b98b2f907e/paper-26.2-112.jar"
  }
  ```

- [ ] **Step 2: Write failing harness unit tests**

  Test SHA mismatch rejection, startup timeout, early-process-exit diagnostics, command marker matching, graceful stop with kill fallback, log redaction, and temp-directory cleanup. Use a fake subprocess rather than launching Paper.

  ```python
  def test_verify_download_rejects_wrong_hash(tmp_path: Path) -> None:
      server_jar = tmp_path / "paper.jar"
      server_jar.write_bytes(b"not paper")

      with pytest.raises(ValueError, match="Paper SHA-256 mismatch"):
          verify_sha256(server_jar, "0" * 64)
  ```

- [ ] **Step 3: Run tests and verify failure**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest scripts/release/tests/test_paper_harness.py -q'
  ```

- [ ] **Step 4: Implement safe download and process ownership**

  Use `urllib.request.urlopen`, stream into a temporary `.part` file, verify SHA-256, then atomically replace the cache file. `PaperServer` writes `eula.txt`, deterministic `server.properties`, copies the plugin to `plugins/`, starts `java -Xms1G -Xmx2G -jar paper.jar --nogui`, and reads merged stdout/stderr on a dedicated daemon reader thread.

  Default test properties include:

  ```properties
  online-mode=false
  server-ip=127.0.0.1
  server-port=25575
  spawn-protection=0
  view-distance=4
  simulation-distance=4
  enable-rcon=false
  motd=MCAV Paper 26.2 integration
  ```

- [ ] **Step 5: Implement log markers without secret exposure**

  `wait_for_log(pattern, timeout)` returns matching sanitized lines. `command(command, marker, timeout)` writes the command to stdin and waits for a unique marker. Redaction replaces both the resolved secret and JSON token values with `[REDACTED]` before keeping or reporting logs.

- [ ] **Step 6: Run unit tests and lint**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest scripts/release/tests/test_paper_harness.py -q && uvx ruff check scripts/release'
  ```

- [ ] **Step 7: Commit**

  ```powershell
  git add scripts/release/paper_26_2_manifest.json scripts/release/paper_harness.py scripts/release/tests/test_paper_harness.py
  git commit -m "test(plugin): add disposable Paper 26.2 harness"
  ```

---

### Task 2: Drive a real plugin/VJ lifecycle on Paper 26.2

**Files:**
- Create: `scripts/release/paper_e2e.py`
- Create: `scripts/release/tests/test_paper_e2e_contract.py`
- Create: `scripts/release/probe/pom.xml`
- Create: `scripts/release/probe/src/main/java/com/audioviz/probe/McavProbePlugin.java`
- Create: `scripts/release/probe/src/main/resources/plugin.yml`
- Modify: `scripts/release/paper_harness.py`

**Interfaces:**
- Consumes: `PaperServer`, production `vj_server.viz_client.VizClient`, plugin JAR
- Produces: JSON evidence for load, generated auth, entity rendering, reconnect cleanup/rehydration, malformed input survival, restart cleanup, and optional-integration absence

- [ ] **Step 1: Write failing E2E contract tests around a fake server/client**

  The orchestration test must require these result keys:

  ```python
  REQUIRED_CHECKS = {
      "plugin_loaded",
      "secret_generated",
      "bad_secret_rejected",
      "authenticated",
      "zone_loaded",
      "pool_initialized",
      "display_entities_applied",
      "malformed_frame_rejected",
      "oversize_frame_rejected",
      "reconnected",
      "disconnect_cleanup",
      "world_unload_cleanup",
      "restart_has_no_orphans",
      "port_conflict_safe",
      "clean_machine_install",
      "uninstall_cleanup",
      "optional_integrations_absent_safe",
  }
  ```

  Assert any false/missing check makes the CLI exit nonzero.

- [ ] **Step 2: Run contract tests and verify failure**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest scripts/release/tests/test_paper_e2e_contract.py -q'
  ```

- [ ] **Step 3: Seed a deterministic visualization zone**

  Before startup write `plugins/AudioViz/zones.yml`:

  ```yaml
  zones:
    main:
      name: main
      id: 00000000-0000-0000-0000-000000000001
      world: world
      origin: {x: 0.0, y: 80.0, z: 0.0}
      size: {x: 16.0, y: 16.0, z: 4.0}
      rotation: 0.0
  ```

- [ ] **Step 4: Implement the production-path scenario**

  Start Paper, parse the persisted `ws-secret` from the private temp server configuration without printing it, reject a `VizClient` using `wrong-secret`, authenticate the real client, assert `get_zones()` returns `main`, initialize 256 `GLOWSTONE` displays, send a representative five-band `batch_update_fast`, then poll `query_zone_status()` until `entity_count == 256`.

- [ ] **Step 5: Exercise failure and reconnect behavior**

  Use a raw authenticated WebSocket to send invalid JSON and then a frame larger than 262,144 characters, confirm both are rejected with sanitized errors, and verify a new authenticated client can still ping. Disconnect the VJ, wait `disconnect_grace_ticks + 20` ticks, assert zone entity count becomes zero, reconnect, initialize the pool again, and assert 256 entities return.

- [ ] **Step 6: Build a test-only world-unload probe plugin**

  The probe POM depends on Paper `26.2.build.112-stable` and installed `com.audioviz:audioviz-plugin:1.1.0` with `provided` scope. Its `mcavprobe unload-cycle` console command creates world `mcav_unload_probe`, gets the loaded `AudioVizPlugin`, calls `createZone("unload_probe", new Location(world, 0, 80, 0))`, initializes 16 block displays, unloads the world with `Bukkit.unloadWorld(world, false)`, and emits `MCAV_PROBE_WORLD_UNLOAD_CLEAN` only when `getEntityCount("unload_probe") == 0`. The probe is copied only into disposable test servers and is never included in the release bundle.

  ```yaml
  name: MCAVIntegrationProbe
  version: 1.0.0
  main: com.audioviz.probe.McavProbePlugin
  api-version: '26.2'
  depend: [AudioViz]
  commands:
    mcavprobe:
      permission: mcavprobe.run
  permissions:
    mcavprobe.run:
      default: op
  ```

- [ ] **Step 7: Prove world unload and shutdown cleanup**

  Build the plugin and probe with:

  ```bash
  ./minecraft_plugin/mvnw -f minecraft_plugin/pom.xml install -DskipTests
  ./minecraft_plugin/mvnw -f scripts/release/probe/pom.xml package
  ```

  Run `mcavprobe unload-cycle` and require `MCAV_PROBE_WORLD_UNLOAD_CLEAN`. Then stop Paper gracefully, restart the same world and plugin without initializing a pool, and send this console command:

  Stop Paper gracefully, restart the same world and plugin without initializing a pool, then send this console command:

  ```text
  execute unless entity @e[type=minecraft:block_display] run say MCAV_PROBE_ENTITY_ABSENT
  ```

  Require the `MCAV_PROBE_ENTITY_ABSENT` marker. Also require logs showing Geyser, Floodgate, and Simple Voice Chat are absent without plugin enable failure.

- [ ] **Step 8: Prove port-conflict degradation and clean uninstall**

  In a second disposable server, reserve loopback port 8765 before startup. Require the plugin to remain enabled, exhaust its bounded listener retries, report the bind failure without a secret, and have zero visualization entities. For uninstall, stop the primary server, remove the plugin and probe JARs from that temporary server only, restart the same world, require AudioViz is absent, and require `MCAV_PROBE_ENTITY_ABSENT` through the server command log.

- [ ] **Step 9: Run the real scenario**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 python scripts/release/paper_e2e.py --plugin minecraft_plugin/target/mcav-paper-1.1.0.jar --report build/reports/paper-e2e.json'
  ```

  Expected: exit 0, every required check true, secret absent from stdout/report.

- [ ] **Step 10: Commit**

  ```powershell
  git add scripts/release/paper_e2e.py scripts/release/paper_harness.py scripts/release/tests/test_paper_e2e_contract.py scripts/release/probe
  git commit -m "test(plugin): verify real Paper and VJ lifecycle"
  ```

---

### Task 3: Add repeatable performance and eight-hour soak measurement

**Files:**
- Create: `scripts/release/paper_performance.py`
- Create: `scripts/release/tests/test_paper_performance.py`
- Create: `docs/superpowers/reports/2026-08-15-paper-26-2-reference-machine.md`

**Interfaces:**
- Consumes: real Paper/VJ harness, `get_ws_metrics`, `query_zone_status`, `jcmd`, 256-entity frames
- Produces: signed-off JSON/Markdown evidence for TPS, applied-frame latency, main-thread p95, bounded queues, resource baseline, and soak duration

- [ ] **Step 1: Write failing metric calculation tests**

  Test percentile calculation, TPS parser, queue-cap assertion, baseline/delta calculation, required sample count, and exact soak duration. Release acceptance is encoded as constants:

  ```python
  MIN_TPS = 19.8
  MAX_APPLIED_P95_MS = 100.0
  MAX_MAIN_THREAD_P95_MS = 10.0
  REQUIRED_ENTITY_COUNT = 256
  REQUIRED_SOAK_SECONDS = 8 * 60 * 60
  ```

- [ ] **Step 2: Run tests and verify failure**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest scripts/release/tests/test_paper_performance.py -q'
  ```

- [ ] **Step 3: Implement applied-frame latency sampling**

  At 20 Hz, record `queueBatches`, send one 256-entity frame, and poll authenticated `get_ws_metrics` until `queueBatches` increments. The elapsed monotonic interval is the VJ-to-applied-entity sample because the counter increments after `batchUpdateEntities`. Collect at least 1,000 samples for a release run.

- [ ] **Step 4: Implement TPS and plugin-main-thread sampling**

  Every minute issue Paper's `tps` command through the harness and parse the one/five/fifteen-minute values. Read `mainThreadUpdateP95Ms`, queue depths, and dropped count from `get_ws_metrics`. Fail immediately if any queue exceeds its configured cap.

- [ ] **Step 5: Implement resource and orphan checks**

  Record process/thread count, `jcmd <pid> GC.heap_info`, `jcmd <pid> Thread.print`, zone entity count, and queue metrics before load, each hour, and after cleanup. The report includes absolute and delta values; after teardown, active entity and queue counts must be zero and non-daemon MCAV threads must not remain.

- [ ] **Step 6: Run a short harness validation**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 python scripts/release/paper_performance.py --plugin minecraft_plugin/target/mcav-paper-1.1.0.jar --duration-seconds 600 --minimum-samples 1000 --report build/reports/paper-performance-smoke.json'
  ```

- [ ] **Step 7: Run the unshortened release soak on the reference machine**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 python scripts/release/paper_performance.py --plugin minecraft_plugin/target/mcav-paper-1.1.0.jar --duration-seconds 28800 --minimum-samples 1000 --reconnect-interval-seconds 900 --report build/reports/paper-performance-8h.json'
  ```

  Expected: elapsed duration at least 28,800 seconds and every threshold passes.

- [ ] **Step 8: Record the reference machine and evidence hash**

  The Markdown report records CPU, RAM, OS/WSL versions, Docker/Java versions, commit SHA, plugin JAR SHA-256, Paper SHA-256, start/end UTC, all percentiles, resource deltas, and SHA-256 of the raw JSON report. It does not include the pairing secret or full environment.

- [ ] **Step 9: Commit implementation and evidence separately**

  ```powershell
  git add scripts/release/paper_performance.py scripts/release/tests/test_paper_performance.py
  git commit -m "test(plugin): add performance and soak gates"
  git add docs/superpowers/reports/2026-08-15-paper-26-2-reference-machine.md
  git commit -m "docs(plugin): record Paper 26.2 soak evidence"
  ```

---

### Task 4: Make Paper 26.2 integration a required CI gate

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/security.yml`

**Interfaces:**
- Consumes: real-server scripts and Java/Python test suites
- Produces: Java 25 Paper build/audit jobs and a required real-Paper job included in `CI Passed`

- [ ] **Step 1: Update only Paper jobs to Java 25**

  In `java-plugin-build`, `java-plugin-audit`, the Paper license/SBOM step, and `security.yml`'s `java-security`, use:

  ```yaml
  - name: Set up JDK 25
    uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4
    with:
      java-version: '25'
      distribution: temurin
      cache: maven
  ```

  Keep Fabric jobs on Java 21.

- [ ] **Step 2: Add the real Paper job**

  The job checks out with `actions/checkout@11d5960a326750d5838078e36cf38b85af677262`, sets up Java 25 and Python 3.12 with `actions/setup-python@a26af69be951a213d495a4c3e4e4022e16d87065`, installs `vj_server/[full]`, runs `./minecraft_plugin/mvnw -f minecraft_plugin/pom.xml install`, runs `./minecraft_plugin/mvnw -f scripts/release/probe/pom.xml package`, and then runs `paper_e2e.py`. Upload the sanitized report with `actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`.

- [ ] **Step 3: Require the job in the summary**

  Add `paper-26-2-integration` to `ci-passed.needs` and assert its result equals `success`. Add its result to the minimum-separator Markdown summary.

- [ ] **Step 4: Verify workflows and run local equivalents**

  ```powershell
  pwsh -File scripts/github/verify-workflow-pins.ps1
  pre-commit run check-yaml --all-files
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw clean verify'
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 python scripts/release/paper_e2e.py --plugin minecraft_plugin/target/mcav-paper-1.1.0.jar --report build/reports/paper-e2e.json'
  ```

- [ ] **Step 5: Commit**

  ```powershell
  git add .github/workflows/ci.yml .github/workflows/security.yml
  git commit -m "ci(plugin): require Paper 26.2 integration"
  ```

---

### Task 5: Build and attest one reproducible release candidate

**Files:**
- Create: `.github/workflows/paper-candidate.yml`
- Create: `scripts/release/create_candidate_manifest.py`
- Create: `scripts/release/tests/test_candidate_manifest.py`

**Interfaces:**
- Consumes: exact current main SHA with green CI/security and the committed soak evidence hash
- Produces: retained `paper-plugin-candidate-1.1.0` artifact containing JAR, SHA256SUMS, CycloneDX SBOM, and candidate manifest plus GitHub attestations

- [ ] **Step 1: Write failing manifest tests**

  Require exact semantic version, commit SHA, JAR filename, JAR SHA-256, Paper coordinate, Java release, SBOM filename/hash, build timestamp source, and soak evidence hash. Reject missing keys, wrong artifact names, and non-hex hashes.

- [ ] **Step 2: Implement deterministic manifest generation**

  `create_candidate_manifest.py` hashes files itself, obtains `git rev-parse HEAD`, rejects a dirty worktree, and emits sorted/indented JSON without reading secrets or arbitrary environment variables.

- [ ] **Step 3: Add exact-main and green-run preflight**

  The workflow is `workflow_dispatch` with input `commit_sha`. It fetches `main`, requires input SHA equal both `github.sha` and `origin/main`, then uses `gh api` to require successful `push` runs of `.github/workflows/ci.yml` and `.github/workflows/security.yml` for that exact SHA.

- [ ] **Step 4: Build twice and compare bytes**

  Set `SOURCE_DATE_EPOCH=$(git show -s --format=%ct HEAD)`. Run two isolated Maven packages with `-Dproject.build.outputTimestamp=$SOURCE_DATE_EPOCH`, copy each JAR under distinct temporary names, and fail unless `sha256sum` is identical. Promote only the first JAR as `mcav-paper-1.1.0.jar`.

- [ ] **Step 5: Generate SBOM and checksums**

  Run:

  ```bash
  ./mvnw -B org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
    -DoutputFormat=json -DoutputName=mcav-paper-1.1.0.cdx
  sha256sum mcav-paper-1.1.0.jar mcav-paper-1.1.0.cdx.json > SHA256SUMS.txt
  ```

  Generate `candidate-manifest.json`, then run the real Paper E2E scenario against the promoted JAR.

- [ ] **Step 6: Attest JAR and SBOM**

  Grant only `id-token: write`, `attestations: write`, and `contents: read` to the attestation job. Use:

  ```yaml
  - uses: actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6 # v4
    with:
      subject-path: |
        candidate/mcav-paper-1.1.0.jar
        candidate/mcav-paper-1.1.0.cdx.json
  ```

- [ ] **Step 7: Upload one retained candidate bundle**

  Use `actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7`, name `paper-plugin-candidate-1.1.0`, include the JAR, SBOM, checksums, manifest, repository `LICENSE`, and sanitized E2E report, set `if-no-files-found: error`, and retain for 90 days.

- [ ] **Step 8: Verify static policy and commit**

  ```powershell
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest scripts/release/tests/test_candidate_manifest.py -q'
  pwsh -File scripts/github/verify-workflow-pins.ps1
  pre-commit run check-yaml --all-files
  git add .github/workflows/paper-candidate.yml scripts/release/create_candidate_manifest.py scripts/release/tests/test_candidate_manifest.py
  git commit -m "ci(plugin): build reproducible attested candidate"
  ```

---

### Task 6: Replace the quarantined tag workflow with protected promotion

**Files:**
- Modify: `.github/workflows/release-plugin.yml`
- Create: `docs/releases/plugin-v1.1.0.md`
- Modify: `scripts/github/apply-repository-policy.ps1`
- Modify: `scripts/github/verify-repository-policy.ps1`

**Interfaces:**
- Consumes: candidate run ID, exact main SHA, expected SHA-256, protected `plugin-release` environment
- Produces: immutable tag and GitHub release containing byte-identical candidate files

- [ ] **Step 1: Configure the protected environment as policy**

  Extend the policy script to create/update `plugin-release`:

  ```powershell
  $environment = @{
    wait_timer = 0
    prevent_self_review = $false
    reviewers = @(@{ type = 'User'; id = 37377365 })
    deployment_branch_policy = @{
      protected_branches = $true
      custom_branch_policies = $false
    }
  } | ConvertTo-Json -Depth 6
  $environment | gh api --method PUT 'repos/ryanthemcpherson/minecraft-audio-viz/environments/plugin-release' --input -
  ```

  The verifier requires reviewer ID `37377365` and protected branches.

- [ ] **Step 2: Change the workflow trigger and inputs**

  Remove the `push.tags` trigger. Use `workflow_dispatch` with required `candidate_run_id`, `commit_sha`, `version`, and `sha256`. Reject any version other than `1.1.0` and derive tag `plugin-v1.1.0` internally.

- [ ] **Step 3: Download candidate bytes without rebuilding**

  Use:

  ```yaml
  - uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8
    with:
      name: paper-plugin-candidate-1.1.0
      path: candidate
      run-id: ${{ inputs.candidate_run_id }}
      github-token: ${{ github.token }}
  ```

  No Java setup, Maven command, or build step is permitted in this workflow.

- [ ] **Step 4: Verify provenance before entering the protected job**

  Require current `origin/main` equals the input SHA; candidate manifest commit/version/file/hash equals all inputs; `sha256sum -c SHA256SUMS.txt` passes; `gh attestation verify` passes for JAR and SBOM; exact-main `CI Passed` and `Security Summary` push runs are successful; and `refs/tags/plugin-v1.1.0` does not exist.

- [ ] **Step 5: Create the immutable tag only inside `plugin-release`**

  The tag/release job declares `environment: plugin-release`, `contents: write`, `actions: read`, and `attestations: read`. Checkout the exact commit with persisted credentials, create an annotated tag, and push only that ref:

  ```bash
  git tag -a plugin-v1.1.0 "$COMMIT_SHA" -m "MCAV Paper Plugin 1.1.0"
  git push origin refs/tags/plugin-v1.1.0
  ```

- [ ] **Step 6: Publish the same files**

  Use `softprops/action-gh-release@3bb12739c298aeb8a4eeaf626c5b8d85266b0e65 # v2`, release notes from `docs/releases/plugin-v1.1.0.md`, `draft: false`, `prerelease: false`, and files:

  ```text
  candidate/mcav-paper-1.1.0.jar
  candidate/mcav-paper-1.1.0.cdx.json
  candidate/SHA256SUMS.txt
  candidate/candidate-manifest.json
  candidate/LICENSE
  ```

- [ ] **Step 7: Write accurate release notes**

  State Paper 26.2, Java 25+, loopback authenticated VJ pairing, source VJ install, artifact verification commands, rollback, optional integrations, and unsupported 1.21/Fabric/Bedrock/distribution boundaries.

- [ ] **Step 8: Verify policy and commit**

  ```powershell
  pwsh -File scripts/github/verify-workflow-pins.ps1
  pwsh -File scripts/github/verify-repository-policy.ps1 -StaticOnly
  pre-commit run check-yaml --all-files
  git add .github/workflows/release-plugin.yml docs/releases/plugin-v1.1.0.md scripts/github/apply-repository-policy.ps1 scripts/github/verify-repository-policy.ps1
  git commit -m "ci(plugin): promote protected release candidate"
  ```

---

### Task 7: Run the pre-candidate release audit and merge

**Files:**
- Create: `docs/superpowers/reports/2026-08-15-paper-26-2-release-readiness.md`

**Interfaces:**
- Consumes: completed Git/runtime/release plans, local soak report, all audits
- Produces: G0-G6 coverage matrix and the expected reproducible JAR hash

- [ ] **Step 1: Verify every spec requirement has authoritative evidence**

  Use sections `G0` through `G6`. Each row contains requirement, evidence path/URL, exact commit/run, status, and reviewer. `not checked` and inferred success are failures.

- [ ] **Step 2: Run all local gates from clean state**

  ```powershell
  git status --short --branch
  pwsh -File scripts/github/verify-dependabot-policy.ps1
  pwsh -File scripts/github/verify-workflow-pins.ps1
  pwsh -File scripts/github/verify-repository-policy.ps1
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw clean verify'
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest vj_server/tests scripts/release/tests -q'
  node --test protocol/tests/phase0-schemas.test.mjs
  ```

- [ ] **Step 3: Verify all dependency ecosystems**

  Run npm audits in four directories, Python audits in isolated WSL environments, Paper OWASP with successful report generation, and Cargo audit. Zero critical/high findings and no scanner error is required.

- [ ] **Step 4: Record the pre-candidate reproducible hash**

  Build twice with the commit timestamp and record the identical SHA-256 in the readiness report. Because the subsequent report commit changes documentation only, the candidate workflow must reproduce this exact JAR hash.

- [ ] **Step 5: Commit the readiness report**

  ```powershell
  git add docs/superpowers/reports/2026-08-15-paper-26-2-release-readiness.md
  git commit -m "docs(release): record Paper 26.2 readiness"
  ```

- [ ] **Step 6: Rebuild after the report commit and require identical JAR bytes**

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'SOURCE_DATE_EPOCH=$(git -C /workspace show -s --format=%ct HEAD); bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw clean package -DskipTests -Dproject.build.outputTimestamp=$SOURCE_DATE_EPOCH'
  Get-FileHash minecraft_plugin/target/mcav-paper-1.1.0.jar -Algorithm SHA256
  ```

  Expected: hash equals the value recorded before the documentation-only commit.

- [ ] **Step 7: Push, open/update the PR, and wait for exact checks**

  ```powershell
  git fetch --prune origin
  git rebase origin/main
  git push -u origin release/paper-26.2
  gh pr create --base main --head release/paper-26.2 --title "feat: release Paper 26.2 plugin" --body-file docs/superpowers/reports/2026-08-15-paper-26-2-release-readiness.md
  gh pr view --json url,state,mergeStateStatus,statusCheckRollup
  ```

  The branch is pushed only after this final rebase, so no force push is required or permitted.

- [ ] **Step 8: Merge only through the protected main ruleset**

  Require resolved conversations and green `CI Passed`/`Security Summary`, then squash or rebase merge. Confirm the merge commit's exact-main push runs also succeed before candidate dispatch.

---

### Task 8: Build candidate, approve promotion, and prove public bytes

**Files:**
- No repository source changes

**Interfaces:**
- Consumes: exact green main SHA and release-readiness hash
- Produces: candidate run, protected approval, tag `plugin-v1.1.0`, public release, and final byte-identity proof

- [ ] **Step 1: Dispatch the candidate workflow**

  ```powershell
  $mainSha = (git ls-remote origin refs/heads/main).Split("`t")[0]
  gh workflow run paper-candidate.yml -f commit_sha=$mainSha
  gh run list --workflow paper-candidate.yml --limit 1
  ```

- [ ] **Step 2: Download and independently verify the candidate**

  ```powershell
  $runId = gh run list --workflow paper-candidate.yml --limit 1 --json databaseId --jq '.[0].databaseId'
  gh run download $runId -n paper-plugin-candidate-1.1.0 -D build/candidate-1.1.0
  Push-Location build/candidate-1.1.0
  Get-FileHash mcav-paper-1.1.0.jar -Algorithm SHA256
  gh attestation verify mcav-paper-1.1.0.jar --repo ryanthemcpherson/minecraft-audio-viz
  gh attestation verify mcav-paper-1.1.0.cdx.json --repo ryanthemcpherson/minecraft-audio-viz
  Pop-Location
  ```

  Require candidate hash equals the release-readiness report.

- [ ] **Step 3: Dispatch protected promotion**

  ```powershell
  $jarHash = (Get-FileHash build/candidate-1.1.0/mcav-paper-1.1.0.jar -Algorithm SHA256).Hash.ToLowerInvariant()
  gh workflow run release-plugin.yml -f candidate_run_id=$runId -f commit_sha=$mainSha -f version=1.1.0 -f sha256=$jarHash
  ```

- [ ] **Step 4: Review and approve the `plugin-release` environment**

  Review G0-G6, candidate manifest, checksum, SBOM, attestation, and exact-main checks in GitHub. Approve the pending deployment only when they match.

- [ ] **Step 5: Verify tag, release, and asset identity**

  ```powershell
  git fetch --tags origin
  git rev-parse plugin-v1.1.0^{commit}
  gh release download plugin-v1.1.0 -p 'mcav-paper-1.1.0.jar' -D build/published-1.1.0
  $candidateHash = (Get-FileHash build/candidate-1.1.0/mcav-paper-1.1.0.jar -Algorithm SHA256).Hash
  $publishedHash = (Get-FileHash build/published-1.1.0/mcav-paper-1.1.0.jar -Algorithm SHA256).Hash
  if ($candidateHash -ne $publishedHash) { throw 'Published bytes differ from candidate' }
  gh attestation verify build/published-1.1.0/mcav-paper-1.1.0.jar --repo ryanthemcpherson/minecraft-audio-viz
  ```

- [ ] **Step 6: Confirm immutability enforcement**

  Use the live ruleset verifier and GitHub rule insights to prove tag update/deletion are blocked. Do not attempt a destructive test push against the public tag.
