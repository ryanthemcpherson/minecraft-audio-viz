# Adaptive Bitmap Renderer

**Date**: 2026-02-22
**Status**: Design
**Scope**: `minecraft_plugin/` — `BitmapRendererBackend` and supporting classes

## Problem

The current bitmap renderer uses 1 TextDisplay entity per pixel. With a default budget of 500 entities per zone, screens are limited to ~500 pixels (e.g., 32x16). DJ profile pictures need 64x64 = 4096 pixels — 8x over budget.

## Solution

A unified adaptive renderer that combines two techniques to maximize pixel count within a fixed entity budget:

1. **Half-block characters** (`▄` U+2584) — each entity encodes 2 vertically stacked pixels (background color = top, text color = bottom), doubling vertical resolution for free.
2. **Greedy rectangle merging** — adjacent cells with identical colors are merged into single scaled entities, dramatically reducing entity count for images with uniform regions.

Together: a 64x64 DJ profile pic on a solid background uses ~100-300 entities instead of 4096.

## Architecture

### Data Flow

```
BitmapFrameBuffer (WxH logical pixels, e.g. 64x64)
         |
         v
    Cell Grid (Wx(H/2) cells, each = topARGB + bottomARGB)
         |
         v
    Greedy Rectangle Merge -> List<MergedRect>
         |
         v
    Entity Assignment (pre-allocated pool)
         |
         v
    Dual Dirty Diff vs last frame -> minimal Bukkit API calls
         |
         v
    setBackgroundColor / text(Component) / setTransformation
```

### Key Principle

Everything above the renderer is unchanged. Patterns write to `BitmapFrameBuffer` at logical pixel resolution. The renderer handles the pixel-to-entity mapping. No pattern, transition, effect, or protocol code needs modification.

## Components

### HalfBlockCell

```java
record HalfBlockCell(int topARGB, int bottomARGB) {}
```

- Cell `(x, y)` maps to logical pixels `(x, y*2)` and `(x, y*2+1)`
- Two cells are **equal** when both `topARGB` and `bottomARGB` match
- When `topARGB == bottomARGB`, the entity uses `" "` + background only (skips text Component — cheaper)

### CellGridMerger

Greedy rectangle merge algorithm. Stateless, reusable across frames.

**Algorithm** — row-first scan:

1. For each row, merge adjacent identical cells into horizontal runs
2. For each run, extend downward while cells match across the full run width
3. Mark consumed cells, output `MergedRect`
4. Repeat until all cells consumed

```java
record MergedRect(int x, int y, int w, int h, int topARGB, int bottomARGB) {}
```

Output is deterministically ordered (top-to-bottom, left-to-right) for stable entity assignment across frames.

**Complexity**: O(cells x passes). For a 64x32 cell grid (~2048 cells) with typical images producing 100-500 rectangles, this is sub-millisecond.

**Future upgrade path**: Can be replaced with histogram-based "maximal rectangle" (stack-based, O(n) per row) if the row-first variant proves insufficient. The `CellGridMerger` interface stays the same.

### AdaptiveEntityAssigner

Maps merged rectangles to entity pool slots and tracks dirty state.

Per entity slot, caches:
```java
int lastTopARGB;
int lastBottomARGB;
float lastX, lastY, lastScaleX, lastScaleY;
boolean active;
```

Each frame, compares new assignment against cache. Three independent dirty flags:
- **geometryDirty**: position or scale changed -> `setTransformation()`
- **bgDirty**: top pixel changed -> `setBackgroundColor()`
- **textDirty**: bottom pixel changed -> `text(Component)`

For static content (profile pic), after the first frame: zero API calls. For dynamic audio patterns, only the subset of entities whose regions changed get updated.

### Entity Pool Changes

**Spawning**: Pool entities are spawned with `▄` as default text, `Billboard.FIXED`, interpolation duration 2-3 ticks. Initial scale is 0 (invisible until assigned).

**Hiding unused entities**: Set `backgroundColor` to `ARGB(0,0,0,0)` and `text(Component.empty())`. Avoids teleport entity-tracking overhead.

**Pool sizing**: Pre-allocate `maxEntities` (default 2048, configurable via `bitmap.max_entities_per_zone`). If merged rects exceed pool size, lower-right rects are dropped (visible as black). A warning is logged.

### Transformation Math

Current approach uses TheCymaera's scaling trick for space characters:
- Space background ~ 1/8 block wide, 1/4 block tall
- Scale factors: `8x` horizontal, `4x` vertical to fill pixel area

