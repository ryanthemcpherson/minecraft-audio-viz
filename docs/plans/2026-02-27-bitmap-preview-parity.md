# Bitmap Preview 1:1 Parity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Forward rendered bitmap frames from Minecraft (mod + plugin) to the VJ server, which relays them to browser preview clients for pixel-perfect 1:1 display.

**Architecture:** MC renders patterns locally (0ms in-game latency). After each frame, the pixel buffer is base64-encoded and broadcast via WebSocket to the VJ server, which relays to browser clients. The browser preview renders received frames directly to canvas instead of running JS approximations.

**Tech Stack:** Java 21 (Fabric mod + Paper plugin), Python (VJ server, msgspec), JavaScript (preview tool, Three.js CanvasTexture)

---

### Task 1: Update Protocol Schema

Add optional `width` and `height` fields to the `bitmap_frame` protocol schema so browser clients know the grid dimensions.

**Files:**
- Modify: `protocol/schemas/messages/bitmap-frame.schema.json`

**Step 1: Add width and height properties**

In `protocol/schemas/messages/bitmap-frame.schema.json`, add inside the `"properties"` object, after the `"brightness"` property:

```json
    "width": {
      "type": "integer",
      "minimum": 1,
      "description": "Grid width in pixels. Required for outbound frames from Minecraft."
    },
    "height": {
      "type": "integer",
      "minimum": 1,
      "description": "Grid height in pixels. Required for outbound frames from Minecraft."
    }
```

Also update the `"description"` at the top to mention bidirectional usage:

```json
"description": "Bitmap pixel frame for a zone. Inbound: VJ server pushes custom frames to Minecraft. Outbound: Minecraft forwards rendered pattern frames for browser preview."
```

**Step 2: Commit**

```bash
git add protocol/schemas/messages/bitmap-frame.schema.json
git commit -m "feat(protocol): add width/height to bitmap_frame schema for preview parity"
```

---

### Task 2: Add Frame Broadcasting to Fabric Mod

After each bitmap frame renders, encode and broadcast it via WebSocket. The mod's `BitmapPatternManager` takes `MinecraftServer` (not `AudioVizMod`), so we add a broadcast callback setter.

**Files:**
- Modify: `minecraft_mod/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java:35-298`
- Modify: `minecraft_mod/src/main/java/com/audioviz/AudioVizMod.java:176` (wire up callback)

**Step 1: Add broadcast callback and encoder to BitmapPatternManager (mod)**

At the top of `BitmapPatternManager.java`, add imports:

```java
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.function.Consumer;
```

Add a field after the existing fields (around line 63):

```java
    /** Optional callback for broadcasting rendered frames to WebSocket clients. */
    private volatile Consumer<JsonObject> frameBroadcaster;
```

Add a setter method after the constructor:

```java
    /**
     * Set the callback for broadcasting rendered bitmap frames.
     * Called by AudioVizMod once the WebSocket server is initialized.
     */
    public void setFrameBroadcaster(Consumer<JsonObject> broadcaster) {
        this.frameBroadcaster = broadcaster;
    }
```

**Step 2: Add broadcast call in tick() after applyFrame**

In the `tick()` method, after line 285 (`renderer.applyFrame(zoneName, ...)`), add:

```java
                    // Broadcast frame to WebSocket clients for browser preview
                    if (frameBroadcaster != null) {
                        broadcastFrame(zoneName, state.buffer);
                    }
```

**Step 3: Add broadcastFrame helper method**

Add after the `tick()` method (before `logRenderDiagnostics()`):

```java
    /**
     * Encode a frame buffer as a bitmap_frame JSON message and broadcast it.
     * Uses base64-encoded little-endian ARGB int array (matches protocol spec).
     */
    private void broadcastFrame(String zoneName, BitmapFrameBuffer buffer) {
        try {
            int[] pixels = buffer.getRawPixels();
            int pixelCount = buffer.getPixelCount();

            ByteBuffer byteBuffer = ByteBuffer.allocate(pixelCount * 4);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < pixelCount; i++) {
                byteBuffer.putInt(pixels[i]);
            }

            String base64 = Base64.getEncoder().encodeToString(byteBuffer.array());

            JsonObject msg = new JsonObject();
            msg.addProperty("type", "bitmap_frame");
            msg.addProperty("zone", zoneName);
            msg.addProperty("width", buffer.getWidth());
            msg.addProperty("height", buffer.getHeight());
            msg.addProperty("pixels", base64);

            frameBroadcaster.accept(msg);
        } catch (Exception e) {
            LOGGER.warn("Failed to broadcast bitmap frame for zone '{}': {}", zoneName, e.getMessage());
        }
    }
```

