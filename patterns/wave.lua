-- Pattern metadata
name = "DNA Helix"
description = "Double helix spiral - rotates and stretches"
category = "Original"
static_camera = false
start_blocks = 90

-- Per-instance state
state = {
    rotation = 0.0,
    stretch = 1.0,
    beat_pulse = 0.0,
    phase = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5
    if n <= 0 then
        return entities
    end

    -- Rotation speed based on energy
    local speed = 0.85 + audio.amplitude * 2.2
    if audio.is_beat then
        speed = speed * 1.5
        state.beat_pulse = 1.0
    end
    state.rotation = state.rotation + speed * dt
    state.phase = state.phase + (0.45 + audio.bands[4] * 0.9) * dt
    state.beat_pulse = decay(state.beat_pulse, 0.84, dt)

    -- Stretch based on bass
    local target_stretch = 0.8 + audio.bands[1] * 0.6 + audio.bands[2] * 0.4
    state.stretch = smooth(state.stretch, target_stretch, 0.13, dt)

    -- Helix parameters
    local radius = 0.12 + audio.amplitude * 0.12 + state.beat_pulse * 0.035
    local pitch = 0.08 * state.stretch

    for i = 1, n do
        -- Alternate between two helixes (0-indexed logic)
        local helix = (i - 1) % 2
        local idx = math.floor((i - 1) / 2)

        -- Position along helix
        local half_n = n / 2
        local t = idx / half_n * math.pi * 3.4  -- 3.4 turns for denser helix
        local angle = t + state.rotation + (helix * math.pi)  -- Offset second helix by 180 degrees

        -- Helix coordinates
        local wobble = math.sin(t * 1.7 + state.phase) * (0.012 + audio.bands[3] * 0.02)
        local local_radius = radius + wobble
        local x = center + math.cos(angle) * local_radius
        local z = center + math.sin(angle) * local_radius
        local y = 0.08 + (idx / half_n) * 0.84 + pitch * math.sin(t + state.phase * 0.4)

        -- Pulse radius with band
        local band_idx = (idx % 5) + 1
        local pulse = audio.bands[band_idx] * 0.05
        x = x + math.cos(angle) * pulse
        z = z + math.sin(angle) * pulse

        local scale = config.base_scale * 0.85 + audio.bands[band_idx] * 0.45
        if audio.is_beat then
            scale = scale * 1.22
        end
        scale = scale + state.beat_pulse * 0.08
        local rot = (angle + y * math.pi * 2.0 + helix * 0.4) * 180 / math.pi

        entities[#entities + 1] = {
            id = string.format("block_%d", i - 1),
            x = clamp(x, 0, 1),
            y = clamp(y, 0, 1),
            z = clamp(z, 0, 1),
            scale = math.min(config.max_scale, scale),
            rotation = rot % 360,
            band = band_idx - 1,
            visible = true,
        }
    end

    return entities
end
