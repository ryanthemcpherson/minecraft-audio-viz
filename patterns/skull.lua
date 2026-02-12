name = "Skull"
description = "Clean anatomical skull with animated jaw and glowing eyes"
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
    state.breathe = state.breathe + 0.012 * state.breathe_dir
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
    state.head_bob = state.head_bob * 0.9
    state.beat_intensity = state.beat_intensity * 0.92
    state.eye_glow = state.eye_glow * 0.85

    -- Jaw opens with bass
    local target_jaw = audio.bands[1] * 0.08 + audio.bands[2] * 0.04
    if audio.beat then
        target_jaw = target_jaw + 0.06
    end
    state.jaw_open = state.jaw_open + (target_jaw - state.jaw_open) * 0.25

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
