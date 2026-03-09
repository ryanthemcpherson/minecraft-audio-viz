# Fabric Mod Rewrite — Map Renderer + Polymer Virtual Entities

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rewrite the Minecraft Paper plugin as a server-side Fabric mod with two new rendering backends: map-based pixel grids (for bitmap/video) and Polymer virtual Display Entities (for 3D patterns), with bundle delimiter packet wrapping and compression disabled.

**Architecture:** The mod replaces the Paper plugin entirely. Bitmap patterns render to map items on invisible item frames — one `MapUpdateS2CPacket` with dirty-rect partial updates replaces thousands of per-entity metadata packets. 3D Lua patterns use Polymer's packet-only virtual Display Entities (no server-side entity overhead) wrapped in bundle delimiter packets for atomic frame updates. Both backends share the existing WebSocket protocol, zone system, and pattern engine.

**Tech Stack:** Fabric 1.21.x, Polymer (`polymer-virtual-entity`), Fabric API (lifecycle events, networking), Java-WebSocket, Gson, JUnit 5

**Server config prerequisite:** Set `network-compression-threshold=-1` in `server.properties` to disable per-packet zlib compression. At the packet sizes we send (35-50 bytes per entity update, ~1-4KB per map update), compression CPU cost exceeds bandwidth savings.

---

## Phase 0: Project Scaffold

### Task 0.1: Gradle Project Setup

**Files:**
- Create: `minecraft_mod/build.gradle`
- Create: `minecraft_mod/settings.gradle`
- Create: `minecraft_mod/gradle.properties`
- Create: `minecraft_mod/src/main/resources/fabric.mod.json`

**Step 1: Create Gradle build file**

Use the Fabric template generator or write manually. Key dependencies:

```gradle
plugins {
    id 'fabric-loom' version '1.9-SNAPSHOT'
    id 'java'
}

version = '1.0.0'
group = 'com.audioviz'

repositories {
    maven { url = 'https://maven.nucleoid.xyz' }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    // Polymer for virtual entities
    modImplementation include("eu.pb4:polymer-virtual-entity:${project.polymer_version}")

    // WebSocket (same library as Paper plugin)
    implementation include("org.java-websocket:Java-WebSocket:1.5.7")

    // Gson (bundled with Minecraft, but declare for clarity)
    compileOnly "com.google.code.gson:gson:2.11.0"

    // Testing
    testImplementation "org.junit.jupiter:junit-jupiter:5.11.0"
    testImplementation "org.mockito:mockito-core:5.14.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
```

```properties
# gradle.properties
minecraft_version=1.21.11
yarn_mappings=1.21.11+build.3
loader_version=0.16.9
fabric_version=0.107.0+1.21.11
polymer_version=0.9.17+1.21.11
```

**Step 2: Create fabric.mod.json**

```json
{
  "schemaVersion": 1,
  "id": "audioviz",
  "version": "1.0.0",
  "name": "AudioViz",
  "description": "Real-time audio visualization with Display Entities and Map Displays",
  "authors": ["MCAV"],
  "license": "MIT",
  "environment": "server",
  "entrypoints": {
    "server": ["com.audioviz.AudioVizMod"]
  },
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": ">=1.21.11",
    "java": ">=21",
    "fabric-api": "*"
  }
}
```

**Step 3: Verify the project compiles**

Run: `cd minecraft_mod && ./gradlew build`
Expected: BUILD SUCCESSFUL (empty mod, no source yet)

**Step 4: Commit**

```bash
git add minecraft_mod/
git commit -m "chore: scaffold Fabric mod project with Polymer + WebSocket deps"
```

---

### Task 0.2: Mod Entry Point + Tick Scheduler

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/AudioVizMod.java`

**Step 1: Write the mod initializer**

Fabric has no `BukkitScheduler`. Use `ServerTickEvents.END_SERVER_TICK` for the main loop. This replaces every `Bukkit.getScheduler().runTaskTimer()` call from the Paper plugin.

```java
package com.audioviz;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudioVizMod implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("audioviz");
    private static MinecraftServer server;

    @Override
    public void onInitializeServer() {
        LOGGER.info("AudioViz Fabric mod initializing...");

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            startup();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> shutdown());

        ServerTickEvents.END_SERVER_TICK.register(s -> tick());
    }

    private void startup() {
        // Will initialize: config, zones, WebSocket, renderers, patterns
        LOGGER.info("AudioViz started");
    }

    private void tick() {
        // Will call: messageQueue.processTick(), patternManager.tick(), etc.
    }

    private void shutdown() {
        // Will cleanup: WebSocket server, entity pools, etc.
        LOGGER.info("AudioViz stopped");
    }

    public static MinecraftServer getServer() { return server; }
}
```

**Step 2: Update fabric.mod.json entrypoint to use `server` key** (already done above)

**Step 3: Verify it loads**

Run: `./gradlew runServer`
Expected: Console shows "AudioViz Fabric mod initializing..." and "AudioViz started"

**Step 4: Commit**

```bash
git add minecraft_mod/src/
git commit -m "feat: add Fabric mod entrypoint with tick scheduler"
```

---

### Task 0.3: Config System

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/ModConfig.java`

**Context:** The Paper plugin uses Bukkit's `getConfig()` (YAML). Fabric mods typically use JSON or TOML. Use Gson (already bundled with Minecraft) to read/write a JSON config file.

**Step 1: Write config class**

```java
package com.audioviz;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;

public class ModConfig {
    // WebSocket
    public int websocketPort = 8765;
    public int djPort = 9000;

    // Rendering
    public int maxEntitiesPerZone = 1000;
    public int maxPixelsPerZone = 500;
    public int bitmapPixelsPerBlock = 4;
    public String defaultRendererBackend = "MAP"; // MAP, VIRTUAL_ENTITY, PARTICLE

    // Performance
    public boolean useMapRenderer = true;
    public boolean useBundlePackets = true;
    public int mapUpdateIntervalTicks = 1; // every tick = 20fps

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ModConfig load(Path configDir) throws IOException {
        Path file = configDir.resolve("audioviz.json");
        if (Files.exists(file)) {
            return GSON.fromJson(Files.readString(file), ModConfig.class);
        }
        ModConfig config = new ModConfig();
        config.save(configDir);
        return config;
    }

    public void save(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("audioviz.json"), GSON.toJson(this));
    }
}
```

**Step 2: Load config in `AudioVizMod.startup()`**

```java
private ModConfig config;

private void startup() {
    Path configDir = FabricLoader.getInstance().getConfigDir().resolve("audioviz");
    config = ModConfig.load(configDir);
}
```

**Step 3: Commit**

```bash
git commit -m "feat: add JSON config system replacing Bukkit YAML config"
```

---

## Phase 1: WebSocket + Protocol Layer (Port)

### Task 1.1: Port WebSocket Server

**Files:**
- Copy + adapt: `minecraft_plugin/.../websocket/VizWebSocketServer.java`
- Copy + adapt: `minecraft_plugin/.../protocol/MessageQueue.java`
- Copy + adapt: `minecraft_plugin/.../protocol/MessageHandler.java`

**Context:** The WebSocket server (`VizWebSocketServer`) uses `org.java_websocket.server.WebSocketServer` which is platform-agnostic. The only Bukkit dependency is `Bukkit.getScheduler()` for the heartbeat timer and message processing. Replace with direct calls from `AudioVizMod.tick()`.

**Step 1: Copy VizWebSocketServer.java**

