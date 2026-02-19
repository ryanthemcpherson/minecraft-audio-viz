# Minecraft Plugin Deployment

The MCAV Minecraft plugin renders audio-reactive visualizations using Display Entities on a Paper server.

## Requirements

- **Paper/Spigot 1.21.1+** (required for Display Entities)
- **Java 21**
- **Maven** (for building)

## Building

```bash
cd minecraft_plugin
mvn package
```

The built JAR will be at `target/audioviz-plugin-1.0.0-SNAPSHOT.jar`.

## Installation

1. Copy the JAR to your server's `plugins/` folder
2. Restart the server
3. Configure the VJ server to connect:

```bash
audioviz-vj --minecraft-host your-mc-server
```

## Configuration

The plugin configuration is at `plugins/AudioViz/config.yml`.

### WebSocket

The plugin runs a WebSocket server on port **8765** that receives visualization data from the VJ server.

## Zone Setup

After installing the plugin, set up a visualization zone in-game:

```
/audioviz zone create main
/audioviz zone setsize main 20 15 20
/audioviz pool init main 64
```

This creates a 20x15x20 block zone with 64 display entities.

## Performance Optimizations

The plugin implements several optimizations for smooth real-time rendering:

### Batch Entity Updates

All entity position/scale/rotation changes are batched into a single scheduler call per server tick, rather than updating entities individually.

### Async JSON Parsing

WebSocket message parsing runs on a dedicated thread to avoid blocking the main server thread.

### Tick-Based Message Queue

Messages are queued and processed once per tick. The queue holds up to 1000 messages; oldest messages are dropped on overflow to prevent memory exhaustion.

### Entity Pool Management

Display entities are pre-allocated in pools and reused. The pool manager handles:

- Entity creation and lifecycle
- Smooth interpolation between positions
- View distance culling
- Material and scale management

## Beat Effects

The plugin supports beat-reactive effects:

| Effect | Description |
|--------|-------------|
| Particle bursts | Spawn particles on beat detection |
| Screen shake | Camera shake on bass drops |
| Lightning strikes | Lightning effect on drops |
| Explosion visuals | Explosion particles |

## Bedrock Support (Geyser)

Bedrock Edition players connected via Geyser cannot see Display Entities. The plugin provides particle-based visualization as an alternative:

1. Switch to **Particles** or **Hybrid** mode in the Admin Panel
2. Configure particle type, color mode, density, and size
3. Available particle types: `DUST`, `FLAME`, `SOUL_FIRE_FLAME`, `END_ROD`, `NOTE`

## Permissions

| Permission | Description |
|------------|-------------|
| `audioviz.admin` | Full access to all commands |
| `audioviz.menu` | Access to GUI menus |
| `audioviz.zone` | Zone management commands |
