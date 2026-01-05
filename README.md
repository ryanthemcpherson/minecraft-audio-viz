# Minecraft Audio Visualizer

A real-time audio visualization system that captures system audio and displays reactive visualizations in Minecraft, browser previews, and through a professional admin control panel.

## Features

- **Real-time Audio Capture** - Captures audio from any Windows application (Spotify, YouTube, etc.) using WASAPI
- **FFT Analysis** - Real frequency band analysis with low-latency mode
- **Multiple Visualization Patterns** - Spectrum bars, wave rings, pulse grids, helix, and more
- **Browser Preview** - 3D WebGL preview of visualizations
- **Admin Control Panel** - Professional DJ/VJ-style control interface with:
  - Live mixer with band faders
  - Pattern switching
  - Audio presets (EDM, Chill, Rock, Auto)
  - Timeline editor for pre-programmed shows
  - Effect triggers (strobe, flash, bass drop)
- **Minecraft Integration** - Sends visualization data to Minecraft via WebSocket
- **Multi-DJ Support** - Multiple remote DJs can perform with a central VJ controlling visuals

## Architecture

### Single DJ Mode (Default)


```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Audio Source   │────▶│  Audio Processor │────▶│   Minecraft     │
│  (Spotify etc)  │     │  (Python/FFT)    │     │   Plugin        │
└─────────────────┘     └────────┬─────────┘     └─────────────────┘
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
           ┌───────────────┐         ┌───────────────┐
           │ Browser 3D    │         │ Admin Panel   │
           │ Preview       │         │ Control UI    │
           └───────────────┘         └───────────────┘
```

### Multi-DJ Mode

For live events with multiple DJs performing remotely:

```
┌──────────────┐
│ DJ 1 (Remote)│───┐
│ --dj-relay   │   │
└──────────────┘   │    ┌────────────────┐     ┌─────────────────┐
                   ├───▶│   VJ Server    │────▶│   Minecraft     │
┌──────────────┐   │    │  (Central)     │     │   (Shared)      │
│ DJ 2 (Remote)│───┤    └───────┬────────┘     └─────────────────┘
│ --dj-relay   │   │            │
└──────────────┘   │    ┌───────┴────────┐
                   │    ▼                ▼
┌──────────────┐   │ ┌───────────┐ ┌───────────┐
│ DJ 3 (Remote)│───┘ │  Viewers  │ │ VJ Admin  │
│ --dj-relay   │     │ (Browser) │ │  Panel    │
└──────────────┘     └───────────┘ └───────────┘
```

## Quick Start

### Requirements

- Python 3.8+
- Windows (for WASAPI audio capture)

### Installation

```bash
pip install pycaw comtypes websockets pyaudiowpatch numpy scipy
```

### Running

1. Start audio capture (with Spotify playing):
```bash
python -m audio_processor.app_capture --app spotify --no-minecraft
```

2. Open in browser:
   - **3D Preview**: http://localhost:8080
   - **Admin Panel**: http://localhost:8080/admin/

### Command Line Options

```
--app NAME       Application to capture audio from (default: spotify)
--no-minecraft   Run without Minecraft connection
--no-fft         Disable FFT analysis (use synthetic bands)
--compact        Use compact single-line spectrograph
--broadcast-port WebSocket port for browser (default: 8766)
--http-port      HTTP port for web interface (default: 8080)
```

## Project Structure

```
minecraft-audio-viz/
├── audio_processor/       # Python audio processing
│   ├── app_capture.py     # Main capture agent (single DJ or relay)
│   ├── vj_server.py       # Multi-DJ VJ server
│   ├── dj_relay.py        # DJ relay client
│   ├── fft_analyzer.py    # FFT frequency analysis
│   ├── patterns.py        # Visualization patterns
│   ├── spectrograph.py    # Terminal display
│   └── timeline/          # Timeline/cue system
├── configs/               # Configuration files
│   └── dj_auth.json       # DJ authentication credentials
├── admin_panel/           # Web control panel
│   ├── index.html
│   ├── css/admin.css
│   └── js/
├── preview_tool/          # 3D browser preview
│   ├── frontend/
│   └── backend/
├── minecraft_plugin/      # Spigot/Paper plugin (Java)
├── python_client/         # Minecraft WebSocket client
└── shows/                 # Saved show files (JSON)
```

## Multi-DJ Mode

For live events with multiple remote DJs:

### 1. Start the VJ Server (Central Control)

```bash
python -m audio_processor.vj_server --no-minecraft
```

This starts:
- DJ connection port: `ws://localhost:9000`
- Browser preview: `http://localhost:8080`
- Admin panel: `http://localhost:8080/admin/`

### 2. DJs Connect in Relay Mode

Each DJ runs on their own machine:

```bash
python -m audio_processor.app_capture --dj-relay \
    --vj-server ws://VJ_SERVER_IP:9000 \
    --dj-name "DJ Alice" \
    --dj-id "dj_alice" \
    --dj-key "alice123"
```

### 3. VJ Controls via Admin Panel

The VJ operator can:
- See all connected DJs with status
- Switch the active DJ
- Kick DJs if needed
- Change visualization patterns

### DJ Auth Config

Edit `configs/dj_auth.json` to set up DJ credentials:

```json
{
  "djs": {
    "dj_alice": {"name": "DJ Alice", "key_hash": "alice123", "priority": 1},
    "dj_bob": {"name": "DJ Bob", "key_hash": "bob456", "priority": 2}
  }
}
```

For production, use `--require-auth` on the VJ server.

## Timeline System

The timeline editor allows pre-programming visualization shows:

- **Tracks**: Patterns, Presets, Effects, Parameters
- **Cues**: Timed events that trigger actions
- **Triggers**: Time-based, beat-synced, or manual
- **Transport**: Play, pause, stop, seek with playhead

## License

MIT
