# Psychedelic Bitmap Pattern Pack — Design

**Date:** 2026-03-05
**Status:** Approved

## Goal

Add 6 new psychedelic/rock-inspired bitmap patterns and 1 new post-process effect to the bitmap display system, targeting Sphere-in-Vegas-level procedural visuals. Push resolution support toward 1000-2000 pixels per zone.

## Existing Infrastructure

The bitmap system already provides:
- `BitmapPattern` base class with `render(buffer, audio, time)` contract
- `BitmapFrameBuffer` with ARGB pixel array, drawing primitives, HSB conversion
- `BitmapRendererBackend` with dirty-checking, TextDisplay entity grid (up to 128x128)
- `CompositionManager` with multi-zone coordination, layer blending, transitions
- `EffectsProcessor` with strobe, freeze, RGB split, bit crush, edge flash, brightness
- `LayerCompositor` with 7 blend modes (additive, multiply, screen, overlay, etc.)
- 33 existing patterns including Plasma, Kaleidoscope, FractalZoom, Moiré, DataMosh, PixelSort

### What already exists (no new work)
- Chromatic aberration → EffectsProcessor RGB Split
- Pixel sorting → BitmapPixelSort
- Data moshing → BitmapDataMosh
- Bit crush → EffectsProcessor
- Kaleidoscope, Fractal, Plasma, Moiré → existing patterns

## New Patterns (6)

### 1. BitmapMetaballs — Lava Lamp / Mercury Blobs

**Algorithm:** Implicit surface / distance-field thresholding
- 6-10 blobs with position, velocity, radius
- Per-pixel: sum `1/dist²` from all blobs → threshold for organic merge boundary
- Color from distance-field gradient (iridescent oil-on-water mapping)

**Audio mapping:**
- Each blob's radius pulses with a mapped frequency band
- Bass controls blob drift speed
- Highs add fine shimmer to color
- Beats spawn temporary extra blobs that fade out over ~1s

**Perf notes:** O(pixels * blobs). At 2000px with 10 blobs = 20k distance calcs/frame — trivial.

### 2. BitmapReactionDiffusion — Gray-Scott Cellular Automata

**Algorithm:** Gray-Scott model (two-chemical reaction-diffusion)
- Two concentration grids (A, B) with feed rate F and kill rate k
- Each tick: diffuse both chemicals, apply reaction `A*B²`, feed/drain
- Classic patterns emerge: spots, stripes, mazes, coral

**Audio mapping:**
- Bass increases feed rate F → more growth/turbulence
- Mids shift kill rate k → morph between pattern types (spots ↔ stripes ↔ maze)
- Beats inject chemical B at random points → chain reactions
- Color: map concentration ratio to palette (dark → coral → white)

**Perf notes:** Runs at half internal resolution, nearest-neighbor upscale to output buffer. Laplacian uses 5-point stencil.

### 3. BitmapFluid — Simplified Navier-Stokes Smoke

**Algorithm:** Jos Stam "Stable Fluids" simplified solver
- Velocity field (vx, vy) + density/dye field
- Steps: add forces → diffuse → advect → project (pressure solve)
- Pressure solve uses ~10 Gauss-Seidel iterations (not full convergence — visuals only)

**Audio mapping:**
- Bass injects large central velocity burst
- Mids add swirling rotational forces at offset points
- Highs add fine random turbulence across the field
- Beats inject big radial dye bursts (colored by dominant band)
- Amplitude controls dye brightness

**Perf notes:** Most expensive pattern. Runs at reduced internal resolution (e.g., 1/3 of output). Bilinear interpolation on advection step. Three float arrays (vx, vy, density).

### 4. BitmapVoronoiShatter — Stained Glass That Breathes

**Algorithm:** Voronoi diagram via brute-force nearest-seed
- 20-40 seed points with slow random drift
- Per-pixel: find closest and second-closest seed → cell assignment + edge detection
- Cell borders = pixels where dist(closest) ≈ dist(second-closest)

