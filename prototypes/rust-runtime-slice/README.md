# Measured Rust / Lua / Paper slice

Development experiment only. This crate compiles the repository's actual `FftAnalyzer` and `BassLane`, embeds the unchanged `patterns/lib.lua` and `patterns/spectrum.lua` ("Stacked Tower"), reproduces the Python wrapper's entity smoothing, and sends JSON text batches to a disposable loopback Paper server. Production Python, DSP, Lua, plugin source, and release implementation are unchanged.

The prototype deliberately supports one visual, 1–256 entities, default pattern settings, fixed simulation dt, and one local transport. It is not a replacement VJ server, DJ application, installable runtime, or public release. It does not implement the VJ's mixing, effects, visual delay, pattern transitions, authentication, reconnection, or production scheduling policies.

## Reproduce in WSL

Use Linux x86_64, WSL-native Python 3.12+, a C compiler, and Rust 1.93.0. Both measured runtimes and Paper run under WSL on the same machine. Never install these Python packages on native Windows.

From the repository root:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r prototypes/rust-runtime-slice/requirements.txt -e vj_server
bash prototypes/rust-runtime-slice/cargo-local.sh build --release --locked
bash prototypes/rust-runtime-slice/cargo-local.sh test --locked
.venv/bin/python -m pytest -c pyproject.toml \
  prototypes/rust-runtime-slice/test_compare.py \
  scripts/release/test_paper_matrix.py vj_server/tests/test_patterns.py -q
.venv/bin/python prototypes/rust-runtime-slice/compare.py \
  --output prototypes/rust-runtime-slice/results/offline
```

`cargo-local.sh` uses a worktree-local toolchain under `.tools/` if present, otherwise the installed WSL Cargo. To install the same isolated toolchain without changing your shell profile:

```bash
slice="$PWD/prototypes/rust-runtime-slice"
mkdir -p "$slice/.tools"
export CARGO_HOME="$slice/.tools/cargo" RUSTUP_HOME="$slice/.tools/rustup"
curl --fail --location --proto '=https' --tlsv1.2 https://sh.rustup.rs \
  -o "$slice/.tools/rustup-init.sh"
sh "$slice/.tools/rustup-init.sh" -y --no-modify-path --profile minimal --default-toolchain 1.93.0
```

The lock pins the crate dependencies. Release measurements use `opt-level=3`, without the DJ application's size-optimized/LTO release profile. `build.rs` extracts the production configuration/result structs verbatim and references the existing DSP module by path, avoiding a fork of the algorithm or a Tauri/device-capture dependency.

`compare.py` creates a 12-second f32le fixture: 2 seconds of silence, 6 seconds of 60 Hz decaying kick bursts at 120 BPM, and a 4-second 40 Hz–20 kHz chirp. Input is 48 kHz mono, analyzed in 1024-sample windows with an 800-sample hop (60 Hz); 719 complete windows result. The input window is 21.333 ms long, not 21.333 ms of processing time. Overlapping windows are passed into BassLane as in the current capture path; its internal sample counter is not elapsed PCM stream time.

Two fresh DSP runs must agree exactly on bands, amplitude, bass energy, and kick windows. The burst check requires 12 detections in the expected analysis windows, including the window that straddles the first burst. FFT beat/BPM/phase use wall-clock time and are intentionally frozen into a feature snapshot for the two-runtime comparison; accelerated DSP does not establish tempo accuracy. The snapshot's hash can change because it also records wall-clock fields and processing measurements. The PCM fixture hash is repeatable.

For each of 8, 64, and 128 entities, three fresh Rust and Python runs compare every frame, stable ID, band, visibility flag, position, scale, and rotation. Numeric tolerance is `1e-9`. The Python baseline calls the actual `LuaPattern.calculate_entities` with its flat-pack path, instruction/memory limits, and smoothing. Only its module-local dt clock is substituted (first frame 16 ms, then 1/60 s). The production `VizClient._encode` supplies msgspec serialization. The Rust bridge uses mlua/PUC Lua 5.4 with instruction and memory limits, typed table extraction, and matching smoothing; it is not tuned to favor the benchmark.

Each run excludes the first 60 frames from timing summaries while checking behavior on all frames. `runtime_us` covers audio table updates, Lua execution, extraction, and smoothing. `serialize_us` covers batch construction and JSON encoding. `runtime_and_serialize_us` is the per-frame sum before calculating percentiles. Disk evidence writes occur after timed/paced work. It is not a full VJ render-loop benchmark. Run the offline comparison without concurrent builds or Paper activity when evaluating speed; retain all three trial results.

## Disposable Paper comparison

Use the tested plugin built from `codex/self-installing-release` commit `e06996dbc46a12fcba1d9368abefce61035a078e`. That branch's Java 21 build, not this default-branch plugin source, produces the tested artifact. The previously verified JAR is in that worktree at `minecraft_plugin/target/audioviz-plugin-1.2.0.jar`, SHA-256 `c410612bdd11a18a5beaf9caa9f16bb0028b5aa5ac1352c08d9f81beefde067e`. Copy it into this worktree's ignored target directory. No plugin binary is committed here.

The unchanged harness, tests, pins, product-version fixture required by its pin test, and acceptance documentation were imported from that release branch. Other release work was not imported. Java and Paper downloads are pinned and checked by `scripts/release/paper_matrix.py`; its cache can be copied from the release worktree to avoid redownloading verified archives. The server's first-run dependency downloads still require network access.

After accepting the Minecraft EULA (already authorized for this local experiment):

```bash
.venv/bin/python prototypes/rust-runtime-slice/paper_compare.py \
  --features prototypes/rust-runtime-slice/results/offline/features.jsonl \
  --output prototypes/rust-runtime-slice/results/paper \
  --plugin minecraft_plugin/target/audioviz-plugin-1.2.0.jar \
  --plugin-sha256 c410612bdd11a18a5beaf9caa9f16bb0028b5aa5ac1352c08d9f81beefde067e \
  --accept-eula
