-- Pattern metadata
name = "Spectrum Circle"
description = "Radial frequency bars in a circle"
category = "Spectrum"
static_camera = true
start_blocks = 100

-- Per-instance state
state = {
    smooth_heights = {0.0, 0.0, 0.0, 0.0, 0.0},
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

    -- Slow rotation
    state.rotation = state.rotation + (0.1 + audio.amplitude * 0.2) * dt

    -- Smooth the band values
    for i = 1, 5 do
        local target = audio.bands[i]
        state.smooth_heights[i] = smooth(state.smooth_heights[i], target, 0.3, dt)
    end

    local function sample5(values, t)
        local p = clamp(t, 0.0, 1.0) * 4.0
        local i0 = math.floor(p) + 1
        local i1 = math.min(5, i0 + 1)
        local f = p - math.floor(p)
        return lerp(values[i0], values[i1], f)
    end

    -- Dynamic radial granularity: more blocks -> more spokes.
    local num_segments = math.max(10, math.min(64, math.floor(n / 3)))
    num_segments = math.max(1, math.min(num_segments, n))
    local points_per_segment = math.max(1, math.floor(n / num_segments))
    local base_radius = 0.15
    local max_bar_length = 0.25

    local entity_idx = 0

    for seg = 0, num_segments - 1 do
        if entity_idx >= n then break end
        local seg_angle = state.rotation + (seg / num_segments) * math.pi * 2
        local t = (num_segments > 1) and (seg / (num_segments - 1)) or 0.0
        local seg_height = sample5(state.smooth_heights, t)
        local band_sample = sample5(audio.bands, t)

        for j = 0, points_per_segment - 1 do
            if entity_idx >= n then break end

            -- Position along the bar (from center outward)
            local bar_pos = (j + 0.5) / points_per_segment
            local visible = bar_pos <= seg_height + 0.1

            -- Radius from center
            local r = base_radius + bar_pos * max_bar_length

            local x = center + math.cos(seg_angle) * r
            local z = center + math.sin(seg_angle) * r
            local y = center  -- Flat on horizontal plane

            -- Scale
            local band_idx = math.floor(t * 4 + 0.5)
            local scale = config.base_scale
            if visible then
                -- Brighter at the end
                if bar_pos > seg_height - 0.2 then
                    scale = scale * 1.3
                end
                scale = scale + band_sample * 0.2
            end

            if audio.is_beat then
                scale = scale * 1.2
            end

            local final_scale = scale
            if not visible then final_scale = 0.01 end
            local rot = (seg_angle + bar_pos * math.pi * 0.9) * 180 / math.pi

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, final_scale),
                rotation = rot % 360,
                band = band_idx,
                visible = visible,
            }
            entity_idx = entity_idx + 1
        end
    end

    return entities
end
