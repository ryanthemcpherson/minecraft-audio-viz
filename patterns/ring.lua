-- Pattern metadata
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