**Step 4: Wire up broadcaster in AudioVizMod**

In `AudioVizMod.java`, after the WebSocket server is started (after both `bitmapPatternManager` and `wsServer` are initialized), add:

```java
        if (bitmapPatternManager != null && wsServer != null) {
            bitmapPatternManager.setFrameBroadcaster(wsServer::broadcast);
        }
```

Find the location where `wsServer` is assigned and started — add this line after it. Look for the block after `wsServer = new VizWebSocketServer(...)` and `wsServer.start()`.

**Step 5: Verify build**

```bash
cd minecraft_mod && ./gradlew build
```

Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add minecraft_mod/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java minecraft_mod/src/main/java/com/audioviz/AudioVizMod.java
git commit -m "feat(mod): broadcast bitmap frames to WebSocket for preview parity"
```

---

### Task 3: Add Frame Broadcasting to Paper Plugin

Same change for the Paper plugin. The plugin's `BitmapPatternManager` already holds a `plugin` reference with `getWebSocketServer()`.

**Files:**
- Modify: `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java:34-400`

**Step 1: Add imports**

At the top of the plugin's `BitmapPatternManager.java`, add:

```java
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
```

**Step 2: Add broadcast call in tick() after applyFrame**

In the `tick()` method, after line 348 (`renderer.applyFrame(zoneName, state.buffer)`), add:

```java
                    // Broadcast frame to WebSocket clients for browser preview
                    if (plugin.getWebSocketServer() != null) {
                        broadcastFrame(zoneName, state.buffer);
                    }
```

**Step 3: Add broadcastFrame helper method**

Add after the `tick()` method (before `logRenderDiagnostics()`):

```java
    /**
     * Encode a frame buffer as a bitmap_frame JSON message and broadcast it.
     * Uses base64-encoded little-endian ARGB int array (matches protocol spec).
     */
    private void broadcastFrame(String zoneName, BitmapFrameBuffer buffer) {
        try {
            int[] pixels = buffer.getRawPixels();
            int pixelCount = buffer.getPixelCount();

            ByteBuffer byteBuffer = ByteBuffer.allocate(pixelCount * 4);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < pixelCount; i++) {
                byteBuffer.putInt(pixels[i]);
            }

            String base64 = Base64.getEncoder().encodeToString(byteBuffer.array());

            JsonObject msg = new JsonObject();
            msg.addProperty("type", "bitmap_frame");
            msg.addProperty("zone", zoneName);
            msg.addProperty("width", buffer.getWidth());
            msg.addProperty("height", buffer.getHeight());
            msg.addProperty("pixels", base64);

            plugin.getWebSocketServer().broadcast(msg);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to broadcast bitmap frame for zone '" + zoneName + "': " + e.getMessage());
        }
    }
```

**Step 4: Verify build**

```bash
cd minecraft_plugin && mvn package -q
```

Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java
git commit -m "feat(plugin): broadcast bitmap frames to WebSocket for preview parity"
```

---

### Task 4: Add Bitmap Frame Relay to VJ Server

Register a handler on the Minecraft VizClient connection to relay `bitmap_frame` messages to browser clients.

**Files:**
- Modify: `vj_server/vj_server.py` (near line 4734 where `viz_client.on()` handlers are registered)

**Step 1: Find where VizClient handlers are registered**

Look for the line:
```python
self.viz_client.on("stage_zone_configs", self._handle_stage_zone_configs)
```
(around line 4734 in `vj_server.py`)

**Step 2: Add bitmap_frame relay handler**

Right after the existing `self.viz_client.on(...)` call(s), add:

```python
        self.viz_client.on("bitmap_frame", self._relay_bitmap_frame)
```

**Step 3: Add the relay method**

Add a new method to the VJServer class (near other handler methods):

```python
    async def _relay_bitmap_frame(self, data: dict):
        """Relay bitmap_frame from Minecraft to browser clients for 1:1 preview."""
        await self._broadcast_to_browsers(_json_str(data))
```

That's it — `_json_str` serializes the dict to JSON, and `_broadcast_to_browsers` sends to all browser clients on port 8766. The VJ server never echoes back to Minecraft because `_broadcast_to_browsers` only targets the `_broadcast_clients` set (port 8766 connections).

**Step 4: Commit**

```bash
git add vj_server/vj_server.py
git commit -m "feat(vj-server): relay bitmap_frame from Minecraft to browser clients"
```

