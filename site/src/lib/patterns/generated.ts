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
    id: "columns",
    name: "Floating Platforms",
    description: "6 levitating platforms - one per frequency",
    category: "Original",
    staticCamera: false,
    startBlocks: null,
    source: `-- Pattern metadata
name = "Floating Platforms"
description = "6 levitating platforms - one per frequency"
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
                band = band - 1,
                visible = true,
            }
        end
    end

    return entities
end
`,
  },
  {
    id: "explode",
    name: "Supernova",
    description: "Explosive burst on beats - 3D shockwave",
    category: "Original",
    staticCamera: false,
    startBlocks: null,
    source: `-- Pattern metadata
name = "Supernova"
description = "Explosive burst on beats - 3D shockwave"
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
    startBlocks: null,
    source: `-- Pattern metadata
name = "Breathing Cube"
description = "Rotating cube vertices - expands with beats"
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
    startBlocks: null,
    source: `-- Pattern metadata
name = "Fountain"
description = "Upward spray with gravity arcs"
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
    startBlocks: null,
    source: `-- Pattern metadata
name = "Atom Model"
description = "Nucleus + electrons on 3D orbital planes"
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
                band = band_idx - 1,
                visible = true,
            }
        end
    end

    return entities
end
`,
  },
  {
    id: "ring",
    name: "Expanding Sphere",
    description: "3D sphere that breathes and pulses",
    category: "Original",
    staticCamera: false,
    startBlocks: null,
    source: `-- Pattern metadata
name = "Expanding Sphere"
description = "3D sphere that breathes and pulses"
category = "Original"
static_camera = false

-- Per-instance state
state = {
    sphere_points = {},
    rotation_y = 0.0,
    rotation_x = 0.0,
    breath = 0.0,
}

-- Generate fibonacci sphere points for even distribution
local function generate_sphere_points(n)
    local points = {}
    local phi = math.pi * (3.0 - math.sqrt(5.0))  -- Golden angle

    for i = 0, n - 1 do
        local y = 1 - (i / (n - 1)) * 2  -- y goes from 1 to -1
        local radius = math.sqrt(1 - y * y)
        local theta = phi * i

        local x = math.cos(theta) * radius
        local z = math.sin(theta) * radius
        points[#points + 1] = { x, y, z }
    end

    return points
end

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize sphere points
    if #state.sphere_points ~= n then
        state.sphere_points = generate_sphere_points(n)
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
        local px = state.sphere_points[i][1]
        local py = state.sphere_points[i][2]
        local pz = state.sphere_points[i][3]

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
    startBlocks: null,
    source: `-- Pattern metadata
name = "Stacked Tower"
description = "Spiraling vertical tower - blocks orbit and bounce"
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
    id: "galaxy",
    name: "Galaxy",
    description: "Spiral galaxy - cosmic visualization",
    category: "Epic",
    staticCamera: false,
    startBlocks: null,
    source: `name = "Galaxy"
description = "Spiral galaxy - cosmic visualization"
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
    if audio.beat then
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

            if audio.beat then
                scale = scale * 1.15
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

    return entities
end
`,
  },
  {
    id: "laser",
    name: "Laser Array",
    description: "Laser beams shooting from center",
    category: "Epic",
    staticCamera: false,
    startBlocks: null,
    source: `name = "Laser Array"
description = "Laser beams shooting from center"
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
    if audio.beat then
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
        if audio.beat then
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

    return entities
end
`,
  },
  {
    id: "mushroom",
    name: "Mushroom",
    description: "Psychedelic toadstool with spots, gills, and spores",
    category: "Epic",
    staticCamera: false,
    startBlocks: null,
    source: `-- Pattern metadata
name = "Mushroom"
description = "Psychedelic toadstool with spots, gills, and spores"
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
            visible = fade > 0.1,
        }
        entity_idx = entity_idx + 1
    end

    return entities
end
`,
  },
  {
    id: "pyramid",
    name: "Pyramid",
    description: "Egyptian pyramid - inverts on drops",
    category: "Epic",
    staticCamera: false,
    startBlocks: null,
    source: `name = "Pyramid"
