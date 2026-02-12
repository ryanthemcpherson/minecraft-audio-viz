-- Pattern metadata
name = "Wormhole Portal"
description = "Infinite tunnel - rings fly toward you"

-- Per-instance state
state = {
    rotation = 0.0,
    tunnel_offset = 0.0,
    flash = 0.0,
    pulse = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Rotation speed driven by energy
    local energy = 0
    for b = 1, 5 do
        energy = energy + audio.bands[b]
    end
    energy = energy / 5

    state.rotation = state.rotation + (1.1 + energy * 2.2) * dt

    -- Tunnel movement speed (flying through)
    local speed = 0.32 + audio.bands[1] * 0.45 + audio.amplitude * 0.35
    state.tunnel_offset = state.tunnel_offset + speed * dt
    if state.tunnel_offset > 1.0 then
        state.tunnel_offset = state.tunnel_offset - 1.0
    end

    -- Beat flash
    if audio.is_beat then
        state.flash = 1.0
        state.pulse = 0.3
    end
    state.flash = state.flash * 0.85
    state.pulse = state.pulse * 0.9

    -- Create rings at different depths
    local num_rings = math.max(6, math.floor(n / 10))
    local points_per_ring = math.floor(n / num_rings)

    for ring = 0, num_rings - 1 do
        -- Depth cycles through with tunnel offset
        local ring_depth = ((ring / num_rings) + state.tunnel_offset) % 1.0

        -- Perspective: close rings are larger, far rings are smaller
        local perspective = 1.0 - ring_depth * 0.8
        local ring_radius = 0.05 + perspective * 0.36

        -- Breathing with amplitude
        ring_radius = ring_radius + audio.amplitude * 0.05 * perspective
        ring_radius = ring_radius + math.sin(state.rotation + ring_depth * math.pi * 3) * 0.02 * perspective

        -- Ring rotation - different speeds for each depth
        local ring_rotation = state.rotation * (1.0 + ring_depth * 0.5)

        -- Y position maps to depth (close = low y, far = high y for upward tunnel)
        local base_y = 0.1 + ring_depth * 0.8
        base_y = base_y + math.sin(state.rotation * 1.3 + ring_depth * math.pi * 2) * 0.03

        for j = 0, points_per_ring - 1 do
            local idx = ring * points_per_ring + j
            if idx >= n then break end

            local angle = ring_rotation + (j / points_per_ring) * math.pi * 2

            -- Spiral twist increases with depth
            local twist = ring_depth * math.pi * 0.5
            angle = angle + twist

            local x = center + math.cos(angle) * ring_radius
            local z = center + math.sin(angle) * ring_radius
            local y = base_y

            -- Band-reactive radius wobble
            local band_idx = j % 5
            local wobble = audio.bands[band_idx + 1] * 0.03 * perspective
            x = x + math.cos(angle) * wobble
            z = z + math.sin(angle) * wobble

            -- Scale - larger when close, affected by flash
            local scale = config.base_scale * perspective
            scale = scale + audio.bands[band_idx + 1] * 0.35 * perspective

            -- Flash effect on close rings
            if ring_depth < 0.3 then
                scale = scale + state.flash * 0.4 * (0.3 - ring_depth)
            end

            if audio.is_beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, math.max(0.05, scale)),
                band = band_idx,
                visible = true,
            }
        end
    end

    return entities
end
