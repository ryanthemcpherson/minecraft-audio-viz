# Docker Demo

Try MCAV in your browser with zero installation using Docker Compose.

## Quick Start

```bash
git clone https://github.com/ryanthemcpherson/minecraft-audio-viz.git
cd minecraft-audio-viz
docker compose -f docker-compose.demo.yml up
```

Then open in your browser:

- **http://localhost:8080** -- Admin Panel (DJ controls)
- **http://localhost:8081** -- 3D Preview (Three.js visualization)

The demo automatically:

1. Starts the VJ server with authentication disabled
2. Launches an audio simulator sending 128 BPM beats
3. Serves the web interfaces

## What You'll See

- **3D Preview** -- Real-time visualization of simulated audio with 5 frequency bands
- **Admin Panel** -- Full DJ controls including pattern selection, entity count, and audio meters
- **Live Beat Detection** -- Visual beat indicators synced to 128 BPM
- **Pattern Engine** -- Switch between 28+ Lua-based visualization patterns

## Architecture

```text
Audio Simulator (128 BPM)
    | WebSocket (port 9000)
    v
VJ Server (Pattern Engine)
    | WebSocket (port 8766)
    v
Browser Preview + Admin Panel
```

## Simulated Audio

The audio simulator generates realistic audio frames with:

- **5 Frequency Bands** -- Bass, Low-mid, Mid, High-mid, High
- **Beat Detection** -- 4/4 time signature with kick pattern
- **BPM Tracking** -- Configurable tempo (default 128 BPM)
- **Tempo Confidence** -- Simulated BPM detector confidence
- **Beat Phase** -- Current position in the beat cycle

## Customization

### Change BPM

```yaml
environment:
  - BPM=140
```

### Change Intensity

```yaml
environment:
  - INTENSITY=0.9   # 0.0-1.0
  - FPS=21.5
```

### Change Entity Count

Edit the VJ server command in `docker-compose.demo.yml`:

```yaml
command: >
  --entities 64
```

### Change Starting Pattern

```yaml
command: >
  --pattern aurora
```

Available patterns include: `spectrum`, `bars`, `circle`, `wave`, `columns`, `aurora`, `blackhole`, `galaxy`, and 20+ more.

## Stopping the Demo

```bash
# Stop all containers
docker compose -f docker-compose.demo.yml down

# Stop and remove volumes
docker compose -f docker-compose.demo.yml down -v
```

## Troubleshooting

### Port Already in Use

If ports 8080 or 8081 are taken, edit `docker-compose.demo.yml`:

```yaml
ports:
  - "9080:8080"  # Change 9080 to any free port
```

### No Visualization Showing

1. Check browser console for WebSocket errors
2. Verify VJ server is healthy: `docker compose -f docker-compose.demo.yml ps`
3. Check audio simulator logs: `docker logs mcav-audio-simulator`

### WebSocket Connection Failed

The preview/admin frontends connect to `ws://localhost:8766`. If running on a remote host, access via the host's IP address instead of localhost.

## Next Steps

- **Add Minecraft** -- Follow the [Minecraft Plugin](minecraft-plugin.md) guide
- **Run a Real DJ Client** -- Download the DJ Client from [GitHub Releases](https://github.com/ryanthemcpherson/minecraft-audio-viz/releases)
- **Explore Patterns** -- Browse patterns or [create your own](../guides/pattern-development.md)

!!! note
    Audio capture requires Windows and cannot run in Docker. DJs run locally and connect to the containerized VJ server.
