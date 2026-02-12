-- Pattern metadata
name = "DNA Helix"
description = "Double helix spiral - rotates and stretches"
category = "Original"
static_camera = false

-- Per-instance state
state = {
    rotation = 0.0,
    stretch = 1.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Rotation speed based on energy
    local speed = 1.0 + audio.amplitude * 2.0
    if audio.is_beat then
        speed = speed * 1.5
    end
    state.rotation = state.rotation + speed * dt

    -- Stretch based on bass
    local target_stretch = 0.8 + audio.bands[1] * 0.6 + audio.bands[2] * 0.4
    state.stretch = smooth(state.stretch, target_stretch, 0.1, dt)

    -- Helix parameters
    local radius = 0.15 + audio.amplitude * 0.1
    local pitch = 0.08 * state.stretch

    for i = 1, n do
        -- Alternate between two helixes (0-indexed logic)
        local helix = (i - 1) % 2
        local idx = math.floor((i - 1) / 2)

        -- Position along helix
        local half_n = n / 2
        local t = idx / half_n * math.pi * 3  -- 3 full turns
        local angle = t + state.rotation + (helix * math.pi)  -- Offset second helix by 180 degrees

        -- Helix coordinates
        local x = center + math.cos(angle) * radius
        local z = center + math.sin(angle) * radius
        local y = 0.1 + (idx / half_n) * 0.8 + pitch * math.sin(t)  -- Vertical spread with pitch modulation

        -- Pulse radius with band
        local band_idx = (idx % 5) + 1
        local pulse = audio.bands[band_idx] * 0.05
        x = x + math.cos(angle) * pulse
        z = z + math.sin(angle) * pulse

        local scale = config.base_scale + audio.bands[band_idx] * 0.4
        if audio.is_beat then
            scale = scale * 1.3
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx - 1,
            visible = true,
        }
    end

    return entities
end
