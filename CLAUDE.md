# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Minecraft Audio Visualizer is a real-time audio visualization system that captures system audio and displays reactive visualizations in Minecraft using Display Entities. The system includes a browser-based 3D preview and a professional DJ/VJ control interface.

## Architecture

Distributed system with Rust DJ client + Python VJ server:
```
System Audio (WASAPI/cpal) → Rust DJ Client (FFT + Beat Detection) → WebSocket → VJ Server
                                                                          ↓
                                              Minecraft Plugin + Browser Preview + Admin Panel
```

### Core Components

1. **dj_client/** (Rust/Tauri) - Desktop DJ client for audio capture and streaming
   - `src-tauri/src/audio/capture.rs` - Audio capture via cpal (system + per-app process loopback)
   - `src-tauri/src/audio/fft.rs` - 5-band FFT, beat detection, BPM estimation, bass lane, audio presets
   - `src-tauri/src/audio/sources.rs` - Audio source enumeration (system, app, input device)
   - `src-tauri/src/audio/platform/` - Platform-specific audio (Windows WASAPI, macOS, Linux)
   - `src-tauri/src/protocol/` - WebSocket client, message types, connect-code auth
   - `src-tauri/src/voice.rs` - Voice streaming (Opus + PCM) for Simple Voice Chat
   - `src-tauri/src/lib.rs` - Tauri commands (start_capture, connect, set_preset, etc.)
   - `src/App.tsx` - React frontend (audio source selection, preset picker, voice controls)

2. **vj_server/** (Python) - Multi-DJ visualization server
   - `vj_server.py` - Central server: DJ auth, queue management, pattern engine, Minecraft relay
   - `patterns.py` - Lua pattern engine (LuaPattern) and pattern registry
   - `config.py` - Audio presets (auto, edm, chill, rock, hiphop, classical) and server config
   - `auth.py` - DJ/VJ authentication (bcrypt, SHA256, connect codes)
   - `cli.py` - CLI entry point (`audioviz-vj`)
   - `spectrograph.py` - Terminal spectrograph display
   - `pyproject.toml` (name: mcav-vj-server) - Independent package

3. **minecraft_plugin/** (Java 21, Paper API) - Minecraft visualization rendering
   - `AudioVizPlugin.java` - Main plugin entry point
   - `entities/EntityPoolManager.java` - Pre-allocated Display Entity pools with batch updates
   - `entities/EntityUpdate.java` - Immutable update record for batching
   - `zones/ZoneManager.java` - Visualization zone management
   - `websocket/VizWebSocketServer.java` - WebSocket server with async message queue
   - `protocol/MessageQueue.java` - Tick-based batch processing
   - `protocol/MessageHandler.java` - Message routing
   - `gui/menus/` - In-game inventory menus
   - `particles/` - Beat-reactive particle effects

4. **admin_panel/** - DJ control interface (vanilla JS with debouncing)
5. **preview_tool/frontend/** - Three.js 3D browser preview
6. **python_client/** - `VizClient` WebSocket client library (used by vj_server)
7. **scripts/** - Quick-start PowerShell scripts

8. **site/** (TypeScript/React) - Landing page and pattern gallery at mcav.live
   - Next.js 15 with App Router, React 19, Tailwind CSS 4
   - `src/app/` - Pages (home, getting started, pattern gallery, login, dashboard)
   - `src/components/` - Navbar, AuthProvider, Three.js visualizations
   - `src/lib/` - Auth utilities, audio simulation, pattern implementations
   - `package.json` (name: mcav-site) - Independent from root package.json

9. **coordinator/** (Python 3.12+) - Central DJ coordinator API
   - FastAPI with SQLAlchemy async, PostgreSQL, Alembic migrations
   - `app/main.py` - FastAPI application entry point
   - `app/config.py` - Pydantic Settings configuration (MCAV_* env vars)
   - `app/models/` - SQLAlchemy ORM models and Pydantic schemas
   - `app/routers/` - API routes (servers, shows, connect, auth, orgs, tenants)
   - `app/services/` - Business logic (auth, Discord OAuth, JWT, rate limiting)
   - `alembic/` - Database migrations
   - `tests/` - Pytest async tests
   - `pyproject.toml` (name: mcav-coordinator) - Independent from root pyproject.toml
   - `Dockerfile` + `Procfile` - Railway deployment

10. **worker/** (TypeScript) - Cloudflare Workers tenant router
    - `src/index.ts` - Wildcard subdomain routing for multi-tenant
    - `wrangler.toml` - Cloudflare Workers configuration
    - `package.json` (name: mcav-tenant-router)

11. **protocol/** - WebSocket message contract schemas (source of truth)
    - JSON Schema definitions for all cross-runtime messages
    - `schemas/messages/` - Message schemas (dj-audio-frame, batch-update, set-pattern, etc.)
    - `schemas/types/` - Shared types (audio-state, entity-update, renderer-backend)
    - Every message requires a `type` field; `v` field optional (defaults to `1.0.0`)

12. **patterns/** - Lua visualization patterns (28 files)
    - Executed by the VJ server's Lua pattern engine
    - `lib.lua` - Shared utilities used by all Lua patterns

13. **discord_bot/** - Discord bot for audio streaming
    - `bot.py` - Bot entry, `audio_sink.py` - Audio capture from Discord voice

14. **archive/python_dj_cli/** - Archived Python DJ CLI (replaced by dj_client/)
    - Preserved for reference only; see `archive/python_dj_cli/README.md`

### WebSocket Ports
- 8765: Minecraft plugin ↔ VJ server
- 8766: Browser clients ↔ VJ server
- 9000: Remote DJs (Rust client) ↔ VJ server
- 8090: Coordinator REST API
- 3000: Site dev server (Next.js)

### Frequency Bands
Bass (40-250Hz), Low-mid (250-500Hz), Mid (500-2000Hz), High-mid (2-6kHz), High (6-20kHz)

Note: The system uses 5 frequency bands with ultra-low-latency mode (21ms window) as default. Sub-bass was removed since 1024-sample FFT cannot accurately detect frequencies below 43Hz.

## Quick Start

### DJ Client (Rust/Tauri)
```bash
cd dj_client
npm install
npm run tauri dev                            # Development mode
npm run tauri build                          # Production build
```

### VJ Server (Python)
```bash
# Install VJ server
cd vj_server && pip install -e .

# Run VJ server (multi-DJ mode)
audioviz-vj
audioviz-vj --port 9000 --minecraft-host mc.local
audioviz-vj --no-auth                        # Dev only - skip authentication
```

### Site & Coordinator Development
```bash
# Site (landing page at mcav.live)
cd site && npm install && npm run dev   # http://localhost:3000

# Coordinator (DJ coordination API)
cd coordinator
pip install -e ".[dev]"
uvicorn app.main:app --reload --port 8090   # http://localhost:8090

# Run coordinator tests
cd coordinator && pytest
```

### PowerShell Scripts
```powershell
# Install VJ server
.\scripts\install.ps1

# VJ server
.\scripts\start-vj-server.ps1
```

## Build Commands

### Java (Minecraft Plugin)
```bash
cd minecraft_plugin
mvn package                                  # Build JAR with shaded dependencies
# Output: target/audioviz-plugin-1.0.0-SNAPSHOT.jar → copy to plugins/
```

### Rust (DJ Client)
```bash
cd dj_client/src-tauri
cargo build                                  # Build Rust backend
cargo test                                   # Run Rust tests
```

## Key Patterns & Extension Points

### Adding a Visualization Pattern
Create a new Lua file in `patterns/` (e.g., `patterns/mypattern.lua`):
```lua
name = "My Pattern"
description = "A custom visualization pattern"
recommended_entities = 64

function calculate(audio, config, dt)
    local entities = {}
    for i = 1, config.entity_count do
        entities[i] = {
            id = "block_" .. (i - 1),
            x = 0.5, y = 0.5, z = 0.5,
            scale = 0.2, rotation = 0, band = 0, visible = true,
        }
    end
    return entities
end
```
Patterns are auto-discovered from `patterns/*.lua` — no registration needed.

### Adding an Audio Preset
Presets are defined in both the Rust DJ client (`dj_client/src-tauri/src/audio/fft.rs`) and the VJ server (`vj_server/config.py`). Add to both:

**Rust (DJ client):**
```rust
AudioPreset { name: "mypreset", attack: 0.5, release: 0.1, beat_threshold: 1.3, .. }
```

**Python (VJ server):**
```python
"mypreset": AudioConfig(attack=0.5, release=0.1, beat_threshold=1.3, ...)
```

### Adding a Particle Effect
Extend `ParticleEffect` in `minecraft_plugin/.../particles/`:
```java
public class MyEffect extends ParticleEffect {
    @Override
    public void spawn(Location loc, ParticleEffectConfig config, double intensity) { }
}
```

### Adding In-Game Commands
Add case to switch in `AudioVizCommand.java`.

### Adding GUI Menus
Extend `Menu` in `minecraft_plugin/.../gui/menus/`.

## Configuration Files

- `vj_server/pyproject.toml` - VJ server Python package
- `dj_client/src-tauri/Cargo.toml` - DJ client Rust dependencies
- `.env` - Environment variables (copy from `.env.example`)
- `configs/dj_auth.json` - DJ credentials and permissions
- `minecraft_plugin/src/main/resources/config.yml` - Plugin settings
- `minecraft_plugin/src/main/resources/plugin.yml` - Plugin metadata

## Data Flow

Audio frames flow from DJ client to VJ server as JSON via WebSocket:
```json
{"type": "dj_audio_frame", "bands": [0.8, 0.6, ...], "peak": 0.9, "beat": true, "bpm": 128.0, "i_bass": 0.7, "i_kick": true, "seq": 12345}
```

The VJ server forwards visualization data to Minecraft as batch_update messages with normalized (0-1) positions that ZoneManager converts to world coordinates.

## Performance Notes

### Minecraft Plugin
- Uses batch entity updates (single scheduler call per tick)
- Async JSON parsing on dedicated thread
- Tick-based message queue (processes all messages once per tick)
- Entity pooling with interpolation for smooth visuals

### Admin Panel
- All sliders debounced (50ms) to prevent message spam
- RAF-throttled meter updates for smooth visuals

## Connectivity Configuration

All WebSocket connections support configurable timeouts and automatic reconnection with exponential backoff. See `docs/CONNECTIVITY.md` for full architecture documentation.

### DJ Client (Rust)
The Rust DJ client handles reconnection automatically with exponential backoff (1s to 30s, up to 10 retries). Configuration is managed through the Tauri frontend.

### VizClient Options (Python - used by VJ server)
```python
from python_client import VizClient

client = VizClient(
    connect_timeout=10.0,       # Connection timeout in seconds (default: 10.0)
    auto_reconnect=False,       # Enable automatic reconnection (default: False)
    max_reconnect_attempts=10,  # Reconnection attempts (default: 10)
)
```

## Platform Notes

- **DJ Client**: Cross-platform audio via cpal; per-app capture uses Windows Process Loopback API (build 20348+)
- **VJ Server**: Python 3.11+, runs on any platform
- Requires **Paper/Spigot 1.21.1+** (Display Entities)
- **Java 21** for Minecraft plugin
- **Rust** + **Node.js** for DJ client (Tauri v2)
