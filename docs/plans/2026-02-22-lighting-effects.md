# Lighting Effects Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add ambient light blocks, per-pixel bitmap brightness, and bloom post-processing to make visualizations visually impactful in vanilla Minecraft.

**Architecture:** Three independent features that layer together. Ambient lights are MC plugin-only (reads audio state already received). Per-pixel brightness extends the bitmap_frame protocol with an optional brightness array. Bloom is a VJ server post-processing pass on the pixel buffer before sending.

**Tech Stack:** Java 21 / Paper API (MC plugin), Python 3.11+ (VJ server), WebSocket JSON protocol

---

### Task 1: AmbientLightManager — Core Class

**Files:**
- Create: `minecraft_plugin/src/main/java/com/audioviz/lighting/AmbientLightManager.java`

**Step 1: Create the class with light position calculation**

```java
package com.audioviz.lighting;

import com.audioviz.zones.VisualizationZone;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;

import java.util.*;
import java.util.logging.Logger;

/**
 * Places invisible Light blocks around visualization zones that pulse
 * with audio intensity. Restores original blocks on teardown.
 */
public class AmbientLightManager {

    private static final int SPACING = 3;        // blocks between light points
    private static final int OFFSET = 1;         // blocks out from zone face
    private static final int MAX_LIGHTS = 40;    // cap per zone
    private static final int UPDATE_INTERVAL = 3; // ticks between updates (150ms)

    private final Logger logger;

    /** zone name → list of light block locations */
    private final Map<String, List<Location>> zoneLights = new HashMap<>();
    /** location → original block material (for restoration) */
    private final Map<Location, Material> originalBlocks = new HashMap<>();
    /** zone name → current light level (avoid redundant updates) */
    private final Map<String, Integer> currentLevels = new HashMap<>();
    /** tick counter for update throttling */
    private int tickCounter = 0;

    public AmbientLightManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Calculate and place light blocks around a zone's perimeter.
     * Only places lights on the floor plane (Y = origin.Y - 1) and
     * on the front face (Z = origin.Z - OFFSET), spaced every SPACING blocks.
     */
    public void initializeZone(VisualizationZone zone) {
        String zoneName = zone.getName().toLowerCase();
        if (zoneLights.containsKey(zoneName)) {
            teardownZone(zoneName);
        }

        Location origin = zone.getOrigin();
        double sizeX = zone.getSize().getX();
        double sizeY = zone.getSize().getY();

        List<Location> positions = new ArrayList<>();

        // Floor row: along X at Y-1, Z centered
        for (double x = 0; x <= sizeX; x += SPACING) {
            Location loc = origin.clone().add(x, -1, -OFFSET);
            if (loc.getBlock().getType().isAir() || loc.getBlock().isLiquid()) {
                positions.add(loc);
            }
        }

        // Side columns: along Y at X=-OFFSET and X=sizeX+OFFSET
        for (double y = 0; y <= sizeY; y += SPACING) {
            Location left = origin.clone().add(-OFFSET, y, 0);
            Location right = origin.clone().add(sizeX + OFFSET, y, 0);
            if (left.getBlock().getType().isAir() || left.getBlock().isLiquid()) {
                positions.add(left);
            }
            if (right.getBlock().getType().isAir() || right.getBlock().isLiquid()) {
                positions.add(right);
            }
        }

        // Cap at MAX_LIGHTS
        if (positions.size() > MAX_LIGHTS) {
            positions = positions.subList(0, MAX_LIGHTS);
        }

        // Save originals and place initial light blocks at level 0
        for (Location loc : positions) {
            originalBlocks.put(loc, loc.getBlock().getType());
            setLightLevel(loc, 0);
        }

        zoneLights.put(zoneName, positions);
        currentLevels.put(zoneName, 0);
        logger.info("Ambient lights: initialized " + positions.size() + " lights for zone '" + zoneName + "'");
    }

    /**
     * Called every server tick. Updates light levels based on audio intensity.
     * Throttled to UPDATE_INTERVAL ticks unless isBeat is true (instant response).
     */
    public void tick(String zoneName, float intensity, boolean isBeat) {
        tickCounter++;
        if (!isBeat && tickCounter % UPDATE_INTERVAL != 0) {
            return;
        }

        List<Location> lights = zoneLights.get(zoneName.toLowerCase());
        if (lights == null || lights.isEmpty()) return;

        int targetLevel = Math.round(intensity * 15.0f);
        targetLevel = Math.max(0, Math.min(15, targetLevel));

        // On beat, flash to max
        if (isBeat) {
            targetLevel = 15;
        }

        Integer current = currentLevels.get(zoneName.toLowerCase());
        if (current != null && current == targetLevel) return;

        for (Location loc : lights) {
            setLightLevel(loc, targetLevel);
        }
        currentLevels.put(zoneName.toLowerCase(), targetLevel);
    }

    /** Remove all light blocks for a zone and restore originals. */
    public void teardownZone(String zoneName) {
        String key = zoneName.toLowerCase();
        List<Location> lights = zoneLights.remove(key);
        currentLevels.remove(key);
        if (lights == null) return;

        for (Location loc : lights) {
            Material original = originalBlocks.remove(loc);
            if (original != null) {
                loc.getBlock().setType(original, true);
            } else {
                loc.getBlock().setType(Material.AIR, true);
            }
        }
        logger.info("Ambient lights: cleaned up zone '" + zoneName + "'");
    }

    /** Remove all light blocks for all zones. */
    public void teardownAll() {
        for (String zoneName : new ArrayList<>(zoneLights.keySet())) {
            teardownZone(zoneName);
        }
    }

    private void setLightLevel(Location loc, int level) {
        Block block = loc.getBlock();
        if (level <= 0) {
            // Remove the light block
            if (block.getType() == Material.LIGHT) {
                Material original = originalBlocks.getOrDefault(loc, Material.AIR);
                block.setType(original, true);
            }
            return;
        }
        block.setType(Material.LIGHT, false);
        if (block.getBlockData() instanceof Levelled levelled) {
            levelled.setLevel(level);
            block.setBlockData(levelled, true);
        }
    }

    public boolean hasZone(String zoneName) {
        return zoneLights.containsKey(zoneName.toLowerCase());
    }
}
```

