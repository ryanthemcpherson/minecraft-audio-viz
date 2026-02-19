name = "Dragon"
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
    if audio.beat then
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
    if audio.beat then
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
            local fire_breath = audio.beat and audio.bands[1] > 0.6
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
            glow = part_glow,
            brightness = clamp(part_brightness, 0, 15),
            material = part_material,
            interpolation = part_interpolation,
        }
    end

    return entities
end
