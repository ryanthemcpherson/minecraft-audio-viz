# AGENTS.md

Guidance for AI coding agents working in this repository. Keep changes factual, scoped, and consistent with the conventions below. See `CLAUDE.md` for design/aesthetic context and `.github/CONTRIBUTING.md` for the human contributor guide.

## Purpose

MCAV (Minecraft Audio Visualizer) turns live system audio into real-time reactive visuals. A Rust/Tauri **DJ client** captures audio and runs FFT/beat detection, streaming frames over WebSocket to a Python **VJ server**, which executes Lua visualization patterns and fans the resulting entity updates out to a **Minecraft plugin**, a **browser 3D preview**, and an **admin panel**. A FastAPI **coordinator** handles multi-tenant DJ/show coordination, with a Next.js **site** (mcav.live) and a Cloudflare **worker** for subdomain routing.

## Tech stack

| Component | Stack |
|-|-|
| DJ client | Rust (Tauri v2, cpal) + React 19 / TypeScript / Vite |
| VJ server | Python 3.11+, asyncio, websockets, Lua pattern engine |
| Minecraft plugin | Java 25, Paper API 26.2 (`maven.compiler.release=25`), Maven |
| Minecraft mod | Java 21, Fabric Loom, Gradle — historical/quarantined, still built in CI |
| Coordinator | Python 3.12+, FastAPI, SQLAlchemy async, Alembic, PostgreSQL |
| Site | Next.js 15, React 19, Tailwind CSS 4 |
| Worker | TypeScript, Cloudflare Workers (wrangler) |
| Admin panel / preview | Vanilla JS and Three.js, served by root Vite |

## Key directories and entry points

- `dj_client/` — `src-tauri/src/lib.rs` (Tauri commands), `src-tauri/src/audio/` (capture, fft, sources), `src/App.tsx`
- `vj_server/` — `vj_server.py` (server core), `cli.py` (`audioviz-vj` entry point), `patterns.py`, `config.py`, `auth.py`, `metrics.py`
- `minecraft_plugin/` — `pom.xml`, `src/main/java/...` (`AudioVizPlugin.java`, `entities/EntityPoolManager.java`, `websocket/VizWebSocketServer.java`)
- `minecraft_mod/` — Fabric server-side mod (map renderer + Polymer virtual entities)
- `coordinator/` — `app/main.py` (FastAPI app), `app/routers/`, `app/services/`, `alembic/`
- `site/` — `src/app/` (App Router pages), `src/components/`, `src/lib/`
- `patterns/` — Lua visualization patterns, auto-discovered from `patterns/*.lua`; `lib.lua` holds shared helpers
- `protocol/` — JSON Schema contracts for all cross-runtime WebSocket messages (**source of truth**)
- `admin_panel/`, `preview_tool/frontend/` — browser surfaces served by the root `vite.config.ts`
- `worker/`, `discord_bot/`, `community_bot/`, `shared/tokens.css`, `scripts/`, `docs/`
- `archive/python_dj_cli/` — archived, reference only; do not extend

Ports: 8765 plugin↔VJ, 8766 browser↔VJ, 9000 DJ↔VJ, 9001 VJ metrics, 8090 coordinator, 3000 site, 8100 community bot webhook.

## Build / run / test

The root `Makefile` is the fastest path (`make help` lists all targets):

```bash
make lint            # ruff check vj_server/
make format-check    # ruff format --check vj_server/
make test            # pytest vj_server/tests/
make build           # Fabric mod: cd minecraft_mod && ./gradlew build
make coordinator-test coordinator-lint
make site-lint site-build
make ci              # mirrors the CI pipeline locally
```

Per component:

```bash
cd vj_server && pip install -e ".[dev]" && audioviz-vj      # --no-auth for dev only
cd minecraft_plugin && ./mvnw package && ./mvnw test        # JDK 25; SpotBugs via mvn spotbugs:check
cd coordinator && pip install -e ".[dev]" && pytest && uvicorn app.main:app --reload --port 8090
cd site && npm install && npm run dev                       # http://localhost:3000
cd dj_client && npm install && npm run tauri dev            # npm run test:rust runs cargo test
cd dj_client/src-tauri && cargo fmt --check && cargo clippy -- -D warnings
node --test protocol/tests/phase0-schemas.test.mjs          # protocol contract tests
npm run dev                                                 # root Vite: admin panel + preview tool
```

CI (`.github/workflows/ci.yml`) runs: ruff lint/format, bandit SAST, pip-audit, Fabric mod Gradle build (JDK 21), Paper plugin Maven build + tests + JaCoCo (JDK 25), Paper 26.2 integration probe, site lint/test/build, coordinator lint/tests, and the protocol schema tests. Required checks are `CI Passed` and `Security Summary`.

## Conventions

- **Python**: ruff is the formatter and linter (`line-length = 100`, `target-version = "py311"`, lint rules `E,F,W,I`). pytest uses `asyncio_mode = "auto"`.
- **Java**: Paper plugin targets Java 25 / Paper API 26.2+; SpotBugs exclusions live in `minecraft_plugin/spotbugs-exclude.xml`.
- **TypeScript/JS**: ESLint for `site/` and `dj_client/`; `tsc --noEmit` must pass.
- **Rust**: `cargo fmt --check` and `cargo clippy -- -D warnings` are enforced in CI.
- **Pre-commit**: `.pre-commit-config.yaml` runs ruff, bandit, site ESLint/typecheck, and hygiene hooks (max added file size 700 KB). Install with `pre-commit install`.
- **Git**: conventional commit prefixes (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `ci:`); branches `feature/`, `fix/`, `chore/`, `docs/`, `release/`, `recovery/`. PRs target `main` — direct pushes to `main` are blocked by the repository ruleset. Never force-push shared branches or use `git reset --hard` as cleanup. See `docs/GIT_WORKFLOW.md`.
- **Protocol changes**: update the JSON Schemas in `protocol/schemas/` first. Every message needs a `type`; `v` defaults to `1.0.0`; minor versions are additive only. Audio payloads are fixed at 5 bands (bass 40-250, low-mid 250-500, mid 500-2000, high-mid 2-6k, high 6-20kHz).
- **Patterns**: add a Lua file to `patterns/` exposing `name`, `description`, and `calculate(audio, config, dt)`; positions are normalized 0-1. No registration needed. See `docs/PATTERN_GUIDE.md`.
- **Audio presets** must be added in **both** `dj_client/src-tauri/src/audio/fft.rs` and `vj_server/config.py`.
- **Tests**: Python changes need tests in `vj_server/tests/` or `coordinator/tests/`; Java changes must build; site changes must lint and build.
- **UI work** follows the dark, neon-accented design tokens in `shared/tokens.css` and the token table in `CLAUDE.md`.
