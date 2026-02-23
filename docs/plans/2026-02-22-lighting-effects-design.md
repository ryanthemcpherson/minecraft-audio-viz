# Lighting Effects Design

**Date:** 2026-02-22
**Status:** Approved

## Overview

Add three lighting systems to enhance visual impact of audio visualizations in vanilla Minecraft (no shader packs required):

1. Ambient light blocks — real world illumination around zones
2. Bitmap per-pixel brightness — dynamic brightness modulation on TextDisplay pixels
3. Bitmap glow halos — bloom-like post-processing on bitmap frames

## 1. Ambient Light Blocks

**Component:** New `AmbientLightManager` class in MC plugin.

**Behavior:**
- On zone init, calculate grid of `Material.LIGHT` block positions around zone perimeter (every 2-3 blocks, 1 block out from zone bounds)
- Every 2-4 ticks, update light levels based on current audio peak/bass intensity
- Map audio intensity (0.0-1.0) → light level (0-15)
- On beat detection, burst to 1-tick update for responsive flashes
- On zone cleanup, restore all light blocks to AIR

**Configuration:** Enable/disable, update rate (ticks), intensity multiplier, max light block count (~20-40 per zone).

**Cost:** ~20-40 block updates every 2-4 ticks per zone. Within Starlight engine budget.

**Protocol:** VJ server sends audio intensity in existing frame messages. MC plugin maps intensity to light levels locally — no new WebSocket messages needed.

## 2. Bitmap Per-Pixel Brightness

**Change:** Add optional `brightness` array to bitmap frame protocol.

**Protocol extension:**
- `bitmap_frame` message gains optional `brightness` field: array of integers (0-15), one per pixel
- If absent, default to 15 (backward compatible)

**MC plugin:**
- `batchUpdateTextBackgrounds` applies `Display.Brightness` per-pixel from brightness array using existing `BRIGHTNESS_CACHE[16]`
- One extra `setBrightness()` call per pixel (entity already touched for color)

**VJ server:**
- Bitmap patterns can output brightness per pixel
- Common use: brightness = pixel luminance, dim edges on beats, pulse overall brightness with bass

**Cost:** Negligible — cached brightness objects, entity already being updated.

## 3. Bitmap Glow Halos (Bloom Post-Processing)

**Location:** VJ server-side post-processing pass after pattern generates frame.

**Algorithm:**
1. Identify bright pixels (luminance above configurable threshold)
2. For neighboring dim/dark pixels within radius, blend semi-transparent version of bright pixel's color
3. Alpha range 0x30-0x80 for translucent halos on TextDisplay backgrounds

**Configuration:** Bloom radius (1-3 pixels), luminance threshold, blend strength, enable/disable toggle.

**Integration:** Implemented as a bitmap effect in existing effects system (alongside marquee, firework, etc.) or as a per-pattern toggle.

**Cost:** Convolution pass over pixel buffer. 512 pixels (32x16) = trivial. 3072 pixels (64x48) = still cheap.

## Implementation Order

1. Ambient light blocks (MC plugin only, no protocol changes)
2. Bitmap per-pixel brightness (protocol + MC plugin + VJ server patterns)
3. Bitmap glow halos (VJ server only)

Each is independently useful and can ship incrementally.
