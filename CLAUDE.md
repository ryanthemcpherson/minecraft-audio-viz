# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Minecraft Audio Visualizer is a real-time audio visualization system that captures system audio and displays reactive visualizations in Minecraft using Display Entities. The system includes a browser-based 3D preview and a professional DJ/VJ control interface.

## Architecture

Three-tier distributed system:
```
Windows Audio (WASAPI) → Python FFT Processor → WebSocket → Minecraft Plugin + Browser Preview
                                                    ↓
                                              Admin Panel (DJ Control)
```

### Core Components

1. **audio_processor/** (Python) - Audio capture, FFT analysis, beat detection, pattern generation
   - `cli.py` - CLI entry points (`audioviz`, `audioviz-vj`)
   - `config.py` - Centralized configuration and presets
   - `app_capture.py` - Per-application audio capture
   - `fft_analyzer.py` - 5-band FFT with beat/drum detection
   - `patterns.py` - 15+ visualization patterns (VisualizationPattern base class)
   - `vj_server.py` - Multi-DJ server for centralized control

2. **minecraft_plugin/** (Java 21, Paper API) - Minecraft visualization rendering
   - `AudioVizPlugin.java` - Main plugin entry point
   - `entities/EntityPoolManager.java` - Pre-allocated Display Entity pools with batch updates
   - `entities/EntityUpdate.java` - Immutable update record for batching
   - `zones/ZoneManager.java` - Visualization zone management
   - `websocket/VizWebSocketServer.java` - WebSocket server with async message queue
   - `protocol/MessageQueue.java` - Tick-based batch processing
   - `protocol/MessageHandler.java` - Message routing
   - `gui/menus/` - In-game inventory menus
   - `particles/` - Beat-reactive particle effects

3. **admin_panel/** - DJ control interface (vanilla JS with debouncing)
4. **preview_tool/frontend/** - Three.js 3D browser preview
5. **python_client/** - `VizClient` WebSocket client library
6. **scripts/** - Quick-start PowerShell scripts

7. **site/** (TypeScript/React) - Landing page and pattern gallery at mcav.live
   - Next.js 15 with App Router, React 19, Tailwind CSS 4
   - `src/app/` - Pages (home, getting started, pattern gallery, login, dashboard)
   - `src/components/` - Navbar, AuthProvider, Three.js visualizations
   - `src/lib/` - Auth utilities, audio simulation, pattern implementations
   - `package.json` (name: mcav-site) - Independent from root package.json

8. **coordinator/** (Python 3.12+) - Central DJ coordinator API
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

9. **worker/** (TypeScript) - Cloudflare Workers tenant router
   - `src/index.ts` - Wildcard subdomain routing for multi-tenant
   - `wrangler.toml` - Cloudflare Workers configuration
   - `package.json` (name: mcav-tenant-router)

### WebSocket Ports
- 8765: Minecraft plugin ↔ Python processor
- 8766: Browser clients ↔ Python processor
- 9000: Remote DJs ↔ VJ server
- 8090: Coordinator REST API
- 3000: Site dev server (Next.js)

### Frequency Bands
Bass (40-250Hz), Low-mid (250-500Hz), Mid (500-2000Hz), High-mid (2-6kHz), High (6-20kHz)

Note: The system uses 5 frequency bands with ultra-low-latency mode (21ms window) as default. Sub-bass was removed since 1024-sample FFT cannot accurately detect frequencies below 43Hz.

## Quick Start

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

### Installation
```bash
# Using UV (recommended)
uv pip install -e .

# Or using pip
pip install -e .

# With all optional dependencies
pip install -e ".[full]"
```

### Running
```bash
# Local DJ mode (captures Spotify, sends to localhost)
audioviz

# Specify application and host
audioviz --app chrome --host 192.168.1.100

# Test mode (no Minecraft, just spectrograph)
audioviz --test

# VJ server mode (for multi-DJ setups)
audioviz-vj

# List audio apps/devices
audioviz --list-apps
audioviz --list-devices
```

### PowerShell Scripts
```powershell
# Install
.\scripts\install.ps1

# Local DJ mode
.\scripts\start-local.ps1 -App spotify -Preview

# VJ server
.\scripts\start-vj-server.ps1

# Test audio capture
.\scripts\test-audio.ps1 -ListApps
```

## Build Commands

### Java (Minecraft Plugin)
```bash
cd minecraft_plugin
mvn package                                  # Build JAR with shaded dependencies
# Output: target/audioviz-plugin-1.0.0-SNAPSHOT.jar → copy to plugins/
```

### Tests
```bash
pytest                                       # Python tests
pytest audio_processor/tests/test_beat_detection.py  # Specific test
```

## Key Patterns & Extension Points

### Adding a Visualization Pattern
Extend `VisualizationPattern` in `audio_processor/patterns.py`:
```python
class MyPattern(VisualizationPattern):
    def calculate_entities(self, audio_state: AudioState, config: PatternConfig) -> List[EntityData]:
        # Return list of entity positions (0-1 normalized coordinates)
```
Register in `get_pattern()` and `list_patterns()`.

### Adding an Audio Preset
Add to `PRESETS` dict in `audio_processor/config.py`:
```python
"mypreset": AudioConfig(
    attack=0.5,
    release=0.1,
    beat_threshold=1.3,
    # ... other settings
)
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

- `pyproject.toml` - Python package configuration
- `.env` - Environment variables (copy from `.env.example`)
- `configs/dj_auth.json` - DJ credentials and permissions
- `minecraft_plugin/src/main/resources/config.yml` - Plugin settings
- `minecraft_plugin/src/main/resources/plugin.yml` - Plugin metadata

## Data Flow

Audio frames flow as JSON via WebSocket:
```json
{"bands": [0.8, 0.6, ...], "peak": 0.9, "beat": true, "frame": 12345}
```

Entity updates use batch_update messages with normalized (0-1) positions that ZoneManager converts to world coordinates.

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

### DJRelayConfig Options
```python
from audio_processor.dj_relay import DJRelayConfig

config = DJRelayConfig(
    max_connect_attempts=3,     # Initial connection retries (default: 3)
    reconnect_interval=5.0,     # Initial backoff in seconds (default: 5.0)
    heartbeat_interval=2.0,     # Heartbeat frequency in seconds (default: 2.0)
)
```

### VizClient Options
```python
from python_client import VizClient

client = VizClient(
    connect_timeout=10.0,       # Connection timeout in seconds (default: 10.0)
    auto_reconnect=False,       # Enable automatic reconnection (default: False)
    max_reconnect_attempts=10,  # Reconnection attempts (default: 10)
)
```

### Network Tuning Examples

**LAN Deployment (Low Latency):**
```python
# Aggressive reconnection for reliable local networks
config = DJRelayConfig(reconnect_interval=2.0, heartbeat_interval=1.0, max_connect_attempts=5)
client = VizClient(connect_timeout=5.0, auto_reconnect=True)
```

**WAN Deployment (High Latency):**
```python
# Patient reconnection for internet connections
config = DJRelayConfig(reconnect_interval=10.0, heartbeat_interval=5.0, max_connect_attempts=3)
client = VizClient(connect_timeout=30.0, auto_reconnect=True, max_reconnect_attempts=15)
```

## Platform Notes

- **Windows-only** audio capture (WASAPI via pycaw)
- Requires **Paper/Spigot 1.21.1+** (Display Entities)
- **Java 21** for Minecraft plugin
- **Python 3.11+** for audio processor
