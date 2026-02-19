# Contributing to MCAV

Thanks for your interest in contributing to the Minecraft Audio Visualizer!

## Prerequisites

- **Python 3.11+** (audio processor, coordinator)
- **Java 21** (Minecraft plugin, Paper API)
- **Node.js 18+** (site, admin panel, DJ client)
- **Rust** (DJ client via Tauri)
- **Maven** (plugin build)

## Local Setup

```bash
# Python audio processor
pip install -e ".[dev]"
pytest audio_processor/tests/

# Minecraft plugin
cd minecraft_plugin && mvn package

# Site (Next.js)
cd site && npm install && npm run dev

# Coordinator API
cd coordinator && pip install -e ".[dev]" && pytest

# DJ Client (Tauri)
cd dj_client && npm install && npm run tauri dev
```

## Coding Standards

### Python
- Formatter/linter: [ruff](https://docs.astral.sh/ruff/)
- Run `ruff check` and `ruff format --check` before committing

### Java
- Target: Java 21, Paper API 1.21.1+
- Static analysis: SpotBugs (`mvn spotbugs:check`)

### TypeScript / JavaScript
- ESLint for site and DJ client (`npm run lint`)

### Rust
- `cargo fmt --check` and `cargo clippy -- -D warnings`

## Branch Naming

- `feature/<short-description>` -- new features
- `fix/<short-description>` -- bug fixes
- `chore/<short-description>` -- maintenance, CI, docs

## Pull Request Process

1. Fork the repo and create a branch from `main`.
2. Make your changes with tests where applicable.
3. Ensure CI passes locally:
   - Python: `ruff check && pytest`
   - Java: `mvn package`
   - Site: `npm run lint && npm run build`
4. Open a PR against `main` and fill out the PR template.
5. A maintainer will review and merge.

## Testing Requirements

- Python changes: add or update tests in `audio_processor/tests/`
- Coordinator changes: add or update tests in `coordinator/tests/`
- Java changes: build must succeed (`mvn package`)
- Site changes: lint and build must pass

## Questions?

Open a [Discussion](../../discussions) or file an [Issue](../../issues).