**Step 2: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/lighting/AmbientLightManager.java
git commit -m "feat(lighting): add AmbientLightManager for audio-reactive light blocks"
```

---

### Task 2: Wire AmbientLightManager into Plugin Lifecycle

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java`

**Step 1: Register AmbientLightManager in AudioVizPlugin**

Add field and getter to `AudioVizPlugin.java`:
```java
private AmbientLightManager ambientLightManager;

// In onEnable(), after zoneManager initialization:
this.ambientLightManager = new AmbientLightManager(getLogger());

// Add getter:
public AmbientLightManager getAmbientLightManager() { return ambientLightManager; }

// In onDisable():
if (ambientLightManager != null) ambientLightManager.teardownAll();
```

**Step 2: Initialize ambient lights when bitmap is initialized**

In `MessageHandler.java` `handleInitBitmap()`, after the bitmap grid is set up and the zone is known, add:

```java
// After successful bitmap init, initialize ambient lights
AmbientLightManager ambientMgr = plugin.getAmbientLightManager();
if (!ambientMgr.hasZone(zoneName)) {
    VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
    if (zone != null) {
        ambientMgr.initializeZone(zone);
    }
}
```

**Step 3: Tick ambient lights from audio state**

In `MessageHandler.java`, find where `audio_state` or `bitmap_frame` messages are processed. After applying the bitmap frame, tick the ambient lights:

```java
// In handleBitmapFrame(), after renderer.applyRawFrame():
AmbientLightManager ambientMgr = plugin.getAmbientLightManager();
if (ambientMgr.hasZone(zoneName)) {
    // Extract intensity from pixel data: average luminance of all pixels
    float intensity = averagePixelLuminance(pixels);
    ambientMgr.tick(zoneName, intensity, false);
}
```

