# Pattern Development Guide

This guide teaches you how to create custom Lua visualization patterns for the Minecraft Audio Visualizer. Patterns are the heart of MCAV - they transform audio data into mesmerizing visual experiences.

## Quick Start

The fastest way to get started is to copy this minimal template:

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
4. **That's it!** The VJ server hot-reloads patterns - just save the file to see changes instantly.

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

State variables persist between calls to `calculate()`. Use them for:
- Rotation angles
- Smoothed values
- Animation timers
- Wave propagation

### The calculate() Function

This is where the magic happens. Called ~60 times per second:

```lua
function calculate(audio, config, dt)
    -- Your code here
    return entities  -- Table of entity definitions
end
```

**Parameters:**
- `audio` - Current audio data (see Audio Data section)
- `config` - Pattern configuration (entity_count, base_scale, etc.)
- `dt` - Delta time in seconds since last frame (typically ~0.016)

**Return value:**
A table (array) of entity definitions. Each entity is a table with these fields:

```lua
{
    id = "block_0",           -- Unique identifier (string)
    x = 0.5,                  -- X position (0-1, normalized)
    y = 0.5,                  -- Y position (0-1, normalized)
    z = 0.5,                  -- Z position (0-1, normalized)
    scale = 0.3,              -- Block scale (0.01-1.0)
    rotation = 45,            -- Rotation in degrees (0-360)
    band = 0,                 -- Frequency band index (0-4)
    visible = true,           -- Whether to render this entity
}
```

## Entity Fields Explained

### id (string, required)
Unique identifier for this entity. Use consistent IDs across frames for smooth animation:
```lua
id = string.format("block_%d", i)
```

### x, y, z (float, 0-1 normalized)
Position in normalized space. The VJ server converts these to world coordinates:
- `0.5, 0.5, 0.5` = center of the visualization zone
- `0, 0, 0` = corner (bottom-left-back)
- `1, 1, 1` = opposite corner (top-right-front)

**Example:**
```lua
x = 0.5 + math.cos(angle) * radius  -- Circular motion around center
y = 0.2 + audio.bands[1] * 0.6      -- Vertical movement (bass-reactive)
z = clamp(0.5 + offset)             -- Safe Z position with bounds checking
```

### scale (float)
Block size. Typical range: `0.1` to `0.8`. Use `config.base_scale` as a starting point:
```lua
scale = config.base_scale + audio.bands[1] * 0.4
scale = math.min(config.max_scale, scale)  -- Respect max scale
```

### rotation (float, degrees)
Rotation angle in degrees (0-360). Rotates around the Y axis:
```lua
rotation = (state.time * 90) % 360  -- Constant spin
rotation = audio.bands[3] * 360     -- Audio-reactive spin
```

### band (integer, 0-4)
Which frequency band colors this entity. Maps to:
- `0` = Bass (40-250 Hz) - Red
- `1` = Low-mid (250-500 Hz) - Orange
- `2` = Mid (500-2000 Hz) - Yellow
- `3` = High-mid (2-6 kHz) - Green
- `4` = High (6-20 kHz) - Blue

### visible (boolean)
Whether to render this entity. Use for show/hide effects:
```lua
visible = audio.bands[band_idx] > 0.1  -- Only show if band is active
visible = block_height <= max_height   -- Spectrum bar masking
```

## Audio Data

The `audio` parameter provides rich audio information:

### audio.bands (table, 5 elements, 0-1 normalized)
Five frequency bands, each ranging 0-1:
```lua
audio.bands[1]  -- Bass (40-250 Hz) - kicks, sub-bass
audio.bands[2]  -- Low-mid (250-500 Hz) - bass guitar, male vocals
audio.bands[3]  -- Mid (500-2000 Hz) - most vocals, guitars
audio.bands[4]  -- High-mid (2-6 kHz) - cymbals, vocals presence
audio.bands[5]  -- High (6-20 kHz) - air, sparkle, hi-hats
```

