-- Pattern metadata
name = "Stacked Tower"
description = "Spiraling vertical tower - blocks orbit and bounce"

-- Per-instance state
state = {
    rotation = 0.0,
    bounce_wave = {},
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local center = 0.5
    local n = config.entity_count

    -- Ensure bounce wave array matches entity count
    while #state.bounce_wave < n do
        state.bounce_wave[#state.bounce_wave + 1] = 0.0
    end

    -- Rotation speed based on energy
    state.rotation = state.rotation + (0.5 + audio.amplitude * 2.0) * dt

    -- Trigger bounce wave on beat
    if audio.is_beat then
        state.bounce_wave[1] = 1.0
    end

    -- Propagate bounce wave upward
    for i = math.min(n, #state.bounce_wave), 2, -1 do
        state.bounce_wave[i] = state.bounce_wave[i] * 0.9 + state.bounce_wave[i - 1] * 0.15
    end
    state.bounce_wave[1] = state.bounce_wave[1] * 0.85

    for i = 1, n do
        -- Vertical position - scale to fit within bounds
        local normalized_i = (i - 1) / math.max(1, n - 1)  -- 0 to 1
        local base_y = 0.1 + normalized_i * 0.6  -- 0.1 to 0.7 base range

        -- Add audio-reactive spread
        local spread = audio.amplitude * 0.15
        local y = base_y + spread * normalized_i

        -- Spiral around center - more turns with more blocks
        local turns = 2 + n / 16  -- More blocks = more spiral turns
        local angle = state.rotation + normalized_i * math.pi * 2 * turns
        local band_idx = ((i - 1) % 5) + 1  -- 1-indexed band
        local orbit_radius = 0.08 + audio.bands[band_idx] * 0.2

        local x = center + math.cos(angle) * orbit_radius
        local z = center + math.sin(angle) * orbit_radius

        -- Bounce effect
        local bounce = 0
        if i <= #state.bounce_wave then
            bounce = state.bounce_wave[i]
        end
        y = y + bounce * 0.15

        -- Scale based on frequency band
        local scale = config.base_scale + audio.bands[band_idx] * 0.6

        if audio.is_beat then
            scale = scale * 1.5
            y = y + 0.05
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0.05, 0.95),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = band_idx - 1,  -- 0-indexed band for output
            visible = true,
        }
    end

    return entities
end
