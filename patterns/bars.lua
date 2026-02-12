-- Pattern metadata
name = "Spectrum Bars"
description = "Classic vertical frequency bars"

-- Per-instance state
state = {
    smooth_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
    peak_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
    peak_fall = {0.0, 0.0, 0.0, 0.0, 0.0},
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Smooth the band values
    for i = 1, 5 do
        local target = audio.bands[i]
        state.smooth_heights[i] = state.smooth_heights[i] + (target - state.smooth_heights[i]) * 0.3

        -- Peak hold with fall
        if state.smooth_heights[i] > state.peak_heights[i] then
            state.peak_heights[i] = state.smooth_heights[i]
            state.peak_fall[i] = 0.0
        else
            state.peak_fall[i] = state.peak_fall[i] + 0.02
            state.peak_heights[i] = state.peak_heights[i] - state.peak_fall[i] * dt
        end

        state.peak_heights[i] = math.max(0, state.peak_heights[i])
    end

    -- Distribute entities across 5 bars
    local blocks_per_bar = math.floor(n / 5)
    local bar_spacing = 0.16
    local start_x = center - (2.0 * bar_spacing)

    local entity_idx = 0

    for bar = 0, 4 do
        local bar_x = start_x + bar * bar_spacing
        local bar_height = state.smooth_heights[bar + 1]

        -- Stack blocks vertically for this bar
        for j = 0, blocks_per_bar - 1 do
            if entity_idx >= n then break end

            -- Normalized position in bar (0 = bottom, 1 = top of max)
            local block_y_norm = (j + 0.5) / blocks_per_bar

            -- Only show blocks up to current height
            local max_height = 0.7
            local block_y = 0.1 + block_y_norm * max_height

            -- Visibility based on bar height
            local visible = block_y_norm <= bar_height

            -- Scale - slightly larger when at the top of the current level
            local scale = config.base_scale
            if visible then
                -- Blocks near the top of current height are brighter
                local top_proximity = 1.0 - math.abs(block_y_norm - bar_height) * 3
                top_proximity = math.max(0, top_proximity)
                scale = scale + top_proximity * 0.3
            end

            if audio.is_beat then
                scale = scale * 1.2
            end

            -- Slight depth variation per bar
            local z = center + (bar - 2.5) * 0.02

            local final_scale = scale
            if not visible then final_scale = 0.01 end

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(bar_x),
                y = clamp(block_y),
                z = clamp(z),
                scale = math.min(config.max_scale, final_scale),
                band = bar,
                visible = visible,
            }
            entity_idx = entity_idx + 1
        end
    end

    return entities
end