**Note:** Lua tables are 1-indexed, but output `band` field is 0-indexed.

### audio.amplitude (float, 0-1)
Overall audio energy level. Also available as `audio.peak`:
```lua
state.rotation = state.rotation + (0.3 + audio.amplitude * 2.0) * dt
```

### audio.is_beat (boolean)
Beat detection trigger. Also available as `audio.beat`:
```lua
if audio.is_beat then
    state.pulse = 1.0  -- Trigger pulse animation
    scale = scale * 1.5  -- Scale boost on beat
end
```

### audio.beat_intensity (float, 0-1)
Strength of the detected beat:
```lua
scale = scale + audio.beat_intensity * 0.3
```

### audio.frame (integer)
Frame counter (increments each frame). Useful for cyclic animations:
```lua
local cycle = (audio.frame % 60) / 60  -- 0-1 cycle over 60 frames
```

## Using lib.lua Utilities

All patterns have access to shared utility functions from `patterns/lib.lua`. Here are the most useful:

### smooth(current, target, rate, dt)
Delta-time-aware smoothing (exponential moving average):
```lua
state.smooth_bass = smooth(state.smooth_bass, audio.bands[1], 0.3, dt)
-- rate 0.3 = moderate smoothing
-- rate 0.1 = heavy smoothing (slow response)
-- rate 0.8 = light smoothing (fast response)
```

**Use when:** You want gentle, organic transitions instead of jittery values.

### decay(value, rate, dt)
Delta-time-aware decay (exponential falloff):
```lua
state.pulse = decay(state.pulse, 0.9, dt)
-- rate 0.9 = slow decay
-- rate 0.5 = fast decay
```

**Use when:** You want a value to naturally fall back to zero (e.g., beat pulses).

### lerp(a, b, t)
Linear interpolation between two values:
```lua
local color_mix = lerp(0.2, 0.8, audio.amplitude)  -- 0.2 when silent, 0.8 when loud
```

### clamp(value, min_val, max_val)
Constrain a value to a range (defaults: 0-1):
```lua
x = clamp(x)              -- Clamp to 0-1
y = clamp(y, 0.1, 0.9)    -- Clamp to 0.1-0.9
```

**Use when:** Ensuring positions stay within bounds.

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
Smooth interpolation with ease-in/ease-out:
```lua
local fade = smoothstep(0.2, 0.8, audio.amplitude)
```

## State Management

Use the `state` table to maintain animation continuity across frames:

