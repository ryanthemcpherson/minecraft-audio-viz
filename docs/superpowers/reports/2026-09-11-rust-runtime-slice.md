# Rust → Lua → Paper measured slice — September 11, 2026

The isolated prototype renders the existing **Stacked Tower** visual in real Paper from repeatable PCM through the production Rust DSP. Its output matches the production Python Lua wrapper, including entity smoothing. Rust has lower local wrapper/encoding cost in the final offline trials, while Paper frame delivery is essentially unchanged. This makes Rust a viable candidate for owning this slice; it does not establish that a production runtime replacement is warranted.

Implementation: `prototypes/rust-runtime-slice/`, commit `20d8d3c4`, on `feature/rust-runtime-slice`. The unchanged disposable Paper harness and its fixtures were imported separately in `3592f2dd`. No production DSP, Python runtime, Lua pattern, or plugin source was changed. No public release, tag, managed installation, or deployment was performed.

## Evidence and method

The [reproduction guide](../../../prototypes/rust-runtime-slice/README.md) documents the fixture, commands, clocks, and deliberate omissions. The [machine-readable evidence](2026-09-11-rust-runtime-slice-evidence.json) retains all three offline timing trials per entity count, six Paper trials, queue counts, entity observations, hashes, and environment details.

Both runtime processes use the same WSL2 Linux environment on an Intel Core i5-13600KF (14 cores / 20 logical processors). Python is 3.12.13 with Lupa 2.6 / PUC Lua 5.4 and production msgspec encoding. Rust is 1.93.0 with mlua 0.11.6 / PUC Lua 5.4, rustfft 6.4.1, and a locked `opt-level=3` build. This is not the DJ application's size-optimized/LTO shipping profile. The final offline trials ran without intentional concurrent Paper/build activity; background Windows/WSL activity and CPU scheduling were not controlled.

Input: 12 seconds of 48 kHz mono f32 PCM, comprising silence, twelve decaying 60 Hz kick bursts, and a 40 Hz–20 kHz chirp. Windows are 1024 samples with an 800-sample hop: 719 complete frames at nominal 60 Hz. The fixture SHA-256 is `0d584a7af3830fe85daf06d47686af4853e308c0b29db474a771cbadbd36bf7c`. Two fresh accelerated DSP runs agree exactly on bands, amplitude, instant bass, and kick windows. All 12 kick detections occur at frames `119,149,179,209,239,269,299,329,359,389,419,449`; the first window straddles the first burst. No kick is reported in the opening silence.

The FFT's beat/BPM/phase use wall-clock time. Their recorded feature snapshot is replayed identically through both runtimes; accelerated execution is not a tempo-accuracy test. The final pair of Paper runs additionally processes PCM in the paced Rust loop and replays **that live run's** recorded features through Python. Thus the result includes the connected audio path, not only synthetic renderer batches or offline playback.

All output fields are checked across every frame at 8, 64, and 128 entities, three trials each. Numeric comparisons include positions, scale, and rotation after Python-equivalent smoothing; IDs, bands, and visibility match exactly. Silence retains the expected base scale, and kick input produces scale increases above 0.5. All comparisons pass at `1e-9` tolerance; maximum observed absolute error is `1.03e-14` (rounded upward). The live PCM/Python pair's maximum error is `5.67e-15` (rounded upward).

## Local processing cost

Timing excludes the first 60 frames but behavior checks include all frames. Runtime cost includes table updates, Lua, extraction, and smoothing. Encoding is measured separately with each implementation's serializer. Combined cost is summed per frame before computing the percentile, not obtained by adding two p95 values. File writes are outside the measured/paced loop. These measurements cover the selected visual and wrapper, not the complete VJ server.

Final offline runtime-plus-encoding cost, milliseconds:

| Entities | Rust p95 range across 3 trials | Python p95 range across 3 trials | Median trial p95, Rust / Python |
|-|-|-|-|
| 8 | 0.0377–0.0541 | 0.0588–0.0643 | 0.0418 / 0.0623 |
| 64 | 0.2538–0.2572 | 0.3223–0.3372 | 0.2540 / 0.3324 |
| 128 | 0.4744–0.5385 | 0.6296–0.8897 | 0.4950 / 0.6739 |

Median trial p95 is about 24–33% lower in Rust across these entity counts. These are per-trial percentiles, not an aggregate percentile or a language-wide speed claim. Both implementations remain below 1 ms p95 for this visual in these offline trials. The Rust Lua bridge saves work while its current JSON construction/encoding costs more than Python's production msgspec path; separate stage timings are retained in the JSON evidence.

