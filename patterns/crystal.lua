name = "Crystal Growth"
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
