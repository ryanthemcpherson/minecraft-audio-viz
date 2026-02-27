// AUTO-GENERATED from patterns/*.lua — DO NOT EDIT
// Run: node scripts/generate-patterns.mjs

export const LIB_LUA = `-- lib.lua: Shared utility functions for Lua pattern scripts

function clamp(value, min_val, max_val)
    min_val = min_val or 0.0
    max_val = max_val or 1.0
    return math.max(min_val, math.min(max_val, value))
end

function lerp(a, b, t)
    return a + (b - a) * t
end

function smoothstep(edge0, edge1, x)
    local t = clamp((x - edge0) / (edge1 - edge0))
    return t * t * (3.0 - 2.0 * t)
end

function rotate_point_3d(x, y, z, rx, ry, rz)
    -- Rotate around X axis
    local cos_x, sin_x = math.cos(rx), math.sin(rx)
    local y1 = y * cos_x - z * sin_x
    local z1 = y * sin_x + z * cos_x
    -- Rotate around Y axis
    local cos_y, sin_y = math.cos(ry), math.sin(ry)
    local x1 = x * cos_y + z1 * sin_y
    local z2 = -x * sin_y + z1 * cos_y
    -- Rotate around Z axis
    local cos_z, sin_z = math.cos(rz), math.sin(rz)
    local x2 = x1 * cos_z - y1 * sin_z
    local y2 = x1 * sin_z + y1 * cos_z
    return x2, y2, z2
end

function fibonacci_sphere(n)
    local points = {}
    local phi = math.pi * (3.0 - math.sqrt(5.0))
    for i = 0, n - 1 do
        local y
        if n > 1 then
            y = 1 - (i / (n - 1)) * 2
        else
            y = 0
        end
        local radius = math.sqrt(1 - y * y)
        local theta = phi * i
        local x = math.cos(theta) * radius
        local z = math.sin(theta) * radius
        points[#points + 1] = {x = x, y = y, z = z}
    end
    return points
end

-- dt-aware smoothing: at dt=0.016, returns identical results to raw lerp
function smooth(current, target, rate, dt)
    local factor = 1.0 - (1.0 - rate) ^ (dt / 0.016)
    return current + (target - current) * factor
end

-- dt-aware decay: at dt=0.016, returns identical results to raw multiply
function decay(value, rate, dt)
    return value * rate ^ (dt / 0.016)
end

-- BPM sync utilities --

-- Get beat subdivision phase (0-1) for a given divisor.
-- divisor=1: whole beat, divisor=2: half note, divisor=4: quarter note
function beat_sub(beat_phase, divisor)
    divisor = divisor or 1
    return (beat_phase * divisor) % 1.0
end

-- Sine wave locked to beat phase. Returns -1 to 1.
function beat_sin(beat_phase, divisor)
    return math.sin(beat_sub(beat_phase, divisor or 1) * math.pi * 2)
end

-- Triangle wave locked to beat phase. Returns 0 to 1 (peaks at phase 0).
function beat_tri(beat_phase, divisor)
    local p = beat_sub(beat_phase, divisor or 1)
    return 1.0 - math.abs(p * 2.0 - 1.0)
end

-- Sharp pulse that peaks at phase 0 and decays. Returns 0 to 1.
-- sharpness controls falloff speed (higher = sharper pulse, default 4).
function beat_pulse(beat_phase, divisor, sharpness)
    sharpness = sharpness or 4.0
    local p = beat_sub(beat_phase, divisor or 1)
    return math.exp(-p * sharpness)
end

function simple_noise(x, y, z, seed)
    seed = seed or 0
    -- Use modular arithmetic to avoid bit operation compatibility issues
    -- across LuaJIT and Lua 5.4
    local n = math.floor(x * 73 + y * 179 + z * 283 + seed * 397)
    -- Scramble using large prime multiplications with modular wraparound
    n = (n * 8191) % 2147483647
    n = (n * 131071) % 2147483647
    local m = ((n * 15731 + 789221) % 2147483647 * n + 1376312589) % 2147483647
    return 1.0 - m / 1073741824.0
end

-- Pack entity table-of-tables into a flat numeric array for fast Python bridge transfer.
-- Returns (flat_array, entity_count). Fields per entity: x, y, z, scale, rotation, band, visible.
-- Python reads by index arithmetic: entity i's x = flat[i*7 + 1] (Lua 1-indexed).
function flat_pack(entities)
    local flat = {}
    local n = 0
    local count = #entities
    for i = 1, count do
        local e = entities[i]
        n = n + 1; flat[n] = e.x or 0.5
        n = n + 1; flat[n] = e.y or 0.5
        n = n + 1; flat[n] = e.z or 0.5
        n = n + 1; flat[n] = e.scale or 0.2
        n = n + 1; flat[n] = e.rotation or 0
        n = n + 1; flat[n] = e.band or 0
        n = n + 1; flat[n] = (e.visible == false) and 0 or 1
    end
    return flat, count
end

-- Normalize pattern output to exactly n entities.
-- Truncates overflow and pads with invisible placeholders when underfilled.
function normalize_entities(entities, n)
    n = math.max(0, math.floor(n or 0))
    while #entities > n do
        entities[#entities] = nil
    end
    while #entities < n do
        local idx = #entities
        entities[#entities + 1] = {
            id = string.format("__pad_%d", idx),
            x = 0.5,
            y = 0.5,
            z = 0.5,
            scale = 0.0,
            rotation = 0.0,
            band = 0,
            visible = false,
        }
    end
    return entities
end
`;

export interface LuaPatternDef {
  id: string;
  name: string;
  description: string;
  category: string;
  staticCamera: boolean;
  startBlocks: number | null;
  source: string;
}

