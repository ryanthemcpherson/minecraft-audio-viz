# Rust → Lua → Paper expanded matrix — September 13, 2026

The expanded prototype preserves the selected Lua behavior across all twelve offline workloads and 26 successful live trials, including four zones with 128 entities each. Rust lowers median trial p95 runtime-plus-serialization cost by 29–54%; at the largest workload it uses 1.323 ms versus Python's 2.242 ms. This supports further bounded Rust integration work, but does not establish a physical latency improvement or justify switching production ownership yet.

## Scope and method

This extends the [initial measured slice](2026-09-11-rust-runtime-slice.md) to four unchanged production Lua visuals: Stacked Tower (`spectrum`), Spectrum Bars (`bars`), Aurora, and Shockwave. Each is tested at 64 and 128 entities. Mixed workloads use Bars/Aurora in two zones and all four visuals in four zones; the single-zone live workload uses Shockwave. Because the visual composition changes, these are concrete workload comparisons rather than a pure zone-count scaling curve.

The [reproduction guide](../../../prototypes/rust-runtime-slice/README.md) describes the isolated prototype and commands. Production Python, Lua, DSP, and Java sources are unchanged. Each zone gets its own Lua 5.4 VM, RNG seed, and smoothing state. Both bindings seed Lua with `math.randomseed(seed, 0)` and use the canonical flat-pack defaults and production-compatible IDs.

The [machine-readable evidence](2026-09-13-rust-runtime-matrix-evidence.json) records every timing trial, parity result, isolation check, compact live observation, queue count, source hash, and raw-artifact digest. Implementation commit: `7b88a075c59c8f707e7000a12bd7ad68d93ecca3`. The same release binary was used for all final offline, feature-replay, and PCM results: SHA-256 `85da206f0518c489ac1afcef410910789d85777195472fbab803b538dc52ebb2`.

The deterministic fixture contains 12 seconds of 48 kHz mono float32 PCM with silence, kicks, and a chirp. A 1024-sample window and 800-sample hop produce 719 frames. Offline parity checks all frames; timing excludes the first 60. Each of twelve offline workloads runs three paired trials in alternating runtime order. Each mixed zone is also compared against fresh standalone Rust and Python runs with its exact seed. Unit tests additionally reorder seeded instances and insert unrelated neighboring work.

Numeric payload fields use absolute tolerance `1e-9`; rotations use shortest angular distance at that tolerance, after requiring values in the canonical 0–360 degree range. Floating-point rounding can yield equivalent orientations near 0 and 360. Raw differences and wrap counts remain in the evidence. IDs, bands, visibility, optional-field presence, and glow/brightness/material/interpolation values must match exactly.

## Local processing cost

All 36 paired offline trials passed payload parity. The largest numeric discrepancy was approximately `4.292e-12`, below the `1e-9` tolerance; optional values and presence matched exactly. Mixed-zone outputs matched their independently seeded standalone counterparts.

The following values are the median of three trial p95 values, in milliseconds, measured on an Intel Core i5-13600KF under WSL2 with Python 3.12.13, Lupa 2.6 / Lua 5.4, and the Rust 1.93.0 release build. They are not pooled percentiles or general language-performance claims.

| Workload | Rust p95 ms | Python p95 ms | Rust reduction |
|-|-|-|-|
| Stacked Tower, 64 | 0.176 | 0.281 | 37.5% |
| Spectrum Bars, 64 | 0.162 | 0.243 | 33.5% |
| Aurora, 64 | 0.199 | 0.283 | 29.5% |
| Shockwave, 64 | 0.180 | 0.391 | 53.9% |
| Bars/Aurora, 2 × 64 | 0.355 | 0.543 | 34.7% |
| All four, 4 × 64 | 0.689 | 1.187 | 42.0% |
| Stacked Tower, 128 | 0.330 | 0.514 | 35.8% |
| Spectrum Bars, 128 | 0.294 | 0.457 | 35.7% |
| Aurora, 128 | 0.376 | 0.559 | 32.7% |
| Shockwave, 128 | 0.350 | 0.741 | 52.7% |
| Bars/Aurora, 2 × 128 | 0.675 | 1.026 | 34.2% |
| All four, 4 × 128 | 1.323 | 2.242 | 41.0% |

For 4 × 128, Rust trial p95 ranged from 1.321–1.347 ms and Python from 2.234–2.300 ms. Across 23,724 measured offline frames per runtime, Rust had no total-frame-work intervals above 16.667 ms. Python had three: one in 4 × 64 and two in 4 × 128, with a maximum of 25.176 ms. Those outliers remain in the evidence despite the low p95 values; their cause was not isolated.

The primary comparable interval sums runtime and serialization across all zones for each frame before computing percentiles. Total frame work and scheduling lateness are separate diagnostics against a 16.667 ms budget. Evidence-object construction and output-file writes are outside the frame-work timer. Rust uses a blocking transport and Python an asynchronous transport; the PCM path also includes DSP. These measurements do not establish one-way network or physical audio-to-display latency.

The expanded Rust bridge now uses canonical flat packing, ID-keyed smoothing, and typed batch serialization. The older report describes a different executable and its timing figures must not be treated as measurements of this implementation.

## Paper application

