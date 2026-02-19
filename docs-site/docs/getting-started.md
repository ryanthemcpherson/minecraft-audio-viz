# Getting Started

## Try the Demo (Zero Install)

**Requirements:** Docker only

Experience MCAV in your browser with simulated audio:

```bash
git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz
docker compose -f docker-compose.demo.yml up
```

Then open:

- **http://localhost:8080** -- Admin Panel
- **http://localhost:8081** -- 3D Preview

See the [Docker Demo](deployment/docker-demo.md) page for full details.

## Download DJ Client (Recommended)

The DJ Client is a cross-platform desktop app (Windows/macOS/Linux) for capturing and streaming audio:

1. Download the installer for your platform from [GitHub Releases](https://github.com/ryanthemcpherson/minecraft-audio-viz/releases)
2. Install and launch the DJ Client
3. Select your audio source (Spotify, Chrome, system audio, etc.)
4. Connect to a VJ server or run in standalone mode with browser preview

## VJ Server Setup

**Requirements:** Python 3.11+

```bash
# Clone and install
git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz
pip install -e vj_server/

# Start VJ server (multi-DJ mode)
audioviz-vj

# Open in browser
#   3D Preview:   http://localhost:8080
#   Admin Panel:  http://localhost:8081
```

## Minecraft Plugin Setup (Optional)

To render visualizations in Minecraft, build the plugin and install it on a Paper server:

```bash
cd minecraft_plugin && mvn package
# Copy target/audioviz-plugin-*.jar to your server's plugins/ folder
# Configure VJ server: audioviz-vj --minecraft-host your-mc-server
```

!!! note "Requirements"
    - Paper/Spigot 1.21.1+ (Display Entities)
    - Java 21

## Multi-DJ Mode (Live Events)

### 1. Start the VJ Server (central control)

```bash
audioviz-vj
```

Defaults:

- DJ connection port: `ws://localhost:9000`
- Browser preview: `http://localhost:8080`
- Admin panel: `http://localhost:8081`

### 2. DJs connect using the DJ Client

1. Download and install the [DJ Client](https://github.com/ryanthemcpherson/minecraft-audio-viz/releases)
2. Launch the DJ Client
3. Enter VJ server connection details (host, port, DJ name, connect code)
4. Select audio source and start streaming

### 3. DJ Authentication (recommended)

The VJ server supports connect-code authentication for multi-DJ sessions. Connect codes use the format `WORD-XXXX` (e.g., `BEAT-7K3M`) and expire after 30 minutes.

For production, the VJ server enforces authentication by default. Use `--no-auth` for development only.

## What's Next?

- [Create a custom pattern](guides/pattern-development.md)
- [Understand the audio pipeline](guides/audio-processing.md)
- [Learn the WebSocket protocol](reference/websocket-protocol.md)
- [Deploy with Docker](deployment/docker-demo.md)