Copy to `minecraft_mod/src/main/java/com/audioviz/websocket/VizWebSocketServer.java`. Remove:
- All `import org.bukkit.*` lines
- Heartbeat scheduler → move to `AudioVizMod.tick()` (count ticks, send heartbeat every 200 ticks = 10s)
- `Bukkit.getScheduler().runTask()` wrappers → direct method calls (we're already on the server thread in tick())

**Step 2: Copy MessageQueue.java**

Copy to `minecraft_mod/src/main/java/com/audioviz/protocol/MessageQueue.java`. Remove:
- `BukkitScheduler` references
- `processTick()` is already called from our tick event, no Bukkit task needed

**Step 3: Stub MessageHandler.java**

Copy the message routing structure but stub out the handler methods that touch Paper API (entity updates, zone lookups). These get filled in as we build each subsystem. Keep:
- JSON parsing logic
- Message type routing (`switch` on message type)
- `dj_audio_frame` handling (updates `latestAudioState` — no Paper API needed)
- `bitmap_frame` routing (will call map renderer later)
- `batch_update` routing (will call virtual entity renderer later)
- `set_pattern` routing (will call pattern manager later)

**Step 4: Wire up in AudioVizMod**

```java
private VizWebSocketServer wsServer;
private MessageQueue messageQueue;

private void startup() {
    // ... config ...
    messageQueue = new MessageQueue(/* handler */);
    wsServer = new VizWebSocketServer(config.websocketPort, messageQueue);
    wsServer.start();
}

private void tick() {
    messageQueue.processTick();
}

private void shutdown() {
    wsServer.stop();
}
```

**Step 5: Test WebSocket connects**

Start the mod, connect with `wscat -c ws://localhost:8765`, send `{"type":"ping"}`.
Expected: Connection established, heartbeat responses.

**Step 6: Commit**

```bash
git commit -m "feat: port WebSocket server and message queue to Fabric"
```

---

### Task 1.2: Port Zone System

**Files:**
- Copy + adapt: `minecraft_plugin/.../zones/ZoneManager.java`
- Copy + adapt: `minecraft_plugin/.../zones/VisualizationZone.java`

**Context:** Zones use Bukkit `Location`, `World`, and `Vector`. Replace with Minecraft's `BlockPos`, `Vec3d`, and `ServerWorld`. The coordinate math (localToWorld, worldToLocal) is pure arithmetic — only the container types change.

**Step 1: Adapt VisualizationZone**

Replace:
- `org.bukkit.Location` → `net.minecraft.util.math.BlockPos` (origin)
- `org.bukkit.util.Vector` → `org.joml.Vector3f` (size) — JOML is already in Minecraft's runtime
- `org.bukkit.World` → `net.minecraft.server.world.ServerWorld`
- `world.getName()` → `world.getRegistryKey().getValue().toString()`

The coordinate transform methods (`localToWorld`, `worldToLocal`) are pure math on doubles — no API changes needed.

**Step 2: Adapt ZoneManager**

Replace:
- YAML persistence: keep same format, use Gson instead of Bukkit's `YamlConfiguration`
- `Bukkit.getWorld(name)` → `server.getWorld(RegistryKey<World>)` or iterate `server.getWorlds()`
- Event listeners (`WorldUnloadEvent`) → `ServerWorldEvents.UNLOAD` from Fabric API

**Step 3: Commit**

```bash
git commit -m "feat: port zone system to Fabric (BlockPos/Vec3d)"
```

---

## Phase 2: Map-Based Bitmap Renderer (NEW)

This is the core performance innovation. Instead of 1 TextDisplay entity per pixel, we render to map items on invisible item frames. One `MapUpdateS2CPacket` replaces hundreds of entity metadata packets.

### Task 2.1: Map Color Palette + RGB Conversion

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/map/MapPalette.java`
- Create: `minecraft_mod/src/test/java/com/audioviz/map/MapPaletteTest.java`

**Context:** Minecraft maps use a 248-color palette (62 base colors × 4 shades). Each pixel is a single byte (0-247, where 0-3 = transparent). We need to convert ARGB pixels from `BitmapFrameBuffer` to the nearest map color. This MUST be fast — precompute a 16MB lookup table (256×256×256 → byte) at startup.

**Step 1: Write the failing test**

```java
@Test
void testRedMapsToFireColor() {
    // Pure red (255,0,0) should map to FIRE base color (ID 4), shade 2 (brightest)
    // FIRE base = (255, 0, 0), shade 2 multiplier = 255/255 = 1.0
    // Map color ID = 4 * 4 + 2 = 18
    byte result = MapPalette.rgbToMapColor(255, 0, 0);
    assertEquals(18, Byte.toUnsignedInt(result));
}

@Test
void testBlackMapsToNearestDark() {
    byte result = MapPalette.rgbToMapColor(0, 0, 0);
    // Should map to the darkest available color (not transparent)
    assertTrue(Byte.toUnsignedInt(result) >= 4); // IDs 0-3 are transparent
}

@Test
void testTransparentPixelsMapToZero() {
    byte result = MapPalette.argbToMapColor(0x00000000); // fully transparent
    assertEquals(0, Byte.toUnsignedInt(result));
}

@Test
void testLookupTableConsistency() {
    // Verify the precomputed table matches brute-force search
    byte fast = MapPalette.rgbToMapColor(123, 45, 67);
    byte brute = MapPalette.rgbToMapColorBruteForce(123, 45, 67);
    assertEquals(brute, fast);
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*.MapPaletteTest"`
Expected: FAIL — class not found

**Step 3: Implement MapPalette**

```java
package com.audioviz.map;

/**
 * Converts ARGB colors to Minecraft's 248-entry map color palette.
 * Uses a precomputed 16MB lookup table (quantized to 6 bits per channel)
 * for O(1) conversion at runtime.
 */
public final class MapPalette {

    // 62 base colors × 4 shades = 248 entries
    // Index = base_id * 4 + shade (0..3)
    // Shade multipliers: 0→180/255, 1→220/255, 2→255/255, 3→135/255
    private static final int[] SHADE_MULTIPLIERS = {180, 220, 255, 135};

    // Base colors: [id] = {r, g, b}
    // Source: https://minecraft.wiki/w/Map_item_format
    private static final int[][] BASE_COLORS = {
        /* 0  NONE      */ {0, 0, 0},        // transparent, skip
        /* 1  GRASS     */ {127, 178, 56},
        /* 2  SAND      */ {247, 233, 163},
        /* 3  WOOL      */ {199, 199, 199},
        /* 4  FIRE      */ {255, 0, 0},
        /* 5  ICE       */ {160, 160, 255},
        /* 6  METAL     */ {167, 167, 167},
        /* 7  PLANT     */ {0, 124, 0},
        /* 8  SNOW      */ {255, 255, 255},
        /* 9  CLAY      */ {164, 168, 184},
        /* 10 DIRT      */ {151, 109, 77},
        /* 11 STONE     */ {112, 112, 112},
        /* 12 WATER     */ {64, 64, 255},
        /* 13 WOOD      */ {143, 119, 72},
        /* 14 QUARTZ    */ {255, 252, 245},
        /* 15 ORANGE    */ {216, 127, 51},
        /* 16 MAGENTA   */ {178, 76, 216},
        /* 17 LIGHT_BLUE*/ {102, 153, 216},
        /* 18 YELLOW    */ {229, 229, 51},
        /* 19 LIME      */ {127, 204, 25},
        /* 20 PINK      */ {242, 127, 165},
        /* 21 GRAY      */ {76, 76, 76},
        /* 22 LIGHT_GRAY*/ {153, 153, 153},
        /* 23 CYAN      */ {76, 127, 153},
        /* 24 PURPLE    */ {127, 63, 178},
        /* 25 BLUE      */ {51, 76, 178},
        /* 26 BROWN     */ {102, 76, 51},
        /* 27 GREEN     */ {102, 127, 51},
        /* 28 RED       */ {153, 51, 51},
        /* 29 BLACK     */ {25, 25, 25},
        /* 30 GOLD      */ {250, 238, 77},
        /* 31 DIAMOND   */ {92, 219, 213},
        /* 32 LAPIS     */ {74, 128, 255},
        /* 33 EMERALD   */ {0, 217, 58},
        /* 34 PODZOL    */ {129, 86, 49},
        /* 35 NETHER    */ {112, 2, 0},
        /* 36 TERR_WHITE*/ {209, 177, 161},
        /* 37 TERR_ORNGE*/ {159, 82, 36},
        /* 38 TERR_MGNTA*/ {149, 87, 108},
        /* 39 TERR_LTBLU*/ {112, 108, 138},
        /* 40 TERR_YELLW*/ {186, 133, 36},
        /* 41 TERR_LIME */ {103, 117, 53},
        /* 42 TERR_PINK */ {160, 77, 78},
        /* 43 TERR_GRAY */ {57, 41, 35},
        /* 44 TERR_LTGRY*/ {135, 107, 98},
        /* 45 TERR_CYAN */ {87, 92, 92},
        /* 46 TERR_PURPL*/ {122, 73, 88},
        /* 47 TERR_BLUE */ {76, 62, 92},
        /* 48 TERR_BROWN*/ {76, 50, 35},
        /* 49 TERR_GREEN*/ {76, 82, 42},
        /* 50 TERR_RED  */ {142, 60, 46},
        /* 51 TERR_BLACK*/ {37, 22, 16},
        /* 52 CRIMSON_NY*/ {189, 48, 49},
        /* 53 CRIMSON_ST*/ {148, 63, 97},
        /* 54 CRIMSON_HY*/ {92, 25, 29},
        /* 55 WARPED_NYL*/ {22, 126, 134},
        /* 56 WARPED_STE*/ {58, 142, 140},
        /* 57 WARPED_HYP*/ {86, 44, 62},
        /* 58 WARPED_WRT*/ {20, 180, 133},
        /* 59 DEEPSLATE */ {100, 100, 100},
        /* 60 RAW_IRON  */ {216, 175, 147},
        /* 61 LICHEN    */ {127, 167, 150},
    };

    // Quantized lookup table: 64×64×64 = 262,144 bytes
    // Quantize RGB to 6 bits each (>> 2) for memory efficiency
    private static final byte[] LOOKUP = new byte[64 * 64 * 64];

    // Full palette: 248 entries of {r, g, b}
    private static final int[][] PALETTE = new int[248][3];

    static {
        // Build full palette from base colors × shade multipliers
        for (int base = 1; base < BASE_COLORS.length; base++) {
            for (int shade = 0; shade < 4; shade++) {
                int idx = base * 4 + shade;
                int mult = SHADE_MULTIPLIERS[shade];
                PALETTE[idx][0] = BASE_COLORS[base][0] * mult / 255;
                PALETTE[idx][1] = BASE_COLORS[base][1] * mult / 255;
                PALETTE[idx][2] = BASE_COLORS[base][2] * mult / 255;
            }
        }

        // Precompute quantized lookup table
        for (int r6 = 0; r6 < 64; r6++) {
            for (int g6 = 0; g6 < 64; g6++) {
                for (int b6 = 0; b6 < 64; b6++) {
                    int r = (r6 << 2) | 2; // center of quantization bin
                    int g = (g6 << 2) | 2;
                    int b = (b6 << 2) | 2;
                    LOOKUP[(r6 << 12) | (g6 << 6) | b6] = findNearest(r, g, b);
                }
            }
        }
    }

    private static byte findNearest(int r, int g, int b) {
        int bestIdx = 4; // skip transparent (0-3)
        int bestDist = Integer.MAX_VALUE;
        for (int i = 4; i < 248; i++) {
            int dr = r - PALETTE[i][0];
            int dg = g - PALETTE[i][1];
            int db = b - PALETTE[i][2];
            // Weighted Euclidean (human perception: green > red > blue)
            int dist = 2 * dr * dr + 4 * dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return (byte) bestIdx;
    }

    /** O(1) RGB to map color lookup. */
    public static byte rgbToMapColor(int r, int g, int b) {
        return LOOKUP[((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2)];
    }

    /** ARGB to map color. Returns 0 (transparent) if alpha < 128. */
    public static byte argbToMapColor(int argb) {
        if (((argb >> 24) & 0xFF) < 128) return 0;
        return rgbToMapColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }

    /** Brute-force search for testing. */
    public static byte rgbToMapColorBruteForce(int r, int g, int b) {
        return findNearest(r, g, b);
    }
}
```

**Step 4: Run tests**

Run: `./gradlew test --tests "*.MapPaletteTest"`
Expected: PASS

**Step 5: Commit**

```bash
git commit -m "feat: map color palette with precomputed O(1) RGB lookup table"
```

---

### Task 2.2: Map Frame Buffer + Dirty Rect Tracking

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/map/MapFrameBuffer.java`
- Create: `minecraft_mod/src/test/java/com/audioviz/map/MapFrameBufferTest.java`

**Context:** Each Minecraft map is 128×128 pixels. For larger displays, we tile multiple maps. The frame buffer tracks which rectangular region changed (dirty rect) so we can send partial `MapUpdateS2CPacket` updates instead of the full 16KB.

**Step 1: Write failing tests**

```java
@Test
void testSetPixelTracksDirtyRect() {
    MapFrameBuffer buf = new MapFrameBuffer();
    buf.setPixel(10, 20, (byte) 42);
    var dirty = buf.getDirtyRect();
    assertTrue(dirty.isPresent());
    assertEquals(10, dirty.get().x());
    assertEquals(20, dirty.get().z());
    assertEquals(1, dirty.get().width());
    assertEquals(1, dirty.get().height());
}

@Test
void testMultiplePixelsExpandDirtyRect() {
    MapFrameBuffer buf = new MapFrameBuffer();
    buf.setPixel(10, 20, (byte) 1);
    buf.setPixel(50, 60, (byte) 2);
    var dirty = buf.getDirtyRect();
    assertEquals(10, dirty.get().x());
    assertEquals(20, dirty.get().z());
    assertEquals(41, dirty.get().width());  // 50 - 10 + 1
    assertEquals(41, dirty.get().height()); // 60 - 20 + 1
}

@Test
void testClearDirtyResetsTracking() {
    MapFrameBuffer buf = new MapFrameBuffer();
    buf.setPixel(5, 5, (byte) 1);
    buf.clearDirty();
    assertTrue(buf.getDirtyRect().isEmpty());
}

@Test
void testBulkWriteFromBitmapFrame() {
    // Simulate converting a 32x18 BitmapFrameBuffer (ARGB int[]) into map colors
    int[] argbPixels = new int[32 * 18];
    Arrays.fill(argbPixels, 0xFFFF0000); // solid red
    MapFrameBuffer buf = new MapFrameBuffer();
    buf.writeFromArgb(argbPixels, 32, 18, 0, 0);
    // All pixels in 32x18 region should be the map color for red
    byte expectedRed = MapPalette.argbToMapColor(0xFFFF0000);
    assertEquals(expectedRed, buf.getPixel(0, 0));
    assertEquals(expectedRed, buf.getPixel(31, 17));
    assertEquals(0, buf.getPixel(32, 0)); // outside written region
}
```

**Step 2: Run tests — expect FAIL**

**Step 3: Implement MapFrameBuffer**

```java
package com.audioviz.map;

import java.util.Optional;

/**
 * 128x128 byte buffer representing one Minecraft map's pixel data.
 * Tracks dirty rectangle for partial packet updates.
 */
public class MapFrameBuffer {
    public static final int SIZE = 128;
    private final byte[] pixels = new byte[SIZE * SIZE]; // row-major

    // Dirty rect tracking
    private int dirtyMinX = Integer.MAX_VALUE, dirtyMinZ = Integer.MAX_VALUE;
    private int dirtyMaxX = Integer.MIN_VALUE, dirtyMaxZ = Integer.MIN_VALUE;
    private boolean dirty = false;

    public record DirtyRect(int x, int z, int width, int height) {}

    public void setPixel(int x, int z, byte color) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return;
        int idx = z * SIZE + x;
        if (pixels[idx] == color) return; // no change
        pixels[idx] = color;
        markDirty(x, z);
    }

    public byte getPixel(int x, int z) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return 0;
        return pixels[z * SIZE + x];
    }

    /**
     * Bulk-write ARGB pixels into this map buffer with palette conversion.
     * Writes a width×height block starting at (offsetX, offsetZ).
     */
    public void writeFromArgb(int[] argb, int width, int height, int offsetX, int offsetZ) {
        for (int y = 0; y < height && (offsetZ + y) < SIZE; y++) {
            for (int x = 0; x < width && (offsetX + x) < SIZE; x++) {
                int px = offsetX + x;
                int pz = offsetZ + y;
                byte color = MapPalette.argbToMapColor(argb[y * width + x]);
                int idx = pz * SIZE + px;
                if (pixels[idx] != color) {
                    pixels[idx] = color;
                    markDirty(px, pz);
                }
            }
        }
    }

    private void markDirty(int x, int z) {
        dirty = true;
        dirtyMinX = Math.min(dirtyMinX, x);
        dirtyMinZ = Math.min(dirtyMinZ, z);
        dirtyMaxX = Math.max(dirtyMaxX, x);
        dirtyMaxZ = Math.max(dirtyMaxZ, z);
    }

    public Optional<DirtyRect> getDirtyRect() {
        if (!dirty) return Optional.empty();
        return Optional.of(new DirtyRect(
            dirtyMinX, dirtyMinZ,
            dirtyMaxX - dirtyMinX + 1,
            dirtyMaxZ - dirtyMinZ + 1
        ));
    }

    /** Extract the dirty region's pixel data for the map packet. */
    public byte[] extractDirtyData() {
        if (!dirty) return new byte[0];
        int w = dirtyMaxX - dirtyMinX + 1;
        int h = dirtyMaxZ - dirtyMinZ + 1;
        byte[] data = new byte[w * h];
        for (int z = 0; z < h; z++) {
            System.arraycopy(pixels, (dirtyMinZ + z) * SIZE + dirtyMinX, data, z * w, w);
        }
        return data;
    }

    public void clearDirty() {
        dirty = false;
        dirtyMinX = Integer.MAX_VALUE;
        dirtyMinZ = Integer.MAX_VALUE;
        dirtyMaxX = Integer.MIN_VALUE;
        dirtyMaxZ = Integer.MIN_VALUE;
    }

    public byte[] getRawPixels() {
        return pixels;
    }
}
```

**Step 4: Run tests — expect PASS**

**Step 5: Commit**

```bash
git commit -m "feat: map frame buffer with dirty-rect tracking for partial updates"
```

---

### Task 2.3: Map Packet Sender

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/map/MapPacketSender.java`
- Create: `minecraft_mod/src/test/java/com/audioviz/map/MapPacketSenderTest.java`

