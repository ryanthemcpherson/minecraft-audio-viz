# Repo Roadmap

Status date: 2026-02-05

## Purpose
This roadmap turns the current multi-component codebase into a modular product platform that can be split into sub-repos safely.

It now includes a renderer-backend architecture so we can add `minecraft-hologram` without destabilizing current Display Entity and particle rendering paths.

## Product Boundaries

1. Protocol and contracts
- Shared WebSocket message schemas
- Versioning and compatibility policy
- Contract tests across Python, Java, Rust, and web clients

2. Core runtime (Python)
- Audio capture and analysis
- Pattern engine
- VJ server and timeline
- Python client library

3. Minecraft runtime (Java plugin)
- Message ingestion
- Zone and GUI management
- Renderer orchestration (backend selection and fallback)

4. Rendering backends
- Display Entities backend (current default)
- Particles backend (Bedrock compatible)
- Hologram backend (planned adapter)

5. Web control surface
- Admin panel
- 3D preview
- Browser transport and state rendering

6. DJ desktop client (Tauri)
- Connection and auth
- Local capture and FFT
- Direct and relay transport modes

7. Integrations and experiments
- Discord bot and future adapters
- Third-party renderer integrations

8. Platform and release
- CI/CD pipelines
- Packaging and release artifacts
- Deployment and docs quality

## Recommended Repo Strategy
Use a modular monorepo first, then split by runtime boundaries.

### Keep in current monorepo now
- `audio_processor` + `python_client`
- `admin_panel` + `preview_tool`
- protocol contracts (`protocol/`)

### First sub-repo candidates
- `minecraft_plugin`
- `dj_client`

### Split later
- `discord_bot` into an integrations repo after protocol stabilization

## Why This Sequence
- Protocol shapes are duplicated across stacks today.
- Splitting before shared contracts will increase breakage and coordination costs.
- Java plugin and DJ client already have separate build and release lifecycles.
- Renderer backend work needs contract stability before extracting plugin-adjacent modules.

## Renderer Backend Plan (New)

### Goal
Support multiple rendering providers per zone behind a stable contract:
- `display_entities`
- `particles`
- `hologram`

### Abstraction
Create a backend interface inside Minecraft runtime:
- `RendererBackend` (select, init, update_frame, apply_zone_config, teardown)
- `RendererRegistry` (capability detection and lifecycle)
- `RendererSelector` (active backend plus fallback backend)

### Runtime behavior
1. Control plane selects backend with `set_renderer_backend`.
2. Plugin reports support via `renderer_capabilities`.
3. If selected backend is unavailable or fails, plugin falls back to configured backend.
4. Audio->entity frame contract remains stable and backend-specific config is optional.

### Third-party `minecraft-hologram` path
- Integrate as optional backend adapter, not as default rendering path.
- Gate behind explicit feature flag and capability check.
- Keep fallback to `display_entities` or `particles` per zone.
- Treat as experimental until load/perf and recovery tests pass.

## Current Risks To Resolve First (P0)

1. Packaging and docs drift
- `pyproject.toml` requires Python `>=3.11`, while parts of docs/workflows still imply wider versions.
- Placeholder repository URLs remain in package metadata.
- Duplicate top-level READMEs (`README.md`, `README2.md`) can diverge.

2. Frontend build config drift
- Root `package.json` references missing files (`vite.config.admin.ts`, `vite.config.preview.ts`) and a non-existent `src/` lint target.

3. Contract drift in band count
- Some code paths still imply 6-band states while active runtime uses 5-band.

4. Large coupled modules
- `audio_processor/app_capture.py`
- `audio_processor/vj_server.py`
- `audio_processor/patterns.py`
- `admin_panel/js/admin-app.js`

5. Stubbed or partial functionality
- DJ client Windows per-app capture still marked TODO.
- Timeline crossfade transitions marked TODO.

6. New backend complexity risk
- Adding hologram backend without capability/fallback contracts would increase operational risk.

## Backlog By Product Part

### 1) Protocol and contracts
P0
- Publish canonical schemas in `protocol/schemas/`.
- Add compatibility policy and message inventory.
- Add contract fixture set for critical message types.
- Add renderer backend contracts: `set_renderer_backend`, `renderer_capabilities`, `set_hologram_config`.

P1
- Generate typed models for TypeScript/Rust/Python.
- Add CI contract checks against Python server and Java handler.
- Add backend compatibility fixtures (entities/particles/hologram fallback).

