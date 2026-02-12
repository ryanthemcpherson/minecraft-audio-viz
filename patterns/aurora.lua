-- Pattern metadata
name = "Aurora"
description = "Northern lights curtains - flowing waves"

-- Per-instance state
state = {
    wave_time = 0.0,
    ripple_origins = {},  -- {x, time} pairs
    color_offset = 0.0,
}

-- Main calculation function
function calculate(audio, config, dt)
    local entities = {}
    local n = config.entity_count
    local center = 0.5

    state.wave_time = state.wave_time + dt

    -- Color sweep
    state.color_offset = state.color_offset + audio.amplitude * 0.02 + 0.005

    -- Beat triggers new ripple
    if audio.is_beat then
        local ripple_x = math.random()  -- Random horizontal position
        state.ripple_origins[#state.ripple_origins + 1] = {x = ripple_x, time = 0.0}
    end

    -- Update ripples
    local new_ripples = {}
    for _, ripple in ipairs(state.ripple_origins) do
        ripple.time = ripple.time + 0.03 + audio.amplitude * 0.02  -- Ripple expands
        if ripple.time < 2.0 then  -- Keep ripple alive for ~2 units
            new_ripples[#new_ripples + 1] = ripple
        end
    end
    state.ripple_origins = new_ripples

    -- 3 curtain layers with different depths
    local num_layers = 3
    local points_per_layer = math.floor(n / num_layers)

    for layer = 0, num_layers - 1 do
        local layer_depth = 0.3 + layer * 0.2  -- Z position for parallax
        local layer_speed = 1.0 + layer * 0.3  -- Different wave speeds

        for j = 0, points_per_layer - 1 do
            local idx = layer * points_per_layer + j
            if idx >= n then break end

            -- Horizontal position across curtain
            local x_norm = j / math.max(1, points_per_layer - 1)
            local x = 0.1 + x_norm * 0.8

            -- Flowing wave motion (multiple sine waves)
            local wave1 = math.sin(x_norm * math.pi * 3 + state.wave_time * layer_speed) * 0.1
            local wave2 = math.sin(x_norm * math.pi * 5 - state.wave_time * 0.7 * layer_speed) * 0.05
            local wave3 = math.sin(x_norm * math.pi * 2 + state.wave_time * 1.3) * 0.08

            -- Combine waves
            local wave_offset = wave1 + wave2 + wave3

            -- Add ripple effects from beats
            local ripple_offset = 0
            for _, ripple in ipairs(state.ripple_origins) do
                local dist = math.abs(x_norm - ripple.x)
                if dist < ripple.time and ripple.time - dist < 0.5 then
                    local ripple_strength = (0.5 - (ripple.time - dist)) * 2
                    ripple_offset = ripple_offset +
                        math.sin((ripple.time - dist) * math.pi * 4) * 0.1 * ripple_strength
                end
            end

            -- Y position (curtains hang from top)
            local base_y = 0.7 - layer * 0.1
            local y = base_y + wave_offset + ripple_offset

            -- Height variation based on audio
            -- layer*2: layer 0->band 1, layer 1->band 3, layer 2->band 5
            local audio_band = math.min(layer * 2 + 1, 5)
            y = y + audio.bands[audio_band] * 0.15

            local z = center + (layer_depth - 0.5)

            -- Color band based on horizontal position + sweep
            local color_pos = (x_norm + state.color_offset) % 1.0
            local band_idx = math.floor(color_pos * 4.9)
            band_idx = math.max(0, math.min(4, band_idx))

            -- Scale pulses with corresponding band
            local scale = config.base_scale + audio.bands[band_idx + 1] * 0.35
            scale = scale + ripple_offset * 2  -- Ripples make particles larger

            if audio.is_beat then
                scale = scale * 1.25
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
