# MCAV Paper 26.2 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve all local work, repair repository governance, port the authenticated Paper plugin to 26.2/Java 25, prove it on a real server, and publish the exact verified `plugin-v1.1.0` artifact.

**Architecture:** Work proceeds through three independently reviewable plans. Git recovery and governance establish a safe integration base; the Paper/VJ plan makes the runtime release-ready; the verification plan builds one candidate and promotes those same bytes only after real-server, performance, soak, security, and human-approval gates pass.

**Tech Stack:** Git/GitHub CLI, GitHub Actions, PowerShell, Maven Wrapper, Java 25, Paper 26.2, Python 3.12 in WSL/Linux, pytest, WebSockets, CycloneDX, GitHub artifact attestations

**Spec:** `docs/superpowers/specs/2026-08-15-paper-26-2-release-design.md`

## Global Constraints

- Supported server is Paper 26.2 only.
- Paper API is `io.papermc.paper:paper-api:26.2.build.112-stable`.
- Java release and release-build JDK are exactly 25.
- Plugin version is `1.1.0`; tag is `plugin-v1.1.0`; artifact is `mcav-paper-1.1.0.jar`.
- JSON WebSocket remains the required render/control path; `sbe.v1` is not a release prerequisite.
- The VJ server remains source-installed and requires Python 3.11 or newer.
- No stash, worktree, branch, untracked product file, or release artifact is deleted by these plans.
- `.codex/`, `.superpowers/`, and the root untracked `AGENTS.md` are not committed as product recovery.
- Fabric, Bedrock claims, desktop installers, auto-update, Docker distribution, and v2 remain quarantined.
- Every implementation task uses TDD and ends in an atomic conventional commit.

---

## Execution order

- [ ] **Stage 1: Execute Git recovery and governance**

  Follow `docs/superpowers/plans/2026-08-15-git-recovery-governance-implementation.md` completely. Stop at its explicit external-deletion checkpoint; closing superseded PRs or removing old worktrees still requires the reviewed target list called for by the spec.

- [ ] **Stage 2: Confirm G0 and G1**

  Evidence required before runtime edits:

  ```powershell
  git status --short --branch
  git worktree list --porcelain
  git stash list
  gh api 'repos/ryanthemcpherson/minecraft-audio-viz/rulesets'
  pwsh -File scripts/github/verify-repository-policy.ps1
  pwsh -File scripts/github/verify-workflow-pins.ps1
  ```

  Expected: release worktree clean; every prior product change mapped in the recovery ledger; both stashes still present; policy verifiers exit 0.

- [ ] **Stage 3: Execute the Paper 26.2 runtime port**

  Follow `docs/superpowers/plans/2026-08-15-paper-26-2-port-implementation.md` completely.

- [ ] **Stage 4: Confirm G2**

  Run the Java suite under Java 25 and the VJ/protocol suites under WSL/Linux:

  ```powershell
  docker run --rm -v "${PWD}:/workspace" -w /workspace/minecraft_plugin eclipse-temurin:25-jdk bash -lc 'bash -c "$(tr -d "\r" < ./mvnw)" ./mvnw clean verify'
  wsl bash -lc 'cd /mnt/c/Users/Ryan/Desktop/minecraft-audio-viz/.worktrees/phase0-containment && uv run --python 3.12 pytest vj_server/tests -q'
  node --test protocol/tests/phase0-schemas.test.mjs
  ```

  Expected: all suites pass; `minecraft_plugin/target/mcav-paper-1.1.0.jar` exists; no source-tree `dependency-reduced-pom.xml` exists.

- [ ] **Stage 5: Execute verification and release engineering**

  Follow `docs/superpowers/plans/2026-08-15-paper-26-2-verification-release-implementation.md` through candidate production and evidence collection.

- [ ] **Stage 6: Confirm G3 through G6**

  Required evidence:

  - Exact-main `CI Passed` and `Security Summary` push runs are green.
  - npm audit reports show zero critical/high findings in root, `dj_client`, `site`, and `worker`.
  - Real Paper 26.2 integration report passes.
  - 256-entity performance report passes 19.8 TPS, 100 ms end-to-visible p95, and 10 ms plugin-main-thread p95.
  - Eight-hour soak report passes.
  - Clean-machine install/uninstall/rollback report passes.
  - Candidate JAR SHA-256, CycloneDX SBOM, and GitHub attestation verify.

- [ ] **Stage 7: Publish with protected approval**

  Dispatch `release-plugin.yml` with the verified candidate run ID, exact main SHA, version `1.1.0`, and recorded SHA-256. Approve the `plugin-release` environment only after reviewing the G0-G6 evidence.

- [ ] **Stage 8: Verify publication**

  ```powershell
  git ls-remote --tags origin refs/tags/plugin-v1.1.0
  gh release view plugin-v1.1.0 --json tagName,targetCommitish,isDraft,isPrerelease,assets,url
  gh attestation verify mcav-paper-1.1.0.jar --repo ryanthemcpherson/minecraft-audio-viz
  Get-FileHash mcav-paper-1.1.0.jar -Algorithm SHA256
  ```

  Expected: immutable tag points to the approved main SHA; release is public and non-prerelease; attestation verifies; hash matches the candidate report exactly.

## Completion rule

Do not declare the project released from a successful build alone. Completion requires every numbered gate in the spec, the public immutable tag, and proof that the published JAR is byte-identical to the tested candidate.

## Plan self-review coverage

- Spec sections 2-3, supported boundary and versions: port Tasks 1 and 7; release Tasks 5-6.
- Spec sections 4 and 7, runtime ownership and Paper port: port Tasks 1-2 and 5-6.
- Spec sections 5-6, Git recovery and governance: Git Tasks 1-9.
- Spec section 8, secure first run, listener, backpressure, and lifecycle: port Tasks 3-6.
- Spec section 9, automated, real-server, performance, and soak verification: port Task 8; release Tasks 1-4 and 7.
- Spec sections 10-11, candidate identity, publication, and rollback: port Task 7; release Tasks 5-6 and 8.
- Spec sections 12-13, release gates and definition of done: release Tasks 7-8 and this umbrella plan's Stages 2, 4, 6, and 8.

Self-review found no uncovered spec requirement. The test-only world-unload probe closes the only behavior that cannot be exercised through vanilla Paper console commands alone. Function/type names are consistent across plans: `PaperServer`, `DisconnectCleanupController`, `MessageQueue.QueueMetrics`, `queueBatches`, and `mainThreadUpdateP95Ms` retain the same spelling at every consumer.