P2
- Add schema deprecation metadata and migration docs.

### 2) Core runtime (Python)
P0
- Extract transport, capture loop, and control API out of `app_capture.py`.
- Split VJ server responsibilities: auth/session/forwarding/broadcast.
- Add integration tests for reconnect and heartbeat behavior.

P1
- Move timeline API to an isolated module boundary with explicit handlers.
- Add message contract validation on ingress/egress.
- Forward renderer backend messages transparently to plugin runtime.

P2
- Add runtime metrics endpoint and structured tracing.

### 3) Minecraft runtime (Java plugin)
P0
- Isolate protocol parsing from Bukkit side effects.
- Add tests for zone conversion and message handler behaviors.
- Introduce `RendererBackend` abstraction and zone backend state.

P1
- Add replay tests from protocol fixtures.
- Add perf assertions for batched entity updates.
- Add hologram adapter with capability detection and fallback handling.

P2
- Expose lightweight health/metrics for operations.
- Add backend-specific fault injection tests.

### 4) Rendering backends
P0
- Normalize common frame input model used by all backends.
- Define capability matrix and fallback rules.

P1
- Implement `display_entities` and `particles` through shared backend interface.
- Add experimental `hologram` adapter and guardrails.

P2
- Promote hologram backend from experimental only after perf/reliability targets are met.

### 5) Web control surface
P0
- Consolidate duplicated preview rendering logic currently split between admin and standalone preview code.
- Enforce single 5-band state model.
- Fix root frontend scripts/config mismatch.

P1
- Shared state/store and shared visualization components.
- Add protocol fixture playback tests.
- Add backend selector UI (backend and fallback backend).

P2
- Progressive enhancement for remote and low-bandwidth sessions.

### 6) DJ desktop client
P0
- Implement real Windows per-app capture path.
- Add integration tests against mock VJ server for auth, reconnect, clock sync.

P1
- Add direct-mode failover tests and telemetry.
- Align message models with canonical protocol schemas.

P2
- Platform-specific capture improvements for macOS/Linux.

### 7) Integrations and experiments
P0
- Tag integrations as experimental until contract-compliant.
- Avoid direct internal imports across runtime boundaries.

P1
- Move integrations into dedicated repo after protocol maturity.

### 8) Platform and release
P0
- Keep workflow matrix but add shared contract gate.
- Define compatibility matrix by release (Core, Plugin, DJ client).
- Add renderer backend compatibility rows to release checklist.

P1
- Add staged release process with canary fixtures.

P2
- Add automated release notes from protocol and runtime changes.

## Milestones and Exit Criteria

### Milestone M1 (Weeks 0-2): Contract and drift cleanup
Done when:
- `protocol/` schemas exist for core message paths.
- Renderer backend contract schemas exist.
- 5-band consistency is enforced in active runtime and web state.
- Packaging/docs metadata is aligned.

### Milestone M2 (Weeks 3-6): Internal modularization
Done when:
- Core Python and web app are split into smaller internal modules.
- Contract fixtures run in CI for Python and Java.
- Plugin has renderer backend abstraction with existing backends migrated.

### Milestone M3 (Weeks 7-10): First repo extraction
Done when:
- `minecraft_plugin` and `dj_client` can be built/released independently.
- Cross-repo compatibility is checked by contract and integration tests.
- Experimental hologram backend works behind feature flag with fallback.

### Milestone M4 (Weeks 11-12): Integrations and hardening
Done when:
- Integrations are either contract-compliant or separated as experimental.
- Release process includes compatibility matrix and rollback path.
- Backend fault/recovery test suite is green.

## Immediate Next Actions

1. Lock protocol starter schemas in `protocol/schemas/messages/` and `protocol/schemas/types/`.
2. Add renderer schemas and enforce capability/fallback behavior in runtime.
3. Resolve obvious config drift (`package.json`, metadata URLs, docs version mismatches).
4. Enforce 5-band state consistency in web/admin and preview paths.
5. Break out `app_capture` transport and timeline routing into dedicated modules.

## Ownership Suggestion

1. Protocol owner
- Maintains schema/version policy and contract fixtures.

2. Runtime owners
- Python core owner
- Java plugin owner
- DJ client owner
- Web control owner
- Renderer backend owner

3. Release owner
- Maintains compatibility matrix and release gates.