**Audio mapping:**
- Each seed's color mapped from its assigned frequency band
- Cell brightness pulses with band intensity
- Beats cause "shatter": seeds temporarily split into 2-3, then recombine over ~1s
- Amplitude modulates border glow intensity
- Bass controls seed drift speed

**Perf notes:** O(pixels * seeds). At 2000px with 30 seeds = 60k distance calcs. Precompute seed positions once per frame, not per pixel.

### 5. BitmapSacredSpiral — Fibonacci Spiral Hallucination

**Algorithm:** Polar-coordinate golden spiral rendering
- 5 interlocking golden spiral arms, each mapped to a frequency band
- Spiral equation: r = a * e^(b*theta), rendered in polar coords from center
- Background: concentric ring pattern that breathes with bass

**Audio mapping:**
- Each arm's width/brightness pulses with its frequency band
- Rotation speed tracks BPM (or continuous time with audio modulation)
- Bass controls concentric ring expansion rate
- Beats cause spiral arms to flash and momentarily expand
- Hue cycles slowly, shifted by mid-frequency energy

**Perf notes:** Per-pixel polar coordinate conversion. Precompute atan2/dist lookup tables on resize (same pattern as BitmapKaleidoscope).

### 6. BitmapFeedbackLoop — Infinite Tunnel / Mirror Hall

**Algorithm:** Frame feedback with geometric transformation
- Maintains a dedicated previous-frame buffer
- Each frame: read previous frame → zoom toward center + slight rotation + hue shift → write to current
- Overlay new audio-reactive content on top (pulsing shapes, dots at beat positions)

**Audio mapping:**
- Bass controls zoom speed (faster = deeper tunnel feel)
- Mids control rotation amount per frame
- Highs control hue shift rate
- Beats momentarily increase zoom factor ("punch in" effect)
- Amplitude controls opacity of new content overlay

**Perf notes:** One extra frame buffer allocation. Per-pixel bilinear sample from previous frame with coordinate transform. Same cost as fluid advection.

## New Effect: Trail/Persistence (EffectsProcessor)

**What:** Post-process effect that adds motion trails to ANY existing pattern.
- Instead of full clear between frames, blend previous output at configurable decay
- `setTrailEnabled(boolean)` / `setTrailDecay(double)` (0.0 = no trail, 0.95 = long trails)
- Inserted into EffectsProcessor chain before strobe (step 0)

**Why as an effect, not a pattern:** This multiplies the value of all 33+ existing patterns. Plasma + trails = flowing aurora. Starfield + trails = hyperspace. Spectrum bars + trails = waterfall spectrogram.

**Implementation:** Store previous frame snapshot in EffectsProcessor. On each process() call, if trail enabled, lerp current buffer toward previous snapshot by decay factor, then snapshot current for next frame.

## Performance Strategy for 1000-2000 Pixels

1. **Half-res simulation** for ReactionDiffusion and Fluid (simulate at N/2 × N/2, upscale output)
2. **Direct array access** via `getRawPixels()` in all hot loops — no `setPixel()` bounds checking
3. **Precomputed lookup tables** for polar coords (SacredSpiral), distance tables (Metaballs cache reused), angle tables
4. **Dirty-checking** already handled by BitmapRendererBackend — only changed pixels pushed to entities
5. **No framework changes needed** — existing 16384 pixel cap in BitmapFrameBuffer supports up to 128x128

## Registration

All 6 patterns registered in the existing bitmap pattern registry alongside the current 33. No changes to CompositionManager, transitions, or blend system — patterns are self-contained.

## File List

New files (all in `minecraft_plugin/src/main/java/com/audioviz/bitmap/patterns/`):
- `BitmapMetaballs.java`
- `BitmapReactionDiffusion.java`
- `BitmapFluid.java`
- `BitmapVoronoiShatter.java`
- `BitmapSacredSpiral.java`
- `BitmapFeedbackLoop.java`

Modified files:
- `EffectsProcessor.java` — add trail/persistence effect
- Pattern registry (wherever patterns are registered) — add 6 new entries