Add helper method to MessageHandler:
```java
private float averagePixelLuminance(int[] argbPixels) {
    if (argbPixels == null || argbPixels.length == 0) return 0f;
    long sum = 0;
    for (int argb : argbPixels) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        // Perceived luminance (ITU-R BT.709)
        sum += (int)(0.2126 * r + 0.7152 * g + 0.0722 * b);
    }
    return (float)(sum / argbPixels.length) / 255.0f;
}
```

**Step 4: Teardown ambient lights on zone cleanup**

In `BitmapRendererBackend.teardown()`, add:
```java
plugin.getAmbientLightManager().teardownZone(zoneName);
```

**Step 5: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/AudioVizPlugin.java \
       minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java \
       minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRendererBackend.java
git commit -m "feat(lighting): wire AmbientLightManager into bitmap lifecycle"
```

---

### Task 3: Per-Pixel Brightness — Protocol Extension

**Files:**
- Modify: `protocol/schemas/messages/bitmap-frame.schema.json` (add optional `brightness` field)
- Modify: `vj_server/viz_client.py` (add brightness param to send methods)

**Step 1: Extend bitmap-frame schema**

Add optional `brightness` field (base64-encoded byte array, one byte per pixel, 0-15):

```json
"brightness": {
  "description": "Per-pixel brightness (0-15). Base64-encoded byte array, one byte per pixel. If absent, all pixels use brightness 15.",
  "type": "string"
}
```

**Step 2: Extend viz_client send methods**

In `viz_client.py`, update `send_bitmap_frame_fast()` to accept optional brightness:

```python
async def send_bitmap_frame_fast(
    self, zone_name: str, pixels: list[int], brightness: list[int] | None = None
) -> None:
    if not self.ws or not self._connected:
        return
    import base64
    import struct

    raw = struct.pack(f"<{len(pixels)}I", *pixels)
    b64 = base64.b64encode(raw).decode("ascii")
    msg = {
        "type": "bitmap_frame",
        "zone": zone_name,
        "pixels": b64,
    }
    if brightness is not None:
        msg["brightness"] = base64.b64encode(bytes(brightness)).decode("ascii")
    try:
        await self.ws.send(self._encode(msg))
    except Exception:
        pass
```

Similarly update `send_bitmap_frame()`.

**Step 3: Commit**

```bash
git add protocol/schemas/messages/bitmap-frame.schema.json vj_server/viz_client.py
git commit -m "feat(protocol): add optional per-pixel brightness to bitmap_frame"
```

---

### Task 4: Per-Pixel Brightness — MC Plugin Parsing + Application

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRendererBackend.java`
- Modify: `minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java`

**Step 1: Parse brightness array from bitmap_frame message**

In `MessageHandler.handleBitmapFrame()`, after parsing `pixels`, add:

```java
// Parse optional per-pixel brightness
int[] brightnessArray = null;
if (message.has("brightness")) {
    try {
        String b64Brightness = message.get("brightness").getAsString();
        byte[] brightnessBytes = Base64.getDecoder().decode(b64Brightness);
        if (brightnessBytes.length == pixelCount) {
            brightnessArray = new int[pixelCount];
            for (int i = 0; i < pixelCount; i++) {
                brightnessArray[i] = Math.max(0, Math.min(15, brightnessBytes[i] & 0xFF));
            }
        }
    } catch (Exception e) {
        // Ignore malformed brightness, continue with null
    }
}

renderer.applyRawFrame(zoneName, pixels, brightnessArray);
```

**Step 2: Thread brightness through BitmapRendererBackend**

Add overloaded `applyRawFrame`:

