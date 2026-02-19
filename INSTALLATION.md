# Installation Guide

This guide walks you through setting up the Minecraft Audio Visualizer from scratch.

## Prerequisites

- **Windows 10/11** (required for WASAPI audio capture)
- **Python 3.10+** (3.11 recommended)
- **Java 21** (for Minecraft plugin)
- **Maven** (for building the plugin)
- **Minecraft Server** running Paper/Spigot 1.21.1+

## Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/minecraft-audio-viz.git
cd minecraft-audio-viz
```

### 2. Install Python Dependencies

We recommend using [UV](https://docs.astral.sh/uv/) for fast dependency management:

```bash
# Install UV (if not already installed)
pip install uv

# Install project in editable mode
uv pip install -e .

# Or with all optional features
uv pip install -e ".[full]"
```

Alternatively, using pip:

```bash
pip install -e .
```

### 3. Build the Minecraft Plugin

```bash
cd minecraft_plugin
mvn clean package
```

The compiled JAR will be at `target/audioviz-plugin-1.0.0-SNAPSHOT.jar`.

### 4. Install the Plugin

Copy the JAR to your Minecraft server's `plugins/` folder:

```bash
cp target/audioviz-plugin-1.0.0-SNAPSHOT.jar /path/to/minecraft/plugins/
```

Restart your Minecraft server.

### 5. Create a Visualization Zone

In-game, run:
```
/audioviz zone create main
```

Then select two corners of your visualization area:
1. Left-click the first corner
2. Right-click the second corner

### 6. Start the Audio Processor

```bash
# Capture Spotify audio and send to localhost
audioviz --app spotify

# Or with browser preview
audioviz --app spotify --preview
```

## Detailed Setup

### Python Audio Processor

#### Required Dependencies
- `numpy` - Numerical processing
- `scipy` - Signal processing
- `websockets` - WebSocket communication
- `pycaw` - Windows audio capture (WASAPI)
- `comtypes` - COM interface for Windows

#### Optional Dependencies
- `pyaudiowpatch` - True WASAPI loopback capture (recommended)
- `sounddevice` - Alternative audio capture
- `pyfftw` - High-performance FFT
- `spectrograms` - Rust-based FFT backend
- `aubio` - Advanced beat detection

#### Verify Audio Capture

```bash
# List available audio applications
audioviz --list-apps

# List audio devices
audioviz --list-devices

# Test capture without Minecraft
audioviz --test
```

### Minecraft Plugin Configuration

After first run, edit `plugins/AudioViz/config.yml`:

```yaml
websocket:
  port: 8765

defaults:
  entity_count: 16
  block_type: SEA_LANTERN
  base_scale: 0.5
  max_scale: 1.0
```

### Admin Panel

The admin panel provides DJ-style controls:

1. Start the audio processor with HTTP server:
   ```bash
   audioviz --app spotify --preview --http-port 8080
   ```

2. Open in browser: `http://localhost:8080/admin/`

### Browser 3D Preview

1. Start with preview enabled:
   ```bash
   audioviz --preview
   ```

2. Open: `http://localhost:8080/`

## Troubleshooting

### No Audio Detected

1. Check that the target application is playing audio
2. Verify Windows audio settings (right-click speaker > Sound settings)
3. Try `audioviz --list-apps` to see active audio sessions

### Plugin Not Working

1. Check server console for errors
2. Verify Java 21: `java -version`
3. Ensure Paper/Spigot 1.21.1+
4. Check `plugins/AudioViz/config.yml` for WebSocket port conflicts

### Connection Issues

1. Check firewall allows port 8765 (and 8766 for preview)
2. Verify Minecraft server is running
3. Try `--host localhost` if on same machine

### Display Entities Not Visible

1. Ensure you're using Java Edition (not Bedrock)
2. Enable "Bedrock Mode" in admin panel for Geyser users
3. Check render distance settings

## Performance Tuning

### Low Latency Mode

For minimal audio-visual delay (~20ms):

```bash
audioviz --low-latency --app spotify
```

Note: This trades bass frequency resolution for speed.

### Tick-Aligned Mode

For smooth integration with Minecraft's 20 TPS:

```bash
audioviz --tick-aligned --app spotify
```

### High Entity Counts

For complex visualizations with many entities:

1. Use batch updates (enabled by default)
2. Consider using particles for Bedrock players
3. Reduce update frequency if TPS drops

## Multi-DJ Setup (VJ Mode)

For events with multiple DJs:

### VJ Server

```bash
audioviz-vj --port 9000 --minecraft-host mc.server.com
```

### Remote DJ

```bash
audioviz --app spotify --dj-relay --vj-server ws://vj.server.com:9000
```

## Updating

```bash
git pull
uv pip install -e .
cd minecraft_plugin && mvn clean package
```

Replace the plugin JAR and restart the Minecraft server.
