# DSP timing baseline — 2026-09-11

This implements the bounded measurement step from the September 10 voice discussions. Measure before changing smoothing, beat detection, scheduling, or migrating DSP/runtime ownership. The one-JAR/one-extra-port install direction and direct DJ-to-server audio path remain the product priorities; SaaS remains control/discovery/ticketing. This branch adds observations and a synthetic DSP benchmark only.

## What the instrumentation means

The optional `dj_audio_frame.timing` object contains milliseconds measured entirely on the DJ's monotonic clock:

| Field | Observation |
|-|-|
| `buffer_to_analysis_ms` | Entry of the newest mono sample batch into the circular buffer to analysis start, including buffer copy/wait. Timestamp and samples are read under the same lock. |
| `analysis_ms` | BassLane, FFT, and kick merging, including analyzer lock waits. |
| `analysis_to_enqueue_ms` | Completed analysis to immediately before bridge serialization/enqueue. Reusing an older analysis increases this age. |
| `window_ms` | Nominal analyzed window duration: 1024 samples at 48 kHz = 21.333 ms. This is not another processing delay to add. |

Receipt is after downmix/voice feeding and buffer-lock acquisition, not hardware acquisition. Startup zero-padding and repeated-window analysis retain existing behavior. No telemetry is emitted before any samples arrive. Reversed, nonfinite, negative or over-60-second intervals are omitted; the nominal window must be positive and at most 60 seconds.

The server records `server_handler_ms` from handler entry (after WebSocket delivery/JSON decode) through audio state handling, and `server_receipt_to_selection_ms` from that same entry to selection by the render loop. Delayed frames carry their own server-local receipt timestamp; live frames use the latest accepted receipt. Selection age includes intentional visual delay and is observed **per render selection**, including reuse of an older frame. It includes handler time, so those two server intervals must not be added together. No-frame and dropped-frame cases do not fabricate samples.

The existing `/metrics` endpoint exposes:

```text
mcav_pipeline_window_ms{stage="dj_analysis_ms",statistic="p95"}
mcav_pipeline_window_samples{stage="dj_analysis_ms"}
```

Six fixed stages cover the four DJ fields (prefixed `dj_`) and two server intervals. Each retains at most 512 observations across all DJs, with nearest-rank p50/p95/max gauges and the current window count. No per-DJ labels or lifetime sample storage are added. Unobserved stages are omitted. These are observation windows, not time windows: old observations remain until replaced or server restart. DJ measurements are client-reported telemetry; they are not authenticated performance evidence.

Old frames still decode. Malformed optional timing objects are ignored while their audio is processed normally. Values are validated against the same names and bounds documented by the JSON Schema. The existing epoch-based `ts`, display latency, clock synchronization and auto-delay behavior are unchanged. In particular, the legacy `pipeline_latency_ms` fallback is **not** evidence of synchronized one-way latency.

## Reproduce

From `dj_client/src-tauri` on Windows:

```powershell
cargo test --lib --locked --no-default-features
cargo test --lib --locked
cargo test --lib --locked --no-default-features --config profile.test.opt-level=3 audio::dsp_benchmark -- --ignored --nocapture
```

The benchmark uses the production FftAnalyzer and BassLane, 48 kHz mono PCM, 1024-sample windows and a 480-sample hop (nominal 10 ms). Scenarios are digital silence, decaying 60 Hz kick bursts every 500 ms, and a 40 Hz–20 kHz linear chirp. One unreported run warms caches, then fresh DSP state processes the measured run. BassLane and FFT are timed separately; `sum_us` is their per-block sum, excluding sample copy and test assertions. Assertions check every frame's finite/bounded outputs and repeated signal results. There is no machine-dependent CI threshold.

FFT beat timing uses wall-clock time, so accelerated PCM cannot measure live onset latency, tempo lock, or BPM accuracy. BassLane is deterministic in sample space, but the existing production path replays overlapping windows into its state; its sample count is not elapsed stream time in this harness. Neither algorithm was changed.

