<div align="center">
  <img src="mcav.png" alt="MCAV Logo" width="64" height="64">
  <h1>MCAV — Minecraft Audio Visualizer</h1>
  <p><strong>Real-time audio → reactive visuals in Minecraft, browser, and beyond</strong></p>

  [![CI](https://github.com/ryanthemcpherson/minecraft-audio-viz/actions/workflows/ci.yml/badge.svg)](https://github.com/ryanthemcpherson/minecraft-audio-viz/actions/workflows/ci.yml)
  [![Deploy](https://github.com/ryanthemcpherson/minecraft-audio-viz/actions/workflows/deploy.yml/badge.svg)](https://github.com/ryanthemcpherson/minecraft-audio-viz/actions/workflows/deploy.yml)
  [![Security](https://github.com/ryanthemcpherson/minecraft-audio-viz/actions/workflows/security.yml/badge.svg)](https://github.com/ryanthemcpherson/minecraft-audio-viz/actions/workflows/security.yml)

  ![Rust](https://img.shields.io/badge/rust-stable-orange.svg)
  ![Python 3.11+](https://img.shields.io/badge/python-3.11+-blue.svg)
  ![Java 21](https://img.shields.io/badge/java-21-orange.svg)
  ![Fabric](https://img.shields.io/badge/fabric-MC%201.21.1-green.svg)
  ![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)
  ![Platform: Windows](https://img.shields.io/badge/platform-Windows-lightgrey.svg)
</div>

---

<p align="center">
  <img src="images/admin_panel_full.png" alt="Admin Panel" width="32%">
  <img src="images/admin_panel_3d_preview.png" alt="3D Preview" width="32%">
  <img src="images/djclient_preview.png" alt="DJ Client" width="32%">
</p>

---

## Table of Contents

- [Features](#features)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Screenshots & Demo](#screenshots--demo)
- [Visualization Patterns](#visualization-patterns)
- [CLI Reference](#cli-reference)
- [Minecraft Commands](#minecraft-commands)
- [Project Structure](#project-structure)
- [Development](#development)
- [Known Limitations](#known-limitations)
- [Acknowledgments](#acknowledgments)
- [License](#license)

---

## Features

- **Windows Audio Capture** — per-app WASAPI capture (Spotify, Chrome, any audio source)
- **Real-time FFT Analysis** — 5-band frequency processing with ultra-low latency (~20ms)
- **80+ Visualization Patterns** — 41 Lua 3D patterns + 42 Java bitmap patterns, from Spectrum Bars to Galaxy Spirals, Auroras, Plasma, and more
- **Dual Render Backends** — high-res map tile displays (128x128 per tile) and virtual entity LED walls, switchable per zone
- **6 Audio Presets** — auto, edm, chill, rock, hiphop, classical
- **Minecraft Rendering** — Fabric mod with SGUI menus, Polymer virtual entities, beat-reactive particles, and ambient lighting
- **3D Browser Preview** — WebGL scene with full Minecraft rendering parity
- **Admin Control Panel** — VJ-style control surface with live meters, effects, and zone controls
- **DJ Client** — cross-platform Tauri desktop app (Rust) for remote DJ sessions
- **Multi-DJ Support** — multiple remote DJs performing with centralized VJ control
- **Stage System** — multi-zone stages with decorators, spotlight effects, and DJ billboards
- **Timeline System** — pre-program timed shows with pattern, preset, and effect cues
- **Coordinator API** — central DJ coordination with connect codes, show management, and JWT auth
- **Docker Deployment** — containerized VJ server for production events

---

## Quick Start

### Try the Demo (Zero Install)

**Requirements:** Docker only

Experience MCAV in your browser with simulated audio:

```bash
git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz
docker compose -f docker-compose.demo.yml up
```

Then open:
- **http://localhost:8080** - Admin Panel
- **http://localhost:8081** - 3D Preview

See [`demo/README.md`](demo/README.md) for full details.

### Download DJ Client (Recommended)

**[Download Latest Release](https://github.com/ryanthemcpherson/minecraft-audio-viz/releases)**

The DJ Client is a cross-platform desktop app (Windows/macOS/Linux) for capturing and streaming audio:

1. Download the installer for your platform from [GitHub Releases](https://github.com/ryanthemcpherson/minecraft-audio-viz/releases)
2. Install and launch the DJ Client
3. Select your audio source (Spotify, Chrome, system audio, etc.)
4. Connect to a VJ server or run in standalone mode with browser preview

### VJ Server Setup

**Requirements:** Python 3.11+ (VJ server can run on any platform)

```bash
# 1. Clone & install VJ server
git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz
pip install -e vj_server/     # Install VJ server package

# 2. Start VJ server (multi-DJ mode)
audioviz-vj                   # Starts on port 9000

# 3. Open in browser
#    3D Preview:   http://localhost:8080
#    Admin Panel:  http://localhost:8081
```

### Minecraft Mod Setup (Optional)

To render visualizations in Minecraft, build the Fabric mod and add it to your server's `mods/` folder:

```bash
cd minecraft_mod && ./gradlew build
# Copy build/libs/audioviz-mod-*.jar to your server's mods/ folder
# Requires: Fabric Loader, Fabric API, SGUI, Polymer
# Configure VJ server: audioviz-vj --minecraft-host your-mc-server
```

---

## Architecture

### Single DJ Mode (standalone)

```
DJ Client (Rust/Tauri) ---> VJ Server (Python/Lua) ---> Minecraft Mod (Fabric)
     Audio Capture              Pattern Engine          Map/Entity Renderer
                                     |
                          +----------+----------+
                          v                     v
                    Browser 3D            Admin Panel
                     Preview              Control UI
```

### Multi-DJ Mode (live events)

```
DJ Client 1 ---+
DJ Client 2 ---+--> VJ Server (Central) ---> Minecraft (Shared)
DJ Client 3 ---+         |
                    +-----+-----+
                    v           v
                 Viewers     VJ Admin
                (Browser)     Panel
```

---

## Screenshots & Demo

### Admin Control Panel
![Admin Panel](images/admin_panel_full.png)

### 3D Browser Preview
![3D Preview](images/preview_active.png)

### Zone Management
![Zone Management](images/admin_panel_zones.png)

**Demo Video:** [Watch on YouTube](https://www.youtube.com/watch?v=zH30YXrc2uw)

---

## Visualization Patterns

### Lua 3D Patterns (41)

3D entity-based patterns computed by the VJ server's Lua engine and rendered by the Minecraft mod using virtual entities.

| Pattern | Key | Description |
|-|-|-|
| Spectrum Bars | `bars` | Classic frequency bar display |
| Stacked Tower | `spectrum` | Vertical stacking bars |
| Spectrum Tubes | `tubes` | 3D tube-based spectrum analyzer |
| Spectrum Circle | `circle` | Circular spectrum layout |
| DNA Helix | `wave` | Double helix rotating structure |
| Atom Model | `orbit` | Orbital electron visualization |
| Expanding Sphere | `ring` | Pulsing sphere that expands with bass |
| Floating Platforms | `columns` | Suspended platforms responding to audio |
| Fountain | `matrix` | Particle fountain effect |
| Breathing Cube | `heartbeat` | Cube that expands/contracts with music |
| Mushroom | `mushroom` | Organic mushroom-shaped visualization |
| Skull | `skull` | Beat-reactive skull pattern |
| Sacred Geometry | `sacred` | Mathematical sacred geometry patterns |
| Vortex | `vortex` | Spinning vortex tunnel |
| Pyramid | `pyramid` | Egyptian pyramid with audio response |
| Galaxy Spiral | `galaxy` | Spiral galaxy visualization |
| Laser Array | `laser` | Concert-style laser beam array |
| Supernova | `explode` | Explosive supernova effect |
| Mandala | `mandala` | Symmetrical mandala visualization |
| Tesseract | `tesseract` | 4D hypercube projection |
| Crystal Growth | `crystal` | Growing crystal structure |
| Black Hole | `blackhole` | Gravitational lensing effect |
| Nebula | `nebula` | Space nebula cloud |
| Wormhole Portal | `wormhole` | Wormhole tunnel visualization |
| Aurora | `aurora` | Northern lights effect |
| Ocean Waves | `ocean` | Ocean wave simulation |
| Fireflies | `fireflies` | Swarm of glowing fireflies |
| Laser Fan | `laserfan` | Scanning laser fan array |
| Moving Heads | `movingheads` | Concert moving head lights |
| Pyrotechnics | `pyro` | Stage pyro flame effects |
| Shockwave | `shockwave` | Expanding shockwave rings |
| Crown | `crown` | Rotating crown structure |
| Dragon | `dragon` | Beat-reactive dragon form |
| Phoenix | `phoenix` | Rising phoenix visualization |
| Fist | `fist` | Pumping fist animation |
| Sword | `sword` | Glowing sword visualization |
| LED Wall | `ledwall` | Flat LED grid rendering |
| Drop Sequence | `dropsequence` | Build-up and drop animation |
| BPM Pulse | `bpm_pulse` | BPM-synced pulsing effect |
| BPM Strobe | `bpm_strobe` | BPM-synced strobe effect |
| Strobe | `strobe` | Classic strobe effect |

### Bitmap Patterns (42)

Flat 2D pixel-grid patterns rendered in-mod via map tile displays (128x128 per tile, glow item frames) or virtual entity LED walls. Includes effects processing, transitions, and layer compositing.

| Pattern | Key | Description |
|-|-|-|
| Spectrum Bars | `bmp_spectrum_bars` | Classic LED bar graph with color mapping |
| Spectrogram | `bmp_spectrogram` | Scrolling frequency x time heat map |
| Plasma | `bmp_plasma` | Audio-reactive plasma shader |
| Waveform | `bmp_waveform` | Oscilloscope-style waveform display |
| VU Meter | `bmp_vu_meter` | Stereo VU meter with peak hold |
| Fire | `bmp_fire` | Fluid fire simulation |
| Kaleidoscope | `bmp_kaleidoscope` | Symmetrical kaleidoscope effect |
| Matrix Rain | `bmp_matrix_rain` | Digital rain cascade |
| Starfield | `bmp_starfield` | Warp-speed starfield |
| Circular Spectrum | `bmp_circular` | Radial spectrum analyzer |
| Aurora | `bmp_aurora` | Northern lights effect |
| Checkerboard Flash | `bmp_checkerboard` | Beat-synced checkerboard |
| Color Wash | `bmp_color_wash` | Smooth color gradient sweep |
| Concentric Rings | `bmp_rings` | Expanding ring visualization |
| Data Mosh | `bmp_datamosh` | Glitch art data corruption |
| Digital Noise | `bmp_noise` | Audio-reactive digital noise |
| Fireflies | `bmp_fireflies` | Glowing particle swarm |
| Fractal Zoom | `bmp_fractal` | Mandelbrot zoom animation |
| Galaxy | `bmp_galaxy` | Spiral galaxy with star particles |
| Grid Warp | `bmp_grid_warp` | Audio-warped grid mesh |
| Hex Grid | `bmp_hex_grid` | Hexagonal grid visualization |
| Ink Drop | `bmp_ink_drop` | Fluid ink drop simulation |
| Lightning | `bmp_lightning` | Beat-triggered lightning bolts |
| Moire | `bmp_moire` | Interference pattern animation |
| Particle Rain | `bmp_particle_rain` | Falling particle systems |
| Pixel Sort | `bmp_pixel_sort` | Glitch-art pixel sorting |
| Radial Burst | `bmp_radial_burst` | Explosive radial pattern |
| Ripple | `bmp_ripple` | Water ripple propagation |
| Rotating Geometry | `bmp_geometry` | Rotating 3D wireframes |
| Scan Lines | `bmp_scan_lines` | Retro scan line effect |
| Terrain | `bmp_terrain` | Audio-reactive terrain mesh |
| Tunnel Zoom | `bmp_tunnel` | Infinite tunnel zoom |
| Wave Propagation | `bmp_wave` | Wave interference patterns |
| Marquee | `bmp_marquee` | Scrolling text with reactive colors |
| Track Display | `bmp_track_display` | Now-playing artist/title overlay |
| Countdown | `bmp_countdown` | Event countdown timer |
| Chat Wall | `bmp_chat_wall` | Live player chat messages |
| Crowd Cam | `bmp_crowd_cam` | Spotlight frames for nearby players |
| Minimap | `bmp_minimap` | Overhead map with pulsing player dots |
| Fireworks | `bmp_firework` | Interactive firework particle system |
| DJ Logo | `bmp_dj_logo` | Custom DJ logo display |
| Image | `bmp_image` | Static/animated image display |

---

## CLI Reference

### VJ Server Commands

```bash
# Start VJ server (multi-DJ mode)
audioviz-vj                               # start on default port 9000
audioviz-vj --port 9000                   # custom DJ port
audioviz-vj --minecraft-host mc.local     # connect to Minecraft server
audioviz-vj --no-auth                     # dev mode - skip authentication
audioviz-vj --metrics-port 9001           # health metrics endpoint
```

### DJ Client

The DJ Client is a desktop GUI app. Audio source selection and streaming controls are in the app interface.

**Download:** [GitHub Releases](https://github.com/ryanthemcpherson/minecraft-audio-viz/releases)

For development, see `dj_client/README.md`

---

## Minecraft Commands

| Command | Description |
|-|-|
| `/audioviz menu` | Open the main control panel (`/av menu`, `/mcav menu`) |
| `/audioviz zone create <name>` | Create a new visualization zone |
| `/audioviz zone delete <name>` | Delete a zone |
| `/audioviz zone list` | List all zones |
| `/audioviz zone setsize <name> <x> <y> <z>` | Set zone dimensions |
| `/audioviz zone setrotation <name> <degrees>` | Set zone rotation |
| `/audioviz zone info <name>` | Show zone details |
| `/audioviz test <zone> <wave\|pulse\|random>` | Run test animation |
| `/audioviz status` | Show mod status |
| `/audioviz help` | Show command help |

---

<details>
<summary><strong>Multi-DJ Mode (Live Events)</strong></summary>

### 1) Start the VJ Server (central control)

```bash
audioviz-vj
```

Defaults:
- DJ connection port: `ws://localhost:9000`
- Browser preview: `http://localhost:8080`
- Admin panel: `http://localhost:8081`

### 2) DJs connect using DJ Client (each DJ machine)

1. Download and install the [DJ Client](https://github.com/ryanthemcpherson/minecraft-audio-viz/releases)
2. Launch the DJ Client
3. Enter VJ server connection details (host, port, DJ name, connect code)
4. Select audio source and start streaming

### 3) Optional: DJ authentication (recommended)

The VJ server supports connect-code authentication for multi-DJ sessions. Configure DJ credentials in `vj_server/config.py` or use environment variables.

For production, the VJ server enforces authentication by default.

</details>

<details>
<summary><strong>Minecraft Mod Features</strong></summary>

### GUI menu system (SGUI)
- Main menu (system status, active zones, connection info)
- DJ control panel (effects, presets, zone selection)
- Stage management (create/edit stages, assign zone roles)
- VJ control panel (pattern selection, intensity slider, render mode toggle)
- Zone management + zone editor (size/rotation/placement)
- Stage decorator menus (spotlights, DJ banners, floor tiles)
- Settings menu (performance tuning)

### Dual render backends
- **Map display** — high-resolution 128x128 pixel maps in glow item frames, dirty-rect tracking for efficient updates
- **Virtual entity LED wall** — Polymer virtual block display entities as individually-addressable pixels

### Beat effects
- Particle bursts on beats (bass flame, beat ring, spectrum dust, ambient mist)
- Beat event system with configurable thresholds
- Ambient lighting that responds to audio state
- Stage decorators (spotlights, DJ billboards, floor tiles, beat text FX)

### Performance optimizations
- Batched entity updates via Polymer virtual entities (no real entity overhead)
- Async bitmap rendering on dedicated thread pool
- Tick-based message queue with bundle packet sending
- Map dirty-rect tracking (only re-send changed regions)
- Entity pool management + interpolation

</details>

<details>
<summary><strong>Timeline System</strong></summary>

Pre-program visualization shows:
- **Tracks:** Patterns, Presets, Effects, Parameters
- **Cues:** timed events that trigger actions
- **Triggers:** time-based, beat-synced, or manual
- **Transport:** play/pause/stop/seek

</details>

<details>
<summary><strong>Docker Support</strong></summary>

The VJ server can run in Docker for deployment:

```bash
docker-compose up -d
docker-compose logs -f vj-server

MINECRAFT_HOST=mc.example.com docker-compose up -d
```

> Note: **Audio capture requires Windows** and cannot run in Docker. DJs run locally and connect to the containerized VJ server.

</details>

---

## Project Structure

```text
minecraft-audio-viz/
+-- dj_client/             # DJ Client (Rust/Tauri, audio capture + FFT)
+-- vj_server/             # VJ Server (Python, Lua pattern engine + routing)
+-- minecraft_mod/         # Fabric mod (Java 21, MC 1.21.1)
+-- admin_panel/           # Web control panel (VJ interface)
+-- preview_tool/          # 3D browser preview (Three.js)
+-- site/                  # Landing page (Next.js 15, mcav.live)
+-- coordinator/           # DJ coordinator API (FastAPI, PostgreSQL)
+-- community_bot/         # Discord community bot (discord.py)
+-- discord_bot/           # Discord voice audio capture bot
+-- worker/                # Tenant router (Cloudflare Workers)
+-- protocol/              # Shared WebSocket protocol schemas
+-- patterns/              # Lua 3D visualization patterns (41)
+-- configs/               # Configuration files
+-- docs/                  # Architecture and ops docs
+-- scripts/               # PowerShell quick-start scripts
+-- shows/                 # Saved show files
+-- archive/               # Archived components
    +-- python_dj_cli/     # Old Python DJ CLI (deprecated)
```

### Web Platform (mcav.live)

| Component | Path | Stack | Purpose |
|-|-|-|-|
| Landing Site | `site/` | Next.js 15, Tailwind CSS 4, Three.js | Product page, pattern gallery, getting started |
| Coordinator | `coordinator/` | FastAPI, SQLAlchemy, PostgreSQL | DJ connect codes, show management, JWT auth |
| Tenant Router | `worker/` | Cloudflare Workers, TypeScript | Multi-tenant subdomain routing |

---

## Development

```bash
# VJ Server
cd vj_server && pip install -e ".[dev]" && pytest

# Minecraft Mod (Fabric)
cd minecraft_mod && ./gradlew build

# DJ Client (Rust/Tauri)
cd dj_client && npm install && npm run tauri dev

# Coordinator API
cd coordinator && pip install -e ".[dev]" && pytest

# Site (Next.js)
cd site && npm install && npm run dev
```

---

## Known Limitations

- **Windows-only audio capture** — WASAPI is required for per-application audio capture. The VJ server can run on Linux/Docker, but DJs must run on Windows.
- **Java Edition only** — The Fabric mod requires Java Edition 1.21.1+ with Fabric Loader.
- **Low-frequency resolution limited** — 1024-sample FFT at 48kHz cannot accurately detect frequencies below ~43Hz, so sub-bass (20-40Hz) is excluded from the 5-band system.

---

## Acknowledgments

The **Bitmap LED Wall** rendering system was inspired by [TheCymaera's Minecraft Text Display Experiments](https://github.com/TheCymaera/minecraft-text-display-experiments) ([video](https://youtu.be/uZmEYYs0ZKs)). TheCymaera pioneered the technique of using text display entities as individually-addressable pixels — setting `text` to a space character and manipulating the `background` ARGB value to create flat pixel grids, bitmap displays, and interactive paint canvases within Minecraft. MCAV adapted this approach for real-time audio-reactive visualization, adding a frame buffer pipeline, VJ control protocol, transition engine, and effects processing on top of the core pixel-grid concept.

---

## License

MIT — see [LICENSE](LICENSE)
