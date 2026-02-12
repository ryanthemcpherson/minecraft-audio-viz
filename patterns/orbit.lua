-- Pattern metadata
name = "Atom Model"
description = "Nucleus + electrons on 3D orbital planes"

-- Per-instance state
state = {
    orbit_angles = {},
    nucleus_pulse = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Ensure orbit angles array matches entity count
    while #state.orbit_angles < n do
        state.orbit_angles[#state.orbit_angles + 1] = math.random() * math.pi * 2
    end

    -- Nucleus pulse
    if audio.is_beat then
        state.nucleus_pulse = 1.0
    end
    state.nucleus_pulse = state.nucleus_pulse * 0.9

    -- Nucleus: first 4 blocks clustered at center
    local nucleus_count = math.min(4, n)
    local nucleus_spread = 0.03 + state.nucleus_pulse * 0.05 + audio.bands[1] * 0.03

    for i = 1, nucleus_count do
        local angle = ((i - 1) / nucleus_count) * math.pi * 2
        local x = center + math.cos(angle) * nucleus_spread
        local z = center + math.sin(angle) * nucleus_spread
        local y = center + math.sin(angle * 2) * nucleus_spread * 0.5

        local scale = config.base_scale + audio.bands[1] * 0.4 + state.nucleus_pulse * 0.3

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            band = 0,
            visible = true,
        }
    end

    -- Electrons: remaining blocks on 3 orbital planes
    local electron_count = n - nucleus_count
    local electrons_per_orbit = math.max(1, math.floor(electron_count / 3))

    local orbit_tilts = { { 0, 0 }, { math.pi / 3, 0 }, { 0, math.pi / 3 } }
    local orbit_speeds = { 1.0, 1.3, 0.8 }

    for orbit = 1, 3 do
        local tilt_x = orbit_tilts[orbit][1]
        local tilt_z = orbit_tilts[orbit][2]
        local speed = orbit_speeds[orbit] * (1 + audio.amplitude)

        if audio.is_beat then
            speed = speed * 1.5
        end

        for j = 0, electrons_per_orbit - 1 do
            local entity_idx = nucleus_count + (orbit - 1) * electrons_per_orbit + j
            if entity_idx >= n then
                break
            end

            -- Update orbit angle (1-indexed in state)
            local state_idx = entity_idx + 1
            state.orbit_angles[state_idx] = state.orbit_angles[state_idx] + speed * dt

            local angle = state.orbit_angles[state_idx] + (j / electrons_per_orbit) * math.pi * 2
            local radius = 0.2 + (orbit - 1) * 0.08

            -- Base position on XZ plane
            local px = math.cos(angle) * radius
            local py = 0
            local pz = math.sin(angle) * radius

            -- Apply tilts
            -- Tilt around X axis
            local cos_tx = math.cos(tilt_x)
            local sin_tx = math.sin(tilt_x)
            local py2 = py * cos_tx - pz * sin_tx
            local pz2 = py * sin_tx + pz * cos_tx

            -- Tilt around Z axis
            local cos_tz = math.cos(tilt_z)
            local sin_tz = math.sin(tilt_z)
            local px2 = px * cos_tz - py2 * sin_tz
            local py3 = px * sin_tz + py2 * cos_tz

            local x = center + px2
            local y = center + py3
            local z = center + pz2

            local band_idx = ((orbit - 1 + 2) % 5) + 1
            local scale = config.base_scale + audio.bands[band_idx] * 0.3

            entities[#entities + 1] = {
                id = string.format("block_%d", entity_idx),
                x = clamp(x, 0, 1),
                y = clamp(y, 0, 1),
                z = clamp(z, 0, 1),
                scale = math.min(config.max_scale, scale),
                band = band_idx - 1,
                visible = true,
            }
        end
    end

    return entities
end
