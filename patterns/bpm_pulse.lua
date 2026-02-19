-- Pattern metadata
name = "BPM Pulse"
description = "Sphere of blocks that pulse in sync with the beat phase"
category = "Original"
static_camera = false
recommended_entities = 64

-- Per-instance state
state = {
    smooth_bass = 0.0,
    smooth_mid = 0.0,
    rotation_y = 0.0,
}

function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    -- Smooth audio inputs
    state.smooth_bass = smooth(state.smooth_bass, audio.bands[1], 0.4, dt)
    state.smooth_mid = smooth(state.smooth_mid, audio.bands[3], 0.3, dt)

    -- Slow rotation, slightly faster with energy
    state.rotation_y = state.rotation_y + (0.3 + audio.amplitude * 0.8) * dt

    -- BPM-synced pulse: sharp attack on beat, smooth decay
    local pulse = beat_pulse(audio.beat_phase, 1, 5.0)
    -- Half-time pulse for variety
    local half_pulse = beat_pulse(audio.beat_phase, 0.5, 3.0)

    -- Generate sphere points
    local points = fibonacci_sphere(n)

    for i = 1, n do
        local p = points[i]

        -- Base sphere radius expands with pulse
        local base_radius = 0.18 + pulse * 0.1 + state.smooth_bass * 0.06

        -- Alternate layers use half-time pulse for polyrhythmic feel
        local layer_pulse = pulse
        if i % 3 == 0 then
            layer_pulse = half_pulse
        end

        -- Scale radius per-entity with its layer pulse
        local radius = base_radius + layer_pulse * 0.04

        -- Apply rotation around Y axis
        local cos_r = math.cos(state.rotation_y)
        local sin_r = math.sin(state.rotation_y)
        local rx = p.x * cos_r - p.z * sin_r
        local rz = p.x * sin_r + p.z * cos_r

        local x = center + rx * radius
        local y = center + p.y * radius
        local z = center + rz * radius

        -- Scale pulses with beat phase
        local base_scale = config.base_scale + state.smooth_mid * 0.15
        local scale = base_scale + layer_pulse * 0.25

        -- Beat intensity boost
        if audio.is_beat then
            scale = scale * 1.3
        end

        local band_idx = (i % 5)

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            rotation = (pulse * 90 + i * 7) % 360,
            band = band_idx,
            visible = true,
        }
    end

    return entities
end
