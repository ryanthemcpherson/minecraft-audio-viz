# MCAV -- Minecraft Audio Visualizer

**Real-time audio to reactive visuals in Minecraft, browser, and beyond.**

MCAV captures system audio, performs real-time FFT analysis and beat detection, and renders reactive visualizations using Minecraft Display Entities, a Three.js browser preview, and a VJ control panel.

---

## Architecture

```text
System Audio (WASAPI/cpal)
    |
    v
DJ Client (Rust/Tauri) -- FFT + Beat Detection
    |
    v  WebSocket
VJ Server (Python/Lua) -- Pattern Engine
    |
    +---> Minecraft Plugin (Display Entities)
    +---> Browser 3D Preview (Three.js)
    +---> Admin Panel (VJ Controls)
```

**Multi-DJ mode** connects multiple remote DJs to a central VJ server for live events.

## Key Features

- **Windows Audio Capture** -- per-app WASAPI capture (Spotify, Chrome, any source)
- **Real-time FFT Analysis** -- 5-band frequency processing with ~20ms latency
- **28 Visualization Patterns** -- from Spectrum Bars to Galaxy Spirals, Auroras, and more
- **6 Audio Presets** -- auto, edm, chill, rock, hiphop, classical
- **Minecraft Rendering** -- Display Entity batching with interpolation and zone management
- **3D Browser Preview** -- WebGL scene with full Minecraft rendering parity
- **Admin Control Panel** -- VJ-style control surface with live meters and effects
- **DJ Client** -- cross-platform Tauri desktop app for remote DJ sessions
- **Multi-DJ Support** -- multiple remote DJs with centralized VJ control
- **Docker Deployment** -- containerized VJ server for production events

## Quick Links

| Section | Description |
|---------|-------------|
| [Getting Started](getting-started.md) | Install and run MCAV |
| [Docker Demo](deployment/docker-demo.md) | Try MCAV in your browser with zero install |
| [Pattern Development](guides/pattern-development.md) | Create custom Lua visualization patterns |
| [Audio Processing](guides/audio-processing.md) | FFT, beat detection, and presets |
| [WebSocket Protocol](reference/websocket-protocol.md) | Message schemas and data flow |
| [Contributing](contributing.md) | How to contribute to MCAV |

## Component Overview

| Component | Stack | Purpose |
|-----------|-------|---------|
| DJ Client | Rust/Tauri | Desktop app for audio capture and streaming |
| VJ Server | Python/Lua | Central pattern engine and DJ management |
| Minecraft Plugin | Java 21, Paper API | Display Entity rendering |
| Admin Panel | Vanilla JS | VJ control interface |
| Browser Preview | Three.js | 3D visualization preview |
| Coordinator | FastAPI, PostgreSQL | DJ coordination API |
| Site | Next.js 15 | Landing page at mcav.live |

## Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 8765 | WebSocket | Minecraft plugin data channel |
| 8766 | WebSocket | Browser client broadcast |
| 9000 | WebSocket | Remote DJ connections |
| 8080 | HTTP | Admin panel web interface |