**Context:** Send `MapUpdateS2CPacket` to players in range. Each packet carries a partial color update (dirty rect only). Minecraft's `MapUpdateS2CPacket` constructor takes: `mapId`, `scale`, `locked`, `decorations`, `colorPatch` (startX, startZ, width, height, colors byte[]).

**Step 1: Implement MapPacketSender**

```java
package com.audioviz.map;

import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Collection;
import java.util.Collections;

public class MapPacketSender {

    /**
     * Send a partial map update to all players within range of the display.
     *
     * @param mapId   Minecraft map ID (from MapState)
     * @param buffer  The map frame buffer with dirty tracking
     * @param players Players to send the update to
     */
    public static void sendDirtyUpdate(int mapId, MapFrameBuffer buffer,
                                        Collection<ServerPlayerEntity> players) {
        var dirtyOpt = buffer.getDirtyRect();
        if (dirtyOpt.isEmpty()) return;

        var dirty = dirtyOpt.get();
        byte[] data = buffer.extractDirtyData();

        // Construct color patch for partial update
        MapState.UpdateData colorPatch = new MapState.UpdateData(
            dirty.x(), dirty.z(),
            dirty.width(), dirty.height(),
            data
        );

        MapUpdateS2CPacket packet = new MapUpdateS2CPacket(
            mapId,
            (byte) 0,      // scale 0 = 1:1 (one pixel per block, but we don't care — maps on item frames ignore scale)
            false,          // locked
            Collections.emptyList(), // no decorations/icons
            colorPatch
        );

        for (ServerPlayerEntity player : players) {
            player.networkHandler.sendPacket(packet);
        }

        buffer.clearDirty();
    }
}
```

