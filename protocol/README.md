# Protocol Contracts

This directory is the contract source of truth for cross-runtime messages in AudioViz.

## Scope
- Message shapes for WebSocket traffic between DJs, VJ server, plugin, and browser clients.
- Versioning and compatibility rules.
- Machine-readable schemas for validation and test fixtures.
- Renderer backend selection and capability contracts.

## Current policy (v1.0.0)

1. Required field: `type` on every message.
2. Optional field: `v` for protocol version; if omitted, interpret as `1.0.0`.
3. Minor releases are additive only.
4. Breaking changes require a new major version and migration notes.
5. Audio band payloads are fixed to 5 bands unless major version changes.
6. Renderer backend values are stable contract keys, not UI labels.

## Layout
- `protocol/schemas/index.json`: schema inventory
- `protocol/schemas/types/`: shared reusable types
- `protocol/schemas/messages/`: message schemas

## How to use

1. Validate outbound and inbound payloads in each runtime against these schemas.
2. Store sample fixtures for critical flows in tests.
3. Block releases when contract tests fail.
4. Enforce backend fallback behavior through fixture-based tests.

## Message Coverage

### Core (Plugin ↔ Processor)
- `ping` / `pong` — Liveness heartbeat
- `error` — Error response for any failed request
- `get_zones` / `zones` — Zone listing
- `audio_state` — High-frequency FFT data (5-band + beat detection)
- `init_pool` — Display entity pool initialization
- `batch_update` — Batched entity position/scale updates
- `set_visible` — Zone visibility toggle (blackout)
- `set_render_mode` — Change visualization render mode
- `set_zone_config` — Zone configuration update

### VJ Controls
- `set_pattern` — Change visualization pattern
- `set_preset` — Apply audio processing preset (named or custom)
- `trigger_effect` — Trigger VJ effects (blackout, freeze, flash, strobe, pulse, wave, spiral, explode)

### Renderer Backend
- `set_renderer_backend` — Select rendering backend per zone
- `renderer_capabilities` / `get_renderer_capabilities` — Query supported backends
- `set_hologram_config` — Hologram backend configuration

### DJ Server
- `dj_auth` / `code_auth` — DJ authentication
- `dj_audio_frame` — Audio data from remote DJs
- `dj_heartbeat` — DJ session keepalive
- `state_broadcast` — State sync to browser clients

## Renderer backend keys
- `display_entities`
- `particles`
- `hologram`

## Frequency Bands (5-band system)
| Index | Band     | Range       |
|-------|----------|-------------|
| 0     | Bass     | 40-250 Hz   |
| 1     | Low-mid  | 250-500 Hz  |
| 2     | Mid      | 500-2000 Hz |
| 3     | High-mid | 2-6 kHz     |
| 4     | High     | 6-20 kHz    |

## Port Reference
| Port | Purpose                             |
|------|-------------------------------------|
| 8765 | Minecraft plugin ↔ Python processor |
| 8766 | Browser clients ↔ Python processor  |
| 9000 | Remote DJs ↔ VJ server              |
