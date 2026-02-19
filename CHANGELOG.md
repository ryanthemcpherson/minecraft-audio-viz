# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- **Rust DJ Client** — cross-platform Tauri desktop app replacing the Python DJ CLI
- **Multi-DJ support** — multiple remote DJs streaming to a centralized VJ server
- **27 visualization patterns** — from Spectrum Bars to Galaxy Spirals, Black Holes, Auroras, and more
- **DJ profile system** — slug URLs, color palettes, image uploads, and profile editing
- **Simple Voice Chat integration** — full-stack audio streaming via Opus + PCM
- **Geyser/Bedrock support** — per-player particle fallback for Bedrock clients
- **Coordinator API** — FastAPI service for DJ connect codes, show management, and JWT auth
- **Landing site** (mcav.live) — Next.js 15 with pattern gallery, getting started guide, and Discord OAuth
- **Tenant router** — Cloudflare Workers for multi-tenant subdomain routing
- **Timeline system** — pre-program timed shows with pattern, preset, and effect cues
- **6 audio presets** — auto, edm, chill, rock, hiphop, classical
- **Docker deployment** — containerized VJ server for production events
- **Admin panel** — VJ-style control surface with live meters, effects, and zone controls
- **3D browser preview** — WebGL scene with full Minecraft rendering parity
- **Onboarding flow** — role selection, org invites, and DJ profiles
- **Security scanning CI** — Bandit, pip-audit, SpotBugs, OWASP, cargo-audit, npm audit
- **GPG commit signing** — Ed25519 key with public key in repo

### Fixed
- Clock sync and auth for connect-code DJs
- VJ server latency correction using clock_sync offset
- Reconnection handling after server restart ("Already connected" error)
- Beat timing stability across DJ, relay, and plugin
- WebGL context exhaustion in pattern gallery
- Spectrum pattern spinning and jiggling artifacts

### Changed
- Reorganized VJ server into standalone `vj_server/` package (from `audio_processor/`)
- Archived Python DJ CLI in favor of Rust DJ client
- Ultra-low-latency mode (21ms window) as default FFT configuration
- Removed sub-bass band (1024-sample FFT cannot detect below 43Hz)

### Security
- Security headers, CSP, and CORS hardening
- Thread safety improvements across components
- Dependency audit automation via Dependabot (all ecosystems)
