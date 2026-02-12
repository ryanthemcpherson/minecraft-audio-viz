-- Pattern metadata
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