**Step 2: Commit**

```bash
git commit -m "feat: map packet sender with dirty-rect partial updates"
```

---

### Task 2.4: Map Display Manager (Tile Grid)

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/map/MapDisplayManager.java`
- Create: `minecraft_mod/src/test/java/com/audioviz/map/MapDisplayManagerTest.java`

**Context:** A single map is 128×128. For larger displays (e.g., 256×128 = two maps wide), we tile maps in a grid of invisible item frames. This manager handles:
1. Allocating map IDs from the server's map state
2. Spawning invisible item frames holding the maps
3. Routing bitmap frame data to the correct tile(s)
4. Calling `MapPacketSender` for each dirty tile each tick

**Step 1: Write failing tests**

```java
@Test
void testTileCalculation() {
    // 32x18 display fits in a single 128x128 map
    MapDisplayManager mgr = new MapDisplayManager(32, 18);
    assertEquals(1, mgr.getTileCountX());
    assertEquals(1, mgr.getTileCountZ());
    assertEquals(1, mgr.getTotalTiles());
}

@Test
void testLargerDisplayTiles() {
    // 200x100 needs 2x1 tiles (each tile is 128x128)
    MapDisplayManager mgr = new MapDisplayManager(200, 100);
    assertEquals(2, mgr.getTileCountX());
    assertEquals(1, mgr.getTileCountZ());
    assertEquals(2, mgr.getTotalTiles());
}

@Test
void testWriteFrameToSingleTile() {
    MapDisplayManager mgr = new MapDisplayManager(32, 18);
    int[] argb = new int[32 * 18];
    Arrays.fill(argb, 0xFFFF0000);
    mgr.writeFrame(argb, 32, 18);
    // Verify the tile's buffer has data
    assertTrue(mgr.getTile(0, 0).getDirtyRect().isPresent());
}
```

**Step 2: Run tests — expect FAIL**

**Step 3: Implement MapDisplayManager**

```java
package com.audioviz.map;

import net.minecraft.server.network.ServerPlayerEntity;
import java.util.Collection;

/**
 * Manages a tiled grid of map frame buffers for large displays.
 * Routes ARGB bitmap data to the correct tiles and sends dirty updates.
 */
public class MapDisplayManager {
    private final int displayWidth;  // total pixels wide
    private final int displayHeight; // total pixels tall
    private final int tilesX;
    private final int tilesZ;
    private final MapFrameBuffer[][] tiles;
    private final int[] mapIds; // Minecraft map IDs, allocated on spawn

