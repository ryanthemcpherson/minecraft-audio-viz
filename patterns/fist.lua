name = "Raised Fist"
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
    if audio.beat then
        state.pump_y = state.pump_y + 0.04 * audio.peak
        state.beat_intensity = 1.0
        state.tilt = state.tilt + 0.05
    end
    state.pump_y = smooth(state.pump_y, pump_target, 0.12, dt)
    state.pump_y = decay(state.pump_y, 0.90, dt) + pump_target * 0.1
    state.beat_intensity = decay(state.beat_intensity, 0.90, dt)
    state.tilt = decay(state.tilt, 0.88, dt)

    local breathe_scale = 1.0 + (state.breathe * 2 - 1) * 0.01
    local fist_scale = 0.55 + audio.bands[2] * 0.04 + state.beat_intensity * 0.02

    local yaw = state.rotation + math.sin(state.time * 0.3) * 0.04
    local cos_r = math.cos(yaw)
    local sin_r = math.sin(yaw)

    -- Forward tilt on pump
    local tilt_x = state.tilt + math.sin(state.time * 0.5) * 0.03
    local cos_tx = math.cos(tilt_x)
    local sin_tx = math.sin(tilt_x)

    -- Vein pulse factor for wrist
    local vein_pulse = beat_pulse(audio.beat_phase or 0, 1, 6) * 0.3

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
        local z = center + rz

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
