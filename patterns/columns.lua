-- Pattern metadata
name = "Floating Platforms"
description = "6 levitating platforms - one per frequency"
category = "Original"
static_camera = false

-- Per-instance state
state = {
    rotation = 0.0,
    platform_y = { 0.2, 0.35, 0.5, 0.65, 0.75 },
    bounce = { 0.0, 0.0, 0.0, 0.0, 0.0 },
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation + 0.5 * dt

    -- Update platform heights based on bands
    for band = 1, 5 do
        local target_y = 0.15 + (band - 1) * 0.14 + audio.bands[band] * 0.15
        state.platform_y[band] = smooth(state.platform_y[band], target_y, 0.1, dt)

        -- Bounce on beat
        if audio.is_beat and band <= 3 then
            state.bounce[band] = 0.15
        end
        state.bounce[band] = decay(state.bounce[band], 0.9, dt)
    end

    -- Distribute entities across 5 platforms
    local entities_per_platform = math.max(1, math.floor(n / 5))

    for band = 1, 5 do
        local platform_angle = state.rotation + (band - 1) * (math.pi * 2 / 5)
        local platform_radius = 0.2 + audio.bands[band] * 0.1

        for j = 0, entities_per_platform - 1 do
            local entity_idx = (band - 1) * entities_per_platform + j
            if entity_idx >= n then
                break
            end

            -- Spread blocks within platform
            local offset_angle = (j / entities_per_platform) * math.pi * 0.5 - math.pi * 0.25
            local angle = platform_angle + offset_angle

            -- Position
            local spread = 0.03 + audio.bands[band] * 0.02
            local x = center + math.cos(angle) * (platform_radius + j * spread * 0.3)
            local z = center + math.sin(angle) * (platform_radius + j * spread * 0.3)
            local y = state.platform_y[band] + state.bounce[band]

            local scale = config.base_scale + audio.bands[band] * 0.5

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                band = band - 1,
                visible = true,
            }
        end
    end

    return entities
end