All 24 feature-replay trials passed: two Rust/Python pairs for each of six zone/entity workloads. A separate connected PCM pair also passed at four zones and 128 entities per zone, for 26 successful live trials. Every run retained its full pools, showed changing sampled output in every zone, matched representative final transforms, accounted for all received batches, and stopped cleanly without ERROR log entries. All twelve replay pairs and the PCM pair passed full payload parity.

| Workload | Received batches per run | Rust applied batches | Python applied batches |
|-|-|-|-|
| Shockwave, 1 × 64 | 720 | 241 / 241 | 241 / 241 |
| Shockwave, 1 × 128 | 720 | 242 / 242 | 241 / 241 |
| Bars/Aurora, 2 × 64 | 1,440 | 482 / 482 | 480 / 484 |
| Bars/Aurora, 2 × 128 | 1,440 | 482 / 484 | 482 / 474 |
| All four, 4 × 64 | 2,880 | 966 / 964 | 964 / 960 |
| All four, 4 × 128 | 2,880 | 964 / 964 | 964 / 964 |
| Connected PCM / exact-feature replay, 4 × 128 | 2,880 | 960 | 964 |

Applied counts include one setup batch per zone. All remaining received batches were coalesced/dropped. These results show the same approximately 20 Hz per-zone application behavior for both senders, despite Rust's lower local processing cost.

At 4 × 128, live total-frame-work p95 was 1.610–1.612 ms in Rust and 2.975–3.019 ms in Python. Across 7,908 measured feature-replay frames per runtime, Rust had no work or scheduling-lateness intervals above 16.667 ms. Python had three work overruns (maximum 30.418 ms) and 30 late starts. In the PCM pair, Rust's total-frame-work p95 including DSP was 1.692 ms, versus 2.949 ms for Python's exact-feature replay; neither exceeded the work budget, while Python had six late starts. The asynchronous Python sender shares the observer's event loop, and the adapters differ, so live scheduling figures are diagnostic rather than a controlled transport comparison.

The pinned artifact is AudioViz `1.2.0`, SHA-256 `c410612bdd11a18a5beaf9caa9f16bb0028b5aa5ac1352c08d9f81beefde067e`, built from release-branch commit `e06996dbc46a12fcba1d9368abefce61035a078e`. This is not a build of the current worktree's default plugin source. The unchanged imported acceptance harness pins Paper 1.21.11 build 132 and Linux Temurin 21.0.12.1+1. Managed VJ installation and voice chat remain disabled.

Every sender trial uses a fresh world, separate stage anchors, and only the requested main pools. Complete pool counts are checked before and after streaming. During the stream, two tagged identities per zone must match monotonically ordered emitted frames and show changing transforms. Three representative identities per zone are checked at completion. NBT snapshots verify actual server positions, scales, and rotation quaternions; hidden entities have zero scale and opposite quaternion signs are equivalent. Explicit final glow values are also checked.

All 24 feature-replay trials completed successfully before an initial PCM attempt exposed a sampling race: the first console query observed the exact setup state while the sender was still starting. The harness now records only that known pre-stream state separately; it cannot count toward motion or conceal a later mismatch. Regression tests reject setup-only, setup-to-static, unknown initial state, and post-stream setup regression. The failed invocation is retained with its original failure flag; the successful PCM pair was rerun separately using `--pcm-only`. The Rust executable was unchanged by this harness correction.

These observations establish application in every measured zone, not inspection of every entity or Minecraft client rendering. Queue accounting is aggregate: every setup and streamed batch must be either applied or coalesced/dropped. A WebSocket ping/pong only fences receipt. Server tick coalescing prevents a claim that every 60 Hz source frame was rendered.

The pinned queued renderer ignores material updates. Pools deliberately start with GLOWSTONE, exposing Shockwave's unapplied SEA_LANTERN requests. Payload parity therefore does not imply material application. Omitted optional fields may retain earlier values after queue coalescing and are not treated as reset commands. This experiment records the limitation without changing production Java.

## Verification and decision

Verification passed: 106 targeted Python tests, 22 Rust tests, Rust formatting, Ruff lint/format checks, and all implementation commit hooks. Only existing WebSocket deprecation warnings appeared in the Python suite. No Paper process remained after the experiments. Production source files were not modified.

**Recommendation: retain Python as the production owner while keeping this Rust prototype as a validated integration candidate.** The expanded evidence removes the earlier single-visual/single-zone compatibility gap for these four visuals and demonstrates approximately 0.919 ms lower median trial p95 cost at 512 displays. Both paths still have substantial p95 processing headroom, and Paper applies their output at essentially the same rate. A production ownership decision still needs lifecycle/recovery and authenticated transport integration, longer representative operation, and properly measured client-visible timing. The queued material gap is a shared renderer issue, independent of runtime language.

Raw features, output frames, server worlds, logs, and summaries are retained in ignored prototype results directories. Final offline results are in `expanded-offline-verified-2026-09-13`; the 24 replay trials are in `expanded-paper-final-2026-09-13`; the successful PCM pair is in `expanded-paper-pcm-verified-2026-09-13`. The initial rotation diagnostic, earlier offline runs, two smoke trials, and failed PCM sampling attempt remain available and are excluded from final performance figures. The final quiet offline run did not overlap builds or Paper execution. No production deployment, release, or tag is part of this experiment.
