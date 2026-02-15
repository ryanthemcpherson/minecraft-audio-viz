# Minecraft Commands

All commands use the base `/audioviz` command with aliases `/av` and `/mcav`.

## Command Reference

| Command | Description |
|---------|-------------|
| `/audioviz menu` | Open the main control panel |
| `/audioviz zone create <name>` | Create a new visualization zone |
| `/audioviz zone delete <name>` | Delete a zone |
| `/audioviz zone list` | List all zones |
| `/audioviz zone setsize <name> <x> <y> <z>` | Set zone dimensions |
| `/audioviz zone setrotation <name> <degrees>` | Set zone rotation |
| `/audioviz zone info <name>` | Show zone details |
| `/audioviz pool init <zone> [count] [material]` | Initialize display-entity pool |
| `/audioviz pool cleanup <zone>` | Remove zone entities |
| `/audioviz test <zone> <wave\|pulse\|random>` | Run test animation |
| `/audioviz status` | Show plugin status |
| `/audioviz help` | Show command help |

## Zone Management

Visualization zones define the 3D space where entities are rendered. Zones have a position, dimensions, and rotation.

### Creating a Zone

```
/audioviz zone create myzone
```

Creates a new zone at your current location.

### Configuring Zone Size

```
/audioviz zone setsize myzone 20 15 20
```

Sets the zone to 20x15x20 blocks (width x height x depth).

### Initializing Entity Pools

```
/audioviz pool init myzone 64
/audioviz pool init myzone 128 DIAMOND_BLOCK
```

Creates the display entities within the zone. Entity count affects visualization detail and performance.

## GUI Menu System

Use `/audioviz menu` to open the in-game GUI:

- **Main Menu** -- System status overview
- **DJ Control Panel** -- Effects, presets, zone selection
- **Settings Menu** -- Performance tuning
- **Zone Management** -- Zone editor with size, rotation, and entity pool controls

## Beat Effects

The Minecraft plugin supports these beat-reactive effects:

- **Particle bursts** on beats
- **Screen shake** on bass drops
- **Lightning strikes** on drops
- **Explosion visuals**

Effects can be triggered via the admin panel or WebSocket API using the `trigger_effect` message.

## Bedrock Mode (Geyser)

Bedrock players can't see Display Entities. Use particle-based visualization instead:

1. Switch to **Particles** or **Hybrid** mode in the Admin Panel
2. Available particle types: `DUST`, `FLAME`, `SOUL_FIRE_FLAME`, `END_ROD`, `NOTE`
3. Color modes: frequency-based, rainbow, intensity, fixed color
4. Adjustable density and particle size

## Performance

The plugin uses several optimizations:

- **Batched entity updates** -- single scheduler call per tick
- **Async JSON parsing** -- dedicated thread for message parsing
- **Tick-based message queue** -- all messages processed once per tick
- **View distance culling** -- skip updates for distant entities
- **Entity pool management** -- pre-allocated entities with interpolation
