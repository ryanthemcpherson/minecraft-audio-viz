# AudioViz DJ Client

A lightweight, cross-platform desktop application for DJs to connect to AudioViz VJ servers and stream real-time audio visualizations.

## Features

- **Connect Code Authentication** - Simple code-based connection (no credentials needed)
- **Per-Application Audio Capture** - Select specific apps (Spotify, Chrome, etc.) or system-wide
- **Real-time Frequency Meters** - 5-band visualization (Bass, Low, Mid, High, Air)
- **Connection Status** - Live latency, queue position, and DJ status
- **Cross-Platform** - Windows, macOS, and Linux support (Windows first)

## Requirements

- Windows 10/11, macOS 13+, or Linux
- Rust 1.70+ and Cargo
- Node.js 18+ and npm

## Development Setup

1. **Install Rust dependencies:**
   ```bash
   cd dj_client
   cargo install tauri-cli
   ```

2. **Install Node.js dependencies:**
   ```bash
   npm install
   ```

3. **Run in development mode:**
   ```bash
   npm run tauri dev
   ```

4. **Build for production:**
   ```bash
   npm run tauri build
   ```

5. **Run automated tests:**
   ```bash
   npm test
   ```

## Usage

### For DJs

1. **Get a Connect Code** - Ask the VJ operator for a connect code (e.g., `BEAT-7K3M`)
2. **Enter Server Details** - Input the VJ server hostname and port
3. **Enter the Code** - Type or paste the 8-character connect code
4. **Enter Your Name** - How you want to appear in the DJ queue
5. **Select Audio Source** - Choose your music app or system audio
6. **Connect!** - Click connect and wait to go live

### For VJ Operators

1. Open the Admin Panel
2. In the DJ Queue section, click **"Generate Connect Code"**
3. Share the code with your DJ (valid for 30 minutes)
4. When they connect, use "Go Live" to make them the active DJ

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    DJ Client (Tauri)                │
├─────────────────────────────────────────────────────┤
│  Frontend (React)          │  Rust Backend          │
│  ┌───────────────────┐     │  ┌──────────────────┐  │
│  │ Connect Code      │     │  │ Audio Capture    │  │
│  │ Audio Source      │◄───►│  │ (WASAPI + FFT)   │  │
│  │ Frequency Meters  │     │  ├──────────────────┤  │
│  │ Status Panel      │     │  │ WebSocket Client │  │
│  └───────────────────┘     │  └────────┬─────────┘  │
└────────────────────────────┴───────────┼────────────┘
                                         │
                              WebSocket (port 9000)
                                         │
                                         ▼
                              ┌──────────────────────┐
                              │      VJ Server       │
                              │  (existing Python)   │
                              └──────────────────────┘
```

## Connect Code Format

Codes follow the format `WORD-XXXX`:
- **First 4 chars**: Memorable word (BEAT, BASS, DROP, etc.)
- **Last 4 chars**: Alphanumeric (A-Z, 2-9, no confusables)

Examples: `BEAT-7K3M`, `DROP-XY9Z`, `WAVE-4NPQ`

## Audio Capture

### Windows
- Per-application capture via WASAPI AudioSessionManager
- System-wide loopback as fallback

### macOS (Planned)
- ScreenCaptureKit for per-app audio (macOS 13+)

### Linux (Planned)
- PulseAudio sink input capture

## Protocol

Messages use JSON over WebSocket:

```json
// Connect with code
{"type": "code_auth", "code": "BEAT-7K3M", "dj_name": "DJ Spark"}

// Audio frame (60fps)
{"type": "dj_audio_frame", "seq": 123, "bands": [0.8, 0.6, 0.4, 0.3, 0.2], "peak": 0.8, "beat": true, "bpm": 128}
```

## Distribution

Built binaries are available in the `target/release` folder:
- Windows: `dj-client.exe` (~3MB) or `.msi` installer
- macOS: `AudioViz DJ.app` or `.dmg`
- Linux: AppImage or `.deb`

## License

MIT
