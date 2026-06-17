# Paper Plugin Production Polish Design

## Goal

Polish the Paper plugin version of MCAV for production reliability and live-show operation. The pass should make secure plugin connections work end to end, remove small deploy-time footguns, and give an operator clearer readiness signals before and during a set.

## Scope

This work targets the Paper plugin connection path and operator-facing tooling:

- `minecraft_plugin/` runtime config, command metadata, and status output.
- `vj_server/` connection settings and `VizClient` authentication behavior.
- `scripts/smoke_test.py` plugin readiness checks.
- Root and plugin documentation for install, secure config, and show readiness.

The existing untracked protocol handler split under `minecraft_plugin/src/main/java/com/audioviz/protocol/handlers/` is not the primary target. It may be used only if needed for a narrow reliability fix.

## Non-Goals

- Do not complete the full `MessageHandler.java` decomposition in this pass.
- Do not redesign the inventory GUI or command tree.
- Do not add new rendering features or visual patterns.
- Do not change Fabric mod behavior except for shared documentation if needed.

## Current Findings

The first review found several concrete polish issues:

- The plugin supports `ws-secret`, but `vj_server.VizClient` does not send an auth message and the VJ CLI/config has no plugin-secret option.
- `scripts/smoke_test.py` does not support plugin WebSocket auth, so it cannot verify production-style configs.
- `config.yml` defines `performance.max_entities_per_zone`, while `MessageHandler` and the untracked `CoreEntityHandler` read `max-entities-per-zone`.
- `plugin.yml` omits permissions already checked by command code: `audioviz.metrics`, `audioviz.sequence`, `audioviz.beatsync`, and `audioviz.recording`.
- The Paper plugin lacks a focused README, while the Fabric mod has one.
- Root README examples use `mvn package`; this Windows environment has the wrapper but no `mvn` on PATH.
- The smoke test setup hint says `/audioviz createzone main`, but the command is `/audioviz zone create main`.
- Local Maven verification is blocked by DNS resolving `repo.maven.apache.org`, even with escalation.

## Design

### Plugin WebSocket Authentication

Add plugin WebSocket auth as a first-class VJ server setting.

`vj_server.config.ServerConfig` should include `plugin_ws_secret`, sourced from `MCAV_PLUGIN_WS_SECRET`. `vj_server.cli` should expose `--plugin-ws-secret` and pass the value into `VJServer`. `VJServer` should pass it into `VizClient` when connecting to Minecraft.

`VizClient` should accept an optional `plugin_ws_secret`. After the WebSocket opens, but before reading the normal welcome message or starting normal requests, it should send:

```json
{"type":"auth","token":"..."}
```

when a secret is configured. It should wait for `{"type":"auth_ok"}` with a short timeout. Auth failures should leave the client disconnected and log a clear reason. When no secret is configured, behavior stays compatible with the current plugin default.

The receive-loop path needs care: authentication should happen before starting the heartbeat receive loop, otherwise two `recv()` consumers can race. After auth succeeds, the existing welcome-message flow can proceed.

### Plugin Config and Metadata

Use `performance.max_entities_per_zone` consistently anywhere entity pool caps are read. Keep backward compatibility only if low-risk: a temporary fallback to `max-entities-per-zone` can be accepted, but the canonical key is the documented nested path.

Declare permissions in `plugin.yml` for the command checks that already exist. Defaults should be conservative:

- `audioviz.metrics`: `op`
- `audioviz.sequence`: `op`
- `audioviz.beatsync`: `op`
- `audioviz.recording`: `op`

No command behavior should be expanded in this pass.

### Live-Show Readiness

Improve operator visibility without redesigning the UI.

`/audioviz status` should clearly show:

- WebSocket port and whether plugin auth is enabled.
- Connected client count.
- Zone count and active zone/stage summary where available.
- Active renderer/backend state at a high level.
- Bedrock/Geyser fallback state if available.

The smoke test should support a `--ws-secret` option and `MCAV_PLUGIN_WS_SECRET` environment fallback. It should send the auth message when configured, fail with a clear message on `auth_ok` timeout/failure, and keep unauthenticated dev behavior unchanged. Fix stale command hints.

Add a `--readiness` smoke-test mode that verifies:

- WebSocket connect/auth.
- `ping`.
- `get_zones`.
- renderer capabilities.
- bitmap catalog queries.

### Documentation

Add `minecraft_plugin/README.md` with:

- Requirements: Paper/Spigot/Purpur 1.21.11+, Java 21.
- Build commands for Windows and Unix wrapper usage.
- Install steps.
- Secure `ws-secret` setup with matching VJ server CLI/env value.
- Smoke test usage, including auth.
- Live-show checklist.
- Troubleshooting for auth failure, port binding, no zones, and high entity load.

Update root README examples to prefer the Maven wrapper over bare `mvn`, and add a concise secure plugin connection example. Do not rewrite old historical plan documents.

## Error Handling

- If plugin auth is configured and the server rejects it, `VizClient.connect()` should return `False` and log the rejection instead of appearing connected.
- If auth times out, close the client connection and surface a timeout-specific message.
- If a malformed auth response is received, treat it as an auth failure.
- Smoke test failures should identify whether the failure was connection, auth, protocol response, or missing zone setup.

## Testing

Expected verification:

- Unit tests for `VizClient` auth message emission and auth failure handling, using mocked WebSocket objects.
- Existing `vj_server` tests for connection paths updated for the new optional constructor/config field.
- Java tests or compile for plugin config/permission/status changes if dependency resolution is available.
- Smoke test argument parsing can be verified without a live server.
- Manual/local Maven verification should use the wrapper with Java 21. If dependency resolution remains blocked by DNS to Maven Central, report the exact blocker and do not claim Java tests passed.

## Implementation Order

1. Wire VJ server plugin secret through config, CLI, `VJServer`, and `VizClient`.
2. Add auth support to the smoke test.
3. Fix plugin config key drift and `plugin.yml` permissions.
4. Improve `/audioviz status` readiness output.
5. Add/update docs.
6. Run focused tests first, then broader verification where environment allows.

## Acceptance Criteria

- A production config with non-empty plugin `ws-secret` can be used by `audioviz-vj` by providing the same secret.
- A mismatched plugin secret fails clearly.
- The smoke test can verify both unauthenticated dev mode and authenticated plugin mode.
- Plugin entity caps use the documented config key.
- All permissions checked by command code are declared in `plugin.yml`.
- Paper plugin setup is documented without relying on globally installed Maven.
- No broad protocol handler refactor is required for this pass.
