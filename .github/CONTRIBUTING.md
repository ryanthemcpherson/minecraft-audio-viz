# Contributing to MCAV

Thanks for your interest in contributing to the Minecraft Audio Visualizer!

## Prerequisites

- **Python 3.11+** (VJ server, coordinator)
- **Java 21** (Minecraft plugin, Paper API)
- **Node.js 18+** (site, admin panel, DJ client)
- **Rust** (DJ client via Tauri)
- **Maven** (plugin build)

## Local Setup

```bash
# Python VJ server
cd vj_server && pip install -e ".[dev]"
pytest vj_server/tests/

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

- Python changes: add or update tests in `vj_server/tests/`
- Coordinator changes: add or update tests in `coordinator/tests/`
- Java changes: build must succeed (`mvn package`)
- Site changes: lint and build must pass

## Contributing Patterns

Custom visualization patterns are the #1 way to contribute to MCAV! Patterns are written in Lua and live in `patterns/*.lua`.

### Quick Start
1. Read the [Pattern Development Guide](../docs/PATTERN_GUIDE.md) for complete documentation
2. Copy the minimal template from the guide or study existing patterns in `patterns/`
3. Save your pattern as `patterns/yourpattern.lua`
4. Test with hot-reload (just save the file, VJ server picks it up automatically!)
5. Submit a PR with your pattern

### What Makes a Good Pattern
- **Smooth motion** - Use `smooth()` and `decay()` from `lib.lua`
- **Scalable** - Works well with 16-256 entities
- **Audio-reactive** - Responds to beats, bass, and frequency bands
- **Performant** - No object creation per frame, pre-allocate tables
- **Documented** - Clear `name`, `description`, and `category`

### Pattern Checklist
- [ ] Tested with 16, 64, 128, and 256 entities
- [ ] No out-of-bounds positions (x/y/z clamped to 0-1)
- [ ] Smooth, jitter-free motion
- [ ] Works well with multiple music genres
- [ ] Includes `name`, `description`, `category`, and `start_blocks` metadata
- [ ] No Lua syntax errors (`lua patterns/yourpattern.lua` runs clean)

See [PATTERN_GUIDE.md](../docs/PATTERN_GUIDE.md) for examples, best practices, and detailed API documentation.

## Questions?

Open a [Discussion](../../discussions) or file an [Issue](../../issues).
