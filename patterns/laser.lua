name = "Laser Array"
description = "Laser beams shooting from center"
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation or 0
    state.flash = state.flash or 0
    if not state.beam_lengths then
        state.beam_lengths = {0, 0, 0, 0, 0, 0, 0, 0}
    end

    -- Rotation speed based on energy
    state.rotation = state.rotation + (0.5 + audio.peak * 2.0) * dt

    -- Flash on beat
    if audio.beat then
        state.flash = 1.0
    end
    state.flash = state.flash * 0.85

    -- Number of beams
    local num_beams = math.min(8, math.max(4, math.floor(n / 8)))
    local points_per_beam = math.floor(n / num_beams)

    for beam = 0, num_beams - 1 do
        -- Beam angle
        local beam_angle = state.rotation + (beam / num_beams) * math.pi * 2

        -- Beam target length based on frequency band
        local band_idx = beam % 5
        local target_length = 0.1 + audio.bands[band_idx + 1] * 0.35
        if audio.beat then
            target_length = target_length + 0.1
        end

        -- Smooth beam extension
        local bl_idx = beam + 1
        state.beam_lengths[bl_idx] = state.beam_lengths[bl_idx] + (target_length - state.beam_lengths[bl_idx]) * 0.3
        local beam_length = state.beam_lengths[bl_idx]

        -- Beam tilt (elevation angle)
        local tilt = (beam % 3 - 1) * 0.3

        for j = 0, points_per_beam - 1 do
            local idx = beam * points_per_beam + j
            if idx >= n then
                break
            end

            -- Position along beam
            local t = (j + 1) / points_per_beam
            local distance = t * beam_length

            -- 3D position
            local x = center + math.cos(beam_angle) * distance * math.cos(tilt)
            local z = center + math.sin(beam_angle) * distance * math.cos(tilt)
            local y = center + distance * math.sin(tilt)

            -- Scale - thinner at ends
            local thickness = 1.0 - t * 0.5
            local scale = config.base_scale * thickness + state.flash * 0.2

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
        end
    end

    return entities
end