description = "Egyptian pyramid - inverts on drops"
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
    if audio.beat and audio.beat_intensity > 0.6 then
        if state.invert > 0.5 then
            state.invert = 0
        else
            state.invert = 1
        end
    end

    -- Hover with bass
    local target_hover = audio.bands[1] * 0.1 + audio.bands[2] * 0.05
    state.hover = smooth(state.hover, target_hover, 0.1, dt)

    -- Pyramid layers
    local layers = math.max(3, math.floor(math.sqrt(n)))

    local entity_idx = 0
    for layer = 0, layers - 1 do
        local layer_norm
        if layers > 1 then
            layer_norm = layer / (layers - 1)
        else
            layer_norm = 0
        end

        -- Inversion
        if state.invert > 0.5 then
            layer_norm = 1.0 - layer_norm
        end

        -- Layer properties
        local layer_size = 1.0 - layer_norm * 0.9
        local layer_warp = math.sin(state.rotation + layer * 0.6) * audio.bands[3] * 0.08
        local layer_y = 0.1 + layer_norm * 0.7 + state.hover + layer_warp * 0.1

        -- Points per layer (square arrangement)
        local side_points = math.max(1, math.floor(math.sqrt(n / layers) * layer_size))

        for i = 0, side_points - 1 do
            for j = 0, side_points - 1 do
                if entity_idx >= n then
                    break
                end

                -- Position within layer
                local local_x = (i / math.max(1, side_points - 1) - 0.5) * layer_size * 0.4
                local local_z = (j / math.max(1, side_points - 1) - 0.5) * layer_size * 0.4

                -- Rotate
                local cos_r = math.cos(state.rotation)
                local sin_r = math.sin(state.rotation)
                local rx = local_x * cos_r - local_z * sin_r
                local rz = local_x * sin_r + local_z * cos_r

                local x = center + rx
                local z = center + rz
                local y = layer_y

                local band_idx = entity_idx % 5
                local scale = config.base_scale + audio.bands[band_idx + 1] * 0.4

                -- Highlight edges
                if i == 0 or i == side_points - 1 or j == 0 or j == side_points - 1 then
                    scale = scale + audio.bands[5] * 0.25
                end

                -- Apex glows more
                if layer_norm > 0.8 then
                    scale = scale + audio.peak * 0.3
                end

                if audio.beat then
                    scale = scale * 1.25
                end

                entities[#entities + 1] = {
                    id = string.format("block_%d", entity_idx),
                    x = clamp(x),
                    y = clamp(y),
                    z = clamp(z),
                    scale = math.min(config.max_scale, scale),
                    band = band_idx,
                    visible = true,
                }
                entity_idx = entity_idx + 1
            end

            if entity_idx >= n then
                break
            end
        end
    end

    return entities
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
    if audio.beat then
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

        if audio.beat then
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
    startBlocks: null,
    source: `name = "Skull"
description = "Clean anatomical skull with animated jaw and glowing eyes"
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
    if audio.beat then
        state.head_bob = 0.025 * audio.peak
        state.beat_intensity = 1.0
        state.eye_glow = 1.0
    end
    state.head_bob = decay(state.head_bob, 0.9, dt)
    state.beat_intensity = decay(state.beat_intensity, 0.92, dt)
    state.eye_glow = decay(state.eye_glow, 0.85, dt)

    -- Jaw opens with bass
    local target_jaw = audio.bands[1] * 0.08 + audio.bands[2] * 0.04
    if audio.beat then
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
        if audio.beat then
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
        }
    end

    return entities
end
`,
  },
  {
    id: "vortex",
    name: "Vortex",
    description: "Swirling tunnel - spiral into infinity",
    category: "Epic",
    staticCamera: false,
    startBlocks: null,
    source: `name = "Vortex"
description = "Swirling tunnel - spiral into infinity"
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
    if audio.beat then
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

            if audio.beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z_pos),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
        end
    end

    return entities
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

    return entities
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

    return entities
end
`,
  },
  {
    id: "crystal",
    name: "Crystal Growth",
    description: "Fractal crystal with recursive branching",
    category: "Cosmic",
    staticCamera: false,
    startBlocks: null,
    source: `name = "Crystal Growth"
description = "Fractal crystal with recursive branching"
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
    if audio.beat then
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

            if audio.beat then
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
    startBlocks: null,
    source: `name = "Mandala"
description = "Sacred geometry rings - frequency mapped"
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
    if audio.beat then
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

            if audio.beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                band = ring,
                visible = true,
            }
            entity_idx = entity_idx + 1
        end
    end

    return entities
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

        -- Update particle position with drift
        state.particles[i].x = px + drift_x * 0.1
        state.particles[i].y = py + drift_y * 0.1
        state.particles[i].z = pz + drift_z * 0.1

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
    if audio.beat then
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

        if audio.beat then
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
    startBlocks: null,
    source: `-- Pattern metadata
name = "Aurora"
description = "Northern lights curtains - flowing waves"
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

    return entities
end
`,
  },
  {
    id: "fireflies",
    name: "Fireflies",
    description: "Swarm of synchronized flashing lights",
    category: "Organic",
    staticCamera: false,
    startBlocks: null,
    source: `-- Pattern metadata
name = "Fireflies"
description = "Swarm of synchronized flashing lights"
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

        -- Organic drifting motion
        -- Perlin-like smooth random walk
        local t = state.drift_time + i * 0.1
        local ax = math.sin(t * 0.5 + i) * 0.0002
        local ay = math.sin(t * 0.3 + i * 1.3) * 0.0001
        local az = math.cos(t * 0.4 + i * 0.7) * 0.0002

        -- Update velocity with acceleration
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

        -- Soft boundaries - turn around near edges
        if x < 0.15 or x > 0.85 then vx = vx * -0.5 end
        if y < 0.15 or y > 0.85 then vy = vy * -0.5 end
        if z < 0.15 or z > 0.85 then vz = vz * -0.5 end

        x = clamp(x, 0.1, 0.9)
        y = clamp(y, 0.1, 0.9)
        z = clamp(z, 0.1, 0.9)

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

        -- Band based on group (groups 1-4 map to bands 1-4, 0-indexed: 1-4)
        local band_idx = group  -- group is 1-4, band output is 0-indexed so we use group directly (1-4)

        local scale = config.base_scale * 0.5 + total_glow * 0.6
        scale = scale + audio.bands[band_idx + 1] * 0.2

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = x,
            y = y,
            z = z,
            scale = math.min(config.max_scale, math.max(0.05, scale)),
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
    startBlocks: null,
    source: `-- Pattern metadata
name = "Ocean Waves"
description = "Water surface with splashes and ripples"
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
            x = splash_x, z = splash_z, time = 0.0, intensity = audio.beat_intensity
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
            wave_high = wave_high * (0.2 + audio.bands[5] * 2 + audio.bands[5] * 2)

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
                band = band_idx,
                visible = true,
            }
        end
    end

    return entities
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

    -- Interpolated virtual bars: more blocks = more horizontal granularity.
    local num_bars = math.max(5, math.min(28, math.floor(n / 4)))
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

    return entities
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

    return entities
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

    return entities
end
`,
  },
];