    public MapDisplayManager(int displayWidth, int displayHeight) {
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.tilesX = (displayWidth + MapFrameBuffer.SIZE - 1) / MapFrameBuffer.SIZE;
        this.tilesZ = (displayHeight + MapFrameBuffer.SIZE - 1) / MapFrameBuffer.SIZE;
        this.tiles = new MapFrameBuffer[tilesZ][tilesX];
        this.mapIds = new int[tilesX * tilesZ];
        for (int z = 0; z < tilesZ; z++) {
            for (int x = 0; x < tilesX; x++) {
                tiles[z][x] = new MapFrameBuffer();
            }
        }
    }

    /**
     * Write a full ARGB frame from a BitmapFrameBuffer into the tiled maps.
     * Handles splitting across tile boundaries.
     */
    public void writeFrame(int[] argb, int width, int height) {
        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileOffsetX = tx * MapFrameBuffer.SIZE;
                int tileOffsetZ = tz * MapFrameBuffer.SIZE;

                // Calculate the region of the source image that maps to this tile
                int srcStartX = tileOffsetX;
                int srcStartZ = tileOffsetZ;
                int regionW = Math.min(MapFrameBuffer.SIZE, width - srcStartX);
                int regionH = Math.min(MapFrameBuffer.SIZE, height - srcStartZ);

                if (regionW <= 0 || regionH <= 0) continue;

                // Extract the sub-region from the source ARGB array
                int[] subRegion = new int[regionW * regionH];
                for (int row = 0; row < regionH; row++) {
                    System.arraycopy(argb, (srcStartZ + row) * width + srcStartX,
                                     subRegion, row * regionW, regionW);
                }

                tiles[tz][tx].writeFromArgb(subRegion, regionW, regionH, 0, 0);
            }
        }
    }

    /** Send dirty updates for all tiles to players. */
    public void sendUpdates(Collection<ServerPlayerEntity> players) {
        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int mapId = mapIds[tz * tilesX + tx];
                MapPacketSender.sendDirtyUpdate(mapId, tiles[tz][tx], players);
            }
        }
    }

    public void setMapId(int tileX, int tileZ, int mapId) {
        mapIds[tileZ * tilesX + tileX] = mapId;
    }

    public int getTileCountX() { return tilesX; }
    public int getTileCountZ() { return tilesZ; }
    public int getTotalTiles() { return tilesX * tilesZ; }
    public MapFrameBuffer getTile(int x, int z) { return tiles[z][x]; }
}
```

**Step 4: Run tests — expect PASS**

**Step 5: Commit**

```bash
git commit -m "feat: tiled map display manager with multi-tile ARGB routing"
```

---

### Task 2.5: Item Frame Spawner

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/map/MapItemFrameSpawner.java`

**Context:** Spawn invisible item frames in a grid at the zone's origin, each holding a filled map. Item frames are real entities (not virtual — Polymer doesn't support item frames well) but there are very few of them (1 per 128×128 tile vs. 1 per pixel). For a 64×36 display, that's just 1 item frame instead of 2,304 TextDisplay entities.

**Step 1: Implement spawner**

```java
package com.audioviz.map;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public class MapItemFrameSpawner {

    /**
     * Spawn a grid of invisible item frames holding blank maps.
     * Returns allocated map IDs for each tile.
     *
     * @param world     Server world to spawn in
     * @param origin    Bottom-left corner of the display wall
     * @param facing    Direction the display faces (e.g., Direction.NORTH)
     * @param tilesX    Number of tiles horizontally
     * @param tilesZ    Number of tiles vertically
     * @return          List of (tileX, tileZ, mapId) for each spawned frame
     */
    public static List<SpawnedTile> spawnGrid(ServerWorld world, BlockPos origin,
                                               Direction facing, int tilesX, int tilesZ) {
        List<SpawnedTile> result = new ArrayList<>();

        // Determine horizontal/vertical axes based on facing direction
        Direction right = facing.rotateYClockwise();

        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                // Position: offset from origin along the wall
                BlockPos pos = origin
                    .offset(right, tx)
                    .offset(Direction.UP, tz);

                // Allocate a new map ID
                MapState mapState = MapState.of(
                    (byte) 0,  // scale 0
                    false,     // not locked
                    world.getRegistryKey()
                );
                int mapId = world.getNextMapId();
                world.putMapState(FilledMapItem.getMapName(mapId), mapState);

                // Create filled map item
                ItemStack mapItem = new ItemStack(Items.FILLED_MAP);
                mapItem.set(net.minecraft.component.DataComponentTypes.MAP_ID,
                    new net.minecraft.component.type.MapIdComponent(mapId));

                // Spawn invisible item frame
                ItemFrameEntity frame = new ItemFrameEntity(
                    EntityType.ITEM_FRAME, world
                );
                frame.setPosition(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                frame.setFacing(facing);
                frame.setHeldItemStack(mapItem);
                frame.setInvisible(true);
                frame.setSilent(true);
                world.spawnEntity(frame);

                result.add(new SpawnedTile(tx, tz, mapId, frame));
            }
        }

        return result;
    }

    public record SpawnedTile(int tileX, int tileZ, int mapId, ItemFrameEntity frame) {}
}
```

**Step 2: Commit**

```bash
git commit -m "feat: item frame grid spawner for map tile displays"
```

---

### Task 2.6: Map Renderer Backend (Integration)

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/render/MapRendererBackend.java`

**Context:** This is the equivalent of `BitmapRendererBackend` from the Paper plugin but uses maps instead of TextDisplay entities. It receives ARGB frame data from the bitmap pattern system and routes it through MapDisplayManager → MapPacketSender.

**Step 1: Implement MapRendererBackend**

```java
package com.audioviz.render;

import com.audioviz.map.MapDisplayManager;
import com.audioviz.map.MapItemFrameSpawner;
import com.audioviz.zones.VisualizationZone;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MapRendererBackend {
    private final Map<String, MapDisplayManager> displays = new ConcurrentHashMap<>();
    private final Map<String, List<MapItemFrameSpawner.SpawnedTile>> spawnedTiles = new ConcurrentHashMap<>();

    /**
     * Initialize a map display for a zone.
     * Call once when a zone is created or switches to MAP renderer.
     */
    public void initializeDisplay(String zoneName, VisualizationZone zone,
                                   int pixelWidth, int pixelHeight,
                                   ServerWorld world, Direction facing) {
        MapDisplayManager display = new MapDisplayManager(pixelWidth, pixelHeight);

        // Spawn item frame grid
        BlockPos origin = zone.getOrigin();
        var tiles = MapItemFrameSpawner.spawnGrid(
            world, origin, facing,
            display.getTileCountX(), display.getTileCountZ()
        );

        // Wire up map IDs
        for (var tile : tiles) {
            display.setMapId(tile.tileX(), tile.tileZ(), tile.mapId());
        }

        displays.put(zoneName.toLowerCase(), display);
        spawnedTiles.put(zoneName.toLowerCase(), tiles);
    }

    /**
     * Apply an ARGB frame (from BitmapFrameBuffer or raw bitmap_frame message).
     * Converts to map palette and tracks dirty rects automatically.
     */
    public void applyFrame(String zoneName, int[] argbPixels, int width, int height) {
        MapDisplayManager display = displays.get(zoneName.toLowerCase());
        if (display == null) return;
        display.writeFrame(argbPixels, width, height);
    }

    /** Send all dirty tile updates to nearby players. Call once per tick. */
    public void flush(String zoneName, Collection<ServerPlayerEntity> players) {
        MapDisplayManager display = displays.get(zoneName.toLowerCase());
        if (display == null) return;
        display.sendUpdates(players);
    }

    /** Cleanup: remove item frames when zone is deleted. */
    public void destroyDisplay(String zoneName) {
        var tiles = spawnedTiles.remove(zoneName.toLowerCase());
        if (tiles != null) {
            for (var tile : tiles) {
                if (tile.frame().isAlive()) {
                    tile.frame().discard();
                }
            }
        }
        displays.remove(zoneName.toLowerCase());
    }
}
```

**Step 2: Commit**

```bash
git commit -m "feat: map renderer backend integrating display manager + frame spawner"
```

---

## Phase 3: Polymer Virtual Entity Renderer (NEW)

For 3D Lua patterns that need depth, rotation, and scale — use Polymer's packet-only virtual Display Entities with bundle delimiter wrapping.

### Task 3.1: Virtual Entity Pool

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/virtual/VirtualEntityPool.java`
- Create: `minecraft_mod/src/test/java/com/audioviz/virtual/VirtualEntityPoolTest.java`

