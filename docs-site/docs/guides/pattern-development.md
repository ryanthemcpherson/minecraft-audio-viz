# Pattern Development Guide

Patterns are the heart of MCAV -- they transform audio data into mesmerizing visual experiences. Patterns are written in Lua and live in `patterns/*.lua`. The VJ server auto-discovers them, so no registration is needed.

## Quick Start

Copy this minimal template and save it as `patterns/mypattern.lua`:

```lua
-- Pattern metadata
name = "My Pattern"
description = "A simple bouncing visualization"
category = "Original"
static_camera = false

-- Per-instance state (preserved across frames)
state = {
    time = 0.0,
}

-- Main calculation function (called ~60 times per second)
function calculate(audio, config, dt)
    local entities = {}
    local center = 0.5

    -- Update state
    state.time = state.time + dt

    -- Create entities
    for i = 0, config.entity_count - 1 do
        local band_idx = (i % 5) + 1  -- Cycle through 5 bands (1-indexed)
        local height = audio.bands[band_idx]

        entities[#entities + 1] = {
            id = string.format("block_%d", i),
            x = center,
            y = 0.2 + height * 0.6,
            z = center,
            scale = config.base_scale + height * 0.4,
            rotation = state.time * 45,
            band = band_idx - 1,  -- 0-indexed for output
            visible = true,
        }
    end

    return entities
end
```

**To test your pattern:**

