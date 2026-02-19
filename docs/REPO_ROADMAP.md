# Repo Roadmap

Status date: 2026-02-07 (updated)

## Purpose
This roadmap turns the current multi-component codebase into a modular product platform that can be split into sub-repos safely.

It now includes a renderer-backend architecture, central DJ coordinator service, deployment/domain strategy, and go-to-market planning.

## Related Strategy Documents
- [Creative Vision](CREATIVE_VISION.md) — Demo scenarios, new patterns, interactive experiences, art themes
- [Go-to-Market Strategy](GTM_STRATEGY.md) — Target audiences, distribution, content marketing, launch timeline
- [Coordinator Architecture](COORDINATOR_ARCHITECTURE.md) — Central DJ code routing service design
- [Deployment Strategy](DEPLOYMENT_STRATEGY.md) — Domain, hosting, CI/CD, Docker, monitoring
- [Refactoring Plans](REFACTORING_PLANS.md) — app_capture.py, vj_server.py, RendererBackend integration

## Live Infrastructure
- **Domain**: mcav.live (purchased, Cloudflare DNS)
- **Web platform** (merged into monorepo):
  - `site/` — Next.js 15 landing page
  - `coordinator/` — FastAPI DJ coordinator API
  - `worker/` — Cloudflare Workers tenant router
- **Hosting**: Railway ($5/mo hobby tier, PostgreSQL included)
- **MC server**: 192.168.1.204 (Paper 1.21.1, systemd, PlugManX hot-reload)

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

7. Central DJ Coordinator (NEW)
- FastAPI REST service for connect code resolution
- VJ server registration and discovery
- JWT-based authentication flow
- See [COORDINATOR_ARCHITECTURE.md](COORDINATOR_ARCHITECTURE.md)

8. Integrations and experiments
- Discord bot (audio streaming/sync — NOT local capture)
- Third-party renderer integrations

9. Platform and release
- CI/CD pipelines
- Packaging and release artifacts
- Deployment, domain, and hosting
- See [DEPLOYMENT_STRATEGY.md](DEPLOYMENT_STRATEGY.md)

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

1. ~~Packaging and docs drift~~ ✅ RESOLVED
- ~~`pyproject.toml` requires Python `>=3.11`, while parts of docs/workflows still imply wider versions.~~
- ~~Placeholder repository URLs remain in package metadata.~~ Fixed — now points to actual GitHub repo.
- ~~Duplicate top-level READMEs (`README.md`, `README2.md`) can diverge.~~ README2.md does not exist.

2. ~~Frontend build config drift~~ ✅ RESOLVED
- ~~Root `package.json` references missing files (`vite.config.admin.ts`, `vite.config.preview.ts`) and a non-existent `src/` lint target.~~ Fixed — scripts now use single `vite.config.ts` with `--open` flags.

3. ~~Contract drift in band count~~ ✅ RESOLVED
- All code paths now consistently use 5-band system (processor.py, config.py, app_capture.py, docs, tests, Java AudioState).

4. Large coupled modules — PLANS READY, see [REFACTORING_PLANS.md](REFACTORING_PLANS.md)
- `audio_processor/app_capture.py` → 7 modules planned
- `audio_processor/vj_server.py` → package with 11 modules planned
- `audio_processor/patterns.py`
- `admin_panel/js/admin-app.js`

5. Stubbed or partial functionality
- DJ client Windows per-app capture still marked TODO.
- ~~Timeline crossfade transitions marked TODO.~~ ✅ RESOLVED — CueExecutor now supports crossfade with easing functions.
- Known bug: `DJAuthConfig.from_dict()` called in cli.py but method doesn't exist (flagged in refactoring plan).

6. New backend complexity risk
- Adding hologram backend without capability/fallback contracts would increase operational risk.
- RendererBackend interface exists but is NOT yet wired into MessageHandler (plan ready).

7. ~~No public domain or hosted presence~~ PARTIALLY RESOLVED
- ~~mcav.live is available ($2.98/yr) — registration is a priority.~~ ✅ mcav.live purchased, Cloudflare DNS.
- mcav-site merged into monorepo (`site/`, `coordinator/`, `worker/`). Railway deployment pending.
- No public-facing documentation site yet.

## Backlog By Product Part

### 1) Protocol and contracts
P0
- ~~Publish canonical schemas in `protocol/schemas/`.~~ ✅ RESOLVED — 23 schemas covering core, VJ, renderer, and DJ messages.
- ~~Add compatibility policy and message inventory.~~ ✅ RESOLVED — protocol/README.md with versioning policy and full message inventory.
- Add contract fixture set for critical message types.
- ~~Add renderer backend contracts: `set_renderer_backend`, `renderer_capabilities`, `set_hologram_config`.~~ ✅ RESOLVED

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
- ~~Add tests for zone conversion and message handler behaviors.~~ ✅ RESOLVED — 90 JUnit tests covering AudioState, EntityUpdate, VisualizationZone, MessageHandler, MessageQueue.
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
- ~~Enforce single 5-band state model.~~ ✅ RESOLVED — All paths use 5-band, preview demo fixed.
- ~~Fix root frontend scripts/config mismatch.~~ ✅ RESOLVED — package.json scripts aligned with vite.config.ts.

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

### 7) Central DJ Coordinator
P0
- Build standalone coordinator service (FastAPI + SQLite). See [architecture doc](COORDINATOR_ARCHITECTURE.md).
- Implement connect code generation and resolution (WORD-XXXX format, 19.4M keyspace).
- VJ server registration and heartbeat endpoints.
- JWT token generation for DJ→VJ auth.

