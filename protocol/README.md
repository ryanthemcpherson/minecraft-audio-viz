# Protocol Starter

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

## Initial message coverage
- `dj_auth`
- `code_auth`
- `dj_audio_frame`
- `dj_heartbeat`
- `batch_update`
- `state`/`audio` browser broadcast
- `set_zone_config`
- `set_renderer_backend`
- `renderer_capabilities`
- `get_renderer_capabilities`
- `set_hologram_config`

## Renderer backend keys
- `display_entities`
- `particles`
- `hologram`
