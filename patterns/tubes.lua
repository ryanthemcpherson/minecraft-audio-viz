-- Pattern metadata
name = "Spectrum Tubes"
description = "3D cylindrical frequency tubes"
category = "Spectrum"
static_camera = true

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

    -- Layout: 5 tubes in a row
    local tube_spacing = 0.15
    local start_x = center - (2.0 * tube_spacing)

    -- Points per tube
    local points_per_tube = math.floor(n / 5)
    local rings_per_tube = math.max(3, math.floor(points_per_tube / 4))
    local points_per_ring = math.floor(points_per_tube / rings_per_tube)

    local entity_idx = 0

    for tube = 0, 4 do
        local tube_x = start_x + tube * tube_spacing
        local tube_height = state.smooth_heights[tube + 1]
        local tube_radius = 0.03 + state.pulse[tube + 1] * 0.02

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
                angle = angle + tube * 0.5  -- Offset per tube

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
                    scale = scale + audio.bands[tube + 1] * 0.3
                end

                if audio.is_beat then
                    scale = scale * 1.15
                end

                local final_scale = scale
                if not visible then final_scale = 0.01 end

                entities[#entities + 1] = {
                    id = string.format("block_%d", entity_idx),
                    x = clamp(x),
                    y = clamp(ring_y),
                    z = clamp(z),
                    scale = math.min(config.max_scale, final_scale),
                    band = tube,
                    visible = visible,
                }
                entity_idx = entity_idx + 1
            end
        end
    end

    return entities
end