```java
public void applyRawFrame(String zoneName, int[] argbPixels, int[] brightness) {
    String zoneKey = zoneName.toLowerCase();
    BitmapGridConfig config = gridConfigs.get(zoneKey);
    if (config == null) return;

    BitmapFrameBuffer temp = new BitmapFrameBuffer(config.width(), config.height());
    int count = Math.min(argbPixels.length, config.width() * config.height());
    System.arraycopy(argbPixels, 0, temp.getRawPixels(), 0, count);
    applyFrame(zoneName, temp, brightness);
}
```

In `applyFrame()`, add brightness parameter and pass it through to `batchUpdateAdaptive`:

```java
public void applyFrame(String zoneName, BitmapFrameBuffer frame, int[] brightness)
```

**Step 3: Add brightness array to batchUpdateAdaptive**

In `EntityPoolManager.batchUpdateAdaptive()`, add an optional `int[] bgBrightness` parallel to `bgArgb`. In the background color update loop:

```java
// Background color updates
for (int i = 0; i < bgCount; i++) {
    Entity entity = pool.get(bgIds[i]);
    if (entity instanceof TextDisplay display) {
        int argb = bgArgb[i];
        display.setBackgroundColor(Color.fromARGB(
            (argb >> 24) & 0xFF, (argb >> 16) & 0xFF,
            (argb >> 8) & 0xFF, argb & 0xFF));
        // Apply per-pixel brightness if provided
        if (bgBrightness != null && i < bgBrightness.length) {
            int b = Math.max(0, Math.min(15, bgBrightness[i]));
            display.setBrightness(BRIGHTNESS_CACHE[b]);
        }
    }
}
```

**Step 4: Verify it compiles**

Run: `cd minecraft_plugin && mvn compile -q`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/protocol/MessageHandler.java \
       minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapRendererBackend.java \
       minecraft_plugin/src/main/java/com/audioviz/entities/EntityPoolManager.java
git commit -m "feat(bitmap): apply per-pixel brightness from bitmap_frame messages"
```

---

### Task 5: Bloom Post-Processing — VJ Server

**Files:**
- Create: `vj_server/bitmap_effects.py`
- Modify: `vj_server/vj_server.py` (hook bloom into frame pipeline)
- Modify: `vj_server/viz_client.py` (if not done in Task 3)

**Step 1: Create bloom post-processor**

```python
"""Bitmap frame post-processing effects (bloom, brightness mapping)."""

from __future__ import annotations


def luminance(argb: int) -> float:
    """Perceived luminance (0-1) from an ARGB int (ITU-R BT.709)."""
    r = (argb >> 16) & 0xFF
    g = (argb >> 8) & 0xFF
    b = argb & 0xFF
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0


def bloom(
    pixels: list[int],
    width: int,
    height: int,
    threshold: float = 0.5,
    radius: int = 1,
    strength: float = 0.4,
) -> list[int]:
    """Apply bloom halo to bright pixels.

    For each pixel above the luminance threshold, bleed a semi-transparent
    version of its color into neighboring dark pixels.

    Args:
        pixels: Flat ARGB int array (length = width * height).
        width: Frame width.
        height: Frame height.
        threshold: Luminance threshold (0-1) for a pixel to emit bloom.
        radius: Bloom spread in pixels (1-3).
        strength: Blend strength (0-1) for the halo.

    Returns:
        New pixel array with bloom applied.
    """
    out = list(pixels)  # copy
    lum_cache = [luminance(p) for p in pixels]

    for y in range(height):
        for x in range(width):
            idx = y * width + x
            if lum_cache[idx] < threshold:
                continue

            src = pixels[idx]
            sr = (src >> 16) & 0xFF
            sg = (src >> 8) & 0xFF
            sb = src & 0xFF

            # Bleed into neighbors
            for dy in range(-radius, radius + 1):
                for dx in range(-radius, radius + 1):
                    if dx == 0 and dy == 0:
                        continue
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < width and 0 <= ny < height:
                        nidx = ny * width + nx
                        if lum_cache[nidx] < threshold:
                            # Distance falloff
                            dist = max(abs(dx), abs(dy))
                            falloff = strength / dist

                            dst = out[nidx]
                            dr = (dst >> 16) & 0xFF
                            dg = (dst >> 8) & 0xFF
                            db = dst & 0xFF

                            # Additive blend
                            nr = min(255, dr + int(sr * falloff))
                            ng = min(255, dg + int(sg * falloff))
                            nb = min(255, db + int(sb * falloff))

                            out[nidx] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb

    return out


