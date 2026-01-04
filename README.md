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

## Architecture

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
│   ├── app_capture.py     # Main capture agent
│   ├── fft_analyzer.py    # FFT frequency analysis
│   ├── patterns.py        # Visualization patterns
│   ├── spectrograph.py    # Terminal display
│   └── timeline/          # Timeline/cue system
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

## Timeline System

The timeline editor allows pre-programming visualization shows:

- **Tracks**: Patterns, Presets, Effects, Parameters
- **Cues**: Timed events that trigger actions
- **Triggers**: Time-based, beat-synced, or manual
- **Transport**: Play, pause, stop, seek with playhead

## License

MIT
