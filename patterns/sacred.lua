name = "Sacred Geometry"
description = "Morphing platonic solids - icosahedron"
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
    state.pulse = state.pulse * 0.9

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
            band = band_idx,
            visible = true,
        }
    end

    return entities
end