export const PATTERNS: LuaPatternDef[] = [
  {
    id: "bpm_pulse",
    name: "BPM Pulse",
    description: "Sphere of blocks that pulse in sync with the beat phase",
    category: "Original",
    staticCamera: false,
    startBlocks: 64,
    source: `-- Pattern metadata
name = "BPM Pulse"
description = "Sphere of blocks that pulse in sync with the beat phase"
category = "Original"
static_camera = false
recommended_entities = 64

-- Per-instance state
state = {
    smooth_bass = 0.0,
    smooth_mid = 0.0,
    rotation_y = 0.0,
}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Smooth audio inputs
    state.smooth_bass = smooth(state.smooth_bass, audio.bands[1], 0.4, dt)
    state.smooth_mid = smooth(state.smooth_mid, audio.bands[3], 0.3, dt)

    -- Slow rotation, slightly faster with energy
    state.rotation_y = state.rotation_y + (0.3 + audio.amplitude * 0.8) * dt

    -- BPM-synced pulse: sharp attack on beat, smooth decay
    local pulse = beat_pulse(audio.beat_phase, 1, 5.0)
    -- Half-time pulse for variety (divisor < 1 slows phase → one pulse per 2 beats)
    local half_pulse = beat_pulse(audio.beat_phase, 0.5, 3.0)

    -- Generate sphere points (cached)
    if not state.points or #state.points ~= n then
        state.points = fibonacci_sphere(n)
    end
    local points = state.points

    for i = 1, n do
        local p = points[i]

        -- Base sphere radius expands with pulse
        local base_radius = 0.18 + pulse * 0.1 + state.smooth_bass * 0.06

        -- Alternate layers use half-time pulse for polyrhythmic feel
        local layer_pulse = pulse
        if i % 3 == 0 then
            layer_pulse = half_pulse
        end

        -- Scale radius per-entity with its layer pulse
        local radius = base_radius + layer_pulse * 0.04

        -- Apply rotation around Y axis
        local cos_r = math.cos(state.rotation_y)
        local sin_r = math.sin(state.rotation_y)
        local rx = p.x * cos_r - p.z * sin_r
        local rz = p.x * sin_r + p.z * cos_r

        local x = center + rx * radius
        local y = center + p.y * radius
        local z = center + rz * radius

        -- Scale pulses with beat phase
        local base_scale = config.base_scale + state.smooth_mid * 0.15
        local scale = base_scale + layer_pulse * 0.25

        -- Beat intensity boost
        if audio.is_beat then
            scale = scale * 1.3
        end

        local band_idx = (i % 5)

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            rotation = (pulse * 90 + i * 7) % 360,
            band = band_idx,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "bpm_strobe",
    name: "BPM Strobe",
    description: "Ring of blocks with visibility and scale locked to beat subdivisions",
    category: "Original",
    staticCamera: false,
    startBlocks: 32,
    source: `-- Pattern metadata
name = "BPM Strobe"
description = "Ring of blocks with visibility and scale locked to beat subdivisions"
category = "Original"
static_camera = false
recommended_entities = 32

-- Per-instance state
state = {
    rotation = 0.0,
    smooth_bass = 0.0,
    smooth_high = 0.0,
}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Smooth inputs
    state.smooth_bass = smooth(state.smooth_bass, audio.bands[1], 0.35, dt)
    state.smooth_high = smooth(state.smooth_high, audio.bands[5], 0.3, dt)

    -- Rotation advances in sync with beat phase for locked motion
    -- Full rotation per beat when BPM is detected
    if audio.bpm > 0 then
        state.rotation = audio.beat_phase * math.pi * 2
    else
        state.rotation = state.rotation + 1.5 * dt
    end

    -- Beat subdivision phases
    local phase_1 = beat_sub(audio.beat_phase, 1)    -- whole beat
    local phase_2 = beat_sub(audio.beat_phase, 2)    -- half notes
    local phase_4 = beat_sub(audio.beat_phase, 4)    -- quarter notes

    -- Triangle waves for smooth pulsing per subdivision
    local tri_1 = beat_tri(audio.beat_phase, 1)
    local tri_2 = beat_tri(audio.beat_phase, 2)
    local tri_4 = beat_tri(audio.beat_phase, 4)

    -- Stacked rings
    local num_rings = math.max(1, math.floor(math.sqrt(n)))
    local per_ring = math.max(1, math.floor(n / num_rings))

    local entity_idx = 0
    for ring = 0, num_rings - 1 do
        local ring_frac = ring / math.max(1, num_rings - 1)  -- 0 to 1
        local ring_y = 0.15 + ring_frac * 0.7

        -- Each ring uses a different subdivision for its strobe
        local subdivision
        if ring % 3 == 0 then
            subdivision = 1  -- whole beat
        elseif ring % 3 == 1 then
            subdivision = 2  -- half notes
        else
            subdivision = 4  -- quarter notes
        end

        local strobe_tri = beat_tri(audio.beat_phase, subdivision)
        local strobe_pulse = beat_pulse(audio.beat_phase, subdivision, 6.0)

        -- Ring radius varies with subdivision pulse
        local base_radius = 0.12 + ring_frac * 0.08
        local radius = base_radius + strobe_pulse * 0.06 + state.smooth_bass * 0.04

        local items_this_ring = per_ring
        if ring == num_rings - 1 then
            items_this_ring = n - entity_idx  -- remaining entities go to last ring
        end

        for j = 0, items_this_ring - 1 do
            if entity_idx >= n then break end

            local angle = (j / items_this_ring) * math.pi * 2 + state.rotation
            -- Offset alternate rings for visual interest
            if ring % 2 == 1 then
                angle = angle + math.pi / items_this_ring
            end

            local x = center + math.cos(angle) * radius
            local z = center + math.sin(angle) * radius

            -- Scale follows the subdivision strobe
            local scale = config.base_scale + strobe_tri * 0.35
            scale = scale + state.smooth_high * 0.1

            -- Visibility: strobe effect - hide when subdivision phase crosses threshold
            local vis_phase = beat_sub(audio.beat_phase, subdivision)
            -- Offset each entity slightly for a sweep effect
            local entity_offset = j / items_this_ring * 0.3
            local vis_check = (vis_phase + entity_offset) % 1.0
            local visible = vis_check < 0.7  -- visible 70% of the time

            -- Band assignment: spread across bands by ring
            local band = (ring + j) % 5

            -- Y position bobs with strobe
            local y = ring_y + strobe_pulse * 0.04

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                rotation = (angle * 180 / math.pi + strobe_tri * 30) % 360,
                band = band,
                visible = visible,
            }

            entity_idx = entity_idx + 1
        end
    end

    return entities
end
`,
  },
  {
    id: "columns",
    name: "Floating Platforms",
    description: "6 levitating platforms - one per frequency",
    category: "Original",
    staticCamera: false,
    startBlocks: 50,
    source: `-- Pattern metadata
name = "Floating Platforms"
description = "6 levitating platforms - one per frequency"
recommended_entities = 50
category = "Original"
static_camera = false

-- Per-instance state
state = {
    rotation = 0.0,
    platform_y = { 0.2, 0.35, 0.5, 0.65, 0.75 },
    bounce = { 0.0, 0.0, 0.0, 0.0, 0.0 },
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation + 0.5 * dt

    -- Update platform heights based on bands
    for band = 1, 5 do
        local target_y = 0.15 + (band - 1) * 0.14 + audio.bands[band] * 0.15
        state.platform_y[band] = smooth(state.platform_y[band], target_y, 0.1, dt)

        -- Bounce on beat
        if audio.is_beat and band <= 3 then
            state.bounce[band] = 0.15
        end
        state.bounce[band] = decay(state.bounce[band], 0.9, dt)
    end

    -- Distribute entities across 5 platforms
    local entities_per_platform = math.max(1, math.floor(n / 5))

    for band = 1, 5 do
        local platform_angle = state.rotation + (band - 1) * (math.pi * 2 / 5)
        local platform_radius = 0.2 + audio.bands[band] * 0.1

        for j = 0, entities_per_platform - 1 do
            local entity_idx = (band - 1) * entities_per_platform + j
            if entity_idx >= n then
                break
            end

            -- Spread blocks within platform
            local offset_angle = (j / entities_per_platform) * math.pi * 0.5 - math.pi * 0.25
            local angle = platform_angle + offset_angle

            -- Position
            local spread = 0.03 + audio.bands[band] * 0.02
            local x = center + math.cos(angle) * (platform_radius + j * spread * 0.3)
            local z = center + math.sin(angle) * (platform_radius + j * spread * 0.3)
            local y = state.platform_y[band] + state.bounce[band]

            local scale = config.base_scale + audio.bands[band] * 0.5

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                rotation = 0,
                band = band - 1,
                visible = true,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "explode",
    name: "Supernova",
    description: "Explosive burst on beats - 3D shockwave",
    category: "Original",
    staticCamera: false,
    startBlocks: 64,
    source: `-- Pattern metadata
name = "Supernova"
description = "Explosive burst on beats - 3D shockwave"
recommended_entities = 64
category = "Original"
static_camera = false

-- Per-instance state
state = {
    positions = {},   -- array of {r, theta, phi}
    velocities = {},
}

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize random directions
    if #state.positions ~= n then
        state.positions = {}
        state.velocities = {}
        for i = 1, n do
            local theta = math.random() * math.pi * 2
            local phi = math.random() * math.pi
            state.positions[i] = { 0.05, theta, phi }  -- {radius, theta, phi}
            state.velocities[i] = 0.0
        end
    end

    -- Beat triggers explosion
    if audio.is_beat and audio.beat_intensity > 0.3 then
        for i = 1, n do
            state.velocities[i] = 0.8 + math.random() * 0.4
            -- Randomize direction slightly
            state.positions[i][2] = state.positions[i][2] + (math.random() - 0.5) * 0.6
            state.positions[i][3] = state.positions[i][3] + (math.random() - 0.5) * 0.4
        end
    end

    local entities = {}
    local center = 0.5

    for i = 1, n do
        -- Update position
        state.positions[i][1] = state.positions[i][1] + state.velocities[i] * dt
        state.velocities[i] = decay(state.velocities[i], 0.96, dt)  -- Drag

        -- Gravity back to center
        if state.positions[i][1] > 0.05 then
            state.velocities[i] = state.velocities[i] - 0.02 * (dt / 0.016)
        end

        -- Clamp radius
        state.positions[i][1] = clamp(state.positions[i][1], 0.02, 0.45)

        -- Spherical to cartesian
        local r = state.positions[i][1]
        local theta = state.positions[i][2]
        local phi = state.positions[i][3]

        local x = center + r * math.sin(phi) * math.cos(theta)
        local y = center + r * math.cos(phi)
        local z = center + r * math.sin(phi) * math.sin(theta)

        local band_idx = ((i - 1) % 5) + 1
        local scale = config.base_scale
            + audio.bands[band_idx] * 0.3
            + math.abs(state.velocities[i]) * 0.5

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx - 1,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "heartbeat",
    name: "Breathing Cube",
    description: "Rotating cube vertices - expands with beats",
    category: "Original",
    staticCamera: false,
    startBlocks: 64,
    source: `-- Pattern metadata
name = "Breathing Cube"
description = "Rotating cube vertices - expands with beats"
recommended_entities = 64
category = "Original"
static_camera = false

-- Per-instance state
state = {
    rotation_x = 0.0,
    rotation_y = 0.0,
    rotation_z = 0.0,
    breath = 0.5,
    points = {},
}

-- Generate points on cube surface for any count
local function generate_cube_points(n)
    local points = {}

    -- Always include 8 vertices
    local vertices = {
        { -1, -1, -1 },
        { -1, -1,  1 },
        { -1,  1, -1 },
        { -1,  1,  1 },
        {  1, -1, -1 },
        {  1, -1,  1 },
        {  1,  1, -1 },
        {  1,  1,  1 },
    }
    for _, v in ipairs(vertices) do
        points[#points + 1] = v
    end

    if n <= 8 then
        local result = {}
        for i = 1, n do
            result[i] = points[i]
        end
        return result
    end

    -- Add edge midpoints (12 edges)
    local edge_mids = {
        {  0, -1, -1 },
        {  0,  1, -1 },
        {  0, -1,  1 },
        {  0,  1,  1 },  -- X edges
        { -1,  0, -1 },
        {  1,  0, -1 },
        { -1,  0,  1 },
        {  1,  0,  1 },  -- Y edges
        { -1, -1,  0 },
        {  1, -1,  0 },
        { -1,  1,  0 },
        {  1,  1,  0 },  -- Z edges
    }
    for _, e in ipairs(edge_mids) do
        points[#points + 1] = e
    end

    if n <= 20 then
        local result = {}
        for i = 1, n do
            result[i] = points[i]
        end
        return result
    end

    -- Add face centers (6 faces)
    local face_centers = {
        {  0,  0, -1 },
        {  0,  0,  1 },  -- Front/Back
        { -1,  0,  0 },
        {  1,  0,  0 },  -- Left/Right
        {  0, -1,  0 },
        {  0,  1,  0 },  -- Bottom/Top
    }
    for _, f in ipairs(face_centers) do
        points[#points + 1] = f
    end

    -- If we need more, add random points on cube surface
    while #points < n do
        local face = math.random(0, 5)
        local u = (math.random() * 1.6) - 0.8  -- -0.8 to 0.8
        local v = (math.random() * 1.6) - 0.8
        if face == 0 then
            points[#points + 1] = { u, v, -1 }
        elseif face == 1 then
            points[#points + 1] = { u, v, 1 }
        elseif face == 2 then
            points[#points + 1] = { -1, u, v }
        elseif face == 3 then
            points[#points + 1] = { 1, u, v }
        elseif face == 4 then
            points[#points + 1] = { u, -1, v }
        else
            points[#points + 1] = { u, 1, v }
        end
    end

    local result = {}
    for i = 1, n do
        result[i] = points[i]
    end
    return result
end

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Generate points if needed
    if #state.points ~= n then
        state.points = generate_cube_points(n)
    end

    -- Rotation speeds - more reactive
    state.rotation_y = state.rotation_y + (0.5 + audio.amplitude * 1.5) * dt
    state.rotation_x = state.rotation_x + (0.2 + audio.bands[3] * 0.5) * dt
    state.rotation_z = state.rotation_z + (0.1 + audio.bands[5] * 0.3) * dt

    -- Breathing - more dramatic
    local target_breath = 0.15 + audio.bands[1] * 0.15 + audio.bands[2] * 0.1
    if audio.is_beat then
        target_breath = target_breath + 0.15
    end
    state.breath = smooth(state.breath, target_breath, 0.2, dt)

    for i = 1, n do
        local px = state.points[i][1]
        local py = state.points[i][2]
        local pz = state.points[i][3]

        -- Apply rotations
        -- Y rotation
        local cos_y = math.cos(state.rotation_y)
        local sin_y = math.sin(state.rotation_y)
        local rx = px * cos_y - pz * sin_y
        local rz = px * sin_y + pz * cos_y

        -- X rotation
        local cos_x = math.cos(state.rotation_x)
        local sin_x = math.sin(state.rotation_x)
        local ry = py * cos_x - rz * sin_x
        local rz2 = py * sin_x + rz * cos_x

        -- Z rotation
        local cos_z = math.cos(state.rotation_z)
        local sin_z = math.sin(state.rotation_z)
        local rx2 = rx * cos_z - ry * sin_z
        local ry2 = rx * sin_z + ry * cos_z

        -- Scale by breath and center
        local x = center + rx2 * state.breath
        local y = center + ry2 * state.breath
        local z = center + rz2 * state.breath

        local band_idx = ((i - 1) % 5) + 1
        local scale = config.base_scale + audio.bands[band_idx] * 0.4

        if audio.is_beat then
            scale = scale * 1.4
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx - 1,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "matrix",
    name: "Fountain",
    description: "Upward spray with gravity arcs",
    category: "Original",
    staticCamera: false,
    startBlocks: 64,
    source: `-- Pattern metadata
name = "Fountain"
description = "Upward spray with gravity arcs"
recommended_entities = 64
category = "Original"
static_camera = false

-- Per-instance state
state = {
    particles = {},  -- array of {x, y, z, vx, vy, vz, age, spin}
}

-- Spawn a new fountain particle
local function spawn_particle(audio, idx)
    local center = 0.5

    -- Random upward velocity
    local speed = 0.015 + audio.amplitude * 0.02
    if audio.is_beat then
        speed = speed * 1.5
    end

    local angle = math.random() * math.pi * 2
    local band_idx = (idx % 5) + 1
    local spread = 0.003 + audio.bands[band_idx] * 0.003

    local vx = math.cos(angle) * spread
    local vz = math.sin(angle) * spread
    local vy = speed + math.random() * 0.008

    local spawn_radius = 0.02 + audio.bands[2] * 0.03
    local x = center + math.cos(angle) * spawn_radius
    local z = center + math.sin(angle) * spawn_radius
    local spin = (math.random() * 2 - 1)  -- -1 to 1

    return { x, 0.05, z, vx, vy, vz, 0.0, spin }
end

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize particles
    if #state.particles ~= n then
        state.particles = {}
        for i = 1, n do
            state.particles[i] = spawn_particle(audio, i - 1)
        end
    end

    local entities = {}
    local gravity = 0.015
    local drag = 0.985

    for i = 1, n do
        local p = state.particles[i]
        local x, y, z, vx, vy, vz, age, spin = p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]

        -- Update physics
        vy = vy - gravity * (dt / 0.016)
        age = age + dt

        -- Add gentle swirl and drag for a more fluid fountain
        local swirl = 0.02 + audio.bands[3] * 0.05
        vx = vx + (-(z - 0.5)) * swirl
        vz = vz + (x - 0.5) * swirl

        local spin_speed = (0.6 + audio.bands[4] * 1.2) * spin
        local cos_s = math.cos(spin_speed * dt)
        local sin_s = math.sin(spin_speed * dt)
        local new_vx = vx * cos_s - vz * sin_s
        local new_vz = vx * sin_s + vz * cos_s
        vx = new_vx
        vz = new_vz

        vx = decay(vx, drag, dt)
        vz = decay(vz, drag, dt)
        x = x + vx * dt * 60
        y = y + vy * dt * 60
        z = z + vz * dt * 60

        -- Respawn if below ground or on beat
        if y < 0 or (audio.is_beat and math.random() < 0.3) then
            local sp = spawn_particle(audio, i - 1)
            x, y, z, vx, vy, vz, age, spin = sp[1], sp[2], sp[3], sp[4], sp[5], sp[6], sp[7], sp[8]
        end

        state.particles[i] = { x, y, z, vx, vy, vz, age, spin }

        local band_idx = ((i - 1) % 5) + 1
        local scale = config.base_scale + audio.bands[band_idx] * 0.4

        -- Scale based on height (bigger at peak)
        local height_scale = 1.0
        if y > 0.5 then
            height_scale = 1.0 + (y - 0.5) * 0.5
        end
        scale = scale * height_scale
        scale = scale + math.min(0.3, age * 0.1)

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx - 1,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "orbit",
    name: "Atom Model",
    description: "Nucleus + electrons on 3D orbital planes",
    category: "Original",
    staticCamera: false,
    startBlocks: 40,
    source: `-- Pattern metadata
name = "Atom Model"
description = "Nucleus + electrons on 3D orbital planes"
recommended_entities = 40
category = "Original"
static_camera = false

-- Per-instance state
state = {
    orbit_angles = {},
    nucleus_pulse = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Ensure orbit angles array matches entity count
    while #state.orbit_angles < n do
        state.orbit_angles[#state.orbit_angles + 1] = math.random() * math.pi * 2
    end

    -- Nucleus pulse
    if audio.is_beat then
        state.nucleus_pulse = 1.0
    end
    state.nucleus_pulse = decay(state.nucleus_pulse, 0.9, dt)

    -- Nucleus: first 4 blocks clustered at center
    local nucleus_count = math.min(4, n)
    local nucleus_spread = 0.03 + state.nucleus_pulse * 0.05 + audio.bands[1] * 0.03

    for i = 1, nucleus_count do
        local angle = ((i - 1) / nucleus_count) * math.pi * 2
        local x = center + math.cos(angle) * nucleus_spread
        local z = center + math.sin(angle) * nucleus_spread
        local y = center + math.sin(angle * 2) * nucleus_spread * 0.5

        local scale = config.base_scale + audio.bands[1] * 0.4 + state.nucleus_pulse * 0.3

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            rotation = 0,
            band = 0,
            visible = true,
        }
    end

    -- Electrons: remaining blocks on 3 orbital planes
    local electron_count = n - nucleus_count
    local electrons_per_orbit = math.max(1, math.floor(electron_count / 3))

    local orbit_tilts = { { 0, 0 }, { math.pi / 3, 0 }, { 0, math.pi / 3 } }
    local orbit_speeds = { 1.0, 1.3, 0.8 }

    for orbit = 1, 3 do
        local tilt_x = orbit_tilts[orbit][1]
        local tilt_z = orbit_tilts[orbit][2]
        local speed = orbit_speeds[orbit] * (1 + audio.amplitude)

        if audio.is_beat then
            speed = speed * 1.5
        end

        for j = 0, electrons_per_orbit - 1 do
            local entity_idx = nucleus_count + (orbit - 1) * electrons_per_orbit + j
            if entity_idx >= n then
                break
            end

            -- Update orbit angle (1-indexed in state)
            local state_idx = entity_idx + 1
            state.orbit_angles[state_idx] = state.orbit_angles[state_idx] + speed * dt

            local angle = state.orbit_angles[state_idx] + (j / electrons_per_orbit) * math.pi * 2
            local radius = 0.2 + (orbit - 1) * 0.08

            -- Base position on XZ plane
            local px = math.cos(angle) * radius
            local py = 0
            local pz = math.sin(angle) * radius

            -- Apply tilts
            -- Tilt around X axis
            local cos_tx = math.cos(tilt_x)
            local sin_tx = math.sin(tilt_x)
            local py2 = py * cos_tx - pz * sin_tx
            local pz2 = py * sin_tx + pz * cos_tx

            -- Tilt around Z axis
            local cos_tz = math.cos(tilt_z)
            local sin_tz = math.sin(tilt_z)
            local px2 = px * cos_tz - py2 * sin_tz
            local py3 = px * sin_tz + py2 * cos_tz

            local x = center + px2
            local y = center + py3
            local z = center + pz2

            local band_idx = ((orbit - 1 + 2) % 5) + 1
            local scale = config.base_scale + audio.bands[band_idx] * 0.3

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                rotation = 0,
                band = band_idx - 1,
                visible = true,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "ring",
    name: "Expanding Sphere",
    description: "3D sphere that breathes and pulses",
    category: "Original",
    staticCamera: false,
    startBlocks: 64,
    source: `-- Pattern metadata
name = "Expanding Sphere"
description = "3D sphere that breathes and pulses"
recommended_entities = 64
category = "Original"
static_camera = false

-- Per-instance state
state = {
    sphere_points = {},
    rotation_y = 0.0,
    rotation_x = 0.0,
    breath = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize sphere points (using fibonacci_sphere from lib.lua)
    if #state.sphere_points ~= n then
        state.sphere_points = fibonacci_sphere(n)
    end

    -- Rotation
    state.rotation_y = state.rotation_y + 0.3 * dt
    state.rotation_x = state.rotation_x + 0.1 * dt

    -- Breathing effect
    local target_breath = audio.bands[1] * 0.5 + audio.bands[2] * 0.3
    if audio.is_beat then
        target_breath = target_breath + 0.3
    end
    state.breath = smooth(state.breath, target_breath, 0.15, dt)

    local entities = {}
    local center = 0.5
    local base_radius = 0.15 + state.breath * 0.2

    for i = 1, n do
        local px = state.sphere_points[i].x
        local py = state.sphere_points[i].y
        local pz = state.sphere_points[i].z

        -- Apply Y rotation
        local cos_y = math.cos(state.rotation_y)
        local sin_y = math.sin(state.rotation_y)
        local rx = px * cos_y - pz * sin_y
        local rz = px * sin_y + pz * cos_y

        -- Apply X rotation
        local cos_x = math.cos(state.rotation_x)
        local sin_x = math.sin(state.rotation_x)
        local ry = py * cos_x - rz * sin_x
        local rz2 = py * sin_x + rz * cos_x

        -- Scale by radius and center
        local band_idx = ((i - 1) % 5) + 1
        local radius = base_radius + audio.bands[band_idx] * 0.1

        local x = center + rx * radius
        local y = center + ry * radius
        local z = center + rz2 * radius

        local scale = config.base_scale + audio.bands[band_idx] * 0.4
        if audio.is_beat then
            scale = scale * 1.4
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx - 1,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "spectrum",
    name: "Stacked Tower",
    description: "Spiraling vertical tower - blocks orbit and bounce",
    category: "Original",
    staticCamera: false,
    startBlocks: 64,
    source: `-- Pattern metadata
name = "Stacked Tower"
description = "Spiraling vertical tower - blocks orbit and bounce"
recommended_entities = 64
category = "Original"
static_camera = false

-- Per-instance state
state = {
    rotation = 0.0,
    bounce_wave = {},
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local center = 0.5
    local n = config.entity_count

    -- Ensure bounce wave array matches entity count
    while #state.bounce_wave < n do
        state.bounce_wave[#state.bounce_wave + 1] = 0.0
    end

    -- Rotation speed based on energy
    state.rotation = state.rotation + (0.5 + audio.amplitude * 2.0) * dt

    -- Trigger bounce wave on beat
    if audio.is_beat then
        state.bounce_wave[1] = 1.0
    end

    -- Propagate bounce wave upward
    for i = math.min(n, #state.bounce_wave), 2, -1 do
        state.bounce_wave[i] = decay(state.bounce_wave[i], 0.9, dt) + state.bounce_wave[i - 1] * 0.15 * (dt / 0.016)
    end
    state.bounce_wave[1] = decay(state.bounce_wave[1], 0.85, dt)

    for i = 1, n do
        -- Vertical position - scale to fit within bounds
        local normalized_i = (i - 1) / math.max(1, n - 1)  -- 0 to 1
        local base_y = 0.1 + normalized_i * 0.6  -- 0.1 to 0.7 base range

        -- Add audio-reactive spread
        local spread = audio.amplitude * 0.15
        local y = base_y + spread * normalized_i

        -- Spiral around center - more turns with more blocks
        local turns = 2 + n / 16  -- More blocks = more spiral turns
        local angle = state.rotation + normalized_i * math.pi * 2 * turns
        local band_idx = ((i - 1) % 5) + 1  -- 1-indexed band
        local orbit_radius = 0.08 + audio.bands[band_idx] * 0.2

        local x = center + math.cos(angle) * orbit_radius
        local z = center + math.sin(angle) * orbit_radius

        -- Bounce effect
        local bounce = 0
        if i <= #state.bounce_wave then
            bounce = state.bounce_wave[i]
        end
        y = y + bounce * 0.15

        -- Scale based on frequency band
        local scale = config.base_scale + audio.bands[band_idx] * 0.6

        if audio.is_beat then
            scale = scale * 1.5
            y = y + 0.05
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0.05, 0.95),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx - 1,  -- 0-indexed band for output
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "wave",
    name: "DNA Helix",
    description: "Double helix spiral - rotates and stretches",
    category: "Original",
    staticCamera: false,
    startBlocks: 90,
    source: `-- Pattern metadata
name = "DNA Helix"
description = "Double helix spiral - rotates and stretches"
category = "Original"
static_camera = false
start_blocks = 90

-- Per-instance state
state = {
    rotation = 0.0,
    stretch = 1.0,
    beat_pulse = 0.0,
    phase = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5
    if n <= 0 then
        return entities
    end

    -- Rotation speed based on energy
    local speed = 0.85 + audio.amplitude * 2.2
    if audio.is_beat then
        speed = speed * 1.5
        state.beat_pulse = 1.0
    end
    state.rotation = state.rotation + speed * dt
    state.phase = state.phase + (0.45 + audio.bands[4] * 0.9) * dt
    state.beat_pulse = decay(state.beat_pulse, 0.84, dt)

    -- Stretch based on bass
    local target_stretch = 0.8 + audio.bands[1] * 0.6 + audio.bands[2] * 0.4
    state.stretch = smooth(state.stretch, target_stretch, 0.13, dt)

    -- Helix parameters
    local radius = 0.12 + audio.amplitude * 0.12 + state.beat_pulse * 0.035
    local pitch = 0.08 * state.stretch

    for i = 1, n do
        -- Alternate between two helixes (0-indexed logic)
        local helix = (i - 1) % 2
        local idx = math.floor((i - 1) / 2)

        -- Position along helix
        local half_n = n / 2
        local t = idx / half_n * math.pi * 3.4  -- 3.4 turns for denser helix
        local angle = t + state.rotation + (helix * math.pi)  -- Offset second helix by 180 degrees

        -- Helix coordinates
        local wobble = math.sin(t * 1.7 + state.phase) * (0.012 + audio.bands[3] * 0.02)
        local local_radius = radius + wobble
        local x = center + math.cos(angle) * local_radius
        local z = center + math.sin(angle) * local_radius
        local y = 0.08 + (idx / half_n) * 0.84 + pitch * math.sin(t + state.phase * 0.4)

        -- Pulse radius with band
        local band_idx = (idx % 5) + 1
        local pulse = audio.bands[band_idx] * 0.05
        x = x + math.cos(angle) * pulse
        z = z + math.sin(angle) * pulse

        local scale = config.base_scale * 0.85 + audio.bands[band_idx] * 0.45
        if audio.is_beat then
            scale = scale * 1.22
        end
        scale = scale + state.beat_pulse * 0.08
        local rot = (angle + y * math.pi * 2.0 + helix * 0.4) * 180 / math.pi

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            rotation = rot % 360,
            band = band_idx - 1,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "crown",
    name: "Crown",
    description: "Floating royal crown with 5 frequency-reactive spikes and glowing jewels",
    category: "Epic",
    staticCamera: false,
    startBlocks: 96,
    source: `name = "Crown"
description = "Floating royal crown with 5 frequency-reactive spikes and glowing jewels"
recommended_entities = 96
category = "Epic"
static_camera = false
state = {}

local function allocate_counts(total, weights)
    local counts = {}
    local fracs = {}
    local weight_sum = 0
    for k, v in pairs(weights) do
        weight_sum = weight_sum + v
    end
    local used = 0
    for k, v in pairs(weights) do
        local exact = (total * v) / weight_sum
        local base = math.floor(exact)
        counts[k] = base
        fracs[#fracs + 1] = {k = k, frac = exact - base}
        used = used + base
    end
    table.sort(fracs, function(a, b) return a.frac > b.frac end)
    local rem = total - used
    local idx = 1
    while rem > 0 and #fracs > 0 do
        local key = fracs[idx].k
        counts[key] = counts[key] + 1
        rem = rem - 1
        idx = idx + 1
        if idx > #fracs then
            idx = 1
        end
    end
    return counts
end

function calculate(audio, config, dt)
    local n = config.entity_count or 0
    -- Initialize state
    if not state.rotation then
        state.rotation = 0
        state.time = 0
        state.beat_flash = 0
        state.hover = 0
        state.spike_impulse = {0, 0, 0, 0, 0}
        state.smooth_bands = {0, 0, 0, 0, 0}
        state.crown_scale = 1.0
    end

    local bands = audio.bands
    local amplitude = audio.amplitude or 0
    local beat = audio.is_beat
    local beat_phase = audio.beat_phase or 0

    -- Smooth bands
    for i = 1, 5 do
        state.smooth_bands[i] = smooth(state.smooth_bands[i], bands[i] or 0, 0.3, dt)
    end

    -- Beat response
    if beat then
        state.beat_flash = 1.0
        state.crown_scale = 1.15
        for i = 1, 5 do
            state.spike_impulse[i] = 0.06
        end
    end

    -- Decay state
    state.beat_flash = decay(state.beat_flash, 0.85, dt)
    state.crown_scale = smooth(state.crown_scale, 1.0, 0.08, dt)
    for i = 1, 5 do
        state.spike_impulse[i] = decay(state.spike_impulse[i], 0.9, dt)
    end

    -- Update time and rotation
    state.time = state.time + dt
    state.rotation = state.rotation + (0.3 + amplitude * 0.2) * dt

    -- Hover oscillation (subtle 3-axis bob for floating feel)
    local hover_y = math.sin(state.time * 1.5) * 0.02
    local hover_x = math.sin(state.time * 0.8) * 0.005
    local hover_z = math.cos(state.time * 0.7) * 0.005

    local entities = {}
    local idx = 1
    local base_y = 0.35 + hover_y
    local crown_s = state.crown_scale
    local base_scale = config.base_scale or 0.15
    local max_scale = config.max_scale or 0.5
    local entity_count = n

    -- Rotation helper: rotate (x, z) around center (0.5, 0.5) by state.rotation
    local cos_r = math.cos(state.rotation)
    local sin_r = math.sin(state.rotation)
    local function rotate_y(x, z)
        local dx = x - 0.5
        local dz = z - 0.5
        return 0.5 + (dx * cos_r - dz * sin_r) * crown_s,
               0.5 + (dx * sin_r + dz * cos_r) * crown_s
    end

    -- Beat brightness boost (applied to all entities)
    local beat_bright_boost = (state.beat_flash > 0.1) and math.floor(state.beat_flash * 3) or 0

    local function add_entity(x, y, z, scale, opts)
        if idx > entity_count then return end
        local rx, rz = rotate_y(x + hover_x, z + hover_z)
        -- Apply crown scale to y offset from base
        local ry = base_y + (y - base_y) * crown_s
        local raw_brightness = ((opts and opts.brightness) or 0) + beat_bright_boost
        entities[idx] = {
            id = string.format("block_%d", idx - 1),
            x = clamp(rx, 0, 1),
            y = clamp(ry, 0, 1),
            z = clamp(rz, 0, 1),
            scale = clamp(scale, 0, max_scale),
            rotation = 0,
            band = (opts and opts.band) or 0,
            visible = true,
            glow = (opts and opts.glow) or false,
            brightness = clamp(math.floor(raw_brightness), 0, 15),
            material = (opts and opts.material) or nil,
            interpolation = 2,
        }
        idx = idx + 1
    end

    local outer_radius = 0.15
    local inner_radius = 0.12

    -- Scale crown parts with entity budget (baseline 96).
    local parts = allocate_counts(entity_count, {
        ring = 24,
        spikes = 40,
        jewels = 5,
        detail = 16,
        filigree = 11,
    })
    local ring_count = math.max(1, parts.ring)
    local spike_count = math.max(0, parts.spikes)
    local jewel_count = math.max(0, math.min(5, parts.jewels))
    local jewel_overflow = math.max(0, parts.jewels - jewel_count)
    local detail_count = math.max(0, parts.detail + jewel_overflow)
    local filigree_count = math.max(0, parts.filigree)

    -- === BASE RING ===
    local ring_scale = base_scale * (0.8 + amplitude * 0.2)
    local ring_brightness = math.floor(8 + amplitude * 5)
    for i = 0, ring_count - 1 do
        local angle = i * 2 * math.pi / ring_count
        local x = 0.5 + math.cos(angle) * outer_radius
        local z = 0.5 + math.sin(angle) * outer_radius
        add_entity(x, base_y, z, ring_scale, {
            band = 0,
            material = "GOLD_BLOCK",
            brightness = ring_brightness,
        })
    end

    -- === 5 SPIKES (adaptive density) ===
    local base_points_per_spike = math.floor(spike_count / 5)
    local spike_rem = spike_count - base_points_per_spike * 5
    for k = 0, 4 do
        local points_this_spike = base_points_per_spike
        if k < spike_rem then
            points_this_spike = points_this_spike + 1
        end
        if points_this_spike > 0 then
            local base_angle = k * 2 * math.pi / 5
            local band_val = state.smooth_bands[k + 1]
            local spike_height = 0.12 + band_val * 0.08 + state.spike_impulse[k + 1]
            local spike_brightness = math.floor(7 + band_val * 6)

            for j = 0, points_this_spike - 1 do
                local t = j / math.max(1, points_this_spike - 1)
                -- Taper width from 0.04 at base to 0.005 at tip
                local width = 0.04 * (1 - t * 0.875)
                local y = base_y + t * spike_height
                -- Slight outward lean
                local lean = math.sin(t * math.pi) * 0.01
                local r = outer_radius + lean - t * 0.03

                local cx = 0.5 + math.cos(base_angle) * r
                local cz = 0.5 + math.sin(base_angle) * r

                -- Width perpendicular to radial direction
                local perp_angle = base_angle + math.pi / 2
                local wobble = math.sin(j * math.pi / 4)
                local wx = math.cos(perp_angle) * width * wobble
                local wz = math.sin(perp_angle) * width * wobble

                -- Tip glow: entities near tip glow when band is active
                local tip_glow = (t > 0.7) and (band_val > 0.5)

                local spike_scale = base_scale * (0.7 + band_val * 0.3) * (1 - t * 0.3)
                add_entity(cx + wx, y, cz + wz, spike_scale, {
                    band = k,
                    material = "GOLD_BLOCK",
                    brightness = spike_brightness,
                    glow = tip_glow,
                })
            end
        end
    end

    -- === JEWELS (adaptive, up to one per spike tip) ===
    local jewel_pulse = beat_pulse(beat_phase, 1, 3)
    local jewel_materials = {
        "DIAMOND_BLOCK", "EMERALD_BLOCK", "AMETHYST_BLOCK",
        "REDSTONE_BLOCK", "LAPIS_BLOCK",
    }
    for j = 0, jewel_count - 1 do
        local k = math.floor(j * 5 / math.max(1, jewel_count))
        local base_angle = k * 2 * math.pi / 5
        local band_val = state.smooth_bands[k + 1]
        local spike_height = 0.12 + band_val * 0.08 + state.spike_impulse[k + 1]

        local tip_r = outer_radius + math.sin(math.pi) * 0.01 - 0.03
        local tip_x = 0.5 + math.cos(base_angle) * tip_r
        local tip_z = 0.5 + math.sin(base_angle) * tip_r
        local tip_y = base_y + spike_height

        local jewel_brightness = math.floor(jewel_pulse * 15)
        add_entity(tip_x, tip_y, tip_z, base_scale * 0.9, {
            band = k,
            glow = true,
            brightness = jewel_brightness,
            material = jewel_materials[k + 1],
        })
    end

    -- === BAND DETAIL (adaptive split: inner ring + cross-braces) ===
    local band_y = base_y + 0.03
    local mid = state.smooth_bands[3]
    local mid_react = mid * 0.15
    local detail_scale = base_scale * (0.6 + mid_react)
    local detail_brightness = math.floor(6 + mid * 5)
    local inner_count = math.floor(detail_count / 2)
    local brace_count = detail_count - inner_count

    -- Inner ring
    for i = 0, inner_count - 1 do
        local angle = i * 2 * math.pi / math.max(1, inner_count)
        local x = 0.5 + math.cos(angle) * inner_radius
        local z = 0.5 + math.sin(angle) * inner_radius
        add_entity(x, band_y, z, detail_scale, {
            band = 3,
            material = "GOLD_BLOCK",
            brightness = detail_brightness,
        })
    end

    -- Cross-braces connecting inner to outer ring
    for i = 0, brace_count - 1 do
        local angle = i * 2 * math.pi / math.max(1, brace_count) + (math.pi / math.max(2, brace_count))
        local mid_r = (inner_radius + outer_radius) / 2
        local x = 0.5 + math.cos(angle) * mid_r
        local z = 0.5 + math.sin(angle) * mid_r
        local brace_y = base_y + 0.015
        add_entity(x, brace_y, z, detail_scale * 0.8, {
            band = 3,
            material = "GOLD_BLOCK",
            brightness = detail_brightness,
        })
    end

    -- === FILIGREE (adaptive arches between adjacent spikes) ===
    local filigree_brightness = math.floor(5 + amplitude * 4)
    local filigree_idx = 0
    local base_arch_points = math.floor(filigree_count / 5)
    local arch_rem = filigree_count - base_arch_points * 5
    for k = 0, 4 do
        local angle_a = k * 2 * math.pi / 5
        local angle_b = ((k + 1) % 5) * 2 * math.pi / 5

        local arch_points = base_arch_points
        if k < arch_rem then
            arch_points = arch_points + 1
        end
        for j = 1, arch_points do
            local t = j / (arch_points + 1)
            local angle = angle_a + (angle_b - angle_a) * t
            -- Handle wrap-around for last spike pair
            if k == 4 then
                local diff = angle_b + 2 * math.pi - angle_a
                angle = angle_a + diff * t
            end

            -- Parabolic arch: peaks at midpoint between spikes
            local arch_height = 0.025 * (4 * t * (1 - t))
            local x = 0.5 + math.cos(angle) * outer_radius
            local z = 0.5 + math.sin(angle) * outer_radius
            local y = base_y + arch_height

            add_entity(x, y, z, detail_scale * 0.7, {
                band = 0,
                material = "GOLD_BLOCK",
                brightness = filigree_brightness,
            })
            filigree_idx = filigree_idx + 1
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "dragon",
    name: "Dragon",
    description: "Fearsome dragon head with spread wings, reactive jaw, glowing eyes, and beat-driven wing flaps",
    category: "Epic",
    staticCamera: false,
    startBlocks: 160,
    source: `name = "Dragon"
description = "Fearsome dragon head with spread wings, reactive jaw, glowing eyes, and beat-driven wing flaps"
recommended_entities = 160
category = "Epic"
static_camera = false
state = {}

local function dragon_slice(y_norm)
    local points = {}

    if y_norm < 0.12 then
        -- Lower jaw: narrow elongated snout
        local t = y_norm / 0.12
        local width = 0.06 + t * 0.06
        local depth = 0.05 + t * 0.03
        for angle = -120, 120, 20 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * width
            local z = -math.cos(rad) * depth - 0.06
            points[#points + 1] = {x, z, "jaw"}
        end

    elseif y_norm < 0.25 then
        -- Upper snout: wider, protruding forward
        local t = (y_norm - 0.12) / 0.13
        local width = 0.10 + t * 0.04
        local depth = 0.07 + t * 0.03
        for angle = -100, 100, 15 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * width
            local z = -math.cos(rad) * depth - 0.12
            points[#points + 1] = {x, z, "snout"}
        end
        -- Nostrils (y 0.22-0.28 overlap)
        if y_norm > 0.20 then
            points[#points + 1] = {-0.03, -0.14, "nostril"}
            points[#points + 1] = { 0.03, -0.14, "nostril"}
        end

    elseif y_norm < 0.35 then
        -- Eyes: two elliptical orbits
        local t = (y_norm - 0.25) / 0.10
        if t > 0.1 and t < 0.9 then
            for _, side in ipairs({-1, 1}) do
                local eye_cx = side * 0.07
                local eye_cz = -0.09
                for angle = 0, 324, 36 do
                    local rad = math.rad(angle)
                    local ex = eye_cx + math.cos(rad) * 0.035
                    local ez = eye_cz + math.sin(rad) * 0.02
                    points[#points + 1] = {ex, ez, "eye"}
                end
            end
        end
        -- Cheek ridges
        local width = 0.14 + t * 0.02
        for _, side in ipairs({-1, 1}) do
            points[#points + 1] = {side * width, -0.04, "cranium"}
        end

    elseif y_norm < 0.50 then
        -- Brow ridge + horns
        local t = (y_norm - 0.35) / 0.15
        -- Brow ridge
        if t < 0.4 then
            for bx = -12, 12, 3 do
                local x = bx * 0.01
                local z = -0.10 - math.abs(x) * 0.4
                points[#points + 1] = {x, z, "brow"}
            end
        end
        -- Horns: extend upward and outward
        if t > 0.2 then
            local horn_t = (t - 0.2) / 0.8
            for _, side in ipairs({-1, 1}) do
                local hx = side * (0.10 + horn_t * 0.08)
                local hz = 0.02 + horn_t * 0.06
                points[#points + 1] = {hx, hz, "horn"}
            end
        end
        -- Cranium sides
        local width = 0.16 - t * 0.01
        for _, side in ipairs({-1, 1}) do
            points[#points + 1] = {side * width, 0.0, "cranium"}
        end

    elseif y_norm < 0.70 then
        -- Cranium: full ellipse
        local t = (y_norm - 0.50) / 0.20
        local width = 0.16 - t * 0.02
        local depth = 0.14 - t * 0.04
        for angle = 0, 348, 12 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * width
            local z = math.cos(rad) * depth
            points[#points + 1] = {x, z, "cranium"}
        end

    elseif y_norm < 0.80 then
        -- Neck: tapering cylinder
        local t = (y_norm - 0.70) / 0.10
        local radius = 0.10 - t * 0.02
        for angle = 0, 340, 20 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * radius
            local z = math.cos(rad) * radius * 0.9
            points[#points + 1] = {x, z, "neck"}
        end
    end

    -- Dorsal spines along the back
    if y_norm > 0.25 and y_norm < 0.80 then
        local spine_interval = 0.07
        local spine_pos = ((y_norm - 0.25) / spine_interval) % 1.0
        if spine_pos < 0.15 then
            points[#points + 1] = {0, 0.14, "spine"}
        end
    end

    return points
end

local function generate_wing(side, all_points)
    -- side: -1 for left, 1 for right
    local tag = side < 0 and "wing_l" or "wing_r"

    -- Wing root
    local root_x = side * 0.12
    local root_y = 0.55
    local root_z = 0.05

    -- Elbow
    local elbow_x = side * 0.25
    local elbow_y = 0.58
    local elbow_z = 0.03

    -- Points along the arm (root to elbow)
    for i = 0, 3 do
        local t = i / 3
        local ax = lerp(root_x, elbow_x, t)
        local ay = lerp(root_y, elbow_y, t)
        local az = lerp(root_z, elbow_z, t)
        all_points[#all_points + 1] = {tag, ax, ay, az}
    end

    -- Finger tips fanning from elbow
    local finger_angles = {-30, -10, 10, 30, 50}
    for _, angle_deg in ipairs(finger_angles) do
        local rad = math.rad(angle_deg)
        local tip_x = elbow_x + side * math.cos(rad) * 0.22
        local tip_y = elbow_y + math.sin(rad) * 0.08
        local tip_z = elbow_z - math.sin(rad) * 0.04

        -- Points along each finger
        for j = 1, 4 do
            local ft = j / 4
            local fx = lerp(elbow_x, tip_x, ft)
            local fy = lerp(elbow_y, tip_y, ft)
            local fz = lerp(elbow_z, tip_z, ft)
            all_points[#all_points + 1] = {tag, fx, fy, fz}
        end
    end

    -- Membrane between fingers (connecting lines with extra midpoints)
    for fi = 1, #finger_angles - 1 do
        local rad1 = math.rad(finger_angles[fi])
        local rad2 = math.rad(finger_angles[fi + 1])
        for j = 1, 3 do
            local ft = j / 4
            local mid_rad = lerp(rad1, rad2, 0.5)
            local dist = 0.14 * ft
            local mx = elbow_x + side * math.cos(mid_rad) * dist
            local my = elbow_y + math.sin(mid_rad) * dist * 0.45
            local mz = elbow_z - math.sin(mid_rad) * dist * 0.22
            all_points[#all_points + 1] = {tag, mx, my, mz}
        end
    end
end

local function generate_dragon_points(n)
    local all_points = {}
    local num_slices = math.max(20, math.floor(n / 8))

    for i = 0, num_slices - 1 do
        local y_norm = i / (num_slices - 1)
        local slice = dragon_slice(y_norm)
        for _, pt in ipairs(slice) do
            all_points[#all_points + 1] = {pt[3], pt[1], y_norm, pt[2]}
        end
    end

    -- Add wings separately (not slice-based)
    generate_wing(-1, all_points)
    generate_wing(1, all_points)

    -- Resample to exactly n
    if #all_points > n then
        local step = #all_points / n
        local selected = {}
        for i = 0, n - 1 do
            local idx = math.floor(i * step) + 1
            if idx <= #all_points then
                selected[#selected + 1] = all_points[idx]
            end
        end
        return selected
    else
        local result = {}
        for _, pt in ipairs(all_points) do
            result[#result + 1] = pt
        end
        local idx = 0
        while #result < n do
            local orig = all_points[(idx % #all_points) + 1]
            local varied = {
                orig[1],
                orig[2] + (idx * 0.001) % 0.01,
                orig[3],
                orig[4] + (idx * 0.001) % 0.01,
            }
            result[#result + 1] = varied
            idx = idx + 1
        end
        while #result > n do
            result[#result] = nil
        end
        return result
    end
end

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Generate points if needed
    if state.dragon_points == nil or #state.dragon_points ~= n then
        state.dragon_points = generate_dragon_points(n)
    end

    state.rotation = state.rotation or 0
    state.jaw_open = state.jaw_open or 0
    state.eye_glow = state.eye_glow or 0
    state.wing_spread = state.wing_spread or 0.6
    state.breathe = state.breathe or 0
    state.breathe_dir = state.breathe_dir or 1
    state.head_bob = state.head_bob or 0
    state.time = state.time or 0
    state.beat_intensity = state.beat_intensity or 0
    state.snarl = state.snarl or 0

    state.time = state.time + dt

    -- Slow menacing rotation
    state.rotation = state.time * 0.12

    -- Breathing triangle oscillator
    state.breathe = state.breathe + 0.012 * state.breathe_dir * (dt / 0.016)
    if state.breathe > 1.0 then
        state.breathe_dir = -1
    elseif state.breathe < 0.0 then
        state.breathe_dir = 1
    end

    -- Beat response
    if audio.is_beat then
        state.head_bob = 0.025 * audio.peak
        state.beat_intensity = 1.0
        state.eye_glow = 1.0
        state.wing_spread = math.min(1.3, state.wing_spread + 0.3)
        state.snarl = 1.0
    end
    state.head_bob = decay(state.head_bob, 0.9, dt)
    state.beat_intensity = decay(state.beat_intensity, 0.92, dt)
    state.eye_glow = decay(state.eye_glow, 0.85, dt)
    state.snarl = decay(state.snarl, 0.9, dt)

    -- Wing spread tracks amplitude
    local wing_target = 0.6 + audio.amplitude * 0.4
    state.wing_spread = smooth(state.wing_spread, wing_target, 0.15, dt)

    -- Jaw opens with bass
    local target_jaw = audio.bands[1] * 0.10
    if audio.is_beat then
        target_jaw = target_jaw + 0.08
    end
    state.jaw_open = smooth(state.jaw_open, target_jaw, 0.25, dt)

    local breathe_scale = 1.0 + state.breathe * 0.02
    local dragon_scale = 0.56 + audio.bands[2] * 0.05 + state.beat_intensity * 0.02

    -- Rotation and tilt
    local yaw = state.rotation + math.sin(state.time * 0.35) * 0.05
    local cos_r = math.cos(yaw)
    local sin_r = math.sin(yaw)
    local tilt_x = math.sin(state.time * 0.6) * 0.08 + state.beat_intensity * 0.12
    local tilt_z = math.cos(state.time * 0.5) * 0.05 + audio.bands[4] * 0.05
    local cos_tx, sin_tx = math.cos(tilt_x), math.sin(tilt_x)
    local cos_tz, sin_tz = math.cos(tilt_z), math.sin(tilt_z)

    -- Wing flap animation
    local wing_flap = math.sin(state.time * 2.2 + (audio.beat_phase or 0) * math.pi * 2) *
        (0.01 + audio.bands[2] * 0.03 + state.beat_intensity * 0.015)
    local head_forward = audio.bands[1] * 0.03 + state.snarl * 0.04

    for i = 1, #state.dragon_points do
        local point = state.dragon_points[i]
        local part_type = point[1]
        local px = point[2]
        local py_norm = point[3]
        local pz = point[4]

        -- Scale and position
        local is_wing = (part_type == "wing_l" or part_type == "wing_r")

        if is_wing then
            -- Wings: apply spread to x offset from center, flap to y
            local wing_x_offset = px * state.wing_spread
            px = wing_x_offset
            py_norm = py_norm + wing_flap
            -- Scale wings less aggressively
            px = px * (dragon_scale * 0.9)
            pz = pz * (dragon_scale * 0.9)
            pz = pz + math.sin(state.time * 1.6 + py_norm * 4) * 0.01
        else
            px = px * dragon_scale * breathe_scale
            pz = pz * dragon_scale * breathe_scale
        end

        local py = py_norm * 0.45 * dragon_scale

        -- Part-specific animation
        if part_type == "jaw" and py_norm < 0.12 then
            py = py - state.jaw_open * (0.12 - py_norm) / 0.12
            pz = pz - state.jaw_open * 0.15
        elseif part_type == "eye" then
            pz = pz + 0.02
        elseif part_type == "snout" or part_type == "nose" or part_type == "jaw" then
            pz = pz - head_forward
        end

        -- Subtle tilt (X axis)
        local ty = py * cos_tx - pz * sin_tx
        local tz = py * sin_tx + pz * cos_tx

        -- Tilt (Z axis)
        local tx = px * cos_tz - ty * sin_tz
        local ty2 = px * sin_tz + ty * cos_tz

        -- Rotate around Y axis
        local rx = tx * cos_r - tz * sin_r
        local rz = tx * sin_r + tz * cos_r

        local x = center + rx
        local y = 0.25 + ty2 + state.head_bob
        local z = center + rz

        -- Scale and rendering per part type
        local band_idx = ((i - 1) % 5)
        local base_scale = config.base_scale * 0.9
        local part_glow = false
        local part_brightness = 8
        local part_material = "NETHER_BRICKS"
        local part_interpolation = 5

        if part_type == "eye" then
            base_scale = base_scale * 1.1
            base_scale = base_scale + state.eye_glow * 0.5 + audio.bands[5] * 0.4
            band_idx = 4
            part_glow = true
            part_brightness = math.floor(state.eye_glow * 15)
            part_material = "GLOWSTONE"
            part_interpolation = 5
        elseif part_type == "jaw" then
            base_scale = base_scale + audio.bands[1] * 0.3
            band_idx = 0
            part_material = "NETHER_BRICKS"
            part_brightness = math.floor(7 + audio.bands[1] * 5)
            part_interpolation = 5
        elseif part_type == "snout" then
            base_scale = base_scale * 0.95
            base_scale = base_scale + audio.bands[1] * 0.15
            band_idx = 0
            part_material = "NETHER_BRICKS"
            part_brightness = math.floor(7 + audio.bands[1] * 4)
            part_interpolation = 5
        elseif part_type == "nostril" then
            base_scale = base_scale * 0.8
            band_idx = 0
            -- Fire breath effect: on beat with strong bass
            local fire_breath = audio.is_beat and audio.bands[1] > 0.6
            if fire_breath then
                part_glow = true
                part_brightness = 15
                part_material = "GLOWSTONE"
            elseif audio.bands[1] > 0.5 then
                part_glow = true
                part_brightness = math.floor(audio.bands[1] * 12)
                part_material = "GLOWSTONE"
            else
                part_glow = false
                part_brightness = math.floor(audio.bands[1] * 10)
                part_material = "NETHER_BRICKS"
            end
            part_interpolation = 4
        elseif part_type == "horn" then
            base_scale = base_scale * 1.05
            base_scale = base_scale + audio.bands[4] * 0.25
            band_idx = 3
            part_material = "BLACKSTONE"
            part_brightness = math.floor(6 + audio.bands[4] * 6)
            part_interpolation = 6
        elseif part_type == "spine" then
            base_scale = base_scale * 1.1
            base_scale = base_scale + audio.bands[3] * 0.3
            band_idx = 2
            part_material = "OBSIDIAN"
            part_glow = state.beat_intensity > 0.6
            part_brightness = math.floor(5 + audio.bands[3] * 7)
            part_interpolation = 5
        elseif part_type == "cranium" or part_type == "neck" then
            base_scale = base_scale + state.breathe * 0.02
            base_scale = base_scale + audio.bands[((i - 1) % 3) + 1] * 0.15
            part_material = "NETHER_BRICKS"
            part_brightness = math.floor(8 + audio.amplitude * 4)
            part_interpolation = 5
        elseif part_type == "brow" then
            base_scale = base_scale * 1.05
            base_scale = base_scale + audio.bands[3] * 0.2
            band_idx = 2
            part_material = "NETHER_BRICKS"
            part_brightness = math.floor(8 + audio.bands[3] * 4)
            part_interpolation = 5
        elseif part_type == "wing_l" or part_type == "wing_r" then
            base_scale = base_scale * 0.85
            base_scale = base_scale + audio.bands[2] * 0.2 + state.beat_intensity * 0.15
            band_idx = 1
            part_material = "BLACKSTONE"
            part_glow = state.beat_intensity > 0.7
            part_brightness = math.floor(6 + audio.bands[2] * 6)
            part_interpolation = 4
        end

        -- Global beat pulse
        if audio.is_beat then
            base_scale = base_scale * 1.1
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, base_scale),
            band = band_idx,
            visible = true,
            glow = part_glow,
            brightness = clamp(part_brightness, 0, 15),
            material = part_material,
            interpolation = part_interpolation,
        }
    end

    return entities
end
`,
  },
  {
    id: "fist",
    name: "Raised Fist",
    description: "Concert fist pumping on the beat with curled fingers and knuckle glow",
    category: "Epic",
    staticCamera: false,
    startBlocks: 128,
    source: `name = "Raised Fist"
description = "Concert fist pumping on the beat with curled fingers and knuckle glow"
recommended_entities = 128
category = "Epic"
static_camera = false
state = {}

local function fist_slice(y_norm)
    local points = {}

    if y_norm < 0.15 then
        -- Wrist: narrow elliptical cross-section
        local t = y_norm / 0.15
        local rx = 0.06 + t * 0.02
        local rz = 0.05 + t * 0.01
        for angle = -160, 160, 25 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * rx
            local z = -math.cos(rad) * rz
            points[#points + 1] = {x, z, "wrist"}
        end

    elseif y_norm < 0.35 then
        -- Palm base: widening ellipse
        local t = (y_norm - 0.15) / 0.20
        local rx = 0.08 + t * 0.03
        local rz = 0.06 + t * 0.02
        for angle = -150, 150, 20 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * rx
            local z = -math.cos(rad) * rz
            points[#points + 1] = {x, z, "palm"}
        end

    elseif y_norm < 0.50 then
        -- Palm mid: full width
        local t = (y_norm - 0.35) / 0.15
        local rx = 0.11 - t * 0.01
        local rz = 0.08
        for angle = -140, 140, 18 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * rx
            local z = -math.cos(rad) * rz
            points[#points + 1] = {x, z, "palm"}
        end

    elseif y_norm < 0.60 then
        -- Finger base: 4 separate small circles
        local finger_x = {-0.06, -0.02, 0.02, 0.06}
        for fi = 1, 4 do
            local cx = finger_x[fi]
            local r = 0.025
            for angle = 0, 359, 60 do
                local rad = math.rad(angle)
                local x = cx + math.cos(rad) * r
                local z = -math.sin(rad) * r
                points[#points + 1] = {x, z, "finger_" .. (fi - 1)}
            end
        end

    elseif y_norm < 0.85 then
        -- Curled fingers: 4 circles curling inward toward the palm
        local finger_x = {-0.06, -0.02, 0.02, 0.06}
        local finger_len = {0.12, 0.13, 0.12, 0.10}
        local curl_t = (y_norm - 0.60) / 0.25
        for fi = 1, 4 do
            local cx = finger_x[fi]
            local curl_angle = curl_t * math.pi * 0.8
            local fl = finger_len[fi]
            local local_z = -0.08 + (1 - math.cos(curl_angle)) * fl * 0.4
            local r = 0.025 * (1 - curl_t * 0.3)
            for angle = 0, 359, 60 do
                local rad = math.rad(angle)
                local x = cx + math.cos(rad) * r
                local z = local_z + math.sin(rad) * r * 0.5
                points[#points + 1] = {x, z, "finger_" .. (fi - 1)}
            end
        end

    else
        -- Fingertips: tucked against palm front
        local finger_x = {-0.06, -0.02, 0.02, 0.06}
        local t = (y_norm - 0.85) / 0.15
        for fi = 1, 4 do
            local cx = finger_x[fi]
            local r = 0.015
            local base_z = -0.02 + t * 0.02
            for angle = 0, 359, 90 do
                local rad = math.rad(angle)
                local x = cx + math.cos(rad) * r
                local z = base_z + math.sin(rad) * r
                points[#points + 1] = {x, z, "finger_" .. (fi - 1)}
            end
        end
    end

    return points
end

local function generate_fist_points(n)
    local all_points = {}
    local num_slices = math.max(25, math.floor(n / 5))

    -- Collect slice-based points
    for i = 0, num_slices - 1 do
        local y_norm = i / (num_slices - 1)
        local slice_points = fist_slice(y_norm)
        for _, pt in ipairs(slice_points) do
            all_points[#all_points + 1] = {pt[3], pt[1], y_norm, pt[2]}
        end
    end

    -- Thumb: bezier curve wrapping over the fingers
    local thumb_start_x, thumb_start_y, thumb_start_z = 0.09, 0.30, -0.04
    local thumb_ctrl_x, thumb_ctrl_y, thumb_ctrl_z = 0.10, 0.50, -0.07
    local thumb_end_x, thumb_end_y, thumb_end_z = 0.06, 0.55, -0.06
    for i = 0, 7 do
        local t = i / 7
        local inv = 1 - t
        -- Quadratic bezier
        local bx = inv * inv * thumb_start_x + 2 * inv * t * thumb_ctrl_x + t * t * thumb_end_x
        local by = inv * inv * thumb_start_y + 2 * inv * t * thumb_ctrl_y + t * t * thumb_end_y
        local bz = inv * inv * thumb_start_z + 2 * inv * t * thumb_ctrl_z + t * t * thumb_end_z
        local r = 0.025 * (1 - t * 0.2)
        for angle = 0, 359, 60 do
            local rad = math.rad(angle)
            local x = bx + math.cos(rad) * r
            local z = bz + math.sin(rad) * r * 0.6
            all_points[#all_points + 1] = {"thumb", x, by, z}
        end
    end

    -- Knuckle bumps: protruding at finger base
    local knuckle_x = {-0.06, -0.02, 0.02, 0.06}
    for ki = 1, 4 do
        all_points[#all_points + 1] = {"knuckle", knuckle_x[ki], 0.50, -0.10}
        all_points[#all_points + 1] = {"knuckle", knuckle_x[ki], 0.52, -0.09}
    end

    -- Resample to target count
    if #all_points > n then
        local step = #all_points / n
        local selected = {}
        for i = 0, n - 1 do
            local idx = math.floor(i * step) + 1
            if idx <= #all_points then
                selected[#selected + 1] = all_points[idx]
            end
        end
        return selected
    else
        local result = {}
        for _, pt in ipairs(all_points) do
            result[#result + 1] = pt
        end
        local idx = 0
        while #result < n do
            local orig = all_points[(idx % #all_points) + 1]
            result[#result + 1] = {
                orig[1],
                orig[2] + (idx * 0.001) % 0.008,
                orig[3],
                orig[4] + (idx * 0.001) % 0.008,
            }
            idx = idx + 1
        end
        while #result > n do
            result[#result] = nil
        end
        return result
    end
end

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Generate points if needed
    if state.fist_points == nil or #state.fist_points ~= n then
        state.fist_points = generate_fist_points(n)
    end

    -- Init state
    state.rotation = state.rotation or 0
    state.pump_y = state.pump_y or 0
    state.beat_intensity = state.beat_intensity or 0
    state.time = state.time or 0
    state.curl_factor = state.curl_factor or 0.8
    state.breathe = state.breathe or 0
    state.breathe_dir = state.breathe_dir or 1
    state.tilt = state.tilt or 0
    state.twist = state.twist or 0

    state.time = state.time + dt

    -- Slow Y rotation
    state.rotation = state.rotation + 0.08 * dt

    -- Breathing triangle oscillator
    state.breathe = state.breathe + 0.015 * state.breathe_dir * (dt / 0.016)
    if state.breathe > 1.0 then
        state.breathe_dir = -1
    elseif state.breathe < 0.0 then
        state.breathe_dir = 1
    end

    -- Curl tightness reacts to low-mid
    state.curl_factor = smooth(state.curl_factor, 0.8 + audio.bands[2] * 0.15, 0.15, dt)

    -- Pump target: bass drives base offset, beat adds spike
    local pump_target = audio.bands[1] * 0.03
    if audio.is_beat then
        state.pump_y = state.pump_y + 0.04 * audio.peak
        state.beat_intensity = 1.0
        state.tilt = state.tilt + 0.05
        state.twist = state.twist + 0.08
    end
    state.pump_y = smooth(state.pump_y, pump_target, 0.12, dt)
    state.pump_y = decay(state.pump_y, 0.90, dt) + pump_target * 0.1
    state.beat_intensity = decay(state.beat_intensity, 0.90, dt)
    state.tilt = decay(state.tilt, 0.88, dt)
    state.twist = decay(state.twist, 0.9, dt)

    local breathe_scale = 1.0 + (state.breathe * 2 - 1) * 0.01
    local fist_scale = 0.55 + audio.bands[2] * 0.04 + state.beat_intensity * 0.02

    local yaw = state.rotation + math.sin(state.time * 0.3) * 0.04 + state.twist
    local cos_r = math.cos(yaw)
    local sin_r = math.sin(yaw)

    -- Forward tilt on pump
    local tilt_x = state.tilt + math.sin(state.time * 0.5) * 0.03
    local cos_tx = math.cos(tilt_x)
    local sin_tx = math.sin(tilt_x)

    -- Vein pulse factor for wrist
    local vein_pulse = beat_pulse(audio.beat_phase or 0, 1, 6) * 0.3
    local punch_forward = state.pump_y * 0.55 + audio.bands[1] * 0.02

    for i = 1, #state.fist_points do
        local point = state.fist_points[i]
        local part_type = point[1]
        local px = point[2]
        local py_norm = point[3]
        local pz = point[4]

        -- Scale positions
        px = px * fist_scale * breathe_scale
        pz = pz * fist_scale * breathe_scale
        local py = py_norm * 0.40 * fist_scale

        -- Adjust curled fingers based on curl_factor
        if part_type == "finger_0" or part_type == "finger_1"
            or part_type == "finger_2" or part_type == "finger_3" then
            if py_norm >= 0.60 and py_norm < 0.85 then
                local curl_t = (py_norm - 0.60) / 0.25
                local base_curl = curl_t * math.pi * 0.8
                local actual_curl = base_curl * state.curl_factor
                local diff = actual_curl - base_curl
                -- Push z inward more when curl_factor is higher
                pz = pz + math.sin(diff) * 0.02
            end
            -- Spread fingers outward on strong highs for silhouette clarity.
            px = px * (1.0 + audio.bands[4] * 0.12)
        elseif part_type == "knuckle" then
            px = px * (1.0 + audio.bands[1] * 0.08 + state.beat_intensity * 0.1)
            pz = pz - 0.01 - state.beat_intensity * 0.015
        elseif part_type == "thumb" then
            pz = pz - 0.012 - audio.bands[3] * 0.01
        end

        -- Apply forward tilt (x-axis rotation)
        local ty = py * cos_tx - pz * sin_tx
        local tz = py * sin_tx + pz * cos_tx

        -- Apply yaw rotation (y-axis)
        local rx = px * cos_r - tz * sin_r
        local rz = px * sin_r + tz * cos_r

        -- Final world position: fist centered, pointing up
        local x = center + rx
        local y = 0.30 + ty + state.pump_y
        local z = center + rz - punch_forward * (0.25 + (1.0 - py_norm) * 0.75)

        -- Per-part scale, band, material, brightness, interpolation, glow
        local band_idx = (i - 1) % 5
        local base_scale = config.base_scale * 0.9
        local do_glow = false
        local mat = "BLACKSTONE"
        local bright = 7
        local interp = 5

        if part_type == "wrist" then
            band_idx = 0
            mat = "OBSIDIAN"
            bright = 7 + audio.bands[1] * 4
            interp = 5
            base_scale = base_scale + audio.bands[1] * 0.2
            -- Forearm vein pulse
            if i % 3 == 0 then
                base_scale = base_scale + vein_pulse * audio.bands[1]
                if vein_pulse * audio.bands[1] > 0.15 then
                    do_glow = true
                    bright = bright + vein_pulse * 5
                end
            end

        elseif part_type == "palm" then
            band_idx = 1
            mat = "BLACKSTONE"
            bright = 8 + audio.bands[2] * 4
            interp = 5
            base_scale = base_scale + audio.bands[2] * 0.15

        elseif part_type == "finger_0" or part_type == "finger_1"
            or part_type == "finger_2" or part_type == "finger_3" then
            band_idx = 2
            mat = "BLACKSTONE"
            bright = 7 + audio.bands[3] * 5
            interp = 4
            base_scale = base_scale + audio.bands[3] * 0.15
            if state.beat_intensity > 0.6 then
                do_glow = true
            end

        elseif part_type == "thumb" then
            band_idx = 3
            mat = "BLACKSTONE"
            bright = 7 + audio.bands[4] * 4
            interp = 5
            base_scale = base_scale + audio.bands[4] * 0.2

        elseif part_type == "knuckle" then
            band_idx = 4
            mat = "CRYING_OBSIDIAN"
            bright = 8 + state.beat_intensity * 7
            interp = 3
            base_scale = base_scale * 1.15
            base_scale = base_scale + state.beat_intensity * 0.2
            if state.beat_intensity > 0.5 then
                do_glow = true
            end
        end

        -- Fist pump glow cascade: wrist glows brightest, fading toward fingers
        if state.pump_y > 0.02 then
            bright = bright + state.pump_y * 10 * (1 - py_norm)
        end

        -- Global breathe modulation
        base_scale = base_scale * breathe_scale

        -- Beat pulse on all entities
        if state.beat_intensity > 0.3 then
            base_scale = base_scale * (1 + state.beat_intensity * 0.08)
        end

        -- Clamp brightness to valid range
        bright = clamp(math.floor(bright + 0.5), 0, 15)

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, base_scale),
            band = band_idx,
            visible = true,
            glow = do_glow,
            material = mat,
            brightness = bright,
            interpolation = interp,
        }
    end

    return entities
end
`,
  },
  {
    id: "galaxy",
    name: "Galaxy",
    description: "Spiral galaxy - cosmic visualization",
    category: "Epic",
    staticCamera: false,
    startBlocks: 96,
    source: `name = "Galaxy"
description = "Spiral galaxy - cosmic visualization"
recommended_entities = 96
category = "Epic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation or 0
    state.arm_twist = state.arm_twist or 0
    state.core_pulse = state.core_pulse or 0

    -- Galaxy rotation
    state.rotation = state.rotation + (0.2 + audio.peak * 0.3) * dt

    -- Arm twist increases with highs
    state.arm_twist = 2.3 + audio.bands[5] * 2.2 + audio.bands[4] * 0.8

    -- Core pulse on beat
    if audio.is_beat then
        state.core_pulse = 1.0
    end
    state.core_pulse = decay(state.core_pulse, 0.9, dt)

    -- Core entities (dense center) - 20%
    local core_count = math.floor(n / 5)

    -- Spiral arm entities - 80%
    local arm_count = n - core_count
    local num_arms = 2
    if (audio.bands[4] + audio.bands[5]) > 1.0 then
        num_arms = 3
    end

    -- === CORE ===
    for i = 0, core_count - 1 do
        local seed_angle = (i * 137.5) * math.pi / 180
        local seed_radius = math.sqrt(i / core_count) * 0.08
        seed_radius = seed_radius + audio.bands[3] * 0.02

        local angle = seed_angle + state.rotation * 2
        local radius = seed_radius * (1.0 + state.core_pulse * 0.3)

        local x = center + math.cos(angle) * radius
        local z = center + math.sin(angle) * radius
        local y = center + math.sin(seed_angle * 3) * 0.02

        local band_idx = i % 5
        local scale = config.base_scale * 1.2 + audio.bands[band_idx + 1] * 0.3 + state.core_pulse * 0.2

        entities[#entities + 1] = {
            id = string.format("block_%d", i),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, scale),
            rotation = 0,
            band = band_idx,
            visible = true,
        }
    end

    -- === SPIRAL ARMS ===
    local per_arm = math.floor(arm_count / num_arms)

    for arm = 0, num_arms - 1 do
        local arm_offset = arm * math.pi

        for j = 0, per_arm - 1 do
            local idx = core_count + arm * per_arm + j
            if idx >= n then
                break
            end

            -- Position along arm
            local t = j / per_arm

            -- Logarithmic spiral
            local radius = 0.08 + t * 0.32

            -- Spiral angle
            local spiral_angle = arm_offset + state.rotation + t * math.pi * state.arm_twist

            -- Scatter
            local scatter = math.sin(j * 0.5) * 0.02
            radius = radius + scatter + audio.bands[2] * 0.02 * (1 - t)

            local x = center + math.cos(spiral_angle) * radius
            local z = center + math.sin(spiral_angle) * radius

            -- Slight vertical variation
            local y = center + math.sin(spiral_angle * 2) * 0.04 * t

            local band_idx = j % 5
            local bass_react = (1 - t) * audio.bands[1] * 0.3
            local high_react = t * audio.bands[5] * 0.3
            local scale = config.base_scale + bass_react + high_react
            scale = scale + state.core_pulse * 0.15 * (1 - t)

            if audio.is_beat then
                scale = scale * 1.15
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = 0,
                band = band_idx,
                visible = true,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "laser",
    name: "Laser Array",
    description: "Laser beams shooting from center",
    category: "Epic",
    staticCamera: false,
    startBlocks: 64,
    source: `name = "Laser Array"
description = "Laser beams shooting from center"
recommended_entities = 64
category = "Epic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation or 0
    state.flash = state.flash or 0
    if not state.beam_lengths then
        state.beam_lengths = {0, 0, 0, 0, 0, 0, 0, 0}
    end

    -- Rotation speed based on energy
    state.rotation = state.rotation + (0.5 + audio.peak * 2.0) * dt

    -- Flash on beat
    if audio.is_beat then
        state.flash = 1.0
    end
    state.flash = decay(state.flash, 0.85, dt)

    -- Number of beams
    local num_beams = math.min(8, math.max(4, math.floor(n / 8)))
    local points_per_beam = math.floor(n / num_beams)

    for beam = 0, num_beams - 1 do
        -- Beam angle
        local beam_angle = state.rotation + (beam / num_beams) * math.pi * 2

        -- Beam target length based on frequency band
        local band_idx = beam % 5
        local target_length = 0.1 + audio.bands[band_idx + 1] * 0.35
        if audio.is_beat then
            target_length = target_length + 0.1
        end

        -- Smooth beam extension
        local bl_idx = beam + 1
        state.beam_lengths[bl_idx] = smooth(state.beam_lengths[bl_idx], target_length, 0.3, dt)
        local beam_length = state.beam_lengths[bl_idx]

        -- Beam tilt (elevation angle)
        local tilt = (beam % 3 - 1) * 0.3

        for j = 0, points_per_beam - 1 do
            local idx = beam * points_per_beam + j
            if idx >= n then
                break
            end

            -- Position along beam
            local t = (j + 1) / points_per_beam
            local distance = t * beam_length

            -- 3D position
            local x = center + math.cos(beam_angle) * distance * math.cos(tilt)
            local z = center + math.sin(beam_angle) * distance * math.cos(tilt)
            local y = center + distance * math.sin(tilt)

            -- Scale - thinner at ends
            local thickness = 1.0 - t * 0.5
            local scale = config.base_scale * thickness + state.flash * 0.2

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "mushroom",
    name: "Mushroom",
    description: "Psychedelic toadstool with spots, gills, and spores",
    category: "Epic",
    staticCamera: false,
    startBlocks: 96,
    source: `-- Pattern metadata
name = "Mushroom"
description = "Psychedelic toadstool with spots, gills, and spores"
recommended_entities = 96
category = "Epic"
static_camera = false

-- Per-instance state
state = {
    rotation = 0.0,
    pulse = 0.0,
    glow = 0.0,
    breathe = 0.0,
    breathe_dir = 1,
    wobble = 0.0,
    spore_time = 0.0,
    grow = 1.0,
    cap_tilt = 0.0,
    stem_sway = 0.0,
    -- Pre-generate spot positions (golden angle spacing)
    spot_angles = {},
    spot_phis = {},
}

-- Initialize spot positions
local function init_spots()
    if #state.spot_angles == 0 then
        for i = 0, 6 do
            state.spot_angles[i + 1] = i * 2.39996
            state.spot_phis[i + 1] = 0.3 + (i % 3) * 0.2
        end
    end
end

-- Main calculation function
function calculate(audio, config, dt)
    init_spots()

    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Rotation - faster with amplitude
    state.rotation = state.rotation + (0.2 + audio.amplitude * 0.4) * dt

    -- Breathing animation (slow oscillation)
    state.breathe = state.breathe + 0.02 * state.breathe_dir * (dt / 0.016)
    if state.breathe > 1.0 then
        state.breathe_dir = -1
    elseif state.breathe < 0.0 then
        state.breathe_dir = 1
    end

    -- Wobble on beat
    if audio.is_beat then
        state.pulse = 1.0
        state.glow = 1.0
        state.wobble = 0.15 * audio.amplitude
        state.grow = 1.15
        state.cap_tilt = 0.08 + audio.amplitude * 0.08
    end
    state.pulse = decay(state.pulse, 0.92, dt)
    state.glow = decay(state.glow, 0.93, dt)
    state.wobble = decay(state.wobble, 0.9, dt)
    state.grow = 1.0 + decay(state.grow - 1.0, 0.95, dt)
    state.cap_tilt = decay(state.cap_tilt, 0.92, dt)

    state.spore_time = state.spore_time + dt

    -- Calculate wobble offset
    local wobble_x = math.sin(state.spore_time * 2) * state.wobble
    local wobble_z = math.cos(state.spore_time * 2.5) * state.wobble * 0.7
    state.stem_sway = math.sin(state.spore_time * 1.1) * 0.03 + audio.bands[2] * 0.04
    local cap_offset_x = math.sin(state.rotation * 0.8) * state.cap_tilt + state.stem_sway * 0.6
    local cap_offset_z = math.cos(state.rotation * 0.6) * state.cap_tilt * 0.8 + state.stem_sway * 0.3

    -- Allocate entities: 20% stem, 42% cap, 15% gills, 10% spots, 7% rim, rest spores
    local stem_count = math.max(6, math.floor(n / 5))
    local cap_count = math.max(10, math.floor(n * 0.42))
    local gill_count = math.max(6, math.floor(n * 0.15))
    local spot_count = math.max(5, math.floor(n / 10))
    local rim_count = math.max(4, math.floor(n / 14))
    local spore_count = n - stem_count - cap_count - gill_count - spot_count - rim_count
    if spore_count < 0 then
        cap_count = math.max(6, cap_count + spore_count)
        spore_count = 0
    end

    local entity_idx = 0
    local breathe_scale = 1.0 + state.breathe * 0.05

    -- === STEM (organic with varied heights) ===
    local stem_radius = 0.06 * breathe_scale + state.pulse * 0.015
    local stem_height = 0.34 * state.grow

    for i = 0, stem_count - 1 do
        -- Use golden angle for even distribution around cylinder
        local golden_angle = 2.39996323
        local angle_base = i * golden_angle

        -- Vertical position with variation
        local base_t = i / stem_count
        local height_variation = math.sin(angle_base * 3) * 0.04 + math.cos(angle_base * 5) * 0.02
        local y_t = base_t + height_variation
        y_t = clamp(y_t, 0, 1)

        local ring_y = 0.06 + y_t * stem_height

        -- Radius varies with height: bulge at base, taper at top
        local taper = 1.0 - y_t * 0.3 + math.sin(y_t * math.pi) * 0.2
        local radius_variation = 1.0 + math.sin(i * 1.7) * 0.1
        local current_radius = stem_radius * taper * radius_variation

        -- Spiral twist increases with height
        local twist = y_t * math.pi * 0.6
        local angle = state.rotation + twist + angle_base

        local sway_amount = state.stem_sway * (0.2 + y_t * 0.8)
        local x = center + math.cos(angle) * current_radius + wobble_x * y_t + sway_amount
        local z = center + math.sin(angle) * current_radius + wobble_z * y_t + sway_amount * 0.6
        local y = ring_y

        -- Vary band assignment for color variation in stem
        local band_idx = 1 + (i % 2)  -- Alternate between bands 1 and 2 (0-indexed: 1 and 2)
        local base_s = config.base_scale * (0.6 + y_t * 0.3)  -- Smaller at base, larger at top
        local scale = base_s + audio.bands[band_idx + 1] * 0.2  -- +1 for 1-indexed bands

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx,
            visible = true,
            glow = false,
            brightness = math.min(15, 6 + math.floor(state.pulse * 6)),
            material = "MUSHROOM_STEM",
            interpolation = 8,
        }
        entity_idx = entity_idx + 1
    end

    -- === CAP (dome with classic toadstool shape) ===
    local cap_base_y = 0.08 + stem_height
    local cap_radius = (0.22 + state.pulse * 0.06 + audio.bands[1] * 0.04) * breathe_scale * state.grow
    local layers = math.max(4, math.floor(math.sqrt(cap_count)))
    local points_placed = 0

    for layer = 0, layers - 1 do
        if points_placed >= cap_count then
            break
        end

        local layer_t = layer / math.max(1, layers - 1)
        -- Classic toadstool: flatter on top, curves down at edges
        local phi = layer_t * (math.pi * 0.55)
        local layer_radius = cap_radius * math.sin(phi)
        -- Flatten top, curve down at edge
        local height_factor = math.cos(phi) * 0.5 + (1 - layer_t) * 0.15
        local ripple = math.sin(state.rotation * 1.8 + layer * 0.8) * audio.bands[3] * 0.025
        local layer_y = cap_base_y + cap_radius * height_factor + ripple

        local points_this_layer = math.max(4, math.floor(6 + layer * 4))

        for j = 0, points_this_layer - 1 do
            if points_placed >= cap_count then
                break
            end

            local angle = state.rotation * 0.4 + (j / points_this_layer) * math.pi * 2
            local x = center + math.cos(angle) * layer_radius + wobble_x + cap_offset_x
            local z = center + math.sin(angle) * layer_radius + wobble_z + cap_offset_z
            local y = layer_y

            local band_idx = 0  -- Cap uses bass
            local scale = config.base_scale * 1.1 + audio.bands[1] * 0.4 + state.glow * 0.25

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
                glow = state.pulse > 0.7,
                brightness = math.min(15, 8 + math.floor(audio.bands[1] * 5)),
                material = "RED_MUSHROOM_BLOCK",
                interpolation = 6,
            }
            entity_idx = entity_idx + 1
            points_placed = points_placed + 1
        end
    end

    -- === RIM (lip around cap edge) ===
    local rim_radius = cap_radius * 0.92
    local rim_drop = 0.02 + audio.bands[3] * 0.03
    for r = 0, rim_count - 1 do
        local angle = state.rotation * 0.5 + (r / rim_count) * math.pi * 2
        local lip_wave = math.sin(angle * 2 + state.rotation) * 0.01
        local x = center + math.cos(angle) * rim_radius + cap_offset_x
        local z = center + math.sin(angle) * rim_radius + cap_offset_z
        local y = cap_base_y + cap_radius * 0.12 - rim_drop + lip_wave

        local band_idx = 2
        local scale = config.base_scale * 0.8 + audio.bands[3] * 0.25 + state.glow * 0.2

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx,
            visible = true,
            glow = false,
            brightness = math.min(15, 7 + math.floor(audio.bands[3] * 5)),
            material = "RED_MUSHROOM_BLOCK",
            interpolation = 5,
        }
        entity_idx = entity_idx + 1
    end

    -- === GILLS (radial lines under cap) ===
    local gill_y = cap_base_y - 0.02 - audio.bands[2] * 0.015
    local num_gill_lines = math.max(4, math.floor(gill_count / 3))
    local points_per_gill = math.floor(gill_count / num_gill_lines)

    for g = 0, num_gill_lines - 1 do
        local gill_angle = state.rotation * 0.4 + (g / num_gill_lines) * math.pi * 2

        for p = 0, points_per_gill - 1 do
            if entity_idx >= stem_count + cap_count + gill_count then
                break
            end

            -- Gills extend from stem to cap edge
            local t = (p + 1) / (points_per_gill + 1)
            local r = stem_radius + t * (cap_radius * 0.85 - stem_radius)

            local x = center + math.cos(gill_angle) * r + wobble_x + cap_offset_x * 0.4
            local z = center + math.sin(gill_angle) * r + wobble_z + cap_offset_z * 0.4
            local y = gill_y - t * (0.03 + audio.bands[2] * 0.02)  -- Slight droop

            local band_idx = 3  -- Gills use mid-high
            local scale = config.base_scale * 0.5 + audio.bands[4] * 0.22

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
                glow = false,
                brightness = math.min(15, 5 + math.floor(audio.bands[4] * 5)),
                material = "MUSHROOM_STEM",
                interpolation = 5,
            }
            entity_idx = entity_idx + 1
        end
    end

    -- === SPOTS (white dots on cap - classic Amanita) ===
    for s = 0, math.min(spot_count, #state.spot_angles) - 1 do
        local spot_phi = state.spot_phis[s + 1] * (math.pi * 0.4)
        local spot_r = cap_radius * 0.85 * math.sin(spot_phi)
        local spot_y = cap_base_y + cap_radius * math.cos(spot_phi) * 0.5 + 0.02

        local spot_angle = state.rotation * 0.4 + state.spot_angles[s + 1]
        local x = center + math.cos(spot_angle) * spot_r + wobble_x + cap_offset_x
        local z = center + math.sin(spot_angle) * spot_r + wobble_z + cap_offset_z
        local y = spot_y

        local band_idx = 4  -- Spots use high freq
        local scale = config.base_scale * 0.9 + state.glow * 0.4 + audio.bands[5] * 0.3

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx,
            visible = true,
            glow = state.glow > 0.3,
            brightness = math.min(15, math.floor(10 + state.glow * 5)),
            material = state.glow > 0.5 and "GLOWSTONE" or "WHITE_CONCRETE",
            interpolation = 5,
        }
        entity_idx = entity_idx + 1
    end

    -- === SPORES (floating particles rising up) ===
    for sp = 0, spore_count - 1 do
        -- Each spore has unique phase
        local phase = sp * 1.618 + state.spore_time
        local spore_life = (phase % 3.0) / 3.0  -- 0-1 lifecycle

        -- Spiral upward from cap
        local spore_angle = phase * 2.0 + sp
        local spore_r = 0.05 + spore_life * 0.15 + math.sin(phase * 3) * 0.03
        local spore_y = cap_base_y + 0.1 + spore_life * 0.4 + audio.bands[5] * 0.05

        local x = center + math.cos(spore_angle) * spore_r + wobble_x * 0.5 + cap_offset_x * 0.6
        local z = center + math.sin(spore_angle) * spore_r + wobble_z * 0.5 + cap_offset_z * 0.6
        local y = spore_y

        local band_idx = 4  -- Spores use high-mid
        -- Fade in then out
        local fade = math.sin(spore_life * math.pi)
        local scale = config.base_scale * 0.3 * fade + audio.bands[5] * 0.15 * fade

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, math.max(0.01, scale)),
            band = band_idx,
            visible = true,
            glow = fade > 0.5 and state.glow > 0.2,
            brightness = math.min(15, math.floor(fade * 8 + state.glow * 7)),
            material = state.glow > 0.4 and "GLOWSTONE" or "YELLOW_CONCRETE",
            interpolation = 3,
        }
        entity_idx = entity_idx + 1
    end

    -- Fill any remainder with additional floating spores so high counts stay visually dense.
    while entity_idx < n do
        local sp = entity_idx
        local phase = sp * 1.618 + state.spore_time
        local spore_life = (phase % 3.0) / 3.0
        local spore_angle = phase * 2.0 + sp
        local spore_r = 0.05 + spore_life * 0.16 + math.sin(phase * 3.2) * 0.03
        local spore_y = cap_base_y + 0.08 + spore_life * 0.42 + audio.bands[5] * 0.06
        local x = center + math.cos(spore_angle) * spore_r + wobble_x * 0.5 + cap_offset_x * 0.6
        local z = center + math.sin(spore_angle) * spore_r + wobble_z * 0.5 + cap_offset_z * 0.6
        local fade = math.max(0.15, math.sin(spore_life * math.pi))
        local scale = config.base_scale * 0.28 * fade + audio.bands[5] * 0.14 * fade

        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(x, 0, 1),
            y = clamp(spore_y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, math.max(0.01, scale)),
            band = 4,
            visible = true,
            glow = fade > 0.55 and state.glow > 0.2,
        }
        entity_idx = entity_idx + 1
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "phoenix",
    name: "Phoenix",
    description: "Fiery phoenix in flight with flapping wings and fire trail",
    category: "Epic",
    staticCamera: false,
    startBlocks: 128,
    source: `-- Pattern metadata
name = "Phoenix"
description = "Fiery phoenix in flight with flapping wings and fire trail"
recommended_entities = 128
category = "Epic"
static_camera = false

-- Per-instance state
state = {
    time = 0,
    flap_phase = 0,
    hover_y = 0.45,
    beat_intensity = 0,
    body_scale = 1.0,
    rotation = 0,
    smooth_bands = {0, 0, 0, 0, 0},
}

local function allocate_counts(total, weights)
    local counts = {}
    local fracs = {}
    local weight_sum = 0
    for k, v in pairs(weights) do
        weight_sum = weight_sum + v
    end
    local used = 0
    for k, v in pairs(weights) do
        local exact = (total * v) / weight_sum
        local base = math.floor(exact)
        counts[k] = base
        fracs[#fracs + 1] = {k = k, frac = exact - base}
        used = used + base
    end
    table.sort(fracs, function(a, b) return a.frac > b.frac end)
    local rem = total - used
    local idx = 1
    while rem > 0 and #fracs > 0 do
        local key = fracs[idx].k
        counts[key] = counts[key] + 1
        rem = rem - 1
        idx = idx + 1
        if idx > #fracs then
            idx = 1
        end
    end
    return counts
end

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count

    -- Update time
    state.time = state.time + dt

    -- Smooth bands for fluid motion
    for i = 1, 5 do
        state.smooth_bands[i] = smooth(state.smooth_bands[i], audio.bands[i], 0.3, dt)
    end

    -- Wing flap: advance phase, speed up with bass
    local flap_speed = 2 + state.smooth_bands[1] * 3
    state.flap_phase = state.flap_phase + flap_speed * dt

    -- Hover bob
    state.hover_y = 0.45 + math.sin(state.time * 1.2) * 0.02 + audio.amplitude * 0.03

    -- Slow Y rotation
    state.rotation = state.rotation + 0.15 * dt

    -- Beat response
    if audio.is_beat then
        state.beat_intensity = 1.0
        state.body_scale = 1.12
        state.flap_phase = math.pi * 0.5 -- snap wings to full extension
    end
    state.beat_intensity = decay(state.beat_intensity, 0.90, dt)
    state.body_scale = 1.0 + decay(state.body_scale - 1.0, 0.92, dt)

    -- Allocate entities by weighted parts (adaptive across low/high budgets).
    local parts = allocate_counts(n, {
        body = 16,
        head = 8,
        lwing = 23,
        rwing = 23,
        tail = 20,
        crest = 6,
        fire = 4,
    })
    local body_count = parts.body
    local head_count = parts.head
    local lwing_count = parts.lwing
    local rwing_count = parts.rwing
    local tail_count = parts.tail
    local crest_count = parts.crest
    local fire_count = parts.fire

    local entity_idx = 0
    local center_x = 0.5
    local center_y = state.hover_y
    local center_z = 0.5
    local cos_r = math.cos(state.rotation)
    local sin_r = math.sin(state.rotation)

    -- Helper: rotate point around Y axis then offset to world center
    local function to_world(lx, ly, lz)
        local rx = lx * cos_r - lz * sin_r
        local rz = lx * sin_r + lz * cos_r
        return clamp(center_x + rx), clamp(ly), clamp(center_z + rz)
    end

    -- Helper: add entity (every entity gets material, brightness, interpolation)
    local function add_entity(lx, ly, lz, s, band, opts)
        local wx, wy, wz = to_world(lx, ly, lz)
        opts = opts or {}
        local bright = opts.brightness or 8
        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = wx,
            y = wy,
            z = wz,
            scale = math.min(config.max_scale, math.max(0.01, s)),
            band = band,
            visible = true,
            material = opts.material or "GOLD_BLOCK",
            brightness = clamp(math.floor(bright), 0, 15),
            interpolation = opts.interpolation or 5,
            glow = opts.glow or false,
        }
        entity_idx = entity_idx + 1
    end

    -- Flap displacement: outer points get more vertical movement
    local flap_base = math.sin(state.flap_phase) * 0.04 * (1 + audio.amplitude * 0.5)

    -- === BODY CORE (fibonacci ellipsoid) ===
    if not state.body_points or #state.body_points ~= body_count then
        state.body_points = fibonacci_sphere(body_count)
    end
    local body_points = state.body_points
    local body_rx = 0.04 * state.body_scale
    local body_ry = 0.08 * state.body_scale
    local body_rz = 0.035 * state.body_scale

    for i = 1, body_count do
        local pt = body_points[i]
        local lx = pt.x * body_rx
        local ly = center_y + pt.y * body_ry
        local lz = pt.z * body_rz

        local s = config.base_scale * 0.9 + state.smooth_bands[3] * 0.2 + state.beat_intensity * 0.15
        add_entity(lx, ly, lz, s, 2, {
            material = "GOLD_BLOCK",
            brightness = 8 + state.smooth_bands[3] * 5,
            interpolation = 5,
            glow = state.beat_intensity > 0.6,
        })
    end

    -- === HEAD + BEAK ===
    local head_y = center_y + body_ry + 0.03
    local head_r = 0.025
    local head_sphere_count = 0
    if head_count > 0 then
        head_sphere_count = math.max(1, math.floor(head_count * 0.7))
    end
    local beak_count = head_count - head_sphere_count

    -- Head sphere (cached)
    if not state.head_points or #state.head_points ~= head_sphere_count then
        state.head_points = fibonacci_sphere(head_sphere_count)
    end
    local head_points = state.head_points
    local head_bob = state.smooth_bands[3] * 0.02

    for i = 1, head_sphere_count do
        local pt = head_points[i]
        local lx = pt.x * head_r
        local ly = head_y + head_bob + pt.y * head_r
        local lz = pt.z * head_r

        local s = config.base_scale * 0.7 + state.smooth_bands[3] * 0.15
        add_entity(lx, ly, lz, s, 2, {
            material = "GOLD_BLOCK",
            brightness = 9 + state.smooth_bands[3] * 4,
            interpolation = 5,
        })
    end

    -- Beak: 2-3 points extending forward (-z direction), narrowing
    for b = 0, beak_count - 1 do
        local t = (b + 1) / (beak_count + 1)
        local lx = 0
        local ly = head_y + head_bob - 0.005 * t
        local lz = -head_r - t * 0.04

        local s = config.base_scale * (0.5 - t * 0.2)
        add_entity(lx, ly, lz, s, 2, {
            material = "ORANGE_CONCRETE",
            brightness = 7,
            interpolation = 5,
        })
    end

    -- === WING GENERATION ===
    -- Quadratic bezier helper
    local function bezier2(t, p0, p1, p2)
        local u = 1 - t
        return u * u * p0 + 2 * u * t * p1 + t * t * p2
    end

    -- Generate one wing (side = -1 for left, +1 for right)
    local function build_wing(side, wing_count)
        local feather_lines = 6
        local pts_per_feather = math.max(2, math.floor(wing_count / feather_lines))
        local placed = 0

        for f = 0, feather_lines - 1 do
            if placed >= wing_count then break end

            local spread = -20 + f * 35 -- fan from -20 to 155 degrees
            local feather_length = 0.15 + f * 0.02

            -- Root at body side
            local root_x = side * 0.04
            local root_y = center_y
            local root_z = 0

            -- Control point: sweeps outward and slightly back
            local ctrl_x = side * (0.15 + f * 0.02)
            local ctrl_y = center_y + math.sin(math.rad(spread)) * 0.04
            local ctrl_z = -0.04

            -- Tip: extends far out
            local tip_x = side * (0.25 + f * 0.02)
            local tip_y = center_y + math.sin(math.rad(spread)) * 0.08 - 0.02
            local tip_z = -0.08 - f * 0.01

            local actual_pts = math.min(pts_per_feather, wing_count - placed)
            for p = 0, actual_pts - 1 do
                local t = p / math.max(1, actual_pts - 1)

                local lx = bezier2(t, root_x, ctrl_x, tip_x)
                local ly = bezier2(t, root_y, ctrl_y, tip_y)
                local lz = bezier2(t, root_z, ctrl_z, tip_z)

                -- Wing flap: outer points displaced more
                local flap_amount = flap_base * t * (0.6 + f * 0.08)
                ly = ly + flap_amount

                -- Wing material gradient: inner gold, mid orange, outer red
                local wing_mat
                if t < 0.33 then
                    wing_mat = "GOLD_BLOCK"
                elseif t < 0.66 then
                    wing_mat = "ORANGE_CONCRETE"
                else
                    wing_mat = "REDSTONE_BLOCK"
                end

                local wing_glow = state.beat_intensity > 0.5 and t > 0.5

                -- Beat flash: outer tips get max brightness on beat
                local wing_bright = 7 + state.smooth_bands[1] * 6
                if state.beat_intensity > 0.5 and t > 0.6 then
                    wing_bright = 15
                end

                local s = config.base_scale * (0.7 + t * 0.3) + state.smooth_bands[1] * 0.25 * t
                add_entity(lx, ly, lz, s, 0, {
                    material = wing_mat,
                    brightness = wing_bright,
                    interpolation = 4,
                    glow = wing_glow,
                })
                placed = placed + 1
            end
        end

        -- Fill remaining if any
        while placed < wing_count do
            local t = placed / wing_count
            local lx = side * (0.04 + t * 0.2)
            local ly = center_y + flap_base * t
            local lz = -t * 0.06
            add_entity(lx, ly, lz, config.base_scale * 0.5, 0, {
                material = "ORANGE_CONCRETE",
                brightness = 7 + state.smooth_bands[1] * 4,
                interpolation = 4,
            })
            placed = placed + 1
        end
    end

    -- Left wing (negative X)
    build_wing(-1, lwing_count)

    -- Right wing (positive X)
    build_wing(1, rwing_count)

    -- === TAIL FEATHERS ===
    local tail_streams = 5
    local pts_per_stream = math.max(2, math.floor(tail_count / tail_streams))
    local tail_placed = 0
    local tail_length_base = 0.12 + state.smooth_bands[1] * 0.06
    local tail_curve = 0.06 + state.smooth_bands[2] * 0.04

    for s = 0, tail_streams - 1 do
        if tail_placed >= tail_count then break end

        -- Fan spread across tail streams: center stream is longest
        local stream_t = s / math.max(1, tail_streams - 1)
        local fan_angle = (stream_t - 0.5) * 0.6 -- -0.3 to 0.3 radians lateral spread
        local length_factor = 1.0 - math.abs(stream_t - 0.5) * 0.6 -- center longer

        local actual_pts = math.min(pts_per_stream, tail_count - tail_placed)
        for p = 0, actual_pts - 1 do
            local t = p / math.max(1, actual_pts - 1)

            -- Origin at body bottom-back
            local lx = math.sin(fan_angle) * t * 0.08
            local ly = center_y - body_ry * 0.5 - t * tail_curve * length_factor
            local lz = body_rz + t * tail_length_base * length_factor

            -- Gentle wave along tail
            local wave = math.sin(state.time * 2 + t * math.pi + s) * 0.01 * t
            lx = lx + wave

            -- Tail material gradient: gold at root, red at tips
            local tail_mat = t < 0.5 and "GOLD_BLOCK" or "REDSTONE_BLOCK"

            -- Tail tips glow softly, intensify on beat
            local tail_bright = 8 + state.smooth_bands[1] * 5
            if t > 0.6 then
                -- Soft ember glow on tips, intensified by beat
                tail_bright = 6 + state.smooth_bands[1] * 4 + state.beat_intensity * 5
            end

            local sc = config.base_scale * (0.8 - t * 0.3) + state.smooth_bands[1] * 0.2 * (1 - t * 0.5)
            add_entity(lx, ly, lz, sc, 1, {
                material = tail_mat,
                brightness = tail_bright,
                interpolation = 4,
                glow = t > 0.6,
            })
            tail_placed = tail_placed + 1
        end
    end

    -- Fill remaining tail
    while tail_placed < tail_count do
        local t = tail_placed / tail_count
        local lx = math.sin(t * 3) * 0.02
        local ly = center_y - body_ry * 0.5 - t * tail_curve
        local lz = body_rz + t * tail_length_base * 0.5
        add_entity(lx, ly, lz, config.base_scale * 0.4, 1, {
            material = t > 0.5 and "REDSTONE_BLOCK" or "GOLD_BLOCK",
            brightness = 7 + state.smooth_bands[1] * 4,
            interpolation = 4,
            glow = t > 0.6,
        })
        tail_placed = tail_placed + 1
    end

    -- === CREST (fan above/behind head) ===
    local crest_base_y = head_y + head_r * 0.5
    local crest_spread = 0.5 + state.smooth_bands[4] * 0.3

    for c = 0, crest_count - 1 do
        local t = c / math.max(1, crest_count - 1)
        local fan_t = (t - 0.5) * 2 -- -1 to 1

        -- Fan above and behind head, curving backward
        local lx = fan_t * 0.03 * crest_spread
        local ly = crest_base_y + (1 - math.abs(fan_t)) * 0.04
        local lz = 0.01 + math.abs(fan_t) * 0.02 -- curve backward

        -- Audio-reactive height
        ly = ly + state.smooth_bands[4] * 0.015

        local s = config.base_scale * 0.55 + state.smooth_bands[4] * 0.2
        add_entity(lx, ly, lz, s, 4, {
            material = "ORANGE_CONCRETE",
            brightness = 10 + state.smooth_bands[4] * 5,
            interpolation = 4,
            glow = true,
        })
    end

    -- === FIRE TRAIL (phase-cycling below tail on beat) ===
    local fire_visible = state.beat_intensity > 0.2

    for f = 0, fire_count - 1 do
        local t = f / math.max(1, fire_count - 1)

        -- Cycle position with beat phase
        local phase = state.time * 3 + t * math.pi * 2
        local lx = math.sin(phase) * 0.03
        local ly = center_y - body_ry - 0.04 - t * 0.08
        local lz = body_rz + 0.04 + t * 0.06

        -- Flicker
        local flicker = 0.7 + simple_noise(t * 10, state.time * 5, 0) * 0.3

        -- Flickering material: cycle between GLOWSTONE and REDSTONE_BLOCK
        local fire_noise = simple_noise(t * 7, state.time * 3, 0.5)
        local fire_mat = fire_noise > 0 and "GLOWSTONE" or "REDSTONE_BLOCK"

        local s = config.base_scale * 0.6 * flicker * state.beat_intensity
        local bright = math.floor(state.beat_intensity * 15)

        add_entity(lx, ly, lz, s, 0, {
            material = fire_mat,
            brightness = bright,
            interpolation = 3,
            glow = fire_visible,
        })
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "pyramid",
    name: "Pyramid",
    description: "Egyptian pyramid - inverts on drops",
    category: "Epic",
    staticCamera: false,
    startBlocks: 64,
    source: `name = "Pyramid"
description = "Egyptian pyramid - inverts on drops"
recommended_entities = 64
category = "Epic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation or 0
    state.invert = state.invert or 0
    state.hover = state.hover or 0

    -- Slow majestic rotation
    state.rotation = state.rotation + (0.3 + audio.peak * 0.35) * dt

    -- Invert on strong beats
    if audio.is_beat and audio.beat_intensity > 0.6 then
        if state.invert > 0.5 then
            state.invert = 0
        else
            state.invert = 1
        end
    end

    -- Hover with bass
    local target_hover = audio.bands[1] * 0.1 + audio.bands[2] * 0.05
    state.hover = smooth(state.hover, target_hover, 0.1, dt)

    local cos_r = math.cos(state.rotation)
    local sin_r = math.sin(state.rotation)

    -- Pyramid geometry
    local half = 0.35
    local base_y = 0.15 + state.hover
    local apex_y = 0.85 + state.hover

    if state.invert > 0.5 then
        base_y, apex_y = apex_y, base_y
    end

    -- 4 base corners (rotated)
    local raw = {
        {-half, -half},
        { half, -half},
        { half,  half},
        {-half,  half},
    }
    local corners = {}
    for i, c in ipairs(raw) do
        corners[i] = {
            c[1] * cos_r - c[2] * sin_r,
            c[1] * sin_r + c[2] * cos_r,
        }
    end

    local entity_idx = 0

    local function place(rx, rz, y, band, extra_scale)
        if entity_idx >= n then return end
        local scale = config.base_scale + audio.bands[band + 1] * 0.4 + (extra_scale or 0)
        if audio.is_beat then scale = scale * 1.25 end
        entities[#entities + 1] = {
            id = string.format("block_%d", entity_idx),
            x = clamp(center + rx),
            y = clamp(y),
            z = clamp(center + rz),
            scale = math.min(config.max_scale, scale),
            rotation = (state.rotation * 57.3) % 360,
            band = band,
            visible = true,
        }
        entity_idx = entity_idx + 1
    end

    -- Entity budget: base edges 35%, slant edges 30%, horizontal layers 35%
    local base_per_edge = math.max(2, math.floor(n * 0.088))
    local slant_per_edge = math.max(2, math.floor(n * 0.075))
    local edge_total = base_per_edge * 4 + slant_per_edge * 4
    local layer_budget = math.max(0, n - edge_total)

    -- === BASE EDGES (prominent, bass-reactive) ===
    for edge = 1, 4 do
        local c1 = corners[edge]
        local c2 = corners[(edge % 4) + 1]
        for i = 0, base_per_edge - 1 do
            local t = base_per_edge > 1 and (i / (base_per_edge - 1)) or 0.5
            local rx = lerp(c1[1], c2[1], t)
            local rz = lerp(c1[2], c2[2], t)
            place(rx, rz, base_y, 0, audio.bands[1] * 0.2)
        end
    end

    -- === SLANT EDGES (corners to apex) ===
    for edge = 1, 4 do
        local c = corners[edge]
        for i = 0, slant_per_edge - 1 do
            local t = slant_per_edge > 1 and (i / (slant_per_edge - 1)) or 0.5
            local rx = c[1] * (1 - t)
            local rz = c[2] * (1 - t)
            local y = lerp(base_y, apex_y, t)
            local band = ((edge - 1) % 4) + 1
            -- Apex glow
            local apex_boost = t > 0.85 and audio.peak * 0.3 or 0
            place(rx, rz, y, band, apex_boost)
        end
    end

    -- === HORIZONTAL LAYER RINGS ===
    if layer_budget > 0 then
        local num_layers = math.max(1, math.min(4, math.floor(layer_budget / 8)))
        local per_layer = math.floor(layer_budget / num_layers)

        for layer = 1, num_layers do
            local t = layer / (num_layers + 1)
            local layer_size = 1.0 - t
            local y = lerp(base_y, apex_y, t)
            local lh = half * layer_size

            -- Warp with mid frequencies
            local warp = math.sin(state.rotation * 2 + layer * 1.2) * audio.bands[3] * 0.03
            y = y + warp

            -- Layer corners (rotated)
            local lc = {}
            for i, c in ipairs(raw) do
                lc[i] = {
                    (c[1] * layer_size) * cos_r - (c[2] * layer_size) * sin_r,
                    (c[1] * layer_size) * sin_r + (c[2] * layer_size) * cos_r,
                }
            end

            -- Distribute points evenly around the 4 edges
            local per_edge = math.max(1, math.floor(per_layer / 4))
            for edge = 1, 4 do
                local c1 = lc[edge]
                local c2 = lc[(edge % 4) + 1]
                for i = 0, per_edge - 1 do
                    local et = per_edge > 1 and (i / (per_edge - 1)) or 0.5
                    local rx = lerp(c1[1], c2[1], et)
                    local rz = lerp(c1[2], c2[2], et)
                    local band = (edge + layer) % 5
                    place(rx, rz, y, band, 0)
                end
            end
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "sacred",
    name: "Sacred Geometry",
    description: "Morphing platonic solids - icosahedron",
    category: "Epic",
    staticCamera: false,
    startBlocks: 96,
    source: `name = "Sacred Geometry"
description = "Morphing platonic solids - icosahedron"
category = "Epic"
static_camera = false
start_blocks = 96
state = {}

local PHI = (1 + math.sqrt(5)) / 2

local function icosahedron_vertices()
    local vertices = {}
    for _, x in ipairs({-1, 1}) do
        for _, y in ipairs({-PHI, PHI}) do
            vertices[#vertices + 1] = {x, y, 0}
        end
    end
    for _, y in ipairs({-1, 1}) do
        for _, z in ipairs({-PHI, PHI}) do
            vertices[#vertices + 1] = {0, y, z}
        end
    end
    for _, x in ipairs({-PHI, PHI}) do
        for _, z in ipairs({-1, 1}) do
            vertices[#vertices + 1] = {x, 0, z}
        end
    end
    return vertices
end

local function icosahedron_edge_midpoints(vertices)
    local edges = {}
    local threshold = 2.1
    for i = 1, #vertices do
        for j = i + 1, #vertices do
            local v1, v2 = vertices[i], vertices[j]
            local dx = v1[1] - v2[1]
            local dy = v1[2] - v2[2]
            local dz = v1[3] - v2[3]
            local dist = math.sqrt(dx * dx + dy * dy + dz * dz)
            if dist < threshold then
                edges[#edges + 1] = {
                    (v1[1] + v2[1]) / 2,
                    (v1[2] + v2[2]) / 2,
                    (v1[3] + v2[3]) / 2,
                }
            end
        end
    end
    return edges
end

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation_x = state.rotation_x or 0
    state.rotation_y = state.rotation_y or 0
    state.rotation_z = state.rotation_z or 0
    state.morph = state.morph or 0
    state.pulse = state.pulse or 0

    -- Generate points
    local vertices = icosahedron_vertices()
    local edges = icosahedron_edge_midpoints(vertices)
    local all_points = {}
    for _, v in ipairs(vertices) do
        all_points[#all_points + 1] = v
    end
    for _, e in ipairs(edges) do
        all_points[#all_points + 1] = e
    end

    -- Rotation
    local speed_mult = 1.0 + audio.peak
    state.rotation_y = state.rotation_y + (0.4 * speed_mult) * dt
    state.rotation_x = state.rotation_x + (0.25 * speed_mult) * dt
    state.rotation_z = state.rotation_z + (0.15 * speed_mult) * dt

    -- Pulse on beat
    if audio.is_beat then
        state.pulse = 1.0
    end
    state.pulse = decay(state.pulse, 0.9, dt)

    -- Scale based on bass
    local base_radius = 0.12 + audio.bands[1] * 0.05 + state.pulse * 0.04

    local points_to_use = all_points
    if n <= #all_points then
        points_to_use = {}
        for i = 1, n do
            points_to_use[i] = all_points[i]
        end
    end

    local count = math.min(n, #points_to_use)
    for i = 1, count do
        local px, py, pz = points_to_use[i][1], points_to_use[i][2], points_to_use[i][3]

        -- Normalize
        local mag = math.sqrt(px * px + py * py + pz * pz)
        if mag > 0 then
            px, py, pz = px / mag, py / mag, pz / mag
        end

        -- Y rotation
        local cos_y, sin_y = math.cos(state.rotation_y), math.sin(state.rotation_y)
        local rx = px * cos_y - pz * sin_y
        local rz = px * sin_y + pz * cos_y

        -- X rotation
        local cos_x, sin_x = math.cos(state.rotation_x), math.sin(state.rotation_x)
        local ry = py * cos_x - rz * sin_x
        local rz2 = py * sin_x + rz * cos_x

        -- Z rotation
        local cos_z, sin_z = math.cos(state.rotation_z), math.sin(state.rotation_z)
        local rx2 = rx * cos_z - ry * sin_z
        local ry2 = rx * sin_z + ry * cos_z

        -- Position
        local radius = base_radius * (1.0 + state.pulse * 0.3)
        local x = center + rx2 * radius
        local y = center + ry2 * radius
        local z = center + rz2 * radius

        local band_idx = (i - 1) % 5
        local scale = config.base_scale + audio.bands[band_idx + 1] * 0.4

        if audio.is_beat then
            scale = scale * 1.3
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, scale),
            rotation = ((state.rotation_y + state.rotation_z + i * 0.05) * 180 / math.pi) % 360,
            band = band_idx,
            visible = true,
        }
    end

    -- Fill remaining with inner layer
    for i = count + 1, n do
        local scale_factor = 0.5
        local point_idx = ((i - 1) % #points_to_use) + 1
        local px, py, pz = points_to_use[point_idx][1], points_to_use[point_idx][2], points_to_use[point_idx][3]

        local mag = math.sqrt(px * px + py * py + pz * pz)
        if mag > 0 then
            px = px / mag * scale_factor
            py = py / mag * scale_factor
            pz = pz / mag * scale_factor
        end

        -- Y rotation
        local cos_y, sin_y = math.cos(state.rotation_y), math.sin(state.rotation_y)
        local rx = px * cos_y - pz * sin_y
        local rz = px * sin_y + pz * cos_y

        -- X rotation
        local cos_x, sin_x = math.cos(state.rotation_x), math.sin(state.rotation_x)
        local ry = py * cos_x - rz * sin_x
        local rz2 = py * sin_x + rz * cos_x

        -- Z rotation
        local cos_z, sin_z = math.cos(state.rotation_z), math.sin(state.rotation_z)
        local rx2 = rx * cos_z - ry * sin_z
        local ry2 = rx * sin_z + ry * cos_z

        local radius = base_radius * (1.0 + state.pulse * 0.3)
        local x = center + rx2 * radius
        local y = center + ry2 * radius
        local z = center + rz2 * radius

        local band_idx = (i - 1) % 5
        local scale = config.base_scale * 0.7 + audio.bands[band_idx + 1] * 0.3

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, scale),
            rotation = ((state.rotation_x + state.rotation_y + i * 0.03) * 180 / math.pi) % 360,
            band = band_idx,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "skull",
    name: "Skull",
    description: "Clean anatomical skull with animated jaw and glowing eyes",
    category: "Epic",
    staticCamera: false,
    startBlocks: 160,
    source: `name = "Skull"
description = "Clean anatomical skull with animated jaw and glowing eyes"
recommended_entities = 160
category = "Epic"
static_camera = false
state = {}

local function skull_slice(y_norm, front_only)
    local points = {}

    if y_norm < 0.15 then
        local t = y_norm / 0.15
        local width = 0.08 + t * 0.04
        local depth = 0.06 + t * 0.02
        for angle = -140, 140, 20 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * width
            local z = -math.cos(rad) * depth - 0.04
            points[#points + 1] = {x, z, "jaw"}
        end

    elseif y_norm < 0.25 then
        local t = (y_norm - 0.15) / 0.10
        local width = 0.12 + t * 0.02
        local depth = 0.08 + t * 0.01
        for angle = -130, 130, 15 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * width
            local z = -math.cos(rad) * depth - 0.02
            points[#points + 1] = {x, z, "jaw"}
        end

    elseif y_norm < 0.35 then
        local t = (y_norm - 0.25) / 0.10
        local width = 0.14 + t * 0.01
        local depth = 0.10
        for angle = -100, 100, 12 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * width
            local z = -math.cos(rad) * depth
            points[#points + 1] = {x, z, "face"}
        end
        if not front_only then
            for _, side in ipairs({-1, 1}) do
                for d = 0, 2 do
                    local x = side * width
                    local z = -depth + 0.02 + d * 0.04
                    points[#points + 1] = {x, z, "face"}
                end
            end
        end

    elseif y_norm < 0.45 then
        local t = (y_norm - 0.35) / 0.10
        local nose_width = 0.03 * (1 - t * 0.5)
        for _, nx in ipairs({-nose_width, 0, nose_width}) do
            points[#points + 1] = {nx, -0.11, "nose"}
        end
        for _, side in ipairs({-1, 1}) do
            points[#points + 1] = {side * 0.14, -0.08, "cheek"}
            points[#points + 1] = {side * 0.16, -0.04, "cheek"}
            points[#points + 1] = {side * 0.15, 0.0, "face"}
        end

    elseif y_norm < 0.55 then
        for _, side in ipairs({-1, 1}) do
            local eye_cx = side * 0.065
            local eye_cz = -0.08
            for angle = 0, 359, 30 do
                local rad = math.rad(angle)
                local ex = eye_cx + math.cos(rad) * 0.04
                local ez = eye_cz + math.sin(rad) * 0.025
                points[#points + 1] = {ex, ez, "eye"}
            end
        end
        points[#points + 1] = {0, -0.10, "nose"}
        for _, side in ipairs({-1, 1}) do
            points[#points + 1] = {side * 0.15, -0.05, "face"}
            points[#points + 1] = {side * 0.16, 0.0, "face"}
        end

    elseif y_norm < 0.65 then
        for bx = -12, 12, 3 do
            local x = bx * 0.01
            local z = -0.10 - math.abs(x) * 0.3
            points[#points + 1] = {x, z, "brow"}
        end
        for _, side in ipairs({-1, 1}) do
            points[#points + 1] = {side * 0.15, -0.03, "temple"}
            points[#points + 1] = {side * 0.14, 0.02, "cranium"}
        end

    elseif y_norm < 0.80 then
        local t = (y_norm - 0.65) / 0.15
        local width = 0.14 - t * 0.02
        for angle = -160, 160, 15 do
            local rad = math.rad(angle)
            local x = math.sin(rad) * width
            local z = -math.cos(rad) * 0.12 * (1 - t * 0.3)
            points[#points + 1] = {x, z, "cranium"}
        end

    else
        local t = (y_norm - 0.80) / 0.20
        local radius = 0.12 * (1 - t * 0.7)
        if radius > 0.01 then
            for angle = 0, 359, 25 do
                local rad = math.rad(angle)
                local x = math.cos(rad) * radius
                local z = math.sin(rad) * radius * 0.9
                points[#points + 1] = {x, z, "cranium"}
            end
        else
            points[#points + 1] = {0, 0, "cranium"}
        end
    end

    return points
end

local function generate_skull_points(n)
    local all_points = {}
    local num_slices = math.max(20, math.floor(n / 8))

    for i = 0, num_slices - 1 do
        local y_norm = i / (num_slices - 1)
        local slice_points = skull_slice(y_norm, false)
        for _, pt in ipairs(slice_points) do
            all_points[#all_points + 1] = {pt[3], pt[1], y_norm, pt[2]}
        end
    end

    -- Extra eye detail
    for _, side in ipairs({-1, 1}) do
        local eye_cx = side * 0.065
        local eye_cy = 0.50
        for i = 0, 7 do
            local angle = i * math.pi * 2 / 8
            local x = eye_cx + math.cos(angle) * 0.025
            local z = -0.08 + math.sin(angle) * 0.015
            all_points[#all_points + 1] = {"eye_inner", x, eye_cy, z}
        end
    end

    -- Extra teeth detail
    for i = 0, 9 do
        local t = i / 9
        local x = -0.07 + t * 0.14
        all_points[#all_points + 1] = {"teeth_upper", x, 0.30, -0.095}
        all_points[#all_points + 1] = {"teeth_lower", x, 0.22, -0.09}
    end

    if #all_points > n then
        local step = #all_points / n
        local selected = {}
        for i = 0, n - 1 do
            local idx = math.floor(i * step) + 1
            if idx <= #all_points then
                selected[#selected + 1] = all_points[idx]
            end
        end
        return selected
    else
        local result = {}
        for _, pt in ipairs(all_points) do
            result[#result + 1] = pt
        end
        local idx = 0
        while #result < n do
            local orig = all_points[(idx % #all_points) + 1]
            local varied = {
                orig[1],
                orig[2] + (idx * 0.001) % 0.01,
                orig[3],
                orig[4] + (idx * 0.001) % 0.01,
            }
            result[#result + 1] = varied
            idx = idx + 1
        end
        -- Trim to exactly n
        while #result > n do
            result[#result] = nil
        end
        return result
    end
end

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Generate points if needed
    if state.skull_points == nil or #state.skull_points ~= n then
        state.skull_points = generate_skull_points(n)
    end

    state.rotation = state.rotation or 0
    state.jaw_open = state.jaw_open or 0
    state.eye_glow = state.eye_glow or 0
    state.breathe = state.breathe or 0
    state.breathe_dir = state.breathe_dir or 1
    state.head_bob = state.head_bob or 0
    state.time = state.time or 0
    state.beat_intensity = state.beat_intensity or 0

    state.time = state.time + dt

    -- Slow menacing rotation
    state.rotation = state.time * 0.12

    -- Breathing
    state.breathe = state.breathe + 0.012 * state.breathe_dir * (dt / 0.016)
    if state.breathe > 1.0 then
        state.breathe_dir = -1
    elseif state.breathe < 0.0 then
        state.breathe_dir = 1
    end

    -- Beat response
    if audio.is_beat then
        state.head_bob = 0.025 * audio.peak
        state.beat_intensity = 1.0
        state.eye_glow = 1.0
    end
    state.head_bob = decay(state.head_bob, 0.9, dt)
    state.beat_intensity = decay(state.beat_intensity, 0.92, dt)
    state.eye_glow = decay(state.eye_glow, 0.85, dt)

    -- Jaw opens with bass
    local target_jaw = audio.bands[1] * 0.08 + audio.bands[2] * 0.04
    if audio.is_beat then
        target_jaw = target_jaw + 0.06
    end
    state.jaw_open = smooth(state.jaw_open, target_jaw, 0.25, dt)

    local breathe_scale = 1.0 + state.breathe * 0.02
    local skull_scale = 0.56 + audio.bands[2] * 0.05 + state.beat_intensity * 0.02

    local yaw = state.rotation + math.sin(state.time * 0.35) * 0.05
    local cos_r = math.cos(yaw)
    local sin_r = math.sin(yaw)
    local tilt_x = math.sin(state.time * 0.6) * 0.08 + state.beat_intensity * 0.12
    local tilt_z = math.cos(state.time * 0.5) * 0.05 + audio.bands[4] * 0.05
    local cos_tx, sin_tx = math.cos(tilt_x), math.sin(tilt_x)
    local cos_tz, sin_tz = math.cos(tilt_z), math.sin(tilt_z)

    for i = 1, #state.skull_points do
        local point = state.skull_points[i]
        local part_type = point[1]
        local px = point[2]
        local py_norm = point[3]
        local pz = point[4]

        -- Scale and position
        px = px * skull_scale * breathe_scale
        pz = pz * skull_scale * breathe_scale
        local py = py_norm * 0.45 * skull_scale

        -- Apply jaw movement
        if part_type == "jaw" and py_norm < 0.25 then
            py = py - state.jaw_open * (0.25 - py_norm) / 0.25
            pz = pz - state.jaw_open * 0.12
        elseif part_type == "teeth_lower" then
            py = py - state.jaw_open * 0.8
            pz = pz - state.jaw_open * 0.1
        elseif part_type == "eye" or part_type == "eye_inner" then
            pz = pz + 0.02
            if part_type == "eye_inner" then
                pz = pz - 0.03
            end
        elseif part_type == "cranium" or part_type == "temple" then
            pz = pz * 1.08
        elseif part_type == "face" or part_type == "cheek" then
            pz = pz * 0.96
        end

        -- Subtle tilt
        local ty = py * cos_tx - pz * sin_tx
        local tz = py * sin_tx + pz * cos_tx
        local tx = px * cos_tz - ty * sin_tz
        local ty2 = px * sin_tz + ty * cos_tz

        -- Rotate around Y axis
        local rx = tx * cos_r - tz * sin_r
        local rz = tx * sin_r + tz * cos_r

        local x = center + rx
        local y = 0.25 + ty2 + state.head_bob
        local z = center + rz

        -- Scale based on part type
        local band_idx = ((i - 1) % 5)
        local base_scale = config.base_scale * 0.9

        if part_type == "eye" or part_type == "eye_inner" then
            base_scale = base_scale * 1.1
            base_scale = base_scale + state.eye_glow * 0.5 + audio.bands[5] * 0.45
            band_idx = 4
            if part_type == "eye_inner" then
                base_scale = base_scale * 0.65
            end
        elseif part_type == "jaw" then
            base_scale = base_scale + audio.bands[1] * 0.3
            band_idx = 0
        elseif part_type == "teeth_upper" or part_type == "teeth_lower" then
            base_scale = base_scale * 0.7
            base_scale = base_scale + state.beat_intensity * 0.25
            band_idx = 4
        elseif part_type == "brow" then
            base_scale = base_scale * 1.05
            base_scale = base_scale + audio.bands[3] * 0.2
        elseif part_type == "cranium" then
            base_scale = base_scale + audio.bands[((i - 1) % 3) + 1] * 0.2
        elseif part_type == "nose" then
            base_scale = base_scale * 0.85
        end

        -- Global beat pulse
        if audio.is_beat then
            base_scale = base_scale * 1.15
        end

        -- Per-part rendering fields
        local ent_glow = false
        local ent_brightness = 8 + math.floor(audio.amplitude * 4)
        local ent_material = "BONE_BLOCK"

        if part_type == "eye" then
            ent_glow = state.eye_glow > 0.3
            ent_brightness = math.floor(state.eye_glow * 15)
            ent_material = state.eye_glow > 0.5 and "GLOWSTONE" or "BONE_BLOCK"
        elseif part_type == "eye_inner" then
            ent_glow = state.eye_glow > 0.7
            ent_brightness = 3 + math.floor(state.eye_glow * 6)
            ent_material = state.eye_glow > 0.7 and "SEA_LANTERN" or "BLACK_CONCRETE"
        elseif part_type == "teeth_upper" or part_type == "teeth_lower" then
            ent_brightness = 10 + math.floor(state.beat_intensity * 5)
            ent_material = state.beat_intensity > 0.6 and "QUARTZ_BLOCK" or "BONE_BLOCK"
        elseif part_type == "jaw" then
            ent_brightness = 7 + math.floor(audio.bands[1] * 5)
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, base_scale),
            rotation = (yaw * 180 / math.pi) % 360,
            band = band_idx,
            visible = true,
            glow = ent_glow,
            brightness = math.min(15, ent_brightness),
            material = ent_material,
            interpolation = 5,
        }
    end

    return entities
end
`,
  },
  {
    id: "sword",
    name: "Sword",
    description: "Giant floating sword with energy pulses traveling up the blade on beat",
    category: "Epic",
    staticCamera: false,
    startBlocks: 96,
    source: `name = "Sword"
description = "Giant floating sword with energy pulses traveling up the blade on beat"
recommended_entities = 96
category = "Epic"
static_camera = false
state = {}

local CENTER = 0.5

-- Blade geometry constants
local BLADE_BASE_Y = 0.30
local BLADE_TIP_Y = 0.80
local BLADE_LENGTH = BLADE_TIP_Y - BLADE_BASE_Y
local BLADE_HALF_WIDTH = 0.06

-- Part boundaries
local POMMEL_Y = 0.16
local GRIP_BASE_Y = 0.18
local GRIP_TOP_Y = 0.30
local GUARD_Y = 0.30
local GUARD_HALF_X = 0.12
local FULLER_BASE_Y = 0.35
local FULLER_TIP_Y = 0.70
local POMMEL_RADIUS = 0.025
local GRIP_RADIUS = 0.02

local MAX_PULSES = 3
local PULSE_SPEED = 0.6

local function allocate_counts(total, weights)
    local counts = {}
    local fracs = {}
    local weight_sum = 0
    for k, v in pairs(weights) do
        weight_sum = weight_sum + v
    end
    local used = 0
    for k, v in pairs(weights) do
        local exact = (total * v) / weight_sum
        local base = math.floor(exact)
        counts[k] = base
        fracs[#fracs + 1] = {k = k, frac = exact - base}
        used = used + base
    end
    table.sort(fracs, function(a, b) return a.frac > b.frac end)
    local rem = total - used
    local idx = 1
    while rem > 0 and #fracs > 0 do
        local key = fracs[idx].k
        counts[key] = counts[key] + 1
        rem = rem - 1
        idx = idx + 1
        if idx > #fracs then
            idx = 1
        end
    end
    return counts
end

local function blade_half_width(t)
    return BLADE_HALF_WIDTH * (1 - t ^ 1.5)
end

local function init_state()
    state.rotation = state.rotation or 0
    state.time = state.time or 0
    state.beat_intensity = state.beat_intensity or 0
    state.energy_pulses = state.energy_pulses or {}
    state.breathe = state.breathe or 0
    state.sword_scale = state.sword_scale or 1.0
end

function calculate(audio, config, dt)
    init_state()
    local n = config.entity_count or 0

    state.time = state.time + dt
    state.rotation = state.rotation + 0.15 * dt

    -- Beat response
    if audio.is_beat then
        state.beat_intensity = 1.0
        state.sword_scale = 1.08
        -- Spawn energy pulse (max 3)
        if #state.energy_pulses < MAX_PULSES then
            state.energy_pulses[#state.energy_pulses + 1] = { y = BLADE_BASE_Y, life = 0 }
        end
    end
    state.beat_intensity = decay(state.beat_intensity, 0.90, dt)
    state.sword_scale = smooth(state.sword_scale, 1.0, 0.08, dt)

    -- Update energy pulses
    local active_pulses = {}
    for _, pulse in ipairs(state.energy_pulses) do
        pulse.y = pulse.y + PULSE_SPEED * dt
        pulse.life = pulse.life + dt
        if pulse.y <= BLADE_TIP_Y then
            active_pulses[#active_pulses + 1] = pulse
        end
    end
    state.energy_pulses = active_pulses

    -- Breathing for grip
    state.breathe = state.breathe + dt * 1.5

    -- Audio-reactive values
    local blade_width_boost = audio.bands[2] * 0.015
    local blade_brightness = math.floor((0.3 + audio.bands[3] * 0.4 + state.beat_intensity * 0.3) * 15)
    blade_brightness = clamp(blade_brightness, 0, 15)

    -- Rotation
    local yaw = state.rotation
    local tilt_x = math.sin(state.time * 0.8) * 0.05
    local cos_y = math.cos(yaw)
    local sin_y = math.sin(yaw)
    local cos_tx = math.cos(tilt_x)
    local sin_tx = math.sin(tilt_x)

    local scale_factor = state.sword_scale
    local base = config.base_scale
    local blade_depth = 0.008 + audio.bands[4] * 0.01 + state.beat_intensity * 0.006
    local parts = allocate_counts(n, {
        blade_edges = 32,
        blade_fill = 16,
        fuller = 8,
        guard = 14,
        grip = 10,
        pommel = 6,
        tip = 2,
        pulses = 8,
    })

    local blade_edge_rows = math.floor(parts.blade_edges / 2)
    local blade_edge_extra = parts.blade_edges - blade_edge_rows * 2
    local blade_fill_levels = math.floor(parts.blade_fill / 2)
    local blade_fill_extra = parts.blade_fill - blade_fill_levels * 2
    local fuller_count = parts.fuller
    local guard_left = math.floor(parts.guard / 2)
    local guard_right = parts.guard - guard_left
    local grip_count = parts.grip
    local pommel_count = parts.pommel
    local tip_count = parts.tip
    local pulse_slots = parts.pulses

    local entities = {}
    local idx = 0

    local function add_entity(x, y, z, scl, band, opts)
        idx = idx + 1
        -- Apply tilt around X axis (relative to center y=0.48)
        local rel_y = y - 0.48
        local rel_z = z - CENTER
        local ty = rel_y * cos_tx - rel_z * sin_tx
        local tz = rel_y * sin_tx + rel_z * cos_tx
        -- Apply Y rotation around center
        local rel_x = x - CENTER
        local rx = rel_x * cos_y - tz * sin_y
        local rz = rel_x * sin_y + tz * cos_y

        local fx = CENTER + rx * scale_factor
        local fy = 0.48 + ty * scale_factor
        local fz = CENTER + rz * scale_factor

        local ent = {
            id = string.format("block_%d", idx - 1),
            x = clamp(fx),
            y = clamp(fy),
            z = clamp(fz),
            scale = math.min(config.max_scale, scl),
            rotation = 0,
            band = band,
            visible = true,
        }
        if opts then
            if opts.glow then ent.glow = true end
            if opts.brightness then ent.brightness = opts.brightness end
            if opts.material then ent.material = opts.material end
            if opts.interpolation then ent.interpolation = opts.interpolation end
        end
        entities[idx] = ent
    end

    -- === BLADE EDGES ===
    for i = 0, blade_edge_rows - 1 do
        local t = i / math.max(1, blade_edge_rows - 1)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        local hw = blade_half_width(t) + blade_width_boost

        -- Check proximity to energy pulses for wake glow
        local near_pulse = false
        for _, pulse in ipairs(state.energy_pulses) do
            if math.abs(y - pulse.y) < 0.05 then
                near_pulse = true
                break
            end
        end
        local edge_glow = near_pulse
        local edge_bright = blade_brightness
        if near_pulse then
            edge_bright = clamp(blade_brightness + 4, 0, 15)
        end

        -- Left edge
        add_entity(CENTER - hw, y, 0.5 - blade_depth, base * 0.85, 2,
            { brightness = edge_bright, material = "IRON_BLOCK", interpolation = 3, glow = edge_glow or nil })
        -- Right edge
        add_entity(CENTER + hw, y, 0.5 + blade_depth, base * 0.85, 2,
            { brightness = edge_bright, material = "IRON_BLOCK", interpolation = 3, glow = edge_glow or nil })
    end
    for i = 0, blade_edge_extra - 1 do
        local t = (i + 0.5) / math.max(1, blade_edge_extra)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        add_entity(CENTER, y, 0.5 + ((i % 2 == 0) and blade_depth or -blade_depth), base * 0.75, 2,
            { brightness = math.max(0, blade_brightness - 1), material = "IRON_BLOCK", interpolation = 3 })
    end

    -- === BLADE FILL ===
    for level = 0, blade_fill_levels - 1 do
        local t = (level + 0.5) / math.max(1, blade_fill_levels)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        local hw = blade_half_width(t) + blade_width_boost
        local left_x = CENTER - hw
        local right_x = CENTER + hw

        -- Check proximity to energy pulses for wake glow
        local near_pulse = false
        for _, pulse in ipairs(state.energy_pulses) do
            if math.abs(y - pulse.y) < 0.05 then
                near_pulse = true
                break
            end
        end
        local fill_glow = near_pulse
        local fill_bright = blade_brightness
        if near_pulse then
            fill_bright = clamp(blade_brightness + 4, 0, 15)
        end

        -- 1/3 and 2/3 between edges
        add_entity(lerp(left_x, right_x, 0.333), y, 0.5 - blade_depth * 0.6, base * 0.75, 2,
            { brightness = fill_bright, material = "IRON_BLOCK", interpolation = 3, glow = fill_glow or nil })
        add_entity(lerp(left_x, right_x, 0.667), y, 0.5 + blade_depth * 0.6, base * 0.75, 2,
            { brightness = fill_bright, material = "IRON_BLOCK", interpolation = 3, glow = fill_glow or nil })
    end
    for i = 0, blade_fill_extra - 1 do
        local t = (i + 0.5) / math.max(1, blade_fill_extra)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        add_entity(CENTER, y, 0.5 + ((i % 2 == 0) and blade_depth * 0.5 or -blade_depth * 0.5), base * 0.7, 2,
            { brightness = math.max(0, blade_brightness - 2), material = "IRON_BLOCK", interpolation = 3 })
    end

    -- === FULLER / GROOVE ===
    for i = 0, fuller_count - 1 do
        local t = i / math.max(1, fuller_count - 1)
        local y = FULLER_BASE_Y + t * (FULLER_TIP_Y - FULLER_BASE_Y)
        add_entity(CENTER, y, 0.5 - 0.005, base * 0.6, 2,
            { brightness = math.max(0, blade_brightness - 2), material = "IRON_BLOCK", interpolation = 3 })
    end

    -- === CROSSGUARD ===
    local guard_scale = base * 1.1 + audio.bands[1] * 0.2
    local guard_flash = state.beat_intensity > 0.7
    local guard_brightness = 8
    if guard_flash then
        guard_brightness = 15
    end
    local function build_guard_side(side, count)
        for i = 0, count - 1 do
            local t = i / math.max(1, count - 1)
            local x = CENTER + side * t * GUARD_HALF_X
            local y_off = 0
            if t > 0.8 then
                y_off = (t - 0.8) * 0.05
            end
            add_entity(x, GUARD_Y + y_off, 0.5, guard_scale, 0,
                { material = "GOLD_BLOCK", interpolation = 4, brightness = guard_brightness, glow = guard_flash or nil })
        end
    end
    build_guard_side(-1, guard_left)
    build_guard_side(1, guard_right)

    -- === GRIP ===
    local grip_breathe = math.sin(state.breathe) * 0.02
    local grip_brightness = 5 + math.floor(math.sin(state.breathe) * 2 + 2)
    for i = 0, grip_count - 1 do
        local t = i / math.max(1, grip_count - 1)
        local y = GRIP_BASE_Y + t * (GRIP_TOP_Y - GRIP_BASE_Y)
        local angle = i * 2.5
        local gx = CENTER + math.cos(angle) * GRIP_RADIUS
        local gz = 0.5 + math.sin(angle) * GRIP_RADIUS
        add_entity(gx, y, gz, base * 0.8 + grip_breathe, 1,
            { material = "NETHER_BRICKS", interpolation = 5, brightness = clamp(grip_brightness, 0, 15) })
    end

    -- === POMMEL ===
    local pommel_brightness = clamp(math.floor(6 + state.beat_intensity * 6), 0, 15)
    if not state.pommel_pts or #state.pommel_pts ~= pommel_count then
        state.pommel_pts = fibonacci_sphere(pommel_count)
    end
    local pommel_pts = state.pommel_pts
    for i = 1, pommel_count do
        local pt = pommel_pts[i]
        local px = CENTER + pt.x * POMMEL_RADIUS
        local py = POMMEL_Y + pt.y * POMMEL_RADIUS
        local pz = 0.5 + pt.z * POMMEL_RADIUS
        add_entity(px, py, pz, base * 0.9, 3,
            { material = "GOLD_BLOCK", interpolation = 4, glow = true, brightness = pommel_brightness })
    end

    -- === TIP GLOW ===
    local tip_brightness = 8 + math.floor(audio.bands[5] * 7)
    for i = 0, tip_count - 1 do
        local t = i / math.max(1, tip_count - 1)
        local tip_y = BLADE_TIP_Y + t * 0.02
        local tip_scale = lerp(base * 0.7, base * 0.5, t)
        add_entity(CENTER, tip_y, 0.5, tip_scale, 4,
            { glow = true, material = "END_ROD", brightness = tip_brightness, interpolation = 2 })
    end

    -- === ENERGY PULSES ===
    local pulse_idx = 0
    for _, pulse in ipairs(state.energy_pulses) do
        local points_per_pulse = math.max(1, math.floor(pulse_slots / math.max(1, #state.energy_pulses)))
        for j = 0, points_per_pulse - 1 do
            if pulse_idx >= pulse_slots then break end
            local angle = (j / math.max(1, points_per_pulse)) * math.pi * 2
            local t = (pulse.y - BLADE_BASE_Y) / BLADE_LENGTH
            local hw = blade_half_width(clamp(t, 0, 1)) * 0.7
            local px = CENTER + math.cos(angle) * hw
            local pz = 0.5 + math.sin(angle) * hw * 0.3
            add_entity(px, pulse.y, pz, base * 1.0, 2,
                { glow = true, material = "SEA_LANTERN", brightness = 15, interpolation = 1 })
            pulse_idx = pulse_idx + 1
        end
    end
    -- Fill remaining pulse slots with faint arc accents so budget stays visible.
    while pulse_idx < pulse_slots do
        local t = (pulse_idx + 0.5) / math.max(1, pulse_slots)
        local y = BLADE_BASE_Y + t * BLADE_LENGTH
        local hw = blade_half_width(clamp(t, 0, 1)) * 0.35
        local px = CENTER + hw
        local pz = 0.5
        add_entity(px, y, pz, base * 0.35, 2,
            { glow = false, material = "IRON_BLOCK", brightness = 5, interpolation = 2 })
        pulse_idx = pulse_idx + 1
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "vortex",
    name: "Vortex",
    description: "Swirling tunnel - spiral into infinity",
    category: "Epic",
    staticCamera: false,
    startBlocks: 80,
    source: `name = "Vortex"
description = "Swirling tunnel - spiral into infinity"
recommended_entities = 80
category = "Epic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation or 0
    state.z_offset = state.z_offset or 0
    state.intensity = state.intensity or 0

    -- Faster rotation with energy
    local speed = 2.4 + audio.peak * 3.4
    if audio.is_beat then
        speed = speed * 1.5
        state.intensity = 1.0
    end
    state.rotation = state.rotation + speed * dt
    state.intensity = decay(state.intensity, 0.95, dt)

    -- Z movement (flying through tunnel)
    state.z_offset = state.z_offset + (0.55 + audio.bands[1] * 0.65) * dt
    if state.z_offset > 1.0 then
        state.z_offset = state.z_offset - 1.0
    end

    -- Rings of entities forming tunnel
    local rings = math.max(5, math.floor(n / 8))
    local per_ring = math.floor(n / rings)

    for ring = 0, rings - 1 do
        -- Depth (z position)
        local ring_z = (ring / rings + state.z_offset) % 1.0
        local depth = ring_z

        -- Ring radius - smaller as it goes further
        local base_radius = 0.36 - depth * 0.26
        local pulse_radius = base_radius + state.intensity * 0.12 * (1 - depth)

        -- Ring rotation
        local ring_rotation = state.rotation * (1.0 + ring * 0.2)

        for j = 0, per_ring - 1 do
            local idx = ring * per_ring + j
            if idx >= n then
                break
            end

            local angle = ring_rotation + (j / per_ring) * math.pi * 2

            -- Wobble
            local wobble = math.sin(angle * 3 + state.rotation * 2) * 0.02
            local radius = pulse_radius + wobble

            -- Audio reactivity per band
            local band_idx = j % 5
            radius = radius + audio.bands[band_idx + 1] * 0.05 * (1 - depth)

            local x = center + math.cos(angle) * radius
            local z_pos = center + math.sin(angle) * radius
            local wave = math.sin(state.rotation + depth * math.pi * 2) * 0.04
            local y = 0.12 + depth * 0.78 + wave

            -- Scale - larger when close
            local scale = config.base_scale * (1.55 - depth) + audio.bands[band_idx + 1] * 0.35

            if audio.is_beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z_pos),
                scale = math.min(config.max_scale, scale),
                rotation = 0,
                band = band_idx,
                visible = true,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "wormhole",
    name: "Wormhole Portal",
    description: "Infinite tunnel - rings fly toward you",
    category: "Epic",
    staticCamera: false,
    startBlocks: 108,
    source: `-- Pattern metadata
name = "Wormhole Portal"
description = "Infinite tunnel - rings fly toward you"
category = "Epic"
static_camera = false
start_blocks = 108

-- Per-instance state
state = {
    rotation = 0.0,
    tunnel_offset = 0.0,
    flash = 0.0,
    pulse = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Rotation speed driven by energy
    local energy = 0
    for b = 1, 5 do
        energy = energy + audio.bands[b]
    end
    energy = energy / 5

    state.rotation = state.rotation + (1.1 + energy * 2.2) * dt

    -- Tunnel movement speed (flying through)
    local speed = 0.32 + audio.bands[1] * 0.45 + audio.amplitude * 0.35
    state.tunnel_offset = state.tunnel_offset + speed * dt
    if state.tunnel_offset > 1.0 then
        state.tunnel_offset = state.tunnel_offset - 1.0
    end

    -- Beat flash
    if audio.is_beat then
        state.flash = 1.0
        state.pulse = 0.3
    end
    state.flash = decay(state.flash, 0.85, dt)
    state.pulse = decay(state.pulse, 0.9, dt)

    -- Create rings at different depths
    local num_rings = math.max(6, math.floor(n / 10))
    local points_per_ring = math.floor(n / num_rings)

    for ring = 0, num_rings - 1 do
        -- Depth cycles through with tunnel offset
        local ring_depth = ((ring / num_rings) + state.tunnel_offset) % 1.0

        -- Perspective: close rings are larger, far rings are smaller
        local perspective = 1.0 - ring_depth * 0.8
        local ring_radius = 0.05 + perspective * 0.36

        -- Breathing with amplitude
        ring_radius = ring_radius + audio.amplitude * 0.05 * perspective
        ring_radius = ring_radius + math.sin(state.rotation + ring_depth * math.pi * 3) * 0.02 * perspective

        -- Ring rotation - different speeds for each depth
        local ring_rotation = state.rotation * (1.0 + ring_depth * 0.5)

        -- Y position maps to depth (close = low y, far = high y for upward tunnel)
        local base_y = 0.1 + ring_depth * 0.8
        base_y = base_y + math.sin(state.rotation * 1.3 + ring_depth * math.pi * 2) * 0.03

        for j = 0, points_per_ring - 1 do
            local idx = ring * points_per_ring + j
            if idx >= n then break end

            local angle = ring_rotation + (j / points_per_ring) * math.pi * 2

            -- Spiral twist increases with depth
            local twist = ring_depth * math.pi * 0.5
            angle = angle + twist

            local x = center + math.cos(angle) * ring_radius
            local z = center + math.sin(angle) * ring_radius
            local y = base_y

            -- Band-reactive radius wobble
            local band_idx = j % 5
            local wobble = audio.bands[band_idx + 1] * 0.03 * perspective
            x = x + math.cos(angle) * wobble
            z = z + math.sin(angle) * wobble

            -- Scale - larger when close, affected by flash
            local scale = config.base_scale * perspective
            scale = scale + audio.bands[band_idx + 1] * 0.35 * perspective

            -- Flash effect on close rings
            if ring_depth < 0.3 then
                scale = scale + state.flash * 0.4 * (0.3 - ring_depth)
            end

            if audio.is_beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, math.max(0.05, scale)),
                rotation = ((angle + ring_depth * math.pi * 1.2) * 180 / math.pi) % 360,
                band = band_idx,
                visible = true,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "blackhole",
    name: "Black Hole",
    description: "Accretion disk with jets - gravity visualization",
    category: "Cosmic",
    staticCamera: false,
    startBlocks: 96,
    source: `-- Pattern metadata
name = "Black Hole"
description = "Accretion disk with jets - gravity visualization"
category = "Cosmic"
static_camera = false
start_blocks = 96

-- Per-instance state
state = {
    particles = {},       -- {r, theta, dr, layer}
    jet_intensity = 0.0,
    rotation = 0.0,
    warp = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Allocate: 75% disk, 25% jets
    local disk_count = math.floor(n * 0.75)
    local jet_count = n - disk_count

    -- Initialize particles
    if #state.particles ~= disk_count then
        state.particles = {}
        for i = 1, disk_count do
            local r = 0.1 + math.random() * 0.3
            local theta = math.random() * math.pi * 2
            local dr = 0.0
            local layer = math.random(0, 2)
            state.particles[i] = {r = r, theta = theta, dr = dr, layer = layer}
        end
    end

    -- Jet intensity on beat
    if audio.is_beat then
        state.jet_intensity = 1.0
        state.warp = 0.5
    end
    state.jet_intensity = decay(state.jet_intensity, 0.92, dt)
    state.warp = decay(state.warp, 0.95, dt)

    -- Accretion rate from bass
    local accretion_rate = 0.001 + audio.bands[1] * 0.003 + audio.bands[2] * 0.002

    state.rotation = state.rotation + 0.5 * dt

    -- === ACCRETION DISK ===
    for i = 1, disk_count do
        local p = state.particles[i]
        local r = p.r
        local theta = p.theta
        local layer = p.layer

        -- Keplerian velocity: v proportional to 1/sqrt(r) (faster when closer)
        local orbital_speed = 0.5 / math.sqrt(math.max(0.05, r))
        theta = theta + orbital_speed * dt

        -- Spiral inward (accretion)
        local dr = -accretion_rate * (1.0 + math.random() * 0.5)
        r = r + dr

        -- Respawn if too close to center
        if r < 0.05 then
            r = 0.35 + math.random() * 0.1
            theta = math.random() * math.pi * 2
            layer = math.random(0, 2)
        end

        state.particles[i].r = r
        state.particles[i].theta = theta
        state.particles[i].layer = layer

        -- Position
        local x = center + math.cos(theta) * r
        local z = center + math.sin(theta) * r

        -- Thin disk with slight vertical variation
        local layer_offset = (layer - 1) * 0.02
        local y = center + layer_offset + math.sin(theta * 4) * 0.01

        -- Warp effect near center (light bending)
        if r < 0.15 then
            local warp_strength = (0.15 - r) / 0.15 * state.warp
            x = center + (x - center) * (1.0 - warp_strength * 0.3)
            z = center + (z - center) * (1.0 - warp_strength * 0.3)
        end

        local band_idx = (i - 1) % 5
        -- Inner disk hotter (higher frequencies)
        if r < 0.15 then
            band_idx = 3 + ((i - 1) % 2)  -- high-mid and high bands (0-indexed: 3,4)
        elseif r < 0.25 then
            band_idx = 2 + ((i - 1) % 2)  -- mid and high-mid bands (0-indexed: 2,3)
        end

        local scale = config.base_scale * (0.5 + (0.4 - r))  -- Larger near center
        scale = scale + audio.bands[band_idx + 1] * 0.2

        if audio.is_beat and r < 0.2 then
            scale = scale * 1.4
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, scale),
            rotation = (theta * 180 / math.pi) % 360,
            band = band_idx,
            visible = true,
        }
    end

    -- === RELATIVISTIC JETS ===
    local jet_height = 0.3 + state.jet_intensity * 0.15 + audio.bands[1] * 0.1
    local points_per_jet = math.floor(jet_count / 2)

    for jet = 0, 1 do
        local direction = 1
        if jet == 1 then direction = -1 end

        for j = 0, points_per_jet - 1 do
            local idx = disk_count + jet * points_per_jet + j
            if idx >= n then break end

            -- Position along jet
            local t = (j + 1) / points_per_jet
            local jet_r = 0.02 + t * 0.04  -- Slight cone shape
            local jet_y = center + direction * (0.05 + t * jet_height)

            -- Spiral in jet
            local jet_angle = state.rotation * 3 + t * math.pi * 2 + jet * math.pi
            local x = center + math.cos(jet_angle) * jet_r
            local z = center + math.sin(jet_angle) * jet_r
            local y = jet_y

            local band_idx = 3 + (j % 2)  -- Jets are hot = high-mid and high bands
            local scale = config.base_scale * (1.0 - t * 0.5) * (0.5 + state.jet_intensity)
            scale = scale + audio.bands[5] * 0.3

            local visible = state.jet_intensity > 0.1 or audio.bands[1] > 0.3

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = (jet_angle * 180 / math.pi) % 360,
                band = band_idx,
                visible = visible,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "crystal",
    name: "Crystal Growth",
    description: "Fractal crystal with recursive branching",
    category: "Cosmic",
    staticCamera: false,
    startBlocks: 80,
    source: `name = "Crystal Growth"
description = "Fractal crystal with recursive branching"
recommended_entities = 80
category = "Cosmic"
static_camera = false
state = {}

local function generate_crystal_structure(depth)
    depth = depth or 3
    local points = {}

    local function add_branch(start_x, start_y, start_z, dx, dy, dz, length, current_depth, branch_id)
        if current_depth > depth or length < 0.02 then
            return
        end

        -- Add points along this branch
        local segments = math.max(2, math.floor(length * 10))
        for i = 0, segments do
            local t = i / segments
            local x = start_x + dx * length * t
            local y = start_y + dy * length * t
            local z = start_z + dz * length * t
            points[#points + 1] = {x, y, z, current_depth, branch_id}
        end

        -- End point of this branch
        local end_x = start_x + dx * length
        local end_y = start_y + dy * length
        local end_z = start_z + dz * length

        -- Spawn child branches
        if current_depth < depth then
            local num_children = math.max(1, 4 - current_depth)

            for child = 0, num_children - 1 do
                local child_angle = (child / num_children) * math.pi * 2
                local spread = 0.3 + current_depth * 0.15

                -- New direction
                local new_dx = dx + math.cos(child_angle) * spread
                local new_dy = dy * 0.8
                local new_dz = dz + math.sin(child_angle) * spread

                -- Normalize
                local mag = math.sqrt(new_dx * new_dx + new_dy * new_dy + new_dz * new_dz)
                if mag > 0 then
                    new_dx = new_dx / mag
                    new_dy = new_dy / mag
                    new_dz = new_dz / mag
                end

                -- Child branches are shorter
                local child_length = length * 0.6

                add_branch(
                    end_x, end_y, end_z,
                    new_dx, new_dy, new_dz,
                    child_length,
                    current_depth + 1,
                    branch_id .. "_" .. child
                )
            end
        end
    end

    -- Main trunk going up
    add_branch(0, 0, 0, 0, 1, 0, 0.4, 0, "trunk")

    -- Root branches going down/out
    for i = 0, 2 do
        local angle = i * math.pi * 2 / 3
        add_branch(
            0, 0, 0,
            math.cos(angle) * 0.3, -0.3, math.sin(angle) * 0.3,
            0.15,
            2,
            "root_" .. i
        )
    end

    return points
end

function calculate(audio, config, dt)
    local n = config.entity_count

    -- Generate crystal structure if needed
    if not state.branch_points then
        state.branch_points = generate_crystal_structure()
    end

    state.sparkle_phase = state.sparkle_phase or 0
    state.growth = state.growth or 0.5
    state.growth_spurt = state.growth_spurt or 0
    state.rotation = state.rotation or 0
    state.sway = state.sway or 0

    state.sparkle_phase = state.sparkle_phase + 0.1 * (dt / 0.016)
    state.rotation = state.rotation + (0.15 + audio.peak * 0.3) * dt
    state.sway = math.sin(state.sparkle_phase * 0.5) * (0.05 + audio.bands[3] * 0.08)

    -- Growth responds to bass
    local target_growth = 0.5 + audio.bands[1] * 0.3 + audio.bands[2] * 0.2
    state.growth = smooth(state.growth, target_growth, 0.05, dt)

    -- Beat triggers growth spurt
    if audio.is_beat then
        state.growth_spurt = 0.35
    end
    state.growth_spurt = decay(state.growth_spurt, 0.9, dt)

    local effective_growth = state.growth + state.growth_spurt

    local entities = {}
    local center = 0.5

    -- Sample points from structure
    local points_to_use = math.min(n, #state.branch_points)
    local step = 1
    if points_to_use > 0 then
        step = #state.branch_points / points_to_use
    end

    local entity_count = 0
    for i = 0, points_to_use - 1 do
        local idx = math.floor(i * step) + 1
        if idx > #state.branch_points then
            idx = #state.branch_points
        end

        local bp = state.branch_points[idx]
        local px, py, pz, depth_val = bp[1], bp[2], bp[3], bp[4]

        -- Scale by growth
        local depth_growth = math.max(0, effective_growth - depth_val * 0.2)
        if depth_growth > 0 then
            -- Position
            local cos_r = math.cos(state.rotation)
            local sin_r = math.sin(state.rotation)
            local rx = px * cos_r - pz * sin_r
            local rz = px * sin_r + pz * cos_r

            local x = center + rx * depth_growth + state.sway * py
            local y = 0.2 + py * depth_growth * 0.6
            local z = center + rz * depth_growth + state.sway * pz * 0.3

            -- Band based on depth
            local band_idx = math.min(4, depth_val * 2)

            -- Scale based on depth and audio
            local base_scale = config.base_scale * (1.0 - depth_val * 0.15)
            local scale = base_scale + audio.bands[math.floor(band_idx) + 1] * 0.3

            -- Tips sparkle with highs
            if depth_val >= 2 then
                local sparkle = math.sin(state.sparkle_phase + i * 0.5) * 0.5 + 0.5
                scale = scale + sparkle * audio.bands[5] * 0.45 + audio.bands[5] * 0.3
            end

            if audio.is_beat then
                scale = scale * (1.2 + depth_val * 0.1)
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", i),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, math.max(0.05, scale)),
                band = math.floor(band_idx),
                visible = depth_growth > 0.1,
            }
            entity_count = entity_count + 1
        end
    end

    -- Fill remaining entities with extra tip points
    for i = entity_count, n - 1 do
        local angle = i * 2.39996 -- Golden angle
        local radius = 0.1 + (i % 5) * 0.03
        local tip_y = 0.2 + effective_growth * 0.6 * 0.8

        local x = center + math.cos(angle) * radius * effective_growth
        local y = tip_y + math.sin(i * 0.7) * 0.05
        local z = center + math.sin(angle) * radius * effective_growth

        -- Apply subtle rotation/sway to tips
        local cos_r = math.cos(state.rotation + i * 0.01)
        local sin_r = math.sin(state.rotation + i * 0.01)
        local tx = x - center
        local tz = z - center
        x = center + tx * cos_r - tz * sin_r + state.sway * 0.2
        z = center + tx * sin_r + tz * cos_r + state.sway * 0.1

        local sparkle = math.sin(state.sparkle_phase + i) * 0.5 + 0.5
        local scale = config.base_scale * 0.5 * sparkle
        scale = scale + audio.bands[5] * 0.3

        entities[#entities + 1] = {
            id = string.format("block_%d", i),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, math.max(0.03, scale)),
            band = 4,
            visible = sparkle > 0.3,
        }
    end

    return entities
end
`,
  },
  {
    id: "mandala",
    name: "Mandala",
    description: "Sacred geometry rings - frequency mapped",
    category: "Cosmic",
    staticCamera: false,
    startBlocks: 60,
    source: `name = "Mandala"
description = "Sacred geometry rings - frequency mapped"
recommended_entities = 60
category = "Cosmic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    if not state.ring_rotations then
        state.ring_rotations = {0, 0, 0, 0, 0}
    end
    state.pulse = state.pulse or 0
    state.petal_boost = state.petal_boost or 0

    -- Beat pulse
    if audio.is_beat then
        state.pulse = 1.0
        state.petal_boost = 1.0
    end
    state.pulse = decay(state.pulse, 0.9, dt)
    state.petal_boost = decay(state.petal_boost, 0.85, dt)

    -- Golden angle for petal distribution
    local golden_angle = math.pi * (3.0 - math.sqrt(5.0))

    -- Distribute entities across 5 rings
    local points_per_ring = math.floor(n / 5)
    local entity_idx = 0

    for ring = 0, 4 do
        -- Ring properties
        local base_radius = 0.08 + ring * 0.07

        -- Radius pulses with corresponding frequency band
        local radius = base_radius + audio.bands[ring + 1] * 0.04 + state.pulse * 0.02

        -- Rotation speed: inner slower, outer faster, alternating direction
        local direction = 1
        if ring % 2 == 1 then
            direction = -1
        end
        local speed = (0.2 + ring * 0.18) * direction
        speed = speed * (1.0 + audio.bands[ring + 1] * 0.5)

        state.ring_rotations[ring + 1] = state.ring_rotations[ring + 1] + speed * dt

        -- Points on this ring
        for j = 0, points_per_ring - 1 do
            if entity_idx >= n then
                break
            end

            -- Golden angle distribution
            local angle = state.ring_rotations[ring + 1] + j * golden_angle

            local x = center + math.cos(angle) * radius
            local z = center + math.sin(angle) * radius

            -- Y varies slightly for 3D depth
            local y = center + math.sin(angle * 2) * 0.02 * (1 + ring * 0.3)

            -- Scale based on band intensity
            local scale = config.base_scale + audio.bands[ring + 1] * 0.5
            scale = scale + state.petal_boost * 0.3 * (1.0 - ring / 5)

            if audio.is_beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = 0,
                band = ring,
                visible = true,
            }
            entity_idx = entity_idx + 1
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "nebula",
    name: "Nebula",
    description: "Cosmic gas cloud with drifting particles",
    category: "Cosmic",
    staticCamera: false,
    startBlocks: 104,
    source: `-- Pattern metadata
name = "Nebula"
description = "Cosmic gas cloud with drifting particles"
category = "Cosmic"
static_camera = false
start_blocks = 104

-- Per-instance state
state = {
    particles = {},        -- {x, y, z, phase}
    expansion = 1.0,
    flash_particles = {},  -- set: flash_particles[i] = true
    drift_time = 0.0,
    swirl = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count
    local center = 0.5

    -- Initialize particles in spherical distribution
    if #state.particles ~= n then
        state.particles = {}
        local points = fibonacci_sphere(n)
        for i = 1, n do
            local p = points[i]
            -- Randomize radius for volume (not just surface)
            local r = 0.3 + math.random() * 0.7  -- 0.3 to 1.0 of max
            local phase = math.random() * math.pi * 2
            state.particles[i] = {x = p.x * r, y = p.y * r, z = p.z * r, phase = phase}
        end
    end

    state.drift_time = state.drift_time + dt
    state.swirl = state.swirl + (0.18 + audio.bands[4] * 0.45) * dt

    -- Expansion with amplitude
    local target_expansion = 0.8 + audio.amplitude * 0.4
    state.expansion = smooth(state.expansion, target_expansion, 0.1, dt)

    -- Beat triggers star flashes (random subset)
    if audio.is_beat then
        state.flash_particles = {}
        local flash_count = math.min(math.floor(n / 4), 20)
        -- Pick random indices to flash
        for _ = 1, flash_count do
            local idx = math.random(1, n)
            state.flash_particles[idx] = true
        end
    else
        -- Decay flash set
        if math.random() < 0.3 then
            state.flash_particles = {}
        end
    end

    local entities = {}
    local base_radius = 0.25 * state.expansion

    for i = 1, n do
        local p = state.particles[i]
        local px, py, pz, phase = p.x, p.y, p.z, p.phase

        -- Smooth drifting motion using noise-like movement
        local drift_x = math.sin(state.drift_time * 0.3 + phase) * 0.02
        local drift_y = math.cos(state.drift_time * 0.2 + phase * 1.3) * 0.02
        local drift_z = math.sin(state.drift_time * 0.25 + phase * 0.7) * 0.02

        -- Update particle position with drift (dt-normalized)
        local drift_scale = 0.1 * (dt / 0.016)
        state.particles[i].x = px + drift_x * drift_scale
        state.particles[i].y = py + drift_y * drift_scale
        state.particles[i].z = pz + drift_z * drift_scale

        -- Keep within bounds (soft boundary)
        local dist = math.sqrt(px * px + py * py + pz * pz)
        if dist > 1.2 then
            -- Pull back toward center
            state.particles[i].x = state.particles[i].x * 0.99
            state.particles[i].y = state.particles[i].y * 0.99
            state.particles[i].z = state.particles[i].z * 0.99
        end

        -- World position
        local swirl_angle = state.swirl + dist * 1.6
        local sx = math.cos(swirl_angle) * px - math.sin(swirl_angle) * pz
        local sz = math.sin(swirl_angle) * px + math.cos(swirl_angle) * pz
        local x = center + sx * base_radius + drift_x
        local y = center + py * base_radius + drift_y
        local z = center + sz * base_radius + drift_z

        -- Band based on position (creates color gradients)
        -- Higher Y = higher frequency colors
        local normalized_y = (py + 1) / 2  -- 0 to 1
        local band_idx = math.floor(normalized_y * 4.9)
        band_idx = math.max(0, math.min(4, band_idx))

        -- Scale based on density (denser near center)
        local density_scale = 1.0 - dist * 0.3
        local scale = config.base_scale * density_scale
        scale = scale + audio.bands[band_idx + 1] * 0.25

        -- Flash effect for "star birth"
        if state.flash_particles[i] then
            scale = scale * 2.0
            band_idx = 4  -- Bright white/high freq
        end

        if audio.is_beat then
            scale = scale * 1.15
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, math.max(0.05, scale)),
            rotation = ((state.swirl + phase + dist * 2.2) * 180 / math.pi) % 360,
            band = band_idx,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "tesseract",
    name: "Tesseract",
    description: "4D hypercube rotating through dimensions",
    category: "Cosmic",
    staticCamera: false,
    startBlocks: 96,
    source: `name = "Tesseract"
description = "4D hypercube rotating through dimensions"
category = "Cosmic"
static_camera = false
start_blocks = 96
state = {}

local function generate_tesseract()
    -- 16 vertices of tesseract (all combinations of +/-1 in 4D)
    local vertices = {}
    for _, x in ipairs({-1, 1}) do
        for _, y in ipairs({-1, 1}) do
            for _, z in ipairs({-1, 1}) do
                for _, w in ipairs({-1, 1}) do
                    vertices[#vertices + 1] = {x, y, z, w}
                end
            end
        end
    end

    -- 32 edges connect vertices that differ in exactly one coordinate
    local edges = {}
    for i = 1, #vertices do
        for j = i + 1, #vertices do
            local v1, v2 = vertices[i], vertices[j]
            local diff = 0
            for k = 1, 4 do
                if v1[k] ~= v2[k] then
                    diff = diff + 1
                end
            end
            if diff == 1 then
                edges[#edges + 1] = {
                    (v1[1] + v2[1]) / 2,
                    (v1[2] + v2[2]) / 2,
                    (v1[3] + v2[3]) / 2,
                    (v1[4] + v2[4]) / 2,
                }
            end
        end
    end

    return vertices, edges
end

local function rotate_4d(point, rxw, ryw, rzw, rxy)
    local x, y, z, w = point[1], point[2], point[3], point[4]

    -- XW rotation (bass)
    local cos_xw, sin_xw = math.cos(rxw), math.sin(rxw)
    local x1 = x * cos_xw - w * sin_xw
    local w1 = x * sin_xw + w * cos_xw

    -- YW rotation (mids)
    local cos_yw, sin_yw = math.cos(ryw), math.sin(ryw)
    local y1 = y * cos_yw - w1 * sin_yw
    local w2 = y * sin_yw + w1 * cos_yw

    -- ZW rotation (highs)
    local cos_zw, sin_zw = math.cos(rzw), math.sin(rzw)
    local z1 = z * cos_zw - w2 * sin_zw
    local w3 = z * sin_zw + w2 * cos_zw

    -- XY rotation (visual spin)
    local cos_xy, sin_xy = math.cos(rxy), math.sin(rxy)
    local x2 = x1 * cos_xy - y1 * sin_xy
    local y2 = x1 * sin_xy + y1 * cos_xy

    return {x2, y2, z1, w3}
end

local function project_4d_to_3d(point, distance)
    distance = distance or 2.0
    local x, y, z, w = point[1], point[2], point[3], point[4]
    local scale = distance / (distance - w * 0.5)
    return {x * scale, y * scale, z * scale, w}
end

function calculate(audio, config, dt)
    local n = config.entity_count

    -- Generate geometry
    if not state.vertices then
        state.vertices, state.edges = generate_tesseract()
    end

    state.rotation_xw = state.rotation_xw or 0
    state.rotation_yw = state.rotation_yw or 0
    state.rotation_zw = state.rotation_zw or 0
    state.rotation_xy = state.rotation_xy or 0
    state.pulse = state.pulse or 0

    -- Rotation speeds driven by frequency bands
    state.rotation_xw = state.rotation_xw + (0.2 + audio.bands[1] * 0.8) * dt
    state.rotation_yw = state.rotation_yw + (0.15 + audio.bands[3] * 0.6) * dt
    state.rotation_zw = state.rotation_zw + (0.1 + audio.bands[5] * 0.8) * dt
    state.rotation_xy = state.rotation_xy + 0.3 * dt

    -- Beat pulse
    if audio.is_beat then
        state.pulse = 1.0
    end
    state.pulse = decay(state.pulse, 0.9, dt)

    local entities = {}
    local center = 0.5
    local base_scale = 0.15 + state.pulse * 0.05

    -- Combine vertices and edge midpoints
    local all_points = {}
    for _, v in ipairs(state.vertices) do
        all_points[#all_points + 1] = v
    end
    for _, e in ipairs(state.edges) do
        all_points[#all_points + 1] = e
    end
    local points_to_use = math.min(n, #all_points)

    for i = 1, points_to_use do
        local point = all_points[i]

        -- Apply 4D rotations
        local rotated = rotate_4d(
            point,
            state.rotation_xw,
            state.rotation_yw,
            state.rotation_zw,
            state.rotation_xy
        )

        -- Project to 3D
        local projected = project_4d_to_3d(rotated)
        local px, py, pz, pw = projected[1], projected[2], projected[3], projected[4]

        -- Position in world
        local x = center + px * base_scale
        local y = center + py * base_scale
        local z = center + pz * base_scale

        -- Band based on w coordinate
        local w_normalized = (pw + 1) / 2
        local band_idx = math.floor(w_normalized * 4.9)
        band_idx = clamp(band_idx, 0, 4)

        -- Scale based on w
        local w_scale = 0.7 + w_normalized * 0.6
        local scale = config.base_scale * w_scale
        scale = scale + audio.bands[math.floor(band_idx) + 1] * 0.4

        if audio.is_beat then
            scale = scale * 1.3
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, scale),
            rotation = ((state.rotation_xy + pw * 0.8 + i * 0.04) * 180 / math.pi) % 360,
            band = math.floor(band_idx),
            visible = true,
        }
    end

    -- Fill remaining with inner vertices at smaller scale
    for i = points_to_use + 1, n do
        local point_idx = ((i - 1) % #state.vertices) + 1
        local point = state.vertices[point_idx]

        local rotated = rotate_4d(
            point,
            state.rotation_xw * 0.5,
            state.rotation_yw * 0.5,
            state.rotation_zw * 0.5,
            state.rotation_xy
        )
        local projected = project_4d_to_3d(rotated)
        local px, py, pz = projected[1], projected[2], projected[3]

        local inner_scale = base_scale * 0.5

        local x = center + px * inner_scale
        local y = center + py * inner_scale
        local z = center + pz * inner_scale

        local band_idx = (i - 1) % 5
        local scale = config.base_scale * 0.6 + audio.bands[band_idx + 1] * 0.2

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, scale),
            rotation = ((state.rotation_xy + i * 0.06) * 180 / math.pi) % 360,
            band = band_idx,
            visible = true,
        }
    end

    return entities
end
`,
  },
  {
    id: "aurora",
    name: "Aurora",
    description: "Northern lights curtains - flowing waves",
    category: "Organic",
    staticCamera: false,
    startBlocks: 64,
    source: `-- Pattern metadata
name = "Aurora"
description = "Northern lights curtains - flowing waves"
recommended_entities = 64
category = "Organic"
static_camera = false

-- Per-instance state
state = {
    wave_time = 0.0,
    ripple_origins = {},  -- {x, time} pairs
    color_offset = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.wave_time = state.wave_time + dt

    -- Color sweep
    state.color_offset = state.color_offset + (audio.amplitude * 0.02 + 0.005) * (dt / 0.016)

    -- Beat triggers new ripple
    if audio.is_beat then
        local ripple_x = math.random()  -- Random horizontal position
        state.ripple_origins[#state.ripple_origins + 1] = {x = ripple_x, time = 0.0}
    end

    -- Update ripples
    local new_ripples = {}
    for _, ripple in ipairs(state.ripple_origins) do
        ripple.time = ripple.time + (0.03 + audio.amplitude * 0.02) * (dt / 0.016)  -- Ripple expands
        if ripple.time < 2.0 then  -- Keep ripple alive for ~2 units
            new_ripples[#new_ripples + 1] = ripple
        end
    end
    state.ripple_origins = new_ripples

    -- 3 curtain layers with different depths
    local num_layers = 3
    local points_per_layer = math.floor(n / num_layers)

    for layer = 0, num_layers - 1 do
        local layer_depth = 0.3 + layer * 0.2  -- Z position for parallax
        local layer_speed = 1.0 + layer * 0.3  -- Different wave speeds

        for j = 0, points_per_layer - 1 do
            local idx = layer * points_per_layer + j
            if idx >= n then break end

            -- Horizontal position across curtain
            local x_norm = j / math.max(1, points_per_layer - 1)
            local x = 0.1 + x_norm * 0.8

            -- Flowing wave motion (multiple sine waves)
            local wave1 = math.sin(x_norm * math.pi * 3 + state.wave_time * layer_speed) * 0.1
            local wave2 = math.sin(x_norm * math.pi * 5 - state.wave_time * 0.7 * layer_speed) * 0.05
            local wave3 = math.sin(x_norm * math.pi * 2 + state.wave_time * 1.3) * 0.08

            -- Combine waves
            local wave_offset = wave1 + wave2 + wave3

            -- Add ripple effects from beats
            local ripple_offset = 0
            for _, ripple in ipairs(state.ripple_origins) do
                local dist = math.abs(x_norm - ripple.x)
                if dist < ripple.time and ripple.time - dist < 0.5 then
                    local ripple_strength = (0.5 - (ripple.time - dist)) * 2
                    ripple_offset = ripple_offset +
                        math.sin((ripple.time - dist) * math.pi * 4) * 0.1 * ripple_strength
                end
            end

            -- Y position (curtains hang from top)
            local base_y = 0.7 - layer * 0.1
            local y = base_y + wave_offset + ripple_offset

            -- Height variation based on audio
            -- layer*2: layer 0->band 1, layer 1->band 3, layer 2->band 5
            local audio_band = math.min(layer * 2 + 1, 5)
            y = y + audio.bands[audio_band] * 0.15

            local z = center + (layer_depth - 0.5)

            -- Color band based on horizontal position + sweep
            local color_pos = (x_norm + state.color_offset) % 1.0
            local band_idx = math.floor(color_pos * 4.9)
            band_idx = math.max(0, math.min(4, band_idx))

            -- Scale pulses with corresponding band
            local scale = config.base_scale + audio.bands[band_idx + 1] * 0.35
            scale = scale + ripple_offset * 2  -- Ripples make particles larger

            if audio.is_beat then
                scale = scale * 1.25
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "fireflies",
    name: "Fireflies",
    description: "Swarm of synchronized flashing lights",
    category: "Organic",
    staticCamera: false,
    startBlocks: 40,
    source: `-- Pattern metadata
name = "Fireflies"
description = "Swarm of synchronized flashing lights"
recommended_entities = 40
category = "Organic"
static_camera = false

-- Per-instance state
state = {
    fireflies = {},          -- {x, y, z, vx, vy, vz, glow_phase, group}
    group_flash = {0.0, 0.0, 0.0, 0.0},  -- Flash intensity per group (1-indexed)
    cascade_timer = 0.0,
    cascade_active = false,
    drift_time = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count
    local center = 0.5

    -- Initialize fireflies
    if #state.fireflies ~= n then
        state.fireflies = {}
        for i = 1, n do
            state.fireflies[i] = {
                x = center + (math.random() - 0.5) * 0.6,
                y = 0.2 + math.random() * 0.6,
                z = center + (math.random() - 0.5) * 0.6,
                vx = (math.random() - 0.5) * 0.01,
                vy = (math.random() - 0.5) * 0.01,
                vz = (math.random() - 0.5) * 0.01,
                glow_phase = math.random() * math.pi * 2,
                group = ((i - 1) % 4) + 1,  -- Groups 1-4 (1-indexed)
            }
        end
    end

    state.drift_time = state.drift_time + dt

    -- Beat triggers cascade
    if audio.is_beat then
        state.group_flash[1] = 1.0
        state.cascade_active = true
        state.cascade_timer = 0.0
    end

    -- Cascade to other groups with delays (100ms = 0.1s)
    if state.cascade_active then
        state.cascade_timer = state.cascade_timer + dt
        if state.cascade_timer > 0.1 and state.group_flash[2] < 0.5 then
            state.group_flash[2] = 1.0
        end
        if state.cascade_timer > 0.2 and state.group_flash[3] < 0.5 then
            state.group_flash[3] = 1.0
        end
        if state.cascade_timer > 0.3 and state.group_flash[4] < 0.5 then
            state.group_flash[4] = 1.0
        end
        if state.cascade_timer > 0.5 then
            state.cascade_active = false
        end
    end

    -- Decay group flashes
    for g = 1, 4 do
        state.group_flash[g] = decay(state.group_flash[g], 0.92, dt)
    end

    local entities = {}

    for i = 1, n do
        local ff = state.fireflies[i]
        local x, y, z = ff.x, ff.y, ff.z
        local vx, vy, vz = ff.vx, ff.vy, ff.vz
        local glow_phase = ff.glow_phase
        local group = ff.group

        -- Organic drifting motion (Perlin-like smooth random walk)
        local t = state.drift_time + i * 0.1
        local ax = math.sin(t * 0.5 + i) * 0.0002
        local ay = math.sin(t * 0.3 + i * 1.3) * 0.0001
        local az = math.cos(t * 0.4 + i * 0.7) * 0.0002

        -- Center bias: cubic pull toward center, strong near edges
        local cx, cy, cz = 0.5 - x, 0.5 - y, 0.5 - z
        local dx, dy, dz = math.abs(cx), math.abs(cy), math.abs(cz)
        local bias = 0.008
        ax = ax + cx * dx * dx * bias
        ay = ay + cy * dy * dy * bias
        az = az + cz * dz * dz * bias

        -- Update velocity with drift + bias acceleration
        vx = decay(vx, 0.98, dt) + ax * (dt / 0.016)
        vy = decay(vy, 0.98, dt) + ay * (dt / 0.016)
        vz = decay(vz, 0.98, dt) + az * (dt / 0.016)

        -- Clamp velocity
        local max_v = 0.015
        vx = clamp(vx, -max_v, max_v)
        vy = clamp(vy, -max_v, max_v)
        vz = clamp(vz, -max_v, max_v)

        -- Update position
        x = x + vx * (dt / 0.016)
        y = y + vy * (dt / 0.016)
        z = z + vz * (dt / 0.016)

        -- Hard boundary: clamp position and kill outward velocity
        if x < 0.1 then x = 0.1; if vx < 0 then vx = 0 end end
        if x > 0.9 then x = 0.9; if vx > 0 then vx = 0 end end
        if y < 0.1 then y = 0.1; if vy < 0 then vy = 0 end end
        if y > 0.9 then y = 0.9; if vy > 0 then vy = 0 end end
        if z < 0.1 then z = 0.1; if vz < 0 then vz = 0 end end
        if z > 0.9 then z = 0.9; if vz > 0 then vz = 0 end end

        -- Update glow phase
        glow_phase = glow_phase + (0.05 + math.random() * 0.02) * (dt / 0.016)

        -- Store updated values
        state.fireflies[i].x = x
        state.fireflies[i].y = y
        state.fireflies[i].z = z
        state.fireflies[i].vx = vx
        state.fireflies[i].vy = vy
        state.fireflies[i].vz = vz
        state.fireflies[i].glow_phase = glow_phase

        -- Individual glow cycle
        local individual_glow = (math.sin(glow_phase) + 1) * 0.3  -- 0 to 0.6

        -- Group flash overlay
        local group_glow = state.group_flash[group]

        -- Combined glow
        local total_glow = individual_glow + group_glow

        -- Audio reactivity - fireflies respond to amplitude
        total_glow = total_glow + audio.amplitude * 0.2

        -- Band based on group (groups 1-4 map to bands 0-3, covering bass through high-mid)
        local band_idx = group - 1  -- groups 1-4 → bands 0-3 (0-indexed output)

        local scale = config.base_scale * 0.5 + total_glow * 0.6
        scale = scale + audio.bands[group] * 0.2  -- bands[1]=bass, [2]=low, [3]=mid, [4]=high-mid

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = x,
            y = y,
            z = z,
            scale = math.min(config.max_scale, math.max(0.05, scale)),
            rotation = 0,
            band = band_idx,
            visible = total_glow > 0.15,  -- Only visible when glowing enough
        }
    end

    return entities
end
`,
  },
  {
    id: "ocean",
    name: "Ocean Waves",
    description: "Water surface with splashes and ripples",
    category: "Organic",
    staticCamera: false,
    startBlocks: 100,
    source: `-- Pattern metadata
name = "Ocean Waves"
description = "Water surface with splashes and ripples"
recommended_entities = 100
category = "Organic"
static_camera = false

-- Per-instance state
state = {
    wave_time = 0.0,
    splashes = {},  -- {x, z, time, intensity}
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.wave_time = state.wave_time + dt

    -- Beat triggers splash
    if audio.is_beat then
        local splash_x = 0.2 + math.random() * 0.6
        local splash_z = 0.2 + math.random() * 0.6
        state.splashes[#state.splashes + 1] = {
            x = splash_x, z = splash_z, time = 0.0, intensity = (audio.beat_intensity or 0.5)
        }
    end

    -- Update splashes (fade over 3 seconds)
    local new_splashes = {}
    for _, splash in ipairs(state.splashes) do
        splash.time = splash.time + dt
        if splash.time < 3.0 then
            new_splashes[#new_splashes + 1] = splash
        end
    end
    state.splashes = new_splashes

    -- Create grid
    local grid_size = math.floor(math.sqrt(n))
    if grid_size < 2 then grid_size = 2 end

    for i = 0, grid_size - 1 do
        for j = 0, grid_size - 1 do
            local idx = i * grid_size + j
            if idx >= n then break end

            -- Grid position
            local x_norm = i / (grid_size - 1)
            local z_norm = j / (grid_size - 1)

            local x = 0.1 + x_norm * 0.8
            local z = 0.1 + z_norm * 0.8

            -- === Wave interference ===
            -- Large waves (bass)
            local wave_bass = math.sin(x_norm * math.pi * 2 + state.wave_time * 0.5) * 0.08
            wave_bass = wave_bass + math.sin(z_norm * math.pi * 1.5 + state.wave_time * 0.3) * 0.06
            wave_bass = wave_bass * (0.5 + audio.bands[1] * 1.5)

            -- Medium waves (mids)
            local wave_mid = math.sin(x_norm * math.pi * 4 + state.wave_time * 1.2) * 0.04
            wave_mid = wave_mid + math.sin(z_norm * math.pi * 3 - state.wave_time * 0.8) * 0.03
            wave_mid = wave_mid * (0.3 + audio.bands[3] + audio.bands[4])

            -- Small shimmer (highs)
            local wave_high = math.sin(x_norm * math.pi * 8 + state.wave_time * 3) * 0.015
            wave_high = wave_high + math.sin(z_norm * math.pi * 7 - state.wave_time * 2.5) * 0.01
            wave_high = wave_high * (0.2 + audio.bands[4] * 2 + audio.bands[5] * 2)

            -- Combine waves
            local y = center + wave_bass + wave_mid + wave_high

            -- === Ripple rings from splashes ===
            local ripple_height = 0
            for _, splash in ipairs(state.splashes) do
                local dist = math.sqrt((x - splash.x) ^ 2 + (z - splash.z) ^ 2)
                local ripple_radius = splash.time * 0.3  -- Expands over time
                local ripple_width = 0.08

                -- Check if point is near ripple ring
                if math.abs(dist - ripple_radius) < ripple_width then
                    -- Ripple strength fades over time
                    local fade = 1.0 - (splash.time / 3.0)
                    local ring_strength = 1.0 - math.abs(dist - ripple_radius) / ripple_width
                    ripple_height = ripple_height +
                        math.sin(dist * 20 - splash.time * 10) * 0.1 * splash.intensity * fade * ring_strength
                end
            end

            y = y + ripple_height

            -- Band based on wave activity
            local wave_activity = math.abs(wave_bass) + math.abs(wave_mid) + math.abs(wave_high)
            local band_idx = math.floor(wave_activity * 10) % 5

            local scale = config.base_scale + wave_activity * 2
            scale = scale + ripple_height * 3  -- Splashes make bigger particles

            if audio.is_beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = 0,
                band = band_idx,
                visible = true,
            }
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "bars",
    name: "Spectrum Bars",
    description: "Classic vertical frequency bars",
    category: "Spectrum",
    staticCamera: true,
    startBlocks: 96,
    source: `-- Pattern metadata
name = "Spectrum Bars"
description = "Classic vertical frequency bars"
category = "Spectrum"
static_camera = true
start_blocks = 96

-- Per-instance state
state = {
    smooth_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
    peak_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
    peak_fall = {0.0, 0.0, 0.0, 0.0, 0.0},
    rotation = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5
    if n <= 0 then
        return entities
    end
    state.rotation = state.rotation + (0.4 + audio.amplitude * 0.9) * dt

    -- Smooth the band values
    for i = 1, 5 do
        local target = audio.bands[i]
        state.smooth_heights[i] = smooth(state.smooth_heights[i], target, 0.3, dt)

        -- Peak hold with fall
        if state.smooth_heights[i] > state.peak_heights[i] then
            state.peak_heights[i] = state.smooth_heights[i]
            state.peak_fall[i] = 0.0
        else
            state.peak_fall[i] = state.peak_fall[i] + 0.02 * (dt / 0.016)
            state.peak_heights[i] = state.peak_heights[i] - state.peak_fall[i] * dt
        end

        state.peak_heights[i] = math.max(0, state.peak_heights[i])
    end

    -- Fewer bars = more blocks per bar = taller vertical columns
    local num_bars = math.max(5, math.min(10, math.floor(n / 10)))
    num_bars = math.max(1, math.min(num_bars, n))
    local blocks_per_bar = math.max(1, math.floor(n / num_bars))
    local span = 0.72
    local start_x = center - span * 0.5
    local bar_spacing = (num_bars > 1) and (span / (num_bars - 1)) or 0

    local function sample5(values, t)
        local p = clamp(t, 0.0, 1.0) * 4.0
        local i0 = math.floor(p) + 1
        local i1 = math.min(5, i0 + 1)
        local f = p - math.floor(p)
        return lerp(values[i0], values[i1], f)
    end

    local entity_idx = 0

    for bar = 0, num_bars - 1 do
        if entity_idx >= n then break end
        local bar_x = start_x + bar * bar_spacing
        local t = (num_bars > 1) and (bar / (num_bars - 1)) or 0.0
        local bar_height = sample5(state.smooth_heights, t)
        local bar_band = math.floor(t * 4 + 0.5)

        -- Stack blocks vertically for this bar
        for j = 0, blocks_per_bar - 1 do
            if entity_idx >= n then break end

            -- Normalized position in bar (0 = bottom, 1 = top of max)
            local block_y_norm = (j + 0.5) / blocks_per_bar

            -- Only show blocks up to current height
            local max_height = 0.7
            local block_y = 0.1 + block_y_norm * max_height

            -- Visibility based on bar height
            local visible = block_y_norm <= bar_height

            -- Scale - slightly larger when at the top of the current level
            local scale = config.base_scale
            if visible then
                -- Blocks near the top of current height are brighter
                local top_proximity = 1.0 - math.abs(block_y_norm - bar_height) * 3
                top_proximity = math.max(0, top_proximity)
                scale = scale + top_proximity * 0.3
            end

            if audio.is_beat then
                scale = scale * 1.2
            end

            -- Slight depth variation per bar
            local z = center + (t - 0.5) * 0.06

            local final_scale = scale
            if not visible then final_scale = 0.01 end
            local rot = (state.rotation + t * math.pi * 1.4 + block_y_norm * math.pi * 0.8) * 180 / math.pi

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(bar_x),
                y = clamp(block_y),
                z = clamp(z),
                scale = math.min(config.max_scale, final_scale),
                rotation = rot % 360,
                band = bar_band,
                visible = visible,
            }
            entity_idx = entity_idx + 1
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "circle",
    name: "Spectrum Circle",
    description: "Radial frequency bars in a circle",
    category: "Spectrum",
    staticCamera: true,
    startBlocks: 100,
    source: `-- Pattern metadata
name = "Spectrum Circle"
description = "Radial frequency bars in a circle"
category = "Spectrum"
static_camera = true
start_blocks = 100

-- Per-instance state
state = {
    smooth_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
    rotation = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5
    if n <= 0 then
        return entities
    end

    -- Slow rotation
    state.rotation = state.rotation + (0.1 + audio.amplitude * 0.2) * dt

    -- Smooth the band values
    for i = 1, 5 do
        local target = audio.bands[i]
        state.smooth_heights[i] = smooth(state.smooth_heights[i], target, 0.3, dt)
    end

    local function sample5(values, t)
        local p = clamp(t, 0.0, 1.0) * 4.0
        local i0 = math.floor(p) + 1
        local i1 = math.min(5, i0 + 1)
        local f = p - math.floor(p)
        return lerp(values[i0], values[i1], f)
    end

    -- Dynamic radial granularity: more blocks -> more spokes.
    local num_segments = math.max(10, math.min(64, math.floor(n / 3)))
    num_segments = math.max(1, math.min(num_segments, n))
    local points_per_segment = math.max(1, math.floor(n / num_segments))
    local base_radius = 0.15
    local max_bar_length = 0.25

    local entity_idx = 0

    for seg = 0, num_segments - 1 do
        if entity_idx >= n then break end
        local seg_angle = state.rotation + (seg / num_segments) * math.pi * 2
        local t = (num_segments > 1) and (seg / (num_segments - 1)) or 0.0
        local seg_height = sample5(state.smooth_heights, t)
        local band_sample = sample5(audio.bands, t)

        for j = 0, points_per_segment - 1 do
            if entity_idx >= n then break end

            -- Position along the bar (from center outward)
            local bar_pos = (j + 0.5) / points_per_segment
            local visible = bar_pos <= seg_height + 0.1

            -- Radius from center
            local r = base_radius + bar_pos * max_bar_length

            local x = center + math.cos(seg_angle) * r
            local z = center + math.sin(seg_angle) * r
            local y = center  -- Flat on horizontal plane

            -- Scale
            local band_idx = math.floor(t * 4 + 0.5)
            local scale = config.base_scale
            if visible then
                -- Brighter at the end
                if bar_pos > seg_height - 0.2 then
                    scale = scale * 1.3
                end
                scale = scale + band_sample * 0.2
            end

            if audio.is_beat then
                scale = scale * 1.2
            end

            local final_scale = scale
            if not visible then final_scale = 0.01 end
            local rot = (seg_angle + bar_pos * math.pi * 0.9) * 180 / math.pi

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, final_scale),
                rotation = rot % 360,
                band = band_idx,
                visible = visible,
            }
            entity_idx = entity_idx + 1
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "tubes",
    name: "Spectrum Tubes",
    description: "3D cylindrical frequency tubes",
    category: "Spectrum",
    staticCamera: true,
    startBlocks: 120,
    source: `-- Pattern metadata
name = "Spectrum Tubes"
description = "3D cylindrical frequency tubes"
category = "Spectrum"
static_camera = true
start_blocks = 120

-- Per-instance state
state = {
    smooth_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
    rotation = 0.0,
    pulse = {0.0, 0.0, 0.0, 0.0, 0.0},
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5
    if n <= 0 then
        return entities
    end

    state.rotation = state.rotation + 0.3 * dt

    -- Smooth the band values and track pulses
    for i = 1, 5 do
        local target = audio.bands[i]
        state.smooth_heights[i] = smooth(state.smooth_heights[i], target, 0.25, dt)

        -- Pulse on beat
        if audio.is_beat then
            state.pulse[i] = 0.5
        end
        state.pulse[i] = decay(state.pulse[i], 0.9, dt)
    end

    local function sample5(values, t)
        local p = clamp(t, 0.0, 1.0) * 4.0
        local i0 = math.floor(p) + 1
        local i1 = math.min(5, i0 + 1)
        local f = p - math.floor(p)
        return lerp(values[i0], values[i1], f)
    end

    -- Dynamic tube count: more blocks = more spectrum detail across X.
    local num_tubes = math.max(5, math.min(18, math.floor(n / 8)))
    num_tubes = math.max(1, math.min(num_tubes, n))
    local span = 0.72
    local tube_spacing = (num_tubes > 1) and (span / (num_tubes - 1)) or 0
    local start_x = center - span * 0.5

    -- Points per tube
    local points_per_tube = math.max(1, math.floor(n / num_tubes))
    local rings_per_tube = math.max(3, math.floor(points_per_tube / 4))
    local points_per_ring = math.max(1, math.floor(points_per_tube / rings_per_tube))

    local entity_idx = 0

    for tube = 0, num_tubes - 1 do
        if entity_idx >= n then break end
        local tube_x = start_x + tube * tube_spacing
        local t = (num_tubes > 1) and (tube / (num_tubes - 1)) or 0.0
        local tube_height = sample5(state.smooth_heights, t)
        local tube_pulse = sample5(state.pulse, t)
        local tube_radius = 0.022 + tube_pulse * 0.02
        local tube_band = math.floor(t * 4 + 0.5)

        for ring = 0, rings_per_tube - 1 do
            if entity_idx >= n then break end

            -- Ring Y position
            local ring_y_norm = (ring + 0.5) / rings_per_tube
            local max_height = 0.65
            local ring_y = 0.1 + ring_y_norm * max_height

            -- Only show rings up to current height
            local visible = ring_y_norm <= tube_height + 0.1

            -- Ring radius varies slightly
            local current_radius = tube_radius * (1.0 + ring_y_norm * 0.2)

            for p = 0, points_per_ring - 1 do
                if entity_idx >= n then break end

                -- Angle around ring
                local angle = state.rotation + (p / points_per_ring) * math.pi * 2
                angle = angle + tube * 0.35  -- Offset per tube

                -- Position
                local x = tube_x + math.cos(angle) * current_radius
                local z = center + math.sin(angle) * current_radius

                -- Scale based on visibility and position
                local scale = config.base_scale * 0.7
                if visible then
                    -- Glow near top
                    if ring_y_norm > tube_height - 0.15 then
                        scale = scale * 1.5
                    end
                    scale = scale + sample5(audio.bands, t) * 0.3
                end

                if audio.is_beat then
                    scale = scale * 1.15
                end

                local final_scale = scale
                if not visible then final_scale = 0.01 end
                local rot = (angle + ring_y_norm * math.pi * 0.6) * 180 / math.pi

                entities[#entities + 1] = {
                    id = string.format("block_%d", entity_idx),
                    x = clamp(x),
                    y = clamp(ring_y),
                    z = clamp(z),
                    scale = math.min(config.max_scale, final_scale),
                    rotation = rot % 360,
                    band = tube_band,
                    visible = visible,
                }
                entity_idx = entity_idx + 1
            end
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "dropsequence",
    name: "Drop Sequence",
    description: "EDM build-up tension over 16 beats, then explosive drop — the tension-release cycle",
    category: "Mainstage",
    staticCamera: true,
    startBlocks: 80,
    source: `-- Pattern metadata
name = "Drop Sequence"
description = "EDM build-up tension over 16 beats, then explosive drop — the tension-release cycle"
category = "Mainstage"
static_camera = true
recommended_entities = 80

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize state
    if not state.phase then
        state.phase = "build"
        state.build_progress = 0
        state.build_beats = 0
        state.drop_timer = 0
        state.orbit_phase = 0
        state.time = 0
        state.positions = {}
        state.velocities = {}
        state.flash = 0

        -- Initialize positions on fibonacci sphere (cache for reuse)
        state.sphere = fibonacci_sphere(n)
        for i = 1, n do
            state.positions[i] = {
                x = 0.5 + state.sphere[i].x * 0.4,
                y = 0.5 + state.sphere[i].y * 0.4,
                z = 0.5 + state.sphere[i].z * 0.4,
            }
            state.velocities[i] = {x = 0, y = 0, z = 0}
        end
    end

    state.time = state.time + dt

    -- Target build beats based on BPM
    local target_beats = 16
    if audio.bpm > 170 then
        target_beats = 8
    elseif audio.bpm > 140 then
        target_beats = 12
    end

    -- Beat handling
    if audio.is_beat then
        if state.phase == "build" then
            state.build_beats = state.build_beats + 1
            state.build_progress = math.min(1.0, state.build_beats / target_beats)

            -- Check for drop trigger
            if state.build_progress >= 1.0 and (audio.beat_intensity or 0) > 0.35 then
                state.phase = "drop"
                state.drop_timer = 0
                state.flash = 1.0

                -- Generate explosion velocities using cached sphere
                for i = 1, n do
                    local speed = 1.2 + math.random() * 0.6
                    -- Amplitude scales explosion
                    speed = speed * (0.8 + audio.amplitude * 0.5)
                    state.velocities[i] = {
                        x = state.sphere[i].x * speed,
                        y = state.sphere[i].y * speed + 0.3,  -- slight upward bias
                        z = state.sphere[i].z * speed,
                    }
                end
            end

        elseif state.phase == "drop" then
            state.flash = 0.8  -- re-flash on beats during drop
        end
    end

    -- Phase-specific update
    if state.phase == "build" then
        -- Orbit entities in a contracting sphere
        local build_t = state.build_progress
        local radius = lerp(0.35, 0.04, build_t)
        local orbit_speed = 1.0 + build_t * 5.0

        state.orbit_phase = state.orbit_phase + orbit_speed * dt

        -- Bass wobble on radius
        local wobble = audio.bands[1] * 0.03

        for i = 1, n do
            local r = radius + wobble * math.sin(i * 0.5 + state.time * 3)

            -- Apply orbit rotation using cached sphere points
            local sx = state.sphere[i].x * r
            local sy = state.sphere[i].y * r
            local sz = state.sphere[i].z * r

            -- Rotate around Y axis
            local angle = state.orbit_phase + (i / n) * 0.3
            local cos_a = math.cos(angle)
            local sin_a = math.sin(angle)
            local rx = sx * cos_a - sz * sin_a
            local rz = sx * sin_a + sz * cos_a

            state.positions[i].x = smooth(state.positions[i].x, 0.5 + rx, 0.2, dt)
            state.positions[i].y = smooth(state.positions[i].y, 0.5 + sy, 0.2, dt)
            state.positions[i].z = smooth(state.positions[i].z, 0.5 + rz, 0.2, dt)
        end

    elseif state.phase == "drop" then
        state.drop_timer = state.drop_timer + dt
        state.flash = decay(state.flash, 0.85, dt)

        -- Update positions with explosion velocities + gravity
        for i = 1, n do
            local v = state.velocities[i]
            local p = state.positions[i]

            p.x = p.x + v.x * dt
            p.y = p.y + v.y * dt
            p.z = p.z + v.z * dt

            -- Gravity
            v.y = v.y - 0.5 * dt

            -- Velocity decay (air resistance)
            v.x = v.x * (1.0 - 2.0 * dt)
            v.z = v.z * (1.0 - 2.0 * dt)
        end

        -- Reset after drop completes
        if state.drop_timer > 2.5 then
            state.phase = "build"
            state.build_progress = 0
            state.build_beats = 0
            state.drop_timer = 0
            state.orbit_phase = 0
            state.flash = 0

            -- Reset positions to sphere using cached points
            for i = 1, n do
                state.positions[i] = {
                    x = 0.5 + state.sphere[i].x * 0.35,
                    y = 0.5 + state.sphere[i].y * 0.35,
                    z = 0.5 + state.sphere[i].z * 0.35,
                }
                state.velocities[i] = {x = 0, y = 0, z = 0}
            end
        end
    end

    -- Render entities
    local entities = {}
    local build_t = state.build_progress

    for i = 1, n do
        local p = state.positions[i]

        local scale, brightness, glow, material, visible, interp
        local band = (i - 1) % 5

        if state.phase == "build" then
            -- Scale shrinks as entities compress
            scale = config.base_scale * (1.0 - build_t * 0.5)

            -- Brightness ramps up with build
            brightness = math.floor(5 + build_t * 10)

            -- Tension flicker at >70% progress
            visible = true
            if build_t > 0.7 then
                local flicker = simple_noise(i, math.floor(state.time * 15), 0)
                visible = flicker > (build_t - 0.7) * 2  -- more flicker as progress increases
            end

            glow = build_t > 0.5
            material = build_t > 0.8 and "GLOWSTONE" or "WHITE_CONCRETE"
            interp = 5  -- smooth orbiting

        else  -- drop
            local drop_life = math.max(0, 1.0 - state.drop_timer / 2.5)

            scale = config.base_scale * (0.5 + state.flash * 1.0) * drop_life
            brightness = math.floor(clamp(state.flash + drop_life * 0.5) * 15)
            glow = drop_life > 0.3
            material = drop_life > 0.5 and "GLOWSTONE" or "WHITE_CONCRETE"
            visible = drop_life > 0.05
            interp = 1  -- fast for explosion
        end

        entities[i] = {
            id = string.format("block_%d", i - 1),
            x = clamp(p.x),
            y = clamp(p.y),
            z = clamp(p.z),
            scale = math.min(config.max_scale, math.max(0.01, scale)),
            rotation = (state.orbit_phase * 50 + i * 10) % 360,
            band = band,
            visible = visible,
            glow = glow,
            brightness = math.min(15, brightness),
            material = material,
            interpolation = interp,
        }
    end

    return entities
end
`,
  },
  {
    id: "laserfan",
    name: "Laser Fan",
    description: "Floor-origin laser beams sweep in synchronized arcs and freeze on beat",
    category: "Mainstage",
    staticCamera: true,
    startBlocks: 96,
    source: `-- Pattern metadata
name = "Laser Fan"
description = "Floor-origin laser beams sweep in synchronized arcs and freeze on beat"
category = "Mainstage"
static_camera = true
recommended_entities = 96

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count
    local num_beams = 8
    local points_per_beam = math.max(2, math.floor(n / num_beams))

    -- Initialize state
    if not state.sweep_phase then
        state.sweep_phase = 0
        state.freeze_timer = 0
        state.smooth_bass = 0
        state.smooth_high = 0
    end

    -- Smooth inputs
    state.smooth_bass = smooth(state.smooth_bass, audio.bands[1], 0.35, dt)
    state.smooth_high = smooth(state.smooth_high, audio.bands[5], 0.3, dt)

    -- Beat: freeze sweep momentarily
    if audio.is_beat then
        state.freeze_timer = 0.15
    end

    -- Update freeze timer
    if state.freeze_timer > 0 then
        state.freeze_timer = state.freeze_timer - dt
    else
        -- Advance sweep, speed tied to BPM
        local sweep_speed = 1.0
        if audio.bpm > 0 then
            sweep_speed = audio.bpm / 128.0
        end
        state.sweep_phase = state.sweep_phase + sweep_speed * dt
    end

    -- Fan geometry
    -- Fan half-angle widens with bass
    local fan_half = 0.3 + state.smooth_bass * 0.5
    -- Beam length scales with amplitude
    local beam_length = 0.4 + audio.amplitude * 0.3

    -- Origin point: bottom center
    local ox, oy, oz = 0.5, 0.1, 0.5

    -- Sweep oscillation
    local sweep_offset = math.sin(state.sweep_phase * math.pi * 2) * 0.3

    local entities = {}
    local idx = 0

    for b = 0, num_beams - 1 do
        -- Base angle for this beam within the fan
        local beam_frac = (b / math.max(1, num_beams - 1)) - 0.5  -- -0.5 to 0.5
        local beam_angle = beam_frac * fan_half * 2 + sweep_offset

        -- Each beam has a slight independent phase offset
        local phase_offset = math.sin(state.sweep_phase * 1.7 + b * 0.8) * 0.1
        beam_angle = beam_angle + phase_offset

        -- Beam direction: upward and outward
        -- angle controls left-right spread, beam goes upward
        local dir_x = math.sin(beam_angle)
        local dir_y = 1.0  -- always upward
        local dir_z = math.cos(beam_angle) * 0.3  -- slight depth

        -- Normalize direction
        local len = math.sqrt(dir_x * dir_x + dir_y * dir_y + dir_z * dir_z)
        dir_x, dir_y, dir_z = dir_x / len, dir_y / len, dir_z / len

        -- Beam visibility: dimmer beams disappear at low amplitude
        local beam_threshold = (b % 4) / 8  -- stagger thresholds
        local beam_visible = audio.amplitude > beam_threshold

        -- Band per beam for color variety
        local beam_band = b % 5

        for j = 0, points_per_beam - 1 do
            if idx >= n then break end

            local t = (j + 1) / points_per_beam
            local x = ox + dir_x * t * beam_length
            local y = oy + dir_y * t * beam_length
            local z = oz + dir_z * t * beam_length

            -- Scale tapers: thick at base, thin at tip
            local base_scale = lerp(0.15, 0.04, t)
            -- High frequencies add shimmer
            local shimmer = state.smooth_high * simple_noise(b, j, math.floor(state.sweep_phase * 10)) * 0.03
            local scale = base_scale + shimmer

            -- Intensity fades slightly along beam
            local intensity = 1.0 - t * 0.3

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = (beam_angle * 180 / math.pi + 90) % 360,
                band = beam_band,
                visible = beam_visible,
                glow = true,
                brightness = math.floor(intensity * 15),
                material = "END_ROD",
                interpolation = 2,
            }

            idx = idx + 1
        end
    end

    -- Fill remaining as invisible
    while idx < n do
        entities[#entities + 1] = {
            id = string.format("block_%d", idx),
            x = 0.5, y = 0.5, z = 0.5,
            scale = 0, rotation = 0, band = 0, visible = false,
        }
        idx = idx + 1
    end

    return entities
end
`,
  },
  {
    id: "ledwall",
    name: "LED Wall",
    description: "Giant LED screen with spectrum bars, waveform, color wash, and beat geometry modes",
    category: "Mainstage",
    staticCamera: true,
    startBlocks: 100,
    source: `-- Pattern metadata
name = "LED Wall"
description = "Giant LED screen with spectrum bars, waveform, color wash, and beat geometry modes"
category = "Mainstage"
static_camera = true
recommended_entities = 100

state = {}

local MATERIALS = {
    "RED_CONCRETE",
    "ORANGE_CONCRETE",
    "YELLOW_CONCRETE",
    "LIME_CONCRETE",
    "BLUE_CONCRETE",
}

local function sample_band(bands, col_frac)
    -- Interpolate 5 bands across a continuous 0-1 range
    local pos = col_frac * 4
    local lo = math.floor(pos)
    local hi = math.min(lo + 1, 4)
    local t = pos - lo
    return lerp(bands[lo + 1] or 0, bands[hi + 1] or 0, t)
end

function calculate(audio, config, dt)
    local n = config.entity_count
    local cols = math.max(2, math.floor(math.sqrt(n)))
    local rows = math.max(2, math.floor(n / cols))

    -- Initialize state
    if not state.mode then
        state.mode = 0
        state.mode_timer = 0
        state.beat_count = 0
        state.scroll_offset = 0
        state.amp_buffer = {}
        state.amp_idx = 1
        state.shape = 0
        state.smooth_bands = {0, 0, 0, 0, 0}
        state.flash = 0
    end

    -- Smooth bands for spectrum display
    for i = 1, 5 do
        state.smooth_bands[i] = smooth(state.smooth_bands[i], audio.bands[i], 0.4, dt)
    end

    -- Beat handling
    if audio.is_beat then
        state.beat_count = state.beat_count + 1
        state.flash = 1.0
        state.mode_timer = state.mode_timer + 1
        if state.mode_timer >= 32 then
            state.mode_timer = 0
            state.mode = (state.mode + 1) % 4
        end
        -- Update amplitude ring buffer (for waveform mode)
        state.amp_buffer[state.amp_idx] = audio.amplitude
        state.amp_idx = (state.amp_idx % cols) + 1
        -- Cycle geometry shape
        state.shape = (state.shape + 1) % 4
    end

    state.flash = decay(state.flash, 0.8, dt)
    state.scroll_offset = state.scroll_offset + dt * 0.3

    local entities = {}
    local idx = 0
    local mode = state.mode

    for row = 0, rows - 1 do
        for col = 0, cols - 1 do
            if idx >= n then break end

            local col_frac = col / math.max(1, cols - 1)
            local row_frac = row / math.max(1, rows - 1)
            local x = 0.1 + col_frac * 0.8
            local y = 0.1 + row_frac * 0.8

            local intensity = 0
            local band_idx = math.floor(col_frac * 4.99)
            local visible = true
            local pixel_scale = config.base_scale

            if mode == 0 then
                -- Spectrum bars: columns as frequency bars rising from bottom
                local bar_height = sample_band(state.smooth_bands, col_frac)
                if row_frac <= bar_height then
                    intensity = 0.5 + bar_height * 0.5
                    band_idx = math.floor(col_frac * 4.99)
                else
                    intensity = 0.05
                    visible = false
                end

            elseif mode == 1 then
                -- Waveform: ring buffer displayed as horizontal wave
                local buf_idx = ((col + math.floor(state.amp_idx)) % cols) + 1
                local amp_val = state.amp_buffer[buf_idx] or 0
                local wave_y = amp_val * 0.8
                local dist = math.abs(row_frac - wave_y)
                if dist < 0.15 then
                    intensity = 1.0 - dist / 0.15
                    band_idx = math.floor(amp_val * 4.99)
                else
                    intensity = 0.02
                    visible = false
                end

            elseif mode == 2 then
                -- Color wash: scrolling gradient
                local shifted = (col_frac + state.scroll_offset) % 1.0
                band_idx = math.floor(shifted * 4.99)
                local band_val = state.smooth_bands[band_idx + 1] or 0
                intensity = 0.3 + band_val * 0.7
                pixel_scale = config.base_scale * (0.8 + band_val * 0.4)

            elseif mode == 3 then
                -- Beat geometry: flash shapes on beat
                local shape = state.shape
                local cx = math.floor(cols / 2)
                local cy = math.floor(rows / 2)
                local in_shape = false

                if shape == 0 then
                    -- X shape: diagonals
                    in_shape = math.abs(col - row * cols / rows) < 1.5
                             or math.abs(col - (rows - 1 - row) * cols / rows) < 1.5
                elseif shape == 1 then
                    -- Diamond
                    local dx = math.abs(col - cx)
                    local dy = math.abs(row - cy)
                    in_shape = (dx / cx + dy / cy) < 0.8 and (dx / cx + dy / cy) > 0.5
                elseif shape == 2 then
                    -- Border
                    in_shape = col == 0 or col == cols - 1 or row == 0 or row == rows - 1
                elseif shape == 3 then
                    -- Cross
                    in_shape = math.abs(col - cx) <= 1 or math.abs(row - cy) <= 1
                end

                if in_shape then
                    intensity = state.flash
                    band_idx = (state.beat_count + col) % 5
                else
                    intensity = state.flash * 0.1
                    visible = state.flash > 0.3
                end
            end

            -- Global amplitude boost
            intensity = clamp(intensity + audio.amplitude * 0.1)

            local brightness = math.floor(clamp(intensity) * 15)
            local glow = intensity > 0.4
            local mat = MATERIALS[(band_idx % 5) + 1]

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = 0.5,
                scale = math.min(config.max_scale, pixel_scale * (0.5 + intensity * 0.5)),
                rotation = 0,
                band = band_idx % 5,
                visible = visible,
                glow = glow,
                brightness = brightness,
                material = glow and mat or "GRAY_CONCRETE",
                interpolation = 2,
            }

            idx = idx + 1
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
  {
    id: "movingheads",
    name: "Moving Heads",
    description: "Concert moving-head lights with sweeping beams and ballyhoo snap on beat",
    category: "Mainstage",
    staticCamera: true,
    startBlocks: 96,
    source: `-- Pattern metadata
name = "Moving Heads"
description = "Concert moving-head lights with sweeping beams and ballyhoo snap on beat"
category = "Mainstage"
static_camera = true
recommended_entities = 96

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count
    local num_fixtures = 8
    local points_per_beam = math.max(2, math.floor(n / num_fixtures))

    -- Initialize state
    if not state.time then
        state.time = 0
        state.snap_timer = 0
        state.fixtures = {}
        for i = 1, num_fixtures do
            state.fixtures[i] = {
                pan = 0,
                tilt = 0,
                target_pan = 0,
                target_tilt = 0,
            }
        end
    end

    state.time = state.time + dt

    -- Beat: trigger ballyhoo (all snap to center)
    if audio.is_beat then
        state.snap_timer = 1.0  -- full beat cycle to return
    end

    -- Sweep speed tied to BPM
    local sweep_speed = 0.5
    if audio.bpm > 0 then
        sweep_speed = audio.bpm / 200.0
    end

    -- Update fixture targets
    local snap_active = state.snap_timer > 0.7  -- snap during first 30% of timer
    state.snap_timer = math.max(0, state.snap_timer - dt * 2)

    for i = 1, num_fixtures do
        local fix = state.fixtures[i]
        local phase_offset = (i - 1) * 0.8

        if snap_active then
            -- Ballyhoo: all snap straight down center
            fix.target_pan = 0
            fix.target_tilt = -1.2  -- steep downward angle
        else
            -- Normal: Lissajous figure-8 sweep
            local t = state.time * sweep_speed
            fix.target_pan = math.sin(t + phase_offset) * 0.6
            fix.target_tilt = math.sin(t * 1.5 + phase_offset) * 0.4 - 0.8
        end

        -- Smooth toward target: fast during snap, slow during sweep
        local rate = snap_active and 0.6 or 0.12
        fix.pan = smooth(fix.pan, fix.target_pan, rate, dt)
        fix.tilt = smooth(fix.tilt, fix.target_tilt, rate, dt)
    end

    -- Beam reach scales with amplitude
    local beam_reach = 0.55 + audio.amplitude * 0.2

    local entities = {}
    local idx = 0

    for i = 1, num_fixtures do
        local fix = state.fixtures[i]

        -- Fixture position: top edge, evenly spaced
        local fixture_x = 0.12 + ((i - 1) / math.max(1, num_fixtures - 1)) * 0.76
        local fixture_y = 0.92
        local fixture_z = 0.5

        -- Beam direction from pan/tilt
        local dir_x = math.sin(fix.pan)
        local dir_y = fix.tilt  -- negative = downward
        local dir_z = math.cos(fix.pan) * 0.3

        -- Normalize
        local len = math.sqrt(dir_x * dir_x + dir_y * dir_y + dir_z * dir_z)
        if len > 0.001 then
            dir_x, dir_y, dir_z = dir_x / len, dir_y / len, dir_z / len
        end

        -- Per-fixture brightness driven by corresponding frequency band
        local fixture_band = ((i - 1) % 5)
        local band_val = audio.bands[fixture_band + 1] or 0
        local fixture_brightness = 10 + math.floor(band_val * 5)

        for j = 0, points_per_beam - 1 do
            if idx >= n then break end

            local t = (j + 1) / points_per_beam

            local x = fixture_x + dir_x * t * beam_reach
            local y = fixture_y + dir_y * t * beam_reach
            local z = fixture_z + dir_z * t * beam_reach

            -- Scale: slightly thicker beam than lasers, tapers at end
            local scale = lerp(0.12, 0.05, t)
            -- Snap pulse makes beams briefly larger
            if snap_active then
                scale = scale * 1.4
            end

            -- Intensity fades along beam
            local intensity = 1.0 - t * 0.4

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = (fix.pan * 180 / math.pi + 90) % 360,
                band = fixture_band,
                visible = true,
                glow = true,
                brightness = math.min(15, math.floor(fixture_brightness * intensity)),
                material = "SEA_LANTERN",
                interpolation = 3,
            }

            idx = idx + 1
        end
    end

    -- Fill remaining as invisible
    while idx < n do
        entities[#entities + 1] = {
            id = string.format("block_%d", idx),
            x = 0.5, y = 0.5, z = 0.5,
            scale = 0, rotation = 0, band = 0, visible = false,
        }
        idx = idx + 1
    end

    return entities
end
`,
  },
  {
    id: "pyro",
    name: "Pyro",
    description: "Beat-triggered firework bursts with ballistic physics and gravity",
    category: "Mainstage",
    staticCamera: true,
    startBlocks: 100,
    source: `-- Pattern metadata
name = "Pyro"
description = "Beat-triggered firework bursts with ballistic physics and gravity"
category = "Mainstage"
static_camera = true
recommended_entities = 100

state = {}

local BURST_MATERIALS = {
    "REDSTONE_BLOCK",
    "GOLD_BLOCK",
    "DIAMOND_BLOCK",
    "EMERALD_BLOCK",
    "LAPIS_BLOCK",
}

function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize state
    if not state.fireworks then
        state.fireworks = {}
        state.time = 0
        state.next_color = 1
    end

    state.time = state.time + dt

    -- Gravity strength: high amplitude = sparks hang longer
    local gravity = 0.4 - audio.amplitude * 0.15

    -- Launch new firework on beat (max 5 concurrent)
    if audio.is_beat then
        local active = 0
        for _, fw in ipairs(state.fireworks) do
            if fw.phase ~= "dead" then active = active + 1 end
        end
        if active < 5 then
            local peak_y = 0.55 + (audio.beat_intensity or 0.5) * 0.3
            state.fireworks[#state.fireworks + 1] = {
                phase = "rise",
                x = 0.3 + math.random() * 0.4,
                y = 0.05,
                z = 0.3 + math.random() * 0.4,
                peak_y = math.min(0.9, peak_y),
                rise_speed = 0.8 + (audio.beat_intensity or 0.5) * 0.4,
                sparks = {},
                life = 1.0,
                material = BURST_MATERIALS[state.next_color],
                band = (state.next_color - 1) % 5,
                trail = {},
            }
            state.next_color = (state.next_color % #BURST_MATERIALS) + 1
        end
    end

    -- Update fireworks
    for i = #state.fireworks, 1, -1 do
        local fw = state.fireworks[i]

        if fw.phase == "rise" then
            -- Rising: move upward toward peak
            fw.y = fw.y + fw.rise_speed * dt

            -- Store trail positions
            fw.trail[#fw.trail + 1] = {x = fw.x, y = fw.y, z = fw.z}
            if #fw.trail > 3 then
                table.remove(fw.trail, 1)
            end

            -- Reached peak: burst
            if fw.y >= fw.peak_y then
                fw.phase = "burst"
                fw.y = fw.peak_y

                -- Generate sparks using fibonacci sphere
                local spark_count = 15 + math.floor((audio.beat_intensity or 0.5) * 10)
                local dirs = fibonacci_sphere(spark_count)
                fw.sparks = {}
                for j = 1, spark_count do
                    local spread = 0.25 + math.random() * 0.15
                    -- Bass adds upward bias
                    local bass_boost = audio.bands[1] * 0.1
                    fw.sparks[j] = {
                        x = fw.x,
                        y = fw.y,
                        z = fw.z,
                        dx = dirs[j].x * spread,
                        dy = dirs[j].y * spread + bass_boost,
                        dz = dirs[j].z * spread,
                        life = 1.0,
                    }
                end
                fw.trail = {}  -- clear trail on burst
            end

        elseif fw.phase == "burst" then
            -- Update sparks
            local all_dead = true
            for _, spark in ipairs(fw.sparks) do
                if spark.life > 0 then
                    all_dead = false
                    spark.x = spark.x + spark.dx * dt
                    spark.y = spark.y + spark.dy * dt
                    spark.z = spark.z + spark.dz * dt
                    spark.dy = spark.dy - gravity * dt
                    -- Velocity drag
                    spark.dx = spark.dx * (1.0 - 1.5 * dt)
                    spark.dz = spark.dz * (1.0 - 1.5 * dt)
                    spark.life = spark.life - dt * 0.7

                    -- High frequencies add sparkle (scale flicker)
                    if audio.bands[5] > 0.3 then
                        spark.life = spark.life + dt * 0.1  -- slightly longer sparkle
                    end
                end
            end

            if all_dead then
                fw.phase = "dead"
            end
        end

        -- Remove dead fireworks
        if fw.phase == "dead" then
            table.remove(state.fireworks, i)
        end
    end

    -- Render entities
    local entities = {}
    local idx = 0

    for _, fw in ipairs(state.fireworks) do
        if fw.phase == "rise" then
            -- Rocket head
            if idx < n then
                entities[#entities + 1] = {
                    id = string.format("block_%d", idx),
                    x = clamp(fw.x),
                    y = clamp(fw.y),
                    z = clamp(fw.z),
                    scale = math.min(config.max_scale, 0.15),
                    rotation = 0,
                    band = fw.band,
                    visible = true,
                    glow = true,
                    brightness = 15,
                    material = "GLOWSTONE",
                    interpolation = 1,
                }
                idx = idx + 1
            end

            -- Trail
            for ti, tp in ipairs(fw.trail) do
                if idx >= n then break end
                local trail_life = ti / (#fw.trail + 1)
                entities[#entities + 1] = {
                    id = string.format("block_%d", idx),
                    x = clamp(tp.x),
                    y = clamp(tp.y),
                    z = clamp(tp.z),
                    scale = math.min(config.max_scale, 0.08 * trail_life),
                    rotation = 0,
                    band = fw.band,
                    visible = true,
                    glow = true,
                    brightness = math.floor(trail_life * 10),
                    material = fw.material,
                    interpolation = 1,
                }
                idx = idx + 1
            end

        elseif fw.phase == "burst" then
            -- Sparks
            for _, spark in ipairs(fw.sparks) do
                if idx >= n then break end
                if spark.life > 0 then
                    local sparkle = 1.0
                    if audio.bands[5] > 0.3 then
                        sparkle = 0.7 + simple_noise(spark.x * 10, spark.y * 10, state.time * 5) * 0.3
                    end

                    local scale = config.base_scale * spark.life * sparkle * 0.8
                    entities[#entities + 1] = {
                        id = string.format("block_%d", idx),
                        x = clamp(spark.x),
                        y = clamp(spark.y),
                        z = clamp(spark.z),
                        scale = math.min(config.max_scale, math.max(0.02, scale)),
                        rotation = (spark.dx * 500) % 360,
                        band = fw.band,
                        visible = true,
                        glow = spark.life > 0.3,
                        brightness = math.floor(clamp(spark.life) * 15),
                        material = fw.material,
                        interpolation = 1,
                    }
                    idx = idx + 1
                end
            end
        end
    end

    -- Fill remaining entities as invisible
    while idx < n do
        entities[#entities + 1] = {
            id = string.format("block_%d", idx),
            x = 0.5, y = 0.5, z = 0.5,
            scale = 0, rotation = 0, band = 0, visible = false,
        }
        idx = idx + 1
    end

    return entities
end
`,
  },
  {
    id: "shockwave",
    name: "Shockwave",
    description: "Expanding ring pulses radiate from center on each beat",
    category: "Mainstage",
    staticCamera: true,
    startBlocks: 80,
    source: `-- Pattern metadata
name = "Shockwave"
description = "Expanding ring pulses radiate from center on each beat"
category = "Mainstage"
static_camera = true
recommended_entities = 80

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize state
    if not state.waves then
        state.waves = {}
        state.time = 0
    end

    state.time = state.time + dt

    -- Spawn new wave on beat (max 4 concurrent)
    if audio.is_beat then
        local active = 0
        for _, w in ipairs(state.waves) do
            if w.life > 0 then active = active + 1 end
        end
        if active < 4 then
            state.waves[#state.waves + 1] = {
                radius = 0,
                speed = 0.6 + (audio.beat_intensity or 0.5) * 0.6,
                life = 1.0,
                band = math.floor(math.random() * 5),
                y_base = 0.5,
                vertical = math.random() > 0.7,  -- 30% chance of vertical wave
            }
        end
    end

    -- Update waves
    for i = #state.waves, 1, -1 do
        local w = state.waves[i]
        w.radius = w.radius + w.speed * dt
        -- Faster fade as wave reaches edges
        local edge_factor = 1.0 + w.radius * 3.0
        w.life = w.life - dt * edge_factor
        -- Slight speed decay for natural deceleration
        w.speed = w.speed * (1.0 - 0.3 * dt)

        -- Remove dead waves
        if w.life <= 0 or w.radius > 0.55 then
            table.remove(state.waves, i)
        end
    end

    -- Count active waves
    local active_waves = {}
    for _, w in ipairs(state.waves) do
        if w.life > 0 then
            active_waves[#active_waves + 1] = w
        end
    end

    local entities = {}
    local idx = 0

    if #active_waves == 0 then
        -- No active waves — show dim center point as idle indicator
        for i = 0, n - 1 do
            entities[#entities + 1] = {
                id = string.format("block_%d", i),
                x = 0.5,
                y = 0.5,
                z = 0.5,
                scale = 0,
                rotation = 0,
                band = 0,
                visible = false,
            }
        end
        return entities
    end

    -- Distribute entities across active waves
    local per_wave = math.max(4, math.floor(n / #active_waves))

    for wi, w in ipairs(active_waves) do
        local count = per_wave
        if wi == #active_waves then
            count = n - idx  -- remaining entities
        end
        if count <= 0 then break end

        local ring_thickness = 0.02 + audio.bands[1] * 0.02
        local high_wobble = audio.bands[5] * 0.05

        for j = 0, count - 1 do
            if idx >= n then break end

            local angle = (j / count) * math.pi * 2
            -- Slight radius variation for ring thickness
            local r_offset = (j % 3 - 1) * ring_thickness
            local r = w.radius + r_offset

            local x, y, z
            if w.vertical then
                -- Vertical ring: expands in x-y plane
                x = 0.5 + math.cos(angle) * r
                y = 0.5 + math.sin(angle) * r
                z = 0.5 + math.sin(angle * 3 + state.time * 5) * high_wobble
            else
                -- Horizontal ring: expands in x-z plane
                x = 0.5 + math.cos(angle) * r
                z = 0.5 + math.sin(angle) * r
                y = w.y_base + math.sin(angle * 3 + state.time * 5) * high_wobble
            end

            local intensity = w.life
            local scale = config.base_scale * (0.5 + intensity * 1.0)
            local brightness = math.floor(clamp(intensity) * 15)

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = (angle * 180 / math.pi) % 360,
                band = w.band,
                visible = true,
                glow = true,
                brightness = brightness,
                material = "SEA_LANTERN",
                interpolation = 1,
            }

            idx = idx + 1
        end
    end

    -- Fill remaining entities as invisible
    while idx < n do
        entities[#entities + 1] = {
            id = string.format("block_%d", idx),
            x = 0.5, y = 0.5, z = 0.5,
            scale = 0, rotation = 0, band = 0, visible = false,
        }
        idx = idx + 1
    end

    return entities
end
`,
  },
  {
    id: "strobe",
    name: "Strobe Wall",
    description: "Full-zone grid that flashes on/off in sync with beats — 4 strobe modes cycle automatically",
    category: "Mainstage",
    staticCamera: true,
    startBlocks: 96,
    source: `-- Pattern metadata
name = "Strobe Wall"
description = "Full-zone grid that flashes on/off in sync with beats — 4 strobe modes cycle automatically"
category = "Mainstage"
static_camera = true
recommended_entities = 96

state = {}

function calculate(audio, config, dt)
    local n = config.entity_count
    -- Grid dimensions: closest rectangle to n
    local cols = math.max(1, math.floor(math.sqrt(n * 1.5)))
    local rows = math.max(1, math.floor(n / cols))

    -- Initialize state
    if not state.flash then
        state.flash = 0
        state.mode = 0
        state.mode_timer = 0
        state.beat_count = 0
        state.parity = 0
    end

    -- Beat handling
    if audio.is_beat then
        state.flash = 1.0
        state.beat_count = state.beat_count + 1
        state.parity = 1 - state.parity
        -- Cycle mode every 16 beats
        state.mode_timer = state.mode_timer + 1
        if state.mode_timer >= 16 then
            state.mode_timer = 0
            state.mode = (state.mode + 1) % 4
        end
    end

    -- Decay flash between beats
    state.flash = decay(state.flash, 0.75, dt)

    -- BPM-adaptive subdivision
    local subdiv = 1
    if audio.bpm > 140 then
        subdiv = 0.5  -- half-time for fast tracks
    elseif audio.bpm > 0 and audio.bpm < 100 then
        subdiv = 2    -- double-time for slow tracks
    end

    -- Beat pulse for sustained strobe between discrete beats
    local pulse = beat_pulse(audio.beat_phase, subdiv, 8.0)
    local base_intensity = math.max(state.flash, pulse)

    -- Bass adds a brightness floor
    local bass_floor = audio.bands[1] * 0.3

    local entities = {}
    local idx = 0

    for row = 0, rows - 1 do
        for col = 0, cols - 1 do
            if idx >= n then break end

            local x = 0.1 + (col / math.max(1, cols - 1)) * 0.8
            local z = 0.1 + (row / math.max(1, rows - 1)) * 0.8

            -- Per-entity intensity based on current mode
            local intensity = 0
            local mode = state.mode

            if mode == 0 then
                -- Mode 0: Full flash — all entities strobe together
                intensity = base_intensity

            elseif mode == 1 then
                -- Mode 1: Checkerboard — alternating halves flash
                local is_even = (row + col) % 2 == state.parity
                intensity = is_even and base_intensity or (base_intensity * 0.1)

            elseif mode == 2 then
                -- Mode 2: Wave sweep — flash propagates left to right
                local wave_pos = beat_sub(audio.beat_phase, subdiv) * (cols + 2)
                local dist = math.abs(col - wave_pos)
                local wave_intensity = math.max(0, 1.0 - dist * 0.4)
                intensity = wave_intensity * math.max(state.flash, 0.5)

            elseif mode == 3 then
                -- Mode 3: Random scatter — noise-based 40% selection on beat
                local noise = simple_noise(col, row, state.beat_count)
                local threshold = 0.2  -- ~40% of noise range [-1,1] is above 0.2
                if noise > threshold then
                    intensity = base_intensity
                else
                    intensity = base_intensity * 0.05
                end
            end

            -- Add bass floor
            intensity = clamp(intensity + bass_floor)

            -- Amplitude affects decay rate perception (high amp = hold longer)
            if audio.amplitude > 0.6 then
                intensity = math.max(intensity, base_intensity * 0.5)
            end

            local scale = config.base_scale + intensity * (config.max_scale - config.base_scale)
            local brightness = math.floor(intensity * 15)
            local glow = intensity > 0.4
            local material = intensity > 0.25 and "GLOWSTONE" or "WHITE_CONCRETE"

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = 0.5,
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                rotation = 0,
                band = math.floor(col / math.max(1, cols) * 5) % 5,
                visible = true,
                glow = glow,
                brightness = brightness,
                material = material,
                interpolation = 0,
            }

            idx = idx + 1
        end
    end

    return normalize_entities(entities, n)
end
`,
  },
];
