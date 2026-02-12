name = "Mandala"
description = "Sacred geometry rings - frequency mapped"
category = "Cosmic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    if not state.ring_rotations then
        state.ring_rotations = {0, 0, 0, 0, 0}
    end
    state.pulse = state.pulse or 0
    state.petal_boost = state.petal_boost or 0

    -- Beat pulse
    if audio.beat then
        state.pulse = 1.0
        state.petal_boost = 1.0
    end
    state.pulse = decay(state.pulse, 0.9, dt)
    state.petal_boost = decay(state.petal_boost, 0.85, dt)

    -- Golden angle for petal distribution
    local golden_angle = math.pi * (3.0 - math.sqrt(5.0))

    -- Distribute entities across 5 rings
    local points_per_ring = math.floor(n / 5)
    local entity_idx = 0

    for ring = 0, 4 do
        -- Ring properties
        local base_radius = 0.08 + ring * 0.07

        -- Radius pulses with corresponding frequency band
        local radius = base_radius + audio.bands[ring + 1] * 0.04 + state.pulse * 0.02

        -- Rotation speed: inner slower, outer faster, alternating direction
        local direction = 1
        if ring % 2 == 1 then
            direction = -1
        end
        local speed = (0.2 + ring * 0.18) * direction
        speed = speed * (1.0 + audio.bands[ring + 1] * 0.5)

        state.ring_rotations[ring + 1] = state.ring_rotations[ring + 1] + speed * dt

        -- Points on this ring
        for j = 0, points_per_ring - 1 do
            if entity_idx >= n then
                break
            end

            -- Golden angle distribution
            local angle = state.ring_rotations[ring + 1] + j * golden_angle

            local x = center + math.cos(angle) * radius
            local z = center + math.sin(angle) * radius

            -- Y varies slightly for 3D depth
            local y = center + math.sin(angle * 2) * 0.02 * (1 + ring * 0.3)

            -- Scale based on band intensity
            local scale = config.base_scale + audio.bands[ring + 1] * 0.5
            scale = scale + state.petal_boost * 0.3 * (1.0 - ring / 5)

            if audio.beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                band = ring,
                visible = true,
            }
            entity_idx = entity_idx + 1
        end
    end

    return entities
end