From the repository root in WSL, using the project `.venv`:

```bash
.venv/bin/python -m pytest -c pyproject.toml vj_server/tests -q
ruff check vj_server/models.py vj_server/vj_server.py vj_server/metrics.py vj_server/pipeline_timing.py vj_server/tests/test_pipeline_timing.py vj_server/tests/test_metrics.py
```

## Evidence and limits

Host: Windows 11, Intel Core i5-13600KF, Rust 1.93.0. Server tests use WSL Python 3.12.13. One optimized test-profile run (`opt-level=3`, debug information retained; not the shipping `opt-level=s`/LTO release profile) produced:

| Scenario | Blocks | BassLane p50 / p95 / max (µs) | FFT p50 / p95 / max (µs) | Per-block sum p50 / p95 / max (µs) |
|-|-|-|-|-|
| Silence, 2 s | 198 | 3.7 / 5.0 / 23.6 | 4.2 / 6.2 / 20.3 | 7.9 / 11.3 / 33.8 |
| Kick bursts, 10 s | 998 | 29.6 / 31.4 / 54.3 | 4.3 / 5.1 / 23.9 | 33.8 / 36.2 / 64.6 |
| Sweep, 10 s | 998 | 3.6 / 3.8 / 10.3 | 4.1 / 4.9 / 11.0 | 7.7 / 8.9 / 15.8 |

The kick track produced 19 BassLane detections for 19 bursts. Silence produced zero kicks/beats. Wall-clock FFT beat counts are intentionally not treated as accuracy evidence. The combined p95 cost here ranges from 0.0089–0.0362 ms; these measurements do not establish the dominant delay in a live distributed session. The next useful evidence is scheduling/queue age and physical output timing, rather than inferring latency from FFT compute cost.

A follow-up regression check records the exact analysis windows that report kicks, pairs them chronologically with the known PCM bursts, and requires one detection per burst with none in silent gaps. Repeated runs must agree on detection windows as well as totals. This closes the aggregate-count blind spot where a missed burst and a false positive could cancel out. All five focused DSP tests passed after this change; the manual cost benchmark remains opt-in.

Verification: the full server suite passed 497 tests, including optional metadata validation, schema-bound parity, unchanged legacy audio, exact delayed-frame receipt correspondence, rate-limit omission, fixed metric storage and HTTP exposition. Five existing Node protocol tests passed. Ruff checks passed for all changed Python files; the new modules/tests are formatted. The final default-feature Rust suite passed 56 tests with one manual benchmark ignored; the optimized benchmark passed when invoked explicitly. Rustfmt checks and `git diff --check` passed. Existing WebSocket deprecation warnings remain; there were no final test failures. Build-generated Tauri schema files were restored to their original contents after testing.

Environment notes: the frozen workspace lock did not install the declared pytest-cov development dependency, so it was installed into the WSL project `.venv` without changing the lock. Tauri's default-feature test build needs frontend assets; the developer copied existing ignored `dj_client/dist` build output into this worktree for that build. No frontend code was changed or visually validated, and this is not a release-package validation.

This does not measure WASAPI/cpal device buffering, bridge outbound queue/write, network transit, server pre-handler queuing, Lua generation, Minecraft tick application or display interpolation. Local intervals and synthetic processing cost cannot be summed into end-to-end latency. The earlier 46–96 ms estimate was an unmeasured audit estimate, not a result of this benchmark.

The next live acceptance run should record audio input mode/device rate, preset, visual-delay mode, host load, existing render-profiler timings and these metric windows under representative music. Physical audio-to-visible-output delay requires a shared-clock loopback or external audio/video measurement, including the remote Paper server; neither is claimed here.

Changes are isolated on `feature/dsp-latency-baseline`. No DSP tuning or deployment is included. For publication, the user approved repairing the existing personal GitHub SSH key's file permissions and using that key for commit signing; the signing override is per command, leaving saved Git signing settings unchanged.