For `▄`, the character cell dimensions are the same as a space. The glyph's top half is transparent (shows background), bottom half is opaque (shows text color). A 1x1 cell entity uses the same base scale. A WxH merged rectangle scales to:

```
scaleX = W * pixelScale * 8.0
scaleY = H * pixelScale * 4.0
```

Position: offset to center the rectangle within its world-space region, accounting for zone rotation and the X-flip for audience-facing orientation.

### EntityPoolManager Addition

New method for the adaptive hot path:

```java
void batchUpdateAdaptive(
    String zoneName,
    // Geometry updates
    String[] geoIds, Transformation[] geoTransforms, int geoCount,
    // Background-only updates
    String[] bgIds, int[] bgArgb, int bgCount,
    // Text-only updates
    String[] txtIds, Component[] txtComponents, int txtCount
)
```

Applies all three update types in a single scheduler task on the main thread.

## Dimension Changes

| Property | Old | New |
|-|-|-|
| Max logical pixels | 128x64 (8192) | 128x128 (16384) |
| BitmapFrameBuffer max | 10,000 | 16,384 |
| Default entity budget | 500 | 2048 |
| Effective pixel count (64x64 img, solid bg) | 4096 entities needed | ~150-300 entities |
| Effective pixel count (dynamic pattern) | 1:1 | ~1:1 (half-block still gives 2x vertical) |

## Files Changed

| File | Scope | Changes |
|-|-|-|
| `BitmapRendererBackend.java` | Major rewrite | Cell grid construction, rectangle merge invocation, entity pool assignment, dual dirty tracking, half-block text Component generation |
| `EntityPoolManager.java` | Minor addition | `batchUpdateAdaptive()` method |
| `BitmapFrameBuffer.java` | One-line | Raise max pixel check from 10,000 to 16,384 |
| All other files | None | Patterns, transitions, effects, VJ server, protocol unchanged |

## New Classes

| Class | Location | Purpose |
|-|-|-|
| `HalfBlockCell` | `bitmap/adaptive/` | Record: `(topARGB, bottomARGB)` |
| `MergedRect` | `bitmap/adaptive/` | Record: `(x, y, w, h, topARGB, bottomARGB)` |
| `CellGridMerger` | `bitmap/adaptive/` | Greedy rectangle merge, stateless |
| `AdaptiveEntityAssigner` | `bitmap/adaptive/` | Pool slot assignment + dirty tracking |

## Example: DJ Profile Pic

64x64 pixel avatar on black background, rendered in a zone with 2048 entity budget:

1. Frame buffer: 64x64 logical pixels
2. Cell grid: 64x32 cells
3. Rectangle merge:
   - Black background regions merge into ~4-8 large rectangles
   - Face detail area: ~200-400 small rectangles
   - Total: ~250 merged rects
4. Entity assignment: 250 out of 2048 pool slots used
5. Remaining 1798 entities: hidden (alpha 0)
6. Subsequent frames (static image): 0 updates (all cached)

## Example: Audio Spectrum Bars

64x32 pixel spectrum display:

1. Frame buffer: 64x32 logical pixels
2. Cell grid: 64x16 cells
3. Rectangle merge:
   - Each bar column is a vertical gradient (few merges within bars)
   - Black background between/above bars merges well
   - Total: ~300-500 merged rects
4. Per-frame: ~100-200 color updates (bar heights change), ~50 geometry updates (merge boundaries shift)

## Edge Cases

- **Odd logical height**: Last row gets solo cells with `bottomARGB = 0` (transparent). Entity uses space + background only.
- **Pool exhaustion**: Log warning, drop lowest-priority rects (bottom-right). Visual degradation is graceful — the screen just gets cropped.
- **Fully chaotic content** (every pixel unique): Degrades to 1 entity per cell = half the entity count of old approach (half-block still helps). Worst case for 64x64 is 2048 entities.
- **Fully uniform content** (solid color): 1 entity for the entire screen.

## Performance Budget

At 20 TPS, per tick:
- Cell grid construction: ~0.05ms (array copy + pair)
- Rectangle merge: ~0.1-0.5ms (depends on output count)
- Dirty diff: ~0.05ms (int comparisons)
- Bukkit API calls: ~0.1-1.0ms (depends on dirty count)
- **Total**: ~0.3-1.5ms per zone per tick (well within 50ms tick budget)

## Not In Scope

- Multi-resolution zones (different logical resolution per screen region) — future enhancement
- Custom font resource packs — separate investigation
- VJ server or protocol changes — this is renderer-only
- Browser preview changes — preview uses Canvas2D, unaffected