```lua
state = {
    rotation = 0.0,           -- Rotation angle
    smooth_heights = {},      -- Array of smoothed values
    pulse = 0.0,              -- Beat pulse intensity
    previous_beat = false,    -- Beat edge detection
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
        state.smooth_heights[i] = smooth(state.smooth_heights[i], audio.bands[i], 0.3, dt)
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
- **Initialize state arrays** before first use (check length or nil)
- **Use `dt` for time-based animations** to maintain consistent speed regardless of frame rate
- **Smooth audio inputs** to avoid jittery motion
- **Decay transient effects** (pulses, flashes) with `decay()`

## Performance Tips

Patterns run 60 times per second, so performance matters:

### ✅ DO: Pre-allocate tables
```lua
-- GOOD: Reuse the same table
local entities = {}
for i = 1, n do
    entities[#entities + 1] = { ... }  -- Append to existing table
end
```

### ❌ DON'T: Create objects per entity per frame
```lua
-- BAD: Creates new table every frame for every entity
for i = 1, n do
    local temp = {}
    temp.x = ...
    entities[i] = temp
end
```

### ✅ DO: Use position deadbanding
The VJ server automatically filters tiny position changes (<0.0015) to reduce network traffic. Design your patterns with this in mind - avoid constant micro-movements.

### ✅ DO: Cache repeated calculations
```lua
-- GOOD: Calculate once
local center = 0.5
local num_bands = #audio.bands

for i = 1, n do
    local x = center + offset  -- Reuse center
end
```

### ❌ DON'T: Repeat expensive operations
```lua
-- BAD: Calculate center every iteration
for i = 1, n do
    local x = 0.5 + offset
end
```

### ✅ DO: Use math.min/max for clamping simple cases
```lua
scale = math.min(config.max_scale, scale)  -- Fast
```

### ✅ DO: Modulo for cycling through bands
```lua
local band_idx = (i % 5) + 1  -- Cycle 1-5 for Lua indexing
```

## Categories

Choose the category that best fits your pattern's vibe:

- **`"Original"`** - Classic visualizations (bars, circles, simple geometry)
- **`"Epic"`** - High-energy, explosive patterns (galaxy, vortex)
- **`"Cosmic"`** - Space-themed (nebula, wormhole, tesseract)
- **`"Organic"`** - Natural, flowing (ocean, fireflies, mushroom)
- **`"Spectrum"`** - Frequency-focused (spectrum bars, radial spectrum)

Categories help users find the right pattern for their music.

## Testing Your Pattern

### 1. Hot-Reload Workflow
1. Start VJ server: `audioviz-vj`
2. Edit your pattern: `patterns/mypattern.lua`
3. **Save the file** - pattern reloads automatically!
4. Test with different music genres and intensities

### 2. Using the Admin Panel
- Open http://localhost:8081
- Use the pattern dropdown to select your pattern
- Adjust entity count to see how it scales
- Test with different audio presets (EDM, Chill, Rock, etc.)

### 3. Testing Checklist
- [ ] Pattern works with 16, 64, 128, and 256 entities
- [ ] No entities go out of bounds (x/y/z outside 0-1)
- [ ] Smooth motion (no jittering or jumps)
- [ ] Responds well to different music genres
- [ ] Beat response is noticeable but not excessive
- [ ] State initializes correctly on first frame
- [ ] No Lua errors in server logs

### 4. Common Issues

**Entities disappear:**
- Check x/y/z are clamped to 0-1
- Verify `visible = true`
- Ensure scale > 0.01

**Jittery motion:**
- Use `smooth()` on audio values
- Apply position interpolation in state

**Speed varies with frame rate:**
- Multiply rotation/movement by `dt`
- Use `smooth(current, target, rate, dt)` instead of raw lerp

**Pattern doesn't show up:**
- Check Lua syntax (run `lua patterns/mypattern.lua`)
- Verify `calculate()` function exists
- Check server logs for errors

## Example: Building a Bouncing Bars Pattern

Let's walk through building a simple but polished pattern step by step.

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

    -- Create 5 horizontal bars
    for i = 0, 4 do
        entities[#entities + 1] = {
            id = string.format("bar_%d", i),
            x = center,
            y = 0.2 + i * 0.15,  -- Stack vertically
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

### Step 2: Make it Audio-Reactive
```lua
function calculate(audio, config, dt)
    local entities = {}
    local center = 0.5

    for i = 0, 4 do
        local band_idx = i + 1  -- 1-indexed for Lua
        local height = audio.bands[band_idx]

        entities[#entities + 1] = {
            id = string.format("bar_%d", i),
            x = center,
            y = 0.2 + i * 0.15,
            z = center,
            scale = config.base_scale + height * 0.5,  -- Grow with audio
            rotation = 0,
            band = i,
            visible = true,
        }
    end

    return entities
end
```

### Step 3: Add Smoothing
```lua
state = {
    smooth_heights = {0, 0, 0, 0, 0},
}

function calculate(audio, config, dt)
    local entities = {}
    local center = 0.5

    -- Smooth all bands
    for i = 1, 5 do
        state.smooth_heights[i] = smooth(state.smooth_heights[i], audio.bands[i], 0.35, dt)
    end

    for i = 0, 4 do
        local height = state.smooth_heights[i + 1]

        entities[#entities + 1] = {
            id = string.format("bar_%d", i),
            x = center,
            y = 0.2 + i * 0.15,
            z = center,
            scale = config.base_scale + height * 0.5,
            rotation = 0,
            band = i,
            visible = true,
        }
    end

    return entities
end
```

### Step 4: Add Beat Response
```lua
state = {
    smooth_heights = {0, 0, 0, 0, 0},
    beat_pulse = 0.0,
}

function calculate(audio, config, dt)
    local entities = {}
    local center = 0.5

    -- Smooth all bands
    for i = 1, 5 do
        state.smooth_heights[i] = smooth(state.smooth_heights[i], audio.bands[i], 0.35, dt)
    end

    -- Beat pulse
    if audio.is_beat then
        state.beat_pulse = 1.0
    end
    state.beat_pulse = decay(state.beat_pulse, 0.85, dt)

    for i = 0, 4 do
        local height = state.smooth_heights[i + 1]
        local scale = config.base_scale + height * 0.5

        -- Beat boost
        scale = scale + state.beat_pulse * 0.3

        entities[#entities + 1] = {
            id = string.format("bar_%d", i),
            x = center,
            y = 0.2 + i * 0.15 + state.beat_pulse * 0.05,  -- Bounce up on beat
            z = center,
            scale = math.min(config.max_scale, scale),
            rotation = state.beat_pulse * 15,  -- Wiggle on beat
            band = i,
            visible = true,
        }
    end

    return entities
end
```

### Step 5: Scale to Entity Count
```lua
function calculate(audio, config, dt)
    local entities = {}
    local center = 0.5
    local n = config.entity_count

    -- Smooth all bands
    for i = 1, 5 do
        state.smooth_heights[i] = smooth(state.smooth_heights[i], audio.bands[i], 0.35, dt)
    end

    -- Beat pulse
    if audio.is_beat then
        state.beat_pulse = 1.0
    end
    state.beat_pulse = decay(state.beat_pulse, 0.85, dt)

    -- Create n entities distributed across 5 bands
    for i = 0, n - 1 do
        local band_idx = (i % 5) + 1  -- Cycle through 5 bands
        local height = state.smooth_heights[band_idx]

        -- Vertical position based on band
        local band_y = 0.2 + ((band_idx - 1) / 4) * 0.6

        -- Horizontal spread
        local x_offset = ((i % 5) - 2) * 0.08

        local scale = config.base_scale + height * 0.5 + state.beat_pulse * 0.3

        entities[#entities + 1] = {
            id = string.format("bar_%d", i),
            x = clamp(center + x_offset),
            y = clamp(band_y + state.beat_pulse * 0.05),
            z = center,
            scale = math.min(config.max_scale, scale),
            rotation = state.beat_pulse * 15,
            band = band_idx - 1,  -- 0-indexed for output
            visible = true,
        }
    end

    return entities
end
```

**Done!** You've built a scalable, audio-reactive pattern with smooth motion and beat response.

## Learning from Examples

Study these patterns from the `patterns/` directory:

- **`bars.lua`** - Spectrum analyzer with peak hold
- **`circle.lua`** - Radial frequency bars
- **`galaxy.lua`** - Spiral arms with core particles
- **`spectrum.lua`** - Stacked tower with spiral motion
- **`vortex.lua`** - Rotating vortex tunnel
- **`fireflies.lua`** - Organic particle swarm

## Next Steps

1. **Start simple** - Copy the Quick Start template
2. **Test early and often** - Use hot-reload to iterate quickly
3. **Study existing patterns** - See how others solve common challenges
4. **Share your work** - Contribute your patterns to the community!

## Contributing Your Pattern

Once you've created an awesome pattern, share it with the community! See the [Contributing Patterns](#contributing-patterns) section in CONTRIBUTING.md for details on submitting your pattern via pull request.

Happy visualizing! 🎵✨