The paced PCM run's Rust DSP cost was **0.060084 ms p95**, with a 0.402171 ms maximum. That includes BassLane, FFT, and kick merging on one monotonic clock. The earlier DSP baseline's Windows build and 480-sample hop are different conditions; its numbers should not be treated as a direct before/after comparison. Neither figure is end-to-end latency.

## Paper delivery and actual entity state

The exact JAR SHA-256 is `c410612bdd11a18a5beaf9caa9f16bb0028b5aa5ac1352c08d9f81beefde067e`, built from release branch commit `e06996db`. The pinned server is Paper 1.21.11 build 132 on Linux Temurin 21.0.12.1+1. Managed VJ installation and optional Simple Voice Chat are disabled.

Each disposable world contains an eight-entity club-stage pool. A single setup batch places each pool ID at a unique location so that console tags identify the actual BlockDisplays. During each 60 Hz visual stream, console NBT positions must match generated frames and show at least ten distinct positions. At completion all eight positions and every scale axis must match the last output frame within 0.001. Queue counts include the setup batch.

All six trials passed with 719 visual frames sent and 720 total messages processed, including setup:

| Trial | Applied batches, including setup | Coalesced/dropped | Distinct sampled positions | Runtime + encoding p95 (ms) |
|-|-|-|-|-|
| Rust feature replay 1 | 241 | 479 | 48 | 0.1513 |
| Python feature replay 1 | 242 | 478 | 49 | 0.1485 |
| Python feature replay 2 | 241 | 479 | 47 | 0.1714 |
| Rust feature replay 2 | 241 | 479 | 48 | 0.1312 |
| Rust paced PCM → DSP → Lua | 242 | 478 | 48 | 0.1054 |
| Python replay of live DSP features | 241 | 479 | 47 | 0.2323 |

All 48 final entity checks (eight per trial) passed for position and all scale axes. Every server shut down gracefully, every console log had zero ERROR entries, and no Paper server process remained. The connected Rust PCM run's processing cost and its replay partner are separate trials, not a controlled claim about a whole production session.

Sender scheduling lateness p95 was 0.137–0.153 ms in the Rust trials and 1.106–1.135 ms in Python. These prototype senders use different sleep/event-loop mechanisms and fixed simulation dt; this scheduling observation is not a measured Python VJ scheduling regression. It produced no meaningful improvement in Paper's applied batch count. Startup and console polling add observer load, so paced costs are retained separately from the quieter offline trials.

Paper's newest-batch-per-zone tick behavior accounts for the coalescing. Sender write success and WebSocket ping/pong establish transport observations; actual sampled and final entity state establishes application. Queue counts do not imply that every 60 Hz source frame becomes a distinct Minecraft tick or client-visible frame.

## Verification, limits, and decision

Verification passed: 16 Rust tests (including existing DSP tests and prototype bounds/sandbox checks); 70 focused Python tests (12 rejection/measurement checks, 24 reused harness tests, 34 production pattern tests); Rustfmt; Ruff check/format; signed-commit hooks including Bandit. Existing WebSocket deprecation warnings remain. No production files needed changes, so a new full plugin package build was not substituted for the previously verified exact JAR.

Raw artifacts remain under `prototypes/rust-runtime-slice/results/`, with every feature/output frame, full Paper logs, worlds, and evidence. The early diagnostic series was deliberately stopped after finding that the Python comparator needed its production serializer; it is retained as diagnostic evidence and excluded from the final result. Early offline samples collected alongside builds/Paper are also excluded from the final performance figures.

There is no visually inspected Minecraft client, physical audio/display loopback, device capture, remote network, full VJ mixing/effects load, or installation acceptance in this result. No timestamps from different clocks are subtracted. The 21.333 ms input-window duration, local processing costs, sender lateness, Paper tick coalescing, and unknown physical audio-to-output delay remain distinct observations.

**Recommendation: keep Python in production for now, with Rust validated as a promising ownership candidate.** Preserve this prototype as a working compatibility and measurement harness. Rust retains the Lua behavior and lowers measured local processing cost, but the sub-millisecond savings do not improve this Paper delivery result. Before changing ownership, test representative visuals and multiple zones at the intended entity budget, then judge operational simplicity and properly measured output timing alongside processing cost. Continue the existing reliable-installation and polished-reference-visual priorities; this result does not justify a broad migration or SaaS expansion.
