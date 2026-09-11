# Paper rendering and cold-restart acceptance

The local renderer lifecycle gate passed on September 11, 2026 in WSL, using Paper 1.21.11 build 132 and Temurin Java 21.0.12.1+1. The disposable server used loopback listeners and the user explicitly accepted the Minecraft EULA. Managed VJ runtime installation was disabled.

## Tested bytes

- Plugin: `minecraft_plugin/target/audioviz-plugin-1.2.0.jar`
- Plugin SHA-256: `10f8bd76aa1b86f0ee3b7298f456585ca8936e5a6fbb566e8ea002d4d1f329ca`
- Paper SHA-256: `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`
- Java archive SHA-256: `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`

The plugin was built from the stage-restoration fix in `5fc0b451`; the subsequent changes before this run concern validation tooling and documentation. Download URLs and pins are in `deploy/paper/paper-lock.json`.

## Observed results

| Check | Clean start | Cold restart |
|-|-|-|
| Main-stage pool size | 8 | 8 |
| First BlockDisplay position | (2, 82, 1.5) | (2, 82, 1.5) |
| First scale on all axes | 0.3 | 0.3 |
| Second BlockDisplay position | (6, 86, 4.5) | (6, 86, 4.5) |
| Second scale on all axes | 0.8 | 0.8 |
| Graceful shutdown | Passed | Passed |

The cold restart restored the active stage and pool without an `activate_stage` or `init_pool` request. The harness queried a real BlockDisplay's Minecraft NBT after sending two synthetic entity batches; it did not infer movement from WebSocket acknowledgements. One entity was sampled while all eight received the batches.

Persisted `stages.yml` was byte-identical across the two shutdowns, with SHA-256 `542cb5bcaf815a11f7f2c7b587e613098c346da1a13d66fa8b5688551d674fbf`. No harness or Paper Java process remained after completion.

Full local evidence and console logs are retained under ignored `minecraft_plugin/target/paper-acceptance/run-7fqw883a/`. `evidence.json` reports `passed: true`. Logs and worlds remain local rather than becoming release artifacts.

## Harness correction and validation

The first attempt, retained in `run-gwvnb2wx`, failed with `GateError: No three-dimensional entity vector: []`. Paper's asynchronous `say` output reordered the harness's begin/end markers after the requested entity data. The plugin had applied the movement, but the reader missed its response.

Console queries now run sequentially, discard stale queued output, and wait for the actual command response. A regression test covers stale entity data and unrelated delayed chat. The fixture also specifies flat-world layers explicitly, resolving the first run's missing-layers warning. All 24 harness tests and Ruff checks passed before the successful rerun; the plugin JAR was unchanged between attempts.

Reproduction from WSL at the repository root:

```bash
.release-venv/bin/python scripts/release/paper_matrix.py \
  --plugin minecraft_plugin/target/audioviz-plugin-1.2.0.jar \
  --plugin-sha256 10f8bd76aa1b86f0ee3b7298f456585ca8936e5a6fbb566e8ea002d4d1f329ca \
  --accept-eula --startup-timeout 360
```

## Remaining acceptance work

Both startups logged `Failed to register events for class com.audioviz.AudioVizPlugin because de/maxhenkel/voicechat/api/VoicechatPlugin does not exist.` Simple Voice Chat was intentionally absent. The renderer checks passed, but the missing optional API prevents registration of the main plugin's event handlers. Fix optional-dependency isolation and verify those handlers register without Simple Voice Chat before claiming error-free standalone startup.

The fixture's entity-cap warnings are expected. Paper also reported that the pinned Minecraft version is behind its latest release; this run establishes only the pinned compatibility combination.

This result does not establish managed runtime download/install, a signed release candidate, live DJ capture, FFT/Lua processing, end-to-end latency, visual quality in a Minecraft client, or Pterodactyl compatibility. The one-JAR/no-terminal product requirement and three polished reference visuals from the voice discussion remain open acceptance work. No public release, tag, or deployment was made.