---

### Task 5: Add Bitmap Frame Rendering to Browser Preview

Handle incoming `bitmap_frame` messages in the preview tool and render the raw pixel data directly to canvas, replacing the local JS pattern approximation when server frames are available.

**Files:**
- Modify: `preview_tool/frontend/js/app.js:683-738` (WebSocket message handler)
- Modify: `preview_tool/frontend/js/bitmap-preview.js:1-278` (add server frame rendering)

**Step 1: Add applyServerFrame method to BitmapPreview**

In `preview_tool/frontend/js/bitmap-preview.js`, add the following method after the `update()` method (after line 103):

```javascript
    /**
     * Apply a server-rendered bitmap frame directly to the canvas.
     * Decodes base64 little-endian ARGB int array to RGBA ImageData.
     * @param {string} zoneName - Target zone
     * @param {string} base64Pixels - Base64-encoded LE ARGB int array
     * @param {number} width - Frame width in pixels
     * @param {number} height - Frame height in pixels
     */
    applyServerFrame(zoneName, base64Pixels, width, height) {
        const s = this.zones[zoneName];
        if (!s || !s.mesh.visible) return;

        // Resize canvas if dimensions changed
        if (s.canvas.width !== width || s.canvas.height !== height) {
            s.canvas.width = width;
            s.canvas.height = height;
            s.width = width;
            s.height = height;
        }

        // Decode base64 to byte array
        const binaryStr = atob(base64Pixels);
        const bytes = new Uint8Array(binaryStr.length);
        for (let i = 0; i < binaryStr.length; i++) {
            bytes[i] = binaryStr.charCodeAt(i);
        }

        // Convert LE ARGB int array to RGBA ImageData
        const pixelCount = width * height;
        const img = s.ctx.createImageData(width, height);
        const d = img.data;
        const view = new DataView(bytes.buffer);

        for (let i = 0; i < pixelCount; i++) {
            const argb = view.getInt32(i * 4, true); // little-endian
            const a = (argb >>> 24) & 0xFF;
            const r = (argb >>> 16) & 0xFF;
            const g = (argb >>> 8) & 0xFF;
            const b = argb & 0xFF;
            const j = i << 2;
            d[j] = r;
            d[j + 1] = g;
            d[j + 2] = b;
            d[j + 3] = a || 255; // Default to opaque if alpha is 0
        }

        s.ctx.putImageData(img, 0, 0);

        // Apply effects (brightness, blackout, wash) on top
        this._applyEffects(s);

        s.texture.needsUpdate = true;

        // Mark as receiving server frames (skip local rendering for this zone)
        s._serverFrameTime = performance.now();
    }
```

**Step 2: Skip local rendering when server frames are active**

In the `update()` method (line 93-103), modify the loop body to skip local rendering when recent server frames exist. Replace:

```javascript
    update(dt, audioState) {
        if (this.effects.frozen) return;
        this.time += dt;

        for (const s of Object.values(this.zones)) {
            if (!s.mesh.visible) continue;
            this._renderPattern(s, audioState);
            this._applyEffects(s);
            s.texture.needsUpdate = true;
        }
    }
```

With:

```javascript
    update(dt, audioState) {
        if (this.effects.frozen) return;
        this.time += dt;

        const now = performance.now();
        for (const s of Object.values(this.zones)) {
            if (!s.mesh.visible) continue;
            // Skip local rendering if server frames arrived within last 2 seconds
            if (s._serverFrameTime && (now - s._serverFrameTime) < 2000) continue;
            this._renderPattern(s, audioState);
            this._applyEffects(s);
            s.texture.needsUpdate = true;
        }
    }
```

**Step 3: Handle bitmap_frame in WebSocket message handler**

In `preview_tool/frontend/js/app.js`, find the `ws.onmessage` handler (around line 683). In the chain of `else if` blocks, add a handler for `bitmap_frame`. Add it after the `bitmap_pattern_set` / `bitmap_transition_started` handler (around line 720):

```javascript
                } else if (data.type === 'bitmap_frame') {
                    if (bitmapPreview && data.zone && data.pixels) {
                        bitmapPreview.applyServerFrame(
                            data.zone, data.pixels,
                            data.width || 16, data.height || 12
                        );
                    }
```

**Step 4: Commit**

```bash
git add preview_tool/frontend/js/bitmap-preview.js preview_tool/frontend/js/app.js
git commit -m "feat(preview): render server bitmap frames for 1:1 preview parity"
```
