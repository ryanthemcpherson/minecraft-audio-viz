# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- **Bitmap LED Wall** — flat 2D pixel-grid display mode using text display entities as individually-addressable pixels, inspired by [TheCymaera's text display experiments](https://github.com/TheCymaera/minecraft-text-display-experiments)
- **13 bitmap patterns** — Spectrum Bars, Spectrogram, Plasma, Waveform, VU Meter, Marquee, Track Display, Countdown, Chat Wall, Crowd Cam, Minimap, Fireworks, Image
- **Transition engine** — Crossfade, Wipe (4 directions), Dissolve, Iris In/Out with configurable durations
- **VJ effects processor** — Strobe, Freeze Frame, Color Palette, Beat Flash, Color Wash, Brightness, Blackout
- **Color palette system** — Fire, Ocean, Neon, Sunset, Matrix, Ice, Lava, Rainbow built-in palettes with LUT interpolation
- **Text rendering** — 5×7 pixel bitmap font (ASCII 32–126) with shadow, centering, and multi-line support
- **Game state modulation** — time-of-day color grading, weather desaturation, lightning flashes, crowd energy scaling
- **Multi-zone composition** — layer blending (Normal, Additive, Multiply, Screen, Lighten, Darken) with per-layer opacity
- **Python VJ client bitmap API** — 25+ protocol methods for full remote control of bitmap system
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
