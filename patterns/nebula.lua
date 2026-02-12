-- Pattern metadata
name = "Nebula"
description = "Cosmic gas cloud with drifting particles"

-- Per-instance state
state = {
    particles = {},        -- {x, y, z, phase}
    expansion = 1.0,
    flash_particles = {},  -- set: flash_particles[i] = true
    drift_time = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local n = config.entity_count
    local center = 0.5

    -- Initialize particles in spherical distribution
    if #state.particles ~= n then
        state.particles = {}
        local points = fibonacci_sphere(n)
        for i = 1, n do
            local p = points[i]
            -- Randomize radius for volume (not just surface)
            local r = 0.3 + math.random() * 0.7  -- 0.3 to 1.0 of max
            local phase = math.random() * math.pi * 2
            state.particles[i] = {x = p.x * r, y = p.y * r, z = p.z * r, phase = phase}
        end
    end

    state.drift_time = state.drift_time + dt

    -- Expansion with amplitude
    local target_expansion = 0.8 + audio.amplitude * 0.4
    state.expansion = state.expansion + (target_expansion - state.expansion) * 0.1

    -- Beat triggers star flashes (random subset)
    if audio.is_beat then
        state.flash_particles = {}
        local flash_count = math.min(math.floor(n / 4), 20)
        -- Pick random indices to flash
        for _ = 1, flash_count do
            local idx = math.random(1, n)
            state.flash_particles[idx] = true
        end
    else
        -- Decay flash set
        if math.random() < 0.3 then
            state.flash_particles = {}
        end
    end

    local entities = {}
    local base_radius = 0.25 * state.expansion

    for i = 1, n do
        local p = state.particles[i]
        local px, py, pz, phase = p.x, p.y, p.z, p.phase

        -- Smooth drifting motion using noise-like movement
        local drift_x = math.sin(state.drift_time * 0.3 + phase) * 0.02
        local drift_y = math.cos(state.drift_time * 0.2 + phase * 1.3) * 0.02
        local drift_z = math.sin(state.drift_time * 0.25 + phase * 0.7) * 0.02

        -- Update particle position with drift
        state.particles[i].x = px + drift_x * 0.1
        state.particles[i].y = py + drift_y * 0.1
        state.particles[i].z = pz + drift_z * 0.1

        -- Keep within bounds (soft boundary)
        local dist = math.sqrt(px * px + py * py + pz * pz)
        if dist > 1.2 then
            -- Pull back toward center
            state.particles[i].x = state.particles[i].x * 0.99
            state.particles[i].y = state.particles[i].y * 0.99
            state.particles[i].z = state.particles[i].z * 0.99
        end

        -- World position
        local x = center + px * base_radius + drift_x
        local y = center + py * base_radius + drift_y
        local z = center + pz * base_radius + drift_z

        -- Band based on position (creates color gradients)
        -- Higher Y = higher frequency colors
        local normalized_y = (py + 1) / 2  -- 0 to 1
        local band_idx = math.floor(normalized_y * 4.9)
        band_idx = math.max(0, math.min(4, band_idx))

        -- Scale based on density (denser near center)
        local density_scale = 1.0 - dist * 0.3
        local scale = config.base_scale * density_scale
        scale = scale + audio.bands[band_idx + 1] * 0.25

        -- Flash effect for "star birth"
        if state.flash_particles[i] then
            scale = scale * 2.0
            band_idx = 4  -- Bright white/high freq
        end

        if audio.is_beat then
            scale = scale * 1.15
        end

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x),
            y = clamp(y),
            z = clamp(z),
            scale = math.min(config.max_scale, math.max(0.05, scale)),
            band = band_idx,
            visible = true,
        }
    end

    return entities
end
