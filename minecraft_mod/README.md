# AudioViz Fabric Mod

Server-side Fabric mod for real-time audio visualization with two rendering backends:

- **Map Renderer** — Bitmap patterns rendered to map items on invisible item frames. One `MapUpdateS2CPacket` with dirty-rect partial updates replaces thousands of per-entity metadata packets.
- **Virtual Entity Renderer** — 3D Lua patterns using Polymer virtual Display Entities (zero server-side entity overhead) wrapped in bundle delimiter packets for atomic frame updates.

## Requirements

- Minecraft 1.21.11+
- Fabric Loader 0.16.0+
- Fabric API
- Java 21+

## Required Server Configuration

### server.properties

```properties
# REQUIRED: Disable packet compression.
# Entity metadata packets are 35-50 bytes each — compression overhead
# exceeds bandwidth savings at this size. Map packets are ~1-4KB and
# compress poorly (random pixel data). CPU savings are significant.
network-compression-threshold=-1
```

### Recommended JVM Flags

```bash
java -Xms4G -Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
     -XX:MaxGCPauseMillis=20 -XX:G1NewSizePercent=30 \
     -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M \
     -jar fabric-server.jar nogui
```

## Build

```bash
cd minecraft_mod
./gradlew build
```

Output JAR: `build/libs/audioviz-mod-1.0.0.jar`

## Commands

All commands require operator level 2.

| Command | Description |
|-|-|
| `/audioviz zone create <name>` | Create zone at your position |
| `/audioviz zone delete <name>` | Delete a zone |
| `/audioviz zone list` | List all zones |
| `/audioviz zone info <name>` | Show zone details |
| `/audioviz zone setsize <name> <x> <y> <z>` | Set zone dimensions |
| `/audioviz status` | Show system status |

## Configuration

Config file: `config/audioviz/audioviz.json` (auto-generated on first run)

```json
{
  "websocketPort": 8765,
  "djPort": 9000,
  "maxEntitiesPerZone": 1000,
  "maxPixelsPerZone": 500,
  "bitmapPixelsPerBlock": 4,
  "defaultRendererBackend": "MAP",
  "useMapRenderer": true,
  "useBundlePackets": true,
  "mapUpdateIntervalTicks": 1
}
```

## Performance vs Paper Plugin

For a 64×36 bitmap display (2,304 pixels) with 10 players at 20 TPS:

| Metric | Paper Plugin | Fabric Mod |
|-|-|-|
| Entities in world | 2,304 TextDisplay | 1 item frame |
| Packets/tick (full frame) | 23,040 | 10 |
| Bytes/tick (full frame) | ~1.15 MB | ~4 KB |
| Server entity overhead | Full collision + tick + chunk tracking | Zero |
| Visual tearing | Yes | No (bundle packets) |
| Color depth | Full ARGB | 248 map colors (3D patterns: full ARGB) |
