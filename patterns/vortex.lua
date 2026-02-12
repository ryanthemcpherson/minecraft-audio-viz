name = "Vortex"
description = "Swirling tunnel - spiral into infinity"
category = "Epic"
static_camera = false
state = {}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.rotation = state.rotation or 0
    state.z_offset = state.z_offset or 0
    state.intensity = state.intensity or 0

    -- Faster rotation with energy
    local speed = 2.4 + audio.peak * 3.4
    if audio.beat then
        speed = speed * 1.5
        state.intensity = 1.0
    end
    state.rotation = state.rotation + speed * dt
    state.intensity = decay(state.intensity, 0.95, dt)

    -- Z movement (flying through tunnel)
    state.z_offset = state.z_offset + (0.55 + audio.bands[1] * 0.65) * dt
    if state.z_offset > 1.0 then
        state.z_offset = state.z_offset - 1.0
    end

    -- Rings of entities forming tunnel
    local rings = math.max(5, math.floor(n / 8))
    local per_ring = math.floor(n / rings)

    for ring = 0, rings - 1 do
        -- Depth (z position)
        local ring_z = (ring / rings + state.z_offset) % 1.0
        local depth = ring_z

        -- Ring radius - smaller as it goes further
        local base_radius = 0.36 - depth * 0.26
        local pulse_radius = base_radius + state.intensity * 0.12 * (1 - depth)

        -- Ring rotation
        local ring_rotation = state.rotation * (1.0 + ring * 0.2)

        for j = 0, per_ring - 1 do
            local idx = ring * per_ring + j
            if idx >= n then
                break
            end

            local angle = ring_rotation + (j / per_ring) * math.pi * 2

            -- Wobble
            local wobble = math.sin(angle * 3 + state.rotation * 2) * 0.02
            local radius = pulse_radius + wobble

            -- Audio reactivity per band
            local band_idx = j % 5
            radius = radius + audio.bands[band_idx + 1] * 0.05 * (1 - depth)

            local x = center + math.cos(angle) * radius
            local z_pos = center + math.sin(angle) * radius
            local wave = math.sin(state.rotation + depth * math.pi * 2) * 0.04
            local y = 0.12 + depth * 0.78 + wave

            -- Scale - larger when close
            local scale = config.base_scale * (1.55 - depth) + audio.bands[band_idx + 1] * 0.35

            if audio.beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z_pos),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
        end
    end

    return entities
end
