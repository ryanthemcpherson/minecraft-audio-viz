# Bitmap Preview 1:1 Parity Design

**Goal:** Make the browser preview tool display the exact same bitmap frames that render in Minecraft, regardless of whether the server runs the Fabric mod or Paper plugin.

## Problem

Bitmap patterns (spectrum bars, plasma, fire, matrix rain, etc.) run inside the Minecraft mod/plugin Java code (`BitmapPatternManager.tick()`). The rendered frames are applied directly to Display Entities but never sent to browser clients. The browser preview runs independent JS pattern approximations that don't match what Minecraft actually renders.

## Approach: Frame Forwarding

Keep patterns running locally on the MC server (zero in-game latency). After each frame renders, forward the pixel data outbound through the existing WebSocket connection to the VJ server, which relays it to browser clients.

```
BitmapPatternManager.tick()
    +-- renderer.applyFrame() --> Display Entities (0ms, unchanged)
    +-- webSocketServer.broadcast(bitmap_frame) --> VJ Server --> Browser clients
```

## Changes

### 1. MC Mod + Plugin: Outbound Frame Broadcasting

After applying each frame in `BitmapPatternManager.tick()`, encode the pixel buffer as base64 little-endian ARGB and broadcast a `bitmap_frame` message. Only send when the frame actually changed (dirty check).

Message format (reuses existing `bitmap_frame` protocol schema):
```json
{
  "type": "bitmap_frame",
  "zone": "main_stage",
  "width": 32,
  "height": 16,
  "pixels": "<base64-encoded little-endian ARGB int array>"
}
```

### 2. VJ Server: Relay to Browser Clients

When receiving a `bitmap_frame` from the Minecraft connection, relay it to all browser clients on port 8766 via `_broadcast_to_browsers()`. No processing needed. Don't echo back to Minecraft (natural — browser broadcast only targets port 8766 clients).

### 3. Browser Preview: Receive and Render Real Frames

Listen for incoming `bitmap_frame` messages. Decode base64 ARGB pixels and render directly to canvas. Fall back to local JS rendering only when no frames are being received (not connected to a server).

## Data Budget

- 32x16 zone = 512 pixels x 4 bytes = 2KB raw, ~2.7KB base64 per frame
- At 20 TPS with 4 zones: ~220KB/s outbound from MC (trivial for local WebSocket)
- Dirty-check skips unchanged frames, so idle zones cost zero bandwidth

## Protocol

Reuses existing `bitmap_frame` schema. Adds optional `width` and `height` fields for browser rendering. Direction is implicit: VJ server knows frames from MC go to browsers, frames from VJ server go to MC.

## Files to Modify

- `minecraft_mod/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java` — Add frame broadcasting after applyFrame()
- `minecraft_plugin/src/main/java/com/audioviz/bitmap/BitmapPatternManager.java` — Same change for Paper plugin
- `vj_server/vj_server.py` — Add bitmap_frame relay handler
- `preview_tool/frontend/js/bitmap-preview.js` — Handle incoming bitmap_frame messages
- `protocol/schemas/messages/bitmap-frame.schema.json` — Add optional width/height fields
