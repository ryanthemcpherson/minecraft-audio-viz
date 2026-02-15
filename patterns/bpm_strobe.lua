-- Pattern metadata
name = "BPM Strobe"
description = "Ring of blocks with visibility and scale locked to beat subdivisions"
category = "Original"
static_camera = false
recommended_entities = 32

-- Per-instance state
state = {
    rotation = 0.0,
    smooth_bass = 0.0,
    smooth_high = 0.0,
}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Smooth inputs
    state.smooth_bass = smooth(state.smooth_bass, audio.bands[1], 0.35, dt)
    state.smooth_high = smooth(state.smooth_high, audio.bands[5], 0.3, dt)

    -- Rotation advances in sync with beat phase for locked motion
    -- Full rotation per beat when BPM is detected
    if audio.bpm > 0 then
        state.rotation = audio.beat_phase * math.pi * 2
    else
        state.rotation = state.rotation + 1.5 * dt
    end

    -- Beat subdivision phases
    local phase_1 = beat_sub(audio.beat_phase, 1)    -- whole beat
    local phase_2 = beat_sub(audio.beat_phase, 2)    -- half notes
    local phase_4 = beat_sub(audio.beat_phase, 4)    -- quarter notes

    -- Triangle waves for smooth pulsing per subdivision
    local tri_1 = beat_tri(audio.beat_phase, 1)
    local tri_2 = beat_tri(audio.beat_phase, 2)
    local tri_4 = beat_tri(audio.beat_phase, 4)

    -- Stacked rings
    local num_rings = math.max(1, math.floor(math.sqrt(n)))
    local per_ring = math.max(1, math.floor(n / num_rings))

    local entity_idx = 0
    for ring = 0, num_rings - 1 do
        local ring_frac = ring / math.max(1, num_rings - 1)  -- 0 to 1
        local ring_y = 0.15 + ring_frac * 0.7

        -- Each ring uses a different subdivision for its strobe
        local subdivision
        if ring % 3 == 0 then
            subdivision = 1  -- whole beat
        elseif ring % 3 == 1 then
            subdivision = 2  -- half notes
        else
            subdivision = 4  -- quarter notes
        end

        local strobe_tri = beat_tri(audio.beat_phase, subdivision)
        local strobe_pulse = beat_pulse(audio.beat_phase, subdivision, 6.0)

        -- Ring radius varies with subdivision pulse
        local base_radius = 0.12 + ring_frac * 0.08
        local radius = base_radius + strobe_pulse * 0.06 + state.smooth_bass * 0.04

        local items_this_ring = per_ring
        if ring == num_rings - 1 then
            items_this_ring = n - entity_idx  -- remaining entities go to last ring
        end

        for j = 0, items_this_ring - 1 do
            if entity_idx >= n then break end

            local angle = (j / items_this_ring) * math.pi * 2 + state.rotation
            -- Offset alternate rings for visual interest
            if ring % 2 == 1 then
                angle = angle + math.pi / items_this_ring
            end

            local x = center + math.cos(angle) * radius
            local z = center + math.sin(angle) * radius

            -- Scale follows the subdivision strobe
            local scale = config.base_scale + strobe_tri * 0.35
            scale = scale + state.smooth_high * 0.1

            -- Visibility: strobe effect - hide when subdivision phase crosses threshold
            local vis_phase = beat_sub(audio.beat_phase, subdivision)
            -- Offset each entity slightly for a sweep effect
            local entity_offset = j / items_this_ring * 0.3
            local vis_check = (vis_phase + entity_offset) % 1.0
            local visible = vis_check < 0.7  -- visible 70% of the time

            -- Band assignment: spread across bands by ring
            local band = (ring + j) % 5

            -- Y position bobs with strobe
            local y = ring_y + strobe_pulse * 0.04

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                rotation = (angle * 180 / math.pi + strobe_tri * 30) % 360,
                band = band,
                visible = visible,
            }

            entity_idx = entity_idx + 1
        end
    end

    return entities
end