**Context:** Replace `EntityPoolManager` from Paper. Instead of real server-side entities, use Polymer's `ElementHolder` with `BlockDisplayElement` instances. Zero server-side overhead (no collision, no ticking, no chunk tracking).

**Step 1: Write failing test**

```java
@Test
void testPoolCreatesRequestedCount() {
    VirtualEntityPool pool = new VirtualEntityPool(64);
    assertEquals(64, pool.size());
}

@Test
void testPoolResizesUp() {
    VirtualEntityPool pool = new VirtualEntityPool(32);
    pool.resize(64);
    assertEquals(64, pool.size());
}

@Test
void testPoolResizesDown() {
    VirtualEntityPool pool = new VirtualEntityPool(64);
    pool.resize(32);
    assertEquals(32, pool.size());
}
```

**Step 2: Implement VirtualEntityPool**

```java
package com.audioviz.virtual;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Pool of virtual BlockDisplay elements managed by Polymer.
 * No server-side entities — pure packet-based rendering.
 */
public class VirtualEntityPool {
    private final ElementHolder holder;
    private final List<BlockDisplayElement> elements;
    private BlockState defaultBlock = Blocks.WHITE_CONCRETE.getDefaultState();

    public VirtualEntityPool(int initialSize) {
        this.holder = new ElementHolder();
        this.elements = new ArrayList<>(initialSize);
        for (int i = 0; i < initialSize; i++) {
            addElement();
        }
    }

    private BlockDisplayElement addElement() {
        BlockDisplayElement el = new BlockDisplayElement(defaultBlock);
        el.setScale(new Vector3f(0.2f, 0.2f, 0.2f));
        holder.addElement(el);
        elements.add(el);
        return el;
    }

    public void resize(int newSize) {
        while (elements.size() < newSize) {
            addElement();
        }
        while (elements.size() > newSize) {
            BlockDisplayElement removed = elements.remove(elements.size() - 1);
            holder.removeElement(removed);
        }
    }

    public BlockDisplayElement get(int index) {
        return elements.get(index);
    }

    public int size() {
        return elements.size();
    }

    public ElementHolder getHolder() {
        return holder;
    }

    /**
     * Batch-update all elements from EntityUpdate list.
     * Call holder.tick() after to flush changes.
     */
    public void applyUpdates(List<EntityUpdate> updates) {
        for (EntityUpdate update : updates) {
            int idx = update.index();
            if (idx < 0 || idx >= elements.size()) continue;
            BlockDisplayElement el = elements.get(idx);

            if (update.position() != null) {
                el.setOffset(update.position());
            }
            if (update.scale() != null) {
                el.setScale(update.scale());
            }
            if (update.blockState() != null) {
                el.setBlockState(update.blockState());
            }
        }
    }

    public record EntityUpdate(int index, Vec3d position, Vector3f scale,
                                BlockState blockState) {}
}
```

**Step 3: Run tests — expect PASS**

**Step 4: Commit**

```bash
git commit -m "feat: Polymer virtual entity pool with batch updates"
```

---

### Task 3.2: Bundle Packet Wrapper

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/net/BundlePacketSender.java`

**Context:** Bundle delimiter packets (`BundleS2CPacket`) guarantee the client processes all enclosed packets atomically on the same render frame. Wrap all per-tick entity updates in a bundle so hundreds of metadata changes appear as one visual update — no tearing.

**Step 1: Implement BundlePacketSender**

```java
package com.audioviz.net;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Wraps multiple packets in a Bundle for atomic client-side processing.
 * All packets in a bundle are applied on the same client tick — no visual tearing.
 *
 * Max 4096 packets per bundle (Minecraft protocol limit).
 */
public class BundlePacketSender {
    private static final int MAX_BUNDLE_SIZE = 4096;

