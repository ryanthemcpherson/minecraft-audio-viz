-- Pattern metadata
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