P1
- VJ server integration: auto-register on startup, create shows via API.
- DJ client integration: "Join by Code" flow, `token_auth` WebSocket message.
- Admin panel: show management dashboard.

P2
- Multi-region coordinator deployment.
- Analytics: show duration, DJ count, popular patterns.

### 8) Integrations and experiments
P0
- Tag integrations as experimental until contract-compliant.
- Avoid direct internal imports across runtime boundaries.

P1
- Move integrations into dedicated repo after protocol maturity.

### 9) Platform, release, and deployment
P0
- Keep workflow matrix but add shared contract gate.
- Define compatibility matrix by release (Core, Plugin, DJ client).
- Add renderer backend compatibility rows to release checklist.
- Register domain: **mcav.live** ($2.98/yr, available). See [DEPLOYMENT_STRATEGY.md](DEPLOYMENT_STRATEGY.md).
- Deploy static sites (admin panel, preview, landing) to Cloudflare Pages.

P1
- Add staged release process with canary fixtures.
- Deploy coordinator API to Fly.io (free tier).
- Set up Cloudflare Tunnel for MC server access.
- Add deploy-web.yml and deploy-api.yml CI workflows.
- Publish to distribution channels: Modrinth, SpigotMC, PyPI. See [GTM_STRATEGY.md](GTM_STRATEGY.md).

P2
- Add automated release notes from protocol and runtime changes.
- UptimeRobot monitoring for all endpoints.
- Discord webhook deploy notifications.

## Milestones and Exit Criteria

### Milestone M1 ✅ COMPLETE: Contract and drift cleanup
Done:
- ✅ `protocol/` schemas exist for core message paths (23 schemas).
- ✅ Renderer backend contract schemas exist.
- ✅ 5-band consistency enforced in all runtimes.
- ✅ Packaging/docs metadata aligned.
- ✅ Dev tooling installed (PlugManX, WorldEdit, mcrcon, hot-reload).
- ✅ Strategy documents written.

### Milestone M2 (Active): Internal modularization + infrastructure
Done when:
- `app_capture.py` decomposed into 7 modules.
- `vj_server.py` converted to package with 11 modules.
- Plugin has renderer backend abstraction with existing backends migrated through MessageHandler.
- Contract fixtures run in CI for Python and Java.
- mcav.live domain registered and DNS configured.

### Milestone M3: Coordinator + deployment
Done when:
- Central DJ coordinator service is operational (code resolution, JWT auth).
- VJ server integrates with coordinator (auto-registration, show management).
- Static sites deployed to Cloudflare Pages (admin panel, preview, landing).
- Coordinator deployed to Fly.io.
- CI/CD workflows for web and API deployment.

### Milestone M4: Launch + distribution
Done when:
- Hero demo video produced (see [CREATIVE_VISION.md](CREATIVE_VISION.md)).
- Published to Modrinth, SpigotMC, PyPI.
- DJ client has "Join by Code" flow working end-to-end.
- `minecraft_plugin` and `dj_client` can be built/released independently.
- Community Discord server live.
- See [GTM_STRATEGY.md](GTM_STRATEGY.md) for full launch checklist.

### Milestone M5: Hardening + growth
Done when:
- Integrations are either contract-compliant or separated as experimental.
- Release process includes compatibility matrix and rollback path.
- Backend fault/recovery test suite is green.
- 100 GitHub stars, 500 Modrinth downloads (30-day targets).
- First community event hosted.

## Immediate Next Actions

1. ~~Lock protocol starter schemas in `protocol/schemas/messages/` and `protocol/schemas/types/`.~~ ✅ DONE
2. ~~Add renderer schemas and enforce capability/fallback behavior in runtime.~~ ✅ DONE
3. ~~Resolve obvious config drift (`package.json`, metadata URLs, docs version mismatches).~~ ✅ DONE
4. ~~Enforce 5-band state consistency in web/admin and preview paths.~~ ✅ DONE
5. ~~Install dev tooling (PlugManX, WorldEdit, mcrcon, hot-reload pipeline).~~ ✅ DONE
6. ~~Write strategy documents (creative vision, GTM, coordinator, deployment, refactoring).~~ ✅ DONE

### Active Priorities
7. **Decompose `app_capture.py`** — Extract 7 modules per [refactoring plan](REFACTORING_PLANS.md#part-1-decompose-app_capturepy).
8. **Decompose `vj_server.py`** — Convert to package per [refactoring plan](REFACTORING_PLANS.md#part-2-decompose-vj_serverpy).
9. **Wire `RendererBackend` into MessageHandler** — Complete integration per [refactoring plan](REFACTORING_PLANS.md#part-3-wire-rendererbackend-into-the-message-pipeline).
10. ~~**Register mcav.live domain**~~ ✅ DONE — Purchased, Cloudflare DNS.
11. ~~**Build central DJ coordinator**~~ ✅ DONE — `coordinator/` in monorepo per [architecture doc](COORDINATOR_ARCHITECTURE.md).
12. Add contract fixture tests that validate messages against protocol schemas.
13. Build DJ Client per-app WASAPI capture pipeline (Rust).
14. Deploy to Railway (site + coordinator + PostgreSQL).
15. Prepare launch content per [GTM strategy](GTM_STRATEGY.md).

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