1. Save this as `patterns/mypattern.lua`
2. Start the VJ server (`audioviz-vj`)
3. Select your pattern from the admin panel (http://localhost:8081)
4. The VJ server hot-reloads patterns -- just save the file to see changes instantly.

## Pattern Structure

Every pattern requires these components:

### Required Metadata (top of file)

```lua
name = "Display Name"               -- Shown in admin panel
description = "What it looks like"  -- User-facing description
category = "Original"               -- See Categories section
static_camera = false               -- true = top-down view recommended
```

### Optional Metadata

```lua
start_blocks = 64                   -- Recommended entity count
-- or
recommended_entities = 64           -- Same as start_blocks
```

### State Table (module-level)

```lua
state = {
    rotation = 0.0,
    pulse = 0.0,
    -- Add any variables you need to persist across frames
}
```

State variables persist between calls to `calculate()`. Use them for rotation angles, smoothed values, animation timers, and wave propagation.

### The calculate() Function

Called ~60 times per second:

```lua
function calculate(audio, config, dt)
    -- Your code here
    return entities  -- Table of entity definitions
end
```

**Parameters:**

- `audio` -- Current audio data (see Audio Data section)
- `config` -- Pattern configuration (entity_count, base_scale, etc.)
- `dt` -- Delta time in seconds since last frame (typically ~0.016)

**Return value:** A table (array) of entity definitions.

## Entity Fields

Each entity in the returned table must have these fields:

| Field | Type | Range | Description |
|-------|------|-------|-------------|
| `id` | string | -- | Unique identifier (use consistent IDs for smooth animation) |
| `x` | float | 0-1 | X position (normalized, 0.5 = center) |
| `y` | float | 0-1 | Y position (normalized, 0.5 = center) |
| `z` | float | 0-1 | Z position (normalized, 0.5 = center) |
| `scale` | float | 0.01-1.0 | Block scale |
| `rotation` | float | 0-360 | Rotation in degrees (Y axis) |
| `band` | integer | 0-4 | Frequency band index for coloring |
| `visible` | boolean | -- | Whether to render this entity |

### Band Color Mapping

| Band | Index | Frequency Range | Color |
|------|-------|-----------------|-------|
| Bass | 0 | 40-250 Hz | Red |
| Low-mid | 1 | 250-500 Hz | Orange |
| Mid | 2 | 500-2000 Hz | Yellow |
| High-mid | 3 | 2-6 kHz | Green |
| High | 4 | 6-20 kHz | Blue |

## Audio Data

The `audio` parameter provides:

### audio.bands (table, 5 elements, 0-1 normalized)

```lua
audio.bands[1]  -- Bass (40-250 Hz) - kicks, sub-bass
audio.bands[2]  -- Low-mid (250-500 Hz) - bass guitar, male vocals
audio.bands[3]  -- Mid (500-2000 Hz) - most vocals, guitars
audio.bands[4]  -- High-mid (2-6 kHz) - cymbals, vocal presence
audio.bands[5]  -- High (6-20 kHz) - air, sparkle, hi-hats
```

!!! note
    Lua tables are 1-indexed, but the output `band` field is 0-indexed.

### audio.amplitude (float, 0-1)

Overall audio energy level. Also available as `audio.peak`.

### audio.is_beat (boolean)

Beat detection trigger. Also available as `audio.beat`.

### audio.beat_intensity (float, 0-1)

Strength of the detected beat.

### audio.frame (integer)

Frame counter (increments each frame). Useful for cyclic animations.

## Using lib.lua Utilities

All patterns have access to shared utility functions from `patterns/lib.lua`:

### smooth(current, target, rate, dt)

Delta-time-aware smoothing (exponential moving average):

```lua
state.smooth_bass = smooth(state.smooth_bass, audio.bands[1], 0.3, dt)
-- rate 0.3 = moderate smoothing
-- rate 0.1 = heavy smoothing (slow response)
-- rate 0.8 = light smoothing (fast response)
```

### decay(value, rate, dt)

Delta-time-aware decay (exponential falloff):

```lua
state.pulse = decay(state.pulse, 0.9, dt)
-- rate 0.9 = slow decay
-- rate 0.5 = fast decay
```

### lerp(a, b, t)

Linear interpolation between two values:

```lua
local color_mix = lerp(0.2, 0.8, audio.amplitude)
```

### clamp(value, min_val, max_val)

Constrain a value to a range (defaults: 0-1):

```lua
x = clamp(x)              -- Clamp to 0-1
y = clamp(y, 0.1, 0.9)    -- Clamp to 0.1-0.9
```

### rotate_point_3d(x, y, z, rx, ry, rz)

Rotate a 3D point around X, Y, Z axes:

```lua
local x2, y2, z2 = rotate_point_3d(x, y, z, 0, state.rotation, 0)
```

### fibonacci_sphere(n)

Generate evenly distributed points on a sphere:

```lua
local points = fibonacci_sphere(config.entity_count)
for i, p in ipairs(points) do
    entities[i] = {
        x = 0.5 + p.x * radius,
        y = 0.5 + p.y * radius,
        z = 0.5 + p.z * radius,
        -- ...
    }
end
```

### smoothstep(edge0, edge1, x)

Smooth interpolation with ease-in/ease-out.

## State Management

Use the `state` table to maintain animation continuity across frames:

```lua
state = {
    rotation = 0.0,
    smooth_heights = {},
    pulse = 0.0,
    previous_beat = false,
}

function calculate(audio, config, dt)
    -- Initialize arrays if needed
    if #state.smooth_heights < 5 then
        state.smooth_heights = {0, 0, 0, 0, 0}
    end

    -- Update rotation (dt-aware)
    state.rotation = state.rotation + 1.5 * dt

    -- Smooth all bands
    for i = 1, 5 do
        state.smooth_heights[i] = smooth(
            state.smooth_heights[i], audio.bands[i], 0.3, dt
        )
    end

    -- Edge-triggered beat detection
    if audio.is_beat and not state.previous_beat then
        state.pulse = 1.0
    end
    state.previous_beat = audio.is_beat

    -- Decay pulse
    state.pulse = decay(state.pulse, 0.88, dt)

    -- ... use state in entity calculations
end
```

**Key principles:**

- Initialize state arrays before first use (check length or nil)
- Use `dt` for time-based animations to maintain consistent speed regardless of frame rate
- Smooth audio inputs to avoid jittery motion
- Decay transient effects (pulses, flashes) with `decay()`

## Categories

Choose the category that best fits your pattern:

- **`"Original"`** -- Classic visualizations (bars, circles, simple geometry)
- **`"Epic"`** -- High-energy, explosive patterns (galaxy, vortex)
- **`"Cosmic"`** -- Space-themed (nebula, wormhole, tesseract)
- **`"Organic"`** -- Natural, flowing (ocean, fireflies, mushroom)
- **`"Spectrum"`** -- Frequency-focused (spectrum bars, radial spectrum)

## Performance Tips

Patterns run 60 times per second, so performance matters.

!!! tip "Pre-allocate tables"
    ```lua
    -- Good: Reuse the same table
    local entities = {}
    for i = 1, n do
        entities[#entities + 1] = { ... }
    end
    ```

!!! tip "Cache repeated calculations"
    ```lua
    local center = 0.5
    local num_bands = #audio.bands
    for i = 1, n do
        local x = center + offset
    end
    ```

!!! tip "Use position deadbanding"
    The VJ server automatically filters tiny position changes (<0.0015) to reduce network traffic. Avoid constant micro-movements.

!!! tip "Use modulo for cycling through bands"
    ```lua
    local band_idx = (i % 5) + 1  -- Cycle 1-5 for Lua indexing
    ```

## Testing Your Pattern

### Hot-Reload Workflow

1. Start VJ server: `audioviz-vj`
2. Edit your pattern: `patterns/mypattern.lua`
3. Save the file -- pattern reloads automatically
4. Test with different music genres and intensities

### Testing Checklist

- [ ] Pattern works with 16, 64, 128, and 256 entities
- [ ] No entities go out of bounds (x/y/z outside 0-1)
- [ ] Smooth motion (no jittering or jumps)
- [ ] Responds well to different music genres
- [ ] Beat response is noticeable but not excessive
- [ ] State initializes correctly on first frame
- [ ] No Lua errors in server logs

### Common Issues

**Entities disappear:** Check x/y/z are clamped to 0-1, verify `visible = true`, ensure scale > 0.01.

**Jittery motion:** Use `smooth()` on audio values. Apply position interpolation in state.

**Speed varies with frame rate:** Multiply rotation/movement by `dt`. Use `smooth(current, target, rate, dt)` instead of raw lerp.

**Pattern doesn't show up:** Check Lua syntax (`lua patterns/mypattern.lua`), verify `calculate()` function exists, check server logs for errors.

## Example Patterns to Study

- **`bars.lua`** -- Spectrum analyzer with peak hold
- **`circle.lua`** -- Radial frequency bars
- **`galaxy.lua`** -- Spiral arms with core particles
- **`spectrum.lua`** -- Stacked tower with spiral motion
- **`vortex.lua`** -- Rotating vortex tunnel
- **`fireflies.lua`** -- Organic particle swarm

## Step-by-Step Example: Bouncing Bars

### Step 1: Basic Structure

```lua
name = "Bouncing Bars"
description = "Horizontal bars that bounce with bass"
category = "Original"
static_camera = true
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local center = 0.5

    for i = 0, 4 do
        entities[#entities + 1] = {
            id = string.format("bar_%d", i),
            x = center,
            y = 0.2 + i * 0.15,
            z = center,
            scale = 0.3,
            rotation = 0,
            band = i,
            visible = true,
        }
    end
    return entities
end
```

### Step 2: Add Audio Reactivity

```lua
local height = audio.bands[i + 1]
-- ...
scale = config.base_scale + height * 0.5,
```

### Step 3: Add Smoothing

```lua
state = { smooth_heights = {0, 0, 0, 0, 0} }

-- In calculate():
for i = 1, 5 do
    state.smooth_heights[i] = smooth(
        state.smooth_heights[i], audio.bands[i], 0.35, dt
    )
end
```

### Step 4: Add Beat Response

```lua
state = { smooth_heights = {0, 0, 0, 0, 0}, beat_pulse = 0.0 }

-- In calculate():
if audio.is_beat then
    state.beat_pulse = 1.0
end
state.beat_pulse = decay(state.beat_pulse, 0.85, dt)

-- Apply to entities:
scale = scale + state.beat_pulse * 0.3
y = y + state.beat_pulse * 0.05
rotation = state.beat_pulse * 15
```

### Step 5: Scale to Entity Count

Use `config.entity_count` and distribute entities across bands with modulo:

```lua
for i = 0, config.entity_count - 1 do
    local band_idx = (i % 5) + 1
    -- ...
end
```