```

Use a fresh output directory for each complete experiment. The harness creates a new world per trial and never deletes existing runs. Managed VJ installation and optional voice chat remain disabled. It performs four paired feature-replay trials in Rust/Python/Python/Rust order, followed by a connected PCM → Rust DSP → Lua → Paper run and a Python replay of the exact features recorded during that live PCM run. Thus both accelerated deterministic parity and the paced connected audio path are exercised.

Each trial creates an eight-entity club stage, sends one setup batch with unique positions, and tags each actual BlockDisplay by stable pool ID. During the 60 Hz stream it samples block_0's real positions through console NBT and requires at least ten distinct positions matching generated frames. At completion it checks all eight blocks' exact final positions and all three scale axes (tolerance 0.001). A WebSocket ping/pong fences byte-stream receipt; no batch acknowledgement is treated as proof of rendering. Shutdown queue counters must account for every sent frame plus the setup batch as either applied or coalesced/dropped. All servers stop gracefully and ERROR log entries fail the gate.

Paper applies the newest zone batch per tick, so coalescing 60 Hz input down to about 20 applied batches per second is expected. Sender scheduling lateness, send-call cost, frame counts, queue counts, and console observations are separate measurements. They do not measure one-way network latency, hardware capture, Minecraft client interpolation, or physical audio-to-visible-output delay. No visually inspected Minecraft client is claimed; the live evidence is actual server entity state, backed by complete Lua/Python output parity.

Raw PCM, feature snapshots, every output frame, server worlds, full logs, and JSON summaries stay in ignored `results/`. The checked-in report records compact evidence and hashes. The earlier DSP timing commits (`2dac895`, `27f45eee`) provide context; their instrumentation was not needed by this standalone clock-local harness. Its 800-sample hop and WSL build differ from their 480-sample-hop Windows benchmark, so their cost numbers are not directly interchangeable.

For manual integration, the binary offers:

```text
mcav-runtime-slice analyze PCM FEATURES
mcav-runtime-slice render FEATURES OUTPUT ENTITY_COUNT [LOOPBACK_PORT]
mcav-runtime-slice stream-pcm PCM OUTPUT ENTITY_COUNT LOOPBACK_PORT
```

The PCM stream mode records its live features alongside output entities, allowing an exact Python comparison without subtracting timestamps from different clocks.
