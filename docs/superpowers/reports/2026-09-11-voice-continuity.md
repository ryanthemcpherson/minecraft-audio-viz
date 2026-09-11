# MCAV voice decisions and implementation handoff

Recorded September 11, 2026 from both September 10 tasks titled **New Realtime Voice Chat**:
`01a08d47-afc9-7960-9a99-ffa5fc823095` and `01a08d5a-3efd-7ee2-ab95-04071fd4cc65`.
All available turns were retrieved, including the older first-session turns.

## Product direction

- Target Pterodactyl constraints: upload one JAR, allocate one additional TCP port, no terminal access, toolchain installation, or startup-command edits.
- Keep DJ feature traffic direct to the server. Hosted services handle discovery, configuration, health, connection tickets, and later monetization; relay is an optional fallback.
- Preserve visual quality while simplifying component ownership. The proposed direction is Rust DJ DSP, a Rust visual runtime retaining Lua artistic behavior, and Paper owning Minecraft entities/lifecycle.
- Prove a measured vertical slice before committing to a production-runtime migration. The current Python sidecar remains implemented; the discussion did not establish a completed Rust replacement or justify deleting it.
- Require three polished reference visuals and reliable installation before expanding SaaS. Pricing and marketplace ideas remain proposals, not implemented commitments.

## Approved DSP work

The user approved GLM as a write-enabled developer for a bounded first slice: timestamp propagation, latency metrics, a reproducible DSP benchmark harness, and tests in `.worktrees/dsp-latency-baseline` on `feature/dsp-latency-baseline`. Preserve current DSP and visual behavior; no broad rewrite yet.

Follow-on DJ work discussed: causal low-frequency crossover/envelopes, multiband onsets, adaptive beat thresholds, tempo/phase confidence, and band normalization. These need baseline evidence before changing production behavior. GLM's reported 46–96 ms estimate is an audit hypothesis, not a measured end-to-end result.

Measure clock-local processing intervals separately from network/transit estimates. Unsynchronized clocks on different hosts cannot provide accurate one-way latency through timestamp subtraction alone. Keep deterministic synthetic kicks/sweeps and later representative music fixtures repeatable.

The DSP baseline is complete and pushed on `feature/dsp-latency-baseline`: `2dac8950` adds clock-local timing and the benchmark; `27f45eee` strengthens per-burst detection checks. Verification passed 497 Python tests, 56 Rust tests, five existing protocol tests, and the explicit optimized benchmark. The follow-up passed all five focused DSP tests. On the i5-13600KF desktop, optimized per-block BassLane/FFT p95 cost ranged from 0.0089 to 0.0362 ms across silence, kick bursts and a sweep. This is synthetic processing cost, not live end-to-end latency. The detailed evidence report is `docs/superpowers/reports/2026-09-11-dsp-latency-baseline.md` on that independent branch.

The original voice task ended; there is no active GLM writer. The replacement `continue-mcav-dsp-baseline` heartbeat is paused after verification. This project task owns continuation. The user subsequently authorized push access and repairing the personal SSH key's Windows permissions, with a per-command signing override. Local signatures verify; GitHub reports `unknown_key` because that key is not registered there for signing. Saved Git signing settings remain unchanged.

## Deployment acceptance gate

The user reported a friend's deployment rendered no entities; those server logs are no longer accessible. The September 11 continuation confirmed a related startup defect in code and regression tests: `loadStages()` restored the persisted active flag but never initialized pools or decorators. Two new restore tests failed before the fix because there were zero pool-initialization calls. Active stage runtime state now restores after metadata loading, preserving saved activation timestamps and file contents; inactive stages stay inactive and a failed restoration does not prevent other stages from restoring. Explicit activation still updates and saves its timestamp. The three targeted regression tests and full plugin package build pass (539 tests, zero failures/errors, four skips). This proves the missing initialization path, not the root cause of every failure on the inaccessible server.

Use a disposable Paper/Pterodactyl environment with retained logs. Prove clean install, cold restart, stage restoration, nonzero entity count, and changing transforms under synthetic audio. Socket startup alone is insufficient. Add actionable diagnostics for plugin, zone/pool, runtime, TLS, DJ connection, and incoming frames. Pin one Paper/Java combination first, then broaden support.

The local Paper renderer gate now passes with Paper 1.21.11 build 132 and Java 21: eight main-stage entities on clean start and cold restart, actual BlockDisplay position/scale changes under synthetic render batches, graceful shutdowns, and unchanged persisted stage history. The cold restart needed no activation or pool-initialization request. Evidence and reproduction are in [the Paper acceptance report](2026-09-11-paper-acceptance.md). This run disabled managed runtime installation and did not test audio processing or Pterodactyl. It also exposed a separate startup error: without Simple Voice Chat, the main plugin's event handlers fail registration because an optional API class is missing. That remains the next standalone-startup fix.

## Recovered release implementation

`main` at `e9b317e8` contains plans but not the implementation. The existing `codex/self-installing-release` branch at `.worktrees/self-installing-release` already includes:

- Signed runtime manifest/schema and Python signer (`e14c4254`, `18cdaeeb`).
- Java manifest verification (`4f682aab`), download/store/extraction, installation, and process supervision.
- Unified TLS ingress, first-run administrator setup, certificate-bound DJ invites, and managed lifecycle.
- Verified native archive tooling through `1c3e0989`.

Uncommitted candidate-signing/verification work also exists there. Preserve it separately. The prior release task stopped at production key custody; no production key generation, publishing, or deployment is authorized by this handoff. Packaging a Python runtime is not proof of the proposed Rust architecture.

The September 11 manifest review reproduced acceptance of dot path segments and differing timestamp normalization across Python/Java. The fix rejects `.`/`..` path segments and noncanonical UTC timestamps in signer/verifier and tightens the public schema. Reviewed golden manifest/signature bytes remain unchanged.

These fixes are now committed separately: `f603534c` hardens manifest validation, and `5fc0b451` restores active stages. The latest Java 21 package build passed 539 tests with zero failures/errors and four skips. The latest targeted signer/version/candidate-tool run passed 72 tests in the WSL `.release-venv`. Pre-existing candidate-signing implementation files remain uncommitted and outside these fixes.

Validation: initial 6 Python signer and 75 Java manifest/platform tests passed; new regressions then produced 18 Python failures and 11 Java failures before the fix. After the fix, 117 release-tool tests and 92 focused Java manifest/platform tests passed. Full `mvnw.cmd -q package` passed under Java 21: 536 tests, zero failures/errors, four skips. Ruff and schema lexical checks against valid/invalid examples also passed. This does not replace the real Paper cold-restart rendering acceptance gate.

## Next sequence

1. Retain the verified DSP baseline on its independent branch until integration review.
2. Fix event-handler registration when Simple Voice Chat is absent, then repeat the now-passing local Paper gate and check error-free startup. Retain the pinned renderer evidence and extend acceptance separately to managed installation and Pterodactyl.
3. Compare one Rust DSP/visual vertical slice against the baseline before revising Python-specific release plans.
4. Reuse the runtime-neutral manifest/store/supervisor foundation for the selected runtime.
5. Finish signed candidate evidence and clean-install gates, then golden visuals and hosted management.
