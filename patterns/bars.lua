-- Pattern metadata
name = "Spectrum Bars"
description = "Classic vertical frequency bars"
category = "Spectrum"
static_camera = true
start_blocks = 96

-- Per-instance state
state = {
    smooth_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
    peak_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
    peak_fall = {0.0, 0.0, 0.0, 0.0, 0.0},
    rotation = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5
    if n <= 0 then
        return entities
    end
    state.rotation = state.rotation + (0.4 + audio.amplitude * 0.9) * dt

    -- Smooth the band values
    for i = 1, 5 do
        local target = audio.bands[i]
        state.smooth_heights[i] = smooth(state.smooth_heights[i], target, 0.3, dt)

        -- Peak hold with fall
        if state.smooth_heights[i] > state.peak_heights[i] then
            state.peak_heights[i] = state.smooth_heights[i]
            state.peak_fall[i] = 0.0
        else
            state.peak_fall[i] = state.peak_fall[i] + 0.02 * (dt / 0.016)
            state.peak_heights[i] = state.peak_heights[i] - state.peak_fall[i] * dt
        end

        state.peak_heights[i] = math.max(0, state.peak_heights[i])
    end

    -- Interpolated virtual bars: more blocks = more horizontal granularity.
    local num_bars = math.max(5, math.min(28, math.floor(n / 4)))
    num_bars = math.max(1, math.min(num_bars, n))
    local blocks_per_bar = math.max(1, math.floor(n / num_bars))
    local span = 0.72
    local start_x = center - span * 0.5
    local bar_spacing = (num_bars > 1) and (span / (num_bars - 1)) or 0

    local function sample5(values, t)
        local p = clamp(t, 0.0, 1.0) * 4.0
        local i0 = math.floor(p) + 1
        local i1 = math.min(5, i0 + 1)
        local f = p - math.floor(p)
        return lerp(values[i0], values[i1], f)
    end

    local entity_idx = 0

    for bar = 0, num_bars - 1 do
        if entity_idx >= n then break end
        local bar_x = start_x + bar * bar_spacing
        local t = (num_bars > 1) and (bar / (num_bars - 1)) or 0.0
        local bar_height = sample5(state.smooth_heights, t)
        local bar_band = math.floor(t * 4 + 0.5)

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
            local z = center + (t - 0.5) * 0.06

            local final_scale = scale
            if not visible then final_scale = 0.01 end
            local rot = (state.rotation + t * math.pi * 1.4 + block_y_norm * math.pi * 0.8) * 180 / math.pi

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(bar_x),
                y = clamp(block_y),
                z = clamp(z),
                scale = math.min(config.max_scale, final_scale),
                rotation = rot % 360,
                band = bar_band,
                visible = visible,
            }
            entity_idx = entity_idx + 1
        end
    end

    return entities
end
