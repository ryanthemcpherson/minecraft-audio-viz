-- Pattern metadata
name = "Supernova"
description = "Explosive burst on beats - 3D shockwave"

-- Per-instance state
state = {
    positions = {},   -- array of {r, theta, phi}
    velocities = {},
}

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count

    -- Initialize random directions
    if #state.positions ~= n then
        state.positions = {}
        state.velocities = {}
        for i = 1, n do
            local theta = math.random() * math.pi * 2
            local phi = math.random() * math.pi
            state.positions[i] = { 0.05, theta, phi }  -- {radius, theta, phi}
            state.velocities[i] = 0.0
        end
    end

    -- Beat triggers explosion
    if audio.is_beat and audio.beat_intensity > 0.3 then
        for i = 1, n do
            state.velocities[i] = 0.8 + math.random() * 0.4
            -- Randomize direction slightly
            state.positions[i][2] = state.positions[i][2] + (math.random() - 0.5) * 0.6
            state.positions[i][3] = state.positions[i][3] + (math.random() - 0.5) * 0.4
        end
    end

    local entities = {}
    local center = 0.5

    for i = 1, n do
        -- Update position
        state.positions[i][1] = state.positions[i][1] + state.velocities[i] * dt
        state.velocities[i] = state.velocities[i] * 0.96  -- Drag

        -- Gravity back to center
        if state.positions[i][1] > 0.05 then
            state.velocities[i] = state.velocities[i] - 0.02
        end

        -- Clamp radius
        state.positions[i][1] = clamp(state.positions[i][1], 0.02, 0.45)

        -- Spherical to cartesian
        local r = state.positions[i][1]
        local theta = state.positions[i][2]
        local phi = state.positions[i][3]

        local x = center + r * math.sin(phi) * math.cos(theta)
        local y = center + r * math.cos(phi)
        local z = center + r * math.sin(phi) * math.sin(theta)

        local band_idx = ((i - 1) % 5) + 1
        local scale = config.base_scale
            + audio.bands[band_idx] * 0.3
            + math.abs(state.velocities[i]) * 0.5

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