def compute_brightness(
    pixels: list[int],
    base_brightness: int = 12,
    beat_boost: int = 3,
    is_beat: bool = False,
) -> list[int]:
    """Compute per-pixel brightness from pixel luminance.

    Maps pixel luminance to brightness 0-15. On beat, adds a boost.

    Args:
        pixels: ARGB pixel array.
        base_brightness: Maximum brightness for full-luminance pixels.
        beat_boost: Extra brightness added on beat (clamped to 15).
        is_beat: Whether a beat was detected this frame.

    Returns:
        Brightness array (0-15 per pixel).
    """
    boost = beat_boost if is_beat else 0
    result = []
    for argb in pixels:
        lum = luminance(argb)
        b = int(lum * base_brightness) + boost
        result.append(max(0, min(15, b)))
    return result
```

**Step 2: Verify syntax**

Run: `python -c "import ast; ast.parse(open('vj_server/bitmap_effects.py', encoding='utf-8').read()); print('OK')"`

**Step 3: Hook bloom + brightness into VJ server bitmap frame sending**

In `vj_server.py`, find where bitmap frames are sent to Minecraft (the `send_bitmap_frame_fast` call). Before sending, apply bloom and compute brightness:

```python
from vj_server.bitmap_effects import bloom, compute_brightness

# After pattern generates pixels, before send:
if self._bloom_enabled:
    pixels = bloom(pixels, width, height)
brightness = compute_brightness(pixels, is_beat=is_beat)
await self.viz_client.send_bitmap_frame_fast(zone_name, pixels, brightness)
```

Add `_bloom_enabled: bool = True` to the server state (or per-zone state).

**Step 4: Commit**

```bash
git add vj_server/bitmap_effects.py vj_server/vj_server.py
git commit -m "feat(bitmap): add bloom post-processing and per-pixel brightness computation"
```

---

### Task 6: Admin Panel Controls

**Files:**
- Modify: `admin_panel/js/admin-app.js`

**Step 1: Add ambient light + bloom toggles to the bitmap section**

In the bitmap controls area of the admin panel, add:
- Toggle for "Ambient Lights" (sends `set_ambient_lights` message with `{enabled: bool}`)
- Toggle for "Bloom Effect" (sends `set_bloom` message with `{enabled: bool}`)
- Slider for "Bloom Strength" (0-100%, sends `set_bloom` with `{strength: float}`)

These wire into the VJ server which tracks the state and applies effects accordingly.

**Step 2: Add WebSocket message handling in VJ server**

In `vj_server.py` browser message handler, add cases for `set_ambient_lights` and `set_bloom`:
- `set_ambient_lights`: Forward to MC plugin as a new message type, or toggle a flag that controls whether ambient lights tick
- `set_bloom`: Set `_bloom_enabled` and `_bloom_strength` on server state

**Step 3: Commit**

```bash
git add admin_panel/js/admin-app.js vj_server/vj_server.py
git commit -m "feat(admin): add ambient light and bloom toggle controls"
```

---

### Task 7: Integration Test — Full Pipeline

**Step 1: Build MC plugin**

Run: `cd minecraft_plugin && mvn package -q`
Expected: BUILD SUCCESS, JAR produced in `target/`

**Step 2: Deploy and test in-game**

1. Copy JAR to MC server plugins/
2. Restart MC server
3. Start VJ server
4. Verify: ambient lights appear around zone on bitmap init
5. Play audio → lights pulse with intensity
6. Open admin panel → bitmap section shows bloom/ambient toggles
7. Toggle bloom off → verify bloom halos disappear
8. Stop audio → lights dim to 0

**Step 3: Final commit**

Any fixes from testing get committed individually.
