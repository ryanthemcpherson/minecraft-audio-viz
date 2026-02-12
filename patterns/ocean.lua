-- Pattern metadata
name = "Ocean Waves"
description = "Water surface with splashes and ripples"
category = "Organic"
static_camera = false

-- Per-instance state
state = {
    wave_time = 0.0,
    splashes = {},  -- {x, z, time, intensity}
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.wave_time = state.wave_time + dt

    -- Beat triggers splash
    if audio.is_beat then
        local splash_x = 0.2 + math.random() * 0.6
        local splash_z = 0.2 + math.random() * 0.6
        state.splashes[#state.splashes + 1] = {
            x = splash_x, z = splash_z, time = 0.0, intensity = audio.beat_intensity
        }
    end

    -- Update splashes (fade over 3 seconds)
    local new_splashes = {}
    for _, splash in ipairs(state.splashes) do
        splash.time = splash.time + dt
        if splash.time < 3.0 then
            new_splashes[#new_splashes + 1] = splash
        end
    end
    state.splashes = new_splashes

    -- Create grid
    local grid_size = math.floor(math.sqrt(n))
    if grid_size < 2 then grid_size = 2 end

    for i = 0, grid_size - 1 do
        for j = 0, grid_size - 1 do
            local idx = i * grid_size + j
            if idx >= n then break end

            -- Grid position
            local x_norm = i / (grid_size - 1)
            local z_norm = j / (grid_size - 1)

            local x = 0.1 + x_norm * 0.8
            local z = 0.1 + z_norm * 0.8

            -- === Wave interference ===
            -- Large waves (bass)
            local wave_bass = math.sin(x_norm * math.pi * 2 + state.wave_time * 0.5) * 0.08
            wave_bass = wave_bass + math.sin(z_norm * math.pi * 1.5 + state.wave_time * 0.3) * 0.06
            wave_bass = wave_bass * (0.5 + audio.bands[1] * 1.5)

            -- Medium waves (mids)
            local wave_mid = math.sin(x_norm * math.pi * 4 + state.wave_time * 1.2) * 0.04
            wave_mid = wave_mid + math.sin(z_norm * math.pi * 3 - state.wave_time * 0.8) * 0.03
            wave_mid = wave_mid * (0.3 + audio.bands[3] + audio.bands[4])

            -- Small shimmer (highs)
            local wave_high = math.sin(x_norm * math.pi * 8 + state.wave_time * 3) * 0.015
            wave_high = wave_high + math.sin(z_norm * math.pi * 7 - state.wave_time * 2.5) * 0.01
            wave_high = wave_high * (0.2 + audio.bands[5] * 2 + audio.bands[5] * 2)

            -- Combine waves
            local y = center + wave_bass + wave_mid + wave_high

            -- === Ripple rings from splashes ===
            local ripple_height = 0
            for _, splash in ipairs(state.splashes) do
                local dist = math.sqrt((x - splash.x) ^ 2 + (z - splash.z) ^ 2)
                local ripple_radius = splash.time * 0.3  -- Expands over time
                local ripple_width = 0.08

                -- Check if point is near ripple ring
                if math.abs(dist - ripple_radius) < ripple_width then
                    -- Ripple strength fades over time
                    local fade = 1.0 - (splash.time / 3.0)
                    local ring_strength = 1.0 - math.abs(dist - ripple_radius) / ripple_width
                    ripple_height = ripple_height +
                        math.sin(dist * 20 - splash.time * 10) * 0.1 * splash.intensity * fade * ring_strength
                end
            end

            y = y + ripple_height

            -- Band based on wave activity
            local wave_activity = math.abs(wave_bass) + math.abs(wave_mid) + math.abs(wave_high)
            local band_idx = math.floor(wave_activity * 10) % 5

            local scale = config.base_scale + wave_activity * 2
            scale = scale + ripple_height * 3  -- Splashes make bigger particles

            if audio.is_beat then
                scale = scale * 1.2
            end

            entities[#entities + 1] = {
                id = string.format("block_%d", idx),
                x = clamp(x),
                y = clamp(y),
                z = clamp(z),
                scale = math.min(config.max_scale, scale),
                band = band_idx,
                visible = true,
            }
        end
    end

    return entities
end
