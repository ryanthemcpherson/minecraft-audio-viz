# MCAV Full-Stack Demo

Try MCAV (Minecraft Audio Visualizer) in your browser with zero installation using Docker Compose.

## Quick Start

```bash
# From the project root
docker compose -f docker-compose.demo.yml up
```

Then open in your browser:
- **http://localhost:8080** - Admin Panel (DJ controls)
- **http://localhost:8081** - 3D Preview (Three.js visualization)

The demo will automatically:
1. Start the VJ server with authentication disabled
2. Launch an audio simulator sending 128 BPM beats
3. Serve the web interfaces

## What You'll See

- **3D Preview**: Real-time visualization of simulated audio with 5 frequency bands
- **Admin Panel**: Full DJ controls including pattern selection, entity count, and audio meters
- **Live Beat Detection**: Visual beat indicators synced to 128 BPM
- **Pattern Engine**: Switch between 28+ Lua-based visualization patterns

## Architecture

```
Audio Simulator (128 BPM)
    ↓ WebSocket (port 9000)
VJ Server (Pattern Engine)
    ↓ WebSocket (port 8766)
Browser Preview + Admin Panel
```

## Simulated Audio

The audio simulator (`audio_simulator.py`) generates realistic audio frames with:
- **5 Frequency Bands**: Bass, Low-mid, Mid, High-mid, High
- **Beat Detection**: 4/4 time signature with kick pattern
- **BPM Tracking**: Configurable tempo (default 128 BPM)
- **Tempo Confidence**: Simulated BPM detector confidence
- **Beat Phase**: Current position in the beat cycle

You can customize the simulator by editing the environment variables in `docker-compose.demo.yml`:
```yaml
environment:
  - BPM=140          # Beats per minute
  - INTENSITY=0.9    # Audio intensity (0.0-1.0)
  - FPS=21.5         # Frames per second
```

## Pattern Selection

The demo starts with the `spectrum` pattern. You can change patterns via:
1. **Admin Panel**: Use the pattern dropdown
2. **Docker Compose**: Edit the `--pattern` flag in the VJ server command
3. **Runtime**: Send WebSocket messages to port 8766

Available patterns include: `spectrum`, `bars`, `circle`, `wave`, `columns`, `aurora`, `blackhole`, and 20+ more.

## Customization

### Change Entity Count
Edit the VJ server command in `docker-compose.demo.yml`:
```yaml
command: >
  --entities 64  # More blocks = denser visualization
```

### Change BPM
Edit the audio simulator environment:
```yaml
environment:
  - BPM=140  # Faster tempo
```

### Different Pattern
```yaml
command: >
  --pattern aurora  # Start with aurora pattern
```

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
The preview/admin frontends connect to `ws://localhost:8766`. If running on a remote host, you'll need to access via the host's IP address instead of localhost.

## Next Steps

- **Add Minecraft**: Follow the main [README.md](../README.md) to connect a Minecraft server
- **Run a Real DJ Client**: Install the Rust DJ client from `dj_client/` to capture real audio
- **Explore Patterns**: Browse the 28 patterns in `patterns/` or create your own Lua pattern
- **Deploy to Production**: See `coordinator/` for multi-tenant setup with authentication

## Development

To modify the demo setup:

1. **Audio Simulator**: Edit `demo/audio_simulator.py` and rebuild:
   ```bash
   docker compose -f docker-compose.demo.yml up --build audio_simulator
   ```

2. **VJ Server**: The VJ server uses the main `Dockerfile` - changes to `vj_server/` require rebuilding:
   ```bash
   docker compose -f docker-compose.demo.yml up --build vj_server
   ```

3. **Frontend**: Preview tool changes are live (nginx serves from `preview_tool/frontend/`):
   ```bash
   # Just refresh your browser
   ```

## Learn More

- [Project Documentation](../README.md)
- [Pattern Development](../patterns/README.md)
- [VJ Server API](../vj_server/README.md)
- [WebSocket Protocol](../protocol/README.md)