    /**
     * Send a list of packets as a bundle to all specified players.
     * Automatically splits into multiple bundles if > 4096 packets.
     */
    public static void sendBundled(List<Packet<?>> packets,
                                    Collection<ServerPlayerEntity> players) {
        if (packets.isEmpty() || players.isEmpty()) return;

        // Split into chunks of MAX_BUNDLE_SIZE
        for (int i = 0; i < packets.size(); i += MAX_BUNDLE_SIZE) {
            int end = Math.min(i + MAX_BUNDLE_SIZE, packets.size());
            List<Packet<?>> chunk = packets.subList(i, end);
            Iterable<Packet<?>> iterable = new ArrayList<>(chunk);
            BundleS2CPacket bundle = new BundleS2CPacket(iterable);

            for (ServerPlayerEntity player : players) {
                player.networkHandler.sendPacket(bundle);
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git commit -m "feat: bundle packet sender for atomic multi-entity updates"
```

---

### Task 3.3: Virtual Entity Renderer Backend

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/render/VirtualEntityRendererBackend.java`

**Context:** The equivalent of `DisplayEntitiesBackend` from Paper but using Polymer virtual entities + bundle packets. Receives `batch_update` messages from the VJ server (Lua patterns) and applies them to virtual BlockDisplayElements.

**Step 1: Implement backend**

```java
package com.audioviz.render;

import com.audioviz.net.BundlePacketSender;
import com.audioviz.virtual.VirtualEntityPool;
import com.audioviz.zones.VisualizationZone;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3D visualization renderer using Polymer virtual Display Entities.
 * Zero server-side entity overhead. Bundle-wrapped packet updates.
 */
public class VirtualEntityRendererBackend {
    private final Map<String, VirtualEntityPool> pools = new ConcurrentHashMap<>();

    public void initializePool(String zoneName, VisualizationZone zone,
                                int entityCount, ServerWorld world) {
        VirtualEntityPool pool = new VirtualEntityPool(entityCount);

        // Attach to world at zone origin — auto sends spawn/destroy as players load chunks
        ChunkAttachment.of(pool.getHolder(), world, zone.getOriginVec3d());

        pools.put(zoneName.toLowerCase(), pool);
    }

    /**
     * Apply batch entity updates from VJ server (Lua patterns).
     * Converts normalized (0-1) positions to zone-local world coordinates.
     */
    public void applyBatchUpdate(String zoneName, VisualizationZone zone,
                                  java.util.List<VirtualEntityPool.EntityUpdate> updates) {
        VirtualEntityPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null) return;
        pool.applyUpdates(updates);
    }

    /** Flush pending element changes. Call once per tick after all updates. */
    public void flush(String zoneName) {
        VirtualEntityPool pool = pools.get(zoneName.toLowerCase());
        if (pool == null) return;
        pool.getHolder().tick();
    }

    public void destroyPool(String zoneName) {
        VirtualEntityPool pool = pools.remove(zoneName.toLowerCase());
        if (pool != null) {
            pool.getHolder().destroy();
        }
    }

    public void resizePool(String zoneName, int newSize) {
        VirtualEntityPool pool = pools.get(zoneName.toLowerCase());
        if (pool != null) pool.resize(newSize);
    }
}
```

**Step 2: Commit**

```bash
git commit -m "feat: virtual entity renderer backend with Polymer + bundle packets"
```

---

## Phase 4: Bitmap Pattern Engine (Port)

### Task 4.1: Port BitmapFrameBuffer

**Files:**
- Copy: `minecraft_plugin/.../bitmap/BitmapFrameBuffer.java`

**Context:** `BitmapFrameBuffer` is 100% pure Java math (ARGB int arrays, fill/draw primitives, color utilities). It has zero Paper API dependencies. Copy it directly.

**Step 1: Copy file**

Copy `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapFrameBuffer.java` to `minecraft_mod/src/main/java/com/audioviz/bitmap/BitmapFrameBuffer.java`. No changes needed — verify it compiles.

**Step 2: Copy all BitmapPattern implementations**

These are all pure pixel math. Copy the entire directory tree:
- `minecraft_mod/src/main/java/com/audioviz/bitmap/BitmapPattern.java` (base class)
- `minecraft_mod/src/main/java/com/audioviz/bitmap/patterns/` (all ~20 audio-reactive patterns)
- `minecraft_mod/src/main/java/com/audioviz/bitmap/text/` (text renderer, marquee, etc.)
- `minecraft_mod/src/main/java/com/audioviz/bitmap/media/` (image, DJ logo)
- `minecraft_mod/src/main/java/com/audioviz/bitmap/effects/` (post-processing)
- `minecraft_mod/src/main/java/com/audioviz/bitmap/transitions/` (fade, dissolve, etc.)
- `minecraft_mod/src/main/java/com/audioviz/bitmap/composition/` (layer compositor)

Verify: `./gradlew compileJava` — should compile with no changes. If any file imports `org.bukkit.*`, those are the ones that need adaptation (likely only `BitmapPattern.java` base class if it references `AudioState` or `Material`).

**Step 3: Commit**

```bash
git commit -m "feat: port bitmap frame buffer and all pattern implementations"
```

---

### Task 4.2: Port BitmapPatternManager

**Files:**
- Copy + adapt: `minecraft_plugin/.../bitmap/BitmapPatternManager.java`
- Copy + adapt: `minecraft_plugin/.../bitmap/AsyncBitmapRenderer.java`

**Context:** `BitmapPatternManager` has two Bukkit dependencies:
1. `BukkitScheduler.runTaskTimer()` → replaced by `AudioVizMod.tick()` calling `patternManager.tick()`
2. References to `BitmapRendererBackend` → now calls `MapRendererBackend.applyFrame()` or `VirtualEntityRendererBackend` depending on zone config

**Step 1: Adapt BitmapPatternManager**

- Remove `BukkitTask` / `BukkitScheduler` references
- Expose `tick(audioState)` as a public method called from `AudioVizMod.tick()`
- Replace `renderer.applyFrame(zoneName, frameBuffer, brightness)` with:

```java
// In the tick method, after pattern renders to frameBuffer:
int[] pixels = frameBuffer.getRawPixels();
mapRenderer.applyFrame(zoneName, pixels, frameBuffer.getWidth(), frameBuffer.getHeight());
```

- `AsyncBitmapRenderer` is pure threading (ExecutorService + double buffering) — no Bukkit deps. Copy directly.

**Step 2: Commit**

```bash
git commit -m "feat: port bitmap pattern manager to Fabric tick system"
```

---

### Task 4.3: Wire Up the Tick Loop

**Files:**
- Modify: `minecraft_mod/src/main/java/com/audioviz/AudioVizMod.java`

**Context:** Connect all the pieces in the main tick loop.

**Step 1: Update tick() method**

```java
private MapRendererBackend mapRenderer;
private VirtualEntityRendererBackend virtualRenderer;
private BitmapPatternManager patternManager;
private MessageQueue messageQueue;

private void tick() {
    // 1. Process incoming WebSocket messages
    messageQueue.processTick();

    // 2. Tick bitmap patterns (renders to frame buffers)
    patternManager.tick(latestAudioState);

    // 3. Flush map display updates to players
    for (String zoneName : mapRenderer.getActiveZones()) {
        var players = getPlayersNearZone(zoneName);
        mapRenderer.flush(zoneName, players);
    }

    // 4. Flush virtual entity updates
    for (String zoneName : virtualRenderer.getActiveZones()) {
        virtualRenderer.flush(zoneName);
    }
}

private Collection<ServerPlayerEntity> getPlayersNearZone(String zoneName) {
    // Get zone origin, find players within tracking distance
    VisualizationZone zone = zoneManager.getZone(zoneName);
    if (zone == null) return Collections.emptyList();
    ServerWorld world = zone.getWorld();
    if (world == null) return Collections.emptyList();
    Vec3d origin = zone.getOriginVec3d();
    double range = 128.0; // tracking range
    return world.getPlayers(p -> p.squaredDistanceTo(origin) < range * range);
}
```

**Step 2: Commit**

```bash
git commit -m "feat: wire up main tick loop — messages, patterns, renderers"
```

---

## Phase 5: Port Supporting Systems

### Task 5.1: Port Particle System

**Files:**
- Copy + adapt: `minecraft_plugin/.../particles/` (all files)

**Context:** Replace `Location.getWorld().spawnParticle()` with Fabric's `ServerWorld.spawnParticles()`. The parameters are nearly identical (particle type, position, count, offset, speed).

Paper: `world.spawnParticle(Particle.FLAME, loc, 10, 0.5, 0.5, 0.5, 0.02)`
Fabric: `world.spawnParticles(ParticleTypes.FLAME, x, y, z, 10, 0.5, 0.5, 0.5, 0.02)`

**Step 1: Adapt particle effects**

Global find-and-replace within particle files:
- `org.bukkit.Particle` → `net.minecraft.particle.ParticleTypes`
- `org.bukkit.Location` → extract `x`, `y`, `z` doubles from zone/world
- `world.spawnParticle(type, loc, count, dx, dy, dz, speed)` → `((ServerWorld) world).spawnParticles(type, x, y, z, count, dx, dy, dz, speed)`

**Step 2: Commit**

```bash
git commit -m "feat: port particle effects to Fabric particle API"
```

---

### Task 5.2: Port Command System

**Files:**
- Create: `minecraft_mod/src/main/java/com/audioviz/commands/AudioVizCommand.java`

**Context:** Fabric uses Brigadier (Minecraft's native command system) directly, unlike Paper which wraps it. Register commands via `CommandRegistrationCallback`.

**Step 1: Register commands in mod initializer**

```java
// In AudioVizMod.onInitializeServer():
CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
    AudioVizCommand.register(dispatcher);
});
```

**Step 2: Port command structure to Brigadier**

The existing `AudioVizCommand` uses a switch statement on subcommands. Convert to Brigadier's builder pattern:

```java
public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(CommandManager.literal("audioviz")
        .requires(source -> source.hasPermissionLevel(2))
        .then(CommandManager.literal("zone")
            .then(CommandManager.literal("create")
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> createZone(ctx))))
            .then(CommandManager.literal("list")
                .executes(ctx -> listZones(ctx)))
            // ... other subcommands
        )
        .then(CommandManager.literal("pattern")
            .then(CommandManager.argument("name", StringArgumentType.string())
                .executes(ctx -> setPattern(ctx)))
        )
    );
}
```

**Step 3: Commit**

```bash
git commit -m "feat: port commands to Brigadier"
```

---

### Task 5.3: Port GUI/Menu System

**Files:**
- Copy + adapt: `minecraft_plugin/.../gui/` (all files)

**Context:** Fabric doesn't have Paper's `Inventory` abstraction for server-side GUIs. Options:
1. Use `SimpleInventory` + `GenericContainerScreenHandler` (vanilla Minecraft server)
2. Use a library like SGUI (server-side GUI library for Fabric by Patbox, same author as Polymer)

Recommend SGUI (`eu.pb4:sgui`) — same maven repo as Polymer, provides `SimpleGui` with `setSlot()`, click handlers, etc. Very similar API to Paper's inventory menus.

**Step 1: Add SGUI dependency**

```gradle
modImplementation include("eu.pb4:sgui:${project.sgui_version}")
```

**Step 2: Adapt menu base class**

Replace `Inventory` + `InventoryClickEvent` → SGUI's `SimpleGui` + `setSlot(slot, guiElement)`.

```java
// Paper style:
Inventory inv = Bukkit.createInventory(null, 54, "Main Menu");
inv.setItem(0, new ItemStack(Material.DIAMOND));

// SGUI style:
SimpleGui gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
gui.setTitle(Text.literal("Main Menu"));
gui.setSlot(0, new GuiElementBuilder(Items.DIAMOND)
    .setName(Text.literal("Zone Manager"))
    .setCallback((idx, type, action) -> openZoneMenu(player)));
gui.open();
```

**Step 3: Port each menu class** (MainMenu, StageListMenu, etc.)

**Step 4: Commit**

```bash
git commit -m "feat: port GUI menus to SGUI (Fabric server-side GUI)"
```

---

### Task 5.4: Port Voice Chat Integration

**Files:**
- Copy + adapt: `minecraft_plugin/.../voice/VoicechatIntegration.java`

**Context:** Simple Voice Chat has both a Bukkit/Paper plugin and a Fabric mod. The server-side API (`de.maxhenkel.voicechat.api`) is the same across both loaders. The integration should port with minimal changes — mainly the entrypoint registration (Fabric uses `voicechat` entrypoint in `fabric.mod.json` instead of Bukkit service loader).

**Step 1: Add fabric.mod.json entrypoint**

```json
{
  "entrypoints": {
    "server": ["com.audioviz.AudioVizMod"],
    "voicechat": ["com.audioviz.voice.VoicechatIntegration"]
  }
}
```

**Step 2: Adapt scheduler references**

Replace `Bukkit.getScheduler()` tick drain → call `drainQueue()` from `AudioVizMod.tick()`.

**Step 3: Commit**

```bash
git commit -m "feat: port Simple Voice Chat integration to Fabric"
```

---

### Task 5.5: Port Stage & Decorator Systems

**Files:**
- Copy + adapt: `minecraft_plugin/.../stages/` (all files)
- Copy + adapt: `minecraft_plugin/.../decorators/` (all files)

**Context:** Stages are primarily data structures (name, anchor, zones, templates). Decorators create TextDisplay/BlockDisplay entities for visual elements — these should use Polymer virtual entities just like the 3D pattern renderer.

**Step 1: Port Stage/StageManager**

Replace `org.bukkit.Location` → `BlockPos` for anchors. Replace YAML persistence with Gson JSON.

**Step 2: Port decorators to use Polymer virtual elements**

Decorators that spawn TextDisplay entities (banners, billboards) should use Polymer's `TextDisplayElement` instead. This gives them the same zero-overhead benefit as the pattern renderer.

**Step 3: Commit**

```bash
git commit -m "feat: port stage and decorator systems to Fabric + Polymer"
```

---

## Phase 6: Integration Testing

### Task 6.1: End-to-End Smoke Test

**Step 1: Start the Fabric server**

```bash
cd minecraft_mod && ./gradlew runServer
```

Verify in console:
- "AudioViz Fabric mod initializing..."
- "AudioViz started"
- "WebSocket server started on port 8765"

**Step 2: Connect VJ server**

```bash
cd vj_server && audioviz-vj --port 9000 --no-auth
```

Verify WebSocket connection established.

**Step 3: Create a zone and test map display**

In-game:
```
/audioviz zone create test 32 18
/audioviz pattern set spectrumbars
```

Verify: Item frames spawn, map displays show spectrum bars pattern.

**Step 4: Test 3D pattern**

```
/audioviz zone create test3d 64 -renderer virtual_entity
/audioviz pattern set helix
```

Verify: Virtual BlockDisplay entities appear, pattern animates.

**Step 5: Performance baseline**

Connect DJ client, play music. Monitor:
- Server TPS (should stay at 20.0)
- Network bytes/sec (should be dramatically lower than Paper plugin for bitmap)
- Visual tearing (should be eliminated by bundle packets)

**Step 6: Commit**

```bash
git commit -m "test: end-to-end smoke test passing"
```

---

## Phase 7: Server Configuration

### Task 7.1: Document Server Properties

**Files:**
- Create: `minecraft_mod/README.md`

**Step 1: Document required server.properties changes**

```markdown
## Required Server Configuration

### server.properties
```properties
# REQUIRED: Disable packet compression.
# Entity metadata packets are 35-50 bytes each — compression overhead
# exceeds bandwidth savings at this size. Map packets are ~1-4KB and
# compress poorly (random pixel data). CPU savings are significant.
network-compression-threshold=-1
```

### Recommended JVM flags
```bash
# G1GC for low-pause, increased young gen for frame buffer allocations
java -Xms4G -Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
     -XX:MaxGCPauseMillis=20 -XX:G1NewSizePercent=30 \
     -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M \
     -jar fabric-server.jar nogui
```
```

**Step 2: Commit**

```bash
git commit -m "docs: server configuration for Fabric mod"
```

---

## Migration Checklist

Summary of what ports directly vs. what's new:

| Component | Strategy | Notes |
|-|-|-|
| BitmapFrameBuffer | Direct copy | Zero API deps |
| All bitmap patterns (20+) | Direct copy | Pure pixel math |
| Effects/transitions | Direct copy | Pure pixel math |
| AsyncBitmapRenderer | Direct copy | Pure threading |
| WebSocket server | Minor adapt | Remove Bukkit scheduler |
| MessageQueue | Minor adapt | Remove Bukkit scheduler |
| MessageHandler | Moderate adapt | Rewire to new renderers |
| Zone system | Moderate adapt | Location → BlockPos |
| Stage system | Moderate adapt | Location → BlockPos, YAML → JSON |
| Commands | Rewrite | Bukkit commands → Brigadier |
| GUI menus | Rewrite | Bukkit Inventory → SGUI |
| Entity rendering | **New: MapRendererBackend** | Maps replace TextDisplay entities |
| 3D rendering | **New: VirtualEntityRendererBackend** | Polymer replaces real entities |
| Bundle packets | **New: BundlePacketSender** | Eliminates visual tearing |
| Map palette | **New: MapPalette** | RGB → 248-color conversion |
| Particle effects | Minor adapt | API signature change |
| Voice chat | Minor adapt | Same API, different loader |
| Decorators | Moderate adapt | Use Polymer virtual elements |
| Bedrock support | Evaluate | Geyser works with Fabric too |

---

## Expected Performance Comparison

For a 64×36 bitmap display (2,304 pixels) with 10 players in range at 20 TPS:

| Metric | Paper Plugin (current) | Fabric Mod (this plan) |
|-|-|-|
| Entities in world | 2,304 TextDisplay | 1 item frame |
| Packets/tick (full frame) | 2,304 × 10 = 23,040 | 1 × 10 = 10 |
| Bytes/tick (full frame) | ~1.15 MB | ~4 KB |
| Server entity overhead | Collision + tick + chunk tracking for 2,304 entities | Zero (map data only) |
| Visual tearing | Yes (sequential packets) | No (single map packet) |
| Compression CPU | 23,040 zlib calls/tick | Disabled (`-1`) |
| Color depth | Full ARGB (16.7M colors) | 248 map colors |

The color depth tradeoff (full RGB → 248 colors) is the main cost. For audio visualizations with bold, saturated colors this should be acceptable. 3D patterns that need full color fidelity use the Polymer virtual entity path instead.
