name = "Tesseract"
description = "4D hypercube rotating through dimensions"
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
    state.rotation_zw = state.rotation_zw + (0.1 + audio.bands[5] * 0.5 + audio.bands[5] * 0.3) * dt
    state.rotation_xy = state.rotation_xy + 0.3 * dt

    -- Beat pulse
    if audio.beat then
        state.pulse = 1.0
    end
    state.pulse = state.pulse * 0.9

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
            band = band_idx,
            visible = true,
        }
    end

    return entities
end
